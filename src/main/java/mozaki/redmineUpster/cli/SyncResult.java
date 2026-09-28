package mozaki.redmineUpster.cli;

import java.util.List;
import java.util.Map;

/**
 * 同期結果。
 *
 * @param totalCount 総処理件数
 * @param successCount 成功件数（変更なしスキップを含む）
 * @param errorCount エラー件数
 * @param errors エラーメッセージのリスト
 * @param createdIssueIds 今回新規作成したチケット（行番号 → チケットID）。Excelへの書き戻しに使用
 */
public record SyncResult(
    int totalCount,
    int successCount,
    int errorCount,
    List<String> errors,
    Map<Integer, Long> createdIssueIds
) {}
