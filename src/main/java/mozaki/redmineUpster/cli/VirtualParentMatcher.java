package mozaki.redmineUpster.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 仮想親チケット（ファイルに行がない祖先）を、同期先プロジェクトの既存チケットに対応付けるクラス。
 * <p>
 * 仮想親にはチケットID列がなく、DBもないため、毎回 Redmine の現在の状態から見つけ直します。
 * 浅い階層から順に、次の条件をすべて満たすチケットを探します。
 * <ul>
 *   <li>親チケットが同じ（最上位の仮想親は親なし。親がファイルの行ならその行のチケットID、
 *       親が仮想親なら先に見つかったそのチケットID）</li>
 *   <li>件名が完全に一致（前後の空白は無視）</li>
 *   <li>トラッカーが仮想親のトラッカー</li>
 *   <li>ファイルの行のチケットID、または別の仮想親に対応付け済みのチケットではない</li>
 *   <li>論理削除ステータス（sync.deletion.statusId）ではない（論理削除済みの仮想親は再利用せず、新しく作る）</li>
 * </ul>
 * 説明に {@link #MARKER} を含むチケット（このツールが作成した仮想親）を優先し、その中で最も小さいIDを使います。
 * 複数見つかった場合は警告を出します。見つからなければ新規作成（CREATE）のままです。
 * 親が新規作成の場合は、その下の仮想親も必ず新規作成になります。
 * </p>
 */
public final class VirtualParentMatcher {

    /** 仮想親チケットの説明に入れる目印（作成時のみ設定） */
    public static final String MARKER = "[redmineUpster] 仮想親チケット（Excelに行がないため自動作成）";

    private VirtualParentMatcher() {
    }

    /**
     * 仮想親を既存チケットに対応付けます。
     *
     * @param items 差分アイテム（ファイルの行と仮想親）
     * @param projectIssues 同期先プロジェクトのチケット（チケットID → チケット情報）
     * @param deleteStatusId 論理削除ステータスID（null可。このステータスのチケットは対応付けない）
     * @param logger ファイルロガー（null可）
     * @return 対応付け後の差分アイテム（見つかった仮想親は既存チケットIDの UPDATE。順序は元のまま）
     */
    public static List<DiffItem> match(List<DiffItem> items, Map<Long, Map<String, Object>> projectIssues,
            Integer deleteStatusId, FileLogger logger) {
        if (items.stream().noneMatch(DiffItem::virtual)) {
            return items;
        }
        Map<Long, Map<String, Object>> issues = projectIssues == null ? Map.of() : projectIssues;
        Map<Integer, Long> rowIssueIds = new HashMap<>();
        Set<Long> claimed = new HashSet<>();
        for (DiffItem item : items) {
            if (!item.virtual() && item.issueId() != null) {
                rowIssueIds.put(item.rowNumber(), item.issueId());
                claimed.add(item.issueId());
            }
        }
        List<DiffItem> virtuals = new ArrayList<>(items.stream().filter(DiffItem::virtual).toList());
        virtuals.sort(Comparator.comparingInt(DiffItem::depth).thenComparingInt(DiffItem::rowNumber));
        Map<Integer, DiffItem> matched = new HashMap<>();
        for (DiffItem virtual : virtuals) {
            Long parentId = null;
            if (virtual.parentRowNumber() != null) {
                parentId = rowIssueIds.get(virtual.parentRowNumber());
                if (parentId == null) {
                    continue; // 親が新規作成 → この仮想親も新規作成
                }
            }
            List<Long> found = new ArrayList<>();
            List<Long> marked = new ArrayList<>();
            for (Map.Entry<Long, Map<String, Object>> entry : issues.entrySet()) {
                Map<String, Object> issue = entry.getValue();
                Long statusId = IssueComparator.nestedId(issue, "status");
                if (claimed.contains(entry.getKey())
                        || (deleteStatusId != null && statusId != null && statusId.longValue() == deleteStatusId)
                        || !Objects.equals(parentId, IssueComparator.nestedId(issue, "parent"))
                        || !text(virtual.subject()).equals(text(issue.get("subject")))
                        || !Objects.equals(virtual.trackerId(), IssueComparator.nestedId(issue, "tracker"))) {
                    continue;
                }
                found.add(entry.getKey());
                if (text(issue.get("description")).contains(MARKER)) {
                    marked.add(entry.getKey());
                }
            }
            if (found.isEmpty()) {
                continue;
            }
            List<Long> preferred = marked.isEmpty() ? found : marked;
            Long issueId = preferred.stream().min(Long::compare).orElseThrow();
            if (found.size() > 1 && logger != null) {
                found.sort(null);
                logger.warn("仮想親 [" + virtual.levelPath() + "] に当てはまるチケットが複数あります " + found
                        + "。#" + issueId + " を使います（不要なチケットは手動で整理してください）");
            }
            claimed.add(issueId);
            rowIssueIds.put(virtual.rowNumber(), issueId);
            matched.put(virtual.rowNumber(), virtual.withExistingIssue(issueId));
        }
        List<DiffItem> result = new ArrayList<>();
        for (DiffItem item : items) {
            result.add(item.virtual() ? matched.getOrDefault(item.rowNumber(), item) : item);
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
