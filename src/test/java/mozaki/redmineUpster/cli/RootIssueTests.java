package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.stream.Stream;

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
 * WBS（ファイル）ごとのルートチケットのテスト（sync.files の rootIssueId・--root-issue）。
 */
class RootIssueTests {

	private static final long DELETE_STATUS = 99L;
	private static final long ROOT = 50L;

	@TempDir
	Path tempDir;

	/** 疑似Redmineのチケット（チケットID → チケット情報） */
	private Map<Long, Map<String, Object>> redmine;
	private final AtomicLong nextId = new AtomicLong(100);
	/** 論理削除（status_id=99 への変更）したチケットID */
	private List<Long> deleted;
	/** 論理削除以外の更新（チケットID） */
	private List<Long> updated;
	private RedmineClient client;
	private RedmineClientFactory factory;

	@BeforeEach
	void setUp() {
		redmine = new LinkedHashMap<>();
		deleted = new ArrayList<>();
		updated = new ArrayList<>();
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
			} else {
				updated.add(id);
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

	/**
	 * @param virtualParents 仮想親チケットを有効にする場合はtrue
	 * @param files sync.files の要素（YAMLのまま。例: "{ path: a.csv, rootIssueId: 50 }"）
	 */
	private Path config(boolean virtualParents, String... files) throws IOException {
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
		if (virtualParents) {
			lines.add("      virtualParents: { enabled: true, tracker: サマリ }");
		}
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

	private int run(Path config, List<String> files, boolean dryRun, Long rootIssueId) {
		SyncConfigService configService = new SyncConfigService(new SyncConfigProperties());
		SyncRunner runner = new SyncRunner(configService, new SpreadsheetParser(), new DiffCalculator(),
				new SyncExecutor(), factory, new TicketIdWriter(), new DirectorySync());
		return runner.runFiles(config.toString(), null, files, dryRun, tempDir.resolve("logs").toString(), false,
				false, null, null, null, rootIssueId);
	}

	private String file(String name) {
		return tempDir.resolve(name).toString();
	}

	private String logText() throws IOException {
		StringBuilder text = new StringBuilder();
		try (Stream<Path> logs = Files.list(tempDir.resolve("logs"))) {
			for (Path log : logs.toList()) {
				text.append(Files.readString(log));
			}
		}
		return text.toString();
	}

	@Test
	@DisplayName("sync.files は { path, rootIssueId } の形式でも書け、rootIssue・数字の文字列も使える")
	void config_mapEntries() throws IOException {
		Path config = config(false, "{ path: a.csv, rootIssueId: 50 }", "{ path: b.csv, rootIssue: '#60' }",
				"c.csv", "{ path: d.csv }");
		SyncConfigService service = new SyncConfigService(new SyncConfigProperties());
		service.loadConfig(config.toString());

		SyncConfigProperties.SyncConfig sync = service.getProjectByName("t").orElseThrow().getSync();
		Path base = tempDir.toAbsolutePath().normalize();
		assertThat(sync.getFiles()).containsExactly(base.resolve("a.csv").toString(),
				base.resolve("b.csv").toString(), base.resolve("c.csv").toString(), base.resolve("d.csv").toString());
		assertThat(sync.getFileRootIssueIds()).containsExactlyInAnyOrderEntriesOf(Map.of(
				base.resolve("a.csv").toString(), 50L, base.resolve("b.csv").toString(), 60L));
	}

	@Test
	@DisplayName("rootIssueId が数値でない設定はエラー")
	void config_invalidRoot() throws IOException {
		Path config = config(false, "{ path: a.csv, rootIssueId: abc }");
		SyncConfigService service = new SyncConfigService(new SyncConfigProperties());

		assertThatThrownBy(() -> service.loadConfig(config.toString()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rootIssueId");
	}

	@Test
	@DisplayName("--root-issue は数値（先頭の # は省略可）。数値でなければエラー")
	void cli_parseRootIssue() {
		assertThat(SyncCommand.parseRootIssue(null)).isNull();
		assertThat(SyncCommand.parseRootIssue("123")).isEqualTo(123L);
		assertThat(SyncCommand.parseRootIssue("#45")).isEqualTo(45L);
		assertThatThrownBy(() -> SyncCommand.parseRootIssue("abc")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SyncCommand.parseRootIssue("0")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SyncCommand.parseRootIssue("")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("ルートチケットがあるファイルの最上位の行は、ルートチケットの子として作成する")
	void create_topLevelUnderRoot() throws IOException {
		redmine.put(ROOT, issue(ROOT, "ルート", 6, null));
		csv("a.csv", ",サマリ,A,", ",タスク,A,a-task");
		Path config = config(false, "{ path: a.csv, rootIssueId: " + ROOT + " }");

		int exit = run(config, List.of(file("a.csv")), false, null);

		assertThat(exit).isEqualTo(0);
		assertThat(redmine.get(100L).get("parent")).isEqualTo(Map.of("id", ROOT));
		assertThat(redmine.get(101L).get("parent")).isEqualTo(Map.of("id", 100L));
		assertThat(deleted).isEmpty();
		assertThat(Files.readString(tempDir.resolve("a.csv"))).contains("100,サマリ,A,").contains("101,タスク,A,a-task");
	}

	@Test
	@DisplayName("--root-issue は sync.files の rootIssueId より優先し、別プロジェクト（親プロジェクト）のチケットも使える")
	void cliRoot_overridesConfig_andOtherProjectAllowed() throws IOException {
		redmine.put(ROOT, issue(ROOT, "ルート", 6, null));
		Map<String, Object> outside = issue(500, "親プロジェクトのルート", 6, null);
		outside.put("project", Map.of("id", 9L, "name", "親"));
		when(client.getIssue(500L)).thenReturn(outside);
		csv("a.csv", ",サマリ,A,");
		Path config = config(false, "{ path: a.csv, rootIssueId: " + ROOT + " }");

		int exit = run(config, List.of(file("a.csv")), false, 500L);

		assertThat(exit).isEqualTo(0);
		assertThat(redmine.get(100L).get("parent")).isEqualTo(Map.of("id", 500L));
		// 範囲は #500 の配下のため、#50 は論理削除しない
		assertThat(deleted).isEmpty();
	}

	@Test
	@DisplayName("ルートチケットの配下で変更がない行は更新をスキップする")
	void unchangedRowsUnderRoot_skipped() throws IOException {
		redmine.put(ROOT, issue(ROOT, "ルート", 6, null));
		redmine.put(1L, issue(1, "A", 6, ROOT));
		redmine.put(2L, issue(2, "a-task", 2, 1L));
		csv("a.csv", "1,サマリ,A,", "2,タスク,A,a-task");
		Path config = config(false, "{ path: a.csv, rootIssueId: " + ROOT + " }");

		int exit = run(config, List.of(file("a.csv")), false, null);

		assertThat(exit).isEqualTo(0);
		verify(client, never()).createIssue(anyMap());
		verify(client, never()).updateIssue(anyLong(), anyMap());
	}

	@Test
	@DisplayName("ルートチケットなしで最上位にあった行は、ルートチケットを指定するとルートの子に付け替える")
	void existingTopLevel_reparentedUnderRoot() throws IOException {
		redmine.put(ROOT, issue(ROOT, "ルート", 6, null));
		redmine.put(1L, issue(1, "A", 6, null));
		csv("a.csv", "1,サマリ,A,");
		Path config = config(false, "a.csv");

		int exit = run(config, List.of(file("a.csv")), false, ROOT);

		assertThat(exit).isEqualTo(0);
		verify(client).updateIssue(Mockito.eq(1L), Mockito.argThat(payload -> Long.valueOf(ROOT).equals(
				payload.get("parent_issue_id"))));
	}

	@Test
	@DisplayName("最上位の仮想親は、親がルートチケットの既存チケットに対応付ける")
	void virtualParent_matchedUnderRoot() throws IOException {
		redmine.put(ROOT, issue(ROOT, "ルート", 6, null));
		redmine.put(12L, issue(12, "V", 6, null)); // 最上位（ルートの外）の同名チケットは使わない
		redmine.put(10L, issue(10, "V", 6, ROOT));
		redmine.put(11L, issue(11, "v-task", 2, 10L));
		csv("a.csv", "11,タスク,V,v-task");
		Path config = config(true, "{ path: a.csv, rootIssueId: " + ROOT + " }");

		int exit = run(config, List.of(file("a.csv")), false, null);

		assertThat(exit).isEqualTo(0);
		verify(client, never()).createIssue(anyMap());
		assertThat(updated).isEmpty();
		// #12 はルートの配下ではないため論理削除の範囲外
		assertThat(deleted).isEmpty();
	}

	@Test
	@DisplayName("ルートチケットが見つからなければ検証エラーで、Redmineに書き込まない")
	void rootNotFound_noWrites() throws IOException {
		redmine.put(1L, issue(1, "A", 6, null));
		csv("a.csv", ",サマリ,B,");
		Path config = config(false, "a.csv");

		int exit = run(config, List.of(file("a.csv")), false, 999L);

		assertThat(exit).isEqualTo(1);
		verify(client, never()).createIssue(anyMap());
		verify(client, never()).updateIssue(anyLong(), anyMap());
		// 行に結び付かないエラーはログだけ。行には未実行を書く
		assertThat(Files.readString(tempDir.resolve("a.csv"), StandardCharsets.UTF_8))
				.contains("未実行," + SyncRunner.ABORTED_BY_INPUT_ERROR);
		assertThat(logText()).contains("ルートチケット #999 が見つかりません");
	}

	@Test
	@DisplayName("ルートチケットを行のチケットIDに書いた・論理削除ステータスのルートチケットは検証エラー")
	void rootAsRow_orDeletedRoot_isError() throws IOException {
		redmine.put(ROOT, issue(ROOT, "ルート", 6, null));
		Map<String, Object> deletedRoot = issue(60, "削除済み", 6, null);
		deletedRoot.put("status", Map.of("id", DELETE_STATUS));
		redmine.put(60L, deletedRoot);
		csv("a.csv", ROOT + ",サマリ,A,");
		csv("b.csv", ",サマリ,B,");
		Path config = config(false, "{ path: a.csv, rootIssueId: " + ROOT + " }", "{ path: b.csv, rootIssueId: 60 }");

		int exit = run(config, List.of(), false, null);

		assertThat(exit).isEqualTo(1);
		verify(client, never()).createIssue(anyMap());
		verify(client, never()).updateIssue(anyLong(), anyMap());
		assertThat(logText()).contains("はこのファイルのルートチケットです").contains("ルートチケット #60 は論理削除ステータス");
	}

	@Test
	@DisplayName("すべてのファイルにルートチケットがあれば、論理削除はルートの配下だけ（ルートとその祖先は対象外）")
	void logicalDelete_scopedToRootDescendants() throws IOException {
		redmine.put(60L, issue(60, "全体", 6, null));
		redmine.put(ROOT, issue(ROOT, "ルート", 6, 60L));
		redmine.put(1L, issue(1, "A", 6, ROOT));
		redmine.put(2L, issue(2, "旧タスク", 2, 1L));
		redmine.put(3L, issue(3, "手動作成（ルートの外）", 2, null));
		redmine.put(4L, issue(4, "手動作成（ルートの下）", 2, ROOT));
		redmine.put(5L, issue(5, "別WBS", 6, 60L));
		csv("a.csv", "1,サマリ,A,");
		Path config = config(false, "{ path: a.csv, rootIssueId: " + ROOT + " }");

		int exit = run(config, List.of(file("a.csv")), false, null);

		assertThat(exit).isEqualTo(0);
		assertThat(deleted).containsExactly(2L, 4L);
		assertThat(logText()).contains("論理削除の範囲: ルートチケット [#50] の配下");
	}

	@Test
	@DisplayName("ルートチケットのないファイルがあればプロジェクト全体が範囲（ルートとその祖先は論理削除しない）")
	void logicalDelete_mixedFiles_projectWide() throws IOException {
		redmine.put(60L, issue(60, "全体", 6, null));
		redmine.put(ROOT, issue(ROOT, "ルート", 6, 60L));
		redmine.put(1L, issue(1, "A", 6, ROOT));
		redmine.put(2L, issue(2, "B", 6, null));
		redmine.put(3L, issue(3, "手動作成（ルートの外）", 2, null));
		redmine.put(4L, issue(4, "手動作成（ルートの下）", 2, ROOT));
		csv("a.csv", "1,サマリ,A,");
		csv("b.csv", "2,サマリ,B,");
		Path config = config(false, "{ path: a.csv, rootIssueId: " + ROOT + " }", "b.csv");

		int exit = run(config, List.of(), false, null);

		assertThat(exit).isEqualTo(0);
		assertThat(deleted).containsExactly(3L, 4L);
		assertThat(logText()).contains("論理削除の範囲: プロジェクト全体（ルートチケットのないファイルがあるため）");
	}

	@Test
	@DisplayName("sync.files にあるだけのファイルのルートチケットも論理削除しない")
	void logicalDelete_listedFileRootProtected() throws IOException {
		redmine.put(ROOT, issue(ROOT, "ルートA", 6, null));
		redmine.put(70L, issue(70, "ルートB", 6, null));
		redmine.put(1L, issue(1, "A", 6, ROOT));
		csv("a.csv", "1,サマリ,A,");
		csv("b.csv", ",サマリ,B,");
		Path config = config(false, "a.csv", "{ path: b.csv, rootIssueId: 70 }");

		int exit = run(config, List.of(file("a.csv")), false, null);

		assertThat(exit).isEqualTo(0);
		// a.csv にはルートがないためプロジェクト全体が範囲。#50 は a.csv の行 #1 の親だが、どのファイルにもない
		assertThat(deleted).containsExactly(ROOT);
		assertThat(deleted).doesNotContain(70L);
	}

	@Test
	@DisplayName("dry-run ではルートチケットをログに出し、Redmineは変更しない")
	void dryRun_logsRoot() throws IOException {
		redmine.put(ROOT, issue(ROOT, "ルート", 6, null));
		csv("a.csv", ",サマリ,A,");
		Path config = config(false, "{ path: a.csv, rootIssueId: " + ROOT + " }");

		assertThat(run(config, List.of(file("a.csv")), true, null)).isEqualTo(0);
		verify(client, never()).createIssue(anyMap());
		verify(client, never()).updateIssue(anyLong(), anyMap());
		assertThat(logText()).contains("ルートチケット #50 「ルート」 の配下に同期します")
				.contains("parent=#50(ルートチケット)");
	}
}
