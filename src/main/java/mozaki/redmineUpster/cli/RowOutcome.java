package mozaki.redmineUpster.cli;

/**
 * 1行（1チケット）の同期結果。入力ファイルの「同期結果」「エラー理由」列へ書き戻します。
 *
 * @param status 結果
 * @param message エラー・未実行の理由（成功時は空）
 */
public record RowOutcome(Status status, String message) {

    /** 同期結果の種類（label がファイルに書く値） */
    public enum Status {
        /** 新規作成した */
        CREATED("作成"),
        /** 更新した */
        UPDATED("更新"),
        /** Redmineの現在値と同じため更新しなかった */
        UNCHANGED("変更なし"),
        /** エラー */
        ERROR("エラー"),
        /** 実行しなかった（親の失敗・入力エラー・処理の中止など） */
        NOT_RUN("未実行");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        /**
         * ファイルに書く値。
         *
         * @return 値（作成／更新／変更なし／エラー／未実行）
         */
        public String label() {
            return label;
        }
    }

    /**
     * 理由を正規化して生成します（null は空文字）。
     */
    public RowOutcome {
        message = message == null ? "" : message;
    }

    /**
     * 成功の結果を作成します。
     *
     * @param status 結果
     * @return 結果
     */
    public static RowOutcome of(Status status) {
        return new RowOutcome(status, "");
    }
}
