package mozaki.redmineUpster.util;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.service.ExcelSource;
import mozaki.redmineUpster.service.SyncConfigService;
import mozaki.redmineUpster.service.TicketIdWriter;
import mozaki.redmineUpster.settings.SettingsDocument;

/**
 * バックアップ（.bak）の保存先（backup.dir / sync.backup.dir）のテスト。
 */
class BackupLocationTests {

	@TempDir
	Path dir;

	private static final String CSV = "チケットID,大分類,トラッカー\n,A,タスク\n";

	@Test
	@DisplayName("保存先なし（既定）は元のファイルと同じフォルダの <ファイル名>.bak")
	void default_besideOriginal() throws Exception {
		Path file = dir.resolve("wbs.csv");
		assertThat(BackupLocation.of(null).backupFor(file)).isEqualTo(dir.resolve("wbs.csv.bak"));
		assertThat(BackupLocation.of("  ").backupFor(file)).isEqualTo(dir.resolve("wbs.csv.bak"));
	}

	@Test
	@DisplayName("保存先フォルダがなければ作成し、同名のファイル名が別フォルダにある場合だけ識別子を付ける")
	void dir_createdAndCollisionsAvoided() throws Exception {
		Path backupDir = dir.resolve("bak/sub");
		Path a = dir.resolve("design/WBS.xlsx");
		Path b = dir.resolve("dev/wbs.xlsx");
		Path c = dir.resolve("dev/other.xlsx");
		BackupLocation location = BackupLocation.of(backupDir.toString()).withFiles(List.of(a, b, c));
		Path backupA = location.backupFor(a);
		Path backupB = location.backupFor(b);
		assertThat(backupDir).isDirectory();
		assertThat(backupA.getParent()).isEqualTo(backupDir);
		assertThat(backupA.getFileName().toString()).startsWith("WBS.xlsx.design-").endsWith(".bak");
		assertThat(backupB.getFileName().toString()).startsWith("wbs.xlsx.dev-").endsWith(".bak");
		assertThat(backupA).isNotEqualTo(backupB);
		assertThat(location.backupFor(c)).isEqualTo(backupDir.resolve("other.xlsx.bak"));
		// 同じファイルを2回書いても同じバックアップ（従来どおり上書き）
		assertThat(location.backupFor(a)).isEqualTo(backupA);
	}

	@Test
	@DisplayName("書き戻しのバックアップを保存先フォルダに作る（既定は従来どおり隣）")
	void ticketIdWriter_usesBackupDir() throws Exception {
		Path file = dir.resolve("wbs.csv");
		Files.writeString(file, CSV, StandardCharsets.UTF_8);
		TicketIdWriter writer = new TicketIdWriter();
		Path backupDir = dir.resolve("backups");

		TicketIdWriter.WriteBackResult result = writer.writeBack(file.toString(), "チケットID", Map.of(2, 10L),
				ExcelSource.DEFAULT, true, null, null, BackupLocation.of(backupDir.toString()));
		assertThat(result.backup()).isEqualTo(backupDir.resolve("wbs.csv.bak"));
		assertThat(Files.readString(result.backup(), StandardCharsets.UTF_8)).isEqualTo(CSV);
		assertThat(dir.resolve("wbs.csv.bak")).doesNotExist();
		assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("10,A,タスク");

		Path other = dir.resolve("other.csv");
		Files.writeString(other, CSV, StandardCharsets.UTF_8);
		writer.writeBack(other.toString(), "チケットID", Map.of(2, 11L), ExcelSource.DEFAULT);
		assertThat(dir.resolve("other.csv.bak")).exists();
	}

	private List<ProjectConfig> load(String yaml) throws Exception {
		Path config = dir.resolve("conf/sync-config.yml");
		Files.createDirectories(config.getParent());
		Files.writeString(config, yaml, StandardCharsets.UTF_8);
		SyncConfigService service = new SyncConfigService(new SyncConfigProperties());
		service.loadConfig(config.toString());
		return service.getAllProjects();
	}

	private static String project(String name, String sync) {
		return """
				  - name: "%s"
				    redmine: { baseUrl: "https://r.example", apiKey: "k", projectId: "p" }
				%s""".formatted(name, sync);
	}

	@Test
	@DisplayName("設定: 相対パスは設定ファイルのフォルダ基準、絶対パス・${ENV} 可、プロジェクトの sync.backup.dir が優先")
	void config_resolution() throws Exception {
		Path absolute = dir.resolve("abs-backup").toAbsolutePath();
		List<ProjectConfig> projects = load("backup:\n  dir: \"bak\"\nprojects:\n"
				+ project("common", "")
				+ project("abs", "    sync:\n      backup: { dir: \"" + absolute.toString().replace("\\", "/") + "\" }\n")
				+ project("env", "    sync:\n      backup: { dir: \"${UNSET_BACKUP_DIR_FOR_TEST:../envbak}\" }\n")
				+ project("blank", "    sync:\n      backup: { dir: \"\" }\n"));
		Path base = dir.resolve("conf").toAbsolutePath().normalize();
		assertThat(projects.get(0).getBackupDir()).isEqualTo(base.resolve("bak").toString());
		assertThat(projects.get(1).getBackupDir()).isEqualTo(absolute.normalize().toString());
		assertThat(projects.get(2).getBackupDir()).isEqualTo(dir.resolve("envbak").toAbsolutePath().normalize().toString());
		// 空欄は共通の設定
		assertThat(projects.get(3).getBackupDir()).isEqualTo(base.resolve("bak").toString());

		assertThat(load("projects:\n" + project("none", "")).get(0).getBackupDir()).isNull();
	}

	@Test
	@DisplayName("設定画面: 保存時の sync-config.yml.bak は backup.dir（共通）に作り、空欄なら隣。往復で値が残る")
	void settingsDocument_backupDir() throws Exception {
		Path config = dir.resolve("sync-config.yml");
		Files.writeString(config, "projects:\n" + project("p", "    default: true\n"), StandardCharsets.UTF_8);
		SettingsDocument doc = SettingsDocument.load(config);
		assertThat(doc.save()).isEqualTo(dir.resolve("sync-config.yml.bak"));

		doc.setBackupDir("bak");
		SettingsDocument.setString(doc.projects().get(0), "sync.backup.dir", "projbak");
		assertThat(doc.validate()).isEmpty();
		doc.save();
		Path backup = SettingsDocument.load(config).save();
		assertThat(backup).isEqualTo(dir.resolve("bak/sync-config.yml.bak"));
		String yaml = Files.readString(config, StandardCharsets.UTF_8);
		assertThat(yaml.indexOf("backup:")).isLessThan(yaml.indexOf("projects:"));
		SettingsDocument again = SettingsDocument.load(config);
		assertThat(again.getBackupDir()).isEqualTo("bak");
		assertThat(SettingsDocument.getString(again.projects().get(0), "sync.backup.dir")).isEqualTo("projbak");

		// ファイルを指定すると検証エラー
		Files.writeString(dir.resolve("afile"), "x");
		again.setBackupDir("afile");
		assertThat(again.validate()).anyMatch(e -> e.contains("ファイル"));
	}
}
