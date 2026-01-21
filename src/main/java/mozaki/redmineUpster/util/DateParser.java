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
		return null;
	}
}
