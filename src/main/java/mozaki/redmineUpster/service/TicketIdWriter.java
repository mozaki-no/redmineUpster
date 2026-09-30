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
 * チケットID列が存在しない場合はヘッダの末尾に追加します（Excel のテーブル指定時は追加せずエラー）。
 * 行番号は {@link SpreadsheetParser.ParsedSheet#rowNumbers()} と同じ番号体系
 * （Excel: 表示行番号、CSV: レコード番号。ヘッダ=1）です。
 * </p>
 */
@Service
public class TicketIdWriter {

	/**
	 * 書き戻しの結果。
	 *
	 * @param backup バックアップファイル（何も書き込まなかった場合は null）
	 * @param notes 情報（数式の参照先へ書き込んだ行など）
	 * @param failures 書き込めなかった行（数式のセルなど。ユーザーが手で入力する必要がある）
	 */
	public record WriteBackResult(Path backup, List<String> notes, List<String> failures) {
	}

	/**
	 * チケットIDを書き戻します（Excel は先頭シート）。
	 *
	 * @param filePath 入力ファイルのパス
	 * @param ticketIdColumn チケットID列のヘッダ名
	 * @param rowIssueIds 行番号 → チケットID
	 * @return バックアップファイルのパス
	 * @throws IOException 読み書きに失敗した場合、または書き込めない行があった場合
	 */
	public Path writeBack(String filePath, String ticketIdColumn, Map<Integer, Long> rowIssueIds) throws IOException {
		WriteBackResult result = writeBack(filePath, ticketIdColumn, rowIssueIds, ExcelSource.DEFAULT);
		if (!result.failures().isEmpty()) {
			throw new IOException(String.join(" / ", result.failures()));
		}
		return result.backup();
	}

	/**
	 * チケットIDを書き戻します。
	 * <p>
	 * Excel では解析時と同じシート・テーブルの同じ行へ書き込みます。チケットID列のセルが
	 * 単一セル参照の数式（=WBS!C12 など）の場合は参照先のセルへ書き込み、それ以外の数式のセルは
	 * 上書きせずに failures に記録します。
	 * </p>
	 *
	 * @param filePath 入力ファイルのパス
	 * @param ticketIdColumn チケットID列のヘッダ名
	 * @param rowIssueIds 行番号 → チケットID
	 * @param source Excel の読み込み元（CSV では無視）
	 * @return 結果
	 * @throws IOException 読み書きに失敗した場合
	 */
	public WriteBackResult writeBack(String filePath, String ticketIdColumn, Map<Integer, Long> rowIssueIds,
			ExcelSource source) throws IOException {
		return writeBack(filePath, ticketIdColumn, rowIssueIds, source, true);
	}

	/**
	 * IDを書き戻します（バックアップの作成有無を指定）。
	 * <p>
	 * 1回の実行で複数の表（ユーザー・グループ・チケット）へ書き戻す場合、2回目以降は makeBackup=false にして
	 * 実行前の元ファイルのバックアップを残します。
	 * </p>
	 *
	 * @param filePath 入力ファイルのパス
	 * @param ticketIdColumn ID列のヘッダ名
	 * @param rowIssueIds 行番号 → ID
	 * @param source Excel の読み込み元（CSV では無視）
	 * @param makeBackup {@code <file>.bak} を作成（上書き）する場合 true
	 * @return 結果（makeBackup=false の場合、backup は既存のバックアップのパス）
	 * @throws IOException 読み書きに失敗した場合
	 */
	public WriteBackResult writeBack(String filePath, String ticketIdColumn, Map<Integer, Long> rowIssueIds,
			ExcelSource source, boolean makeBackup) throws IOException {
		Path path = Paths.get(filePath);
		byte[] original = Files.readAllBytes(path);
		List<String> notes = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		byte[] updated;
		if (path.getFileName().toString().toLowerCase().endsWith(".csv")) {
			updated = rewriteCsv(original, ticketIdColumn, rowIssueIds);
		} else {
			updated = rewriteExcel(original, ticketIdColumn, rowIssueIds, source, notes, failures);
		}
		if (updated == null) {
			return new WriteBackResult(null, notes, failures);
		}
		Path backup = path.resolveSibling(path.getFileName().toString() + ".bak");
		if (makeBackup) {
			Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
		}
		Files.write(path, updated);
		return new WriteBackResult(backup, notes, failures);
	}

	private byte[] rewriteExcel(byte[] original, String ticketIdColumn, Map<Integer, Long> rowIssueIds,
			ExcelSource source, List<String> notes, List<String> failures) throws IOException {
		try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(original))) {
			ExcelArea area = ExcelArea.resolve(workbook, source);
			Sheet sheet = area.sheet();
			Row header = area.headerRowIndex() >= 0 ? sheet.getRow(area.headerRowIndex()) : null;
			if (header == null) {
				throw new IOException("ヘッダ行が見つかりません（シート「" + area.sheetName() + "」）");
			}
			int column = area.findColumn(ticketIdColumn);
			if (column < 0) {
				if (area.table() != null) {
					throw new IOException("テーブル「" + area.table().getName() + "」にチケットID列「" + ticketIdColumn
							+ "」がありません");
				}
				column = Math.max(header.getLastCellNum(), 0);
				Cell headerCell = header.createCell(column, CellType.STRING);
				headerCell.setCellValue(ticketIdColumn);
				if (column > 0 && header.getCell(column - 1) != null) {
					headerCell.setCellStyle(header.getCell(column - 1).getCellStyle());
				}
			}
			int written = 0;
			for (Map.Entry<Integer, Long> entry : rowIssueIds.entrySet()) {
				String label = "シート「" + area.sheetName() + "」行" + entry.getKey();
				int rowIndex = entry.getKey() - 1;
				Row row = sheet.getRow(rowIndex);
				if (row == null) {
					row = sheet.createRow(rowIndex);
				}
				Cell cell = row.getCell(column);
				if (cell == null) {
					cell = row.createCell(column);
				}
				double id = entry.getValue().doubleValue();
				if (cell.getCellType() != CellType.FORMULA) {
					cell.setCellValue(id);
					written++;
					continue;
				}
				ExcelArea.Reference target = followReference(cell);
				if (target == null) {
					failures.add(label + ": チケットID列のセルが数式（=" + cell.getCellFormula()
							+ "）のため書き込めません。チケット #" + entry.getValue() + " を手で入力してください");
					continue;
				}
				Row targetRow = target.sheet().getRow(target.row());
				if (targetRow == null) {
					targetRow = target.sheet().createRow(target.row());
				}
				Cell targetCell = targetRow.getCell(target.column());
				if (targetCell == null) {
					targetCell = targetRow.createCell(target.column());
				}
				targetCell.setCellValue(id);
				// 数式は残したまま、保存される計算結果も更新する（再計算しなくても次回の読み込みで ID が読める）
				cell.setCellValue(id);
				workbook.setForceFormulaRecalculation(true);
				notes.add(label + ": チケットID列が参照の数式（=" + cell.getCellFormula() + "）のため、チケット #"
						+ entry.getValue() + " を参照先 " + target.describe() + " に書き込みました");
				written++;
			}
			if (written == 0) {
				return null;
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			workbook.write(out);
			return out.toByteArray();
		}
	}

	/**
	 * 単一セル参照の数式をたどり、値を書き込むセル（数式でないセル）を返します。
	 * 途中に単一セル参照以外の数式がある場合・循環している場合は null。
	 */
	private static ExcelArea.Reference followReference(Cell cell) {
		ExcelArea.Reference reference = ExcelArea.simpleReference(cell);
		for (int hop = 0; reference != null && hop < 10; hop++) {
			Cell next = reference.cell();
			if (next == null || next.getCellType() != CellType.FORMULA) {
				return reference;
			}
			reference = ExcelArea.simpleReference(next);
		}
		return null;
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
