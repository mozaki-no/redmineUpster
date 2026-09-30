package mozaki.redmineUpster.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Excel の読み込み範囲（シート・ヘッダ行・列範囲・最終行）。
 * <p>
 * 解析（{@link SpreadsheetParser}）と書き戻し（{@link TicketIdWriter}）で同じ範囲を使うための共通処理です。
 * </p>
 *
 * @param sheet シート
 * @param headerRowIndex ヘッダ行（0始まり。見つからなければ -1）
 * @param firstColumn 先頭列（0始まり）
 * @param lastColumn 最終列（0始まり、両端含む。-1 ならヘッダ行の最後のセルまで）
 * @param lastRowIndex 最終データ行（0始まり、両端含む。-1 ならシートの最終行まで）
 * @param table テーブル（テーブル指定でなければ null）
 */
public record ExcelArea(Sheet sheet, int headerRowIndex, int firstColumn, int lastColumn, int lastRowIndex,
		XSSFTable table) {

	/**
	 * 指定に従って読み込み範囲を決定します。
	 *
	 * @param workbook ワークブック
	 * @param source シート・テーブルの指定
	 * @return 読み込み範囲
	 * @throws IOException シート・テーブルが見つからない場合
	 */
	public static ExcelArea resolve(Workbook workbook, ExcelSource source) throws IOException {
		ExcelSource src = source != null ? source : ExcelSource.DEFAULT;
		if (src.table() != null) {
			return resolveTable(workbook, src.table());
		}
		Sheet sheet = src.sheet() != null ? resolveSheet(workbook, src.sheet()) : workbook.getSheetAt(0);
		return new ExcelArea(sheet, findHeaderRow(sheet), 0, -1, -1, null);
	}

	/**
	 * シート名（ログ・メッセージ用）。
	 *
	 * @return シート名
	 */
	public String sheetName() {
		return sheet.getSheetName();
	}

	/**
	 * 実際の最終列（0始まり、両端含む）。
	 *
	 * @return 最終列
	 */
	public int effectiveLastColumn() {
		if (lastColumn >= 0) {
			return lastColumn;
		}
		Row header = headerRowIndex >= 0 ? sheet.getRow(headerRowIndex) : null;
		return header == null ? -1 : header.getLastCellNum() - 1;
	}

	/**
	 * 実際の最終データ行（0始まり、両端含む）。
	 *
	 * @return 最終データ行
	 */
	public int effectiveLastRow() {
		return lastRowIndex >= 0 ? lastRowIndex : sheet.getLastRowNum();
	}

	/**
	 * ヘッダ名の列（0始まり）を返します。
	 *
	 * @param name ヘッダ名
	 * @return 列。見つからなければ -1
	 */
	public int findColumn(String name) {
		Row header = headerRowIndex >= 0 ? sheet.getRow(headerRowIndex) : null;
		if (header == null) {
			return -1;
		}
		for (int c = firstColumn; c <= effectiveLastColumn(); c++) {
			Cell cell = header.getCell(c);
			if (cell != null && name.equals(stripBom(SpreadsheetParser.getCellString(cell)).trim())) {
				return c;
			}
		}
		return -1;
	}

	private static ExcelArea resolveTable(Workbook workbook, String tableName) throws IOException {
		if (!(workbook instanceof XSSFWorkbook xssf)) {
			throw new IOException("テーブル「" + tableName + "」: テーブル指定は .xlsx / .xlsm のみ対応しています");
		}
		List<String> available = new ArrayList<>();
		for (int i = 0; i < xssf.getNumberOfSheets(); i++) {
			XSSFSheet sheet = xssf.getSheetAt(i);
			for (XSSFTable table : sheet.getTables()) {
				available.add(table.getName() + "（シート「" + sheet.getSheetName() + "」）");
				if (tableName.equalsIgnoreCase(table.getName()) || tableName.equalsIgnoreCase(table.getDisplayName())) {
					if (table.getHeaderRowCount() < 1) {
						throw new IOException("テーブル「" + tableName + "」に見出し行がありません"
								+ "（テーブルデザインの「見出し行」をオンにしてください）");
					}
					CellReference start = table.getStartCellReference();
					CellReference end = table.getEndCellReference();
					int lastRow = end.getRow() - Math.max(table.getTotalsRowCount(), 0);
					return new ExcelArea(sheet, start.getRow(), start.getCol(), end.getCol(), lastRow, table);
				}
			}
		}
		throw new IOException("テーブル「" + tableName + "」が見つかりません。ファイル内のテーブル: "
				+ (available.isEmpty() ? "なし" : String.join("、", available)));
	}

	private static Sheet resolveSheet(Workbook workbook, String sheetSpec) throws IOException {
		Sheet byName = workbook.getSheet(sheetSpec);
		if (byName != null) {
			return byName;
		}
		if (sheetSpec.chars().allMatch(Character::isDigit)) {
			int index = Integer.parseInt(sheetSpec);
			if (index >= 1 && index <= workbook.getNumberOfSheets()) {
				return workbook.getSheetAt(index - 1);
			}
		}
		List<String> names = new ArrayList<>();
		for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
			names.add((i + 1) + ":" + workbook.getSheetName(i));
		}
		throw new IOException("シート「" + sheetSpec + "」が見つかりません。ファイル内のシート: " + String.join("、", names));
	}

	/**
	 * 値のある最初の行をヘッダ行とします。
	 */
	private static int findHeaderRow(Sheet sheet) {
		DataFormatter formatter = new DataFormatter();
		for (int r = Math.max(sheet.getFirstRowNum(), 0); r <= sheet.getLastRowNum(); r++) {
			Row row = sheet.getRow(r);
			if (row == null) {
				continue;
			}
			for (Cell cell : row) {
				if (cell.getCellType() == CellType.FORMULA || !formatter.formatCellValue(cell).isBlank()) {
					return r;
				}
			}
		}
		return -1;
	}

	/** 単一セル参照の数式（例: WBS!C12、'WBS 本体'!$C$12、$C$12）。ブック外参照・範囲・関数は対象外 */
	private static final Pattern SIMPLE_REFERENCE = Pattern.compile(
			"^(?:(?:'((?:[^']|'')+)'|([^\\s'!\\[\\]()+\\-*/&,:;=<>\"^]+))!)?(\\$?[A-Za-z]{1,3}\\$?[0-9]+)$");

	/**
	 * 単一セル参照の数式の参照先。
	 *
	 * @param sheet 参照先シート
	 * @param row 行（0始まり）
	 * @param column 列（0始まり）
	 */
	public record Reference(Sheet sheet, int row, int column) {
		/**
		 * 参照先のセル（なければ null）。
		 *
		 * @return セル
		 */
		public Cell cell() {
			Row r = sheet.getRow(row);
			return r == null ? null : r.getCell(column);
		}

		/**
		 * 表示用の参照（例: 'WBS'!C12）。
		 *
		 * @return 参照
		 */
		public String describe() {
			return "'" + sheet.getSheetName() + "'!" + new CellReference(row, column).formatAsString(false);
		}
	}

	/**
	 * 数式が単一セルの参照（=WBS!C12 など）であれば参照先を返します。
	 *
	 * @param cell セル
	 * @return 参照先。数式でない・単一セル参照でない・参照先シートがない場合は null
	 */
	public static Reference simpleReference(Cell cell) {
		if (cell == null || cell.getCellType() != CellType.FORMULA) {
			return null;
		}
		Matcher m = SIMPLE_REFERENCE.matcher(cell.getCellFormula().trim());
		if (!m.matches()) {
			return null;
		}
		String sheetName = m.group(1) != null ? m.group(1).replace("''", "'") : m.group(2);
		Sheet target = sheetName == null ? cell.getSheet() : cell.getSheet().getWorkbook().getSheet(sheetName);
		if (target == null) {
			return null;
		}
		CellReference ref = new CellReference(m.group(3));
		return new Reference(target, ref.getRow(), ref.getCol());
	}

	private static String stripBom(String value) {
		return !value.isEmpty() && value.charAt(0) == '﻿' ? value.substring(1) : value;
	}
}
