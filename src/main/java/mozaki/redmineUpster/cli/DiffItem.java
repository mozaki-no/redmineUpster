package mozaki.redmineUpster.cli;

import java.util.Map;

/**
 * インメモリ用の差分アイテム。
 * <p>
 * CLI同期モードで使用される差分情報を保持します。
 * DBに保存せずインメモリで差分計算を行う際に使用されます。
 * </p>
 *
 * @param externalKey 外部キー（CSV/Excelのid列）
 * @param subject チケットの件名
 * @param parentKey 親チケットの外部キー
 * @param levelPath 階層パス（例: "大分類 > 中分類 > 小分類"）
 * @param action アクション（CREATE / UPDATE / DELETE）
 * @param status ステータス
 * @param payload Redmine APIに送信するペイロード
 */
public record DiffItem(
    String externalKey,
    String subject,
    String parentKey,
    String levelPath,
    String action,
    String status,
    Map<String, Object> payload
) {}
