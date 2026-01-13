package mozaki.redmineUpster.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.opencsv.CSVReader;

@Service
public class SpreadsheetParser {

	public ParsedSheet parse(MultipartFile file) throws IOException {
		String filename = file.getOriginalFilename();
		if (filename != null && filename.toLowerCase().endsWith(".csv")) {
			return parseCsv(file);
		}
		return parseExcel(file);
	}

	private ParsedSheet parseCsv(MultipartFile file) throws IOException {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
				CSVReader csv = new CSVReader(reader)) {
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
	}

	private ParsedSheet parseExcel(MultipartFile file) throws IOException {
		try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
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
	}

	private String getCellString(Cell cell) {
		if (cell == null) {
			return "";
		}
		return switch (cell.getCellType()) {
			case STRING -> cell.getStringCellValue();
			case NUMERIC -> {
				double value = cell.getNumericCellValue();
				long asLong = (long) value;
				if (Math.abs(value - asLong) < 0.0000001) {
					yield Long.toString(asLong);
				}
				yield Double.toString(value);
			}
			case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
			case FORMULA -> cell.getCellFormula();
			default -> "";
		};
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
