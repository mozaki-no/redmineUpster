package mozaki.redmineUpster.cli;

import static mozaki.redmineUpster.cli.SyncConstants.ACTION_CREATE;
import static mozaki.redmineUpster.cli.SyncConstants.ACTION_UPDATE;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_CLOSED;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_IN_PROGRESS;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_MODE_BY_DATES;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_MODE_FIXED;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_NEW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import mozaki.redmineUpster.config.SyncConfigProperties.ColumnsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.TrackerConfig;
import mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet;
import mozaki.redmineUpster.util.ColumnDefinitions;
import mozaki.redmineUpster.util.StringUtils;

/**
 * 差分計算クラス。
 * <p>
 * Excel/CSVの各行を1件のRedmineチケットとして扱い、
 * <ul>
 *   <li>チケットID列が空欄なら CREATE、値があれば UPDATE</li>
 *   <li>親子関係は階層列だけから決定（値が入っている一番深い階層列がその行のレベル。
 *       その行の値から一番深い値を除いたものと、階層列・値がまったく同じ行が親。
 *       途中の階層列の空欄は「その階層を飛ばす」ことを表す）</li>
 *   <li>トラッカーは行ごとのトラッカー列（名前 → ID）</li>
 * </ul>
 * を計算します。親行がない・階層パスの重複・チケットIDの重複や非数値・不明なトラッカーは
 * 検証エラーとして返し、Redmineには一切書き込みません。
 * </p>
 */
@Component
public class DiffCalculator {

    private static final String KEY_SEPARATOR = "\u001F";
    private static final String PATH_DELIMITER = " > ";

    /**
     * 差分を計算します。
     *
     * @param sheet 解析済みシート（行番号付き）
     * @param projectConfig プロジェクト設定
     * @param trackerResolver トラッカー名 → ID の変換（null の場合は数値のみ受け付ける）
     * @param logger ファイルロガー（null可）
     * @return 差分アイテムと検証エラー
     */
    public DiffPlan calculate(ParsedSheet sheet, ProjectConfig projectConfig, TrackerResolver trackerResolver,
            FileLogger logger) {
        TrackerResolver resolver = trackerResolver != null ? trackerResolver : new TrackerResolver(Map.of(), null);
        List<Map<String, String>> rows = sheet.rows();
        List<Integer> rowNumbers = sheet.rowNumbers();
        List<String> errors = new ArrayList<>();

        Set<String> availableColumns = new HashSet<>(sheet.headers());
        if (availableColumns.isEmpty() && !rows.isEmpty()) {
            availableColumns.addAll(rows.get(0).keySet());
        }
        HierarchyColumns hierarchy = resolveHierarchyColumns(projectConfig, sheet.headers().isEmpty()
                ? availableColumns : sheet.headers());
        if (!rows.isEmpty() && hierarchy.columns().isEmpty()) {
            errors.add(hierarchy.message());
            return new DiffPlan(List.of(), errors);
        }
        if (logger != null) {
            if (hierarchy.message() != null) {
                logger.warn(hierarchy.message());
            }
            logger.info("階層列: " + String.join(" > ", hierarchy.columns()));
        }
        List<String> hierarchyColumns = hierarchy.columns();
        if (isFillDownHierarchy(projectConfig)) {
            rows = fillDownHierarchy(rows, hierarchyColumns);
        }
        ColumnNames names = new ColumnNames(projectConfig);
        List<String> customFieldColumns = getCustomFieldColumns(projectConfig);
        Map<String, String> customFieldMap = getCustomFieldMap(projectConfig);

        // 1. 行ごとの解析（チケットID・階層）
        List<RowData> parsed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int rowNumber = rowNumbers.get(i);
            List<String> cells = new ArrayList<>();
            for (String column : hierarchyColumns) {
                cells.add(value(row, column));
            }
            int deepest = -1;
            for (int k = 0; k < cells.size(); k++) {
                if (!cells.get(k).isBlank()) {
                    deepest = k;
                }
            }
            if (deepest < 0) {
                errors.add("行" + rowNumber + ": 階層列（" + String.join("/", hierarchyColumns) + "）がすべて空です");
                continue;
            }
            if (cells.get(0).isBlank()) {
                // 一番浅い階層列が空欄の行は、前行の値を引き継ぐ前提のシート（セル結合なし）の可能性が高いため、
                // 最上位として作成せずエラーにする
                errors.add("行" + rowNumber + " [" + joinPath(cells) + "]: " + hierarchyColumns.get(0)
                        + "が空欄です（同じ値が続く場合は各行に入力するかセル結合してください。"
                        + "空欄を前行の値で補うには sync.columns.fillDownHierarchy: true）");
                continue;
            }
            List<String> ownCells = cells.subList(0, deepest + 1);
            List<String> parentCells = new ArrayList<>(ownCells);
            parentCells.set(deepest, "");
            trimTrailingBlanks(parentCells);

            String levelPath = joinPath(ownCells);
            String rawId = value(row, names.ticketIdColumn);
            Long issueId = null;
            if (!rawId.isBlank()) {
                issueId = parseTicketId(rawId);
                if (issueId == null) {
                    errors.add("行" + rowNumber + " [" + levelPath + "]: チケットIDが数値ではありません: " + rawId);
                    continue;
                }
            }
            parsed.add(new RowData(rowNumber, row, issueId, deepest, levelPath,
                    toKey(ownCells), parentCells.isEmpty() ? null : toKey(parentCells), joinPath(parentCells),
                    resolveSubject(row, ownCells), names));
        }

        // 2. 重複チェック（階層パス・チケットID）
        Map<String, RowData> byKey = new HashMap<>();
        Map<Long, RowData> byIssueId = new HashMap<>();
        Set<RowData> invalid = new HashSet<>();
        for (RowData data : parsed) {
            RowData samePath = byKey.putIfAbsent(data.key, data);
            if (samePath != null) {
                errors.add("行" + data.rowNumber + " [" + data.levelPath + "]: 階層パスが行" + samePath.rowNumber
                        + "と重複しています");
                invalid.add(data);
            }
            if (data.issueId != null) {
                RowData sameId = byIssueId.putIfAbsent(data.issueId, data);
                if (sameId != null) {
                    errors.add("行" + data.rowNumber + " [" + data.levelPath + "]: チケットID " + data.issueId
                            + " が行" + sameId.rowNumber + "と重複しています");
                    invalid.add(data);
                }
            }
        }

        // 3. 親の決定・トラッカー解決・DiffItem生成
        TrackerConfig trackerConfig = projectConfig.getSync() != null ? projectConfig.getSync().getTracker() : null;
        String defaultTracker = trackerConfig != null && trackerConfig.isEnabled()
                && trackerConfig.getValue() != null && !trackerConfig.getValue().isBlank()
                ? trackerConfig.getValue().trim() : null;

        List<DiffItem> items = new ArrayList<>();
        for (RowData data : parsed) {
            if (invalid.contains(data)) {
                continue;
            }
            Integer parentRowNumber = null;
            if (data.parentKey != null) {
                RowData parent = byKey.get(data.parentKey);
                if (parent == null) {
                    errors.add("行" + data.rowNumber + " [" + data.levelPath + "]: 親行 [" + data.parentPath
                            + "] がファイルにありません");
                    continue;
                }
                parentRowNumber = parent.rowNumber;
            }

            String action = data.issueId == null ? ACTION_CREATE : ACTION_UPDATE;
            String trackerValue = value(data.row, names.trackerColumn);
            Long trackerId = null;
            if (!trackerValue.isBlank()) {
                trackerId = resolver.resolve(trackerValue);
                if (trackerId == null) {
                    errors.add("行" + data.rowNumber + " [" + data.levelPath + "]: トラッカー「" + trackerValue
                            + "」が見つかりません（trackerMap / Redmineのトラッカー名を確認してください）");
                    continue;
                }
            } else if (defaultTracker != null) {
                trackerId = resolver.resolve(defaultTracker);
                if (trackerId == null) {
                    errors.add("行" + data.rowNumber + " [" + data.levelPath + "]: 既定トラッカー「" + defaultTracker
                            + "」（sync.tracker.value）が見つかりません");
                    continue;
                }
            } else if (ACTION_CREATE.equals(action)) {
                errors.add("行" + data.rowNumber + " [" + data.levelPath + "]: 新規作成する行のトラッカー（"
                        + names.trackerColumn + "列）が空です");
                continue;
            }

            DiffItem item = new DiffItem(
                    data.rowNumber,
                    data.issueId,
                    data.subject,
                    data.levelPath,
                    data.depth,
                    parentRowNumber,
                    action,
                    resolveStatus(data, projectConfig),
                    trackerId,
                    buildPayload(data, customFieldMap, customFieldColumns));
            items.add(item);
            if (logger != null) {
                logger.debug("Row " + data.rowNumber + ": action=" + action + " issueId=" + data.issueId
                        + " path=[" + data.levelPath + "] parentRow=" + parentRowNumber + " trackerId=" + trackerId);
            }
        }

        items.sort(Comparator.comparingInt(DiffItem::depth).thenComparingInt(DiffItem::rowNumber));
        return new DiffPlan(items, withSheetName(errors, sheet.sheetName()));
    }

    /**
     * 論理削除の候補を求めます。
     * <p>
     * 同期開始時にRedmineから取得した同期先プロジェクトのチケット（手動作成したものも含む）のうち、
     * 今回のExcelに存在しないチケットIDを返します。論理削除ステータスが指定されている場合、
     * 既にそのステータスのチケットは除外します。
     * </p>
     *
     * @param excelIssueIds Excelに記載されたチケットID
     * @param projectIssues 同期先プロジェクトのチケット（チケットID → チケット情報）
     * @param deleteStatusId 論理削除ステータスID（未設定ならnull）
     * @return 論理削除候補のチケットID（昇順）
     */
    public static List<Long> findLogicalDeleteCandidates(Collection<Long> excelIssueIds,
            Map<Long, Map<String, Object>> projectIssues, Integer deleteStatusId) {
        if (projectIssues == null || projectIssues.isEmpty()) {
            return List.of();
        }
        Set<Long> present = new HashSet<>(excelIssueIds);
        Set<Long> candidates = new TreeSet<>();
        for (Map.Entry<Long, Map<String, Object>> entry : projectIssues.entrySet()) {
            Long issueId = entry.getKey();
            if (present.contains(issueId)) {
                continue;
            }
            Long statusId = IssueComparator.nestedId(entry.getValue(), "status");
            if (deleteStatusId != null && statusId != null && statusId.longValue() == deleteStatusId.longValue()) {
                continue;
            }
            candidates.add(issueId);
        }
        return new ArrayList<>(candidates);
    }

    /**
     * 設定からチケットID列名を取得します。
     *
     * @param projectConfig プロジェクト設定
     * @return チケットID列名
     */
    public static String getTicketIdColumn(ProjectConfig projectConfig) {
        return new ColumnNames(projectConfig).ticketIdColumn;
    }

    /**
     * プロジェクト設定から階層列を取得します（ファイルに該当列がなければ自動判定）。
     *
     * @param projectConfig プロジェクト設定
     * @param availableColumns ファイルのヘッダ
     * @return 階層列（浅い順。ファイルにある列だけ）
     */
    public static List<String> getHierarchyColumns(ProjectConfig projectConfig, Collection<String> availableColumns) {
        return resolveHierarchyColumns(projectConfig, availableColumns).columns();
    }

    /**
     * 階層列の決定結果。
     *
     * @param columns 使用する階層列（浅い順。ファイルにある列だけ。見つからなければ空）
     * @param message 警告（設定と異なる列を使う場合）またはエラー（columns が空の場合）。問題なければ null
     */
    public record HierarchyColumns(List<String> columns, String message) {
    }

    /**
     * 階層列を決定します。
     * <p>
     * 候補は「設定の hierarchy」「既定（大分類〜タスク）」「旧形式（Lv.01〜Lv.06 ＋ タスク）」で、
     * ファイルのヘッダに存在する列が最も多い候補を使います（同数なら設定 → 既定 → 旧形式の順）。
     * 使うのは候補のうちファイルに存在する列だけです。以前は設定の列が1つでもファイルにあれば
     * （例: 共通の「タスク」列だけ）設定をそのまま使っていたため、Lv.* 形式のファイルで
     * 「階層列がすべて空」になっていました。
     * </p>
     *
     * @param projectConfig プロジェクト設定
     * @param headers ファイルのヘッダ（空の場合は設定または既定をそのまま返す）
     * @return 決定結果
     */
    public static HierarchyColumns resolveHierarchyColumns(ProjectConfig projectConfig, Collection<String> headers) {
        List<String> configured = null;
        if (projectConfig != null && projectConfig.getSync() != null && projectConfig.getSync().getColumns() != null
                && projectConfig.getSync().getColumns().getHierarchy() != null
                && !projectConfig.getSync().getColumns().getHierarchy().isEmpty()) {
            configured = projectConfig.getSync().getColumns().getHierarchy();
        }
        if (headers == null || headers.isEmpty()) {
            return new HierarchyColumns(configured != null ? configured : ColumnDefinitions.HIERARCHY_COLUMNS, null);
        }
        Set<String> available = new HashSet<>(headers);
        List<String> legacy = new ArrayList<>(ColumnDefinitions.LEGACY_HIERARCHY_COLUMNS);
        legacy.add(ColumnDefinitions.COL_TASK);
        List<List<String>> candidates = new ArrayList<>();
        if (configured != null) {
            candidates.add(configured);
        }
        candidates.add(ColumnDefinitions.HIERARCHY_COLUMNS);
        candidates.add(legacy);

        List<String> best = candidates.get(0);
        List<String> bestPresent = List.of();
        for (List<String> candidate : candidates) {
            List<String> present = candidate.stream().filter(available::contains).toList();
            if (present.size() > bestPresent.size()) {
                best = candidate;
                bestPresent = present;
            }
        }
        List<String> expected = configured != null ? configured : ColumnDefinitions.HIERARCHY_COLUMNS;
        if (bestPresent.isEmpty()) {
            return new HierarchyColumns(List.of(), "階層列がファイルに見つかりません（設定: " + expected
                    + "、ファイルのヘッダ: " + headers + "）。sync.columns.hierarchy をファイルの列名に合わせてください");
        }
        String message = null;
        if (!bestPresent.equals(expected)) {
            List<String> missing = expected.stream().filter(c -> !available.contains(c)).toList();
            message = (configured != null ? "設定の階層列 " : "既定の階層列 ") + expected
                    + (missing.isEmpty() ? "" : " のうち " + missing + " がファイルにありません")
                    + "。ファイルの列 " + bestPresent + " を階層列として使います"
                    + (best == configured ? "" : "（sync.columns.hierarchy をファイルの列名に合わせてください）");
        }
        return new HierarchyColumns(bestPresent, message);
    }

    private static boolean isFillDownHierarchy(ProjectConfig projectConfig) {
        return projectConfig != null && projectConfig.getSync() != null && projectConfig.getSync().getColumns() != null
                && projectConfig.getSync().getColumns().isFillDownHierarchy();
    }

    /**
     * 階層列の空欄を前行の値で補完します（sync.columns.fillDownHierarchy: true の場合のみ。旧来の動作）。
     * <p>
     * 補完するのは「その行でより深い階層列に値がある」空欄だけです。一番深い値より右側の空欄は、
     * その行の階層レベルを表すため補完しません。また、ある階層列に前行と異なる値が入った場合、
     * それより深い列の前行値は引き継ぎません。この場合、階層を飛ばした行は作れません。
     * </p>
     *
     * @param rows 行データ（変更しない）
     * @param hierarchyColumns 階層列（浅い順）
     * @return 補完後の行データ（コピー）
     */
    static List<Map<String, String>> fillDownHierarchy(List<Map<String, String>> rows, List<String> hierarchyColumns) {
        String[] lastValues = new String[hierarchyColumns.size()];
        java.util.Arrays.fill(lastValues, "");
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> original : rows) {
            Map<String, String> row = new LinkedHashMap<>(original);
            int deepest = -1;
            for (int k = 0; k < hierarchyColumns.size(); k++) {
                if (!value(row, hierarchyColumns.get(k)).isBlank()) {
                    deepest = k;
                }
            }
            for (int k = 0; k < hierarchyColumns.size(); k++) {
                String column = hierarchyColumns.get(k);
                String v = value(row, column);
                if (!v.isBlank()) {
                    if (!v.equals(lastValues[k])) {
                        for (int j = k + 1; j < lastValues.length; j++) {
                            lastValues[j] = "";
                        }
                    }
                    lastValues[k] = v;
                } else if (k < deepest) {
                    row.put(column, lastValues[k]);
                }
            }
            result.add(row);
        }
        return result;
    }

    private static Long parseTicketId(String raw) {
        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1).trim();
        }
        if (!StringUtils.isNumeric(value) || value.length() > 18) {
            return null;
        }
        long id = Long.parseLong(value);
        return id > 0 ? id : null;
    }

    /**
     * Excel の場合、行番号で始まるメッセージにシート名を付けます（例: シート「取込」行12 ...）。
     */
    private static List<String> withSheetName(List<String> errors, String sheetName) {
        if (sheetName == null) {
            return errors;
        }
        List<String> result = new ArrayList<>();
        for (String error : errors) {
            result.add(error.startsWith("行") ? "シート「" + sheetName + "」" + error : error);
        }
        return result;
    }

    private static void trimTrailingBlanks(List<String> cells) {
        while (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) {
            cells.remove(cells.size() - 1);
        }
    }

    private static String toKey(List<String> cells) {
        return String.join(KEY_SEPARATOR, cells);
    }

    private static String joinPath(List<String> cells) {
        List<String> nonBlank = new ArrayList<>();
        for (String cell : cells) {
            if (!cell.isBlank()) {
                nonBlank.add(cell);
            }
        }
        return String.join(PATH_DELIMITER, nonBlank);
    }

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
     * 件名を解決します（タスク列 → 一番深い階層値）。
     */
    private String resolveSubject(Map<String, String> row, List<String> hierarchyCells) {
        String task = value(row, ColumnDefinitions.COL_TASK);
        if (!task.isBlank()) {
            return task;
        }
        for (int i = hierarchyCells.size() - 1; i >= 0; i--) {
            if (!hierarchyCells.get(i).isBlank()) {
                return hierarchyCells.get(i);
            }
        }
        return "";
    }

    /**
     * ステータスを解決します。
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

    private Map<String, String> getCustomFieldMap(ProjectConfig projectConfig) {
        if (projectConfig.getSync() == null || projectConfig.getSync().getCustomFieldMap() == null) {
            return Map.of();
        }
        return projectConfig.getSync().getCustomFieldMap();
    }

    private Map<String, Object> buildPayload(RowData rowData, Map<String, String> customFieldMap,
            List<String> customFieldColumns) {
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
            if (customFieldMap.get(column) == null) {
                continue;
            }
            customFields.put(column, value(rowData.row, column));
        }
        payload.put("customFields", customFields);
        return payload;
    }

    private static String value(Map<String, String> row, String key) {
        if (key == null) {
            return "";
        }
        String raw = row.get(key);
        return raw == null ? "" : raw.trim();
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
            return Math.max(0, Math.min(100, progress));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 列名の設定値（未設定ならデフォルト）。
     */
    private static final class ColumnNames {
        private final String ticketIdColumn;
        private final String trackerColumn;
        private final String startDateColumn;
        private final String dueDateColumn;
        private final String statusColumn;
        private final String progressColumn;

        private ColumnNames(ProjectConfig projectConfig) {
            ColumnsConfig columns = projectConfig != null && projectConfig.getSync() != null
                    ? projectConfig.getSync().getColumns() : null;
            if (columns == null) {
                columns = new ColumnsConfig();
            }
            this.ticketIdColumn = StringUtils.valueOrDefault(columns.getTicketIdColumn(), ColumnDefinitions.COL_TICKET_ID);
            this.trackerColumn = StringUtils.valueOrDefault(columns.getTrackerColumn(), ColumnDefinitions.COL_TRACKER);
            this.startDateColumn = StringUtils.valueOrDefault(columns.getStartDateColumn(), ColumnDefinitions.COL_START_PLAN);
            this.dueDateColumn = StringUtils.valueOrDefault(columns.getDueDateColumn(), ColumnDefinitions.COL_DUE_PLAN);
            this.statusColumn = StringUtils.valueOrDefault(columns.getStatusColumn(), ColumnDefinitions.COL_STATUS);
            this.progressColumn = StringUtils.valueOrDefault(columns.getProgressColumn(), ColumnDefinitions.COL_PROGRESS);
        }
    }

    /**
     * 行データの内部クラス。
     */
    private static final class RowData {
        private final int rowNumber;
        private final Map<String, String> row;
        private final Long issueId;
        private final int depth;
        private final String levelPath;
        private final String key;
        private final String parentKey;
        private final String parentPath;
        private final String subject;
        private final String assignee;
        private final String startPlan;
        private final String duePlan;
        private final String startActual;
        private final String dueActual;
        private final String statusValue;
        private final Integer progress;

        private RowData(int rowNumber, Map<String, String> row, Long issueId, int depth, String levelPath,
                String key, String parentKey, String parentPath, String subject, ColumnNames names) {
            this.rowNumber = rowNumber;
            this.row = row;
            this.issueId = issueId;
            this.depth = depth;
            this.levelPath = levelPath;
            this.key = key;
            this.parentKey = parentKey;
            this.parentPath = parentPath;
            this.subject = subject;
            this.assignee = value(row, ColumnDefinitions.COL_ASSIGNEE);
            this.startPlan = value(row, names.startDateColumn);
            this.duePlan = value(row, names.dueDateColumn);
            this.startActual = value(row, ColumnDefinitions.COL_START_ACTUAL);
            this.dueActual = value(row, ColumnDefinitions.COL_DUE_ACTUAL);
            this.statusValue = value(row, names.statusColumn);
            this.progress = parseProgress(value(row, names.progressColumn));
        }
    }
}
