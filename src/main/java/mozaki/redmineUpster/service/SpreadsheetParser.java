package mozaki.redmineUpster.service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180ParserBuilder;
import com.opencsv.exceptions.CsvValidationException;

import mozaki.redmineUpster.util.CsvFileFormat;

/**
 * スプレッドシートパーサー。
 * <p>
 * CSV/Excelファイルを解析し、ヘッダーと行データを抽出します。
 * </p>
 */
@Service
public class SpreadsheetParser {

	/** 浮動小数点比較の許容誤差 */
	private static final double DOUBLE_TOLERANCE = 0.0000001;

	/**
	 * MultipartFileからスプレッドシートを解析します。
	 *
	 * @param file アップロードされたファイル
	 * @return 解析結果
	 * @throws IOException 解析に失敗した場合
	 */
	public ParsedSheet parse(MultipartFile file) throws IOException {
		String filename = file.getOriginalFilename();
		if (filename != null && filename.toLowerCase().endsWith(".csv")) {
			return parseCsv(file);
		}
		return parseExcel(file);
	}

	/**
	 * ファイルパスからスプレッドシートを解析します。
	 * <p>
	 * 値はファイルのまま読み取り、階層列の空欄を前行の値で補完することはしません
	 * （補完は {@code sync.columns.fillDownHierarchy: true} のとき DiffCalculator が行います）。
	 * xlsx/xls の縦方向のセル結合は、結合範囲の先頭セルの値として読み取ります（全列）。
	 * </p>
	 *
	 * @param filePath ファイルパス
	 * @return 解析結果
	 * @throws IOException 解析に失敗した場合
	 */
	public ParsedSheet parseFromPath(String filePath) throws IOException {
		return parseFromPath(filePath, ExcelSource.DEFAULT);
	}

	/**
	 * ファイルパスからスプレッドシートを解析します（Excel の読み込み元を指定）。
	 * <p>
	 * Excel ではテーブル（指定時はその範囲。見出し行がヘッダ）＞ シート（値のある最初の行がヘッダ）＞ 先頭シート
	 * の順に読み込み元を決めます。数式のセルは保存時に計算済みの値を読みます。CSV では source を無視します。
	 * </p>
	 *
	 * @param filePath ファイルパス
	 * @param source Excel の読み込み元（null なら先頭シート）
	 * @return 解析結果
	 * @throws IOException 解析に失敗した場合、シート・テーブルが見つからない場合
	 */
	public ParsedSheet parseFromPath(String filePath, ExcelSource source) throws IOException {
		Path path = Paths.get(filePath);
		if (!Files.exists(path)) {
			throw new IOException("File not found: " + filePath);
		}
		String filename = path.getFileName().toString();
		if (filename.toLowerCase().endsWith(".csv")) {
			return parseCsvFromPath(path);
		}
		return parseExcelFromPath(path, source);
	}

	/**
	 * CSVファイルをパスから解析します。
	 * 文字コード（UTF-8 / BOM付きUTF-8 / Windows-31J）は自動判定します。
	 */
	private ParsedSheet parseCsvFromPath(Path path) throws IOException {
		byte[] bytes = Files.readAllBytes(path);
		CsvFileFormat format = CsvFileFormat.detect(bytes);
		try (CSVReader csv = newCsvReader(format.decode(bytes))) {
			return parseCsvInternal(csv);
		} catch (CsvValidationException e) {
			throw new IOException("Failed to parse CSV", e);
		}
	}

	/**
	 * Excelファイルをパスから解析します。
	 */
	private ParsedSheet parseExcelFromPath(Path path, ExcelSource source) throws IOException {
		try (InputStream is = new FileInputStream(path.toFile());
				Workbook workbook = WorkbookFactory.create(is)) {
			return parseExcelInternal(workbook, source);
		}
	}

	private ParsedSheet parseCsv(MultipartFile file) throws IOException {
		byte[] bytes = file.getBytes();
		CsvFileFormat format = CsvFileFormat.detect(bytes);
		try (CSVReader csv = newCsvReader(format.decode(bytes))) {
			return parseCsvInternal(csv);
		} catch (CsvValidationException e) {
			throw new IOException("Failed to parse CSV", e);
		}
	}

	/**
	 * CSVReaderからデータを解析する内部メソッド。
	 * 行番号はCSVのレコード番号（ヘッダ=1、最初のデータ行=2）です。
	 */
	private ParsedSheet parseCsvInternal(CSVReader csv)
			throws IOException, CsvValidationException {
		String[] headerRow = csv.readNext();
		if (headerRow == null) {
			return new ParsedSheet(List.of(), List.of(), List.of());
		}
		List<String> headers = new ArrayList<>();
		for (String header : headerRow) {
			headers.add(normalize(header));
		}
		List<Map<String, String>> rows = new ArrayList<>();
		List<Integer> rowNumbers = new ArrayList<>();
		String[] row;
		int recordNumber = 1;
		while ((row = csv.readNext()) != null) {
			recordNumber++;
			String[] normalized = new String[headers.size()];
			boolean allBlank = true;
			for (int i = 0; i < headers.size(); i++) {
				String v = i < row.length ? normalize(row[i]) : "";
				normalized[i] = v;
				if (!v.isBlank()) {
					allBlank = false;
				}
			}
			if (allBlank) {
				continue;
			}
			rows.add(toRowMap(headers, normalized));
			rowNumbers.add(recordNumber);
		}
		return new ParsedSheet(headers, rows, rowNumbers);
	}

	private ParsedSheet parseExcel(MultipartFile file) throws IOException {
		try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
			return parseExcelInternal(workbook, ExcelSource.DEFAULT);
		}
	}

	/**
	 * Workbookからデータを解析する内部メソッド。
	 * 行番号はExcelの表示行番号（1始まり）です。
	 */
	private ParsedSheet parseExcelInternal(Workbook workbook, ExcelSource source) throws IOException {
		ExcelArea area = ExcelArea.resolve(workbook, source);
		Sheet sheet = area.sheet();
		Row headerRow = area.headerRowIndex() >= 0 ? sheet.getRow(area.headerRowIndex()) : null;
		if (headerRow == null) {
			return new ParsedSheet(List.of(), List.of(), List.of(), area.sheetName());
		}
		int firstColumn = area.firstColumn();
		int lastColumn = area.effectiveLastColumn();
		List<String> headers = new ArrayList<>();
		for (int c = firstColumn; c <= lastColumn; c++) {
			headers.add(normalize(getCellString(headerRow.getCell(c))));
		}
		List<CellRangeAddress> verticalMerges = new ArrayList<>();
		for (CellRangeAddress region : sheet.getMergedRegions()) {
			if (region.getLastRow() > region.getFirstRow()) {
				verticalMerges.add(region);
			}
		}
		List<Map<String, String>> rows = new ArrayList<>();
		List<Integer> rowNumbers = new ArrayList<>();
		for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= area.effectiveLastRow(); rowIndex++) {
			Row row = sheet.getRow(rowIndex);
			if (row == null) {
				continue;
			}
			boolean allBlank = true;
			String[] normalized = new String[headers.size()];
			for (int i = 0; i < headers.size(); i++) {
				int column = firstColumn + i;
				// 空行の判定は結合を考慮しない実セルで行う（結合範囲の末尾だけが残った行を拾わない）
				if (!normalize(getCellString(row.getCell(column))).isBlank()) {
					allBlank = false;
				}
				normalized[i] = normalize(getCellString(resolveMergedCell(sheet, verticalMerges, row, column)));
			}
			if (allBlank) {
				continue;
			}
			rows.add(toRowMap(headers, normalized));
			rowNumbers.add(rowIndex + 1);
		}
		return new ParsedSheet(headers, rows, rowNumbers, area.sheetName());
	}

	/**
	 * 縦方向のセル結合範囲に含まれるセルは、結合範囲の先頭行のセルを返します。
	 * <p>
	 * 結合範囲の先頭列だけが対象です（横方向に結合された右側の列は空欄のまま＝階層を飛ばした扱い）。
	 * </p>
	 */
	private static Cell resolveMergedCell(Sheet sheet, List<CellRangeAddress> verticalMerges, Row row, int column) {
		int rowIndex = row.getRowNum();
		for (CellRangeAddress region : verticalMerges) {
			if (region.getFirstColumn() == column && region.getFirstRow() < rowIndex
					&& rowIndex <= region.getLastRow()) {
				Row top = sheet.getRow(region.getFirstRow());
				return top == null ? null : top.getCell(column);
			}
		}
		return row.getCell(column);
	}

	private Map<String, String> toRowMap(List<String> headers, String[] values) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < headers.size(); i++) {
			map.put(headers.get(i), values[i]);
		}
		return map;
	}

	static String getCellString(Cell cell) {
		if (cell == null) {
			return "";
		}
		return switch (cell.getCellType()) {
			case STRING -> cell.getStringCellValue();
			case NUMERIC -> {
				if (DateUtil.isCellDateFormatted(cell)) {
					yield formatDateCell(cell);
				}
				yield formatNumericCell(cell.getNumericCellValue());
			}
			case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
			case FORMULA -> formatFormulaCell(cell);
			default -> "";
		};
	}

	private static String formatFormulaCell(Cell cell) {
		// 単一セル参照（=WBS!C12 など）の参照先が空欄なら空欄とする（Excel は 0 を計算結果として保存するため）
		ExcelArea.Reference reference = ExcelArea.simpleReference(cell);
		if (reference != null) {
			Cell target = reference.cell();
			if (target == null || target.getCellType() == CellType.BLANK
					|| (target.getCellType() == CellType.STRING && target.getStringCellValue().isBlank())) {
				return "";
			}
		}
		return switch (cell.getCachedFormulaResultType()) {
			case STRING -> cell.getStringCellValue();
			case NUMERIC -> {
				if (DateUtil.isCellDateFormatted(cell)) {
					yield formatDateCell(cell);
				}
				yield formatNumericCell(cell.getNumericCellValue());
			}
			case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
			default -> cell.getCellFormula();
		};
	}

	private static String formatNumericCell(double value) {
		long asLong = (long) value;
		if (Math.abs(value - asLong) < DOUBLE_TOLERANCE) {
			return Long.toString(asLong);
		}
		return Double.toString(value);
	}

	private static String formatDateCell(Cell cell) {
		Instant instant = cell.getDateCellValue().toInstant();
		LocalDate date = instant.atZone(ZoneId.systemDefault()).toLocalDate();
		return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
	}

	private static String normalize(String value) {
		if (value == null) {
			return "";
		}
		String v = value;
		if (!v.isEmpty() && v.charAt(0) == '\uFEFF') {
			v = v.substring(1);
		}
		return v.trim();
	}

	/**
	 * 解析結果。
	 *
	 * @param headers ヘッダ（ファイル上の順序）
	 * @param rows 行データ（空行は除外）
	 * @param rowNumbers 各行のファイル上の行番号（Excel: 表示行番号、CSV: レコード番号。ヘッダ=1）
	 * @param sheetName 読み込んだシート名（CSV では null）
	 */
	public record ParsedSheet(List<String> headers, List<Map<String, String>> rows, List<Integer> rowNumbers,
			String sheetName) {
		/**
		 * シート名なしで構築します。
		 */
		public ParsedSheet(List<String> headers, List<Map<String, String>> rows, List<Integer> rowNumbers) {
			this(headers, rows, rowNumbers, null);
		}

		/**
		 * 行番号なしで構築します（行番号は2から連番）。
		 */
		public ParsedSheet(List<String> headers, List<Map<String, String>> rows) {
			this(headers, rows, sequentialRowNumbers(rows.size()), null);
		}

		private static List<Integer> sequentialRowNumbers(int size) {
			List<Integer> numbers = new ArrayList<>();
			for (int i = 0; i < size; i++) {
				numbers.add(i + 2);
			}
			return numbers;
		}
	}

	/**
	 * RFC4180準拠（バックスラッシュをエスケープ文字として扱わない）のCSVReaderを生成します。
	 */
	static CSVReader newCsvReader(String text) {
		return new CSVReaderBuilder(new StringReader(text))
				.withCSVParser(new RFC4180ParserBuilder().build())
				.build();
	}
}
