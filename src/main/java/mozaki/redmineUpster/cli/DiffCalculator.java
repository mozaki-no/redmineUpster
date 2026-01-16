package mozaki.redmineUpster.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.domain.IssueLinkEntity;
import mozaki.redmineUpster.repository.IssueLinkRepository;
import mozaki.redmineUpster.util.ColumnDefinitions;

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
    private final ObjectMapper objectMapper;

    /**
     * 差分アイテムを計算します。
     * <p>
     * 各行のexternal_keyを基にIssueLinkRepositoryを参照し、
     * CREATE（新規）またはUPDATE（更新）のアクションを決定します。
     * </p>
     *
     * @param rows CSV/Excelから解析された行データ
     * @param projectConfig プロジェクト設定
     * @return 差分アイテムのリスト
     */
    public List<DiffItem> calculate(
            List<Map<String, String>> rows,
            ProjectConfig projectConfig) {

        List<RowData> parsed = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String externalKey = value(row, ColumnDefinitions.COL_ID);
            if (externalKey.isBlank()) {
                continue;
            }
            List<String> hierarchy = hierarchyValues(row);
            String subject = resolveSubject(row, hierarchy);
            String levelPath = String.join(" > ", hierarchy);
            List<String> parentHierarchy = hierarchy.size() > 1 ? hierarchy.subList(0, hierarchy.size() - 1) : List.of();
            String parentPath = String.join(" > ", parentHierarchy);
            RowData data = new RowData(externalKey, subject, levelPath, parentPath, row);
            parsed.add(data);
        }

        Map<String, String> pathToExternalKey = new HashMap<>();
        for (RowData rowData : parsed) {
            if (!rowData.levelPath.isBlank() && !pathToExternalKey.containsKey(rowData.levelPath)) {
                pathToExternalKey.put(rowData.levelPath, rowData.externalKey);
            }
        }

        Map<String, String> customFieldMap = getCustomFieldMap(projectConfig);
        List<DiffItem> items = new ArrayList<>();

        for (RowData rowData : parsed) {
            String parentKey = pathToExternalKey.get(rowData.parentPath);
            String action = resolveAction(rowData.externalKey, projectConfig.getName());
            String status = resolveStatus(rowData, projectConfig);
            Map<String, Object> payload = buildPayload(rowData, customFieldMap);

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
        }

        return items;
    }

    /**
     * アクションを解決します（CREATE/UPDATE）。
     *
     * @param externalKey 外部キー
     * @param projectName プロジェクト名
     * @return CREATE または UPDATE
     */
    private String resolveAction(String externalKey, String projectName) {
        Optional<IssueLinkEntity> existing = issueLinkRepository.findByExternalKey(externalKey);
        return existing.isPresent() ? "UPDATE" : "CREATE";
    }

    /**
     * 階層列の値を取得します。
     *
     * @param row 行データ
     * @return 階層値のリスト
     */
    private List<String> hierarchyValues(Map<String, String> row) {
        List<String> values = new ArrayList<>();
        for (String column : ColumnDefinitions.HIERARCHY_COLUMNS) {
            String value = value(row, column);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
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

        String mode = valueOrDefault(statusConfig.getMode(), "BY_DATES");
        if ("FIXED".equalsIgnoreCase(mode)) {
            return valueOrDefault(statusConfig.getFixed(), "New");
        }

        if (!rowData.dueActual.isBlank()) {
            return "Closed";
        }
        if (!rowData.startActual.isBlank()) {
            return "In Progress";
        }
        return "New";
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
     * @return ペイロード
     */
    private Map<String, Object> buildPayload(RowData rowData, Map<String, String> customFieldMap) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignee", rowData.assignee);
        payload.put("startDate", rowData.startPlan);
        payload.put("dueDate", rowData.duePlan);
        payload.put("startActual", rowData.startActual);
        payload.put("dueActual", rowData.dueActual);

        Map<String, String> customFields = new LinkedHashMap<>();
        for (String column : ColumnDefinitions.CUSTOM_FIELD_COLUMNS) {
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
     * 値がnullまたは空の場合はデフォルト値を返します。
     *
     * @param value 値
     * @param fallback デフォルト値
     * @return 値またはデフォルト値
     */
    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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

        private RowData(String externalKey, String subject, String levelPath, String parentPath,
                Map<String, String> row) {
            this.externalKey = externalKey;
            this.subject = subject;
            this.levelPath = levelPath;
            this.parentPath = parentPath;
            this.row = row;
            this.assignee = value(row, ColumnDefinitions.COL_ASSIGNEE);
            this.startPlan = value(row, ColumnDefinitions.COL_START_PLAN);
            this.duePlan = value(row, ColumnDefinitions.COL_DUE_PLAN);
            this.startActual = value(row, ColumnDefinitions.COL_START_ACTUAL);
            this.dueActual = value(row, ColumnDefinitions.COL_DUE_ACTUAL);
        }
    }
}
