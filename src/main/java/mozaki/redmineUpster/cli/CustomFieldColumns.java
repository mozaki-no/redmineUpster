package mozaki.redmineUpster.cli;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 「CF:名前」列（カスタムフィールド）の共通処理。
 * <p>
 * チケット・ユーザー・グループのシートで、見出しが {@code CF:} で始まる列をカスタムフィールドとして扱います。
 * 名前 → ID は Redmine から取得したチケット・ユーザー・グループの custom_fields から解決します
 * （管理者でなくてもチケットのカスタムフィールドは使えます）。空欄のセルは「変更しない」の意味です。
 * </p>
 */
public final class CustomFieldColumns {

    /** 列名の接頭辞 */
    public static final String PREFIX = "CF:";

    /**
     * カスタムフィールドの定義。
     *
     * @param id ID
     * @param name 名前
     * @param multiple 複数選択の場合 true
     */
    public record Definition(long id, String name, boolean multiple) {
    }

    private CustomFieldColumns() {
    }

    /**
     * カスタムフィールドの列か判定します（「CF:」「cf：」なども可）。
     *
     * @param header 見出し
     * @return カスタムフィールドの列なら true
     */
    public static boolean isColumn(String header) {
        return header != null && header.length() > 3 && header.substring(0, 2).equalsIgnoreCase("CF")
                && (header.charAt(2) == ':' || header.charAt(2) == '：') && !name(header).isEmpty();
    }

    /**
     * 列名からカスタムフィールド名を取り出します。
     *
     * @param header 見出し（CF:名前）
     * @return 名前
     */
    public static String name(String header) {
        return header.substring(3).trim();
    }

    /**
     * 行の「CF:名前」列のうち値のあるものを返します。
     *
     * @param row 行（見出し → 値）
     * @return カスタムフィールド名 → 値（空欄は含まない）
     */
    public static Map<String, String> cellValues(Map<String, String> row) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (isColumn(entry.getKey()) && entry.getValue() != null && !entry.getValue().isBlank()) {
                values.put(name(entry.getKey()), entry.getValue().trim());
            }
        }
        return values;
    }

    /**
     * Redmine のレコード（チケット・ユーザー・グループ）の custom_fields から定義を集めます。
     *
     * @param records レコード
     * @return 名前 → 定義（ID順）
     */
    public static Map<String, Definition> definitions(Collection<Map<String, Object>> records) {
        Map<Long, Definition> byId = new TreeMap<>();
        for (Map<String, Object> record : records) {
            if (!(record.get("custom_fields") instanceof List<?> fields)) {
                continue;
            }
            for (Object element : fields) {
                if (element instanceof Map<?, ?> field && field.get("id") instanceof Number id
                        && field.get("name") != null) {
                    boolean multiple = Boolean.TRUE.equals(field.get("multiple"))
                            || field.get("value") instanceof Collection<?>;
                    Definition current = byId.get(id.longValue());
                    if (current == null || (multiple && !current.multiple())) {
                        byId.put(id.longValue(), new Definition(id.longValue(), String.valueOf(field.get("name")).trim(),
                                multiple));
                    }
                }
            }
        }
        Map<String, Definition> byName = new LinkedHashMap<>();
        for (Definition definition : byId.values()) {
            byName.putIfAbsent(definition.name(), definition);
        }
        return byName;
    }

    /**
     * 名前で定義を探します（完全一致 → 大文字小文字・全角空白を無視）。
     *
     * @param definitions 定義
     * @param name 名前
     * @return 定義（なければ null）
     */
    public static Definition find(Map<String, Definition> definitions, String name) {
        Definition exact = definitions.get(name);
        if (exact != null) {
            return exact;
        }
        String key = normalize(name);
        for (Definition definition : definitions.values()) {
            if (normalize(definition.name()).equals(key)) {
                return definition;
            }
        }
        return null;
    }

    /**
     * API に送る custom_fields の1要素を作ります（複数選択はカンマ区切りを配列に）。
     *
     * @param definition 定義
     * @param cell セルの値
     * @return {@code {id, value}}
     */
    public static Map<String, Object> payload(Definition definition, String cell) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", definition.id());
        field.put("value", definition.multiple() ? split(cell) : cell);
        return field;
    }

    /**
     * レコードの現在の値をセルの表記（複数はカンマ区切り）で返します。
     *
     * @param record レコード
     * @param id カスタムフィールドID
     * @return 値（なければ空文字）
     */
    public static String currentText(Map<String, Object> record, long id) {
        if (record == null || !(record.get("custom_fields") instanceof List<?> fields)) {
            return "";
        }
        for (Object element : fields) {
            if (element instanceof Map<?, ?> field && field.get("id") instanceof Number fieldId
                    && fieldId.longValue() == id) {
                return text(field.get("value"));
            }
        }
        return "";
    }

    /**
     * セルの値が現在の値と同じか判定します（複数選択は順不同）。
     *
     * @param definition 定義
     * @param cell セルの値
     * @param record レコード
     * @return 同じなら true
     */
    public static boolean same(Definition definition, String cell, Map<String, Object> record) {
        String current = currentText(record, definition.id());
        if (definition.multiple()) {
            return new java.util.HashSet<>(split(cell)).equals(new java.util.HashSet<>(split(current)));
        }
        return cell.trim().equals(current);
    }

    /**
     * Excel に書き出す値（yyyy-MM-dd は日付、配列はカンマ区切り）。
     *
     * @param record レコード
     * @param id カスタムフィールドID
     * @return 値
     */
    public static Object exportValue(Map<String, Object> record, long id) {
        String value = currentText(record, id);
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException ex) {
                return value;
            }
        }
        return value;
    }

    /**
     * 出力する列名（CF:名前）を返します。
     *
     * @param definitions 定義
     * @return 列名
     */
    public static List<String> headers(Map<String, Definition> definitions) {
        List<String> headers = new ArrayList<>();
        definitions.keySet().forEach(name -> headers.add(PREFIX + name));
        return headers;
    }

    static List<String> split(String value) {
        List<String> result = new ArrayList<>();
        for (String token : value.split("[,、\\r\\n]+")) {
            if (!token.isBlank()) {
                result.add(token.trim());
            }
        }
        return result;
    }

    private static String text(Object value) {
        if (value instanceof Collection<?> values) {
            List<String> texts = new ArrayList<>();
            values.forEach(v -> texts.add(v == null ? "" : String.valueOf(v).trim()));
            return String.join(", ", texts);
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String normalize(String value) {
        return value.trim().replace('　', ' ').toLowerCase(java.util.Locale.ROOT);
    }
}
