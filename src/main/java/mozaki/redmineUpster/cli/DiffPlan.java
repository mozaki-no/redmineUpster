package mozaki.redmineUpster.cli;

import java.util.List;

/**
 * 差分計算の結果。
 *
 * @param items 同期する差分アイテム（親→子の順）
 * @param errors 検証エラー（1件でもあればRedmineへの書き込みは行わない）
 */
public record DiffPlan(List<DiffItem> items, List<String> errors) {

    /**
     * 検証エラーがあるかどうか。
     *
     * @return エラーがある場合はtrue
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
