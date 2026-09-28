package mozaki.redmineUpster.cli;

import static mozaki.redmineUpster.cli.SyncConstants.ACTION_CREATE;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_CLOSED;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_IN_PROGRESS;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_MODE_BY_DATES;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_MODE_FIXED;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_NEW;
import static mozaki.redmineUpster.util.StringUtils.isNumeric;
import static mozaki.redmineUpster.util.StringUtils.valueOrDefault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.util.DateParser;

/**
 * 同期実行クラス。
 * <p>
 * 差分アイテムを親→子の順にRedmineへ反映します。
 * <ul>
 *   <li>CREATE: チケットを作成し、同じ実行内で子の parent_issue_id に作成したIDを渡す</li>
 *   <li>UPDATE: 同期開始時に取得したプロジェクトのチケットに含まれることを確認し、
 *       送信内容と現在の値に違いがあれば更新（親も階層から再設定）</li>
 *   <li>論理削除: Excelにないプロジェクト内のチケットのステータスを変更（物理削除はしない）</li>
 *   <li>仮想親（ファイルに行がない祖先）: CREATE/UPDATE は行と同じ。作成時だけ説明に目印を入れ、
 *       ステータスは送らず、チケットIDの書き戻し対象にしない</li>
 * </ul>
 * DBは使わず、Redmineの現在の状態を正とします。
 * </p>
 */
@Component
public class SyncExecutor {

    /**
     * 同期を実行します。
     *
     * @param items 差分アイテム
     * @param projectIssues 同期開始時に取得した同期先プロジェクトのチケット（チケットID → チケット情報）
     * @param logicalDeleteCandidates 論理削除候補のチケットID
     * @param projectConfig プロジェクト設定
     * @param client Redmineクライアント
     * @param dryRun ドライランの場合はtrue（Redmineへの書き込みを行わない）
     * @param logger ファイルロガー
     * @param forceUpdate 変更なしスキップを無効化する場合はtrue
     * @return 同期結果
     */
    public SyncResult execute(
            List<DiffItem> items,
            Map<Long, Map<String, Object>> projectIssues,
            List<Long> logicalDeleteCandidates,
            ProjectConfig projectConfig,
            RedmineClient client,
            boolean dryRun,
            FileLogger logger,
            boolean forceUpdate) {

        int totalCount = items.size();
        int successCount = 0;
        List<String> errors = new ArrayList<>();
        Map<Integer, Long> createdIssueIds = new LinkedHashMap<>();

        Map<Long, Map<String, Object>> currentIssues = projectIssues == null ? Map.of() : projectIssues;
        Map<String, String> customFieldMap = getCustomFieldMap(projectConfig);
        List<String> customFieldDateColumns = getCustomFieldDateColumns(projectConfig);
        String projectId = client.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("redmine.project-id is not configured");
        }

        Map<Integer, String> virtualPaths = new HashMap<>();
        for (DiffItem item : items) {
            if (item.virtual()) {
                virtualPaths.put(item.rowNumber(), item.levelPath());
            }
        }

        List<DiffItem> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparingInt(DiffItem::depth).thenComparingInt(DiffItem::rowNumber));

        Map<Integer, Long> rowIssueIds = new HashMap<>();
        for (DiffItem item : ordered) {
            if (item.issueId() != null) {
                rowIssueIds.put(item.rowNumber(), item.issueId());
            }
        }
        Set<Integer> failedRows = new HashSet<>();
        Set<Integer> plannedCreates = new HashSet<>();

        for (DiffItem item : ordered) {
            try {
                Object parentIssueId = null;
                if (item.parentRowNumber() != null) {
                    if (failedRows.contains(item.parentRowNumber())) {
                        failedRows.add(item.rowNumber());
                        addError(errors, logger, "同期失敗: " + item.label() + " 理由=親" + parentName(item, virtualPaths)
                                + "の同期に失敗したためスキップしました");
                        continue;
                    }
                    parentIssueId = rowIssueIds.get(item.parentRowNumber());
                    if (parentIssueId == null && !(dryRun && plannedCreates.contains(item.parentRowNumber()))) {
                        failedRows.add(item.rowNumber());
                        addError(errors, logger, "同期失敗: " + item.label() + " 理由=親" + parentName(item, virtualPaths)
                                + "のチケットIDが確定していません");
                        continue;
                    }
                } else {
                    // 最上位の行は親を空にする（Excelで最上位に移動した場合に親子関係を解除する。
                    // Redmineの現在値と比較するときは「親なし」と同じ扱いになる）
                    parentIssueId = "";
                }

                Map<String, Object> issuePayload = buildIssuePayload(item, projectId, parentIssueId, projectConfig,
                        customFieldMap, customFieldDateColumns);

                if (ACTION_CREATE.equalsIgnoreCase(item.action())) {
                    if (dryRun) {
                        plannedCreates.add(item.rowNumber());
                        logger.info("DRY_RUN CREATE " + item.label() + " subject=" + item.subject()
                                + " tracker_id=" + item.trackerId() + parentLabel(item, virtualPaths));
                        successCount++;
                        continue;
                    }
                    if (item.virtual()) {
                        // 仮想親の目印（次回以降の再識別で優先する）。説明は作成時だけ設定し、更新・比較はしない
                        issuePayload.put("description", VirtualParentMatcher.MARKER);
                    }
                    logger.debug("API Request: POST " + client.getBaseUrl() + "/issues.json");
                    logger.debug("Request body: " + formatPayloadForLog(issuePayload));
                    Long issueId = client.createIssue(issuePayload);
                    if (issueId == null) {
                        failedRows.add(item.rowNumber());
                        addError(errors, logger, "作成失敗: " + item.label() + " (レスポンスにissue idがありません)");
                        continue;
                    }
                    rowIssueIds.put(item.rowNumber(), issueId);
                    if (!item.virtual()) {
                        createdIssueIds.put(item.rowNumber(), issueId);
                    }
                    logger.info("created issue " + issueId + " for " + item.label());
                    successCount++;
                    continue;
                }

                Long issueId = item.issueId();
                Map<String, Object> current = currentIssues.get(issueId);
                if (current == null) {
                    failedRows.add(item.rowNumber());
                    addError(errors, logger, "更新失敗: " + item.label() + " 理由=" + describeMissing(client, issueId));
                    continue;
                }
                List<String> changed = new ArrayList<>(IssueComparator.changedFields(issuePayload, current));
                if (parentIssueId == null) {
                    // dry-runで親が新規作成予定の場合（親IDは本実行で確定する）
                    changed.add("parent_issue_id");
                }
                if (!forceUpdate && changed.isEmpty()) {
                    logger.info("skipped update for " + item.label() + " (no changes)");
                    successCount++;
                    continue;
                }
                String changes = forceUpdate && changed.isEmpty() ? "(force-update)" : String.join(",", changed);
                if (dryRun) {
                    logger.info("DRY_RUN UPDATE " + item.label() + " subject=" + item.subject()
                            + " tracker_id=" + item.trackerId() + parentLabel(item, virtualPaths)
                            + " changes=" + changes);
                    successCount++;
                    continue;
                }
                logger.debug("API Request: PUT " + client.getBaseUrl() + "/issues/" + issueId + ".json");
                logger.debug("Request body: " + formatPayloadForLog(issuePayload));
                client.updateIssue(issueId, issuePayload);
                logger.info("updated issue " + issueId + " for " + item.label() + " changes=" + changes);
                successCount++;
            } catch (RuntimeException ex) {
                failedRows.add(item.rowNumber());
                addError(errors, logger, formatError(item.label(), ex));
            }
        }

        // 論理削除（Excelにないプロジェクト内のチケット。Redmineで手動作成したチケットも含む）
        Integer deleteStatusId = getLogicalDeleteStatusId(projectConfig);
        List<Long> candidates = logicalDeleteCandidates == null ? List.of() : logicalDeleteCandidates;
        if (!candidates.isEmpty() && deleteStatusId == null) {
            for (Long issueId : candidates) {
                logger.warn("論理削除候補: " + describeIssue(issueId, currentIssues)
                        + "（Excelに存在しません。sync.deletion.statusId が未設定のため変更しません）");
            }
        } else if (deleteStatusId != null) {
            for (Long issueId : candidates) {
                totalCount++;
                try {
                    logicallyDelete(issueId, deleteStatusId, currentIssues, client, dryRun, logger);
                    successCount++;
                } catch (RuntimeException ex) {
                    addError(errors, logger, formatError("論理削除 #" + issueId, ex));
                }
            }
        }

        return new SyncResult(totalCount, successCount, errors.size(), errors, createdIssueIds);
    }

    /**
     * ログ表示用の親の表記（" parent=行12" / " parent=(仮想親)[大分類 > 中分類]"）。
     */
    private static String parentName(DiffItem item, Map<Integer, String> virtualPaths) {
        Integer parent = item.parentRowNumber();
        return parent != null && parent < 0 ? "(仮想親 [" + virtualPaths.getOrDefault(parent, "?") + "])"
                : "行(行" + parent + ")";
    }

    private static String parentLabel(DiffItem item, Map<Integer, String> virtualPaths) {
        Integer parent = item.parentRowNumber();
        if (parent == null) {
            return "";
        }
        return parent < 0 ? " parent=(仮想親)[" + virtualPaths.getOrDefault(parent, "?") + "]" : " parent=行" + parent;
    }

    /**
     * 1件の論理削除（ステータス変更）を行います。
     */
    private void logicallyDelete(Long issueId, Integer statusId, Map<Long, Map<String, Object>> currentIssues,
            RedmineClient client, boolean dryRun, FileLogger logger) {
        String label = describeIssue(issueId, currentIssues);
        Long currentStatus = IssueComparator.nestedId(currentIssues.get(issueId), "status");
        if (currentStatus != null && currentStatus.longValue() == statusId.longValue()) {
            logger.debug("skip logical delete " + label + " (already status_id=" + statusId + ")");
            return;
        }
        if (dryRun) {
            logger.info("DRY_RUN LOGICAL_DELETE " + label + " status_id=" + statusId);
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("status_id", statusId.longValue());
        logger.debug("API Request: PUT " + client.getBaseUrl() + "/issues/" + issueId + ".json (logical delete)");
        client.updateIssue(issueId, payload);
        logger.info("logically deleted " + label + " (status_id=" + statusId + ")");
    }

    private static String describeIssue(Long issueId, Map<Long, Map<String, Object>> currentIssues) {
        Map<String, Object> issue = currentIssues.get(issueId);
        Object subject = issue == null ? null : issue.get("subject");
        return "#" + issueId + (subject != null ? " 「" + subject + "」" : "");
    }

    /**
     * 更新対象のチケットが同期先プロジェクトのチケット一覧に無い理由を調べます。
     *
     * @return 理由
     */
    private String describeMissing(RedmineClient client, Long issueId) {
        Map<String, Object> issue = client.getIssue(issueId);
        if (issue == null) {
            return "チケット#" + issueId + " がRedmineに存在しません";
        }
        Object project = issue.get("project");
        String name = project instanceof Map<?, ?> map && map.get("name") != null
                ? String.valueOf(map.get("name")) : String.valueOf(IssueComparator.nestedId(issue, "project"));
        return "チケット#" + issueId + " は別プロジェクト（" + name + "）のチケットです";
    }

    /**
     * Redmine APIに送信するissueペイロードを構築します。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildIssuePayload(
            DiffItem item,
            String projectId,
            Object parentIssueId,
            ProjectConfig projectConfig,
            Map<String, String> customFieldMap,
            List<String> customFieldDateColumns) {

        Map<String, Object> issue = new HashMap<>();
        issue.put("project_id", projectId);
        issue.put("subject", item.subject());

        Map<String, Object> payload = item.payload();

        String assignee = (String) payload.get("assignee");
        if (assignee != null && !assignee.isBlank() && isNumeric(assignee)) {
            issue.put("assigned_to_id", Long.parseLong(assignee));
        }

        String startDate = DateParser.normalizeDate((String) payload.get("startDate"));
        if (startDate != null) {
            issue.put("start_date", startDate);
        }

        String dueDate = DateParser.normalizeDate((String) payload.get("dueDate"));
        if (dueDate != null) {
            issue.put("due_date", dueDate);
        }

        Object progress = payload.get("progress");
        if (progress instanceof Number) {
            issue.put("done_ratio", ((Number) progress).intValue());
        } else if (progress instanceof String progressString && isNumeric(progressString)) {
            issue.put("done_ratio", Integer.parseInt(progressString));
        }

        if (parentIssueId != null) {
            issue.put("parent_issue_id", parentIssueId);
        }

        if (item.trackerId() != null) {
            issue.put("tracker_id", item.trackerId());
        }

        StatusConfig statusConfig = projectConfig.getSync() != null ? projectConfig.getSync().getStatus() : null;
        // 仮想親はステータスを送らない（作成時は Redmine の既定、以後は変更しない）
        if (statusConfig != null && statusConfig.isEnabled() && !item.virtual()) {
            String statusValue = resolveStatus(item, payload, statusConfig);
            statusValue = mapStatusValue(statusValue, statusConfig);
            statusValue = mapByDatesDefaultStatusId(statusValue, statusConfig);
            if (statusValue != null && !statusValue.isBlank()) {
                if (isNumeric(statusValue)) {
                    issue.put("status_id", Long.parseLong(statusValue));
                } else {
                    issue.put("status", statusValue);
                }
            }
        }

        Map<String, String> customFieldValues = (Map<String, String>) payload.get("customFields");
        List<Map<String, Object>> customFields = buildCustomFields(customFieldValues, customFieldMap,
                customFieldDateColumns);
        if (!customFields.isEmpty()) {
            issue.put("custom_fields", customFields);
        }
        return issue;
    }

    private List<Map<String, Object>> buildCustomFields(
            Map<String, String> values,
            Map<String, String> mapping,
            List<String> customFieldDateColumns) {
        List<Map<String, Object>> customFields = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return customFields;
        }
        List<String> dateColumns = customFieldDateColumns == null ? List.of() : customFieldDateColumns;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String column = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (!value.isBlank() && dateColumns.contains(column)) {
                String normalized = DateParser.normalizeDate(value);
                if (normalized != null) {
                    value = normalized;
                }
            }
            String field = mapping.get(column);
            if (field == null || field.isBlank()) {
                continue;
            }
            Map<String, Object> cf = new HashMap<>();
            if (isNumeric(field)) {
                cf.put("id", Long.parseLong(field));
            } else {
                cf.put("name", field);
            }
            cf.put("value", value);
            customFields.add(cf);
        }
        return customFields;
    }

    private String resolveStatus(DiffItem item, Map<String, Object> payload, StatusConfig statusConfig) {
        if (item.status() != null && !item.status().isBlank()) {
            return item.status();
        }
        String mode = valueOrDefault(statusConfig.getMode(), STATUS_MODE_BY_DATES);
        if (STATUS_MODE_FIXED.equalsIgnoreCase(mode)) {
            return valueOrDefault(statusConfig.getFixed(), STATUS_NEW);
        }
        String dueActual = (String) payload.get("dueActual");
        if (dueActual != null && !dueActual.isBlank()) {
            return STATUS_CLOSED;
        }
        String startActual = (String) payload.get("startActual");
        if (startActual != null && !startActual.isBlank()) {
            return STATUS_IN_PROGRESS;
        }
        return STATUS_NEW;
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

    private String mapByDatesDefaultStatusId(String statusValue, StatusConfig statusConfig) {
        if (statusValue == null || statusValue.isBlank() || statusConfig == null) {
            return statusValue;
        }
        String mode = valueOrDefault(statusConfig.getMode(), STATUS_MODE_BY_DATES);
        if (!STATUS_MODE_BY_DATES.equalsIgnoreCase(mode)) {
            return statusValue;
        }
        if (STATUS_NEW.equalsIgnoreCase(statusValue)) {
            return "1";
        }
        if (STATUS_IN_PROGRESS.equalsIgnoreCase(statusValue)) {
            return "2";
        }
        if (STATUS_CLOSED.equalsIgnoreCase(statusValue)) {
            return "5";
        }
        return statusValue;
    }

    private Map<String, String> getCustomFieldMap(ProjectConfig projectConfig) {
        if (projectConfig.getSync() == null || projectConfig.getSync().getCustomFieldMap() == null) {
            return Map.of();
        }
        return projectConfig.getSync().getCustomFieldMap();
    }

    private List<String> getCustomFieldDateColumns(ProjectConfig projectConfig) {
        if (projectConfig.getSync() == null || projectConfig.getSync().getCustomFieldDateColumns() == null) {
            return List.of();
        }
        return projectConfig.getSync().getCustomFieldDateColumns();
    }

    private Integer getLogicalDeleteStatusId(ProjectConfig projectConfig) {
        if (projectConfig.getSync() == null || projectConfig.getSync().getDeletion() == null) {
            return null;
        }
        return projectConfig.getSync().getDeletion().getStatusId();
    }

    private void addError(List<String> errors, FileLogger logger, String message) {
        errors.add(message);
        logger.error(message);
    }

    private String formatPayloadForLog(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"issue\":{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else if (value instanceof List) {
                sb.append("[...]");
            } else {
                sb.append(value);
            }
        }
        sb.append("}}");
        return sb.toString();
    }

    private String formatError(String label, RuntimeException ex) {
        if (ex instanceof RestClientResponseException responseEx) {
            String body = responseEx.getResponseBodyAsString();
            String reason = responseEx.getStatusText();
            return "同期失敗: " + label + " 理由=" + responseEx.getStatusCode().value() + " "
                    + (reason == null ? "" : reason) + " " + body;
        }
        String message = ex.getMessage();
        return "同期失敗: " + label + " 理由=" + (message == null ? ex.getClass().getSimpleName() : message);
    }
}
