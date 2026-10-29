package mozaki.redmineUpster.cli;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「ユーザー」シートの参照用の列（--export だけが出力し、取り込みでは使わない）。
 * <p>
 * Redmine の {@code GET /users.json} が返す読み取り専用の項目（作成日・最終ログイン・パスワード変更日・2段階認証）です。
 * 日時は Redmine の応答（UTC）を実行したPCのタイムゾーンに直して {@code yyyy/MM/dd HH:mm} で出力します。
 * </p>
 */
public final class UserInfoColumns {

    private UserInfoColumns() {
    }

    public static final String COL_CREATED_ON = "作成日";
    public static final String COL_LAST_LOGIN_ON = "最終ログイン";
    public static final String COL_PASSWD_CHANGED_ON = "パスワード変更日";
    public static final String COL_TWOFA = "2段階認証";

    /** 列名（出力順） */
    public static final List<String> COLUMN_NAMES = List.of(COL_CREATED_ON, COL_LAST_LOGIN_ON, COL_PASSWD_CHANGED_ON,
            COL_TWOFA);

    /** 見出しのコメント */
    public static final String NOTE = "参照用（Redmine の現在の値）。取り込みでは使いません。";

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    /**
     * ユーザーの参照用の値を返します。
     *
     * @param user Redmine のユーザー
     * @return セルの値（{@link #COLUMN_NAMES} の順）
     */
    public static List<String> exportValues(Map<String, Object> user) {
        List<String> cells = new ArrayList<>();
        cells.add(dateTime(user.get("created_on")));
        cells.add(dateTime(user.get("last_login_on")));
        cells.add(dateTime(user.get("passwd_changed_on")));
        // 管理者の API キーなら twofa_scheme は常に含まれる（null = 未設定）。キー自体がなければ空欄
        if (user.containsKey("twofa_scheme")) {
            Object scheme = user.get("twofa_scheme");
            cells.add(scheme == null || String.valueOf(scheme).isBlank() ? "なし" : String.valueOf(scheme).toUpperCase());
        } else {
            cells.add("");
        }
        return cells;
    }

    static String dateTime(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return "";
        }
        String raw = String.valueOf(value).trim();
        try {
            return OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).format(FORMAT);
        } catch (DateTimeParseException ex) {
            return raw;
        }
    }
}
