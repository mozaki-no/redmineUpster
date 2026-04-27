package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import mozaki.redmineUpster.config.SyncConfigProperties.ColumnsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.RedmineConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.SyncConfig;
import mozaki.redmineUpster.domain.IssueLinkEntity;
import mozaki.redmineUpster.repository.IssueLinkRepository;

class DiffCalculatorTests {

	@Test
	@DisplayName("親パスが見つからない場合は外部キーから親キーを推定する")
	void calculate_inferParentKeyFromExternalKey() {
		IssueLinkRepository repository = Mockito.mock(IssueLinkRepository.class);
		when(repository.findByExternalKey(Mockito.anyString())).thenReturn(Optional.empty());
		when(repository.findAll()).thenReturn(List.of());
		DiffCalculator calculator = new DiffCalculator(repository);

		ColumnsConfig columns = new ColumnsConfig();
		columns.setExternalKeyColumn("WBS_ID");
		columns.setHierarchy(List.of("レベル1", "レベル2"));
		columns.setStartDateColumn("開始日");
		columns.setDueDateColumn("期限");
		columns.setStatusColumn("状態");
		columns.setProgressColumn("進捗率");

		StatusConfig statusConfig = new StatusConfig();
		statusConfig.setEnabled(true);

		SyncConfig syncConfig = new SyncConfig();
		syncConfig.setColumns(columns);
		syncConfig.setStatus(statusConfig);

		ProjectConfig projectConfig = new ProjectConfig();
		projectConfig.setSync(syncConfig);

		Map<String, String> row = Map.of(
				"WBS_ID", "1.2.3.4",
				"レベル1", "親",
				"レベル2", "子",
				"開始日", "2026-1-3",
				"期限", "2026-1-10",
				"状態", "進行中",
				"進捗率", "40"
		);

		List<DiffItem> items = calculator.calculate(List.of(row), projectConfig, null, false, false);

		DiffItem item = items.stream()
				.filter(diffItem -> "1.2.3.4".equals(diffItem.externalKey()))
				.findFirst()
				.orElseThrow();
		assertThat(item.parentKey()).isEqualTo("1.2.3");
		assertThat(item.status()).isEqualTo("進行中");
		assertThat(item.payload().get("startDate")).isEqualTo("2026-1-3");
		assertThat(item.payload().get("dueDate")).isEqualTo("2026-1-10");
		assertThat(item.payload().get("progress")).isEqualTo(40);
	}

	@Test
	@DisplayName("親が存在しない場合は仮想親を生成し日付を集計する")
	void calculate_generatesVirtualParentsWithAggregatedDates() {
		IssueLinkRepository repository = Mockito.mock(IssueLinkRepository.class);
		when(repository.findByExternalKey(Mockito.anyString())).thenReturn(Optional.empty());
		when(repository.findAll()).thenReturn(List.of());
		DiffCalculator calculator = new DiffCalculator(repository);

		ColumnsConfig columns = new ColumnsConfig();
		columns.setExternalKeyColumn("WBS_ID");
		columns.setHierarchy(List.of("レベル1", "レベル2", "レベル3"));
		columns.setStartDateColumn("開始日");
		columns.setDueDateColumn("期限");
		columns.setStatusColumn("状態");
		columns.setProgressColumn("進捗率");

		StatusConfig statusConfig = new StatusConfig();
		statusConfig.setEnabled(true);

		SyncConfig syncConfig = new SyncConfig();
		syncConfig.setColumns(columns);
		syncConfig.setStatus(statusConfig);

		ProjectConfig projectConfig = new ProjectConfig();
		projectConfig.setSync(syncConfig);

		Map<String, String> row1 = Map.of(
				"WBS_ID", "1.1.1",
				"レベル1", "A",
				"レベル2", "B",
				"レベル3", "C",
				"開始日", "2026-1-3",
				"期限", "2026-1-10",
				"進捗率", "50",
				"状態", "完了"
		);
		Map<String, String> row2 = Map.of(
				"WBS_ID", "1.1.2",
				"レベル1", "A",
				"レベル2", "B",
				"レベル3", "D",
				"開始日", "2026-1-1",
				"期限", "2026-1-20",
				"進捗率", "100",
				"状態", "進行中"
		);

		List<DiffItem> items = calculator.calculate(List.of(row1, row2), projectConfig, null, false, false);

		DiffItem parent = items.stream()
				.filter(item -> "1.1".equals(item.externalKey()))
				.findFirst()
				.orElseThrow();

		assertThat(parent.subject()).isEqualTo("B");
		assertThat(parent.payload().get("startDate")).isEqualTo("2026-01-01");
		assertThat(parent.payload().get("dueDate")).isEqualTo("2026-01-20");
		assertThat(parent.payload().get("trackerId")).isEqualTo(6);
		assertThat(parent.payload().get("progress")).isEqualTo(75);
		assertThat(parent.status()).isEqualTo("In Progress");
		assertThat(parent.payload().get("customFields")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.containsEntry("WBS_ID", "1.1");
	}

	@Test
	@DisplayName("CSVに存在しない外部キーは削除対象になる")
	void calculate_marksMissingKeysForDeletion() {
		IssueLinkRepository repository = Mockito.mock(IssueLinkRepository.class);
		when(repository.findByExternalKey(Mockito.anyString())).thenReturn(Optional.empty());
		IssueLinkEntity link1 = new IssueLinkEntity("1.1", 100L);
		link1.setProjectId("proj");
		IssueLinkEntity link2 = new IssueLinkEntity("9.9", 200L);
		link2.setProjectId("proj");
		when(repository.findAll()).thenReturn(List.of(link1, link2));
		DiffCalculator calculator = new DiffCalculator(repository);

		ColumnsConfig columns = new ColumnsConfig();
		columns.setExternalKeyColumn("WBS_ID");
		columns.setHierarchy(List.of("レベル1", "レベル2"));
		columns.setStartDateColumn("開始日");
		columns.setDueDateColumn("期限");
		columns.setStatusColumn("状態");
		columns.setProgressColumn("進捗率");

		StatusConfig statusConfig = new StatusConfig();
		statusConfig.setEnabled(true);

		SyncConfig syncConfig = new SyncConfig();
		syncConfig.setColumns(columns);
		syncConfig.setStatus(statusConfig);

		RedmineConfig redmineConfig = new RedmineConfig();
		redmineConfig.setProjectId("proj");

		ProjectConfig projectConfig = new ProjectConfig();
		projectConfig.setSync(syncConfig);
		projectConfig.setRedmine(redmineConfig);

		Map<String, String> row = Map.of(
				"WBS_ID", "1.1.1",
				"レベル1", "A",
				"レベル2", "B",
				"開始日", "2026-1-3",
				"期限", "2026-1-10",
				"状態", "進行中",
				"進捗率", "50"
		);

		List<DiffItem> items = calculator.calculate(List.of(row), projectConfig, null, false, false);

		assertThat(items).anyMatch(item -> "9.9".equals(item.externalKey()) && "DELETE".equals(item.action()));
		assertThat(items).noneMatch(item -> "1.1".equals(item.externalKey()) && "DELETE".equals(item.action()));
	}

	@Test
	@DisplayName("relinkモードでは削除を行わない")
	void calculate_relinkModeSkipsDeletion() {
		IssueLinkRepository repository = Mockito.mock(IssueLinkRepository.class);
		when(repository.findByExternalKey(Mockito.anyString())).thenReturn(Optional.empty());
		IssueLinkEntity link = new IssueLinkEntity("9.9", 200L);
		link.setProjectId("proj");
		when(repository.findAll()).thenReturn(List.of(link));
		DiffCalculator calculator = new DiffCalculator(repository);

		ColumnsConfig columns = new ColumnsConfig();
		columns.setExternalKeyColumn("WBS_ID");
		columns.setHierarchy(List.of("レベル1", "レベル2"));
		columns.setStartDateColumn("開始日");
		columns.setDueDateColumn("期限");
		columns.setStatusColumn("状態");
		columns.setProgressColumn("進捗率");

		StatusConfig statusConfig = new StatusConfig();
		statusConfig.setEnabled(true);

		SyncConfig syncConfig = new SyncConfig();
		syncConfig.setColumns(columns);
		syncConfig.setStatus(statusConfig);

		RedmineConfig redmineConfig = new RedmineConfig();
		redmineConfig.setProjectId("proj");

		ProjectConfig projectConfig = new ProjectConfig();
		projectConfig.setSync(syncConfig);
		projectConfig.setRedmine(redmineConfig);

		Map<String, String> row = Map.of(
				"WBS_ID", "1.1.1",
				"レベル1", "A",
				"レベル2", "B",
				"開始日", "2026-1-3",
				"期限", "2026-1-10",
				"状態", "進行中",
				"進捗率", "50"
		);

		List<DiffItem> items = calculator.calculate(List.of(row), projectConfig, null, true, false);

		assertThat(items).noneMatch(item -> "DELETE".equals(item.action()));
	}

	@Test
	@DisplayName("既定設定でも issues.csv 形式の列名から外部キーと階層を自動判定する")
	void calculate_detectsLegacyIssueCsvColumns() {
		IssueLinkRepository repository = Mockito.mock(IssueLinkRepository.class);
		when(repository.findByExternalKey(Mockito.anyString())).thenReturn(Optional.empty());
		when(repository.findAll()).thenReturn(List.of());
		DiffCalculator calculator = new DiffCalculator(repository);

		StatusConfig statusConfig = new StatusConfig();
		statusConfig.setEnabled(true);

		SyncConfig syncConfig = new SyncConfig();
		syncConfig.setStatus(statusConfig);

		ProjectConfig projectConfig = new ProjectConfig();
		projectConfig.setSync(syncConfig);

		Map<String, String> row = Map.of(
				"#", "2374",
				"Lv.01", "新eD拡張",
				"Lv.02", "業務共通(WF、会計連携)",
				"Lv.03", "ビジネスルール(WF一覧、仕訳パターン)",
				"Lv.04", "会計連携",
				"Lv.05", "仕訳整理/パターン作成・ビジネスルール作成",
				"Lv.06", "(サービス（見積）)初版作成",
				"タスクNo", "1.2.1.4.2.2.12",
				"社/組織", "NTD",
				"担当", ""
		);

		List<DiffItem> items = calculator.calculate(List.of(row), projectConfig, null, false, false);

		DiffItem item = items.stream()
				.filter(diffItem -> "1.2.1.4.2.2.12".equals(diffItem.externalKey()))
				.findFirst()
				.orElseThrow();

		assertThat(item.parentKey()).isEqualTo("1.2.1.4.2.2");
		assertThat(item.subject()).isEqualTo("(サービス（見積）)初版作成");
		assertThat(item.levelPath()).isEqualTo("新eD拡張 > 業務共通(WF、会計連携) > ビジネスルール(WF一覧、仕訳パターン) > 会計連携 > 仕訳整理/パターン作成・ビジネスルール作成 > (サービス（見積）)初版作成");
	}

	@Test
	@DisplayName("resetモードでは既存リンクを全削除し対象行をCREATEに固定する")
	void calculate_resetSyncDeletesAllExistingAndRecreatesRows() {
		IssueLinkRepository repository = Mockito.mock(IssueLinkRepository.class);
		IssueLinkEntity link1 = new IssueLinkEntity("1.1", 100L);
		link1.setProjectId("proj");
		IssueLinkEntity link2 = new IssueLinkEntity("9.9", 200L);
		link2.setProjectId("proj");
		when(repository.findAll()).thenReturn(List.of(link1, link2));
		when(repository.findByExternalKey(Mockito.anyString())).thenReturn(Optional.of(link1));
		DiffCalculator calculator = new DiffCalculator(repository);

		ColumnsConfig columns = new ColumnsConfig();
		columns.setExternalKeyColumn("WBS_ID");
		columns.setHierarchy(List.of("レベル1", "レベル2"));

		SyncConfig syncConfig = new SyncConfig();
		syncConfig.setColumns(columns);

		RedmineConfig redmineConfig = new RedmineConfig();
		redmineConfig.setProjectId("proj");

		ProjectConfig projectConfig = new ProjectConfig();
		projectConfig.setSync(syncConfig);
		projectConfig.setRedmine(redmineConfig);

		Map<String, String> row = Map.of(
				"WBS_ID", "1.1",
				"レベル1", "親",
				"レベル2", "子"
		);

		List<DiffItem> items = calculator.calculate(List.of(row), projectConfig, null, false, true);

		assertThat(items)
				.filteredOn(item -> "1.1".equals(item.externalKey()))
				.extracting(DiffItem::action)
				.containsExactlyInAnyOrder("CREATE", "DELETE");
		assertThat(items)
				.filteredOn(item -> "9.9".equals(item.externalKey()))
				.extracting(DiffItem::action)
				.containsExactly("DELETE");
	}
}
