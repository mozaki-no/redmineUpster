package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import mozaki.redmineUpster.config.SyncConfigProperties.DeletionConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.SyncConfig;
import mozaki.redmineUpster.domain.IssueLinkEntity;
import mozaki.redmineUpster.repository.IssueLinkRepository;
import mozaki.redmineUpster.service.RedmineClient;

class SyncExecutorTests {

	@TempDir
	Path tempDir;

	private FileLogger logger;
	private IssueLinkRepository repository;
	private RedmineClient client;
	private SyncExecutor executor;

	@BeforeEach
	void setUp() throws IOException {
		logger = new FileLogger(tempDir.toString());
		repository = Mockito.mock(IssueLinkRepository.class);
		when(repository.findByIssueIdAndProjectId(any(), any())).thenReturn(Optional.empty());
		client = Mockito.mock(RedmineClient.class);
		when(client.getProjectId()).thenReturn("proj");
		when(client.getBaseUrl()).thenReturn("http://redmine.local");
		when(client.getProjectNumericId()).thenReturn(7L);
		executor = new SyncExecutor(repository);
	}

	@AfterEach
	void tearDown() {
		logger.close();
	}

	private static ProjectConfig projectConfig(Integer deleteStatusId) {
		SyncConfig syncConfig = new SyncConfig();
		if (deleteStatusId != null) {
			DeletionConfig deletion = new DeletionConfig();
			deletion.setStatusId(deleteStatusId);
			syncConfig.setDeletion(deletion);
		}
		ProjectConfig config = new ProjectConfig();
		config.setSync(syncConfig);
		return config;
	}

	private static DiffItem item(int row, Long issueId, int depth, Integer parentRow, String subject) {
		return new DiffItem(row, issueId, subject, subject, depth, parentRow,
				issueId == null ? "CREATE" : "UPDATE", null, 2L,
				Map.of("startDate", "2026-1-3", "customFields", Map.of()));
	}

	private static Map<String, Object> issue(long id, long projectId, long statusId) {
		return Map.of("id", id, "project", Map.of("id", projectId, "name", "P" + projectId),
				"status", Map.of("id", statusId));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private List<Map<String, Object>> capturedCreates(int count) {
		ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
		verify(client, times(count)).createIssue(captor.capture());
		return (List) captor.getAllValues();
	}

	@Test
	@DisplayName("同じ実行内で新規作成した親のIDを子のparent_issue_idに渡す")
	void execute_passesCreatedParentIdToChildren() {
		when(client.createIssue(anyMap())).thenReturn(101L, 102L, 103L);

		// 子→親の順に渡しても親から作成される
		SyncResult result = executor.execute(List.of(
				item(4, null, 2, 3, "孫"),
				item(3, null, 1, 2, "子"),
				item(2, null, 0, null, "親")), List.of(), projectConfig(null), client, false, logger, false);

		assertThat(result.errorCount()).isZero();
		List<Map<String, Object>> payloads = capturedCreates(3);
		assertThat(payloads.get(0)).containsEntry("subject", "親").containsEntry("parent_issue_id", "")
				.containsEntry("tracker_id", 2L).containsEntry("start_date", "2026-01-03");
		assertThat(payloads.get(1)).containsEntry("subject", "子").containsEntry("parent_issue_id", 101L);
		assertThat(payloads.get(2)).containsEntry("subject", "孫").containsEntry("parent_issue_id", 102L);
		assertThat(result.createdIssueIds()).containsExactly(Map.entry(2, 101L), Map.entry(3, 102L),
				Map.entry(4, 103L));
		verify(repository, times(3)).save(any(IssueLinkEntity.class));
	}

	@Test
	@DisplayName("既存チケットの親の下に子を作成し、既存チケットは存在確認してから更新する")
	@SuppressWarnings({ "unchecked", "rawtypes" })
	void execute_updatesExistingAndCreatesChild() {
		when(client.getIssue(50L)).thenReturn(issue(50, 7, 1));
		when(client.createIssue(anyMap())).thenReturn(201L);

		SyncResult result = executor.execute(List.of(
				item(3, null, 1, 2, "子"),
				item(2, 50L, 0, null, "親")), List.of(), projectConfig(null), client, false, logger, false);

		assertThat(result.errorCount()).isZero();
		ArgumentCaptor<Map> update = ArgumentCaptor.forClass(Map.class);
		verify(client).updateIssue(eq(50L), update.capture());
		assertThat((Map<String, Object>) update.getValue()).containsEntry("parent_issue_id", "");
		assertThat(capturedCreates(1).get(0)).containsEntry("parent_issue_id", 50L);
		assertThat(result.createdIssueIds()).containsExactly(Map.entry(3, 201L));
	}

	@Test
	@DisplayName("存在しない・別プロジェクトのチケットIDはエラーにして、子もスキップし他の行は続行する")
	void execute_updateErrorsAreReportedAndChildrenSkipped() {
		when(client.getIssue(60L)).thenReturn(null);
		when(client.getIssue(61L)).thenReturn(issue(61, 99, 1));
		when(client.getIssue(62L)).thenReturn(issue(62, 7, 1));

		SyncResult result = executor.execute(List.of(
				item(2, 60L, 0, null, "なし"),
				item(3, null, 1, 2, "なしの子"),
				item(4, 61L, 0, null, "別PJ"),
				item(5, 62L, 0, null, "正常")), List.of(), projectConfig(null), client, false, logger, false);

		assertThat(result.errorCount()).isEqualTo(3);
		assertThat(result.errors().get(0)).contains("行2").contains("存在しません");
		assertThat(result.errors().get(1)).contains("行4").contains("別プロジェクト").contains("P99");
		assertThat(result.errors().get(2)).contains("行3").contains("親行(行2)");
		verify(client, never()).createIssue(anyMap());
		verify(client).updateIssue(eq(62L), anyMap());
		verify(client, never()).updateIssue(eq(60L), anyMap());
		verify(client, never()).updateIssue(eq(61L), anyMap());
	}

	@Test
	@DisplayName("前回と同じ内容の更新はスキップする（--force-updateなら更新する）")
	void execute_skipsUnchangedUpdates() {
		when(client.getIssue(70L)).thenReturn(issue(70, 7, 1));
		DiffItem item = item(2, 70L, 0, null, "同じ");
		executor.execute(List.of(item), List.of(), projectConfig(null), client, false, logger, false);
		ArgumentCaptor<IssueLinkEntity> saved = ArgumentCaptor.forClass(IssueLinkEntity.class);
		verify(repository).save(saved.capture());
		IssueLinkEntity link = saved.getValue();
		assertThat(link.getIssueId()).isEqualTo(70L);
		assertThat(link.getProjectId()).isEqualTo("proj");
		when(repository.findByIssueIdAndProjectId(70L, "proj")).thenReturn(Optional.of(link));

		SyncResult second = executor.execute(List.of(item), List.of(), projectConfig(null), client, false, logger,
				false);
		assertThat(second.successCount()).isEqualTo(1);
		verify(client, times(1)).updateIssue(eq(70L), anyMap());

		executor.execute(List.of(item), List.of(), projectConfig(null), client, false, logger, true);
		verify(client, times(2)).updateIssue(eq(70L), anyMap());
	}

	@Test
	@DisplayName("statusId設定時はExcelから消えたチケットのステータスを変更する（既にそのステータスならスキップ）")
	@SuppressWarnings({ "unchecked", "rawtypes" })
	void execute_logicalDeleteWithStatusId() {
		when(client.getIssue(20L)).thenReturn(issue(20, 7, 1));
		when(client.getIssue(30L)).thenReturn(issue(30, 7, 6));
		IssueLinkEntity already = new IssueLinkEntity(40L, "proj");
		already.setPayloadHash("logical-delete:6");
		when(repository.findByIssueIdAndProjectId(40L, "proj")).thenReturn(Optional.of(already));

		SyncResult result = executor.execute(List.of(), List.of(20L, 30L, 40L), projectConfig(6), client, false,
				logger, false);

		assertThat(result.errorCount()).isZero();
		assertThat(result.successCount()).isEqualTo(3);
		ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
		verify(client).updateIssue(eq(20L), payload.capture());
		assertThat((Map<String, Object>) payload.getValue()).containsExactly(Map.entry("status_id", 6L));
		verify(client, never()).updateIssue(eq(30L), anyMap());
		verify(client, never()).getIssue(40L);
		verify(client, never()).deleteIssue(anyLong());
		ArgumentCaptor<IssueLinkEntity> saved = ArgumentCaptor.forClass(IssueLinkEntity.class);
		verify(repository, times(2)).save(saved.capture());
		assertThat(saved.getAllValues()).extracting(IssueLinkEntity::getPayloadHash)
				.containsOnly("logical-delete:6");
	}

	@Test
	@DisplayName("statusId未設定なら論理削除候補はログに出すだけで何も変更しない")
	void execute_logicalDeleteWithoutStatusIdOnlyWarns() {
		SyncResult result = executor.execute(List.of(), List.of(20L, 30L), projectConfig(null), client, false,
				logger, false);

		assertThat(result.errorCount()).isZero();
		assertThat(result.totalCount()).isZero();
		verify(client, never()).getIssue(anyLong());
		verify(client, never()).updateIssue(anyLong(), anyMap());
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("dry-runではRedmineへの書き込みもissue_linkの保存も行わない")
	void execute_dryRunDoesNotWrite() {
		when(client.getIssue(50L)).thenReturn(issue(50, 7, 1));

		SyncResult result = executor.execute(List.of(
				item(2, null, 0, null, "親"),
				item(3, null, 1, 2, "子"),
				item(4, 50L, 0, null, "既存")), List.of(20L), projectConfig(6), client, true, logger, false);

		assertThat(result.errorCount()).isZero();
		assertThat(result.createdIssueIds()).isEmpty();
		verify(client, never()).createIssue(anyMap());
		verify(client, never()).updateIssue(anyLong(), anyMap());
		verify(repository, never()).save(any());
	}
}
