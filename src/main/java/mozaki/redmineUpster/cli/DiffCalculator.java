package mozaki.redmineUpster.cli;

import static mozaki.redmineUpster.cli.SyncConstants.ACTION_CREATE;
import static mozaki.redmineUpster.cli.SyncConstants.ACTION_DELETE;
import static mozaki.redmineUpster.cli.SyncConstants.ACTION_UPDATE;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_CLOSED;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_IN_PROGRESS;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_MODE_BY_DATES;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_MODE_FIXED;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_NEW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Set;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import mozaki.redmineUpster.config.SyncConfigProperties.ColumnsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.domain.IssueLinkEntity;
import mozaki.redmineUpster.repository.IssueLinkRepository;
import mozaki.redmineUpster.util.ColumnDefinitions;
import mozaki.redmineUpster.util.DateParser;
import mozaki.redmineUpster.util.StringUtils;

/**
 * 差分計算クラス。
 * <p>
 * CSV/Excelの行データから差分アイテムを計算します。
 * DBに保存せずインメモリで差分を計算し、{@link DiffItem}のリストを返します。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class DiffCalculator {

    private final IssueLinkRepository issueLinkRepository;

    /**
     * 差分アイテムを計算します。
     * <p>
     * 各行のexternal_keyを基にIssueLinkRepositoryを参照し、
     * CREATE（新規）またはUPDATE（更新）のアクションを決定します。
     * </p>
     *
     * @param rows CSV/Excelから解析された行データ
     * @param projectConfig プロジェクト設定
     * @param logger ファイルロガー
     * @return 差分アイテムのリスト
     */
    public List<DiffItem> calculate(
            List<Map<String, String>> rows,
            ProjectConfig projectConfig,
            FileLogger logger,
            boolean relinkOnly,
            boolean resetSync) {

        StatusConfig statusConfig = null;
        if (projectConfig.getSync() != null) {
            statusConfig = projectConfig.getSync().getStatus();
        }
        String projectId = null;
        if (projectConfig.getRedmine() != null) {
            projectId = projectConfig.getRedmine().getProjectId();
        }

        Set<String> availableColumns = rows.isEmpty() ? Set.of() : rows.get(0).keySet();
        List<String> hierarchyColumns = getHierarchyColumns(projectConfig, availableColumns);
        List<String> customFieldColumns = getCustomFieldColumns(projectConfig);
        String externalKeyColumn = getExternalKeyColumn(projectConfig, availableColumns);
        String startDateColumn = getStartDateColumn(projectConfig);
        String dueDateColumn = getDueDateColumn(projectConfig);
        String statusColumn = getStatusColumn(projectConfig);
        String progressColumn = getProgressColumn(projectConfig);

        List<RowData> parsed = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String externalKey = value(row, externalKeyColumn);
            if (externalKey.isBlank()) {
                continue;
            }
            HierarchyData hierarchyData = hierarchyValues(row, hierarchyColumns);
            List<String> hierarchy = hierarchyData.values();
            List<String> hierarchyColumnsUsed = hierarchyData.columns();
            String subject = resolveSubject(row, hierarchy);
            String levelPath = String.join(" > ", hierarchy);
            List<String> parentHierarchy = hierarchy.size() > 1 ? hierarchy.subList(0, hierarchy.size() - 1) : List.of();
            List<String> parentHierarchyColumns = hierarchyColumnsUsed.size() > 1
                    ? hierarchyColumnsUsed.subList(0, hierarchyColumnsUsed.size() - 1)
                    : List.of();
            String parentPath = String.join(" > ", parentHierarchy);
            RowData data = new RowData(externalKey, subject, levelPath, parentPath, row,
                    startDateColumn, dueDateColumn, statusColumn, progressColumn, hierarchyColumnsUsed, hierarchy,
                    parentHierarchyColumns, parentHierarchy);
            parsed.add(data);

            // デバッグログ: 階層パスの生成結果
            if (logger != null) {
                logger.debug("Hierarchy path: " + levelPath);
            }
        }

        Map<String, String> pathToExternalKey = new HashMap<>();
        Set<String> existingExternalKeys = new LinkedHashSet<>();
        Set<String> requiredExternalKeys = new LinkedHashSet<>();
        for (RowData rowData : parsed) {
            existingExternalKeys.add(rowData.externalKey);
            requiredExternalKeys.add(rowData.externalKey);
            requiredExternalKeys.addAll(resolveParentKeys(rowData.externalKey));
            if (!rowData.levelPath.isBlank() && !pathToExternalKey.containsKey(rowData.levelPath)) {
                pathToExternalKey.put(rowData.levelPath, rowData.externalKey);
            }
        }

        Map<String, String> customFieldMap = getCustomFieldMap(projectConfig);
        List<DiffItem> items = new ArrayList<>();
        Map<String, ParentAggregate> virtualParents = new LinkedHashMap<>();

        for (RowData rowData : parsed) {
            String parentKey = pathToExternalKey.get(rowData.parentPath);
            if (parentKey == null || parentKey.isBlank()) {
                parentKey = inferParentKeyFromExternalKey(rowData.externalKey);
            }
            if ((parentKey == null || parentKey.isBlank()) && logger != null) {
                String segs = rowData.externalKey == null ? "" : String.join(",", rowData.externalKey.split("\\."));
                logger.debug("ParentKey null for externalKey=" + rowData.externalKey
                        + " parentPath=[" + rowData.parentPath + "] levelPath=[" + rowData.levelPath + "]"
                        + " hierarchyValues=" + rowData.hierarchyValues
                        + " hierarchyCols=" + rowData.hierarchyColumnsUsed
                        + " segments=" + segs);
            }
            String action = resetSync ? ACTION_CREATE : resolveAction(rowData.externalKey, logger);
            String status = resolveStatus(rowData, projectConfig);
            Map<String, Object> payload = buildPayload(rowData, customFieldMap, customFieldColumns);

            DiffItem item = new DiffItem(
                rowData.externalKey,
                rowData.subject,
                parentKey,
                rowData.levelPath,
                action,
                status,
                payload
            );
            items.add(item);

            // デバッグログ: 親子関係の推定結果とアクション判定
            if (logger != null) {
                logger.debug("Parent key: " + (parentKey != null ? parentKey : "(none)"));
                logger.debug("Action: " + action + " (" + (ACTION_CREATE.equals(action) ? "no existing link" : "existing link found") + ")");
            }

            collectVirtualParents(rowData, existingExternalKeys, virtualParents, status, statusConfig, logger);
        }

        if (!virtualParents.isEmpty()) {
            // 仮想親は外部キーの数値的順序でソートして出力する（1,2,3,...）
            List<String> keys = new ArrayList<>(virtualParents.keySet());
            keys.sort(DiffCalculator::compareExternalKeys);
            for (String key : keys) {
                ParentAggregate parent = virtualParents.get(key);
                String action = resetSync ? ACTION_CREATE : resolveAction(parent.externalKey, logger);
                Integer trackerId = getVirtualParentTrackerId(projectConfig);
                Map<String, Object> payload = buildParentPayload(parent, trackerId, externalKeyColumn);
                payload.put("virtualParent", true);
                DiffItem parentItem = new DiffItem(
                        parent.externalKey,
                        parent.subject,
                        parent.parentKey,
                        parent.levelPath,
                        action,
                        parent.statusValue,
                        payload
                );
                items.add(parentItem);
                if (logger != null) {
                    logger.debug("Virtual parent: " + parent.externalKey + " path=" + parent.levelPath);
                }
            }
        }

        if (!relinkOnly) {
            List<DiffItem> deleteItems = resetSync
                    ? buildResetDeleteItems(projectId, logger)
                    : buildDeleteItems(requiredExternalKeys, projectId, logger);
            if (!deleteItems.isEmpty()) {
                items.addAll(deleteItems);
            }
        }

        // 最終的に外部キーを数値的自然順でソートして返す（1,2,3,...）
        items.sort((a, b) -> compareExternalKeys(a.externalKey(), b.externalKey()));

        return items;
    }

    private static int compareExternalKeys(String a, String b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        String[] sa = a.split("\\.");
        String[] sb = b.split("\\.");
        int n = Math.max(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            if (i >= sa.length) return -1;
            if (i >= sb.length) return 1;
            String pa = sa[i];
            String pb = sb[i];
            Integer ia = parseIntOrNull(pa);
            Integer ib = parseIntOrNull(pb);
            if (ia != null && ib != null) {
                int cmp = Integer.compare(ia, ib);
                if (cmp != 0) return cmp;
            } else {
                int cmp = pa.compareTo(pb);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * アクションを解決します（CREATE/UPDATE）。
     *
     * @param externalKey 外部キー
     * @param logger ファイルロガー
     * @return CREATE または UPDATE
     */
    private String resolveAction(String externalKey, FileLogger logger) {
        Optional<IssueLinkEntity> existing = issueLinkRepository.findByExternalKey(externalKey);
        return existing.isPresent() ? ACTION_UPDATE : ACTION_CREATE;
    }

    /**
     * 階層列の値を取得します。
     *
     * @param row 行データ
     * @param hierarchyColumns 階層列のリスト
     * @return 階層値のリスト
     */
    private HierarchyData hierarchyValues(Map<String, String> row, List<String> hierarchyColumns) {
        List<String> values = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        for (String column : hierarchyColumns) {
            String value = value(row, column);
            if (!value.isBlank()) {
                values.add(value);
                columns.add(column);
            }
        }
        return new HierarchyData(columns, values);
    }

    /**
     * プロジェクト設定から階層列を取得します。
     * 設定がない場合はデフォルト値を返します。
     *
     * @param projectConfig プロジェクト設定
     * @return 階層列のリスト
     */
    private List<String> getHierarchyColumns(ProjectConfig projectConfig, Set<String> availableColumns) {
        if (projectConfig.getSync() != null && projectConfig.getSync().getColumns() != null) {
            ColumnsConfig columns = projectConfig.getSync().getColumns();
            if (columns.getHierarchy() != null && !columns.getHierarchy().isEmpty()) {
                List<String> configured = columns.getHierarchy();
                if (hasAnyColumn(availableColumns, configured)) {
                    return configured;
                }
            }
        }
        List<String> detected = detectHierarchyColumns(availableColumns);
        if (!detected.isEmpty()) {
            return detected;
        }
        return ColumnDefinitions.HIERARCHY_COLUMNS;
    }

    /**
     * プロジェクト設定からカスタムフィールド対象列を取得します。
     * 設定がない場合はデフォルト値を返します。
     *
     * @param projectConfig プロジェクト設定
     * @return カスタムフィールド対象列のリスト
     */
    private List<String> getCustomFieldColumns(ProjectConfig projectConfig) {
        if (projectConfig.getSync() != null && projectConfig.getSync().getColumns() != null) {
            ColumnsConfig columns = projectConfig.getSync().getColumns();
            if (columns.getCustomFieldColumns() != null && !columns.getCustomFieldColumns().isEmpty()) {
                return columns.getCustomFieldColumns();
            }
        }
        return ColumnDefinitions.CUSTOM_FIELD_COLUMNS;
    }

    /**
     * プロジェクト設定から外部キー列名を取得します。
     * 設定がない場合はデフォルト値（"id"）を返します。
     *
     * @param projectConfig プロジェクト設定
     * @return 外部キー列名
     */
    private String getExternalKeyColumn(ProjectConfig projectConfig, Set<String> availableColumns) {
        if (projectConfig != null && projectConfig.getSync() != null
                && projectConfig.getSync().getColumns() != null) {
            String col = projectConfig.getSync().getColumns().getExternalKeyColumn();
            if (col != null && !col.isBlank() && (availableColumns.isEmpty() || availableColumns.contains(col))) {
                return col;
            }
        }
        for (String candidate : ColumnDefinitions.EXTERNAL_KEY_CANDIDATES) {
            if (availableColumns.contains(candidate)) {
                return candidate;
            }
        }
        return ColumnDefinitions.COL_ID;
    }

    private boolean hasAnyColumn(Set<String> availableColumns, List<String> candidateColumns) {
        if (availableColumns == null || availableColumns.isEmpty()) {
            return false;
        }
        for (String candidate : candidateColumns) {
            if (availableColumns.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private List<String> detectHierarchyColumns(Set<String> availableColumns) {
        if (availableColumns == null || availableColumns.isEmpty()) {
            return List.of();
        }

        List<String> defaultMatches = ColumnDefinitions.HIERARCHY_COLUMNS.stream()
                .filter(availableColumns::contains)
                .toList();
        List<String> legacyMatches = ColumnDefinitions.LEGACY_HIERARCHY_COLUMNS.stream()
                .filter(availableColumns::contains)
                .toList();

        if (legacyMatches.size() > defaultMatches.size()) {
            return legacyMatches;
        }
        if (!defaultMatches.isEmpty()) {
            return ColumnDefinitions.HIERARCHY_COLUMNS;
        }
        return legacyMatches;
    }

    /**
     * プロジェクト設定から開始日列名を取得します。
     * 設定がない場合はデフォルト値を返します。
     *
     * @param projectConfig プロジェクト設定
     * @return 開始日列名
     */
    private String getStartDateColumn(ProjectConfig projectConfig) {
        if (projectConfig != null && projectConfig.getSync() != null
                && projectConfig.getSync().getColumns() != null) {
            String col = projectConfig.getSync().getColumns().getStartDateColumn();
            if (col != null && !col.isBlank()) {
                return col;
            }
        }
        return ColumnDefinitions.COL_START_PLAN;
    }

    /**
     * プロジェクト設定から期限列名を取得します。
     * 設定がない場合はデフォルト値を返します。
     *
     * @param projectConfig プロジェクト設定
     * @return 期限列名
     */
    private String getDueDateColumn(ProjectConfig projectConfig) {
        if (projectConfig != null && projectConfig.getSync() != null
                && projectConfig.getSync().getColumns() != null) {
            String col = projectConfig.getSync().getColumns().getDueDateColumn();
            if (col != null && !col.isBlank()) {
                return col;
            }
        }
        return ColumnDefinitions.COL_DUE_PLAN;
    }

    /**
     * プロジェクト設定からステータス列名を取得します。
     * 設定がない場合はデフォルト値を返します。
     *
     * @param projectConfig プロジェクト設定
     * @return ステータス列名
     */
    private String getStatusColumn(ProjectConfig projectConfig) {
        if (projectConfig != null && projectConfig.getSync() != null
                && projectConfig.getSync().getColumns() != null) {
            String col = projectConfig.getSync().getColumns().getStatusColumn();
            if (col != null && !col.isBlank()) {
                return col;
            }
        }
        return ColumnDefinitions.COL_STATUS;
    }

    /**
     * プロジェクト設定から進捗率列名を取得します。
     * 設定がない場合はデフォルト値を返します。
     *
     * @param projectConfig プロジェクト設定
     * @return 進捗率列名
     */
    private String getProgressColumn(ProjectConfig projectConfig) {
        if (projectConfig != null && projectConfig.getSync() != null
                && projectConfig.getSync().getColumns() != null) {
            String col = projectConfig.getSync().getColumns().getProgressColumn();
            if (col != null && !col.isBlank()) {
                return col;
            }
        }
        return ColumnDefinitions.COL_PROGRESS;
    }

    /**
     * 件名を解決します。
     *
     * @param row 行データ
     * @param hierarchy 階層値
     * @return 件名
     */
    private String resolveSubject(Map<String, String> row, List<String> hierarchy) {
        String task = value(row, ColumnDefinitions.COL_TASK);
        if (!task.isBlank()) {
            return task;
        }
        if (!hierarchy.isEmpty()) {
            return hierarchy.get(hierarchy.size() - 1);
        }
        return "";
    }

    /**
     * ステータスを解決します。
     *
     * @param rowData 行データ
     * @param projectConfig プロジェクト設定
     * @return ステータス
     */
    private String resolveStatus(RowData rowData, ProjectConfig projectConfig) {
        StatusConfig statusConfig = null;
        if (projectConfig.getSync() != null) {
            statusConfig = projectConfig.getSync().getStatus();
        }

        if (statusConfig == null || !statusConfig.isEnabled()) {
            return null;
        }

        if (!rowData.statusValue.isBlank()) {
            return rowData.statusValue;
        }

        String mode = StringUtils.valueOrDefault(statusConfig.getMode(), STATUS_MODE_BY_DATES);
        if (STATUS_MODE_FIXED.equalsIgnoreCase(mode)) {
            return StringUtils.valueOrDefault(statusConfig.getFixed(), STATUS_NEW);
        }

        if (!rowData.dueActual.isBlank()) {
            return STATUS_CLOSED;
        }
        if (!rowData.startActual.isBlank()) {
            return STATUS_IN_PROGRESS;
        }
        return STATUS_NEW;
    }

    /**
     * カスタムフィールドマップを取得します。
     *
     * @param projectConfig プロジェクト設定
     * @return カスタムフィールドマップ
     */
    private Map<String, String> getCustomFieldMap(ProjectConfig projectConfig) {
        if (projectConfig.getSync() == null || projectConfig.getSync().getCustomFieldMap() == null) {
            return Map.of();
        }
        return projectConfig.getSync().getCustomFieldMap();
    }

    /**
     * ペイロードを構築します。
     *
     * @param rowData 行データ
     * @param customFieldMap カスタムフィールドマップ
     * @param customFieldColumns カスタムフィールド対象列のリスト
     * @return ペイロード
     */
    private Map<String, Object> buildPayload(RowData rowData, Map<String, String> customFieldMap, List<String> customFieldColumns) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignee", rowData.assignee);
        payload.put("startDate", rowData.startPlan);
        payload.put("dueDate", rowData.duePlan);
        payload.put("startActual", rowData.startActual);
        payload.put("dueActual", rowData.dueActual);
        if (rowData.progress != null) {
            payload.put("progress", rowData.progress);
        }

        Map<String, String> customFields = new LinkedHashMap<>();
        for (String column : customFieldColumns) {
            String mapping = customFieldMap.get(column);
            if (mapping == null) {
                continue;
            }
            String value = value(rowData.row, column);
            if (!value.isBlank()) {
                customFields.put(column, value);
            }
        }
        payload.put("customFields", customFields);

        return payload;
    }

    private Map<String, Object> buildParentPayload(ParentAggregate parent, Integer trackerId, String externalKeyColumn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (parent.startPlan != null) {
            payload.put("startDate", parent.startPlan);
        } else {
            payload.put("startDate", "");
        }
        if (parent.duePlan != null) {
            payload.put("dueDate", parent.duePlan);
        } else {
            payload.put("dueDate", "");
        }
        if (parent.startActual != null) {
            payload.put("startActual", parent.startActual);
        } else {
            payload.put("startActual", "");
        }
        if (parent.dueActual != null) {
            payload.put("dueActual", parent.dueActual);
        } else {
            payload.put("dueActual", "");
        }
        if (parent.progress != null) {
            payload.put("progress", parent.progress);
        }
        parent.customFieldValues.putIfAbsent(externalKeyColumn, parent.externalKey);
        payload.put("customFields", parent.customFieldValues);
        payload.put("trackerId", trackerId);
        return payload;
    }

    /**
     * 行から値を取得します。
     *
     * @param row 行データ
     * @param key キー
     * @return 値（トリム済み、nullの場合は空文字）
     */
    private static String value(Map<String, String> row, String key) {
        String raw = row.get(key);
        return raw == null ? "" : raw.trim();
    }

    /**
     * 行データの内部クラス。
     */
    private static class RowData {
        private final String externalKey;
        private final String subject;
        private final String levelPath;
        private final String parentPath;
        private final Map<String, String> row;
        private final String assignee;
        private final String startPlan;
        private final String duePlan;
        private final String startActual;
        private final String dueActual;
        private final String statusValue;
        private final Integer progress;
        private final List<String> hierarchyColumnsUsed;
        private final List<String> hierarchyValues;
        private final List<String> parentHierarchyColumns;
        private final List<String> parentHierarchyValues;

        private RowData(String externalKey, String subject, String levelPath, String parentPath,
                Map<String, String> row, String startDateColumn, String dueDateColumn, String statusColumn,
                String progressColumn,
                List<String> hierarchyColumnsUsed, List<String> hierarchyValues,
                List<String> parentHierarchyColumns, List<String> parentHierarchyValues) {
            this.externalKey = externalKey;
            this.subject = subject;
            this.levelPath = levelPath;
            this.parentPath = parentPath;
            this.row = row;
            this.assignee = value(row, ColumnDefinitions.COL_ASSIGNEE);
            this.startPlan = value(row, startDateColumn);
            this.duePlan = value(row, dueDateColumn);
            this.startActual = value(row, ColumnDefinitions.COL_START_ACTUAL);
            this.dueActual = value(row, ColumnDefinitions.COL_DUE_ACTUAL);
            this.statusValue = value(row, statusColumn);
            this.progress = parseProgress(value(row, progressColumn));
            this.hierarchyColumnsUsed = hierarchyColumnsUsed;
            this.hierarchyValues = hierarchyValues;
            this.parentHierarchyColumns = parentHierarchyColumns;
            this.parentHierarchyValues = parentHierarchyValues;
        }
    }

    private record HierarchyData(List<String> columns, List<String> values) {}

    private static class ParentAggregate {
        private final String externalKey;
        private final String levelPath;
        private final String subject;
        private final String parentKey;
        private final List<String> hierarchyColumns;
        private final List<String> hierarchyValues;
        private final Map<String, String> customFieldValues = new LinkedHashMap<>();
        private LocalDate minStart;
        private LocalDate maxDue;
        private String startPlan;
        private String duePlan;
        private LocalDate minStartActual;
        private LocalDate maxDueActual;
        private String startActual;
        private String dueActual;
        private int progressSum;
        private int progressCount;
        private Integer progress;
        private int totalChildren;
        private int inProgressCount;
        private int closedCount;
        private int newCount;
        private String statusValue;

        private ParentAggregate(String externalKey, String levelPath, String subject, String parentKey,
                List<String> hierarchyColumns, List<String> hierarchyValues) {
            this.externalKey = externalKey;
            this.levelPath = levelPath;
            this.subject = subject;
            this.parentKey = parentKey;
            this.hierarchyColumns = hierarchyColumns;
            this.hierarchyValues = hierarchyValues;
        }

        private void addChild(RowData rowData, ParentStatus status) {
            updateDates(rowData.startPlan, rowData.duePlan);
            updateActualDates(rowData.startActual, rowData.dueActual);
            updateProgress(rowData.progress);
            updateStatus(status);
            for (String column : hierarchyColumns) {
                String value = value(rowData.row, column);
                if (!value.isBlank() && !customFieldValues.containsKey(column)) {
                    customFieldValues.put(column, value);
                }
            }
        }

        private void updateDates(String startValue, String dueValue) {
            LocalDate start = parseDate(startValue);
            if (start != null && (minStart == null || start.isBefore(minStart))) {
                minStart = start;
                startPlan = minStart.format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            LocalDate due = parseDate(dueValue);
            if (due != null && (maxDue == null || due.isAfter(maxDue))) {
                maxDue = due;
                duePlan = maxDue.format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
        }

        private void updateActualDates(String startValue, String dueValue) {
            LocalDate start = parseDate(startValue);
            if (start != null && (minStartActual == null || start.isBefore(minStartActual))) {
                minStartActual = start;
                startActual = minStartActual.format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            LocalDate due = parseDate(dueValue);
            if (due != null && (maxDueActual == null || due.isAfter(maxDueActual))) {
                maxDueActual = due;
                dueActual = maxDueActual.format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
        }

        private void updateProgress(Integer value) {
            if (value == null) {
                return;
            }
            progressSum += value;
            progressCount++;
            progress = (int) Math.round(progressSum / (double) progressCount);
        }

        private void updateStatus(ParentStatus status) {
            totalChildren++;
            if (status == ParentStatus.CLOSED) {
                closedCount++;
            } else if (status == ParentStatus.IN_PROGRESS) {
                inProgressCount++;
            } else {
                newCount++;
            }
            if (inProgressCount > 0) {
                statusValue = STATUS_IN_PROGRESS;
                return;
            }
            if (closedCount == totalChildren) {
                statusValue = STATUS_CLOSED;
                return;
            }
            if (newCount == totalChildren) {
                statusValue = STATUS_NEW;
                return;
            }
            statusValue = STATUS_IN_PROGRESS;
        }
    }

    private Integer getVirtualParentTrackerId(ProjectConfig projectConfig) {
        if (projectConfig != null && projectConfig.getSync() != null) {
            Integer value = projectConfig.getSync().getVirtualParentTrackerId();
            if (value != null && value > 0) {
                return value;
            }
        }
        return 6;
    }

        private void collectVirtualParents(RowData rowData, Set<String> existingExternalKeys,
            Map<String, ParentAggregate> virtualParents, String statusValue, StatusConfig statusConfig, FileLogger logger) {
        String externalKey = rowData.externalKey == null ? "" : rowData.externalKey.trim();
        String[] rawSegments = externalKey.isEmpty() ? new String[0] : externalKey.split("\\.");
        // 空文字や空白のみのセグメントを除去して正規化
        List<String> segList = new ArrayList<>();
        for (String s : rawSegments) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) segList.add(t);
        }
        String[] segments = segList.toArray(new String[0]);
        ParentStatus parentStatus = classifyStatus(statusValue, statusConfig);

        // 深い WBS セグメントをカバーするため、階層列と外部キーセグメントの両方を考慮して最大レベルを決定
        int hierarchyLevels = Math.max(0, Math.max(0, rowData.hierarchyValues.size() - 1));
        int segmentLevels = Math.max(0, Math.max(0, segments.length - 1));
        int maxLevels = Math.max(hierarchyLevels, segmentLevels);

        for (int levelIndex = 0; levelIndex < maxLevels; levelIndex++) {
            String parentKey = segments.length > 0 ? joinSegments(segments, levelIndex + 1) : null;
            if ((parentKey == null || parentKey.isBlank()) && rowData.hierarchyValues.size() < levelIndex + 1) {
                if (logger != null) logger.debug("Skipping generation: no parentKey and no hierarchy value for level=" + levelIndex + " externalKey=" + externalKey);
                continue;
            }
            if (parentKey != null && existingExternalKeys.contains(parentKey)) {
                if (logger != null) logger.debug("Skip virtual parent generation because parent exists in CSV: " + parentKey);
                continue;
            }

            List<String> values;
            List<String> columns;
            if (rowData.hierarchyValues.size() >= levelIndex + 1) {
                values = rowData.hierarchyValues.subList(0, levelIndex + 1);
                columns = rowData.hierarchyColumnsUsed.subList(0, levelIndex + 1);
            } else {
                // 階層名が不足している場合は、親キーを件名に使う（数字のみのWBSでも扱えるようにする）
                values = parentKey == null ? List.of() : List.of(parentKey);
                columns = List.of();
            }

            String levelPath = String.join(" > ", values);
            String subject = values.isEmpty() ? (parentKey == null ? "" : parentKey) : values.get(values.size() - 1);
            String parentParentKey = (segments.length > 0 && levelIndex > 0) ? joinSegments(segments, levelIndex) : null;
            String useParentKey = parentKey == null || parentKey.isBlank() ? subject : parentKey;
            ParentAggregate aggregate = virtualParents.computeIfAbsent(
                    useParentKey,
                    key -> new ParentAggregate(key, levelPath, subject, parentParentKey, columns, values));
            aggregate.addChild(rowData, parentStatus);
            if (logger != null) {
                logger.debug("Added/updated virtual parent: " + useParentKey + " levelPath=" + levelPath + " subject=" + subject);
            }
        }
    }

    private static String joinSegments(String[] segments, int length) {
        if (segments == null || segments.length < length || length <= 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                sb.append(".");
            }
            sb.append(segments[i]);
        }
        return sb.toString();
    }

    private String inferParentKeyFromExternalKey(String externalKey) {
        if (externalKey == null || externalKey.isBlank()) {
            return null;
        }
        int lastDot = externalKey.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == externalKey.length() - 1) {
            return null;
        }
        String parent = externalKey.substring(0, lastDot);
        if (parent.isBlank()) {
            return null;
        }
        return parent;
    }

    private static LocalDate parseDate(String value) {
        String normalized = DateParser.normalizeDate(value);
        if (normalized == null) {
            return null;
        }
        return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static Integer parseProgress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("%")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (!StringUtils.isNumeric(trimmed)) {
            return null;
        }
        try {
            int progress = Integer.parseInt(trimmed);
            if (progress < 0) {
                return 0;
            }
            if (progress > 100) {
                return 100;
            }
            return progress;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private ParentStatus classifyStatus(String statusValue, StatusConfig statusConfig) {
        if (statusValue == null || statusValue.isBlank()) {
            return ParentStatus.NEW;
        }
        String mapped = mapStatusValue(statusValue, statusConfig);
        if (mapped != null) {
            if (StringUtils.isNumeric(mapped)) {
                return mapStatusId(mapped);
            }
            if (STATUS_CLOSED.equalsIgnoreCase(mapped) || "完了".equals(mapped)) {
                return ParentStatus.CLOSED;
            }
            if (STATUS_IN_PROGRESS.equalsIgnoreCase(mapped) || "進行中".equals(mapped)) {
                return ParentStatus.IN_PROGRESS;
            }
            if (STATUS_NEW.equalsIgnoreCase(mapped) || "未着手".equals(mapped)) {
                return ParentStatus.NEW;
            }
        }
        if (STATUS_CLOSED.equalsIgnoreCase(statusValue) || "完了".equals(statusValue)) {
            return ParentStatus.CLOSED;
        }
        if (STATUS_IN_PROGRESS.equalsIgnoreCase(statusValue) || "進行中".equals(statusValue)) {
            return ParentStatus.IN_PROGRESS;
        }
        if (STATUS_NEW.equalsIgnoreCase(statusValue) || "未着手".equals(statusValue)) {
            return ParentStatus.NEW;
        }
        return ParentStatus.IN_PROGRESS;
    }

    private String mapStatusValue(String statusValue, StatusConfig statusConfig) {
        if (statusValue == null || statusValue.isBlank() || statusConfig == null) {
            return statusValue;
        }
        Map<String, String> map = statusConfig.getStatusMap();
        if (map == null || map.isEmpty()) {
            return statusValue;
        }
        return map.getOrDefault(statusValue, statusValue);
    }

    private ParentStatus mapStatusId(String statusId) {
        if ("1".equals(statusId)) {
            return ParentStatus.NEW;
        }
        if ("2".equals(statusId)) {
            return ParentStatus.IN_PROGRESS;
        }
        if ("5".equals(statusId)) {
            return ParentStatus.CLOSED;
        }
        return ParentStatus.IN_PROGRESS;
    }

    private enum ParentStatus {
        NEW,
        IN_PROGRESS,
        CLOSED
    }

    private List<String> resolveParentKeys(String externalKey) {
        List<String> parents = new ArrayList<>();
        if (externalKey == null || externalKey.isBlank()) {
            return parents;
        }
        String[] segments = externalKey.split("\\.");
        if (segments.length < 2) {
            return parents;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length - 1; i++) {
            if (i > 0) {
                sb.append(".");
            }
            sb.append(segments[i]);
            parents.add(sb.toString());
        }
        return parents;
    }

    private List<DiffItem> buildDeleteItems(Set<String> requiredExternalKeys, String projectId, FileLogger logger) {
        List<DiffItem> deletions = new ArrayList<>();
        for (IssueLinkEntity link : issueLinkRepository.findAll()) {
            String externalKey = link.getExternalKey();
            if (externalKey == null || externalKey.isBlank()) {
                continue;
            }
            if (projectId != null && link.getProjectId() != null
                    && !projectId.equals(link.getProjectId())) {
                continue;
            }
            if (link.getProjectId() == null || link.getProjectId().isBlank()) {
                if (logger != null) {
                    logger.debug("Skip delete (missing project_id) for external_key=" + externalKey);
                }
                continue;
            }
            if (requiredExternalKeys.contains(externalKey)) {
                continue;
            }
            deletions.add(new DiffItem(
                    externalKey,
                    "",
                    null,
                    "",
                    ACTION_DELETE,
                    null,
                    Map.of()
            ));
        }
        // 数値的に自然順で返す
        deletions.sort((a, b) -> compareExternalKeys(a.externalKey(), b.externalKey()));
        return deletions;
    }

    private List<DiffItem> buildResetDeleteItems(String projectId, FileLogger logger) {
        List<DiffItem> deletions = new ArrayList<>();
        for (IssueLinkEntity link : issueLinkRepository.findAll()) {
            String externalKey = link.getExternalKey();
            if (externalKey == null || externalKey.isBlank()) {
                continue;
            }
            if (projectId != null && link.getProjectId() != null
                    && !projectId.equals(link.getProjectId())) {
                continue;
            }
            if (link.getProjectId() == null || link.getProjectId().isBlank()) {
                if (logger != null) {
                    logger.debug("Skip reset delete (missing project_id) for external_key=" + externalKey);
                }
                continue;
            }
            deletions.add(new DiffItem(
                    externalKey,
                    "",
                    null,
                    "",
                    ACTION_DELETE,
                    null,
                    Map.of()
            ));
        }
        deletions.sort((a, b) -> compareExternalKeys(a.externalKey(), b.externalKey()));
        return deletions;
    }
}
