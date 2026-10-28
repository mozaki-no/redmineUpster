package mozaki.redmineUpster.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 「ユーザー」シートの設定列（ユーザーの属性と個人設定）。
 * <p>
 * Redmine の REST API（{@code POST /users.json}・{@code PUT /users/:id.json}）で管理者が変更できる項目のうち、
 * 基本の列（ログインID・姓・名・メールアドレス・管理者・状態・パスワード・CF:名前）以外を扱います。
 * ユーザーの属性は {@code "user"} に、個人設定（UserPreference）は {@code "pref"} に入れて送ります
 * （UsersController#create / #update は {@code params[:pref]} を {@code @user.pref.safe_attributes} に渡す）。
 * 空欄のセルは「変更しない」です。値を消したい項目は「なし」「既定」などを書きます。
 * </p>
 * <p>
 * Redmine の API はユーザーの個人設定（pref）やメール通知を返さないため、現在の値が分からない項目は、
 * セルに値があれば毎回送ります（応答に値が含まれる Redmine では比較して、同じなら送りません）。
 * 古い Redmine にない個人設定は Redmine 側で無視されます。
 * </p>
 */
public final class UserSettingColumns {

    private UserSettingColumns() {
    }

    /** 送り先 */
    public enum Target {
        /** "user" の属性 */
        USER,
        /** "pref"（個人設定） */
        PREF
    }

    /**
     * 設定列の定義。
     *
     * @param column 列名
     * @param key Redmine のキー
     * @param target 送り先
     * @param parser セルの値 → 送る値（不正なら IllegalArgumentException）
     * @param exporter Redmine の値 → 出力するセルの値
     */
    public record Column(String column, String key, Target target, Function<String, Object> parser,
            Function<Object, String> exporter) {
    }

    /** はい／いいえ（true / false で送る） */
    private static final int BOOL = 0;
    /** はい／いいえ（"1" / "0" で送る。pref の others に入る項目） */
    private static final int FLAG = 1;

    /** メール通知（Redmine の値 → 出力する名前） */
    public static final Map<String, String> MAIL_NOTIFICATION = ordered(
            "all", "すべて",
            "selected", "選択したプロジェクト",
            "only_my_events", "自分が関係",
            "only_assigned", "自分が担当",
            "only_owner", "自分が作成",
            "none", "通知しない");
    private static final Map<String, String> MAIL_NOTIFICATION_ALIASES = Map.of(
            "参加しているプロジェクトのすべての通知", "all",
            "選択", "selected",
            "選択したプロジェクトのすべての通知", "selected",
            "ウォッチ中または自分が関係しているもの", "only_my_events",
            "ウォッチ中または自分が担当しているもの", "only_assigned",
            "ウォッチ中または自分が作成したもの", "only_owner",
            "なし", "none");

    /** コメントの表示順 */
    public static final Map<String, String> COMMENTS_SORTING = ordered("asc", "古い順", "desc", "新しい順");
    private static final Map<String, String> COMMENTS_SORTING_ALIASES = Map.of("昇順", "asc", "降順", "desc");

    /** テキストエリアのフォント（"" は既定） */
    public static final Map<String, String> TEXTAREA_FONT = ordered("", "既定", "monospace", "等幅",
            "proportional", "プロポーショナル");
    private static final Map<String, String> TEXTAREA_FONT_ALIASES = Map.of("default", "", "なし", "");

    /** 履歴の既定のタブ */
    public static final Map<String, String> HISTORY_DEFAULT_TAB = ordered(
            "notes", "注記",
            "history", "履歴",
            "properties", "プロパティの変更",
            "time_entries", "作業時間",
            "changesets", "関係しているリビジョン",
            "last_tab_visited", "最後に表示したタブ");
    private static final Map<String, String> HISTORY_DEFAULT_TAB_ALIASES = Map.of(
            "コメント", "notes", "すべて", "history", "プロパティ", "properties", "リビジョン", "changesets",
            "最後のタブ", "last_tab_visited");

    /** 自動ウォッチ（Redmine の値 → 出力する名前） */
    public static final Map<String, String> AUTO_WATCH_ON = ordered(
            "issue_created", "作成したチケット",
            "issue_contributed_to", "更新したチケット");
    private static final Map<String, String> AUTO_WATCH_ON_ALIASES = Map.of("作成", "issue_created",
            "更新", "issue_contributed_to");

    /**
     * Redmine で選べるタイムゾーンの名前（Rails の ActiveSupport::TimeZone の名前。個人設定のプルダウンと同じ）。
     */
    public static final List<String> TIME_ZONES = List.of("International Date Line West", "Midway Island",
            "American Samoa", "Hawaii", "Alaska", "Pacific Time (US & Canada)", "Tijuana",
            "Mountain Time (US & Canada)", "Arizona", "Chihuahua", "Mazatlan", "Central Time (US & Canada)",
            "Saskatchewan", "Guadalajara", "Mexico City", "Monterrey", "Central America",
            "Eastern Time (US & Canada)", "Indiana (East)", "Bogota", "Lima", "Quito", "Atlantic Time (Canada)",
            "Caracas", "La Paz", "Santiago", "Asuncion", "Newfoundland", "Brasilia", "Buenos Aires", "Montevideo",
            "Georgetown", "Puerto Rico", "Greenland", "Mid-Atlantic", "Azores", "Cape Verde Is.", "Dublin",
            "Edinburgh", "Lisbon", "London", "Casablanca", "Monrovia", "UTC", "Belgrade", "Bratislava", "Budapest",
            "Ljubljana", "Prague", "Sarajevo", "Skopje", "Warsaw", "Zagreb", "Brussels", "Copenhagen", "Madrid",
            "Paris", "Amsterdam", "Berlin", "Bern", "Zurich", "Rome", "Stockholm", "Vienna", "West Central Africa",
            "Bucharest", "Cairo", "Helsinki", "Kyiv", "Kyev", "Riga", "Sofia", "Tallinn", "Vilnius", "Athens",
            "Istanbul", "Minsk", "Jerusalem", "Harare", "Pretoria", "Kaliningrad", "Moscow", "St. Petersburg",
            "Volgograd", "Samara", "Kuwait", "Riyadh", "Nairobi", "Baghdad", "Tehran", "Abu Dhabi", "Muscat", "Baku",
            "Tbilisi", "Yerevan", "Kabul", "Ekaterinburg", "Islamabad", "Karachi", "Tashkent", "Chennai", "Kolkata",
            "Mumbai", "New Delhi", "Kathmandu", "Astana", "Dhaka", "Sri Jayawardenepura", "Almaty", "Novosibirsk",
            "Rangoon", "Bangkok", "Hanoi", "Jakarta", "Krasnoyarsk", "Beijing", "Chongqing", "Hong Kong", "Urumqi",
            "Kuala Lumpur", "Singapore", "Taipei", "Perth", "Irkutsk", "Ulaanbaatar", "Ulaan Bataar", "Seoul",
            "Osaka", "Sapporo", "Tokyo", "Yakutsk", "Darwin", "Adelaide", "Canberra", "Melbourne", "Sydney",
            "Brisbane", "Hobart", "Vladivostok", "Guam", "Port Moresby", "Magadan", "Srednekolymsk", "Solomon Is.",
            "New Caledonia", "Fiji", "Kamchatka", "Marshall Is.", "Auckland", "Wellington", "Nuku'alofa",
            "Tokelau Is.", "Chatham Is.", "Samoa");

    public static final String COL_LANGUAGE = "言語";
    public static final String COL_MAIL_NOTIFICATION = "メール通知";
    public static final String COL_NOTIFIED_PROJECTS = "通知するプロジェクト";
    public static final String COL_AUTH_SOURCE = "認証方式";
    public static final String COL_MUST_CHANGE_PASSWD = "パスワード変更を要求";
    public static final String COL_HIDE_MAIL = "メール非公開";
    public static final String COL_TIME_ZONE = "タイムゾーン";
    public static final String COL_COMMENTS_SORTING = "コメントの表示順";
    public static final String COL_WARN_ON_LEAVING_UNSAVED = "未保存で移動時に警告";
    public static final String COL_NO_SELF_NOTIFIED = "自分の変更は通知しない";
    public static final String COL_NOTIFY_HIGH_PRIORITY = "高優先度チケットを通知";
    public static final String COL_TEXTAREA_FONT = "テキストエリアのフォント";
    public static final String COL_RECENTLY_USED_PROJECTS = "最近使ったプロジェクトの表示数";
    public static final String COL_HISTORY_DEFAULT_TAB = "履歴の既定のタブ";
    public static final String COL_TOOLBAR_LANGUAGE_OPTIONS = "コードハイライトの言語";
    public static final String COL_DEFAULT_ISSUE_QUERY = "既定のチケットクエリ";
    public static final String COL_DEFAULT_PROJECT_QUERY = "既定のプロジェクトクエリ";
    public static final String COL_AUTO_WATCH_ON = "自動ウォッチ";
    /** パスワードの再発行（はい＝自動生成して本人にメールで通知。更新のときだけ使う） */
    public static final String COL_RESET_PASSWORD = "パスワード再発行";

    /** 通知するプロジェクトのキー（プロジェクトの識別子・名前 → ID の解決が必要） */
    public static final String KEY_NOTIFIED_PROJECTS = "notified_project_ids";

    /** 設定列（出力順） */
    public static final List<Column> COLUMNS = List.of(
            column(COL_MAIL_NOTIFICATION, "mail_notification", Target.USER,
                    raw -> choice(raw, COL_MAIL_NOTIFICATION, MAIL_NOTIFICATION, MAIL_NOTIFICATION_ALIASES),
                    value -> label(MAIL_NOTIFICATION, value)),
            column(COL_NOTIFIED_PROJECTS, KEY_NOTIFIED_PROJECTS, Target.USER, UserSettingColumns::list,
                    UserSettingColumns::joined),
            column(COL_LANGUAGE, "language", Target.USER, UserSettingColumns::language, UserSettingColumns::text),
            column(COL_AUTH_SOURCE, "auth_source_id", Target.USER,
                    raw -> idOrNone(raw, COL_AUTH_SOURCE, "内部", "redmine"), UserSettingColumns::authSource),
            column(COL_MUST_CHANGE_PASSWD, "must_change_passwd", Target.USER, raw -> yesNo(raw,
                    COL_MUST_CHANGE_PASSWD, BOOL), UserSettingColumns::yesNoLabel),
            column(COL_HIDE_MAIL, "hide_mail", Target.PREF, raw -> yesNo(raw, COL_HIDE_MAIL, BOOL),
                    UserSettingColumns::yesNoLabel),
            column(COL_TIME_ZONE, "time_zone", Target.PREF, UserSettingColumns::timeZone, UserSettingColumns::text),
            column(COL_COMMENTS_SORTING, "comments_sorting", Target.PREF,
                    raw -> choice(raw, COL_COMMENTS_SORTING, COMMENTS_SORTING, COMMENTS_SORTING_ALIASES),
                    value -> label(COMMENTS_SORTING, value)),
            column(COL_WARN_ON_LEAVING_UNSAVED, "warn_on_leaving_unsaved", Target.PREF,
                    raw -> yesNo(raw, COL_WARN_ON_LEAVING_UNSAVED, FLAG), UserSettingColumns::yesNoLabel),
            column(COL_NO_SELF_NOTIFIED, "no_self_notified", Target.PREF,
                    raw -> yesNo(raw, COL_NO_SELF_NOTIFIED, FLAG), UserSettingColumns::yesNoLabel),
            column(COL_NOTIFY_HIGH_PRIORITY, "notify_about_high_priority_issues", Target.PREF,
                    raw -> yesNo(raw, COL_NOTIFY_HIGH_PRIORITY, FLAG), UserSettingColumns::yesNoLabel),
            column(COL_AUTO_WATCH_ON, "auto_watch_on", Target.PREF, UserSettingColumns::autoWatchOn,
                    UserSettingColumns::autoWatchLabel),
            column(COL_TEXTAREA_FONT, "textarea_font", Target.PREF,
                    raw -> choice(raw, COL_TEXTAREA_FONT, TEXTAREA_FONT, TEXTAREA_FONT_ALIASES),
                    value -> value == null ? "" : label(TEXTAREA_FONT, value)),
            column(COL_RECENTLY_USED_PROJECTS, "recently_used_projects", Target.PREF,
                    raw -> number(raw, COL_RECENTLY_USED_PROJECTS), UserSettingColumns::text),
            column(COL_HISTORY_DEFAULT_TAB, "history_default_tab", Target.PREF,
                    raw -> choice(raw, COL_HISTORY_DEFAULT_TAB, HISTORY_DEFAULT_TAB, HISTORY_DEFAULT_TAB_ALIASES),
                    value -> label(HISTORY_DEFAULT_TAB, value)),
            column(COL_TOOLBAR_LANGUAGE_OPTIONS, "toolbar_language_options", Target.PREF,
                    raw -> String.join(",", splitList(raw)), UserSettingColumns::text),
            column(COL_DEFAULT_ISSUE_QUERY, "default_issue_query", Target.PREF,
                    raw -> idOrNone(raw, COL_DEFAULT_ISSUE_QUERY), UserSettingColumns::text),
            column(COL_DEFAULT_PROJECT_QUERY, "default_project_query", Target.PREF,
                    raw -> idOrNone(raw, COL_DEFAULT_PROJECT_QUERY), UserSettingColumns::text));

    /** 列名（出力順。最後に「パスワード再発行」） */
    public static final List<String> COLUMN_NAMES;
    static {
        List<String> names = new ArrayList<>();
        COLUMNS.forEach(c -> names.add(c.column()));
        names.add(COL_RESET_PASSWORD);
        COLUMN_NAMES = Collections.unmodifiableList(names);
    }

    /**
     * 行の設定。
     *
     * @param user "user" に入れる属性（通知するプロジェクトはセルの値のリスト。解決は {@link #resolveProjects}）
     * @param pref "pref" に入れる個人設定
     * @param resetPassword パスワードを再発行する（更新のときだけ）
     */
    public record Values(Map<String, Object> user, Map<String, Object> pref, boolean resetPassword) {
        public static final Values NONE = new Values(Map.of(), Map.of(), false);

        public boolean isEmpty() {
            return user.isEmpty() && pref.isEmpty() && !resetPassword;
        }
    }

    /**
     * 行の設定列を解析します。
     *
     * @param row 行（列名 → 値）
     * @param projects 通知するプロジェクトの解決（識別子・名前・ID → ID。見つからなければ null）
     * @param errors 不正な値のエラー（列ごとに追加）
     * @return 設定
     */
    public static Values parse(Map<String, String> row, Function<String, Long> projects, List<String> errors) {
        Map<String, Object> user = new LinkedHashMap<>();
        Map<String, Object> pref = new LinkedHashMap<>();
        for (Column column : COLUMNS) {
            String raw = row.get(column.column());
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                Object value = column.parser().apply(raw.trim());
                if (KEY_NOTIFIED_PROJECTS.equals(column.key())) {
                    List<Long> ids = resolveProjects(castList(value), projects);
                    // 0件は画面と同じく空文字1つの配列で送る（空の配列は Rails で無視されることがある）
                    value = ids.isEmpty() ? List.of("") : ids;
                }
                (column.target() == Target.USER ? user : pref).put(column.key(), value);
            } catch (IllegalArgumentException ex) {
                errors.add(ex.getMessage());
            }
        }
        boolean reset = false;
        String rawReset = row.get(COL_RESET_PASSWORD);
        if (rawReset != null && !rawReset.isBlank()) {
            try {
                reset = Boolean.TRUE.equals(yesNo(rawReset.trim(), COL_RESET_PASSWORD, BOOL));
            } catch (IllegalArgumentException ex) {
                errors.add(ex.getMessage());
            }
        }
        return new Values(user, pref, reset);
    }

    /**
     * 現在の値と違う項目だけを返します。現在の値が分からない項目（Redmine が返さない）は違うものとして扱います。
     *
     * @param values 送りたい値
     * @param current 現在の値（user の属性、または pref）。null なら全部分からない
     * @return 送る項目
     */
    public static Map<String, Object> changed(Map<String, Object> values, Map<?, ?> current) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (current == null || !current.containsKey(key) || !normalize(value).equals(normalize(current.get(key)))) {
                result.put(key, value);
            }
        });
        return result;
    }

    /**
     * ユーザーの個人設定（"pref" または "preferences"）。Redmine の API が返さない場合は null。
     *
     * @param user ユーザー
     * @return 個人設定
     */
    public static Map<?, ?> currentPref(Map<String, Object> user) {
        if (user.get("pref") instanceof Map<?, ?> pref) {
            return pref;
        }
        return user.get("preferences") instanceof Map<?, ?> pref ? pref : null;
    }

    /**
     * 出力するセルの値（Redmine が返さない項目は空欄）。
     *
     * @param user ユーザー
     * @return 列名 → セルの値（{@link #COLUMN_NAMES} の順）
     */
    public static List<String> exportValues(Map<String, Object> user) {
        Map<?, ?> pref = currentPref(user);
        List<String> cells = new ArrayList<>();
        for (Column column : COLUMNS) {
            Map<?, ?> source = column.target() == Target.USER ? user : pref;
            Object value = source != null ? source.get(column.key()) : null;
            if (KEY_NOTIFIED_PROJECTS.equals(column.key()) && value == null && user.get("notified_projects") != null) {
                value = user.get("notified_projects");
            }
            cells.add(value == null ? "" : column.exporter().apply(value));
        }
        cells.add("");
        return cells;
    }

    // ---------------------------------------------------------------- 値の解釈

    static Object yesNo(String raw, String column, int kind) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        Boolean result = switch (value) {
            case "はい", "yes", "true", "1", "○", "〇", "on" -> Boolean.TRUE;
            case "いいえ", "no", "false", "0", "×", "-", "off" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(column + "の値が不正です（はい／いいえ）: " + raw);
        };
        return kind == FLAG ? (result ? "1" : "0") : result;
    }

    static String timeZone(String raw) {
        for (String zone : TIME_ZONES) {
            if (zone.equalsIgnoreCase(raw)) {
                return zone;
            }
        }
        if (List.of("asia/tokyo", "jst", "日本", "東京").contains(raw.toLowerCase(Locale.ROOT))) {
            return "Tokyo";
        }
        if (List.of("なし", "既定", "default").contains(raw.toLowerCase(Locale.ROOT))) {
            return "";
        }
        throw new IllegalArgumentException(COL_TIME_ZONE + "の値が不正です（Redmine の名前。例: Tokyo、UTC。"
                + "未設定に戻すなら「なし」）: " + raw);
    }

    static String language(String raw) {
        String value = raw.trim();
        switch (value.toLowerCase(Locale.ROOT)) {
            case "日本語":
                return "ja";
            case "英語", "english":
                return "en";
            case "なし", "既定", "default":
                return "";
            default:
                break;
        }
        if (!value.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")) {
            throw new IllegalArgumentException(COL_LANGUAGE + "の値が不正です（例: ja、en、日本語。既定に戻すなら「既定」）: " + raw);
        }
        return value;
    }

    static String choice(String raw, String column, Map<String, String> labels, Map<String, String> aliases) {
        String value = raw.trim();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(value) || entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        String alias = aliases.get(value.toLowerCase(Locale.ROOT));
        if (alias != null) {
            return alias;
        }
        List<String> allowed = new ArrayList<>();
        labels.forEach((code, label) -> allowed.add(label + (code.isEmpty() ? "" : "(" + code + ")")));
        throw new IllegalArgumentException(column + "の値が不正です（" + String.join("／", allowed) + "）: " + raw);
    }

    static Object number(String raw, String column) {
        String value = raw.trim();
        if (value.endsWith(".0")) {
            value = value.substring(0, value.length() - 2);
        }
        if (!value.matches("\\d{1,4}")) {
            throw new IllegalArgumentException(column + "の値が不正です（0以上の整数）: " + raw);
        }
        return value;
    }

    static Object idOrNone(String raw, String column, String... noneWords) {
        String value = raw.trim();
        List<String> none = new ArrayList<>(List.of("なし", "既定", "none"));
        none.addAll(List.of(noneWords));
        if (none.contains(value.toLowerCase(Locale.ROOT))) {
            return "";
        }
        if (value.endsWith(".0")) {
            value = value.substring(0, value.length() - 2);
        }
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (!value.matches("\\d{1,10}")) {
            throw new IllegalArgumentException(column + "の値が不正です（ID の数値、または「なし」）: " + raw);
        }
        return value;
    }

    static Object list(String raw) {
        List<String> values = splitList(raw);
        if (values.size() == 1 && List.of("なし", "none").contains(values.get(0).toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        return values;
    }

    static Object autoWatchOn(String raw) {
        List<String> values = new ArrayList<>();
        for (String token : splitList(raw)) {
            if (List.of("なし", "none").contains(token.toLowerCase(Locale.ROOT))) {
                continue;
            }
            values.add(choice(token, COL_AUTO_WATCH_ON, AUTO_WATCH_ON, AUTO_WATCH_ON_ALIASES));
        }
        // 何もウォッチしない場合も、画面と同じく空文字1つの配列で送る（空の配列は Rails で無視されることがある）
        return values.isEmpty() ? List.of("") : values;
    }

    /**
     * 通知するプロジェクト（識別子・名前・ID）を ID にします。
     */
    static List<Long> resolveProjects(List<String> tokens, Function<String, Long> projects) {
        List<Long> ids = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String token : tokens) {
            Long id = projects != null ? projects.apply(token) : null;
            if (id == null && token.matches("#?\\d{1,10}")) {
                id = Long.parseLong(token.replace("#", ""));
            }
            if (id == null) {
                unknown.add(token);
            } else if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(COL_NOTIFIED_PROJECTS + "のプロジェクトが Redmine にありません: " + unknown);
        }
        return ids;
    }

    static List<String> splitList(String raw) {
        List<String> values = new ArrayList<>();
        for (String token : raw.split("[,、，\\r\\n]+")) {
            if (!token.isBlank()) {
                values.add(token.trim());
            }
        }
        return values;
    }

    // ---------------------------------------------------------------- 出力・比較

    private static String yesNoLabel(Object value) {
        String raw = text(value).toLowerCase(Locale.ROOT);
        return "true".equals(raw) || "1".equals(raw) ? "はい" : "いいえ";
    }

    private static String label(Map<String, String> labels, Object value) {
        String raw = text(value);
        return labels.getOrDefault(raw, raw);
    }

    private static String autoWatchLabel(Object value) {
        List<String> labels = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (!text(item).isEmpty()) {
                    labels.add(label(AUTO_WATCH_ON, item));
                }
            }
            return labels.isEmpty() ? "なし" : String.join(",", labels);
        }
        return text(value);
    }

    private static String authSource(Object value) {
        String raw = text(value);
        return raw.isEmpty() ? "内部" : raw;
    }

    private static String joined(Object value) {
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                items.add(item instanceof Map<?, ?> map && map.get("id") != null ? text(map.get("id")) : text(item));
            }
            return String.join(",", items);
        }
        return text(value);
    }

    /** 比較用に値をそろえる（true/"1"、数値/文字列、リストの順序を区別しない） */
    static String normalize(Object value) {
        if (value instanceof Boolean b) {
            return b ? "1" : "0";
        }
        if (value instanceof List<?> list) {
            TreeSet<String> items = new TreeSet<>();
            for (Object item : list) {
                String text = item instanceof Map<?, ?> map && map.get("id") != null ? text(map.get("id"))
                        : normalize(item);
                if (!text.isEmpty()) {
                    items.add(text);
                }
            }
            return items.toString();
        }
        String raw = text(value);
        if ("true".equalsIgnoreCase(raw)) {
            return "1";
        }
        if ("false".equalsIgnoreCase(raw)) {
            return "0";
        }
        return raw;
    }

    private static Column column(String name, String key, Target target, Function<String, Object> parser,
            Function<Object, String> exporter) {
        return new Column(name, key, target, parser, exporter);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return (List<String>) value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
