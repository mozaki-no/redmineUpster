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
import mozaki.redmineUpster.repository.IssueLinkRepository;

class DiffCalculatorTests {

	@Test
	@DisplayName("親パスが見つからない場合は外部キーから親キーを推定する")
	void calculate_inferParentKeyFromExternalKey() {
		IssueLinkRepository repository = Mockito.mock(IssueLinkRepository.class);
		when(repository.findByExternalKey(Mockito.anyString())).thenReturn(Optional.empty());
		DiffCalculator calculator = new DiffCalculator(repository);

		ColumnsConfig columns = new ColumnsConfig();
		columns.setExternalKeyColumn("WBS_ID");
		columns.setHierarchy(List.of("レベル1", "レベル2"));
		columns.setStartDateColumn("開始日");
		columns.setDueDateColumn("期限");
		columns.setStatusColumn("状態");

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
				"状態", "進行中"
		);

		List<DiffItem> items = calculator.calculate(List.of(row), projectConfig, null);

		assertThat(items).hasSize(1);
		DiffItem item = items.get(0);
		assertThat(item.parentKey()).isEqualTo("1.2.3");
		assertThat(item.status()).isEqualTo("進行中");
		assertThat(item.payload().get("startDate")).isEqualTo("2026-1-3");
		assertThat(item.payload().get("dueDate")).isEqualTo("2026-1-10");
	}
}
