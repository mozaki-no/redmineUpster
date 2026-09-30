package mozaki.redmineUpster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
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

import mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet;
import mozaki.redmineUpster.util.DateParser;

/**
 * Excel の読み込み元（シート・テーブル）の指定、数式セルの読み取り、書き戻しのテスト。
 */
class ExcelSourceTests {

	@TempDir
	Path dir;

	private final SpreadsheetParser parser = new SpreadsheetParser();
	private final TicketIdWriter writer = new TicketIdWriter();

	private static void set(Sheet sheet, int row, int col, Object value) {
		Row r = sheet.getRow(row) != null ? sheet.getRow(row) : sheet.createRow(row);
		Cell cell = r.createCell(col);
		if (value instanceof Number n) {
			cell.setCellValue(n.doubleValue());
		} else if (value instanceof String s && s.startsWith("=")) {
			cell.setCellFormula(s.substring(1));
		} else {
			cell.setCellValue(String.valueOf(value));
		}
	}

	private Path save(Workbook workbook, String name) throws Exception {
		workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
		Path file = dir.resolve(name);
		try (OutputStream out = Files.newOutputStream(file)) {
			workbook.write(out);
		}
		workbook.close();
		return file;
	}

	/** 1枚目: 雑多な WBS、2枚目「取込」: 行3がヘッダ（行1・2は空行） */
	private Path twoSheetWorkbook() throws Exception {
		XSSFWorkbook wb = new XSSFWorkbook();
		Sheet messy = wb.createSheet("WBS");
		set(messy, 0, 0, "メモ");
		set(messy, 1, 0, "ぐちゃぐちゃ");
		Sheet clean = wb.createSheet("取込");
		set(clean, 2, 0, "チケットID");
		set(clean, 2, 1, "大分類");
		set(clean, 3, 1, "A");
		set(clean, 4, 0, 12);
		set(clean, 4, 1, "B");
		return save(wb, "two-sheets.xlsx");
	}

	@Test
	@DisplayName("シート名・シート番号で読み込み元を指定できる（値のある最初の行がヘッダ）")
	void sheetByNameOrIndex() throws Exception {
		Path file = twoSheetWorkbook();
		for (String spec : List.of("取込", "2")) {
			ParsedSheet sheet = parser.parseFromPath(file.toString(), new ExcelSource(spec, null));
			assertThat(sheet.sheetName()).isEqualTo("取込");
			assertThat(sheet.headers()).containsExactly("チケットID", "大分類");
			assertThat(sheet.rowNumbers()).containsExactly(4, 5);
			assertThat(sheet.rows().get(1).get("チケットID")).isEqualTo("12");
		}
		assertThatThrownBy(() -> parser.parseFromPath(file.toString(), new ExcelSource("存在しない", null)))
				.hasMessageContaining("シート「存在しない」が見つかりません").hasMessageContaining("2:取込");
		ParsedSheet first = parser.parseFromPath(file.toString());
		assertThat(first.sheetName()).isEqualTo("WBS");
	}

	@Test
	@DisplayName("シート指定: 上の空行は読み飛ばし、値のある最初の行をヘッダにする")
	void sheetHeaderIsFirstNonEmptyRow() throws Exception {
		XSSFWorkbook wb = new XSSFWorkbook();
		wb.createSheet("WBS");
		Sheet clean = wb.createSheet("取込");
		clean.createRow(0);
		set(clean, 2, 0, "チケットID");
		set(clean, 2, 1, "大分類");
		set(clean, 3, 1, "A");
		Path file = save(wb, "blank-top.xlsx");
		ParsedSheet sheet = parser.parseFromPath(file.toString(), new ExcelSource("取込", null));
		assertThat(sheet.headers()).containsExactly("チケットID", "大分類");
		assertThat(sheet.rowNumbers()).containsExactly(4);
	}

	/**
	 * 1枚目「WBS 本体」に元データ、2枚目「取込」の B4:F7 にテーブル「取込表」（数式で元データを参照）。
	 * テーブルの外（G列・行9）にも値を置く。
	 */
	private Path tableWorkbook(String ticketIdFormulaRow6) throws Exception {
		XSSFWorkbook wb = new XSSFWorkbook();
		Sheet src = wb.createSheet("WBS 本体");
		CellStyle dateStyle = wb.createCellStyle();
		dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy/mm/dd"));
		set(src, 0, 0, "ID");
		set(src, 0, 1, "大分類");
		set(src, 0, 2, "タスク");
		set(src, 0, 3, "着手予定");
		// 行2: ID あり、行3: ID 空欄（新規）
		set(src, 1, 0, 101);
		set(src, 1, 1, "設計");
		set(src, 2, 1, "設計");
		set(src, 2, 2, "画面設計");
		Cell date = src.getRow(2).createCell(3);
		date.setCellValue(Date.from(LocalDate.of(2026, 1, 10).atStartOfDay(ZoneId.systemDefault()).toInstant()));
		date.setCellStyle(dateStyle);

		XSSFSheet sheet = wb.createSheet("取込");
		set(sheet, 0, 0, "取込用（この行はテーブル外）");
		String[] headers = { "チケットID", "トラッカー", "大分類", "タスク", "着手予定" };
		for (int c = 0; c < headers.length; c++) {
			set(sheet, 3, c + 1, headers[c]);
		}
		set(sheet, 3, 6, "テーブル外の列");
		// 行5: 元データ行2 を参照、行6: 元データ行3 を参照、行7: 直接入力（チケットID空欄）
		set(sheet, 4, 1, "='WBS 本体'!$A$2");
		set(sheet, 4, 2, "サマリ");
		set(sheet, 4, 3, "='WBS 本体'!B2");
		set(sheet, 4, 4, "='WBS 本体'!C2");
		set(sheet, 5, 1, ticketIdFormulaRow6);
		set(sheet, 5, 2, "タスク");
		set(sheet, 5, 3, "='WBS 本体'!B3");
		set(sheet, 5, 4, "='WBS 本体'!C3");
		set(sheet, 5, 5, "='WBS 本体'!D3");
		set(sheet, 5, 6, "外");
		set(sheet, 6, 2, "タスク");
		set(sheet, 6, 3, "設計");
		set(sheet, 6, 4, "直接入力");
		set(sheet, 8, 3, "テーブルの下");
		XSSFTable table = sheet.createTable(new AreaReference("B4:F7", SpreadsheetVersion.EXCEL2007));
		table.setName("取込表");
		table.setDisplayName("取込表");
		return save(wb, "table.xlsx");
	}

	@Test
	@DisplayName("テーブル指定: 2枚目のテーブル範囲だけを読み、数式は計算済みの値（参照先が空欄なら空欄）を読む")
	void tableWithFormulas() throws Exception {
		Path file = tableWorkbook("='WBS 本体'!$A$3");
		ParsedSheet sheet = parser.parseFromPath(file.toString(), new ExcelSource("WBS 本体", "取込表"));
		assertThat(sheet.sheetName()).isEqualTo("取込");
		assertThat(sheet.headers()).containsExactly("チケットID", "トラッカー", "大分類", "タスク", "着手予定");
		assertThat(sheet.rowNumbers()).containsExactly(5, 6, 7);
		Map<String, String> row5 = sheet.rows().get(0);
		assertThat(row5.get("チケットID")).isEqualTo("101");
		assertThat(row5.get("タスク")).isEmpty(); // 参照先が空欄（Excel は 0 を保存する）
		Map<String, String> row6 = sheet.rows().get(1);
		assertThat(row6.get("チケットID")).isEmpty();
		assertThat(row6.get("タスク")).isEqualTo("画面設計");
		// 日付書式のない数式セルはシリアル値で保存されるが、日付として解釈できる
		assertThat(DateParser.normalizeDate(row6.get("着手予定"))).isEqualTo("2026-01-10");
		assertThat(sheet.rows()).noneMatch(r -> r.containsKey("テーブル外の列"));
	}

	@Test
	@DisplayName("テーブルが見つからない場合は、ファイル内のテーブル名を示してエラー")
	void missingTable() throws Exception {
		Path file = tableWorkbook("='WBS 本体'!$A$3");
		assertThatThrownBy(() -> parser.parseFromPath(file.toString(), new ExcelSource(null, "なし")))
				.hasMessageContaining("テーブル「なし」が見つかりません")
				.hasMessageContaining("取込表（シート「取込」）");
	}

	@Test
	@DisplayName("書き戻し: テーブルの値セルへはそのまま、単一セル参照の数式は参照先のセルへ書き込む")
	void writeBackToTableAndThroughReference() throws Exception {
		Path file = tableWorkbook("='WBS 本体'!$A$3");
		ExcelSource source = new ExcelSource(null, "取込表");
		TicketIdWriter.WriteBackResult result = writer.writeBack(file.toString(), "チケットID",
				Map.of(6, 555L, 7, 556L), source);

		assertThat(result.failures()).isEmpty();
		assertThat(result.backup()).exists();
		assertThat(result.notes()).singleElement().satisfies(n -> assertThat(n)
				.contains("シート「取込」行6").contains("#555").contains("'WBS 本体'!A3"));
		try (InputStream in = Files.newInputStream(file); Workbook wb = WorkbookFactory.create(in)) {
			Sheet src = wb.getSheet("WBS 本体");
			assertThat(src.getRow(2).getCell(0).getNumericCellValue()).isEqualTo(555);
			Cell formula = wb.getSheet("取込").getRow(5).getCell(1);
			assertThat(formula.getCellType()).isEqualTo(CellType.FORMULA);
			assertThat(formula.getCellFormula()).isEqualTo("'WBS 本体'!$A$3");
			assertThat(wb.getSheet("取込").getRow(6).getCell(1).getNumericCellValue()).isEqualTo(556);
		}
		// 再計算しなくても、次回の読み込みで ID が読める
		ParsedSheet reread = parser.parseFromPath(file.toString(), source);
		assertThat(reread.rows().get(1).get("チケットID")).isEqualTo("555");
		assertThat(reread.rows().get(2).get("チケットID")).isEqualTo("556");
	}

	@Test
	@DisplayName("書き戻し: 単一セル参照以外の数式は上書きせず、手で入力するようエラーにする")
	void writeBackRefusesComplexFormula() throws Exception {
		Path file = tableWorkbook("=IF('WBS 本体'!A3=\"\",\"\",'WBS 本体'!A3)");
		TicketIdWriter.WriteBackResult result = writer.writeBack(file.toString(), "チケットID",
				Map.of(6, 555L), new ExcelSource(null, "取込表"));

		assertThat(result.failures()).singleElement().satisfies(f -> assertThat(f)
				.contains("シート「取込」行6").contains("#555").contains("手で入力"));
		assertThat(result.backup()).isNull();
		try (InputStream in = Files.newInputStream(file); Workbook wb = WorkbookFactory.create(in)) {
			assertThat(wb.getSheet("取込").getRow(5).getCell(1).getCellFormula()).startsWith("IF(");
			Row srcRow = wb.getSheet("WBS 本体").getRow(2);
			assertThat(srcRow.getCell(0) == null || srcRow.getCell(0).getCellType() == CellType.BLANK).isTrue();
		}
	}

	@Test
	@DisplayName("CLI の指定は設定より優先（CLI でシートだけを指定したら設定のテーブルは使わない）")
	void mergePrecedence() {
		ExcelSource config = new ExcelSource("取込", "取込表");
		assertThat(ExcelSource.merge(config, null)).isEqualTo(config);
		assertThat(ExcelSource.merge(config, new ExcelSource(" ", ""))).isEqualTo(config);
		assertThat(ExcelSource.merge(config, new ExcelSource("2", null))).isEqualTo(new ExcelSource("2", null));
		assertThat(ExcelSource.merge(config, new ExcelSource(null, "別表"))).isEqualTo(new ExcelSource(null, "別表"));
	}
}
