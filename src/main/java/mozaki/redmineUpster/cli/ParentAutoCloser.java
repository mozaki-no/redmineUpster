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
 * <p>
 * また、新規（Redmine のステータス一覧で最初の未完了ステータス）の親のうち、子に新規でないチケット
 * （進行中・完了など）があるものを進行中にします（これも再帰的。進行中・完了の親を新規に戻すことはしません）。
 * </p>
 */
public final class ParentAutoCloser {

    /** 自動完了の目安にするステータス名（statusMap のキー・Redmine のステータス名） */
    static final List<String> DEFAULT_STATUS_NAMES = List.of("完了", "終了");
    /** 進行中の目安にするステータス名（statusMap のキー・Redmine のステータス名） */
    static final List<String> DEFAULT_IN_PROGRESS_NAMES = List.of("進行中", "作業中");

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
     * 親チケット1件のステータス変更。
     *
     * @param issueId チケットID
     * @param target 変更後のステータス
     * @param close 完了にする場合はtrue（進行中にする場合はfalse）
     */
    public record Change(long issueId, TargetStatus target, boolean close) {
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
     * 進行中にする親チケットを返します。
     * <p>
     * 新規の親で、子に新規でないチケットがあるものが対象です（完了にする親を除く。進行中にした親も
     * 「新規でない子」として数えるので再帰的）。
     * </p>
     *
     * @param nodes チケットの状態（同期後）
     * @param newStatusId 新規のステータスID
     * @param closing 完了にする親（{@link #plan} の結果）
     * @param candidates 変更してよいチケット
     * @param protectedIds 変更しないチケット
     * @return 進行中にするチケットID（深い階層から）
     */
    public static List<Long> planInProgress(Map<Long, Node> nodes, long newStatusId, List<Long> closing,
            Set<Long> candidates, Set<Long> protectedIds) {
        Map<Long, List<Long>> children = new HashMap<>();
        Set<Long> started = new HashSet<>(closing);
        nodes.forEach((id, node) -> {
            if (node.parentId() != null && nodes.containsKey(node.parentId())) {
                children.computeIfAbsent(node.parentId(), key -> new ArrayList<>()).add(id);
            }
            if (node.statusId() != null && node.statusId() != newStatusId) {
                started.add(id);
            }
        });
        List<Long> result = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            List<Long> round = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> entry : children.entrySet()) {
                Long parent = entry.getKey();
                if (started.contains(parent) || !candidates.contains(parent) || protectedIds.contains(parent)) {
                    continue;
                }
                if (entry.getValue().stream().anyMatch(started::contains)) {
                    round.add(parent);
                }
            }
            round.sort(null);
            if (!round.isEmpty()) {
                started.addAll(round);
                result.addAll(round);
                changed = true;
            }
        }
        return result;
    }

    /**
     * 新規のステータスID（Redmine のステータス一覧で最初の未完了ステータス）を返します。
     *
     * @return ステータスID（見つからない場合は null）
     */
    public static Long newStatusId(Map<String, Long> statuses, Set<Long> closedStatusIds) {
        return statuses.values().stream().filter(id -> !closedStatusIds.contains(id)).findFirst().orElse(null);
    }

    /**
     * 進行中にするステータスを決めます。
     * <p>
     * 設定 autoCloseParents.inProgressStatus（名前またはID）→ statusMap の「進行中」「作業中」→
     * Redmine の「進行中」「作業中」の順です。
     * </p>
     *
     * @return ステータス（見つからない・新規と同じ場合は null）
     * @throws IllegalArgumentException 設定したステータスが見つからない・終了ステータスの場合
     */
    public static TargetStatus resolveInProgress(AutoCloseParentsConfig config, StatusConfig statusConfig,
            Map<String, Long> statuses, Set<Long> closedStatusIds) {
        Map<String, String> statusMap = statusConfig != null && statusConfig.getStatusMap() != null
                ? statusConfig.getStatusMap() : Map.of();
        String configured = config != null ? config.getInProgressStatus() : null;
        Long id = null;
        if (configured != null && !configured.isBlank()) {
            id = statusId(configured.trim(), statusMap, statuses);
            if (id == null) {
                throw new IllegalArgumentException("autoCloseParents.inProgressStatus「" + configured
                        + "」が Redmine のステータスにありません");
            }
            if (closedStatusIds.contains(id)) {
                throw new IllegalArgumentException("autoCloseParents.inProgressStatus「" + configured
                        + "」は終了したチケット扱いのステータスです");
            }
        } else {
            for (String name : DEFAULT_IN_PROGRESS_NAMES) {
                Long found = statusId(name, statusMap, statuses);
                if (found != null && !closedStatusIds.contains(found)) {
                    id = found;
                    break;
                }
            }
        }
        if (id == null || id.equals(newStatusId(statuses, closedStatusIds))) {
            return null;
        }
        String label = configured != null && !configured.isBlank() && !isNumeric(configured.trim())
                ? configured.trim() : label(id, statusMap, statuses, DEFAULT_IN_PROGRESS_NAMES);
        return new TargetStatus(id, label);
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
            label = label(id, statusMap, statuses, DEFAULT_STATUS_NAMES);
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
    private static String label(long id, Map<String, String> statusMap, Map<String, Long> statuses,
            List<String> preferred) {
        for (String name : preferred) {
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
     * 親チケットのステータスを変更します（dry-run ではログだけ）。
     * <p>
     * 子の変更に失敗した場合、その親は変更しません（未完了の子がある親は Redmine が完了にできないため）。
     * </p>
     *
     * @param changes 変更（完了 → 進行中の順。それぞれ深い階層から）
     * @return 結果（completed は実際に変更したもの）
     */
    public static Result execute(List<Change> changes, Map<Long, Node> nodes, RedmineClient client,
            boolean dryRun, FileLogger logger) {
        List<String> errors = new ArrayList<>();
        List<Change> completed = new ArrayList<>();
        Set<Long> failed = new HashSet<>();
        for (Change change : changes) {
            Long id = change.issueId();
            Node node = nodes.get(id);
            String label = "#" + id + (node != null && node.subject() != null ? " 「" + node.subject() + "」" : "");
            String reason = change.close() ? "子チケットがすべて完了" : "子チケットに着手済みのものがある";
            boolean childFailed = change.close() && nodes.entrySet().stream()
                    .anyMatch(entry -> id.equals(entry.getValue().parentId()) && failed.contains(entry.getKey()));
            if (childFailed) {
                failed.add(id);
                String message = "親チケットの自動完了をスキップ: " + label + " 理由=子チケットを完了にできませんでした";
                errors.add(message);
                logger.error(message);
                continue;
            }
            String action = change.close() ? "AUTO_CLOSE" : "AUTO_IN_PROGRESS";
            if (dryRun) {
                logger.info("DRY_RUN " + action + " " + label + " status_id=" + change.target().id() + "（" + reason
                        + "）");
                completed.add(change);
                continue;
            }
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("status_id", change.target().id());
                client.updateIssue(id, payload);
                logger.info((change.close() ? "auto-closed" : "auto-started") + " parent " + label + " (status_id="
                        + change.target().id() + "。" + reason + ")");
                completed.add(change);
            } catch (RuntimeException ex) {
                failed.add(id);
                String message = "親チケットのステータス変更に失敗: " + label + " 理由=" + ex.getMessage();
                errors.add(message);
                logger.error(message);
            }
        }
        return new Result(completed, errors);
    }

    /**
     * 自動完了の結果。
     *
     * @param completed 変更した（dry-run では変更する予定の）親チケット
     * @param errors エラー
     */
    public record Result(List<Change> completed, List<String> errors) {
    }
}
