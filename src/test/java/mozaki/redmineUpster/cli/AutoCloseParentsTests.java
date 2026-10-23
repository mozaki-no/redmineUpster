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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.AutoCloseParentsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.RedmineClientFactory;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.service.SyncConfigService;
import mozaki.redmineUpster.service.TicketIdWriter;

/**
 * 親チケットの自動完了（sync.autoCloseParents）のテスト。
 */
class AutoCloseParentsTests {

	private static final long OPEN = 1L;
	private static final long IN_PROGRESS = 2L;
	private static final long DONE = 5L;
	private static final long DELETE_STATUS = 99L;
	private static final long ROOT = 50L;

	@TempDir
	Path tempDir;

	/** 疑似Redmineのチケット（チケットID → チケット情報） */
	private Map<Long, Map<String, Object>> redmine;
	private final AtomicLong nextId = new AtomicLong(100);
	/** ステータスを変更したチケットID → ステータスID（順番どおり） */
	private List<long[]> statusChanges;
	private RedmineClient client;
	private RedmineClientFactory factory;

	@BeforeEach
	void setUp() {
		redmine = new LinkedHashMap<>();
		statusChanges = new ArrayList<>();
		client = Mockito.mock(RedmineClient.class);
		when(client.getProjectId()).thenReturn("p");
		when(client.getBaseUrl()).thenReturn("http://redmine.local");
		Map<String, Long> statuses = new LinkedHashMap<>();
		statuses.put("新規", OPEN);
		statuses.put("進行中", IN_PROGRESS);
		statuses.put("終了", DONE);
		statuses.put("却下", DELETE_STATUS);
		when(client.listIssueStatuses()).thenReturn(statuses);
		when(client.listClosedIssueStatusIds()).thenReturn(Set.of(DONE, DELETE_STATUS));
		when(client.listProjectIssues()).thenAnswer(invocation -> {
			Map<Long, Map<String, Object>> copy = new LinkedHashMap<>();
			redmine.forEach((id, issue) -> copy.put(id, new HashMap<>(issue)));
			return copy;
		});
		when(client.createIssue(anyMap())).thenAnswer(invocation -> {
			Map<String, Object> payload = invocation.getArgument(0);
			long id = nextId.getAndIncrement();
			Object parent = payload.get("parent_issue_id");
			Object status = payload.get("status_id");
			redmine.put(id, issue(id, (String) payload.get("subject"),
					parent instanceof Number number ? number.longValue() : null,
					status instanceof Number number ? number.longValue() : OPEN));
			return id;
		});
		Mockito.doAnswer(invocation -> {
			Long id = invocation.getArgument(0);
			Map<String, Object> payload = invocation.getArgument(1);
			if (payload.get("status_id") instanceof Number number) {
				redmine.get(id).put("status", Map.of("id", number.longValue()));
				statusChanges.add(new long[] { id, number.longValue() });
			}
			if (payload.get("parent_issue_id") instanceof Number number) {
				redmine.get(id).put("parent", Map.of("id", number.longValue()));
			}
			return null;
		}).when(client).updateIssue(anyLong(), anyMap());
		factory = Mockito.mock(RedmineClientFactory.class);
		when(factory.createClient(any())).thenReturn(client);
	}

	private static Map<String, Object> issue(long id, String subject, Long parentId, long statusId) {
		Map<String, Object> issue = new HashMap<>();
		issue.put("id", id);
		issue.put("subject", subject);
		issue.put("tracker", Map.of("id", 2L));
		issue.put("status", Map.of("id", statusId));
		if (parentId != null) {
			issue.put("parent", Map.of("id", parentId));
		}
		return issue;
	}

	private Path csv(String header, String... lines) throws IOException {
		Path file = tempDir.resolve("a.csv");
		Files.writeString(file, header + "\n" + String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		return file;
	}

	private Path config(boolean autoClose, String... extra) throws IOException {
		List<String> lines = new ArrayList<>(List.of(
				"projects:",
				"  - name: t",
				"    default: true",
				"    redmine: { baseUrl: 'http://127.0.0.1:1', apiKey: k, projectId: p }",
				"    sync:",
				"      trackerMap: { タスク: 2 }",
				"      deletion: { statusId: " + DELETE_STATUS + " }",
				"      status: { enabled: true, mode: BY_DATES, statusMap: { 未着手: '1', 完了: '5' } }",
				"      columns:",
				"        hierarchy: [大分類, 中分類, タスク]"));
		if (autoClose) {
			lines.add("      autoCloseParents: { enabled: true }");
		}
		lines.addAll(List.of(extra));
		Path config = tempDir.resolve("sync-config.yml");
		Files.writeString(config, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		return config;
	}

	private int run(Path config, boolean dryRun, Long rootIssueId) {
		SyncConfigService configService = new SyncConfigService(new SyncConfigProperties());
		SyncRunner runner = new SyncRunner(configService, new SpreadsheetParser(), new DiffCalculator(),
				new SyncExecutor(), factory, new TicketIdWriter(), new DirectorySync());
		return runner.runFiles(config.toString(), null, List.of(tempDir.resolve("a.csv").toString()), dryRun,
				tempDir.resolve("logs").toString(), false, false, null, null, null, rootIssueId);
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

	private long status(long id) {
		return IssueComparator.nestedId(redmine.get(id), "status");
	}

	private static final String HEADER = "チケットID,トラッカー,大分類,中分類,タスク,ステータス";

	@Test
	@DisplayName("子がすべて完了した親を、孫→子→親の順に再帰的に完了にし、ステータス列に書き戻す")
	void closesRecursively_andWritesBackStatus() throws IOException {
		redmine.put(1L, issue(1, "A", null, OPEN));
		redmine.put(2L, issue(2, "B", 1L, OPEN));
		redmine.put(3L, issue(3, "t1", 2L, OPEN));
		redmine.put(4L, issue(4, "t2", 2L, DONE));
		csv(HEADER, "1,タスク,A,,,", "2,タスク,A,B,,", "3,タスク,A,B,t1,完了", "4,タスク,A,B,t2,完了");

		int exit = run(config(true), false, null);

		assertThat(exit).isEqualTo(0);
		assertThat(status(2)).isEqualTo(DONE);
		assertThat(status(1)).isEqualTo(DONE);
		// 子（#2）を先に完了にしてから親（#1）
		List<Long> order = statusChanges.stream().filter(change -> change[1] == DONE).map(change -> change[0])
				.toList();
		assertThat(order).containsExactly(3L, 2L, 1L);
		assertThat(Files.readString(tempDir.resolve("a.csv"))).contains("1,タスク,A,,,完了")
				.contains("2,タスク,A,B,,完了");
		assertThat(tempDir.resolve("a.csv.bak")).exists();

		// 再実行しても、ステータス列の「完了」で完了のまま（開き直さない）
		statusChanges.clear();
		assertThat(run(config(true), false, null)).isEqualTo(0);
		assertThat(statusChanges).isEmpty();
	}

	@Test
	@DisplayName("同期結果・作成したID・自動で変えたステータスを1回で書き戻す（.bak は元ファイル）。自動で変えた行は「更新」")
	void writesStatusesWithResultsInOneWrite() throws IOException {
		redmine.put(1L, issue(1, "A", null, OPEN));
		redmine.put(2L, issue(2, "B", 1L, OPEN));
		redmine.put(3L, issue(3, "t1", 2L, DONE));
		String original = HEADER + "\n1,タスク,A,,,\n2,タスク,A,B,,\n3,タスク,A,B,t1,完了\n,タスク,A,B,t2,完了\n";
		Files.writeString(tempDir.resolve("a.csv"), original, StandardCharsets.UTF_8);

		assertThat(run(config(true), false, null)).isEqualTo(0);

		assertThat(status(1)).isEqualTo(DONE);
		assertThat(status(2)).isEqualTo(DONE);
		// .bak は1回だけ作る（元のファイルのまま）
		assertThat(Files.readString(tempDir.resolve("a.csv.bak"))).isEqualTo(original);
		List<String> lines = Files.readAllLines(tempDir.resolve("a.csv"));
		assertThat(lines.get(0)).isEqualTo(HEADER + ",最終同期日時,同期結果,エラー理由");
		assertThat(lines.get(1)).startsWith("1,タスク,A,,,完了,").endsWith(",更新,");
		assertThat(lines.get(2)).startsWith("2,タスク,A,B,,完了,").endsWith(",更新,");
		assertThat(lines.get(3)).startsWith("3,タスク,A,B,t1,完了,").endsWith(",変更なし,");
		assertThat(lines.get(4)).startsWith("100,タスク,A,B,t2,完了,").endsWith(",作成,");

		// 再実行ではすべて変更なし
		statusChanges.clear();
		assertThat(run(config(true), false, null)).isEqualTo(0);
		assertThat(statusChanges).isEmpty();
		assertThat(Files.readAllLines(tempDir.resolve("a.csv")).subList(1, 5))
				.allSatisfy(line -> assertThat(line).endsWith(",変更なし,"));
	}

	@Test
	@DisplayName("親チケットの自動完了に失敗した行は「エラー」と理由を書き、ステータス列は書き換えない")
	void autoCloseFailure_writesErrorToRow() throws IOException {
		redmine.put(1L, issue(1, "A", null, OPEN));
		redmine.put(2L, issue(2, "t1", 1L, DONE));
		csv(HEADER, "1,タスク,A,,,", "2,タスク,A,,t1,完了");
		Mockito.doThrow(new IllegalStateException("422 status invalid")).when(client).updateIssue(Mockito.eq(1L),
				anyMap());

		assertThat(run(config(true), false, null)).isEqualTo(1);

		List<String> lines = Files.readAllLines(tempDir.resolve("a.csv"));
		assertThat(lines.get(1)).startsWith("1,タスク,A,,,,").contains(",エラー,").contains("422 status invalid");
		assertThat(lines.get(2)).endsWith(",変更なし,");
	}

	@Test
	@DisplayName("Excelにない子チケット（Redmineだけのチケット）が未完了なら、親は完了にしない（進行中にする）")
	void redmineOnlyOpenChild_keepsParentOpen() throws IOException {
		redmine.put(1L, issue(1, "A", null, OPEN));
		redmine.put(2L, issue(2, "B", 1L, OPEN));
		redmine.put(3L, issue(3, "t1", 2L, DONE));
		csv(HEADER, "1,タスク,A,,,", "2,タスク,A,B,,", "3,タスク,A,B,t1,完了");
		// 論理削除の対象にならないよう、Excelにない子は別ファイル扱いにせず、論理削除を無効にした設定で確認する
		Path config = config(true).getParent().resolve("sync-config.yml");
		Files.writeString(config, Files.readString(config).replace("      deletion: { statusId: 99 }\n", ""));
		redmine.put(9L, issue(9, "手動", 2L, OPEN));

		int exit = run(config, false, null);

		assertThat(exit).isEqualTo(0);
		assertThat(status(2)).isEqualTo(IN_PROGRESS);
		assertThat(status(1)).isEqualTo(IN_PROGRESS);
	}

	@Test
	@DisplayName("子に進行中のチケットがあれば、新規の親を再帰的に進行中にし、ステータス列に書き戻す")
	void inProgressChild_startsParentsRecursively() throws IOException {
		redmine.put(1L, issue(1, "A", null, OPEN));
		redmine.put(2L, issue(2, "B", 1L, OPEN));
		redmine.put(3L, issue(3, "t1", 2L, OPEN));
		redmine.put(4L, issue(4, "t2", 2L, OPEN));
		redmine.put(5L, issue(5, "C", 1L, DONE));
		csv(HEADER, "1,タスク,A,,,", "2,タスク,A,B,,", "3,タスク,A,B,t1,進行中", "4,タスク,A,B,t2,", "5,タスク,A,C,,完了");

		int exit = run(config(true), false, null);

		assertThat(exit).isEqualTo(0);
		assertThat(status(2)).isEqualTo(IN_PROGRESS);
		assertThat(status(1)).isEqualTo(IN_PROGRESS);
		assertThat(status(4)).isEqualTo(OPEN);
		assertThat(Files.readString(tempDir.resolve("a.csv"))).contains("1,タスク,A,,,進行中")
				.contains("2,タスク,A,B,,進行中");

		statusChanges.clear();
		assertThat(run(config(true), false, null)).isEqualTo(0);
		assertThat(statusChanges).isEmpty();
	}

	@Test
	@DisplayName("inProgress: false なら親を進行中にしない。完了・進行中の親を新規に戻さない")
	void inProgressDisabled_andNoDowngrade() throws IOException {
		redmine.put(1L, issue(1, "A", null, OPEN));
		redmine.put(2L, issue(2, "t1", 1L, IN_PROGRESS));
		redmine.put(3L, issue(3, "B", null, IN_PROGRESS));
		redmine.put(4L, issue(4, "t2", 3L, OPEN));
		csv(HEADER, "1,タスク,A,,,", "2,タスク,A,,t1,進行中", "3,タスク,B,,,", "4,タスク,B,,t2,");
		Path config = config(false, "      autoCloseParents: { enabled: true, inProgress: false }");

		assertThat(run(config, false, null)).isEqualTo(0);
		assertThat(status(1)).isEqualTo(OPEN);

		Path enabled = config(true);
		assertThat(run(enabled, false, null)).isEqualTo(0);
		assertThat(status(1)).isEqualTo(IN_PROGRESS);
		assertThat(status(3)).isEqualTo(IN_PROGRESS);
	}

	@Test
	@DisplayName("Excelから消えて論理削除（終了ステータス）した子は完了として数える")
	void logicallyDeletedChild_countsAsClosed() throws IOException {
		redmine.put(1L, issue(1, "A", null, OPEN));
		redmine.put(2L, issue(2, "t1", 1L, DONE));
		redmine.put(3L, issue(3, "t2", 1L, OPEN));
		csv(HEADER, "1,タスク,A,,,", "2,タスク,A,,t1,完了");

		int exit = run(config(true), false, null);

		assertThat(exit).isEqualTo(0);
		assertThat(status(3)).isEqualTo(DELETE_STATUS);
		assertThat(status(1)).isEqualTo(DONE);
	}

	@Test
	@DisplayName("設定が無効（既定）なら親は完了にしない")
	void disabled_noAutoClose() throws IOException {
		redmine.put(1L, issue(1, "A", null, OPEN));
		redmine.put(2L, issue(2, "t1", 1L, DONE));
		csv(HEADER, "1,タスク,A,,,", "2,タスク,A,,t1,完了");

		int exit = run(config(false), false, null);

		assertThat(exit).isEqualTo(0);
		assertThat(status(1)).isEqualTo(OPEN);
		verify(client, never()).listClosedIssueStatusIds();
	}

	@Test
	@DisplayName("dry-run では、作成予定のチケットも含めて完了にする親をログに出すだけで、変更・書き戻しはしない")
	void dryRun_logsOnly() throws IOException {
		redmine.put(1L, issue(1, "A", null, OPEN));
		redmine.put(2L, issue(2, "B", 1L, OPEN));
		redmine.put(3L, issue(3, "t1", 2L, OPEN));
		Path file = csv(HEADER, "1,タスク,A,,,", "2,タスク,A,B,,", "3,タスク,A,B,t1,完了", ",タスク,A,B,t2,完了");
		String before = Files.readString(file);

		int exit = run(config(true), true, null);

		assertThat(exit).isEqualTo(0);
		verify(client, never()).createIssue(anyMap());
		verify(client, never()).updateIssue(anyLong(), anyMap());
		assertThat(Files.readString(file)).isEqualTo(before);
		assertThat(logText()).contains("DRY_RUN AUTO_CLOSE #2 「B」").contains("DRY_RUN AUTO_CLOSE #1 「A」");
	}

	@Test
	@DisplayName("ルートチケットは子がすべて完了しても完了にしない")
	void rootIssue_notClosed() throws IOException {
		redmine.put(ROOT, issue(ROOT, "ルート", null, OPEN));
		redmine.put(1L, issue(1, "A", ROOT, OPEN));
		redmine.put(2L, issue(2, "t1", 1L, DONE));
		csv(HEADER, "1,タスク,A,,,", "2,タスク,A,,t1,完了");

		int exit = run(config(true), false, ROOT);

		assertThat(exit).isEqualTo(0);
		assertThat(status(1)).isEqualTo(DONE);
		assertThat(status(ROOT)).isEqualTo(OPEN);
	}

	@Test
	@DisplayName("ステータス列がなくても親を完了にし、空欄のステータスで開き直さない")
	void noStatusColumn_closesAndKeepsClosed() throws IOException {
		redmine.put(1L, issue(1, "A", null, OPEN));
		redmine.put(2L, issue(2, "t1", 1L, DONE));
		Path file = csv("チケットID,トラッカー,大分類,中分類,タスク,完了実績", "1,タスク,A,,,", "2,タスク,A,,t1,2026/10/01");
		Path config = config(true);
		Files.writeString(config, Files.readString(config).replace("hierarchy: [大分類, 中分類, タスク]",
				"hierarchy: [大分類, 中分類, タスク]\n        dueActualColumn: 完了実績"));

		assertThat(run(config, false, null)).isEqualTo(0);
		assertThat(status(1)).isEqualTo(DONE);
		assertThat(Files.readString(file)).doesNotContain("完了\n");

		statusChanges.clear();
		assertThat(run(config, false, null)).isEqualTo(0);
		assertThat(statusChanges).isEmpty();
	}

	@Test
	@DisplayName("完了にするステータス: 既定は statusMap の「完了」、設定した名前・ID、終了ステータスでなければエラー")
	void resolveTarget() {
		StatusConfig statusConfig = new StatusConfig();
		statusConfig.setStatusMap(Map.of("完了", "5"));
		Map<String, Long> statuses = new LinkedHashMap<>();
		statuses.put("新規", 1L);
		statuses.put("進行中", 2L);
		statuses.put("終了", 5L);
		statuses.put("却下", 99L);
		Set<Long> closed = Set.of(5L, 99L);

		assertThat(ParentAutoCloser.resolveTarget(new AutoCloseParentsConfig(), statusConfig, statuses, closed))
				.isEqualTo(new ParentAutoCloser.TargetStatus(5L, "完了"));
		assertThat(ParentAutoCloser.resolveTarget(new AutoCloseParentsConfig(), null, statuses, closed))
				.isEqualTo(new ParentAutoCloser.TargetStatus(5L, "終了"));
		AutoCloseParentsConfig byName = new AutoCloseParentsConfig();
		byName.setStatus("却下");
		assertThat(ParentAutoCloser.resolveTarget(byName, statusConfig, statuses, closed))
				.isEqualTo(new ParentAutoCloser.TargetStatus(99L, "却下"));
		AutoCloseParentsConfig open = new AutoCloseParentsConfig();
		open.setStatus("新規");
		assertThatThrownBy(() -> ParentAutoCloser.resolveTarget(open, statusConfig, statuses, closed))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("終了したチケット扱い");

		assertThat(ParentAutoCloser.newStatusId(statuses, closed)).isEqualTo(1L);
		assertThat(ParentAutoCloser.resolveInProgress(new AutoCloseParentsConfig(), statusConfig, statuses, closed))
				.isEqualTo(new ParentAutoCloser.TargetStatus(2L, "進行中"));
		AutoCloseParentsConfig closedInProgress = new AutoCloseParentsConfig();
		closedInProgress.setInProgressStatus("終了");
		assertThatThrownBy(() -> ParentAutoCloser.resolveInProgress(closedInProgress, statusConfig, statuses, closed))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("設定 autoCloseParents を読み込む")
	void config_parsed() throws IOException {
		Path config = config(false,
				"      autoCloseParents: { enabled: true, status: 完了, inProgressStatus: 進行中, inProgress: false }");
		SyncConfigService service = new SyncConfigService(new SyncConfigProperties());
		service.loadConfig(config.toString());

		AutoCloseParentsConfig autoClose = service.getProjectByName("t").orElseThrow().getSync().getAutoCloseParents();
		assertThat(autoClose.isEnabled()).isTrue();
		assertThat(autoClose.getStatus()).isEqualTo("完了");
		assertThat(autoClose.getInProgressStatus()).isEqualTo("進行中");
		assertThat(autoClose.isInProgress()).isFalse();
		assertThat(new AutoCloseParentsConfig().isInProgress()).isTrue();
	}
}
