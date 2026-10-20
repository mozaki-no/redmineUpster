package mozaki.redmineUpster.cli;

import static mozaki.redmineUpster.util.StringUtils.isNumeric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mozaki.redmineUpster.config.SyncConfigProperties.AutoCloseParentsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.service.RedmineClient;

/**
 * 親チケットの自動完了。
 * <p>
 * 子チケットがすべて完了（Redmine で is_closed のステータス）になった親チケットを完了にします。
 * 孫→子→親の順に再帰的に判定するので、深い階層から順に閉じていきます（Redmine は未完了の子がある親を
 * 完了にできないため、この順で送ります）。子は Redmine の同期先プロジェクトのチケットすべて
 * （Excelにないチケットを含む）で判定します。完了した親を未完了に戻すことはしません。
 * </p>
 */
public final class ParentAutoCloser {

    /** 自動完了の目安にするステータス名（statusMap のキー・Redmine のステータス名） */
    static final List<String> DEFAULT_STATUS_NAMES = List.of("完了", "終了");

    private ParentAutoCloser() {
    }

    /**
     * チケット1件の状態。
     *
     * @param parentId 親チケットID（親なしは null）
     * @param statusId ステータスID
     * @param subject 件名
     */
    public record Node(Long parentId, Long statusId, String subject) {
    }

    /**
     * 完了にするステータス。
     *
     * @param id ステータスID
     * @param label Excel のステータス列へ書き戻す値
     */
    public record TargetStatus(long id, String label) {
    }

    /**
     * Redmine のチケット一覧（チケットID → チケット情報）から状態を作ります。
     */
    public static Map<Long, Node> nodes(Map<Long, Map<String, Object>> issues) {
        Map<Long, Node> nodes = new LinkedHashMap<>();
        if (issues == null) {
            return nodes;
        }
        issues.forEach((id, issue) -> nodes.put(id, new Node(IssueComparator.nestedId(issue, "parent"),
                IssueComparator.nestedId(issue, "status"),
                issue.get("subject") == null ? null : String.valueOf(issue.get("subject")))));
        return nodes;
    }

    /**
     * 完了にする親チケットを、完了にする順（深い階層から）に返します。
     *
     * @param nodes チケットの状態（同期後）
     * @param closedStatusIds 終了ステータスのID
     * @param candidates 完了にしてよいチケット（今回同期したチケットとその祖先）
     * @param protectedIds 完了にしないチケット（ルートチケットとその祖先）
     * @return 完了にするチケットID
     */
    public static List<Long> plan(Map<Long, Node> nodes, Set<Long> closedStatusIds, Set<Long> candidates,
            Set<Long> protectedIds) {
        Map<Long, List<Long>> children = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        nodes.forEach((id, node) -> {
            if (node.parentId() != null && nodes.containsKey(node.parentId())) {
                children.computeIfAbsent(node.parentId(), key -> new ArrayList<>()).add(id);
            }
            if (node.statusId() != null && closedStatusIds.contains(node.statusId())) {
                closed.add(id);
            }
        });
        List<Long> result = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            List<Long> round = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> entry : children.entrySet()) {
                Long parent = entry.getKey();
                if (closed.contains(parent) || !candidates.contains(parent) || protectedIds.contains(parent)) {
                    continue;
                }
                if (closed.containsAll(entry.getValue())) {
                    round.add(parent);
                }
            }
            round.sort(null);
            if (!round.isEmpty()) {
                closed.addAll(round);
                result.addAll(round);
                changed = true;
            }
        }
        return result;
    }

    /**
     * 今回同期したチケットとその祖先を返します（自動完了の対象。ルートチケットより上には進まない）。
     */
    public static Set<Long> candidates(Set<Long> syncedIds, Map<Long, Node> nodes, Set<Long> protectedIds) {
        Set<Long> result = new HashSet<>();
        for (Long id : syncedIds) {
            Long current = id;
            while (current != null && !protectedIds.contains(current) && result.add(current)) {
                Node node = nodes.get(current);
                current = node == null ? null : node.parentId();
            }
        }
        return result;
    }

    /**
     * 完了にするステータスを決めます。
     * <p>
     * 設定 autoCloseParents.status（名前またはID。名前は statusMap → Redmine のステータス名）→
     * statusMap の「完了」「終了」→ Redmine の「完了」「終了」→ 最初の終了ステータスの順です。
     * </p>
     *
     * @return ステータス（決められない場合は null）
     * @throws IllegalArgumentException 設定したステータスが見つからない・終了ステータスでない場合
     */
    public static TargetStatus resolveTarget(AutoCloseParentsConfig config, StatusConfig statusConfig,
            Map<String, Long> statuses, Set<Long> closedStatusIds) {
        Map<String, String> statusMap = statusConfig != null && statusConfig.getStatusMap() != null
                ? statusConfig.getStatusMap() : Map.of();
        String configured = config != null ? config.getStatus() : null;
        Long id = null;
        String label = null;
        if (configured != null && !configured.isBlank()) {
            id = statusId(configured.trim(), statusMap, statuses);
            if (id == null) {
                throw new IllegalArgumentException("autoCloseParents.status「" + configured
                        + "」が Redmine のステータスにありません");
            }
            label = isNumeric(configured.trim()) ? null : configured.trim();
        } else {
            for (String name : DEFAULT_STATUS_NAMES) {
                Long found = statusId(name, statusMap, statuses);
                if (found != null && closedStatusIds.contains(found)) {
                    id = found;
                    break;
                }
            }
            if (id == null && !closedStatusIds.isEmpty()) {
                id = closedStatusIds.iterator().next();
            }
            if (id == null) {
                return null;
            }
        }
        if (!closedStatusIds.contains(id)) {
            throw new IllegalArgumentException("autoCloseParents.status「" + configured + "」（status_id=" + id
                    + "）は Redmine で終了したチケット扱いのステータスではありません");
        }
        if (label == null) {
            label = label(id, statusMap, statuses);
        }
        return new TargetStatus(id, label);
    }

    private static Long statusId(String value, Map<String, String> statusMap, Map<String, Long> statuses) {
        if (isNumeric(value)) {
            return Long.parseLong(value);
        }
        String mapped = statusMap.get(value);
        if (mapped != null && isNumeric(mapped.trim())) {
            return Long.parseLong(mapped.trim());
        }
        String name = mapped != null ? mapped.trim() : value;
        for (Map.Entry<String, Long> entry : statuses.entrySet()) {
            if (entry.getKey().trim().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Excel へ書き戻す名前（statusMap でそのIDになる名前 → Redmine のステータス名） */
    private static String label(long id, Map<String, String> statusMap, Map<String, Long> statuses) {
        for (String name : DEFAULT_STATUS_NAMES) {
            String mapped = statusMap.get(name);
            if (mapped != null && mapped.trim().equals(String.valueOf(id))) {
                return name;
            }
        }
        for (Map.Entry<String, String> entry : statusMap.entrySet()) {
            if (entry.getValue() != null && entry.getValue().trim().equals(String.valueOf(id))) {
                return entry.getKey();
            }
        }
        for (Map.Entry<String, Long> entry : statuses.entrySet()) {
            if (entry.getValue() == id) {
                return entry.getKey();
            }
        }
        return String.valueOf(id);
    }

    /**
     * 親チケットを完了にします（dry-run ではログだけ）。
     * <p>
     * 子の完了に失敗した場合、その親は完了にしません（Redmine が受け付けないため）。
     * </p>
     *
     * @param plan 完了にするチケットID（{@link #plan} の順）
     * @return 結果（completed は実際に完了にしたチケットID）
     */
    public static Result execute(List<Long> plan, Map<Long, Node> nodes, TargetStatus target, RedmineClient client,
            boolean dryRun, FileLogger logger) {
        List<String> errors = new ArrayList<>();
        List<Long> completed = new ArrayList<>();
        Set<Long> failed = new HashSet<>();
        for (Long id : plan) {
            Node node = nodes.get(id);
            String label = "#" + id + (node != null && node.subject() != null ? " 「" + node.subject() + "」" : "");
            boolean childFailed = nodes.entrySet().stream()
                    .anyMatch(entry -> id.equals(entry.getValue().parentId()) && failed.contains(entry.getKey()));
            if (childFailed) {
                failed.add(id);
                String message = "親チケットの自動完了をスキップ: " + label + " 理由=子チケットを完了にできませんでした";
                errors.add(message);
                logger.error(message);
                continue;
            }
            if (dryRun) {
                logger.info("DRY_RUN AUTO_CLOSE " + label + " status_id=" + target.id() + "（子チケットがすべて完了）");
                completed.add(id);
                continue;
            }
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("status_id", target.id());
                client.updateIssue(id, payload);
                logger.info("auto-closed parent " + label + " (status_id=" + target.id() + "。子チケットがすべて完了)");
                completed.add(id);
            } catch (RuntimeException ex) {
                failed.add(id);
                String message = "親チケットの自動完了に失敗: " + label + " 理由=" + ex.getMessage();
                errors.add(message);
                logger.error(message);
            }
        }
        return new Result(completed, errors);
    }

    /**
     * 自動完了の結果。
     *
     * @param completed 完了にした（dry-run では完了にする予定の）チケットID
     * @param errors エラー
     */
    public record Result(List<Long> completed, List<String> errors) {
    }
}
