package mozaki.redmineUpster.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * 全内容のバックアップの1つの表（シート）を書き出すクラス。
 * <p>
 * 1行目に見出しを書き、行を追加するたびに書き出します（SXSSF。メモリに溜めない）。
 * Excel の行数の上限を超える場合は「名前(2)」「名前(3)」… のシートに続けます（各シートに見出し）。
 * セルの文字数の上限（32,767）を超える値は、先頭だけをセルに書き、全文を {@code long_text} フォルダの
 * テキストファイル（UTF-8）に書いて、そのパスをセルの末尾に書きます。
 * </p>
 */
final class BackupSheet {

    /** Excel の1シートの行数の上限（見出しを除くデータ行の数） */
    static final int EXCEL_MAX_DATA_ROWS = 1_048_575;
    /** Excel の1セルの文字数の上限 */
    static final int EXCEL_MAX_CELL_CHARS = 32_767;

    /** 書き出し先とセルの書式（シート間で共有） */
    record Context(SXSSFWorkbook workbook, CellStyle header, CellStyle date, CellStyle dateTime, Path longTextDir,
            String longTextPrefix, int maxDataRows) {
    }

    private final Context context;
    private final String name;
    private final List<String> headers;
    private final int[] widths;
    private SXSSFSheet sheet;
    private int part;
    private int rowInSheet;
    private long totalRows;
    private int longTexts;

    BackupSheet(Context context, String name, List<String> headers, int... widths) {
        this.context = context;
        this.name = name;
        this.headers = List.copyOf(headers);
        this.widths = widths;
        newPart();
    }

    private void newPart() {
        part++;
        String sheetName = part == 1 ? name : name + "(" + part + ")";
        sheet = context.workbook().createSheet(sheetName);
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(context.header());
            int width = i < widths.length ? widths[i] : 14;
            sheet.setColumnWidth(i, Math.min(255, width) * 256);
        }
        sheet.createFreezePane(0, 1);
        rowInSheet = 0;
    }

    /**
     * 1行を追加します。値は String・Number・Boolean・LocalDate・LocalDateTime（null は空欄）。
     *
     * @param values 見出しの順の値
     * @throws IOException 長い文字列のテキストファイルを書けない場合
     */
    void addRow(Object... values) throws IOException {
        if (rowInSheet >= context.maxDataRows()) {
            newPart();
        }
        rowInSheet++;
        totalRows++;
        Row row = sheet.createRow(rowInSheet);
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(i);
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else if (value instanceof Boolean bool) {
                cell.setCellValue(bool);
            } else if (value instanceof LocalDate date) {
                cell.setCellValue(date);
                cell.setCellStyle(context.date());
            } else if (value instanceof LocalDateTime dateTime) {
                cell.setCellValue(dateTime);
                cell.setCellStyle(context.dateTime());
            } else {
                String text = value.toString();
                if (text.isEmpty()) {
                    continue;
                }
                cell.setCellValue(text.length() > EXCEL_MAX_CELL_CHARS ? overflow(text, i) : text);
            }
        }
    }

    /**
     * 上限を超える文字列の全文をテキストファイルに書き、セルに書く文字列（先頭＋全文のパス）を返します。
     */
    private String overflow(String text, int column) throws IOException {
        longTexts++;
        String header = column < headers.size() ? headers.get(column) : "col" + (column + 1);
        String fileName = BackupNames.portable(context.longTextDir(),
                BackupNames.sanitize(sheet.getSheetName() + "_" + (rowInSheet + 1) + "行_" + header) + ".txt",
                "sheet" + (context.workbook().getSheetIndex(sheet) + 1) + "_row" + (rowInSheet + 1) + "_col"
                        + (column + 1) + ".txt");
        Files.createDirectories(context.longTextDir());
        Files.writeString(context.longTextDir().resolve(fileName), text, StandardCharsets.UTF_8);
        String note = "\n…（" + text.length() + " 文字のうち先頭だけ。全文: " + context.longTextPrefix() + fileName + "）";
        int keep = EXCEL_MAX_CELL_CHARS - note.length();
        if (Character.isHighSurrogate(text.charAt(keep - 1))) {
            keep--;
        }
        return text.substring(0, keep) + note;
    }

    /** 書いたデータ行の数（全シートの合計） */
    long totalRows() {
        return totalRows;
    }

    /** シートの数（行数の上限で分けた数） */
    int parts() {
        return part;
    }

    /** 全文をテキストファイルに書いたセルの数 */
    int longTexts() {
        return longTexts;
    }

    String name() {
        return name;
    }
}
