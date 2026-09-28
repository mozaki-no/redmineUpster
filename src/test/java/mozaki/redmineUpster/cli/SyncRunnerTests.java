package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncRunnerTests {

	@TempDir
	Path tempDir;

	@AfterEach
	void tearDown() {
		System.clearProperty("jpackage.app-path");
	}

	@Test
	@DisplayName("設定ファイルは カレントディレクトリ → 実行ファイルのフォルダ の順に探す（Windowsのexe直下）")
	void defaultConfigCandidates_exeFolder() {
		System.setProperty("jpackage.app-path", tempDir.resolve("redmineUpster.exe").toString());
		assertThat(SyncRunner.defaultConfigCandidates()).containsExactly(
				Paths.get("sync-config.yml").toAbsolutePath().normalize(),
				tempDir.resolve("sync-config.yml").toAbsolutePath().normalize());
	}

	@Test
	@DisplayName("Linuxのランチャー（bin/redmineUpster）ではアプリのルートフォルダを見る")
	void defaultConfigCandidates_linuxBinFolder() {
		System.setProperty("jpackage.app-path", tempDir.resolve("bin").resolve("redmineUpster").toString());
		assertThat(SyncRunner.defaultConfigCandidates())
				.contains(tempDir.resolve("sync-config.yml").toAbsolutePath().normalize());
	}
}
