package mozaki.redmineUpster.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvValidationException;

import mozaki.redmineUpster.util.CsvFileFormat;

/**
 * 新規作成したチケットIDを入力ファイル（Excel/CSV）の「チケットID」列へ書き戻すクラス。
 * <p>
 * 書き込み前に元ファイルを {@code <file>.bak} として保存します。
 * チケットID列が存在しない場合はヘッダの末尾に追加します。
 * 行番号は {@link SpreadsheetParser.ParsedSheet#rowNumbers()} と同じ番号体系
 * （Excel: 表示行番号、CSV: レコード番号。ヘッダ=1）です。
 * </p>
 */
@Service
public class TicketIdWriter {

	/**
	 * チケットIDを書き戻します。
	 *
	 * @param filePath 入力ファイルのパス
	 * @param ticketIdColumn チケットID列のヘッダ名
	 * @param rowIssueIds 行番号 → チケットID
	 * @return バックアップファイルのパス
	 * @throws IOException 読み書きに失敗した場合
	 */
	public Path writeBack(String filePath, String ticketIdColumn, Map<Integer, Long> rowIssueIds) throws IOException {
		Path path = Paths.get(filePath);
		byte[] original = Files.readAllBytes(path);
		byte[] updated;
		if (path.getFileName().toString().toLowerCase().endsWith(".csv")) {
			updated = rewriteCsv(original, ticketIdColumn, rowIssueIds);
		} else {
			updated = rewriteExcel(original, ticketIdColumn, rowIssueIds);
		}
		Path backup = path.resolveSibling(path.getFileName().toString() + ".bak");
		Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
		Files.write(path, updated);
		return backup;
	}

	private byte[] rewriteExcel(byte[] original, String ticketIdColumn, Map<Integer, Long> rowIssueIds)
			throws IOException {
		try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(original))) {
			Sheet sheet = workbook.getSheetAt(0);
			Row header = sheet.getRow(sheet.getFirstRowNum());
			if (header == null) {
				throw new IOException("ヘッダ行が見つかりません");
			}
			int column = findExcelColumn(header, ticketIdColumn);
			if (column < 0) {
				column = Math.max(header.getLastCellNum(), 0);
				Cell headerCell = header.createCell(column, CellType.STRING);
				headerCell.setCellValue(ticketIdColumn);
				if (column > 0 && header.getCell(column - 1) != null) {
					headerCell.setCellStyle(header.getCell(column - 1).getCellStyle());
				}
			}
			for (Map.Entry<Integer, Long> entry : rowIssueIds.entrySet()) {
				int rowIndex = entry.getKey() - 1;
				Row row = sheet.getRow(rowIndex);
				if (row == null) {
					row = sheet.createRow(rowIndex);
				}
				Cell cell = row.getCell(column);
				if (cell == null) {
					cell = row.createCell(column);
				}
				cell.setCellValue(entry.getValue().doubleValue());
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			workbook.write(out);
			return out.toByteArray();
		}
	}

	private int findExcelColumn(Row header, String ticketIdColumn) {
		DataFormatter formatter = new DataFormatter();
		for (int i = 0; i < header.getLastCellNum(); i++) {
			Cell cell = header.getCell(i);
			if (cell != null && ticketIdColumn.equals(formatter.formatCellValue(cell).trim())) {
				return i;
			}
		}
		return -1;
	}

	private byte[] rewriteCsv(byte[] original, String ticketIdColumn, Map<Integer, Long> rowIssueIds)
			throws IOException {
		CsvFileFormat format = CsvFileFormat.detect(original);
		List<String[]> records = new ArrayList<>();
		try (CSVReader reader = SpreadsheetParser.newCsvReader(format.decode(original))) {
			String[] record;
			while ((record = reader.readNext()) != null) {
				records.add(record);
			}
		} catch (CsvValidationException e) {
			throw new IOException("Failed to parse CSV", e);
		}
		if (records.isEmpty()) {
			throw new IOException("ヘッダ行が見つかりません");
		}
		String[] header = records.get(0);
		int column = -1;
		for (int i = 0; i < header.length; i++) {
			String name = header[i] == null ? "" : header[i];
			if (i == 0 && !name.isEmpty() && name.charAt(0) == '﻿') {
				name = name.substring(1);
			}
			if (ticketIdColumn.equals(name.trim())) {
				column = i;
				break;
			}
		}
		if (column < 0) {
			column = header.length;
			records.set(0, withValue(header, column, ticketIdColumn));
		}
		for (Map.Entry<Integer, Long> entry : rowIssueIds.entrySet()) {
			int index = entry.getKey() - 1;
			if (index <= 0 || index >= records.size()) {
				throw new IOException("書き戻し対象の行が見つかりません: " + entry.getKey());
			}
			records.set(index, withValue(records.get(index), column, String.valueOf(entry.getValue())));
		}

		StringWriter buffer = new StringWriter();
		try (CSVWriter writer = new CSVWriter(buffer, CSVWriter.DEFAULT_SEPARATOR, CSVWriter.DEFAULT_QUOTE_CHARACTER,
				CSVWriter.DEFAULT_ESCAPE_CHARACTER, format.lineSeparator())) {
			for (String[] record : records) {
				writer.writeNext(record, false);
			}
		}
		String text = buffer.toString();
		if (!format.trailingNewline() && text.endsWith(format.lineSeparator())) {
			text = text.substring(0, text.length() - format.lineSeparator().length());
		}
		return format.encode(text);
	}

	private String[] withValue(String[] record, int column, String value) {
		String[] copy = record.length > column ? record.clone() : Arrays.copyOf(record, column + 1);
		for (int i = 0; i < copy.length; i++) {
			if (copy[i] == null) {
				copy[i] = "";
			}
		}
		copy[column] = value;
		return copy;
	}
}
