package mozaki.redmineUpster.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;

class SpreadsheetParserTests {

	@Test
	void parseCsvReadsHeadersAndRows() throws Exception {
		String csv = String.join("\n",
				"id,チーム,工程,大分類,中分類,小分類,成果物,タスク,社/組織,担当,着手予定,着手実績,完了予定,完了実績",
				"T-001,基盤,設計,UI,画面,ログイン,画面設計書,ログイン画面作成,開発1課,山田,2026-01-10,2026-01-11,2026-01-20,2026-01-19",
				",,,,,,,,,,,,,", // empty row should be ignored
				"T-002,基盤,実装,API,認証,トークン,API仕様書,認証API実装,開発1課,佐藤,2026-01-12,,2026-01-25,"
		);
		MockMultipartFile file = new MockMultipartFile("file", "sample.csv", "text/csv",
				csv.getBytes(StandardCharsets.UTF_8));

		SpreadsheetParser parser = new SpreadsheetParser();
		SpreadsheetParser.ParsedSheet sheet = parser.parse(file);

		assertEquals(14, sheet.headers().size());
		assertEquals(List.of("id", "チーム", "工程", "大分類", "中分類", "小分類", "成果物", "タスク", "社/組織", "担当", "着手予定", "着手実績", "完了予定", "完了実績"),
				sheet.headers());
		assertEquals(2, sheet.rows().size());
		Map<String, String> firstRow = sheet.rows().get(0);
		assertEquals("T-001", firstRow.get("id"));
		assertEquals("ログイン画面作成", firstRow.get("タスク"));
		assertTrue(firstRow.containsKey("完了実績"));
	}

	@Test
	void parseExcelFormatsDateCells() throws Exception {
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Sheet1");
			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("id");
			header.createCell(1).setCellValue("着手予定");

			CreationHelper creationHelper = workbook.getCreationHelper();
			CellStyle dateStyle = workbook.createCellStyle();
			dateStyle.setDataFormat(creationHelper.createDataFormat().getFormat("yyyy-MM-dd"));

			Row row = sheet.createRow(1);
			row.createCell(0).setCellValue("T-001");
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
		}
	}
}
