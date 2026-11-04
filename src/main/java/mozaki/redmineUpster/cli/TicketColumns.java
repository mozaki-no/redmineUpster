package mozaki.redmineUpster.cli;

import java.util.LinkedHashMap;
import java.util.Map;

import mozaki.redmineUpster.config.SyncConfigProperties.ColumnsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.util.ColumnDefinitions;
import mozaki.redmineUpster.util.StringUtils;

/**
 * チケットの標準フィールドの取込列名（設定 sync.columns。未設定・空欄なら既定の列名）。
 * <p>
 * 取り込み（{@link DiffCalculator}）と Excel 出力（{@link WorkbookExporter}）で同じ列名を使うため、
 * 出力した Excel はそのまま取り込めます。階層列は {@link DiffCalculator#resolveHierarchyColumns} で決めます。
 * </p>
 *
 * @param ticketId チケットID列（既定: チケットID）
 * @param tracker トラッカー列（既定: トラッカー）
 * @param subject 件名列（既定: タスク。空欄なら一番深い階層列の値）
 * @param status ステータス列（既定: ステータス）
 * @param assignee 担当者列（既定: 担当）
 * @param startDate 開始日列（既定: 着手予定）
 * @param dueDate 期日列（既定: 完了予定）
 * @param startActual 着手実績列（既定: 着手実績。ステータスの自動判定に使用）
 * @param dueActual 完了実績列（既定: 完了実績。ステータスの自動判定に使用）
 * @param progress 進捗率列（既定: 進捗率）
 * @param description 説明列（既定: 説明）
 */
public record TicketColumns(String ticketId, String tracker, String subject, String status, String assignee,
        String startDate, String dueDate, String startActual, String dueActual, String progress,
        String description) {

    /** 既定の列名 */
    public static final TicketColumns DEFAULTS = new TicketColumns(ColumnDefinitions.COL_TICKET_ID,
            ColumnDefinitions.COL_TRACKER, ColumnDefinitions.COL_TASK, ColumnDefinitions.COL_STATUS,
            ColumnDefinitions.COL_ASSIGNEE, ColumnDefinitions.COL_START_PLAN, ColumnDefinitions.COL_DUE_PLAN,
            ColumnDefinitions.COL_START_ACTUAL, ColumnDefinitions.COL_DUE_ACTUAL, ColumnDefinitions.COL_PROGRESS,
            ColumnDefinitions.COL_DESCRIPTION);

    /**
     * プロジェクト設定の列名を返します（未設定・空欄の項目は既定の列名）。
     *
     * @param projectConfig プロジェクト設定（null可）
     * @return 列名
     */
    public static TicketColumns of(ProjectConfig projectConfig) {
        ColumnsConfig columns = projectConfig != null && projectConfig.getSync() != null
                ? projectConfig.getSync().getColumns() : null;
        return of(columns);
    }

    /**
     * 列設定の列名を返します（未設定・空欄の項目は既定の列名）。
     *
     * @param columns 列設定（null可）
     * @return 列名
     */
    public static TicketColumns of(ColumnsConfig columns) {
        if (columns == null) {
            return DEFAULTS;
        }
        return new TicketColumns(
                or(columns.getTicketIdColumn(), DEFAULTS.ticketId),
                or(columns.getTrackerColumn(), DEFAULTS.tracker),
                or(columns.getSubjectColumn(), DEFAULTS.subject),
                or(columns.getStatusColumn(), DEFAULTS.status),
                or(columns.getAssigneeColumn(), DEFAULTS.assignee),
                or(columns.getStartDateColumn(), DEFAULTS.startDate),
                or(columns.getDueDateColumn(), DEFAULTS.dueDate),
                or(columns.getStartActualColumn(), DEFAULTS.startActual),
                or(columns.getDueActualColumn(), DEFAULTS.dueActual),
                or(columns.getProgressColumn(), DEFAULTS.progress),
                or(columns.getDescriptionColumn(), DEFAULTS.description));
    }

    /**
     * 設定キー（sync.columns.*）→ 列名（ログ・確認用。設定ファイルの順）。
     *
     * @return 設定キー → 列名
     */
    public Map<String, String> byKey() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("ticketIdColumn", ticketId);
        map.put("trackerColumn", tracker);
        map.put("subjectColumn", subject);
        map.put("statusColumn", status);
        map.put("assigneeColumn", assignee);
        map.put("startDateColumn", startDate);
        map.put("dueDateColumn", dueDate);
        map.put("startActualColumn", startActual);
        map.put("dueActualColumn", dueActual);
        map.put("progressColumn", progress);
        map.put("descriptionColumn", description);
        return map;
    }

    private static String or(String value, String defaultValue) {
        return StringUtils.valueOrDefault(value, defaultValue);
    }
}
