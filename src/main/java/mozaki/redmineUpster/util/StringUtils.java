package mozaki.redmineUpster.util;

/**
 * 文字列ユーティリティクラス。
 * <p>
 * 文字列操作のための共通メソッドを提供します。
 * </p>
 */
public final class StringUtils {

    private StringUtils() {
        // ユーティリティクラスのためインスタンス化不可
    }

    /**
     * 値がnullまたは空の場合はデフォルト値を返します。
     *
     * @param value 値
     * @param fallback デフォルト値
     * @return 値またはデフォルト値
     */
    public static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 文字列が数値かどうかを判定します。
     *
     * @param value 文字列
     * @return 数値の場合はtrue
     */
    public static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
