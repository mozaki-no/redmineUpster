package mozaki.redmineUpster.cli;

import static mozaki.redmineUpster.cli.SyncConstants.STATUS_CLOSED;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_IN_PROGRESS;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_MODE_BY_DATES;
import static mozaki.redmineUpster.cli.SyncConstants.STATUS_NEW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.util.StringUtils;

/**
 * チケットの「担当」と「ステータス」の名前を Redmine のIDに変換するクラス。
 * <p>
 * 担当は ユーザーID ＞ ログインID ＞ 氏名（「姓 名」「名 姓」、空白なしも可）＞ グループ名 の順に探します。
 * 見つからない場合は警告を出し、その行の担当は変更しません（従来どおり）。
 * ステータスは statusMap にない名前を Redmine のステータス名から探します。
 * ユーザー・グループ・ステータスの一覧は必要になったときだけ取得します。
 * </p>
 */
final class TicketValueResolver {

    private static final Set<String> BY_DATES_KEYWORDS = Set.of(STATUS_NEW.toLowerCase(Locale.ROOT),
            STATUS_IN_PROGRESS.toLowerCase(Locale.ROOT), STATUS_CLOSED.toLowerCase(Locale.ROOT));

    private final RedmineClient client;
    private final FileLogger logger;
    private final Set<String> plannedLogins;
    private Map<Long, Map<String, Object>> users;
    private Map<Long, Map<String, Object>> groups;
    private Map<String, Long> assigneeIndex;
    private Map<String, Long> statusIndex;
    private final Set<String> warned = new HashSet<>();

    /**
     * @param client クライアント
     * @param logger ロガー
     * @param users 取得済みのユーザー（null なら必要時に取得）
     * @param groups 取得済みのグループ（null なら必要時に取得）
     * @param plannedLogins 今回作成するユーザーのログインID（dry-run では ID がまだない）
     */
    TicketValueResolver(RedmineClient client, FileLogger logger, Map<Long, Map<String, Object>> users,
            Map<Long, Map<String, Object>> groups, Set<String> plannedLogins) {
        this.client = client;
        this.logger = logger;
        this.users = users;
        this.groups = groups;
        this.plannedLogins = new HashSet<>();
        plannedLogins.forEach(login -> this.plannedLogins.add(normalize(login)));
    }

    /**
     * 担当・ステータスの名前をIDに置き換えた差分アイテムを返します。
     *
     * @param items 差分アイテム
     * @param statusConfig ステータス設定（null可）
     * @return 置き換え後の差分アイテム
     */
    List<DiffItem> resolve(List<DiffItem> items, StatusConfig statusConfig) {
        List<DiffItem> result = new ArrayList<>(items.size());
        for (DiffItem item : items) {
            Map<String, Object> payload = item.payload();
            Object rawAssignee = payload.get("assignee");
            String assignee = rawAssignee == null ? "" : String.valueOf(rawAssignee).trim();
            if (!assignee.isEmpty() && !StringUtils.isNumeric(assignee)) {
                payload = new LinkedHashMap<>(payload);
                Long id = resolveAssignee(assignee, item);
                payload.put("assignee", id == null ? "" : String.valueOf(id));
            }
            String status = item.status();
            if (needsStatusLookup(status, statusConfig)) {
                Long statusId = resolveStatus(status, item);
                if (statusId != null) {
                    status = String.valueOf(statusId);
                }
            }
            if (payload == item.payload() && status == item.status()) {
                result.add(item);
            } else {
                result.add(new DiffItem(item.rowNumber(), item.issueId(), item.subject(), item.levelPath(),
                        item.depth(), item.parentRowNumber(), item.action(), status, item.trackerId(), payload,
                        item.virtual()));
            }
        }
        return result;
    }

    private Long resolveAssignee(String assignee, DiffItem item) {
        if (assigneeIndex == null) {
            assigneeIndex = buildAssigneeIndex();
        }
        String key = normalize(assignee);
        Long id = assigneeIndex.get(key);
        if (id == null) {
            id = assigneeIndex.get(key.replaceAll("\\s+", ""));
        }
        if (id != null) {
            return id;
        }
        if (plannedLogins.contains(key)) {
            logger.info(item.label() + ": 担当「" + assignee + "」は今回作成するユーザーのため、dry-run では設定しません");
        } else {
            warnOnce("assignee:" + key, "担当「" + assignee + "」に当たるユーザー・グループが見つかりません（" + item.label()
                    + " など。ユーザーID・ログインID・氏名・グループ名で指定）。その行の担当は変更しません");
        }
        return null;
    }

    private Map<String, Long> buildAssigneeIndex() {
        Map<String, Long> index = new HashMap<>();
        try {
            if (groups == null) {
                groups = client.listGroups();
            }
            for (Map.Entry<Long, Map<String, Object>> entry : groups.entrySet()) {
                index.putIfAbsent(normalize(text(entry.getValue().get("name"))), entry.getKey());
            }
        } catch (RuntimeException ex) {
            warnOnce("groups", "グループの一覧を取得できないため、担当のグループ名は使えません: " + ex.getMessage());
        }
        try {
            if (users == null) {
                users = client.listUsers();
            }
            // 後から入れたものが優先（ログインID ＞ 氏名 ＞ グループ名）
            for (Map.Entry<Long, Map<String, Object>> entry : users.entrySet()) {
                String last = text(entry.getValue().get("lastname"));
                String first = text(entry.getValue().get("firstname"));
                for (String name : List.of(last + " " + first, first + " " + last, last + first, first + last)) {
                    if (!name.isBlank()) {
                        index.put(normalize(name), entry.getKey());
                    }
                }
            }
            for (Map.Entry<Long, Map<String, Object>> entry : users.entrySet()) {
                String login = text(entry.getValue().get("login"));
                if (!login.isEmpty()) {
                    index.put(normalize(login), entry.getKey());
                }
            }
        } catch (RuntimeException ex) {
            warnOnce("users", "ユーザーの一覧を取得できないため（管理者の API キーが必要）、担当はユーザーIDで指定してください: "
                    + ex.getMessage());
        }
        return index;
    }

    private boolean needsStatusLookup(String status, StatusConfig statusConfig) {
        if (statusConfig == null || !statusConfig.isEnabled() || status == null || status.isBlank()
                || StringUtils.isNumeric(status.trim())) {
            return false;
        }
        if (statusConfig.getStatusMap() != null && statusConfig.getStatusMap().containsKey(status)) {
            return false;
        }
        String mode = StringUtils.valueOrDefault(statusConfig.getMode(), STATUS_MODE_BY_DATES);
        return !(STATUS_MODE_BY_DATES.equalsIgnoreCase(mode) && BY_DATES_KEYWORDS.contains(normalize(status)));
    }

    private Long resolveStatus(String status, DiffItem item) {
        if (statusIndex == null) {
            statusIndex = new HashMap<>();
            try {
                client.listIssueStatuses().forEach((name, id) -> statusIndex.put(normalize(name), id));
            } catch (RuntimeException ex) {
                warnOnce("statuses", "ステータスの一覧を取得できません: " + ex.getMessage());
            }
        }
        Long id = statusIndex.get(normalize(status));
        if (id == null) {
            warnOnce("status:" + status, "ステータス「" + status + "」が Redmine にありません（" + item.label()
                    + " など）。statusMap に追加するか Redmine のステータス名にしてください");
        }
        return id;
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            logger.warn(message);
        }
    }

    private static String normalize(String value) {
        return value.trim().replace('　', ' ').toLowerCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
