package mozaki.redmineUpster.cli;

import java.util.Arrays;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * CLI実行クラス。
 * <p>
 * Spring Bootの{@link CommandLineRunner}を実装し、コマンドライン引数から
 * 同期処理を実行します。
 * </p>
 *
 * <p>使用方法:</p>
 * <pre>
 * java -jar redmineUpster.jar \
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
 *   <li>{@code --sync}: CLI同期モード実行（この引数がない場合はWebサーバーモード）</li>
 *   <li>{@code --config}: 設定ファイルパス（省略時は sync.config-path 設定値）</li>
 *   <li>{@code --project}: プロジェクト名（省略時はdefault=trueのプロジェクト）</li>
 *   <li>{@code --file}: CSV/Excelファイルパス（必須）</li>
 *   <li>{@code --dry-run}: ドライランモード（省略時はfalse）</li>
 *   <li>{@code --log-dir}: ログ出力ディレクトリ（省略時はカレントディレクトリ）</li>
 *   <li>{@code --debug}: デバッグモード（詳細なログを出力、省略時はfalse）</li>
 *   <li>{@code --relink-parent}: 親子関係の再計算を強制（削除は実行しない）</li>
 *   <li>{@code --force-update}: 更新スキップを無効化して全件Update</li>
 *   <li>{@code --reset-sync}: 既存チケットを全削除してからCSV/Excelを全件再作成</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SyncCommand implements CommandLineRunner {

    private final SyncRunner syncRunner;

    /**
     * コマンドライン引数を解析して同期処理を実行します。
     *
     * @param args コマンドライン引数
     */
    @Override
    public void run(String... args) {
        // --sync引数がない場合はWebサーバーモードとして何もしない
        if (!hasArg(args, "--sync")) {
            return;
        }

        // 引数を解析
        String configPath = getArgValue(args, "--config");
        String projectName = getArgValue(args, "--project");
        String filePath = getArgValue(args, "--file");
        boolean dryRun = hasArg(args, "--dry-run");
        String logDir = getArgValue(args, "--log-dir");
        boolean debug = hasArg(args, "--debug");
        boolean relinkOnly = hasArg(args, "--relink-parent");
        boolean forceUpdate = hasArg(args, "--force-update");
        boolean resetSync = hasArg(args, "--reset-sync");

        // --fileは必須
        if (filePath == null || filePath.isBlank()) {
            System.err.println("Error: --file argument is required");
            System.err.println();
            printUsage();
            System.exit(1);
            return;
        }

        // 同期実行
        int exitCode = syncRunner.run(configPath, projectName, filePath, dryRun, logDir, debug, relinkOnly, forceUpdate,
            resetSync);
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
        System.out.println("Usage: java -jar redmineUpster.jar --sync [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --sync                  CLI sync mode (required for CLI execution)");
        System.out.println("  --config=<path>         Configuration file path (optional)");
        System.out.println("  --project=<name>        Project name (optional, uses default if not specified)");
        System.out.println("  --file=<path>           CSV/Excel file path (required)");
        System.out.println("  --dry-run               Dry run mode (optional)");
        System.out.println("  --log-dir=<path>        Log output directory (optional, defaults to current directory)");
        System.out.println("  --debug                 Debug mode (output detailed logs, optional)");
        System.out.println("  --relink-parent         Recalculate parent links (skips deletion)");
        System.out.println("  --force-update          Disable update skipping (force all updates)");
        System.out.println("  --reset-sync           Delete existing issues first, then recreate all rows");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar redmineUpster.jar --sync --file=input.csv --dry-run");
        System.out.println("  java -jar redmineUpster.jar --sync --config=my-config.yml --project=\"Production\" --file=tasks.xlsx");
        System.out.println("  java -jar redmineUpster.jar --sync --file=input.csv --debug");
    }
}
