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
    /** アクション: 論理削除（ステータス変更） */
    public static final String ACTION_LOGICAL_DELETE = "LOGICAL_DELETE";

    /** issue_link.payload_hash に記録する論理削除済みマーカーの接頭辞 */
    public static final String LOGICAL_DELETE_HASH_PREFIX = "logical-delete:";

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
