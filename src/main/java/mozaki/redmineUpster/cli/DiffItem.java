package mozaki.redmineUpster.cli;

import java.util.Map;

/**
 * インメモリ用の差分アイテム（Excel/CSVの1行 = Redmineのチケット1件）。
 *
 * @param rowNumber ファイル上の行番号（Excel: 表示行番号、CSV: レコード番号。ヘッダ=1）
 * @param issueId チケットID列の値（空欄＝新規作成の場合はnull）
 * @param subject チケットの件名
 * @param levelPath 階層パス（例: "大分類 > 中分類 > 小分類"）
 * @param depth 階層の深さ（値が入っている一番深い階層列の位置、0始まり）
 * @param parentRowNumber 親行の行番号（最上位の場合はnull）
 * @param action アクション（CREATE / UPDATE）
 * @param status ステータス
 * @param trackerId トラッカーID（未指定の場合はnull＝更新時は変更しない）
 * @param payload Redmine APIに送信する値の元データ
 */
public record DiffItem(
    int rowNumber,
    Long issueId,
    String subject,
    String levelPath,
    int depth,
    Integer parentRowNumber,
    String action,
    String status,
    Long trackerId,
    Map<String, Object> payload
) {
    /**
     * ログ表示用のラベル（例: "行12 #345 [大分類 > 中分類]"）。
     *
     * @return ラベル
     */
    public String label() {
        return "行" + rowNumber + (issueId != null ? " #" + issueId : "") + " [" + levelPath + "]";
    }
}
