package mozaki.redmineUpster.settings;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 設定画面の起動判定（{@link SettingsLauncher}）のテスト。
 */
class SettingsLauncherTests {

    @Test
    @DisplayName("--settings を判定し、--config= の設定ファイルを絶対パスで返す")
    void settingsOption_andConfigPath() {
        assertThat(SettingsLauncher.isRequested(new String[] { "--settings", "--config=conf/a.yml" })).isTrue();
        assertThat(SettingsLauncher.isRequested(new String[] { "--sync", "--settings-x" })).isFalse();
        assertThat(SettingsLauncher.resolveConfigPath(new String[] { "--settings", "--config=conf/a.yml" }))
                .isEqualTo(Paths.get("conf/a.yml").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("--config 省略時は同期と同じ候補（なければカレントディレクトリの sync-config.yml）")
    void defaultConfigPath() {
        if (System.getenv("SYNC_CONFIG_PATH") != null) {
            return;
        }
        assertThat(SettingsLauncher.resolveConfigPath(new String[] { "--settings" }).getFileName().toString())
                .isEqualTo("sync-config.yml");
    }
}
