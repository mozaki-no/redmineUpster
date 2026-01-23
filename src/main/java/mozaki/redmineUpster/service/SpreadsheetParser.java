package mozaki.redmineUpster.service;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import com.opencsv.exceptions.CsvValidationException;

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
	 *
	 * @param filePath ファイルパス
	 * @return 解析結果
	 * @throws IOException 解析に失敗した場合
	 */
	public ParsedSheet parseFromPath(String filePath) throws IOException {
		Path path = Paths.get(filePath);
		if (!Files.exists(path)) {
			throw new IOException("File not found: " + filePath);
		}
		String filename = path.getFileName().toString();
		if (filename.toLowerCase().endsWith(".csv")) {
			return parseCsvFromPath(path);
		}
		return parseExcelFromPath(path);
	}

	/**
	 * CSVファイルをパスから解析します。
	 *
	 * @param path ファイルパス
	 * @return 解析結果
	 * @throws IOException 解析に失敗した場合
	 */
	private ParsedSheet parseCsvFromPath(Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
				CSVReader csv = new CSVReader(reader)) {
			return parseCsvInternal(csv);
		} catch (CsvValidationException e) {
			throw new IOException("Failed to parse CSV", e);
		}
	}

	/**
	 * Excelファイルをパスから解析します。
	 *
	 * @param path ファイルパス
	 * @return 解析結果
	 * @throws IOException 解析に失敗した場合
	 */
	private ParsedSheet parseExcelFromPath(Path path) throws IOException {
		try (InputStream is = new FileInputStream(path.toFile());
				Workbook workbook = WorkbookFactory.create(is)) {
			return parseExcelInternal(workbook);
		}
	}

	private ParsedSheet parseCsv(MultipartFile file) throws IOException {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
				CSVReader csv = new CSVReader(reader)) {
			return parseCsvInternal(csv);
		} catch (CsvValidationException e) {
			throw new IOException("Failed to parse CSV", e);
		}
	}

	/**
	 * CSVReaderからデータを解析する内部メソッド。
	 *
	 * @param csv CSVReader
	 * @return 解析結果
	 * @throws IOException 解析に失敗した場合
	 * @throws CsvValidationException CSV検証に失敗した場合
	 */
	private ParsedSheet parseCsvInternal(CSVReader csv) throws IOException, CsvValidationException {
		String[] headerRow = csv.readNext();
		if (headerRow == null) {
			return new ParsedSheet(List.of(), List.of());
		}
		List<String> headers = new ArrayList<>();
		for (String header : headerRow) {
			headers.add(normalize(header));
		}
		List<Map<String, String>> rows = new ArrayList<>();
		String[] row;
		while ((row = csv.readNext()) != null) {
			Map<String, String> values = new LinkedHashMap<>();
			for (int i = 0; i < headers.size(); i++) {
				String value = i < row.length ? normalize(row[i]) : "";
				values.put(headers.get(i), value);
			}
			if (!isEmptyRow(values)) {
				rows.add(values);
			}
		}
		return new ParsedSheet(headers, rows);
	}

	private ParsedSheet parseExcel(MultipartFile file) throws IOException {
		try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
			return parseExcelInternal(workbook);
		}
	}

	/**
	 * Workbookからデータを解析する内部メソッド。
	 *
	 * @param workbook Workbook
	 * @return 解析結果
	 */
	private ParsedSheet parseExcelInternal(Workbook workbook) {
		Sheet sheet = workbook.getSheetAt(0);
		Row headerRow = sheet.getRow(sheet.getFirstRowNum());
		if (headerRow == null) {
			return new ParsedSheet(List.of(), List.of());
		}
		List<String> headers = new ArrayList<>();
		for (int i = 0; i < headerRow.getLastCellNum(); i++) {
			headers.add(normalize(getCellString(headerRow.getCell(i))));
		}
		List<Map<String, String>> rows = new ArrayList<>();
		for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
			Row row = sheet.getRow(rowIndex);
			if (row == null) {
				continue;
			}
			Map<String, String> values = new LinkedHashMap<>();
			for (int i = 0; i < headers.size(); i++) {
				Cell cell = row.getCell(i);
				values.put(headers.get(i), normalize(getCellString(cell)));
			}
			if (!isEmptyRow(values)) {
				rows.add(values);
			}
		}
		return new ParsedSheet(headers, rows);
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

	private boolean isEmptyRow(Map<String, String> values) {
		return values.values().stream().allMatch(String::isBlank);
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim();
	}

	public record ParsedSheet(List<String> headers, List<Map<String, String>> rows) {
	}
}
