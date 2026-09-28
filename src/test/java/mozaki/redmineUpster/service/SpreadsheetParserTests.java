package mozaki.redmineUpster.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class SpreadsheetParserTests {

	@Test
	void parseCsvReadsHeadersAndRows() throws Exception {
		String csv = String.join("\n",
				"チケットID,チーム,工程,大分類,中分類,小分類,成果物,タスク,社/組織,担当,着手予定,着手実績,完了予定,完了実績",
				"101,基盤,設計,UI,画面,ログイン,画面設計書,ログイン画面作成,開発1課,山田,2026-01-10,2026-01-11,2026-01-20,2026-01-19",
				",,,,,,,,,,,,,", // empty row should be ignored
				"102,基盤,実装,API,認証,トークン,API仕様書,認証API実装,開発1課,佐藤,2026-01-12,,2026-01-25,"
		);
		MockMultipartFile file = new MockMultipartFile("file", "sample.csv", "text/csv",
				csv.getBytes(StandardCharsets.UTF_8));

		SpreadsheetParser parser = new SpreadsheetParser();
		SpreadsheetParser.ParsedSheet sheet = parser.parse(file);

		assertEquals(14, sheet.headers().size());
		assertEquals(List.of("チケットID", "チーム", "工程", "大分類", "中分類", "小分類", "成果物", "タスク", "社/組織", "担当", "着手予定", "着手実績", "完了予定", "完了実績"),
				sheet.headers());
		assertEquals(2, sheet.rows().size());
		Map<String, String> firstRow = sheet.rows().get(0);
		assertEquals("101", firstRow.get("チケットID"));
		assertEquals(List.of(2, 4), sheet.rowNumbers());
		assertEquals("ログイン画面作成", firstRow.get("タスク"));
		assertTrue(firstRow.containsKey("完了実績"));
	}

	@Test
	void parseExcelFormatsDateCells() throws Exception {
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Sheet1");
			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("チケットID");
			header.createCell(1).setCellValue("着手予定");

			CreationHelper creationHelper = workbook.getCreationHelper();
			CellStyle dateStyle = workbook.createCellStyle();
			dateStyle.setDataFormat(creationHelper.createDataFormat().getFormat("yyyy-MM-dd"));

			Row row = sheet.createRow(1);
			row.createCell(0).setCellValue(101);
			row.createCell(1).setCellStyle(dateStyle);
			LocalDate date = LocalDate.of(2026, 1, 3);
			row.getCell(1).setCellValue(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			workbook.write(out);

			MockMultipartFile file = new MockMultipartFile(
					"file",
					"sample.xlsx",
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
					out.toByteArray());

			SpreadsheetParser parser = new SpreadsheetParser();
			SpreadsheetParser.ParsedSheet sheetData = parser.parse(file);

			assertEquals(2, sheetData.headers().size());
			assertEquals("2026-01-03", sheetData.rows().get(0).get("着手予定"));
			assertEquals("101", sheetData.rows().get(0).get("チケットID"));
		}
	}

	@Test
	void parseFromPathFillsOnlyBlankHierarchyCellsLeftOfDeepestValue(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("wbs.csv");
		String csv = String.join("\n",
				"\uFEFFチケットID,大分類,中分類,小分類,担当",
				",A,,,",
				",,B,,",
				",,,C,",
				",,D,,",
				",E,,,",
				",,F,,");
		Files.write(file, csv.getBytes(StandardCharsets.UTF_8));

		SpreadsheetParser.ParsedSheet sheet = new SpreadsheetParser()
				.parseFromPath(file.toString(), List.of("大分類", "中分類", "小分類"));

		assertEquals("チケットID", sheet.headers().get(0));
		assertEquals(List.of(2, 3, 4, 5, 6, 7), sheet.rowNumbers());
		List<String> paths = sheet.rows().stream()
				.map(r -> r.get("大分類") + "/" + r.get("中分類") + "/" + r.get("小分類"))
				.toList();
		assertEquals(List.of("A//", "A/B/", "A/B/C", "A/D/", "E//", "E/F/"), paths);
	}
}
