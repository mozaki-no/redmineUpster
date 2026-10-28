package mozaki.redmineUpster.settings;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.service.SyncConfigService;
import mozaki.redmineUpster.settings.SettingsDocument.FileEntry;

/**
 * 設定画面の読み書き（{@link SettingsDocument}）のテスト。
 */
class SettingsDocumentTests {

    @TempDir
    Path dir;

    private static final String CONFIG = """
            # コメント
            projects:
              - name: "本番"
                default: true
                unknownProjectKey: "残す"
                redmine:
                  baseUrl: "https://redmine.example.com"
                  apiKey: "${REDMINE_API_KEY}"
                  projectId: "my-project"
                sync:
                  customFieldMap:
                    "担当": "担当者"
                  trackerMap:
                    "タスク": 2
                    "サマリ": 6
                  files:
                    - "WBS-design.xlsx"
                    - { path: "WBS-dev.xlsx", rootIssueId: 124 }
                  status:
                    enabled: true
                    mode: "BY_DATES"
                    statusMap:
                      "未着手": "1"
                      "完了": "5"
                  virtualParents:
                    enabled: true
                    tracker: "サマリ"
                  columns:
                    ticketIdColumn: "チケットID"
                    hierarchy: ["大分類", "中分類", "タスク"]
                    progressColumn: "進捗率"
              - name: "テスト"
                redmine:
                  baseUrl: "https://test.example.com"
                  apiKey: "key"
                  projectId: "test"
            extraRoot:
              keep: 1
            """;

    private Path write(String content) throws Exception {
        Path path = dir.resolve("sync-config.yml");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static List<ProjectConfig> loadWithApp(Path path) {
        SyncConfigService service = new SyncConfigService(new SyncConfigProperties());
        service.loadConfig(path.toString());
        return service.getAllProjects();
    }

    @Test
    @DisplayName("読み込み→保存で画面にない項目と ${...} が残り、アプリの読み込みで同じ値になる")
    void roundTrip_keepsUnknownKeysAndEnvRefs() throws Exception {
        Path path = write(CONFIG);
        List<ProjectConfig> before = loadWithApp(path);

        SettingsDocument doc = SettingsDocument.load(path);
        doc.save();
        String saved = Files.readString(path, StandardCharsets.UTF_8);

        assertThat(saved).startsWith("# redmineUpster 設定ファイル");
        assertThat(saved).contains("apiKey: \"${REDMINE_API_KEY}\"");
        assertThat(saved).contains("unknownProjectKey: \"残す\"");
        assertThat(saved).contains("extraRoot:");
        assertThat(saved).contains("customFieldMap:");
        assertThat(saved).doesNotContain("# コメント");

        List<ProjectConfig> after = loadWithApp(path);
        assertThat(after).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    @DisplayName("保存前の内容を .bak に残す")
    void save_createsBackup() throws Exception {
        Path path = write(CONFIG);
        SettingsDocument doc = SettingsDocument.load(path);
        SettingsDocument.setString(doc.projects().get(0), "redmine.projectId", "other");
        Path backup = doc.save();

        assertThat(backup).isEqualTo(dir.resolve("sync-config.yml.bak"));
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(CONFIG);
        assertThat(loadWithApp(path).get(0).getRedmine().getProjectId()).isEqualTo("other");
    }

    @Test
    @DisplayName("ファイルがなければ空のプロジェクト1つ（既定）で始まり、保存で作成する（.bak は作らない）")
    void load_missingFile_startsWithOneProject() throws Exception {
        Path path = dir.resolve("sub").resolve("sync-config.yml");
        SettingsDocument doc = SettingsDocument.load(path);

        assertThat(doc.projects()).hasSize(1);
        assertThat(SettingsDocument.isDefault(doc.projects().get(0))).isTrue();
        assertThat(doc.validate()).anyMatch(e -> e.contains("URL"));

        Map<String, Object> project = doc.projects().get(0);
        SettingsDocument.setString(project, "name", "新規");
        SettingsDocument.setString(project, "redmine.baseUrl", "https://r.example.com");
        SettingsDocument.setString(project, "redmine.projectId", "p");
        assertThat(doc.validate()).isEmpty();
        assertThat(doc.save()).isNull();

        List<ProjectConfig> loaded = loadWithApp(path);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getName()).isEqualTo("新規");
        assertThat(loaded.get(0).isDefaultProject()).isTrue();
        assertThat(Files.exists(path.resolveSibling("sync-config.yml.bak"))).isFalse();
    }

    @Test
    @DisplayName("ファイル一覧: ルートチケット番号の有無で文字列／{ path, rootIssueId } を書き分ける")
    void files_withAndWithoutRootIssue() throws Exception {
        Path path = write(CONFIG);
        SettingsDocument doc = SettingsDocument.load(path);
        Map<String, Object> project = doc.projects().get(0);

        assertThat(SettingsDocument.getFiles(project)).containsExactly(new FileEntry("WBS-design.xlsx", ""),
                new FileEntry("WBS-dev.xlsx", "124"));

        SettingsDocument.setFiles(project, List.of(new FileEntry("a/WBS-A.xlsx", "#123"),
                new FileEntry("WBS-B.csv", ""), new FileEntry("  ", "9")));
        doc.save();

        String saved = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(saved).contains("path: \"a/WBS-A.xlsx\"").contains("rootIssueId: 123").contains("- \"WBS-B.csv\"");
        SyncConfigProperties.SyncConfig sync = loadWithApp(path).get(0).getSync();
        assertThat(sync.getFiles()).containsExactly(dir.resolve("a/WBS-A.xlsx").normalize().toString(),
                dir.resolve("WBS-B.csv").normalize().toString());
        assertThat(sync.getFileRootIssueIds()).containsEntry(dir.resolve("a/WBS-A.xlsx").normalize().toString(), 123L)
                .hasSize(1);
    }

    @Test
    @DisplayName("空欄にした項目はキーを削除し、空になった親も削除する（既定値が使われる）")
    void emptyValues_removeKeys() throws Exception {
        Path path = write(CONFIG);
        SettingsDocument doc = SettingsDocument.load(path);
        Map<String, Object> project = doc.projects().get(0);

        SettingsDocument.setString(project, "sync.columns.ticketIdColumn", "");
        SettingsDocument.setString(project, "sync.columns.progressColumn", "  ");
        SettingsDocument.setStringList(project, "sync.columns.hierarchy", List.of("", " "));
        SettingsDocument.setStringMap(project, "sync.trackerMap", Map.of(), true);
        SettingsDocument.setBoolean(project, "sync.virtualParents.enabled", false, false);
        SettingsDocument.remove(project, "sync.virtualParents.tracker");
        SettingsDocument.setFiles(project, List.of());
        doc.save();

        String saved = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(saved).doesNotContain("columns:").doesNotContain("trackerMap:").doesNotContain("virtualParents:")
                .doesNotContain("files:").doesNotContain("\"\"");
        ProjectConfig loaded = loadWithApp(path).get(0);
        assertThat(loaded.getSync().getColumns()).isNull();
        assertThat(loaded.getSync().getStatus().getStatusMap()).containsEntry("完了", "5");
    }

    @Test
    @DisplayName("数値の項目は数値で書き、${...} はそのまま。数値でない値は検証エラー")
    void numbers_andValidation() throws Exception {
        Path path = write(CONFIG);
        SettingsDocument doc = SettingsDocument.load(path);
        Map<String, Object> project = doc.projects().get(0);

        SettingsDocument.setNumber(project, "sync.deletion.statusId", "6");
        Map<String, String> trackers = new LinkedHashMap<>();
        trackers.put("タスク", "2");
        trackers.put("バグ", "${BUG_TRACKER:1}");
        SettingsDocument.setStringMap(project, "sync.trackerMap", trackers, true);
        assertThat(doc.validate()).isEmpty();
        doc.save();
        String saved = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(saved).contains("statusId: 6").contains("バグ: \"${BUG_TRACKER:1}\"");
        ProjectConfig loaded = loadWithApp(path).get(0);
        assertThat(loaded.getSync().getDeletion().getStatusId()).isEqualTo(6);
        assertThat(loaded.getSync().getTrackerMap()).containsEntry("バグ", "1");

        SettingsDocument.setNumber(project, "sync.deletion.statusId", "abc");
        trackers.put("タスク", "x");
        SettingsDocument.setStringMap(project, "sync.trackerMap", trackers, true);
        SettingsDocument.setFiles(project, List.of(new FileEntry("a.xlsx", "ルート")));
        assertThat(doc.validate()).hasSize(3);
    }

    @Test
    @DisplayName("プロジェクト名の必須・重複、既定はちょうど1つを検証する")
    void validate_projects() throws Exception {
        SettingsDocument doc = SettingsDocument.load(write(CONFIG));
        SettingsDocument.setString(doc.projects().get(1), "name", "本番");
        assertThat(doc.validate()).anyMatch(e -> e.contains("重複"));

        SettingsDocument.setString(doc.projects().get(1), "name", "");
        assertThat(doc.validate()).anyMatch(e -> e.contains("プロジェクト名を入力"));

        SettingsDocument.setString(doc.projects().get(1), "name", "テスト");
        doc.projects().get(0).remove("default");
        assertThat(doc.validate()).anyMatch(e -> e.contains("既定"));

        doc.setDefaultProject(1);
        assertThat(doc.validate()).isEmpty();
        assertThat(SettingsDocument.isDefault(doc.projects().get(0))).isFalse();
    }

    @Test
    @DisplayName("複製は既定の印を外して名前を変え、削除した既定は先頭に移る")
    void duplicateAndRemove() throws Exception {
        SettingsDocument doc = SettingsDocument.load(write(CONFIG));
        Map<String, Object> copy = doc.duplicateProject(0);

        assertThat(doc.projects()).hasSize(3);
        assertThat(SettingsDocument.getString(copy, "name")).isEqualTo("本番 のコピー");
        assertThat(SettingsDocument.isDefault(copy)).isFalse();
        SettingsDocument.setString(copy, "redmine.projectId", "changed");
        assertThat(SettingsDocument.getString(doc.projects().get(0), "redmine.projectId")).isEqualTo("my-project");

        doc.removeProject(0);
        assertThat(SettingsDocument.isDefault(doc.projects().get(0))).isTrue();
        assertThat(doc.validate()).isEmpty();
    }

    @Test
    @DisplayName("設定ファイルのフォルダの中のファイルは / 区切りの相対パス、外は絶対パス")
    void toConfigPath_relativeUnderBaseDir() throws Exception {
        SettingsDocument doc = SettingsDocument.load(dir.resolve("sync-config.yml"));
        assertThat(doc.toConfigPath(dir.resolve("wbs").resolve("WBS.xlsx"))).isEqualTo("wbs/WBS.xlsx");
        Path outside = dir.getParent().resolve("other.xlsx");
        assertThat(doc.toConfigPath(outside)).isEqualTo(outside.toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("配布版の sync-config.yml を読み込み→保存しても、アプリの読み込み結果は変わらない")
    void distConfig_roundTrip() throws Exception {
        Path source = Paths.get("packaging/dist/sync-config.yml");
        Path path = write(Files.readString(source, StandardCharsets.UTF_8));
        List<ProjectConfig> before = loadWithApp(path);

        SettingsDocument.load(path).save();

        assertThat(loadWithApp(path)).usingRecursiveComparison().isEqualTo(before);
        // 2回目の保存で内容が変わらない（キーの並びが安定している）
        String first = Files.readString(path, StandardCharsets.UTF_8);
        SettingsDocument.load(path).save();
        assertThat(Files.readString(path, StandardCharsets.UTF_8)).isEqualTo(first);
    }
}
