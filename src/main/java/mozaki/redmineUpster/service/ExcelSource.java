package mozaki.redmineUpster.service;

/**
 * Excel の読み込み元（シート・テーブル）の指定。
 * <p>
 * 優先順位は テーブル ＞ シート ＞ 先頭シート です。CSV では使いません。
 * </p>
 *
 * @param sheet シート名、または1始まりのシート番号（null/空なら指定なし）
 * @param table Excel のテーブル（挿入 → テーブル）の名前（null/空なら指定なし）
 */
public record ExcelSource(String sheet, String table) {

	/** 指定なし（先頭シート） */
	public static final ExcelSource DEFAULT = new ExcelSource(null, null);

	/**
	 * 値を正規化して生成します（前後の空白を除き、空文字は null）。
	 */
	public ExcelSource {
		sheet = blankToNull(sheet);
		table = blankToNull(table);
	}

	/**
	 * シート・テーブルのどちらも指定されていない場合 true。
	 *
	 * @return 指定なしなら true
	 */
	public boolean isDefault() {
		return sheet == null && table == null;
	}

	/**
	 * CLI の指定で設定ファイルの指定を上書きします。
	 * <p>
	 * CLI でシートだけを指定した場合は、設定ファイルのテーブル指定は使いません（CLI の指定を優先）。
	 * </p>
	 *
	 * @param config 設定ファイルの指定（null可）
	 * @param cli CLI の指定（null可）
	 * @return 実際に使う指定
	 */
	public static ExcelSource merge(ExcelSource config, ExcelSource cli) {
		ExcelSource base = config != null ? config : DEFAULT;
		if (cli == null || cli.isDefault()) {
			return base;
		}
		if (cli.table() != null) {
			return new ExcelSource(cli.sheet(), cli.table());
		}
		return new ExcelSource(cli.sheet(), null);
	}

	/**
	 * ログ表示用の説明。
	 *
	 * @return 説明
	 */
	public String describe() {
		if (table != null) {
			return "テーブル「" + table + "」";
		}
		if (sheet != null) {
			return "シート「" + sheet + "」";
		}
		return "先頭シート";
	}

	private static String blankToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
