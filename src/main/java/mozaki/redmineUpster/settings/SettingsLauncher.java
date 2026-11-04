package mozaki.redmineUpster.settings;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.swing.SwingUtilities;

import mozaki.redmineUpster.cli.SyncRunner;

/**
 * 設定画面の起動（{@code --settings}）。
 * <p>
 * Spring を起動せずに {@link mozaki.redmineUpster.RedmineUpsterApplication#main} から呼ばれ、
 * sync-config.yml を編集する画面を開きます。
 * </p>
 */
public final class SettingsLauncher {

    /** 設定画面を開く引数 */
    public static final String OPTION = "--settings";

    private SettingsLauncher() {
    }

    /**
     * @param args コマンドライン引数
     * @return {@code --settings} が指定されているか
     */
    public static boolean isRequested(String[] args) {
        for (String arg : args) {
            if (OPTION.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 設定画面を開きます（画面はイベントスレッドで動き、閉じるとプロセスが終了します）。
     *
     * @param args コマンドライン引数（{@code --config=} を読む）
     * @return 0: 画面を開いた／1: 画面を表示できない環境
     */
    public static int launch(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Error: 画面を表示できない環境のため、設定画面を開けません。"
                    + "sync-config.yml をテキストエディタで編集してください（説明は USER_GUIDE.md）。");
            return 1;
        }
        Path configPath = resolveConfigPath(args);
        SwingUtilities.invokeLater(() -> SettingsWindow.open(configPath));
        return 0;
    }

    /**
     * 編集する設定ファイルを決めます（同期の {@code --config} 省略時と同じ順）。
     * <ol>
     *   <li>{@code --config=<パス>}</li>
     *   <li>環境変数 SYNC_CONFIG_PATH</li>
     *   <li>カレントディレクトリ → 実行ファイルと同じフォルダの sync-config.yml のうち、あるもの</li>
     *   <li>どれもなければカレントディレクトリの sync-config.yml（保存すると作成）</li>
     * </ol>
     *
     * @param args コマンドライン引数
     * @return 設定ファイルのパス（絶対パス）
     */
    static Path resolveConfigPath(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--config=") && !arg.substring("--config=".length()).isBlank()) {
                return Paths.get(arg.substring("--config=".length()).trim()).toAbsolutePath().normalize();
            }
        }
        String env = System.getenv("SYNC_CONFIG_PATH");
        if (env != null && !env.isBlank()) {
            return Paths.get(env.trim()).toAbsolutePath().normalize();
        }
        List<Path> candidates = SyncRunner.defaultConfigCandidates();
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(candidates.get(0));
    }
}
