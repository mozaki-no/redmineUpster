package mozaki.redmineUpster.cli;

import java.util.List;

/**
 * 同期結果。
 * <p>
 * 同期実行の結果を保持します。
 * 総数、成功数、エラー数、およびエラーメッセージのリストを含みます。
 * </p>
 *
 * @param totalCount 総処理件数
 * @param successCount 成功件数
 * @param errorCount エラー件数
 * @param errors エラーメッセージのリスト
 */
public record SyncResult(
    int totalCount,
    int successCount,
    int errorCount,
    List<String> errors
) {}
