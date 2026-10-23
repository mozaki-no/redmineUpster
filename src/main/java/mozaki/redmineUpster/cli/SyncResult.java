package mozaki.redmineUpster.cli;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 同期結果。
 *
 * @param totalCount 総処理件数
 * @param successCount 成功件数（変更なしスキップを含む）
 * @param errorCount エラー件数
 * @param errors エラーメッセージのリスト
 * @param createdIssueIds 今回新規作成したチケット（行番号 → チケットID）。Excelへの書き戻しに使用
 * @param createdVirtualIssueIds 今回新規作成した仮想親チケットのID（書き戻しはしない。論理削除の対象外にするために使用）
 * @param rowOutcomes ファイルの行ごとの結果（行番号 → 結果。仮想親は含まない）。「同期結果」列への書き戻しに使用
 * @param plannedIssues 同期したチケットの同期後の状態（親・ステータス。dry-run の作成予定は負の仮ID）。
 *        親チケットの自動完了に使用
 */
public record SyncResult(
    int totalCount,
    int successCount,
    int errorCount,
    List<String> errors,
    Map<Integer, Long> createdIssueIds,
    Set<Long> createdVirtualIssueIds,
    Map<Integer, RowOutcome> rowOutcomes,
    List<PlannedIssue> plannedIssues
) {

    /**
     * 同期したチケット1件の同期後の状態。
     *
     * @param issueId チケットID（dry-run で作成予定のチケットは負の仮ID）
     * @param rowNumber ファイル上の行番号（仮想親は負の仮番号）
     * @param virtual 仮想親の場合はtrue
     * @param parentId 同期後の親チケットID（親なしは null）
     * @param statusId 送信したステータスID（送らない場合は null＝変更なし）
     * @param subject 件名
     */
    public record PlannedIssue(long issueId, int rowNumber, boolean virtual, Long parentId, Long statusId,
            String subject) {
    }

    /**
     * 同期後の状態を持たない結果を作成します。
     */
    public SyncResult(int totalCount, int successCount, int errorCount, List<String> errors,
            Map<Integer, Long> createdIssueIds, Set<Long> createdVirtualIssueIds) {
        this(totalCount, successCount, errorCount, errors, createdIssueIds, createdVirtualIssueIds, Map.of(),
                List.of());
    }

    /**
     * 仮想親の作成がない場合の結果を作成します。
     */
    public SyncResult(int totalCount, int successCount, int errorCount, List<String> errors,
            Map<Integer, Long> createdIssueIds) {
        this(totalCount, successCount, errorCount, errors, createdIssueIds, Set.of());
    }
}
