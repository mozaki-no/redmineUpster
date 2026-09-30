package mozaki.redmineUpster.cli;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 同期の対象（CLI の {@code --targets=tickets,users,groups}）。
 */
public enum SyncTarget {
    /** チケット */
    TICKETS,
    /** ユーザー */
    USERS,
    /** グループ */
    GROUPS;

    /**
     * カンマ区切りの指定を解析します（英語・日本語どちらも可）。
     *
     * @param raw 指定（null/空ならすべて）
     * @return 対象
     * @throws IllegalArgumentException 不明な対象がある場合
     */
    public static Set<SyncTarget> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return EnumSet.allOf(SyncTarget.class);
        }
        Set<SyncTarget> targets = EnumSet.noneOf(SyncTarget.class);
        for (String token : raw.split("[,、]")) {
            String value = token.trim().toLowerCase(Locale.ROOT);
            switch (value) {
                case "" -> {
                }
                case "tickets", "ticket", "issues", "チケット" -> targets.add(TICKETS);
                case "users", "user", "ユーザー" -> targets.add(USERS);
                case "groups", "group", "グループ" -> targets.add(GROUPS);
                case "all", "すべて" -> targets.addAll(EnumSet.allOf(SyncTarget.class));
                default -> throw new IllegalArgumentException(
                        "--targets の値が不正です: " + token.trim() + "（tickets, users, groups のカンマ区切り）");
            }
        }
        return targets.isEmpty() ? EnumSet.allOf(SyncTarget.class) : targets;
    }
}
