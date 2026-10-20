package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.RedmineClientFactory;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.service.SyncConfigService;
import mozaki.redmineUpster.service.TicketIdWriter;

/**
 * 複数ファイルの同期（--file の複数指定・sync.files）と、論理削除の範囲のテスト。
 */
class MultiFileSyncTests {

	private static final long DELETE_STATUS = 99L;

	@TempDir
	Path tempDir;

	/** 疑似Redmineのチケット（チケットID → チケット情報） */
	private Map<Long, Map<String, Object>> redmine;
	private final AtomicLong nextId = new AtomicLong(100);
	/** 論理削除（status_id=99 への変更）したチケットID */
	private List<Long> deleted;
	private RedmineClient client;
	private RedmineClientFactory factory;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		redmine = new LinkedHashMap<>();
		deleted = new ArrayList<>();
		redmine.put(1L, issue(1, "A", 6, null));
		redmine.put(2L, issue(2, "B", 6, null));
		redmine.put(3L, issue(3, "手動作成", 2, null));
		client = Mockito.mock(RedmineClient.class);
		when(client.getProjectId()).thenReturn("p");
		when(client.getBaseUrl()).thenReturn("http://redmine.local");
		when(client.listProjectIssues()).thenAnswer(invocation -> {
			Map<Long, Map<String, Object>> copy = new LinkedHashMap<>();
			redmine.forEach((id, issue) -> copy.put(id, new HashMap<>(issue)));
			return copy;
		});
		when(client.createIssue(anyMap())).thenAnswer(invocation -> {
			Map<String, Object> payload = invocation.getArgument(0);
			long id = nextId.getAndIncrement();
			Object parent = payload.get("parent_issue_id");
			redmine.put(id, issue(id, (String) payload.get("subject"), ((Number) payload.get("tracker_id")).longValue(),
					parent instanceof Number number ? number.longValue() : null));
			return id;
		});
		Mockito.doAnswer(invocation -> {
			Long id = invocation.getArgument(0);
			Map<String, Object> payload = invocation.getArgument(1);
			Object status = payload.get("status_id");
			if (status instanceof Number number && number.longValue() == DELETE_STATUS) {
				deleted.add(id);
				redmine.get(id).put("status", Map.of("id", DELETE_STATUS));
			}
			return null;
		}).when(client).updateIssue(anyLong(), anyMap());
		factory = Mockito.mock(RedmineClientFactory.class);
		when(factory.createClient(any())).thenReturn(client);
	}

	private static Map<String, Object> issue(long id, String subject, long trackerId, Long parentId) {
		Map<String, Object> issue = new HashMap<>();
		issue.put("id", id);
		issue.put("subject", subject);
		issue.put("tracker", Map.of("id", trackerId));
		issue.put("status", Map.of("id", 1L));
		if (parentId != null) {
			issue.put("parent", Map.of("id", parentId));
		}
		return issue;
	}

	private Path csv(String name, String... lines) throws IOException {
		Path file = tempDir.resolve(name);
		Files.writeString(file, "チケットID,トラッカー,大分類,タスク\n" + String.join("\n", lines) + "\n",
				StandardCharsets.UTF_8);
		return file;
	}

	private Path config(String... files) throws IOException {
		List<String> lines = new ArrayList<>(List.of(
				"projects:",
				"  - name: t",
				"    default: true",
				"    redmine: { baseUrl: 'http://127.0.0.1:1', apiKey: k, projectId: p }",
				"    sync:",
				"      trackerMap: { サマリ: 6, タスク: 2 }",
				"      deletion: { statusId: " + DELETE_STATUS + " }",
				"      columns:",
				"        hierarchy: [大分類, タスク]"));
		if (files.length > 0) {
			lines.add("      files:");
			for (String file : files) {
				lines.add("        - " + file);
			}
		}
		Path config = tempDir.resolve("sync-config.yml");
		Files.writeString(config, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		return config;
	}

	private int run(Path config, List<String> files, boolean dryRun) {
		SyncConfigService configService = new SyncConfigService(new SyncConfigProperties());
		SyncRunner runner = new SyncRunner(configService, new SpreadsheetParser(), new DiffCalculator(),
				new SyncExecutor(), factory, new TicketIdWriter(), new DirectorySync());
		return runner.runFiles(config.toString(), null, files, dryRun, tempDir.resolve("logs").toString(), false,
				false, null, null, null);
	}

	/** A: 既存の大分類 #1 と新規タスク、B: 既存の大分類 #2 */
	private void writeFiles() throws IOException {
		csv("a.csv", "1,サマリ,A,", ",タスク,A,a-task");
		csv("b.csv", "2,サマリ,B,");
	}

	@Test
	@DisplayName("--file は複数指定でき、指定した順に値を返す（空欄は無視）")
	void getArgValues_multipleFiles() {
		String[] args = { "--sync", "--file=a.xlsx", "--dry-run", "--file=b.xlsx", "--file=", "--files=x" };
		assertThat(SyncCommand.getArgValues(args, "--file")).containsExactly("a.xlsx", "b.xlsx");
		assertThat(SyncCommand.getArgValues(new String[] { "--sync" }, "--file")).isEmpty();
	}

	@Test
	@DisplayName("sync.files の相対パスは設定ファイルのフォルダ基準で解決する（1件なら文字列でも書ける）")
	void syncFiles_resolvedAgainstConfigFolder() throws IOException {
		Path dir = Files.createDirectories(tempDir.resolve("conf"));
		Path absolute = tempDir.resolve("other").resolve("c.xlsx").toAbsolutePath();
		Path config = dir.resolve("sync-config.yml");
		Files.writeString(config, String.join("\n",
				"projects:",
				"  - name: t",
				"    default: true",
				"    redmine: { baseUrl: 'http://127.0.0.1:1', apiKey: k, projectId: p }",
				"    sync:",
				"      files:",
				"        - a.xlsx",
				"        - ../wbs/b.csv",
				"        - ''",
				"        - '" + absolute + "'",
				"  - name: single",
				"    redmine: { baseUrl: 'http://127.0.0.1:1', apiKey: k, projectId: q }",
				"    sync:",
				"      files: one.xlsx",
				""), StandardCharsets.UTF_8);
		SyncConfigService service = new SyncConfigService(new SyncConfigProperties());
		service.loadConfig(config.toString());

		Path base = dir.toAbsolutePath().normalize();
		assertThat(service.getProjectByName("t").orElseThrow().getSync().getFiles()).containsExactly(
				base.resolve("a.xlsx").toString(),
				base.resolve("../wbs/b.csv").normalize().toString(),
				absolute.normalize().toString());
		assertThat(service.getProjectByName("single").orElseThrow().getSync().getFiles())
				.containsExactly(base.resolve("one.xlsx").toString());
	}

	@Test
	@DisplayName("--file があればその順（重複は1回）、なければ sync.files を使う")
	void resolveFiles_cliOverListed() {
		assertThat(SyncRunner.resolveFiles(List.of("b.csv", "a.csv", "./b.csv"), List.of("a.csv"), null))
				.containsExactly("b.csv", "a.csv");
		assertThat(SyncRunner.resolveFiles(List.of(), List.of("a.csv", "b.csv"), null))
				.containsExactly("a.csv", "b.csv");
		assertThat(SyncRunner.resolveFiles(List.of(), List.of(), null)).isEmpty();
	}

	@Test
	@DisplayName("A だけを同期しても、sync.files にある B のチケットと今回作成したチケットは論理削除しない")
	void singleFileRun_doesNotDeleteOtherListedFile() throws IOException {
		writeFiles();
		Path config = config("a.csv", "b.csv");

		int exit = run(config, List.of(tempDir.resolve("a.csv").toString()), false);

		assertThat(exit).isEqualTo(0);
		// a-task を #100 として作成（親は #1）
		assertThat(redmine.get(100L).get("subject")).isEqualTo("a-task");
		assertThat(redmine.get(100L).get("parent")).isEqualTo(Map.of("id", 1L));
		// 論理削除はどのファイルにもない手動作成の #3 だけ
		assertThat(deleted).containsExactly(3L);
		assertThat(Files.readString(tempDir.resolve("a.csv"))).contains("100,タスク,A,a-task");
		assertThat(tempDir.resolve("a.csv.bak")).exists();
		assertThat(tempDir.resolve("b.csv.bak")).doesNotExist();
	}

	@Test
	@DisplayName("sync.files を使わない場合、--file に複数指定したファイルの和集合で論理削除を判定する")
	void multipleCliFiles_unionScope() throws IOException {
		writeFiles();
		Path config = config();

		int exit = run(config,
				List.of(tempDir.resolve("a.csv").toString(), tempDir.resolve("b.csv").toString()), false);

		assertThat(exit).isEqualTo(0);
		assertThat(deleted).containsExactly(3L);
		assertThat(redmine).containsKey(100L);
	}

	@Test
	@DisplayName("--file を省略すると sync.files のファイルを書いた順にすべて同期する")
	void noCliFile_usesSyncFiles() throws IOException {
		csv("a.csv", "1,サマリ,A,", ",タスク,A,a-task");
		csv("b.csv", "2,サマリ,B,", ",タスク,B,b-task");
		Path config = config("a.csv", "b.csv");

		int exit = run(config, List.of(), false);

		assertThat(exit).isEqualTo(0);
		assertThat(redmine.get(100L).get("subject")).isEqualTo("a-task");
		assertThat(redmine.get(101L).get("subject")).isEqualTo("b-task");
		assertThat(redmine.get(101L).get("parent")).isEqualTo(Map.of("id", 2L));
		assertThat(deleted).containsExactly(3L);
		assertThat(Files.readString(tempDir.resolve("b.csv"))).contains("101,タスク,B,b-task");
	}

	@Test
	@DisplayName("--file も sync.files もなければエラー終了し、Redmineに接続しない")
	void noFiles_isError() throws IOException {
		Path config = config();

		assertThat(run(config, List.of(), false)).isEqualTo(1);
		Mockito.verifyNoInteractions(factory);
	}

	@Test
	@DisplayName("sync.files のファイルを読めない場合は安全のため論理削除を行わない")
	void unreadableListedFile_skipsLogicalDelete() throws IOException {
		writeFiles();
		Path config = config("a.csv", "b.csv", "missing.csv");

		int exit = run(config, List.of(tempDir.resolve("a.csv").toString()), false);

		assertThat(exit).isEqualTo(0);
		assertThat(redmine).containsKey(100L);
		assertThat(deleted).isEmpty();
	}

	@Test
	@DisplayName("sync.files のファイルに検証エラーがある場合も論理削除を行わない")
	void invalidListedFile_skipsLogicalDelete() throws IOException {
		writeFiles();
		csv("c.csv", "abc,サマリ,C,");
		Path config = config("a.csv", "b.csv", "c.csv");

		assertThat(run(config, List.of(tempDir.resolve("a.csv").toString()), false)).isEqualTo(0);
		assertThat(deleted).isEmpty();
	}

	@Test
	@DisplayName("どれか1つのファイルに検証エラーがあれば、どのファイルもRedmineに書き込まない")
	void validationErrorInAnyFile_noWrites() throws IOException {
		writeFiles();
		csv("c.csv", ",不明なトラッカー,C,");
		Path config = config("a.csv", "b.csv", "c.csv");

		int exit = run(config, List.of(), false);

		assertThat(exit).isEqualTo(1);
		verify(client, never()).createIssue(anyMap());
		verify(client, never()).updateIssue(anyLong(), anyMap());
		assertThat(tempDir.resolve("a.csv.bak")).doesNotExist();
	}

	@Test
	@DisplayName("sync.files にあるだけのファイルが必要とする仮想親チケットは論理削除しない")
	void virtualParentOfListedFile_notDeleted() throws IOException {
		// #10 は B の仮想親（大分類「V」。B には大分類の行がない）
		redmine.put(10L, issue(10, "V", 6, null));
		redmine.put(11L, issue(11, "v-task", 2, 10L));
		csv("a.csv", "1,サマリ,A,");
		csv("b.csv", "11,タスク,V,v-task");
		Path config = config("a.csv", "b.csv");
		Files.writeString(config, Files.readString(config).replace("      columns:",
				"      virtualParents: { enabled: true, tracker: サマリ }\n      columns:"));

		int exit = run(config, List.of(tempDir.resolve("a.csv").toString()), false);

		assertThat(exit).isEqualTo(0);
		assertThat(deleted).containsExactly(2L, 3L);
	}

	@Test
	@DisplayName("dry-run では論理削除候補をログに出すだけで、Redmineは変更しない")
	void dryRun_noWrites() throws IOException {
		writeFiles();
		Path config = config("a.csv", "b.csv");

		assertThat(run(config, List.of(), true)).isEqualTo(0);
		verify(client, never()).createIssue(anyMap());
		verify(client, never()).updateIssue(anyLong(), anyMap());
	}
}
