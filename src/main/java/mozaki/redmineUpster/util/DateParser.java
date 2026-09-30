package mozaki.redmineUpster.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class DateParser {
	private static final DateTimeFormatter[] FORMATTERS = new DateTimeFormatter[] {
			DateTimeFormatter.ISO_LOCAL_DATE,
			DateTimeFormatter.ofPattern("yyyy/MM/dd"),
			DateTimeFormatter.ofPattern("yyyy/M/d"),
			DateTimeFormatter.ofPattern("yyyy-M-d")
	};

	/** Excel のシリアル値の起点（1900年日付システム。1900-03-01 以降で正しい） */
	private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
	/** シリアル値として扱う範囲（1927-05-18 〜 9999-12-31） */
	private static final double MIN_SERIAL = 10000;
	private static final double MAX_SERIAL = 2958465;

	private DateParser() {
	}

	public static String normalizeDate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		for (DateTimeFormatter formatter : FORMATTERS) {
			try {
				LocalDate date = LocalDate.parse(value.trim(), formatter);
				return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
			} catch (DateTimeParseException ex) {
				// try next
			}
		}
		return fromExcelSerial(value.trim());
	}

	/**
	 * Excel の日付シリアル値（例: 46032、46032.5）を日付に変換します。
	 * 日付の書式が付いていない数式セル（=WBS!D5 など）は計算結果が数値で保存されるため、その救済です。
	 */
	private static String fromExcelSerial(String value) {
		if (!value.matches("[0-9]+(\\.[0-9]+)?")) {
			return null;
		}
		double serial = Double.parseDouble(value);
		if (serial < MIN_SERIAL || serial > MAX_SERIAL) {
			return null;
		}
		return EXCEL_EPOCH.plusDays((long) Math.floor(serial)).format(DateTimeFormatter.ISO_LOCAL_DATE);
	}
}
