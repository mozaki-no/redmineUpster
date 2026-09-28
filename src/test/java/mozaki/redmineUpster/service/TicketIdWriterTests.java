package mozaki.redmineUpster.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mozaki.redmineUpster.util.CsvFileFormat;

class TicketIdWriterTests {

	@TempDir
	Path tempDir;

	private final TicketIdWriter writer = new TicketIdWriter();
	private final SpreadsheetParser parser = new SpreadsheetParser();

	@Test
	@DisplayName("xlsx: 新規作成したIDをチケットID列の該当行に書き込み、他のセル・書式・シートを保持する")
	void writeBack_xlsx() throws Exception {
		Path file = tempDir.resolve("wbs.xlsx");
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("WBS");
			CellStyle bold = workbook.createCellStyle();
			Font font = workbook.createFont();
			font.setBold(true);
			bold.setFont(font);
			Row header = sheet.createRow(0);
			String[] headers = { "チケットID", "トラッカー", "大分類", "中分類" };
			for (int i = 0; i < headers.length; i++) {
				header.createCell(i).setCellValue(headers[i]);
				header.getCell(i).setCellStyle(bold);
			}
			Row r1 = sheet.createRow(1);
			r1.createCell(0).setCellValue(10);
			r1.createCell(1).setCellValue("サマリ");
			r1.createCell(2).setCellValue("A");
			Row r2 = sheet.createRow(2);
			r2.createCell(1).setCellValue("タスク");
			r2.createCell(2).setCellValue("A");
			r2.createCell(3).setCellValue("B");
			workbook.createSheet("メモ").createRow(0).createCell(0).setCellValue("keep");
			try (OutputStream out = Files.newOutputStream(file)) {
				workbook.write(out);
			}
		}

		writer.writeBack(file.toString(), "チケットID", Map.of(3, 123L));

		assertThat(tempDir.resolve("wbs.xlsx.bak")).exists();
		try (InputStream in = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(in)) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(10);
			assertThat(sheet.getRow(2).getCell(0).getNumericCellValue()).isEqualTo(123);
			assertThat(sheet.getRow(2).getCell(3).getStringCellValue()).isEqualTo("B");
			assertThat(workbook.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold())
					.isTrue();
			assertThat(workbook.getSheet("メモ").getRow(0).getCell(0).getStringCellValue()).isEqualTo("keep");
		}
		assertThat(parser.parseFromPath(file.toString()).rows().get(1).get("チケットID")).isEqualTo("123");
	}

	@Test
	@DisplayName("xlsx: チケットID列がなければヘッダの末尾に追加する")
	void writeBack_xlsxAddsMissingColumn() throws Exception {
		Path file = tempDir.resolve("no-id.xlsx");
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("WBS");
			sheet.createRow(0).createCell(0).setCellValue("大分類");
			sheet.getRow(0).createCell(1).setCellValue("トラッカー");
			sheet.createRow(1).createCell(0).setCellValue("A");
			try (OutputStream out = Files.newOutputStream(file)) {
				workbook.write(out);
			}
		}

		writer.writeBack(file.toString(), "チケットID", Map.of(2, 7L));

		try (InputStream in = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(in)) {
			Row header = workbook.getSheetAt(0).getRow(0);
			assertThat(header.getCell(2).getStringCellValue()).isEqualTo("チケットID");
			assertThat(workbook.getSheetAt(0).getRow(1).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
			assertThat(workbook.getSheetAt(0).getRow(1).getCell(2).getNumericCellValue()).isEqualTo(7);
		}
	}

	@Test
	@DisplayName("CSV: BOM・CRLF・列順・引用符付きの値を保ったまま該当行にIDを書き込む")
	void writeBack_csvPreservesFormat() throws Exception {
		Path file = tempDir.resolve("wbs.csv");
		String content = "チケットID,トラッカー,大分類,中分類,備考\r\n"
				+ "10,サマリ,A,,\"カンマ,あり\"\r\n"
				+ ",タスク,A,B,C:\\path\r\n";
		byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
		byte[] body = content.getBytes(StandardCharsets.UTF_8);
		byte[] bytes = new byte[bom.length + body.length];
		System.arraycopy(bom, 0, bytes, 0, bom.length);
		System.arraycopy(body, 0, bytes, bom.length, body.length);
		Files.write(file, bytes);

		writer.writeBack(file.toString(), "チケットID", Map.of(3, 555L));

		assertThat(Files.readAllBytes(tempDir.resolve("wbs.csv.bak"))).isEqualTo(bytes);
		byte[] updated = Files.readAllBytes(file);
		CsvFileFormat format = CsvFileFormat.detect(updated);
		assertThat(format.bom()).isTrue();
		assertThat(format.lineSeparator()).isEqualTo("\r\n");
		assertThat(format.decode(updated)).isEqualTo("チケットID,トラッカー,大分類,中分類,備考\r\n"
				+ "10,サマリ,A,,\"カンマ,あり\"\r\n"
				+ "555,タスク,A,B,C:\\path\r\n");

		List<Map<String, String>> rows = parser.parseFromPath(file.toString()).rows();
		assertThat(rows.get(0).get("チケットID")).isEqualTo("10");
		assertThat(rows.get(1).get("チケットID")).isEqualTo("555");
	}

	@Test
	@DisplayName("CSV: Shift_JIS・LF・チケットID列なしの場合は列を末尾に追加し、文字コードを保つ")
	void writeBack_csvShiftJisAddsColumn() throws Exception {
		Path file = tempDir.resolve("sjis.csv");
		String content = "トラッカー,大分類\n\nタスク,設計\n";
		Files.write(file, content.getBytes(CsvFileFormat.WINDOWS_31J));

		assertThat(parser.parseFromPath(file.toString()).rowNumbers()).containsExactly(3);
		writer.writeBack(file.toString(), "チケットID", Map.of(3, 9L));

		byte[] updated = Files.readAllBytes(file);
		CsvFileFormat format = CsvFileFormat.detect(updated);
		assertThat(format.charset()).isEqualTo(CsvFileFormat.WINDOWS_31J);
		assertThat(format.decode(updated)).isEqualTo("トラッカー,大分類,チケットID\n\nタスク,設計,9\n");
		assertThat(parser.parseFromPath(file.toString()).rows().get(0).get("チケットID")).isEqualTo("9");
	}
}
