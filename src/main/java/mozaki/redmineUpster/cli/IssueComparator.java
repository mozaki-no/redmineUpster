package mozaki.redmineUpster.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 送信予定のチケット内容と、Redmineから取得した現在のチケットを比較するクラス。
 * <p>
 * このツールが送信する項目だけを比較し、違いがなければ更新をスキップします
 * （DBに前回の送信内容を保存しない代わりに、Redmineの現在の状態を正とします）。
 * 送信しない項目（空欄の日付など）は比較しません。
 * </p>
 */
public final class IssueComparator {

    private IssueComparator() {
    }

    /**
     * 送信予定の内容のうち、Redmineの現在の値と異なる項目名を返します。
     *
     * @param payload 送信予定のチケット内容（Redmine APIの issue の中身）
     * @param issue Redmineから取得したチケット（GET /issues.json の1要素）
     * @return 異なる項目名（空なら変更なし）
     */
    public static List<String> changedFields(Map<String, Object> payload, Map<String, Object> issue) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            boolean same = switch (key) {
                case "project_id" -> true; // 取得時点で同期先プロジェクトのチケットに限定済み
                case "subject" -> text(value).equals(text(issue.get("subject")));
                case "tracker_id" -> sameId(value, nestedId(issue, "tracker"));
                case "status_id" -> sameId(value, nestedId(issue, "status"));
                case "status" -> text(value).equals(text(nestedValue(issue, "status", "name")));
                case "assigned_to_id" -> sameId(value, nestedId(issue, "assigned_to"));
                case "parent_issue_id" -> sameId(value, nestedId(issue, "parent"));
                case "start_date", "due_date" -> text(value).equals(text(issue.get(key)));
                case "done_ratio" -> sameId(value, issue.get("done_ratio") instanceof Number n ? n.longValue() : null);
                case "custom_fields" -> sameCustomFields(value, issue.get("custom_fields"));
                default -> false; // 知らない項目は変更ありとみなす（安全側）
            };
            if (!same) {
                changed.add(key);
            }
        }
        return changed;
    }

    /**
     * {@code issue.get(key).get("id")} を数値で返します。
     *
     * @param issue チケット
     * @param key 項目名（status, tracker, project, parent など）
     * @return ID（なければnull）
     */
    public static Long nestedId(Map<String, Object> issue, String key) {
        Object value = nestedValue(issue, key, "id");
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Object nestedValue(Map<String, Object> issue, String key, String field) {
        if (issue == null) {
            return null;
        }
        Object value = issue.get(key);
        if (value instanceof Map<?, ?> map) {
            return map.get(field);
        }
        return null;
    }

    private static boolean sameId(Object expected, Long actual) {
        Long expectedId = toLong(expected);
        return Objects.equals(expectedId, actual);
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = text(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> collection) {
            List<String> parts = new ArrayList<>();
            for (Object element : collection) {
                parts.add(text(element));
            }
            parts.sort(null);
            return String.join(",", parts);
        }
        return String.valueOf(value).trim();
    }

    private static boolean sameCustomFields(Object expected, Object actual) {
        if (!(expected instanceof Collection<?> expectedFields)) {
            return expected == null;
        }
        List<?> actualFields = actual instanceof List<?> list ? list : List.of();
        for (Object element : expectedFields) {
            if (!(element instanceof Map<?, ?> field)) {
                return false;
            }
            Map<?, ?> current = findCustomField(actualFields, field);
            if (current == null) {
                // チケットのトラッカーで使えないカスタムフィールドはRedmineが無視するため、比較しない
                continue;
            }
            if (!text(field.get("value")).equals(text(current.get("value")))) {
                return false;
            }
        }
        return true;
    }

    private static Map<?, ?> findCustomField(List<?> actualFields, Map<?, ?> field) {
        Long id = toLong(field.get("id"));
        String name = field.get("name") == null ? null : String.valueOf(field.get("name"));
        for (Object element : actualFields) {
            if (!(element instanceof Map<?, ?> current)) {
                continue;
            }
            if (id != null && id.equals(toLong(current.get("id")))) {
                return current;
            }
            if (id == null && name != null && name.equals(String.valueOf(current.get("name")))) {
                return current;
            }
        }
        return null;
    }
}
