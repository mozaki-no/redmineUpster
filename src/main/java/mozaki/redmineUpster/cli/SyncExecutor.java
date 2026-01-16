package mozaki.redmineUpster.cli;

import static mozaki.redmineUpster.util.StringUtils.isNumeric;
import static mozaki.redmineUpster.util.StringUtils.valueOrDefault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.TrackerConfig;
import mozaki.redmineUpster.domain.IssueLinkEntity;
import mozaki.redmineUpster.repository.IssueLinkRepository;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.util.DateParser;

/**
 * 同期実行クラス。
 * <p>
 * 差分アイテムをRedmineに同期します。
 * CREATE（新規作成）とUPDATE（更新）のアクションを実行し、
 * 成功時はIssueLinkを保存します。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class SyncExecutor {

    /** アクション: 新規作成 */
    private static final String ACTION_CREATE = "CREATE";
    /** ステータス: 新規 */
    private static final String STATUS_NEW = "New";
    /** ステータス: 進行中 */
    private static final String STATUS_IN_PROGRESS = "In Progress";
    /** ステータス: 完了 */
    private static final String STATUS_CLOSED = "Closed";
    /** ステータスモード: 日付ベース */
    private static final String STATUS_MODE_BY_DATES = "BY_DATES";
    /** ステータスモード: 固定値 */
    private static final String STATUS_MODE_FIXED = "FIXED";
    /** 階層パス区切り文字 */
    private static final String HIERARCHY_DELIMITER = ">";

    private final IssueLinkRepository issueLinkRepository;

    /**
     * 同期を実行します。
     *
     * @param items 差分アイテムのリスト
     * @param projectConfig プロジェクト設定
     * @param client Redmineクライアント
     * @param dryRun ドライランモードの場合はtrue
     * @param logger ファイルロガー
     * @return 同期結果
     */
    public SyncResult execute(
            List<DiffItem> items,
            ProjectConfig projectConfig,
            RedmineClient client,
            boolean dryRun,
            FileLogger logger) {

        int totalCount = items.size();
        int successCount = 0;
        int errorCount = 0;
        List<String> errors = new ArrayList<>();

        Map<String, String> customFieldMap = getCustomFieldMap(projectConfig);
        Map<String, Long> createdIssueIds = new HashMap<>();

        // 階層の深さでソート（親を先に処理）
        List<DiffItem> sortedItems = new ArrayList<>(items);
        sortedItems.sort(Comparator.comparingInt(this::depth));

        for (DiffItem item : sortedItems) {
            try {
                if (dryRun) {
                    logger.info("DRY_RUN " + item.action() + " " + item.externalKey() + " " + item.subject());
                    successCount++;
                    continue;
                }

                Map<String, Object> issuePayload = buildIssuePayload(item, client, projectConfig, customFieldMap, createdIssueIds);

                if (ACTION_CREATE.equalsIgnoreCase(item.action())) {
                    Long issueId = client.createIssue(issuePayload);
                    if (issueId == null) {
                        errorCount++;
                        String errorMsg = "create failed: " + item.externalKey();
                        errors.add(errorMsg);
                        logger.error(errorMsg);
                        continue;
                    }
                    IssueLinkEntity link = new IssueLinkEntity(item.externalKey(), issueId);
                    issueLinkRepository.save(link);
                    createdIssueIds.put(item.externalKey(), issueId);
                    logger.info("created issue " + issueId + " for " + item.externalKey());
                    successCount++;
                } else {
                    Optional<IssueLinkEntity> link = issueLinkRepository.findByExternalKey(item.externalKey());
                    if (link.isEmpty()) {
                        errorCount++;
                        String errorMsg = "missing issue link for " + item.externalKey();
                        errors.add(errorMsg);
                        logger.error(errorMsg);
                        continue;
                    }
                    client.updateIssue(link.get().getIssueId(), issuePayload);
                    logger.info("updated issue " + link.get().getIssueId() + " for " + item.externalKey());
                    successCount++;
                }
            } catch (RuntimeException ex) {
                errorCount++;
                String errorMsg = "sync failed for " + item.externalKey() + ": " + ex.getMessage();
                errors.add(errorMsg);
                logger.error(errorMsg);
            }
        }

        return new SyncResult(totalCount, successCount, errorCount, errors);
    }

    /**
     * Redmine APIに送信するissueペイロードを構築します。
     *
     * @param item 差分アイテム
     * @param client Redmineクライアント
     * @param projectConfig プロジェクト設定
     * @param customFieldMap カスタムフィールドマップ
     * @param createdIssueIds 今回の実行で作成されたissue ID
     * @return issueペイロード
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildIssuePayload(
            DiffItem item,
            RedmineClient client,
            ProjectConfig projectConfig,
            Map<String, String> customFieldMap,
            Map<String, Long> createdIssueIds) {

        Map<String, Object> issue = new HashMap<>();
        String projectId = client.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("redmine.project-id is not configured");
        }
        issue.put("project_id", projectId);
        issue.put("subject", item.subject());

        Map<String, Object> payload = item.payload();

        // 担当者
        String assignee = (String) payload.get("assignee");
        if (assignee != null && !assignee.isBlank()) {
            if (isNumeric(assignee)) {
                issue.put("assigned_to_id", Long.parseLong(assignee));
            }
        }

        // 開始日
        String startDate = DateParser.normalizeDate((String) payload.get("startDate"));
        if (startDate != null) {
            issue.put("start_date", startDate);
        }

        // 期限
        String dueDate = DateParser.normalizeDate((String) payload.get("dueDate"));
        if (dueDate != null) {
            issue.put("due_date", dueDate);
        }

        // 親チケット
        Long parentIssueId = resolveParentIssueId(item.parentKey(), createdIssueIds);
        if (parentIssueId != null) {
            issue.put("parent_issue_id", parentIssueId);
        }

        // トラッカー
        TrackerConfig trackerConfig = null;
        if (projectConfig.getSync() != null) {
            trackerConfig = projectConfig.getSync().getTracker();
        }
        if (trackerConfig != null && trackerConfig.isEnabled() && trackerConfig.getValue() != null && !trackerConfig.getValue().isBlank()) {
            String trackerValue = trackerConfig.getValue();
            if (isNumeric(trackerValue)) {
                issue.put("tracker_id", Long.parseLong(trackerValue));
            } else {
                issue.put("tracker", trackerValue);
            }
        }

        // ステータス
        StatusConfig statusConfig = null;
        if (projectConfig.getSync() != null) {
            statusConfig = projectConfig.getSync().getStatus();
        }
        if (statusConfig != null && statusConfig.isEnabled()) {
            String statusValue = resolveStatus(item, payload, statusConfig);
            if (statusValue != null && !statusValue.isBlank()) {
                if (isNumeric(statusValue)) {
                    issue.put("status_id", Long.parseLong(statusValue));
                } else {
                    issue.put("status", statusValue);
                }
            }
        }

        // カスタムフィールド
        Map<String, String> customFieldValues = (Map<String, String>) payload.get("customFields");
        List<Map<String, Object>> customFields = buildCustomFields(customFieldValues, customFieldMap);
        if (!customFields.isEmpty()) {
            issue.put("custom_fields", customFields);
        }

        return issue;
    }

    /**
     * 親チケットのissue IDを解決します。
     *
     * @param parentKey 親の外部キー
     * @param createdIssueIds 今回の実行で作成されたissue ID
     * @return 親のissue ID（見つからない場合はnull）
     */
    private Long resolveParentIssueId(String parentKey, Map<String, Long> createdIssueIds) {
        if (parentKey == null || parentKey.isBlank()) {
            return null;
        }
        Long created = createdIssueIds.get(parentKey);
        if (created != null) {
            return created;
        }
        Optional<IssueLinkEntity> link = issueLinkRepository.findByExternalKey(parentKey);
        return link.map(IssueLinkEntity::getIssueId).orElse(null);
    }

    /**
     * カスタムフィールドのリストを構築します。
     *
     * @param values カスタムフィールド値
     * @param mapping カラム名からフィールド名へのマッピング
     * @return カスタムフィールドのリスト
     */
    private List<Map<String, Object>> buildCustomFields(Map<String, String> values, Map<String, String> mapping) {
        List<Map<String, Object>> customFields = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return customFields;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String column = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
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

    /**
     * ステータスを解決します。
     *
     * @param item 差分アイテム
     * @param payload ペイロード
     * @param statusConfig ステータス設定
     * @return ステータス値
     */
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
     * 階層の深さを取得します。
     *
     * @param item 差分アイテム
     * @return 階層の深さ
     */
    private int depth(DiffItem item) {
        String path = item.levelPath();
        if (path == null || path.isBlank()) {
            return 0;
        }
        return path.split(HIERARCHY_DELIMITER).length;
    }
}
