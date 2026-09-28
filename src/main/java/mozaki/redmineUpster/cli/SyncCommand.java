package mozaki.redmineUpster.cli;

import java.util.Arrays;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import mozaki.redmineUpster.service.ExcelSource;

/**
 * CLI実行クラス。
 * <p>
 * Spring Bootの{@link CommandLineRunner}を実装し、コマンドライン引数から
 * 同期処理を実行します。
 * </p>
 *
 * <p>使用方法:</p>
 * <pre>
 * redmineUpster.exe \        （または java -jar redmineUpster.jar）
 *   --sync \
 *   --config=sync-config.yml \
 *   --project="本番環境" \
 *   --file=input.csv \
 *   --dry-run \
 *   --log-dir=/var/log/redmine-sync/
 * </pre>
 *
 * <p>引数:</p>
 * <ul>
 *   <li>{@code --sync}: 同期を実行（{@code --file} を指定した場合は省略可）</li>
 *   <li>{@code --config}: 設定ファイルパス（省略時は SYNC_CONFIG_PATH、なければカレントディレクトリ／
 *       実行ファイルと同じフォルダの sync-config.yml）</li>
 *   <li>{@code --project}: プロジェクト名（省略時はdefault=trueのプロジェクト）</li>
 *   <li>{@code --file}: CSV/Excelファイルパス（必須）</li>
 *   <li>{@code --dry-run}: ドライランモード（省略時はfalse）</li>
 *   <li>{@code --log-dir}: ログ出力ディレクトリ（省略時はカレントディレクトリの logs フォルダ）</li>
 *   <li>{@code --debug}: デバッグモード（詳細なログを出力、省略時はfalse）</li>
 *   <li>{@code --force-update}: 更新スキップを無効化して全件Update</li>
 *   <li>{@code --sheet} / {@code --table}: Excel の読み込み元（シート名・番号／テーブル名。設定 sync.excel より優先）</li>
 *   <li>{@code --help}: 使い方を表示</li>
 *   <li>{@code --relink-parent} / {@code --reset-sync}: 廃止（指定しても無視し、警告を出す）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SyncCommand implements CommandLineRunner {

    /** 廃止されたオプション（親子は毎回階層から再設定、物理削除は行わないため不要） */
    private static final String[] REMOVED_OPTIONS = { "--relink-parent", "--reset-sync" };

    private final SyncRunner syncRunner;

    /**
     * コマンドライン引数を解析して同期処理を実行します。
     *
     * @param args コマンドライン引数
     */
    @Override
    public void run(String... args) {
        // --sync も --file もない場合（引数なし・--help）は使い方を表示して終了
        if (hasArg(args, "--help") || hasArg(args, "-h")
                || (!hasArg(args, "--sync") && !hasArg(args, "--file"))) {
            printUsage();
            return;
        }

        // 引数を解析
        String configPath = getArgValue(args, "--config");
        String projectName = getArgValue(args, "--project");
        String filePath = getArgValue(args, "--file");
        boolean dryRun = hasArg(args, "--dry-run");
        String logDir = getArgValue(args, "--log-dir");
        boolean debug = hasArg(args, "--debug");
        boolean forceUpdate = hasArg(args, "--force-update");
        ExcelSource excelSource = new ExcelSource(getArgValue(args, "--sheet"), getArgValue(args, "--table"));
        for (String removed : REMOVED_OPTIONS) {
            if (hasArg(args, removed)) {
                System.err.println("WARN: " + removed + " は廃止されました（チケットID列方式では不要のため無視します）");
            }
        }

        // --fileは必須
        if (filePath == null || filePath.isBlank()) {
            System.err.println("Error: --file argument is required");
            System.err.println();
            printUsage();
            System.exit(1);
            return;
        }

        // 同期実行
        int exitCode = syncRunner.run(configPath, projectName, filePath, dryRun, logDir, debug, forceUpdate,
                excelSource);
        System.exit(exitCode);
    }

    /**
     * 指定された引数が存在するかチェックします。
     *
     * @param args 引数配列
     * @param argName 引数名
     * @return 引数が存在する場合はtrue
     */
    private boolean hasArg(String[] args, String argName) {
        return Arrays.stream(args)
                .anyMatch(arg -> arg.equals(argName) || arg.startsWith(argName + "="));
    }

    /**
     * 引数の値を取得します。
     * <p>
     * {@code --arg=value} 形式から値を抽出します。
     * </p>
     *
     * @param args 引数配列
     * @param argName 引数名（例: "--config"）
     * @return 引数の値（見つからない場合はnull）
     */
    private String getArgValue(String[] args, String argName) {
        String prefix = argName + "=";
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith(prefix))
                .map(arg -> arg.substring(prefix.length()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 使用方法を表示します。
     */
    private void printUsage() {
        System.out.println("Usage: redmineUpster --sync --file=<CSV/Excel> [options]");
        System.out.println("       (java -jar redmineUpster.jar --sync --file=<CSV/Excel> [options])");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --sync                  Run sync (may be omitted when --file is given)");
        System.out.println("  --file=<path>           CSV/Excel file path (required)");
        System.out.println("  --config=<path>         Config file (default: SYNC_CONFIG_PATH, else sync-config.yml");
        System.out.println("                          in the current folder or next to the executable)");
        System.out.println("  --project=<name>        Project name (default: the project with default: true)");
        System.out.println("  --dry-run               Show what would change without writing to Redmine");
        System.out.println("  --log-dir=<path>        Log output directory (default: ./logs)");
        System.out.println("  --debug                 Output detailed logs");
        System.out.println("  --force-update          Update every row even if Redmine already has the same values");
        System.out.println("  --sheet=<name|number>   Excel sheet to read (default: sync.excel.sheet, else the first sheet)");
        System.out.println("  --table=<name>          Excel table to read (default: sync.excel.table; overrides --sheet)");
        System.out.println("  --help                  Show this help");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  redmineUpster --sync --config=sync-config.yml --file=WBS.xlsx --dry-run");
        System.out.println("  redmineUpster --sync --config=sync-config.yml --file=WBS.xlsx");
    }
}
