package mozaki.redmineUpster.cli;

import java.util.Map;

/**
 * インメモリ用の差分アイテム（Excel/CSVの1行 = Redmineのチケット1件）。
 * <p>
 * 仮想親チケット（{@code virtual = true}）はファイルに行がない祖先で、行番号は負の仮番号です
 * （親子の対応付けにだけ使い、チケットIDの書き戻しは行いません）。
 * </p>
 *
 * @param rowNumber ファイル上の行番号（Excel: 表示行番号、CSV: レコード番号。ヘッダ=1。仮想親は負の仮番号）
 * @param issueId チケットID列の値（空欄＝新規作成の場合はnull。仮想親はRedmineで見つかった既存チケットのID）
 * @param subject チケットの件名
 * @param levelPath 階層パス（例: "大分類 > 中分類 > 小分類"）
 * @param depth 階層の深さ（値が入っている一番深い階層列の位置、0始まり）
 * @param parentRowNumber 親行の行番号（最上位の場合はnull）
 * @param action アクション（CREATE / UPDATE）
 * @param status ステータス
 * @param trackerId トラッカーID（未指定の場合はnull＝更新時は変更しない）
 * @param payload Redmine APIに送信する値の元データ
 * @param virtual 仮想親チケット（ファイルに行がない祖先）の場合はtrue
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
    Map<String, Object> payload,
    boolean virtual
) {
    /**
     * ファイルの行（仮想親ではない）の差分アイテムを作成します。
     */
    public DiffItem(int rowNumber, Long issueId, String subject, String levelPath, int depth, Integer parentRowNumber,
            String action, String status, Long trackerId, Map<String, Object> payload) {
        this(rowNumber, issueId, subject, levelPath, depth, parentRowNumber, action, status, trackerId, payload,
                false);
    }

    /**
     * 既存チケットに対応付けたコピー（UPDATE）を返します（仮想親の再識別に使用）。
     *
     * @param existingIssueId 既存チケットのID
     * @return コピー
     */
    public DiffItem withExistingIssue(Long existingIssueId) {
        return new DiffItem(rowNumber, existingIssueId, subject, levelPath, depth, parentRowNumber,
                SyncConstants.ACTION_UPDATE, status, trackerId, payload, virtual);
    }

    /**
     * ログ表示用のラベル（例: "行12 #345 [大分類 > 中分類]"、仮想親は "(仮想親) #345 [大分類 > 中分類]"）。
     *
     * @return ラベル
     */
    public String label() {
        return (virtual ? "(仮想親)" : "行" + rowNumber) + (issueId != null ? " #" + issueId : "")
                + " [" + levelPath + "]";
    }
}
