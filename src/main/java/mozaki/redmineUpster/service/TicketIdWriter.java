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
	public record WriteBackResult(Path backup, List<String> notes, List<String> failures, List<String> warnings) {
		/**
		 * 警告なしで作成します。
		 */
		public WriteBackResult(Path backup, List<String> notes, List<String> failures) {
			this(backup, notes, failures, List.of());
		}
	}

	/**
	 * 1行の同期結果（「同期結果」「エラー理由」列に書く値）。
	 *
	 * @param result 同期結果（作成／更新／変更なし／エラー／未実行）
	 * @param error エラー理由（成功時は空。空ならセルを空にして前回のエラーを消す）
	 */
	public record RowResult(String result, String error) {
		/**
		 * 値を正規化して生成します（null は空文字）。
		 */
		public RowResult {
			result = result == null ? "" : result;
			error = error == null ? "" : error;
		}
	}

	/**
	 * 同期結果の書き戻し内容。
	 *
	 * @param lastSyncedAtColumn 最終同期日時の列名
	 * @param resultColumn 同期結果の列名
	 * @param errorColumn エラー理由の列名
	 * @param syncedAt 最終同期日時（文字列として書く。全行同じ値）
	 * @param rows 行番号 → 結果
	 */
	public record SyncResults(String lastSyncedAtColumn, String resultColumn, String errorColumn, String syncedAt,
			Map<Integer, RowResult> rows) {

		/**
		 * 列名（最終同期日時・同期結果・エラー理由の順）。
		 *
		 * @return 列名
		 */
		public List<String> columns() {
			return List.of(lastSyncedAtColumn, resultColumn, errorColumn);
		}

		private String[] values(RowResult row) {
			return new String[] { syncedAt, row.result(), truncate(row.error()) };
		}
	}

	/** エラー理由の最大文字数（Redmine のエラー応答が長い場合に切り詰める。Excel のセルは 32767 文字まで） */
	static final int MAX_ERROR_LENGTH = 1000;

	private static String truncate(String value) {
		String oneLine = value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim();
		return oneLine.length() <= MAX_ERROR_LENGTH ? oneLine : oneLine.substring(0, MAX_ERROR_LENGTH) + "…";
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
		return writeBack(filePath, ticketIdColumn, rowIssueIds, source, makeBackup, null);
	}

	/**
	 * 新規作成したIDと同期結果（最終同期日時・同期結果・エラー理由）を1回で書き戻します（.bak も1回）。
	 * <p>
	 * 同期結果の列がなければヘッダの末尾に追加します（Excel のテーブルはテーブルを右に1列ずつ広げる。
	 * テーブルのすぐ右の列が使われている場合は追加せず warnings に記録）。同期結果の列のセルが数式の場合は
	 * 上書きせず warnings に記録します。
	 * </p>
	 *
	 * @param filePath 入力ファイルのパス
	 * @param ticketIdColumn ID列のヘッダ名
	 * @param rowIssueIds 行番号 → ID（空なら ID は書かない）
	 * @param source Excel の読み込み元（CSV では無視）
	 * @param makeBackup {@code <file>.bak} を作成（上書き）する場合 true
	 * @param results 同期結果（null なら書かない）
	 * @return 結果（何も書かなかった場合 backup は null）
	 * @throws IOException 読み書きに失敗した場合
	 */
	public WriteBackResult writeBack(String filePath, String ticketIdColumn, Map<Integer, Long> rowIssueIds,
			ExcelSource source, boolean makeBackup, SyncResults results) throws IOException {
		Path path = Paths.get(filePath);
		byte[] original = Files.readAllBytes(path);
		List<String> notes = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		Map<Integer, Long> ids = rowIssueIds == null ? Map.of() : rowIssueIds;
		SyncResults syncResults = results != null && !results.rows().isEmpty() ? results : null;
		if (ids.isEmpty() && syncResults == null) {
			return new WriteBackResult(null, notes, failures, warnings);
		}
		byte[] updated;
		if (path.getFileName().toString().toLowerCase().endsWith(".csv")) {
			updated = rewriteCsv(original, ticketIdColumn, ids, syncResults);
		} else {
			updated = rewriteExcel(original, ticketIdColumn, ids, source, notes, failures, syncResults, warnings);
		}
		if (updated == null) {
			return new WriteBackResult(null, notes, failures, warnings);
		}
		Path backup = path.resolveSibling(path.getFileName().toString() + ".bak");
		if (makeBackup) {
			Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
		}
		Files.write(path, updated);
		return new WriteBackResult(backup, notes, failures, warnings);
	}

	private byte[] rewriteExcel(byte[] original, String ticketIdColumn, Map<Integer, Long> rowIssueIds,
			ExcelSource source, List<String> notes, List<String> failures, SyncResults results,
			List<String> warnings) throws IOException {
		try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(original))) {
			ExcelArea area = ExcelArea.resolve(workbook, source);
			Sheet sheet = area.sheet();
			Row header = area.headerRowIndex() >= 0 ? sheet.getRow(area.headerRowIndex()) : null;
			if (header == null) {
				throw new IOException("ヘッダ行が見つかりません（シート「" + area.sheetName() + "」）");
			}
			int written = 0;
			if (!rowIssueIds.isEmpty()) {
				written += writeExcelIds(workbook, area, header, ticketIdColumn, rowIssueIds, notes, failures);
			}
			if (results != null) {
				written += writeExcelResults(area, header, results, warnings);
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
	 * 同期結果の列（なければ追加）に値を書き込みます。
	 *
	 * @return 書き込んだセルの数
	 */
	private static int writeExcelResults(ExcelArea area, Row header, SyncResults results, List<String> warnings) {
		Sheet sheet = area.sheet();
		List<String> names = results.columns();
		int[] columns = new int[names.size()];
		for (int i = 0; i < names.size(); i++) {
			columns[i] = area.findColumn(names.get(i));
		}
		int tableLastColumn = area.table() != null ? area.table().getEndCellReference().getCol() : -1;
		int written = 0;
		boolean tableBlocked = false;
		for (int i = 0; i < names.size(); i++) {
			if (columns[i] >= 0) {
				continue;
			}
			if (area.table() != null) {
				if (tableBlocked) {
					continue;
				}
				columns[i] = appendTableColumn(area, tableLastColumn + 1, names.get(i), warnings);
				if (columns[i] >= 0) {
					tableLastColumn = columns[i];
					written++;
				} else {
					tableBlocked = true;
				}
			} else {
				int column = Math.max(header.getLastCellNum(), 0);
				createHeaderCell(header, column, names.get(i));
				columns[i] = column;
				written++;
			}
		}
		int[] formulaRows = new int[names.size()];
		for (Map.Entry<Integer, RowResult> entry : results.rows().entrySet()) {
			int rowIndex = entry.getKey() - 1;
			if (rowIndex <= area.headerRowIndex()) {
				continue;
			}
			Row row = sheet.getRow(rowIndex);
			if (row == null) {
				row = sheet.createRow(rowIndex);
			}
			String[] values = results.values(entry.getValue());
			for (int i = 0; i < names.size(); i++) {
				if (columns[i] < 0) {
					continue;
				}
				Cell cell = row.getCell(columns[i]);
				if (cell != null && cell.getCellType() == CellType.FORMULA) {
					formulaRows[i]++;
					continue;
				}
				if (values[i].isEmpty()) {
					if (cell != null && cell.getCellType() != CellType.BLANK) {
						cell.setBlank();
						written++;
					}
					continue;
				}
				if (cell == null) {
					cell = row.createCell(columns[i]);
				}
				cell.setCellValue(values[i]);
				written++;
			}
		}
		for (int i = 0; i < names.size(); i++) {
			if (formulaRows[i] > 0) {
				warnings.add("シート「" + area.sheetName() + "」の「" + names.get(i) + "」列は数式のセルのため、"
						+ formulaRows[i] + "行に書き込みませんでした（同期結果の列には数式を入れないでください）");
			}
		}
		return written;
	}

	/**
	 * テーブルの右に列を追加します（テーブルの範囲・オートフィルタも広げる）。
	 *
	 * @return 追加した列（追加できない場合は -1）
	 */
	private static int appendTableColumn(ExcelArea area, int column, String name, List<String> warnings) {
		org.apache.poi.xssf.usermodel.XSSFTable table = area.table();
		Sheet sheet = area.sheet();
		int firstRow = table.getStartCellReference().getRow();
		int lastRow = table.getEndCellReference().getRow();
		for (int r = firstRow; r <= lastRow; r++) {
			Row row = sheet.getRow(r);
			Cell cell = row == null ? null : row.getCell(column);
			if (cell != null && (cell.getCellType() == CellType.FORMULA
					|| !SpreadsheetParser.getCellString(cell).isBlank())) {
				warnings.add("テーブル「" + table.getName() + "」に「" + name + "」列がなく、すぐ右の列（"
						+ org.apache.poi.ss.util.CellReference.convertNumToColString(column)
						+ "列）が使われているため追加できません。テーブルに「" + name + "」列を追加してください");
				return -1;
			}
		}
		for (org.apache.poi.xssf.usermodel.XSSFTable other : ((org.apache.poi.xssf.usermodel.XSSFSheet) sheet)
				.getTables()) {
			if (other != table && other.getStartCellReference().getCol() <= column
					&& other.getEndCellReference().getCol() >= column
					&& other.getStartCellReference().getRow() <= lastRow
					&& other.getEndCellReference().getRow() >= firstRow) {
				warnings.add("テーブル「" + table.getName() + "」のすぐ右にテーブル「" + other.getName() + "」があるため、「"
						+ name + "」列を追加できません。テーブルに「" + name + "」列を追加してください");
				return -1;
			}
		}
		Row header = sheet.getRow(firstRow);
		createHeaderCell(header, column, name);
		table.createColumn(name);
		return column;
	}

	private static void createHeaderCell(Row header, int column, String name) {
		Cell headerCell = header.createCell(column, CellType.STRING);
		headerCell.setCellValue(name);
		if (column > 0 && header.getCell(column - 1) != null) {
			headerCell.setCellStyle(header.getCell(column - 1).getCellStyle());
		}
	}

	/**
	 * チケットIDを書き込みます。
	 *
	 * @return 書き込んだ行の数
	 */
	private static int writeExcelIds(Workbook workbook, ExcelArea area, Row header, String ticketIdColumn,
			Map<Integer, Long> rowIssueIds, List<String> notes, List<String> failures) throws IOException {
		Sheet sheet = area.sheet();
		int column = area.findColumn(ticketIdColumn);
		if (column < 0) {
			if (area.table() != null) {
				throw new IOException("テーブル「" + area.table().getName() + "」にチケットID列「" + ticketIdColumn
						+ "」がありません");
			}
			column = Math.max(header.getLastCellNum(), 0);
			createHeaderCell(header, column, ticketIdColumn);
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
		return written;
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

	private byte[] rewriteCsv(byte[] original, String ticketIdColumn, Map<Integer, Long> rowIssueIds,
			SyncResults results) throws IOException {
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
		if (!rowIssueIds.isEmpty()) {
			int column = csvColumn(records, ticketIdColumn);
			for (Map.Entry<Integer, Long> entry : rowIssueIds.entrySet()) {
				int index = entry.getKey() - 1;
				if (index <= 0 || index >= records.size()) {
					throw new IOException("書き戻し対象の行が見つかりません: " + entry.getKey());
				}
				records.set(index, withValue(records.get(index), column, String.valueOf(entry.getValue())));
			}
		}
		if (results != null) {
			List<String> names = results.columns();
			int[] columns = new int[names.size()];
			for (int i = 0; i < names.size(); i++) {
				columns[i] = csvColumn(records, names.get(i));
			}
			for (Map.Entry<Integer, RowResult> entry : results.rows().entrySet()) {
				int index = entry.getKey() - 1;
				if (index <= 0 || index >= records.size()) {
					continue;
				}
				String[] values = results.values(entry.getValue());
				String[] record = records.get(index);
				for (int i = 0; i < names.size(); i++) {
					record = withValue(record, columns[i], values[i]);
				}
				records.set(index, record);
			}
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

	/**
	 * ヘッダの列を探し、なければヘッダの末尾に追加します。
	 *
	 * @return 列（0始まり）
	 */
	private static int csvColumn(List<String[]> records, String columnName) {
		String[] header = records.get(0);
		for (int i = 0; i < header.length; i++) {
			String name = header[i] == null ? "" : header[i];
			if (i == 0 && !name.isEmpty() && name.charAt(0) == '\uFEFF') {
				name = name.substring(1);
			}
			if (columnName.equals(name.trim())) {
				return i;
			}
		}
		int column = header.length;
		records.set(0, withValue(header, column, columnName));
		return column;
	}

	private static String[] withValue(String[] record, int column, String value) {
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
