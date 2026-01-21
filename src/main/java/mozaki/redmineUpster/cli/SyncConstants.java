package mozaki.redmineUpster.cli;

/**
 * 同期処理で使用する定数クラス。
 * <p>
 * アクション、ステータス、モードなどの共通定数を定義します。
 * </p>
 */
public final class SyncConstants {

    private SyncConstants() {
        // ユーティリティクラスのためインスタンス化を禁止
    }

    // アクション
    /** アクション: 新規作成 */
    public static final String ACTION_CREATE = "CREATE";
    /** アクション: 更新 */
    public static final String ACTION_UPDATE = "UPDATE";

    // ステータス
    /** ステータス: 新規 */
    public static final String STATUS_NEW = "New";
    /** ステータス: 進行中 */
    public static final String STATUS_IN_PROGRESS = "In Progress";
    /** ステータス: 完了 */
    public static final String STATUS_CLOSED = "Closed";

    // ステータスモード
    /** ステータスモード: 日付ベース */
    public static final String STATUS_MODE_BY_DATES = "BY_DATES";
    /** ステータスモード: 固定値 */
    public static final String STATUS_MODE_FIXED = "FIXED";

    // 区切り文字
    /** 階層パス区切り文字 */
    public static final String HIERARCHY_DELIMITER = ">";
}
