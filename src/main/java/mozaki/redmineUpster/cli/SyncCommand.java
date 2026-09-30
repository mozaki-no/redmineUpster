package mozaki.redmineUpster.cli;

import java.util.Arrays;
import java.util.Set;

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
 *   <li>{@code --virtual-parents} / {@code --no-virtual-parents}: 親行がない行の祖先を仮想親チケットとして
 *       作成する／しない（設定 sync.virtualParents.enabled より優先）</li>
 *   <li>{@code --targets}: 同期・出力する対象（tickets,users,groups のカンマ区切り。省略時はファイルにある表すべて）</li>
 *   <li>{@code --export}: 同期の代わりに、プロジェクトのチケットとユーザー・グループを {@code --file} の .xlsx に出力</li>
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
    private final ExportRunner exportRunner;

    /**
     * コマンドライン引数を解析して同期処理を実行します。
     *
     * @param args コマンドライン引数
     */
    @Override
    public void run(String... args) {
        // --sync も --file もない場合（引数なし・--help）は使い方を表示して終了
        if (hasArg(args, "--help") || hasArg(args, "-h")
                || (!hasArg(args, "--sync") && !hasArg(args, "--file") && !hasArg(args, "--export"))) {
            printUsage();
            return;
        }

        Set<SyncTarget> targets;
        try {
            String rawTargets = getArgValue(args, "--targets");
            targets = rawTargets == null ? null : SyncTarget.parse(rawTargets);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Excel 出力（--export --file=出力先.xlsx）
        if (hasArg(args, "--export")) {
            String out = getArgValue(args, "--file");
            if (out == null || out.isBlank()) {
                System.err.println("Error: --export には --file=<出力先.xlsx> が必要です");
                System.exit(1);
                return;
            }
            System.exit(exportRunner.run(getArgValue(args, "--config"), getArgValue(args, "--project"), out,
                    getArgValue(args, "--log-dir"), hasArg(args, "--debug"), targets));
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
                excelSource, parseVirtualParents(args), targets);
        System.exit(exitCode);
    }

    /**
     * {@code --virtual-parents} / {@code --no-virtual-parents} を解析します。
     *
     * @param args 引数配列
     * @return true / false（両方ある場合は後に書いた方）。どちらもなければnull（設定に従う）
     */
    static Boolean parseVirtualParents(String[] args) {
        Boolean result = null;
        for (String arg : args) {
            if (arg.equals("--virtual-parents") || arg.equals("--virtual-parents=true")) {
                result = Boolean.TRUE;
            } else if (arg.equals("--no-virtual-parents") || arg.equals("--virtual-parents=false")) {
                result = Boolean.FALSE;
            }
        }
        return result;
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
        System.out.println("       redmineUpster --export --file=<output.xlsx> [options]");
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
        System.out.println("  --virtual-parents       Auto-create missing ancestor rows as virtual parent tickets");
        System.out.println("  --no-virtual-parents    Treat missing parent rows as errors (overrides sync.virtualParents)");
        System.out.println("  --targets=<list>        What to sync/export: tickets,users,groups (default: all sheets in the file)");
        System.out.println("  --export                Export tickets of the project, users and groups to --file (.xlsx)");
        System.out.println("                          The exported file can be used as --file for --sync as it is");
        System.out.println("  --help                  Show this help");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  redmineUpster --sync --config=sync-config.yml --file=WBS.xlsx --dry-run");
        System.out.println("  redmineUpster --sync --config=sync-config.yml --file=WBS.xlsx");
        System.out.println("  redmineUpster --export --config=sync-config.yml --file=redmine.xlsx");
        System.out.println("  redmineUpster --sync --config=sync-config.yml --file=redmine.xlsx --targets=users,groups");
    }
}
