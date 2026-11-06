package mozaki.redmineUpster.cli;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 全内容のバックアップのファイル名（{@code <プロジェクト識別子>_yyyyMMdd_HHmmss.xlsx}）と、
 * Windows で使えない文字の置き換え。
 */
final class BackupNames {

    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    /** 添付ファイル・長い文字列を置くフォルダの接尾辞（{@code <xlsxの名前>_files}） */
    static final String FILES_SUFFIX = "_files";
    /** 書き出し中の一時ファイル・フォルダの接尾辞 */
    static final String TEMP_SUFFIX = ".tmp";

    private static final Pattern INVALID = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Pattern RESERVED = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");
    /** ファイル名の長さの上限（パス全体の長さ 260 文字の制限に余裕を持たせる） */
    private static final int MAX_NAME_LENGTH = 120;

    private BackupNames() {
    }

    /**
     * バックアップのファイル名の基本部分（拡張子なし）。
     *
     * @param projectIdentifier プロジェクト識別子（設定の redmine.projectId）
     * @param time 実行日時
     * @return 例: {@code myproject_20261106_093000}
     */
    static String baseName(String projectIdentifier, LocalDateTime time) {
        return sanitize(projectIdentifier) + "_" + time.format(TIMESTAMP);
    }

    /**
     * 世代管理の対象になるファイル名（同じプロジェクトの、このツールが付けた名前の .xlsx）の正規表現。
     *
     * @param projectIdentifier プロジェクト識別子
     * @return {@code <識別子>_\d{8}_\d{6}\.xlsx}
     */
    static Pattern generationPattern(String projectIdentifier) {
        return Pattern.compile(Pattern.quote(sanitize(projectIdentifier)) + "_\\d{8}_\\d{6}\\.xlsx");
    }

    /**
     * {@code relative} をこの OS のファイル名にできるか確かめ、できなければ {@code fallback} を返します
     * （Linux の Jenkins などで文字コードが UTF-8 でなく、日本語のファイル名を作れない場合）。
     *
     * @param dir 基準のフォルダ
     * @param relative 使いたい相対パス
     * @param fallback 代わりの相対パス（ASCII のみ）
     * @return 使える相対パス
     */
    static String portable(Path dir, String relative, String fallback) {
        try {
            dir.resolve(relative);
            return relative;
        } catch (InvalidPathException e) {
            return fallback;
        }
    }

    /** ファイル名の拡張子（ASCII の英数字だけの場合。なければ空） */
    static String asciiExtension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String ext = fileName.substring(dot);
        return ext.length() <= 10 && ext.substring(1).matches("[A-Za-z0-9]+") ? ext : "";
    }

    /**
     * Windows のファイル名に使えない文字（{@code \ / : * ? " < > |}・制御文字）を {@code _} にし、
     * 末尾の空白・ピリオドと予約名（CON など）を避け、長すぎる名前を切り詰めます。
     *
     * @param name 元の名前
     * @return ファイル名に使える名前（空なら {@code _}）
     */
    static String sanitize(String name) {
        String text = name == null ? "" : INVALID.matcher(name).replaceAll("_").strip();
        while (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        if (text.isEmpty()) {
            return "_";
        }
        if (RESERVED.matcher(text).matches()) {
            text = "_" + text;
        }
        if (text.length() > MAX_NAME_LENGTH) {
            // 拡張子は残す（例: 長い名前.pdf）
            int dot = text.lastIndexOf('.');
            String ext = dot > 0 && text.length() - dot <= 10 ? text.substring(dot) : "";
            int cut = MAX_NAME_LENGTH - ext.length();
            if (Character.isHighSurrogate(text.charAt(cut - 1))) {
                cut--;
            }
            text = text.substring(0, cut) + ext;
        }
        return text;
    }
}
