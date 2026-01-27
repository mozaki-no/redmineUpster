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

		List<DiffItem> items = calculator.calculate(List.of(row), projectConfig, null);

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

		List<DiffItem> items = calculator.calculate(List.of(row1, row2), projectConfig, null);

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
		when(repository.findAll()).thenReturn(List.of(
				new IssueLinkEntity("1.1", 100L),
				new IssueLinkEntity("9.9", 200L)
		));
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
				"WBS_ID", "1.1.1",
				"レベル1", "A",
				"レベル2", "B",
				"開始日", "2026-1-3",
				"期限", "2026-1-10",
				"状態", "進行中",
				"進捗率", "50"
		);

		List<DiffItem> items = calculator.calculate(List.of(row), projectConfig, null);

		assertThat(items).anyMatch(item -> "9.9".equals(item.externalKey()) && "DELETE".equals(item.action()));
		assertThat(items).noneMatch(item -> "1.1".equals(item.externalKey()) && "DELETE".equals(item.action()));
	}
}
