package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import mozaki.redmineUpster.config.SyncConfigProperties.ColumnsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.RedmineConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.SyncConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.TrackerConfig;
import mozaki.redmineUpster.domain.IssueLinkEntity;
import mozaki.redmineUpster.repository.IssueLinkRepository;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet;

class DiffCalculatorTests {

	private static final List<String> HEADERS = List.of(
			"チケットID", "トラッカー", "L1", "L2", "L3", "開始日", "期限", "状態", "進捗率");

	private final IssueLinkRepository repository = Mockito.mock(IssueLinkRepository.class);
	private final DiffCalculator calculator = new DiffCalculator(repository);

	private static ProjectConfig projectConfig() {
		ColumnsConfig columns = new ColumnsConfig();
		columns.setHierarchy(List.of("L1", "L2", "L3"));
		columns.setStartDateColumn("開始日");
		columns.setDueDateColumn("期限");
		columns.setStatusColumn("状態");
		columns.setProgressColumn("進捗率");

		StatusConfig statusConfig = new StatusConfig();
		statusConfig.setEnabled(true);

		SyncConfig syncConfig = new SyncConfig();
		syncConfig.setColumns(columns);
		syncConfig.setStatus(statusConfig);
		syncConfig.setTrackerMap(Map.of("タスク", "2", "サマリ", "6"));

		RedmineConfig redmineConfig = new RedmineConfig();
		redmineConfig.setProjectId("proj");

		ProjectConfig projectConfig = new ProjectConfig();
		projectConfig.setSync(syncConfig);
		projectConfig.setRedmine(redmineConfig);
		return projectConfig;
	}

	/** id, tracker, L1, L2, L3 の順で行を作る */
	private static Map<String, String> row(String id, String tracker, String l1, String l2, String l3) {
		Map<String, String> row = new LinkedHashMap<>();
		for (String header : HEADERS) {
			row.put(header, "");
		}
		row.put("チケットID", id);
		row.put("トラッカー", tracker);
		row.put("L1", l1);
		row.put("L2", l2);
		row.put("L3", l3);
		return row;
	}

	private static ParsedSheet sheet(List<Map<String, String>> rows) {
		List<Integer> numbers = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			numbers.add(i + 2);
		}
		return new ParsedSheet(HEADERS, rows, numbers);
	}

	private DiffPlan calculate(ProjectConfig config, List<Map<String, String>> rows) {
		return calculator.calculate(sheet(rows), config, new TrackerResolver(config.getSync().getTrackerMap(), null),
				null);
	}

	private static DiffItem byRow(DiffPlan plan, int rowNumber) {
		return plan.items().stream().filter(i -> i.rowNumber() == rowNumber).findFirst().orElseThrow();
	}

	@Test
	@DisplayName("チケットIDが空欄なら新規作成、値があれば更新になる")
	void calculate_createOrUpdateByTicketId() {
		DiffPlan plan = calculate(projectConfig(), List.of(
				row("10", "サマリ", "A", "", ""),
				row("", "タスク", "A", "B", ""),
				row("#11", "タスク", "A", "C", "")));

		assertThat(plan.errors()).isEmpty();
		assertThat(byRow(plan, 2).action()).isEqualTo("UPDATE");
		assertThat(byRow(plan, 2).issueId()).isEqualTo(10L);
		assertThat(byRow(plan, 3).action()).isEqualTo("CREATE");
		assertThat(byRow(plan, 3).issueId()).isNull();
		assertThat(byRow(plan, 4).action()).isEqualTo("UPDATE");
		assertThat(byRow(plan, 4).issueId()).isEqualTo(11L);
	}

	@Test
	@DisplayName("親は階層列から決まり、親→子の順に並ぶ（途中の空欄列も扱える）")
	void calculate_resolvesParentFromHierarchy() {
		DiffPlan plan = calculate(projectConfig(), List.of(
				row("", "タスク", "A", "B", "C"),
				row("", "タスク", "A", "", "X"),
				row("", "サマリ", "A", "B", ""),
				row("", "サマリ", "A", "", "")));

		assertThat(plan.errors()).isEmpty();
		assertThat(plan.items()).extracting(DiffItem::rowNumber).containsExactly(5, 4, 2, 3);
		assertThat(byRow(plan, 5).parentRowNumber()).isNull();
		assertThat(byRow(plan, 4).parentRowNumber()).isEqualTo(5);
		assertThat(byRow(plan, 2).parentRowNumber()).isEqualTo(4);
		// A > (空) > X の親は A
		assertThat(byRow(plan, 3).parentRowNumber()).isEqualTo(5);
		assertThat(byRow(plan, 2).levelPath()).isEqualTo("A > B > C");
		assertThat(byRow(plan, 2).subject()).isEqualTo("C");
	}

	@Test
	@DisplayName("親行がファイルにない場合は行番号と階層パス付きの検証エラーになる")
	void calculate_missingParentIsError() {
		DiffPlan plan = calculate(projectConfig(), List.of(
				row("", "サマリ", "A", "", ""),
				row("", "タスク", "A", "B", "C")));

		assertThat(plan.hasErrors()).isTrue();
		assertThat(plan.errors()).singleElement().asString()
				.contains("行3")
				.contains("A > B > C")
				.contains("親行 [A > B]");
	}

	@Test
	@DisplayName("同じ階層パスの行が2つあると検証エラーになる")
	void calculate_duplicatePathIsError() {
		DiffPlan plan = calculate(projectConfig(), List.of(
				row("", "サマリ", "A", "", ""),
				row("", "タスク", "A", "B", ""),
				row("", "タスク", "A", "B", "")));

		assertThat(plan.errors()).singleElement().asString()
				.contains("行4")
				.contains("行3と重複");
	}

	@Test
	@DisplayName("チケットIDが数値でない・重複している場合は検証エラーになる")
	void calculate_invalidOrDuplicateTicketIdIsError() {
		DiffPlan plan = calculate(projectConfig(), List.of(
				row("abc", "サマリ", "A", "", ""),
				row("20", "タスク", "B", "", ""),
				row("20", "タスク", "C", "", "")));

		assertThat(plan.errors()).hasSize(2);
		assertThat(plan.errors().get(0)).contains("行2").contains("数値ではありません");
		assertThat(plan.errors().get(1)).contains("行4").contains("チケットID 20").contains("行3");
	}

	@Test
	@DisplayName("階層列がすべて空の行は検証エラーになる")
	void calculate_rowWithoutHierarchyIsError() {
		DiffPlan plan = calculate(projectConfig(), List.of(row("", "タスク", "", "", "")));

		assertThat(plan.errors()).singleElement().asString().contains("行2").contains("階層列");
	}

	@Test
	@DisplayName("トラッカー名は trackerMap → Redmine の順で解決し、不明な名前はエラーになる")
	void calculate_resolvesTrackerNames() {
		RedmineClient client = Mockito.mock(RedmineClient.class);
		when(client.listTrackers()).thenReturn(Map.of("バグ", 1L, "機能", 3L));
		ProjectConfig config = projectConfig();
		TrackerResolver resolver = new TrackerResolver(config.getSync().getTrackerMap(), client);

		DiffPlan plan = calculator.calculate(sheet(List.of(
				row("", "サマリ", "A", "", ""),
				row("", "バグ", "A", "B", ""),
				row("", "機能", "A", "C", ""),
				row("", "5", "A", "D", ""),
				row("", "存在しない", "A", "E", ""))), config, resolver, null);

		assertThat(byRow(plan, 2).trackerId()).isEqualTo(6L);
		assertThat(byRow(plan, 3).trackerId()).isEqualTo(1L);
		assertThat(byRow(plan, 4).trackerId()).isEqualTo(3L);
		assertThat(byRow(plan, 5).trackerId()).isEqualTo(5L);
		assertThat(plan.errors()).singleElement().asString().contains("行6").contains("存在しない");
		// Redmineへの問い合わせは1回だけ（キャッシュ）
		verify(client, times(1)).listTrackers();
	}

	@Test
	@DisplayName("trackerMapで解決できる場合はRedmineに問い合わせない")
	void calculate_trackerMapDoesNotCallRedmine() {
		RedmineClient client = Mockito.mock(RedmineClient.class);
		ProjectConfig config = projectConfig();
		TrackerResolver resolver = new TrackerResolver(config.getSync().getTrackerMap(), client);

		DiffPlan plan = calculator.calculate(sheet(List.of(row("", "タスク", "A", "", ""))), config, resolver, null);

		assertThat(plan.errors()).isEmpty();
		verify(client, never()).listTrackers();
	}

	@Test
	@DisplayName("トラッカー空欄は sync.tracker の既定値を使い、既定値がなければ新規はエラー・更新は変更しない")
	void calculate_blankTrackerFallback() {
		ProjectConfig noDefault = projectConfig();
		DiffPlan plan = calculate(noDefault, List.of(
				row("10", "", "A", "", ""),
				row("", "", "B", "", "")));
		assertThat(byRow(plan, 2).trackerId()).isNull();
		assertThat(plan.errors()).singleElement().asString().contains("行3").contains("トラッカー");

		ProjectConfig withDefault = projectConfig();
		TrackerConfig trackerConfig = new TrackerConfig();
		trackerConfig.setEnabled(true);
		trackerConfig.setValue("タスク");
		withDefault.getSync().setTracker(trackerConfig);
		DiffPlan planWithDefault = calculate(withDefault, List.of(row("", "", "B", "", "")));
		assertThat(planWithDefault.errors()).isEmpty();
		assertThat(byRow(planWithDefault, 2).trackerId()).isEqualTo(2L);
	}

	@Test
	@DisplayName("日付・ステータス・進捗率の列設定は従来どおり反映される")
	void calculate_keepsDateStatusProgress() {
		Map<String, String> row = row("", "タスク", "A", "", "");
		row.put("開始日", "2026-1-3");
		row.put("期限", "2026-1-10");
		row.put("状態", "進行中");
		row.put("進捗率", "40%");

		DiffItem item = calculate(projectConfig(), List.of(row)).items().get(0);

		assertThat(item.status()).isEqualTo("進行中");
		assertThat(item.payload().get("startDate")).isEqualTo("2026-1-3");
		assertThat(item.payload().get("dueDate")).isEqualTo("2026-1-10");
		assertThat(item.payload().get("progress")).isEqualTo(40);
	}

	@Test
	@DisplayName("論理削除候補は同じプロジェクトのissue_linkのうちExcelにないチケット")
	void findLogicalDeleteCandidates_selectsMissingIssues() {
		when(repository.findAllByProjectId("proj")).thenReturn(List.of(
				new IssueLinkEntity(10L, "proj"),
				new IssueLinkEntity(30L, "proj"),
				new IssueLinkEntity(20L, "proj")));

		assertThat(calculator.findLogicalDeleteCandidates(Set.of(10L, 99L), "proj")).containsExactly(20L, 30L);
		assertThat(calculator.findLogicalDeleteCandidates(Set.of(), " ")).isEmpty();
	}

	@Test
	@DisplayName("旧形式（Lv.xx列）の階層も自動判定できる")
	void calculate_detectsLegacyHierarchyColumns() {
		SyncConfig syncConfig = new SyncConfig();
		syncConfig.setTrackerMap(Map.of("タスク", "2"));
		ProjectConfig config = new ProjectConfig();
		config.setSync(syncConfig);

		Map<String, String> parent = new LinkedHashMap<>();
		parent.put("トラッカー", "タスク");
		parent.put("Lv.01", "新eD拡張");
		parent.put("Lv.02", "");
		Map<String, String> child = new LinkedHashMap<>(parent);
		child.put("Lv.02", "業務共通");

		DiffPlan plan = calculator.calculate(new ParsedSheet(List.of("トラッカー", "Lv.01", "Lv.02"),
				List.of(parent, child)), config, new TrackerResolver(syncConfig.getTrackerMap(), null), null);

		assertThat(plan.errors()).isEmpty();
		assertThat(byRow(plan, 3).parentRowNumber()).isEqualTo(2);
		assertThat(byRow(plan, 3).levelPath()).isEqualTo("新eD拡張 > 業務共通");
	}
}
