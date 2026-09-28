package mozaki.redmineUpster.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DateParserTests {

	@Test
	@DisplayName("normalizeDateはゼロ埋め無しの日付をISO形式に整形する")
	void normalizeDate_withoutZeroPadding() {
		assertThat(DateParser.normalizeDate("2026-1-3")).isEqualTo("2026-01-03");
		assertThat(DateParser.normalizeDate("2026/1/3")).isEqualTo("2026-01-03");
	}

	@Test
	@DisplayName("normalizeDateはISO形式をそのまま返す")
	void normalizeDate_isoFormat() {
		assertThat(DateParser.normalizeDate("2026-01-13")).isEqualTo("2026-01-13");
	}

	@Test
	@DisplayName("normalizeDateは不正な日付をnullにする")
	void normalizeDate_invalid() {
		assertThat(DateParser.normalizeDate("2026/13/40")).isNull();
	}

	@Test
	@DisplayName("normalizeDateはExcelの日付シリアル値（書式なしの数式セル）を日付にする")
	void normalizeDate_excelSerial() {
		assertThat(DateParser.normalizeDate("46032")).isEqualTo("2026-01-10");
		assertThat(DateParser.normalizeDate("46032.75")).isEqualTo("2026-01-10");
		assertThat(DateParser.normalizeDate("12")).isNull();
		assertThat(DateParser.normalizeDate("-46032")).isNull();
	}
}
