package mozaki.redmineUpster.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mozaki.redmineUpster.service.TicketIdWriter.RowResult;
import mozaki.redmineUpster.service.TicketIdWriter.SyncResults;

/**
 * 同期結果（最終同期日時・同期結果・エラー理由）の書き戻しのテスト。
 */
class TicketIdWriterResultTests {

	private static final String AT = "2026/10/23 09:15:00";

	@TempDir
	Path tempDir;

	private final TicketIdWriter writer = new TicketIdWriter();
	private final SpreadsheetParser parser = new SpreadsheetParser();

	private static SyncResults results(Map<Integer, RowResult> rows) {
		return new SyncResults("最終同期日時", "同期結果", "エラー理由", AT, rows);
	}

	private static Map<Integer, RowResult> rows(Object... pairs) {
		Map<Integer, RowResult> rows = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 3) {
			rows.put((Integer) pairs[i], new RowResult((String) pairs[i + 1], (String) pairs[i + 2]));
		}
		return rows;
	}

	private Path save(Workbook workbook, String name) throws Exception {
		Path file = tempDir.resolve(name);
		try (OutputStream out = Files.newOutputStream(file)) {
			workbook.write(out);
		}
		workbook.close();
		return file;
	}

	private static Workbook open(Path file) throws Exception {
		try (InputStream in = Files.newInputStream(file)) {
			return WorkbookFactory.create(in);
		}
	}

	private static String text(Sheet sheet, int row, int column) {
		Row r = sheet.getRow(row);
		return r == null || r.getCell(column) == null ? null : SpreadsheetParser.getCellString(r.getCell(column));
	}

	private static XSSFWorkbook simpleSheet(String... extraHeaders) {
		XSSFWorkbook wb = new XSSFWorkbook();
		XSSFSheet sheet = wb.createSheet("WBS");
		Row header = sheet.createRow(0);
		String[] headers = { "チケットID", "トラッカー", "大分類" };
		for (int i = 0; i < headers.length; i++) {
			header.createCell(i).setCellValue(headers[i]);
		}
		for (int i = 0; i < extraHeaders.length; i++) {
			header.createCell(headers.length + i).setCellValue(extraHeaders[i]);
		}
		Row r1 = sheet.createRow(1);
		r1.createCell(0).setCellValue(10);
		r1.createCell(1).setCellValue("サマリ");
		r1.createCell(2).setCellValue("A");
		Row r2 = sheet.createRow(2);
		r2.createCell(1).setCellValue("サマリ");
		r2.createCell(2).setCellValue("B");
		return wb;
	}

	@Test
	@DisplayName("xlsx: IDと同期結果を1回で書き、なければ列を末尾に追加する。日時は文字列")
	void xlsx_idsAndResults() throws Exception {
		Path file = save(simpleSheet(), "wbs.xlsx");

		TicketIdWriter.WriteBackResult result = writer.writeBack(file.toString(), "チケットID", Map.of(3, 123L),
				ExcelSource.DEFAULT, true, results(rows(2, "変更なし", "", 3, "作成", "")));

		assertThat(result.backup()).exists();
		assertThat(result.warnings()).isEmpty();
		try (Workbook wb = open(file)) {
			Sheet sheet = wb.getSheetAt(0);
			assertThat(text(sheet, 0, 3)).isEqualTo("最終同期日時");
			assertThat(text(sheet, 0, 4)).isEqualTo("同期結果");
			assertThat(text(sheet, 0, 5)).isEqualTo("エラー理由");
			assertThat(sheet.getRow(1).getCell(3).getCellType()).isEqualTo(CellType.STRING);
			assertThat(text(sheet, 1, 3)).isEqualTo(AT);
			assertThat(text(sheet, 1, 4)).isEqualTo("変更なし");
			assertThat(sheet.getRow(2).getCell(0).getNumericCellValue()).isEqualTo(123);
			assertThat(text(sheet, 2, 4)).isEqualTo("作成");
		}
		// 次回の読み込みでは列として読める（差分での無視は SyncRunner で行う）
		assertThat(parser.parseFromPath(file.toString()).headers()).contains("同期結果");
	}

	@Test
	@DisplayName("xlsx: 既存の列に上書きし、成功した行の前回のエラー理由を消す。数式のセルは上書きせず警告")
	void xlsx_existingColumns_clearAndFormula() throws Exception {
		XSSFWorkbook wb = simpleSheet("同期結果", "メモ", "エラー理由", "最終同期日時");
		wb.getSheetAt(0).getRow(1).createCell(3).setCellValue("エラー");
		wb.getSheetAt(0).getRow(1).createCell(4).setCellValue("keep");
		wb.getSheetAt(0).getRow(1).createCell(5).setCellValue("前回のエラー");
		wb.getSheetAt(0).getRow(2).createCell(3).setCellFormula("\"固定\"");
		Path file = save(wb, "wbs.xlsx");

		TicketIdWriter.WriteBackResult result = writer.writeBack(file.toString(), "チケットID", Map.of(),
				ExcelSource.DEFAULT, true, results(rows(2, "更新", "", 3, "エラー", "理由")));

		assertThat(result.warnings()).singleElement().asString().contains("「同期結果」列は数式のセル");
		try (Workbook book = open(file)) {
			Sheet sheet = book.getSheetAt(0);
			assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 7);
			assertThat(text(sheet, 1, 3)).isEqualTo("更新");
			assertThat(text(sheet, 1, 4)).isEqualTo("keep");
			assertThat(sheet.getRow(1).getCell(5).getCellType()).isEqualTo(CellType.BLANK);
			assertThat(text(sheet, 1, 6)).isEqualTo(AT);
			assertThat(sheet.getRow(2).getCell(3).getCellType()).isEqualTo(CellType.FORMULA);
			assertThat(text(sheet, 2, 5)).isEqualTo("理由");
		}
	}

	private static XSSFWorkbook tableBook(boolean blockRight) {
		XSSFWorkbook wb = new XSSFWorkbook();
		XSSFSheet sheet = wb.createSheet("取込");
		String[][] cells = { { "チケットID", "トラッカー", "大分類" }, { "10", "サマリ", "A" }, { "", "サマリ", "B" } };
		for (int r = 0; r < cells.length; r++) {
			Row row = sheet.createRow(r + 1);
			for (int c = 0; c < 3; c++) {
				row.createCell(c + 1).setCellValue(cells[r][c]);
			}
		}
		if (blockRight) {
			sheet.getRow(2).createCell(4).setCellValue("メモ");
		}
		XSSFTable table = sheet.createTable(new AreaReference("B2:D4", SpreadsheetVersion.EXCEL2007));
		table.setName("取込表");
		table.setDisplayName("取込表");
		table.getCTTable().addNewAutoFilter().setRef("B2:D4");
		return wb;
	}

	@Test
	@DisplayName("テーブル: 同期結果の列がなければテーブルを右に広げて追加する")
	void table_extended() throws Exception {
		Path file = save(tableBook(false), "table.xlsx");
		ExcelSource source = new ExcelSource(null, "取込表");

		TicketIdWriter.WriteBackResult result = writer.writeBack(file.toString(), "チケットID", Map.of(),
				source, true, results(rows(3, "変更なし", "", 4, "エラー", "x")));

		assertThat(result.warnings()).isEmpty();
		try (Workbook wb = open(file)) {
			XSSFTable table = ((XSSFSheet) wb.getSheet("取込")).getTables().get(0);
			assertThat(table.getArea().formatAsString()).isEqualTo("B2:G4");
			assertThat(table.getColumns()).extracting(c -> c.getName()).containsExactly("チケットID", "トラッカー",
					"大分類", "最終同期日時", "同期結果", "エラー理由");
			assertThat(table.getCTTable().getAutoFilter().getRef()).isEqualTo("B2:G4");
		}
		SpreadsheetParser.ParsedSheet parsed = parser.parseFromPath(file.toString(), source);
		assertThat(parsed.rows().get(0)).containsEntry("同期結果", "変更なし").containsEntry("最終同期日時", AT);
		assertThat(parsed.rows().get(1)).containsEntry("同期結果", "エラー").containsEntry("エラー理由", "x");
	}

	@Test
	@DisplayName("テーブル: すぐ右の列が使われていれば列を追加せず警告（ID は書き戻す）")
	void table_blocked() throws Exception {
		Path file = save(tableBook(true), "table.xlsx");
		ExcelSource source = new ExcelSource(null, "取込表");

		TicketIdWriter.WriteBackResult result = writer.writeBack(file.toString(), "チケットID", Map.of(4, 55L),
				source, true, results(rows(3, "変更なし", "", 4, "作成", "")));

		assertThat(result.warnings()).singleElement().asString().contains("「最終同期日時」列がなく").contains("追加できません");
		try (Workbook wb = open(file)) {
			XSSFTable table = ((XSSFSheet) wb.getSheet("取込")).getTables().get(0);
			assertThat(table.getArea().formatAsString()).isEqualTo("B2:D4");
			assertThat(text(wb.getSheet("取込"), 3, 1)).isEqualTo("55");
			assertThat(text(wb.getSheet("取込"), 2, 4)).isEqualTo("メモ");
		}
	}

	@Test
	@DisplayName("CSV: 文字コード（Shift_JIS）・改行（CRLF）を保ち、IDと結果を書く。カンマを含む理由は引用符で囲む")
	void csv_shiftJis() throws Exception {
		Path file = tempDir.resolve("wbs.csv");
		String content = "チケットID,トラッカー,大分類\r\n10,サマリ,A\r\n,サマリ,B\r\n";
		Files.write(file, content.getBytes("Windows-31J"));

		writer.writeBack(file.toString(), "チケットID", Map.of(3, 77L), ExcelSource.DEFAULT, true,
				results(rows(2, "変更なし", "", 3, "エラー", "a,b\n改行")));

		String text = new String(Files.readAllBytes(file), "Windows-31J");
		assertThat(text).isEqualTo("チケットID,トラッカー,大分類,最終同期日時,同期結果,エラー理由\r\n"
				+ "10,サマリ,A," + AT + ",変更なし,\r\n"
				+ "77,サマリ,B," + AT + ",エラー,\"a,b 改行\"\r\n");
		assertThat(new String(Files.readAllBytes(tempDir.resolve("wbs.csv.bak")), "Windows-31J")).isEqualTo(content);
	}

	@Test
	@DisplayName("長いエラー理由は切り詰める。書く内容がなければファイルを変更しない")
	void truncateAndNothingToWrite() throws Exception {
		Path file = tempDir.resolve("wbs.csv");
		Files.writeString(file, "チケットID,大分類\n,A\n", StandardCharsets.UTF_8);

		TicketIdWriter.WriteBackResult none = writer.writeBack(file.toString(), "チケットID", Map.of(),
				ExcelSource.DEFAULT, true, results(Map.of()));
		assertThat(none.backup()).isNull();
		assertThat(tempDir.resolve("wbs.csv.bak")).doesNotExist();

		writer.writeBack(file.toString(), "チケットID", Map.of(), ExcelSource.DEFAULT, true,
				results(rows(2, "エラー", "x".repeat(5000))));
		String row = Files.readAllLines(file, StandardCharsets.UTF_8).get(1);
		assertThat(row).endsWith(",エラー," + "x".repeat(TicketIdWriter.MAX_ERROR_LENGTH) + "…");
		// ID列がなく、書き戻すIDもなければ ID 列は追加しない
		assertThat(Files.readAllLines(file, StandardCharsets.UTF_8).get(0))
				.isEqualTo("チケットID,大分類,最終同期日時,同期結果,エラー理由");
	}
}
