package mozaki.redmineUpster.cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ファイルログ出力クラス。
 * <p>
 * ログをファイルと標準出力の両方に出力します。
 * ログファイル名は {@code sync-{timestamp}.log} 形式です。
 * </p>
 */
public class FileLogger implements AutoCloseable {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BufferedWriter writer;
    private final Path logFile;
    private boolean debugEnabled = false;

    /**
     * FileLoggerを構築します。
     *
     * @param logDir ログ出力ディレクトリ
     * @throws IOException ファイル作成に失敗した場合
     */
    public FileLogger(String logDir) throws IOException {
        Path dir = logDir != null && !logDir.isBlank() ? Paths.get(logDir) : Paths.get(".");
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER);
        String filename = "sync-" + timestamp + ".log";
        this.logFile = dir.resolve(filename);

        this.writer = Files.newBufferedWriter(
            logFile,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    /**
     * ログファイルのパスを取得します。
     *
     * @return ログファイルのパス
     */
    public Path getLogFile() {
        return logFile;
    }

    /**
     * デバッグモードを設定します。
     *
     * @param enabled デバッグモードを有効にする場合はtrue
     */
    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
    }

    /**
     * デバッグモードが有効かどうかを取得します。
     *
     * @return デバッグモードが有効な場合はtrue
     */
    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * DEBUGレベルのログを出力します。
     * <p>
     * デバッグモードが有効な場合のみ出力されます。
     * </p>
     *
     * @param message ログメッセージ
     */
    public void debug(String message) {
        if (debugEnabled) {
            log("DEBUG", message);
        }
    }

    /**
     * INFOレベルのログを出力します。
     *
     * @param message ログメッセージ
     */
    public void info(String message) {
        log("INFO", message);
    }

    /**
     * WARNレベルのログを出力します。
     *
     * @param message ログメッセージ
     */
    public void warn(String message) {
        log("WARN", message);
    }

    /**
     * ERRORレベルのログを出力します。
     *
     * @param message ログメッセージ
     */
    public void error(String message) {
        log("ERROR", message);
    }

    /**
     * ログを出力します。
     *
     * @param level ログレベル
     * @param message ログメッセージ
     */
    private void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String logLine = String.format("[%s] [%s] %s", timestamp, level, message);

        // 標準出力に出力
        System.out.println(logLine);

        // ファイルに出力
        try {
            writer.write(logLine);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            System.err.println("Failed to write log: " + e.getMessage());
        }
    }

    /**
     * リソースを解放します。
     */
    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("Failed to close log file: " + e.getMessage());
        }
    }
}
