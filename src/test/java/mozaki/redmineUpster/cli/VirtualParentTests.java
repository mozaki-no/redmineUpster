package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.ColumnsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.RedmineConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.SyncConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.VirtualParentsConfig;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet;
import mozaki.redmineUpster.service.SyncConfigService;

/**
 * 仮想親チケット（ファイルに行がない祖先の自動作成）のテスト。
 */
class VirtualParentTests {

	private static final List<String> HEADERS = List.of(
			"チケットID", "トラッカー", "L1", "L2", "L3", "L4", "開始日", "期限", "進捗率");

	@TempDir
	Path tempDir;

	private FileLogger logger;
	private RedmineClient client;
	/** 疑似Redmineのチケット（チケットID → チケット情報） */
	private Map<Long, Map<String, Object>> redmine;
	private final AtomicLong nextId = new AtomicLong(100);
	private final DiffCalculator calculator = new DiffCalculator();
	private final SyncExecutor executor = new SyncExecutor();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() throws IOException {
		logger = new FileLogger(tempDir.resolve("logs").toString());
		redmine = new LinkedHashMap<>();
		client = Mockito.mock(RedmineClient.class);
		when(client.getProjectId()).thenReturn("proj");
		when(client.getBaseUrl()).thenReturn("http://redmine.local");
		// 作成したチケットを疑似Redmineに保存する（再実行の確認用）
		when(client.createIssue(anyMap())).thenAnswer(invocation -> {
			Map<String, Object> payload = invocation.getArgument(0);
			long id = nextId.getAndIncrement();
			redmine.put(id, toIssue(id, payload));
			return id;
		});
	}

	@AfterEach
	void tearDown() {
		logger.close();
	}

	private static Map<String, Object> toIssue(long id, Map<String, Object> payload) {
		Map<String, Object> issue = new HashMap<>();
		issue.put("id", id);
		issue.put("subject", payload.get("subject"));
		issue.put("tracker", Map.of("id", ((Number) payload.get("tracker_id")).longValue()));
		issue.put("status", Map.of("id", 1L));
		Object parent = payload.get("parent_issue_id");
		if (parent instanceof Number number) {
			issue.put("parent", Map.of("id", number.longValue()));
		}
		for (String key : List.of("start_date", "due_date", "done_ratio", "description")) {
			if (payload.get(key) != null) {
				issue.put(key, payload.get(key));
			}
		}
		return issue;
	}

	private static ProjectConfig config(boolean virtualEnabled, String virtualTracker) {
		ColumnsConfig columns = new ColumnsConfig();
		columns.setHierarchy(List.of("L1", "L2", "L3", "L4"));
		columns.setStartDateColumn("開始日");
		columns.setDueDateColumn("期限");
		columns.setProgressColumn("進捗率");
		StatusConfig status = new StatusConfig();
		status.setEnabled(true);
		SyncConfig sync = new SyncConfig();
		sync.setColumns(columns);
		sync.setStatus(status);
		sync.setTrackerMap(Map.of("タスク", "2", "サマリ", "6", "機能", "3"));
		VirtualParentsConfig virtualParents = new VirtualParentsConfig();
		virtualParents.setEnabled(virtualEnabled);
		virtualParents.setTracker(virtualTracker);
		sync.setVirtualParents(virtualParents);
		RedmineConfig redmineConfig = new RedmineConfig();
		redmineConfig.setProjectId("proj");
		ProjectConfig project = new ProjectConfig();
		project.setSync(sync);
		project.setRedmine(redmineConfig);
		return project;
	}

	/** id, tracker, L1..L4, 開始日, 期限, 進捗率 */
	private static Map<String, String> row(String... values) {
		Map<String, String> row = new LinkedHashMap<>();
		for (int i = 0; i < HEADERS.size(); i++) {
			row.put(HEADERS.get(i), i < values.length ? values[i] : "");
		}
		return row;
	}

	private static ParsedSheet sheet(List<Map<String, String>> rows) {
		List<Integer> numbers = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			numbers.add(i + 2);
		}
		return new ParsedSheet(HEADERS, rows, numbers);
	}

	private DiffPlan plan(ProjectConfig config, List<Map<String, String>> rows) {
		return calculator.calculate(sheet(rows), config,
				new TrackerResolver(config.getSync().getTrackerMap(), null), null);
	}

	private static DiffItem virtual(DiffPlan plan, String path) {
		return plan.items().stream().filter(i -> i.virtual() && i.levelPath().equals(path)).findFirst()
				.orElseThrow(() -> new AssertionError("仮想親がない: " + path + " in " + plan.items()));
	}

	/** SyncRunner と同じ流れ（対応付け → 論理削除候補 → 実行）で同期する */
	private SyncResult sync(DiffPlan plan, Integer deleteStatusId, boolean dryRun) {
		assertThat(plan.errors()).isEmpty();
		Map<Long, Map<String, Object>> fetched = new LinkedHashMap<>();
		redmine.forEach((id, issue) -> fetched.put(id, new HashMap<>(issue)));
		List<DiffItem> items = VirtualParentMatcher.match(plan.items(), fetched, deleteStatusId, logger);
		Set<Long> ids = new HashSet<>();
		items.stream().filter(i -> i.issueId() != null).forEach(i -> ids.add(i.issueId()));
		List<Long> deletes = DiffCalculator.findLogicalDeleteCandidates(ids, fetched, deleteStatusId);
		ProjectConfig config = config(true, null);
		if (deleteStatusId != null) {
			SyncConfigProperties.DeletionConfig deletion = new SyncConfigProperties.DeletionConfig();
			deletion.setStatusId(deleteStatusId);
			config.getSync().setDeletion(deletion);
		}
		return executor.execute(items, fetched, deletes, config, client, dryRun, logger, false);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private List<Map<String, Object>> capturedCreates(int count) {
		ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
		verify(client, times(count)).createIssue(captor.capture());
		return (List) captor.getAllValues();
	}

	@Test
	@DisplayName("モード無効（既定）なら親行がない行は従来どおり検証エラー")
	void disabled_missingParentIsError() {
		DiffPlan plan = plan(config(false, null), List.of(row("", "タスク", "A", "B")));
		assertThat(plan.hasErrors()).isTrue();
		assertThat(plan.errors().get(0)).contains("親行 [A] がファイルにありません").contains("--virtual-parents");
		assertThat(plan.items()).isEmpty();
	}

	@Test
	@DisplayName("親行が1つ欠けている → 仮想親を作り、子の親にする。子のIDだけ書き戻し対象")
	void missingSingleParent() {
		DiffPlan plan = plan(config(true, null), List.of(
				row("", "タスク", "A", "B", "", "", "2026/1/5", "2026/1/10", "40"),
				row("", "タスク", "A", "C", "", "", "2026/1/3", "2026/1/8", "80")));
		DiffItem a = virtual(plan, "A");
		assertThat(a.subject()).isEqualTo("A");
		assertThat(a.trackerId()).isEqualTo(6L); // 既定「サマリ」
		assertThat(a.parentRowNumber()).isNull();
		assertThat(a.rowNumber()).isNegative();
		assertThat(a.payload()).containsEntry("startDate", "2026-01-03").containsEntry("dueDate", "2026-01-10")
				.containsEntry("progress", 60);
		assertThat(plan.items()).filteredOn(i -> !i.virtual())
				.allSatisfy(i -> assertThat(i.parentRowNumber()).isEqualTo(a.rowNumber()));
		assertThat(plan.items().get(0).virtual()).isTrue(); // 親→子の順

		SyncResult result = sync(plan, null, false);
		assertThat(result.errorCount()).isZero();
		List<Map<String, Object>> creates = capturedCreates(3);
		assertThat(creates.get(0)).containsEntry("subject", "A").containsEntry("tracker_id", 6L)
				.containsEntry("start_date", "2026-01-03").containsEntry("due_date", "2026-01-10")
				.containsEntry("done_ratio", 60).containsEntry("description", VirtualParentMatcher.MARKER)
				.doesNotContainKey("status_id");
		assertThat(creates.get(1)).containsEntry("parent_issue_id", 100L).containsKey("status_id")
				.doesNotContainKey("description");
		assertThat(result.createdIssueIds()).containsOnlyKeys(2, 3).doesNotContainValue(100L);
	}

	@Test
	@DisplayName("2階層続けて欠けている → 最上位から作成し、作成したIDを次の階層へ渡す")
	void missingChain() {
		DiffPlan plan = plan(config(true, "機能"), List.of(row("", "タスク", "A", "B", "C", "", "2026-02-01",
				"2026-02-05")));
		DiffItem a = virtual(plan, "A");
		DiffItem b = virtual(plan, "A > B");
		assertThat(b.parentRowNumber()).isEqualTo(a.rowNumber());
		assertThat(b.trackerId()).isEqualTo(3L);
		assertThat(a.payload()).containsEntry("startDate", "2026-02-01").containsEntry("dueDate", "2026-02-05")
				.doesNotContainKey("progress");

		SyncResult result = sync(plan, null, false);
		assertThat(result.errorCount()).isZero();
		List<Map<String, Object>> creates = capturedCreates(3);
		assertThat(creates.get(0)).containsEntry("subject", "A").containsEntry("parent_issue_id", "");
		assertThat(creates.get(1)).containsEntry("subject", "B").containsEntry("parent_issue_id", 100L);
		assertThat(creates.get(2)).containsEntry("subject", "C").containsEntry("parent_issue_id", 101L);
		assertThat(result.createdIssueIds()).containsExactly(Map.entry(2, 102L));
	}

	@Test
	@DisplayName("ファイルにある行は仮想親より優先し、途中の祖先だけを仮想親にする")
	void existingRowsWin() {
		DiffPlan plan = plan(config(true, null), List.of(
				row("10", "サマリ", "A"),
				row("", "タスク", "A", "B", "C")));
		assertThat(plan.items()).filteredOn(DiffItem::virtual).extracting(DiffItem::levelPath)
				.containsExactly("A > B");
		assertThat(virtual(plan, "A > B").parentRowNumber()).isEqualTo(2);
	}

	@Test
	@DisplayName("階層を飛ばした行の祖先は、飛ばした階層を空欄のまま仮想親にする")
	void skipLevelAncestor() {
		// A > (飛ばし) > C > D の親 [A, "", C] がない → 仮想親 "A > C"（L3 の値）、その親は A の行
		DiffPlan plan = plan(config(true, null), List.of(
				row("", "サマリ", "A"),
				row("", "タスク", "A", "", "C", "D")));
		DiffItem c = virtual(plan, "A > C");
		assertThat(c.depth()).isEqualTo(2);
		assertThat(c.subject()).isEqualTo("C");
		assertThat(c.parentRowNumber()).isEqualTo(2);
		assertThat(plan.items()).filteredOn(DiffItem::virtual).hasSize(1);
	}

	@Test
	@DisplayName("仮想親のトラッカーが解決できなければ検証エラー")
	void unresolvedTrackerIsError() {
		DiffPlan plan = plan(config(true, "存在しない"), List.of(row("", "タスク", "A", "B")));
		assertThat(plan.errors()).anySatisfy(e -> assertThat(e).contains("仮想親チケットのトラッカー「存在しない」"));
	}

	@Test
	@DisplayName("再実行: 既存の仮想親を親・件名・トラッカーで見つけ直し、作り直さず、変更なしならスキップ")
	void rerunMatchesExistingVirtualParents() {
		List<Map<String, String>> rows = List.of(
				row("", "タスク", "A", "B", "C", "", "2026-02-01", "2026-02-05", "50"));
		SyncResult first = sync(plan(config(true, null), rows), null, false);
		assertThat(first.errorCount()).isZero();
		capturedCreates(3);
		Long taskId = first.createdIssueIds().get(2);

		// 2回目: 書き戻されたチケットIDあり
		List<Map<String, String>> rows2 = List.of(
				row(String.valueOf(taskId), "タスク", "A", "B", "C", "", "2026-02-01", "2026-02-05", "50"));
		DiffPlan plan2 = plan(config(true, null), rows2);
		List<DiffItem> matched = VirtualParentMatcher.match(plan2.items(), redmine, null, logger);
		assertThat(matched).filteredOn(DiffItem::virtual).extracting(DiffItem::issueId).containsExactly(100L, 101L);
		assertThat(matched).filteredOn(DiffItem::virtual).extracting(DiffItem::action).containsOnly("UPDATE");

		SyncResult second = sync(plan2, 6, false);
		assertThat(second.errorCount()).isZero();
		verify(client, times(3)).createIssue(anyMap()); // 増えていない
		verify(client, never()).updateIssue(anyLong(), anyMap()); // 全件スキップ・論理削除なし
	}

	@Test
	@DisplayName("再実行: 子の日付が変わると仮想親の日付も更新する")
	void rerunUpdatesAggregatedDates() {
		SyncResult first = sync(plan(config(true, null), List.of(
				row("", "タスク", "A", "B", "", "", "2026-02-01", "2026-02-05"))), null, false);
		Long taskId = first.createdIssueIds().get(2);
		sync(plan(config(true, null), List.of(
				row(String.valueOf(taskId), "タスク", "A", "B", "", "", "2026-02-01", "2026-02-20"))), null, false);
		verify(client).updateIssue(Mockito.eq(100L), Mockito.argThat(p -> "2026-02-20".equals(p.get("due_date"))));
	}

	@Test
	@DisplayName("対応付け: 目印付きを優先し、同じ条件なら最小ID。別の親・別トラッカー・Excelの行のID・論理削除済みは使わない")
	void matchingRules() {
		DiffPlan plan = plan(config(true, null), List.of(row("50", "タスク", "A", "B")));
		Map<Long, Map<String, Object>> issues = new LinkedHashMap<>();
		issues.put(50L, issueOf(50, "B", 6, null, null));
		issues.put(20L, issueOf(20, "A", 6, 99L, null)); // 親あり → 最上位ではない
		issues.put(21L, issueOf(21, "A", 2, null, null)); // トラッカー違い
		issues.put(22L, issueOf(22, "A", 6, null, null));
		issues.put(30L, issueOf(30, "A", 6, null, VirtualParentMatcher.MARKER));
		List<DiffItem> matched = VirtualParentMatcher.match(plan.items(), issues, null, logger);
		assertThat(matched).filteredOn(DiffItem::virtual).extracting(DiffItem::issueId).containsExactly(30L);

		issues.remove(30L);
		issues.put(23L, issueOf(23, "A", 6, null, null));
		matched = VirtualParentMatcher.match(plan.items(), issues, null, logger);
		assertThat(matched).filteredOn(DiffItem::virtual).extracting(DiffItem::issueId).containsExactly(22L);

		// 論理削除ステータスのチケットは使わない
		issues.put(22L, issueOf(22, "A", 6, null, null));
		issues.get(22L).put("status", Map.of("id", 9L));
		matched = VirtualParentMatcher.match(plan.items(), issues, 9, logger);
		assertThat(matched).filteredOn(DiffItem::virtual).extracting(DiffItem::issueId).containsExactly(23L);
	}

	@Test
	@DisplayName("論理削除: 今回も必要な仮想親は候補にせず、子がなくなった仮想親は候補にする")
	void logicalDeleteOfOrphanedVirtualParents() {
		SyncResult first = sync(plan(config(true, null), List.of(
				row("", "タスク", "A", "B"),
				row("", "タスク", "X", "Y"))), null, false);
		assertThat(first.errorCount()).isZero();
		// 作成順: A=100, X=101, B=102, Y=103
		Long b = first.createdIssueIds().get(2);
		// 2回目: X > Y の行を削除
		SyncResult second = sync(plan(config(true, null), List.of(row(String.valueOf(b), "タスク", "A", "B"))), 6,
				false);
		assertThat(second.errorCount()).isZero();
		verify(client, never()).updateIssue(Mockito.eq(100L), anyMap());
		verify(client).updateIssue(Mockito.eq(101L), Mockito.argThat(p -> Long.valueOf(6).equals(p.get("status_id"))));
		verify(client).updateIssue(Mockito.eq(103L), Mockito.argThat(p -> Long.valueOf(6).equals(p.get("status_id"))));
	}

	@Test
	@DisplayName("dry-run: 仮想親は作成予定として表示し、子も親の作成予定を前提に成功扱い")
	void dryRun() throws IOException {
		SyncResult result = sync(plan(config(true, null), List.of(row("", "タスク", "A", "B", "C"))), null, true);
		assertThat(result.errorCount()).isZero();
		assertThat(result.totalCount()).isEqualTo(3);
		verify(client, never()).createIssue(anyMap());
		logger.close();
		String log;
		try (var files = Files.list(tempDir.resolve("logs"))) {
			log = Files.readString(files.findFirst().orElseThrow());
		}
		assertThat(log).contains("DRY_RUN CREATE (仮想親) [A]").contains("DRY_RUN CREATE (仮想親) [A > B]")
				.contains("parent=(仮想親)[A > B]");
	}

	@Test
	@DisplayName("設定 sync.virtualParents（enabled / trackerId）を読み込む")
	void configParsing() throws IOException {
		Path file = tempDir.resolve("sync-config.yml");
		Files.writeString(file, String.join("\n",
				"projects:",
				"  - name: t",
				"    default: true",
				"    redmine: { baseUrl: 'http://x', apiKey: k, projectId: p }",
				"    sync:",
				"      virtualParents:",
				"        enabled: true",
				"        trackerId: サマリ",
				""));
		SyncConfigService service = new SyncConfigService(new SyncConfigProperties());
		service.loadConfig(file.toString());
		ProjectConfig project = service.getDefaultProject().orElseThrow();
		assertThat(DiffCalculator.isVirtualParentsEnabled(project)).isTrue();
		assertThat(project.getSync().getVirtualParents().getTracker()).isEqualTo("サマリ");
	}

	@Test
	@DisplayName("CLI: --virtual-parents / --no-virtual-parents は設定より優先（どちらもなければ null＝設定に従う）")
	void cliFlags() {
		assertThat(SyncCommand.parseVirtualParents(new String[] { "--sync", "--file=a.csv" })).isNull();
		assertThat(SyncCommand.parseVirtualParents(new String[] { "--virtual-parents" })).isTrue();
		assertThat(SyncCommand.parseVirtualParents(new String[] { "--no-virtual-parents" })).isFalse();
		// 設定で有効でも、計算時に false を渡せば従来どおりエラー
		DiffPlan plan = calculator.calculate(sheet(List.of(row("", "タスク", "A", "B"))), config(true, null),
				new TrackerResolver(Map.of("タスク", "2", "サマリ", "6"), null), null, false);
		assertThat(plan.hasErrors()).isTrue();
		// 設定で無効でも、true を渡せば仮想親を作る
		plan = calculator.calculate(sheet(List.of(row("", "タスク", "A", "B"))), config(false, null),
				new TrackerResolver(Map.of("タスク", "2", "サマリ", "6"), null), null, true);
		assertThat(plan.errors()).isEmpty();
		assertThat(plan.items()).anyMatch(DiffItem::virtual);
	}

	private static Map<String, Object> issueOf(long id, String subject, long trackerId, Long parentId,
			String description) {
		Map<String, Object> issue = new HashMap<>();
		issue.put("id", id);
		issue.put("subject", subject);
		issue.put("tracker", Map.of("id", trackerId));
		issue.put("status", Map.of("id", 1L));
		if (parentId != null) {
			issue.put("parent", Map.of("id", parentId));
		}
		if (description != null) {
			issue.put("description", description);
		}
		return issue;
	}
}
