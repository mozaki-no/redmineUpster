package mozaki.redmineUpster.samples;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbookType;

/**
 * 配布用サンプル {@code samples/sample-wbs.xlsx} / {@code samples/sample-wbs.xlsm} を作成するジェネレータ（テスト用コード）。
 * <p>
 * 1枚目「WBS」は人が見る表（タイトル行・セル結合・色・ツールが使わない列あり）、
 * 2枚目「取込」はツールが読むテーブル「取込表」で、各セルが「WBS」シートを参照する数式です。
 * チケットID列は単一セル参照（='WBS'!$B$5 など）なので、新規作成したチケット番号は「WBS」シートへ書き戻されます。
 * 保存前に数式を計算して計算結果を保存し、Excel で開いたときにも再計算されるようにしています。
 * </p>
 * <p>
 * 再作成: {@code mvn -q test-compile} の後、テストのクラスパスで
 * {@code java mozaki.redmineUpster.samples.SampleWorkbookGenerator samples} を実行します
 * （引数は出力先フォルダ。省略時は samples）。
 * </p>
 */
public final class SampleWorkbookGenerator {

	/** 取込テーブル名 */
	public static final String TABLE_NAME = "取込表";
	/** WBS シート名 */
	public static final String WBS_SHEET = "WBS";
	/** 取込シート名 */
	public static final String IMPORT_SHEET = "取込";

	/** WBS シートの見出し（列順） */
	private static final List<String> WBS_HEADERS = List.of("No", "チケットID", "トラッカー", "大分類", "中分類", "小分類",
			"成果物", "タスク", "担当", "着手予定", "完了予定", "ステータス", "進捗率", "工数(人日)", "備考");
	private static final int COL_ID = 1;
	private static final int COL_TRACKER = 2;
	private static final int COL_L1 = 3;
	private static final int COL_TASK = 7;
	private static final int COL_START = 9;
	private static final int COL_DUE = 10;
	private static final int COL_STATUS = 11;
	private static final int COL_PROGRESS = 12;

	/** WBS の見出し行（0始まり。表示は4行目） */
	private static final int WBS_HEADER_ROW = 3;
	/** 取込テーブルの見出し行（0始まり。表示は3行目） */
	private static final int IMPORT_HEADER_ROW = 2;

	/** 取込テーブルの列（見出し名, WBS の列）。進捗率だけは % → 数値へ変換する数式 */
	private static final List<String> IMPORT_HEADERS = List.of("チケットID", "トラッカー", "大分類", "中分類", "小分類",
			"成果物", "タスク", "着手予定", "完了予定", "ステータス", "進捗率");
	private static final int[] IMPORT_SOURCE_COLUMNS = { COL_ID, COL_TRACKER, 3, 4, 5, 6, COL_TASK, COL_START, COL_DUE,
			COL_STATUS, COL_PROGRESS };

	/**
	 * WBS の1行。null は空欄。separator は取込対象外の区切り行（見た目用）。
	 */
	private record Line(String tracker, String l1, String l2, String l3, String deliverable, String task,
			String assignee, String start, String due, String status, Double progress, Double effort, String note,
			boolean separator) {
		static Line of(String tracker, String l1, String l2, String l3, String deliverable, String task,
				String assignee, String start, String due, String status, Double progress, Double effort,
				String note) {
			return new Line(tracker, l1, l2, l3, deliverable, task, assignee, start, due, status, progress, effort,
					note, false);
		}

		static Line separator(String note) {
			return new Line(null, null, null, null, null, null, null, null, null, null, null, null, note, true);
		}

		String[] hierarchy() {
			return new String[] { l1, l2, l3, deliverable, task };
		}
	}

	private static final List<Line> LINES = List.of(
			Line.of("サマリ", "要件定義", null, null, null, null, null, "2026-10-01", "2026-10-16", null, null, null,
					"大分類の行（親チケット）"),
			Line.of("サマリ", "要件定義", "業務要件", null, null, null, null, "2026-10-01", "2026-10-14", null, null, null,
					null),
			Line.of("タスク", "要件定義", "業務要件", null, null, "現行業務ヒアリング", "田中", "2026-10-01", "2026-10-07", "完了",
					1.0, 3.0, "中分類の直下のタスク（小分類・成果物を飛ばす）"),
			Line.of("タスク", "要件定義", "業務要件", null, null, "業務フロー作成", "田中", "2026-10-06", "2026-10-14", "進行中",
					0.5, 4.0, null),
			Line.of("タスク", "要件定義", null, null, null, "要件定義書レビュー", "山田", "2026-10-15", "2026-10-16", "未着手", 0.0,
					1.0, "大分類の直下のタスク（中分類以下を飛ばす）"),
			Line.separator("―― ここから基本設計（この行は取込対象外） ――"),
			Line.of("サマリ", "基本設計", null, null, null, null, null, "2026-10-19", "2026-11-13", null, null, null,
					null),
			Line.of("サマリ", "基本設計", "画面設計", null, null, null, null, "2026-10-19", "2026-11-06", null, null, null,
					null),
			Line.of("サマリ", "基本設計", "画面設計", "ログイン画面", null, null, null, "2026-10-19", "2026-10-30", null, null,
					null, null),
			Line.of("サマリ", "基本設計", "画面設計", "ログイン画面", "画面設計書", null, null, "2026-10-19", "2026-10-30", null,
					null, null, "成果物の行"),
			Line.of("タスク", "基本設計", "画面設計", "ログイン画面", "画面設計書", "画面レイアウト作成", "佐藤", "2026-10-19",
					"2026-10-23", "未着手", 0.0, 3.0, null),
			Line.of("タスク", "基本設計", "画面設計", "ログイン画面", "画面設計書", "入力チェック仕様作成", "佐藤", "2026-10-26",
					"2026-10-30", "未着手", 0.0, 3.0, null),
			Line.of("タスク", "基本設計", "画面設計", null, null, "画面一覧作成", "鈴木", "2026-11-02", "2026-11-06", "未着手",
					0.0, 2.0, "中分類の直下のタスク"),
			Line.of("サマリ", "基本設計", "DB設計", null, null, null, null, "2026-10-26", "2026-11-11", null, null, null,
					null),
			Line.of("タスク", "基本設計", "DB設計", null, null, "テーブル定義作成", "鈴木", "2026-10-26", "2026-11-11", "未着手",
					0.0, 5.0, null),
			Line.of("タスク", "基本設計", null, null, null, "基本設計書レビュー", "山田", "2026-11-12", "2026-11-13", "未着手",
					0.0, 1.0, "大分類の直下のタスク"));

	private SampleWorkbookGenerator() {
	}

	/**
	 * サンプルを作成します。
	 *
	 * @param args 出力先フォルダ（省略時は samples）
	 * @throws IOException 書き込みに失敗した場合
	 */
	public static void main(String[] args) throws IOException {
		Path dir = Paths.get(args.length > 0 ? args[0] : "samples");
		Files.createDirectories(dir);
		write(dir.resolve("sample-wbs.xlsx"), XSSFWorkbookType.XLSX);
		write(dir.resolve("sample-wbs.xlsm"), XSSFWorkbookType.XLSM);
		System.out.println("Created: " + dir.resolve("sample-wbs.xlsx") + ", " + dir.resolve("sample-wbs.xlsm"));
	}

	/**
	 * サンプルのワークブックを書き込みます。
	 *
	 * @param file 出力先
	 * @param type XLSX または XLSM（マクロなし）
	 * @throws IOException 書き込みに失敗した場合
	 */
	public static void write(Path file, XSSFWorkbookType type) throws IOException {
		try (XSSFWorkbook wb = build(type); OutputStream out = Files.newOutputStream(file)) {
			wb.write(out);
		}
	}

	/**
	 * サンプルのワークブックを作成します（数式は計算済み）。
	 *
	 * @param type XLSX または XLSM（マクロなし）
	 * @return ワークブック
	 */
	public static XSSFWorkbook build(XSSFWorkbookType type) {
		XSSFWorkbook wb = new XSSFWorkbook(type);
		Styles styles = new Styles(wb);
		XSSFSheet wbs = wb.createSheet(WBS_SHEET);
		XSSFSheet imp = wb.createSheet(IMPORT_SHEET);
		int[] wbsRows = buildWbs(wbs, styles);
		buildImport(wb, imp, styles, wbsRows);
		wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
		wb.setForceFormulaRecalculation(true);
		wb.setActiveSheet(0);
		return wb;
	}

	/** @return 取込対象の各行の WBS 行番号（0始まり） */
	private static int[] buildWbs(XSSFSheet sheet, Styles styles) {
		int lastColumn = WBS_HEADERS.size() - 1;
		Row title = sheet.createRow(0);
		title.setHeightInPoints(24);
		cell(title, 0, "サンプルシステム刷新プロジェクト WBS", styles.title);
		sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, lastColumn));
		cell(sheet.createRow(1), 0,
				"更新日: 2026/9/28　※人が見る・編集する表です。Redmine への取込は「取込」シートのテーブル「取込表」がこの表を参照して行います。",
				styles.memo);
		sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, lastColumn));

		Row header = sheet.createRow(WBS_HEADER_ROW);
		header.setHeightInPoints(20);
		for (int c = 0; c < WBS_HEADERS.size(); c++) {
			boolean used = c >= COL_ID && c <= COL_PROGRESS && c != 8;
			cell(header, c, WBS_HEADERS.get(c), used ? styles.header : styles.headerExtra);
		}

		int[] importRows = new int[(int) LINES.stream().filter(l -> !l.separator()).count()];
		int rowIndex = WBS_HEADER_ROW + 1;
		int no = 0;
		for (Line line : LINES) {
			Row row = sheet.createRow(rowIndex);
			if (line.separator()) {
				cell(row, 0, line.note(), styles.separator);
				for (int c = 1; c <= lastColumn; c++) {
					row.createCell(c).setCellStyle(styles.separator);
				}
				sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, lastColumn));
				rowIndex++;
				continue;
			}
			importRows[no] = rowIndex;
			no++;
			boolean summary = "サマリ".equals(line.tracker());
			CellStyle text = summary ? styles.summaryText : styles.text;
			number(row, 0, no, summary ? styles.summaryCenter : styles.center);
			row.createCell(COL_ID).setCellStyle(summary ? styles.summaryId : styles.id);
			cell(row, COL_TRACKER, line.tracker(), summary ? styles.summaryCenter : styles.center);
			String[] hierarchy = line.hierarchy();
			for (int i = 0; i < hierarchy.length; i++) {
				cell(row, COL_L1 + i, hierarchy[i], i < 2 ? styles.merged : text);
			}
			cell(row, 8, line.assignee(), text);
			date(row, COL_START, line.start(), summary ? styles.summaryDate : styles.date);
			date(row, COL_DUE, line.due(), summary ? styles.summaryDate : styles.date);
			cell(row, COL_STATUS, line.status(), summary ? styles.summaryCenter : styles.center);
			Cell progress = row.createCell(COL_PROGRESS);
			progress.setCellStyle(summary ? styles.summaryPercent : styles.percent);
			if (line.progress() != null) {
				progress.setCellValue(line.progress());
			}
			Cell effort = row.createCell(13);
			effort.setCellStyle(summary ? styles.summaryDecimal : styles.decimal);
			if (line.effort() != null) {
				effort.setCellValue(line.effort());
			}
			cell(row, 14, line.note(), text);
			rowIndex++;
		}
		int lastDataRow = rowIndex - 1;

		// 大分類・中分類は同じ値が続く範囲を縦に結合する（人が見やすいように）
		mergeRuns(sheet, COL_L1, WBS_HEADER_ROW + 1, lastDataRow);
		mergeRuns(sheet, COL_L1 + 1, WBS_HEADER_ROW + 1, lastDataRow);

		int[] widths = { 5, 10, 9, 12, 12, 13, 12, 20, 8, 11, 11, 9, 8, 10, 40 };
		for (int c = 0; c < widths.length; c++) {
			sheet.setColumnWidth(c, widths[c] * 256 + 200);
		}
		sheet.createFreezePane(3, WBS_HEADER_ROW + 1);
		return importRows;
	}

	/** 同じ値が連続する（空欄・区切り行で途切れる）範囲を縦に結合します。 */
	private static void mergeRuns(XSSFSheet sheet, int column, int firstRow, int lastRow) {
		int start = -1;
		String current = null;
		for (int r = firstRow; r <= lastRow + 1; r++) {
			String value = r <= lastRow ? stringAt(sheet, r, column) : null;
			if (value != null && value.equals(current)) {
				// 空欄にして結合（Excel は結合範囲の先頭セルの値だけを持つ）
				sheet.getRow(r).getCell(column).setBlank();
				continue;
			}
			if (current != null && r - 1 > start) {
				sheet.addMergedRegion(new CellRangeAddress(start, r - 1, column, column));
			}
			start = r;
			current = value;
		}
	}

	private static String stringAt(XSSFSheet sheet, int row, int column) {
		Row r = sheet.getRow(row);
		Cell c = r == null ? null : r.getCell(column);
		if (c == null || c.getCellType() != org.apache.poi.ss.usermodel.CellType.STRING) {
			return null;
		}
		String v = c.getStringCellValue();
		return v.isBlank() ? null : v;
	}

	private static void buildImport(XSSFWorkbook wb, XSSFSheet sheet, Styles styles, int[] wbsRows) {
		cell(sheet.createRow(0), 0,
				"※このシートは Redmine 取込用です（テーブル「取込表」）。各セルは「WBS」シートを参照する数式なので、直接入力せず WBS シートを編集してください。",
				styles.memo);
		Row header = sheet.createRow(IMPORT_HEADER_ROW);
		for (int c = 0; c < IMPORT_HEADERS.size(); c++) {
			header.createCell(c).setCellValue(IMPORT_HEADERS.get(c));
		}
		for (int i = 0; i < wbsRows.length; i++) {
			Row row = sheet.createRow(IMPORT_HEADER_ROW + 1 + i);
			int wbsRow = wbsRows[i];
			for (int c = 0; c < IMPORT_HEADERS.size(); c++) {
				int source = IMPORT_SOURCE_COLUMNS[c];
				// 結合セルは結合範囲の先頭セルを参照する（結合範囲の2行目以降のセルは空欄のため）
				String ref = WBS_SHEET + "!" + new CellReference(topOfMerge(wb.getSheet(WBS_SHEET), wbsRow, source),
						source, true, true).formatAsString();
				Cell cell = row.createCell(c);
				if (source == COL_PROGRESS) {
					// 進捗率は WBS の 50%（=0.5）を Redmine の 50 に変換する
					cell.setCellFormula("IF(" + ref + "=\"\",\"\",ROUND(" + ref + "*100,0))");
					cell.setCellStyle(styles.plain);
				} else if (source == COL_START || source == COL_DUE) {
					cell.setCellFormula(ref);
					cell.setCellStyle(styles.importDate);
				} else {
					// チケットID・階層などは単一セル参照（参照先が空欄なら 0 と表示されないよう書式で隠す）
					cell.setCellFormula(ref);
					cell.setCellStyle(styles.hideZero);
				}
			}
		}
		int lastRow = IMPORT_HEADER_ROW + wbsRows.length;
		AreaReference area = new AreaReference(new CellReference(IMPORT_HEADER_ROW, 0),
				new CellReference(lastRow, IMPORT_HEADERS.size() - 1), SpreadsheetVersion.EXCEL2007);
		XSSFTable table = sheet.createTable(area);
		table.setName(TABLE_NAME);
		table.setDisplayName(TABLE_NAME);
		table.setStyleName("TableStyleMedium2");
		table.getCTTable().getTableStyleInfo().setShowRowStripes(true);
		if (!table.getCTTable().isSetAutoFilter()) {
			table.getCTTable().addNewAutoFilter().setRef(area.formatAsString());
		}
		table.updateHeaders();
		int[] widths = { 10, 9, 12, 12, 13, 12, 20, 11, 11, 9, 8 };
		for (int c = 0; c < widths.length; c++) {
			sheet.setColumnWidth(c, widths[c] * 256 + 200);
		}
		sheet.createFreezePane(0, IMPORT_HEADER_ROW + 1);
	}

	private static int topOfMerge(XSSFSheet sheet, int row, int column) {
		for (CellRangeAddress region : sheet.getMergedRegions()) {
			if (region.isInRange(row, column)) {
				return region.getFirstRow();
			}
		}
		return row;
	}

	private static void cell(Row row, int column, String value, CellStyle style) {
		Cell cell = row.createCell(column);
		cell.setCellStyle(style);
		if (value != null) {
			cell.setCellValue(value);
		}
	}

	private static void number(Row row, int column, double value, CellStyle style) {
		Cell cell = row.createCell(column);
		cell.setCellStyle(style);
		cell.setCellValue(value);
	}

	private static void date(Row row, int column, String iso, CellStyle style) {
		Cell cell = row.createCell(column);
		cell.setCellStyle(style);
		if (iso != null) {
			cell.setCellValue(LocalDate.parse(iso));
		}
	}

	/** セルの書式 */
	private static final class Styles {
		final CellStyle title;
		final CellStyle memo;
		final CellStyle header;
		final CellStyle headerExtra;
		final CellStyle separator;
		final CellStyle text;
		final CellStyle center;
		final CellStyle id;
		final CellStyle date;
		final CellStyle percent;
		final CellStyle decimal;
		final CellStyle merged;
		final CellStyle summaryText;
		final CellStyle summaryCenter;
		final CellStyle summaryId;
		final CellStyle summaryDate;
		final CellStyle summaryPercent;
		final CellStyle summaryDecimal;
		final CellStyle plain;
		final CellStyle hideZero;
		final CellStyle importDate;

		Styles(XSSFWorkbook wb) {
			short dateFormat = wb.createDataFormat().getFormat("yyyy/m/d");
			short percentFormat = wb.createDataFormat().getFormat("0%");
			short decimalFormat = wb.createDataFormat().getFormat("0.0");
			short idFormat = wb.createDataFormat().getFormat("0");

			Font titleFont = wb.createFont();
			titleFont.setBold(true);
			titleFont.setFontHeightInPoints((short) 14);
			title = wb.createCellStyle();
			title.setFont(titleFont);

			Font memoFont = wb.createFont();
			memoFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
			memoFont.setFontHeightInPoints((short) 9);
			memo = wb.createCellStyle();
			memo.setFont(memoFont);

			Font headerFont = wb.createFont();
			headerFont.setBold(true);
			headerFont.setColor(IndexedColors.WHITE.getIndex());
			header = bordered(wb);
			header.setFont(headerFont);
			fill(header, IndexedColors.DARK_TEAL);
			header.setAlignment(HorizontalAlignment.CENTER);
			header.setVerticalAlignment(VerticalAlignment.CENTER);
			headerExtra = bordered(wb);
			headerExtra.cloneStyleFrom(header);
			fill(headerExtra, IndexedColors.GREY_50_PERCENT);

			Font separatorFont = wb.createFont();
			separatorFont.setItalic(true);
			separatorFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
			separator = wb.createCellStyle();
			separator.setFont(separatorFont);
			fill(separator, IndexedColors.GREY_25_PERCENT);

			text = bordered(wb);
			text.setVerticalAlignment(VerticalAlignment.CENTER);
			center = bordered(wb);
			center.cloneStyleFrom(text);
			center.setAlignment(HorizontalAlignment.CENTER);
			id = bordered(wb);
			id.cloneStyleFrom(center);
			id.setDataFormat(idFormat);
			fill(id, IndexedColors.LIGHT_YELLOW);
			date = bordered(wb);
			date.cloneStyleFrom(center);
			date.setDataFormat(dateFormat);
			percent = bordered(wb);
			percent.cloneStyleFrom(center);
			percent.setDataFormat(percentFormat);
			decimal = bordered(wb);
			decimal.cloneStyleFrom(center);
			decimal.setDataFormat(decimalFormat);
			merged = bordered(wb);
			merged.cloneStyleFrom(text);
			merged.setVerticalAlignment(VerticalAlignment.TOP);
			fill(merged, IndexedColors.LIGHT_TURQUOISE);

			Font boldFont = wb.createFont();
			boldFont.setBold(true);
			summaryText = summary(wb, text, boldFont);
			summaryCenter = summary(wb, center, boldFont);
			summaryId = summary(wb, id, boldFont);
			fill(summaryId, IndexedColors.LIGHT_YELLOW);
			summaryDate = summary(wb, date, boldFont);
			summaryPercent = summary(wb, percent, boldFont);
			summaryDecimal = summary(wb, decimal, boldFont);

			plain = wb.createCellStyle();
			hideZero = wb.createCellStyle();
			hideZero.setDataFormat(wb.createDataFormat().getFormat("0;-0;;@"));
			importDate = wb.createCellStyle();
			importDate.setDataFormat(wb.createDataFormat().getFormat("yyyy/m/d;;"));
		}

		private static CellStyle summary(XSSFWorkbook wb, CellStyle base, Font bold) {
			CellStyle style = wb.createCellStyle();
			style.cloneStyleFrom(base);
			style.setFont(bold);
			fill(style, IndexedColors.PALE_BLUE);
			return style;
		}

		private static XSSFCellStyle bordered(XSSFWorkbook wb) {
			XSSFCellStyle style = wb.createCellStyle();
			style.setBorderTop(BorderStyle.THIN);
			style.setBorderBottom(BorderStyle.THIN);
			style.setBorderLeft(BorderStyle.THIN);
			style.setBorderRight(BorderStyle.THIN);
			return style;
		}

		private static void fill(CellStyle style, IndexedColors color) {
			style.setFillForegroundColor(color.getIndex());
			style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		}
	}
}
