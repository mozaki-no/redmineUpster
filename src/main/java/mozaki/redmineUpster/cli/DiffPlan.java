package mozaki.redmineUpster.cli;

import java.util.List;
import java.util.Map;

/**
 * 差分計算の結果。
 *
 * @param items 同期する差分アイテム（親→子の順）
 * @param errors 検証エラー（1件でもあればRedmineへの書き込みは行わない）
 * @param rowErrors 行に結び付く検証エラー（行番号 → 理由。入力ファイルの「エラー理由」列への書き戻しに使用。
 *        errors にも同じエラーが含まれる）
 */
public record DiffPlan(List<DiffItem> items, List<String> errors, Map<Integer, List<String>> rowErrors) {

    /**
     * 行に結び付くエラーなしで構築します。
     */
    public DiffPlan(List<DiffItem> items, List<String> errors) {
        this(items, errors, Map.of());
    }

    /**
     * 検証エラーがあるかどうか。
     *
     * @return エラーがある場合はtrue
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
