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
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
	 * ファイルパスからスプレッドシートを解析します（階層列の補完なし）。
	 *
	 * @param filePath ファイルパス
	 * @return 解析結果
	 * @throws IOException 解析に失敗した場合
	 */
	public ParsedSheet parseFromPath(String filePath) throws IOException {
		return parseFromPath(filePath, List.of());
	}

	/**
	 * ファイルパスからスプレッドシートを解析します。
	 * <p>
	 * hierarchyColumns（浅い順）に指定された階層列は、セル結合などで空欄になっている場合に
	 * 前行の値で補完します。ただし補完するのは「その行でより深い階層列に値がある」空欄だけです。
	 * 一番深い値より右側の空欄は、その行の階層レベルを表すため補完しません。
	 * また、ある階層列に前行と異なる値が入った場合、それより深い列の前行値は引き継ぎません。
	 * </p>
	 *
	 * @param filePath ファイルパス
	 * @param hierarchyColumns 階層列（浅い順）。空の場合は補完しない
	 * @return 解析結果
	 * @throws IOException 解析に失敗した場合
	 */
	public ParsedSheet parseFromPath(String filePath, List<String> hierarchyColumns) throws IOException {
		Path path = Paths.get(filePath);
		if (!Files.exists(path)) {
			throw new IOException("File not found: " + filePath);
		}
		String filename = path.getFileName().toString();
		if (filename.toLowerCase().endsWith(".csv")) {
			return parseCsvFromPath(path, hierarchyColumns);
		}
		return parseExcelFromPath(path, hierarchyColumns);
	}

	/**
	 * CSVファイルをパスから解析します。
	 * 文字コード（UTF-8 / BOM付きUTF-8 / Windows-31J）は自動判定します。
	 */
	private ParsedSheet parseCsvFromPath(Path path, List<String> hierarchyColumns) throws IOException {
		byte[] bytes = Files.readAllBytes(path);
		CsvFileFormat format = CsvFileFormat.detect(bytes);
		try (CSVReader csv = newCsvReader(format.decode(bytes))) {
			return parseCsvInternal(csv, hierarchyColumns);
		} catch (CsvValidationException e) {
			throw new IOException("Failed to parse CSV", e);
		}
	}

	/**
	 * Excelファイルをパスから解析します（先頭シートのみ）。
	 */
	private ParsedSheet parseExcelFromPath(Path path, List<String> hierarchyColumns) throws IOException {
		try (InputStream is = new FileInputStream(path.toFile());
				Workbook workbook = WorkbookFactory.create(is)) {
			return parseExcelInternal(workbook, hierarchyColumns);
		}
	}

	private ParsedSheet parseCsv(MultipartFile file) throws IOException {
		byte[] bytes = file.getBytes();
		CsvFileFormat format = CsvFileFormat.detect(bytes);
		try (CSVReader csv = newCsvReader(format.decode(bytes))) {
			return parseCsvInternal(csv, List.of());
		} catch (CsvValidationException e) {
			throw new IOException("Failed to parse CSV", e);
		}
	}

	/**
	 * CSVReaderからデータを解析する内部メソッド。
	 * 行番号はCSVのレコード番号（ヘッダ=1、最初のデータ行=2）です。
	 */
	private ParsedSheet parseCsvInternal(CSVReader csv, List<String> hierarchyColumns)
			throws IOException, CsvValidationException {
		String[] headerRow = csv.readNext();
		if (headerRow == null) {
			return new ParsedSheet(List.of(), List.of(), List.of());
		}
		List<String> headers = new ArrayList<>();
		for (String header : headerRow) {
			headers.add(normalize(header));
		}
		HierarchyFiller filler = new HierarchyFiller(headers, hierarchyColumns);
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
			rows.add(toRowMap(headers, filler.fill(normalized)));
			rowNumbers.add(recordNumber);
		}
		return new ParsedSheet(headers, rows, rowNumbers);
	}

	private ParsedSheet parseExcel(MultipartFile file) throws IOException {
		try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
			return parseExcelInternal(workbook, List.of());
		}
	}

	/**
	 * Workbookからデータを解析する内部メソッド。
	 * 行番号はExcelの表示行番号（1始まり）です。
	 */
	private ParsedSheet parseExcelInternal(Workbook workbook, List<String> hierarchyColumns) {
		Sheet sheet = workbook.getSheetAt(0);
		Row headerRow = sheet.getRow(sheet.getFirstRowNum());
		if (headerRow == null) {
			return new ParsedSheet(List.of(), List.of(), List.of());
		}
		List<String> headers = new ArrayList<>();
		for (int i = 0; i < headerRow.getLastCellNum(); i++) {
			headers.add(normalize(getCellString(headerRow.getCell(i))));
		}
		HierarchyFiller filler = new HierarchyFiller(headers, hierarchyColumns);
		List<Map<String, String>> rows = new ArrayList<>();
		List<Integer> rowNumbers = new ArrayList<>();
		for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
			Row row = sheet.getRow(rowIndex);
			if (row == null) {
				continue;
			}
			boolean allBlank = true;
			String[] normalized = new String[headers.size()];
			for (int i = 0; i < headers.size(); i++) {
				String v = normalize(getCellString(row.getCell(i)));
				normalized[i] = v;
				if (!v.isBlank()) {
					allBlank = false;
				}
			}
			if (allBlank) {
				continue;
			}
			rows.add(toRowMap(headers, filler.fill(normalized)));
			rowNumbers.add(rowIndex + 1);
		}
		return new ParsedSheet(headers, rows, rowNumbers);
	}

	private Map<String, String> toRowMap(List<String> headers, String[] values) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < headers.size(); i++) {
			map.put(headers.get(i), values[i]);
		}
		return map;
	}

	private String getCellString(Cell cell) {
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

	private String formatFormulaCell(Cell cell) {
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

	private String formatNumericCell(double value) {
		long asLong = (long) value;
		if (Math.abs(value - asLong) < DOUBLE_TOLERANCE) {
			return Long.toString(asLong);
		}
		return Double.toString(value);
	}

	private String formatDateCell(Cell cell) {
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
	 * 階層列の前行値補完を行うヘルパー。
	 */
	private static final class HierarchyFiller {
		private final int[] indexes;
		private final String[] lastValues;

		private HierarchyFiller(List<String> headers, List<String> hierarchyColumns) {
			List<Integer> found = new ArrayList<>();
			for (String column : hierarchyColumns == null ? List.<String>of() : hierarchyColumns) {
				int idx = headers.indexOf(column);
				if (idx >= 0) {
					found.add(idx);
				}
			}
			this.indexes = found.stream().mapToInt(Integer::intValue).toArray();
			this.lastValues = new String[indexes.length];
			java.util.Arrays.fill(lastValues, "");
		}

		private String[] fill(String[] values) {
			int deepest = -1;
			for (int k = 0; k < indexes.length; k++) {
				if (!values[indexes[k]].isBlank()) {
					deepest = k;
				}
			}
			for (int k = 0; k < indexes.length; k++) {
				String v = values[indexes[k]];
				if (!v.isBlank()) {
					if (!v.equals(lastValues[k])) {
						for (int j = k + 1; j < indexes.length; j++) {
							lastValues[j] = "";
						}
					}
					lastValues[k] = v;
				} else if (k < deepest) {
					values[indexes[k]] = lastValues[k];
				}
			}
			return values;
		}
	}

	/**
	 * 解析結果。
	 *
	 * @param headers ヘッダ（ファイル上の順序）
	 * @param rows 行データ（空行は除外）
	 * @param rowNumbers 各行のファイル上の行番号（Excel: 表示行番号、CSV: レコード番号。ヘッダ=1）
	 */
	public record ParsedSheet(List<String> headers, List<Map<String, String>> rows, List<Integer> rowNumbers) {
		/**
		 * 行番号なしで構築します（行番号は2から連番）。
		 */
		public ParsedSheet(List<String> headers, List<Map<String, String>> rows) {
			this(headers, rows, sequentialRowNumbers(rows.size()));
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
