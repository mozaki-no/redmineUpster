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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import mozaki.redmineUpster.service.RedmineClient;

class SyncExecutorTests {

	@TempDir
	Path tempDir;

	private FileLogger logger;
	private Map<Long, Map<String, Object>> projectIssues;
	private RedmineClient client;
	private SyncExecutor executor;

	@BeforeEach
	void setUp() throws IOException {
		logger = new FileLogger(tempDir.toString());
		projectIssues = new LinkedHashMap<>();
		client = Mockito.mock(RedmineClient.class);
		when(client.getProjectId()).thenReturn("proj");
		when(client.getBaseUrl()).thenReturn("http://redmine.local");
		when(client.getProjectNumericId()).thenReturn(7L);
		executor = new SyncExecutor();
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
		Map<String, Object> issue = new HashMap<>();
		issue.put("id", id);
		issue.put("subject", "S" + id);
		issue.put("project", Map.of("id", projectId, "name", "P" + projectId));
		issue.put("status", Map.of("id", statusId));
		issue.put("tracker", Map.of("id", 2L));
		return issue;
	}

	/** item() と同じ内容を持つRedmine上のチケット（変更なし判定用） */
	private static Map<String, Object> sameAsItem(long id, String subject, Long parentId) {
		Map<String, Object> issue = issue(id, 7, 1);
		issue.put("subject", subject);
		issue.put("start_date", "2026-01-03");
		if (parentId != null) {
			issue.put("parent", Map.of("id", parentId));
		}
		return issue;
	}

	private SyncResult run(List<DiffItem> items, List<Long> deletes, Integer deleteStatusId, boolean dryRun,
			boolean force) {
		return executor.execute(items, projectIssues, deletes, projectConfig(deleteStatusId), client, dryRun, logger,
				force);
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
		SyncResult result = run(List.of(
				item(4, null, 2, 3, "孫"),
				item(3, null, 1, 2, "子"),
				item(2, null, 0, null, "親")), List.of(), null, false, false);

		assertThat(result.errorCount()).isZero();
		List<Map<String, Object>> payloads = capturedCreates(3);
		assertThat(payloads.get(0)).containsEntry("subject", "親").containsEntry("parent_issue_id", "")
				.containsEntry("tracker_id", 2L).containsEntry("start_date", "2026-01-03");
		assertThat(payloads.get(1)).containsEntry("subject", "子").containsEntry("parent_issue_id", 101L);
		assertThat(payloads.get(2)).containsEntry("subject", "孫").containsEntry("parent_issue_id", 102L);
		assertThat(result.createdIssueIds()).containsExactly(Map.entry(2, 101L), Map.entry(3, 102L),
				Map.entry(4, 103L));
	}

	@Test
	@DisplayName("既存チケットの親の下に子を作成し、既存チケットは取得済みの一覧で確認してから更新する（個別GETなし）")
	@SuppressWarnings({ "unchecked", "rawtypes" })
	void execute_updatesExistingAndCreatesChild() {
		projectIssues.put(50L, issue(50, 7, 1));
		when(client.createIssue(anyMap())).thenReturn(201L);

		SyncResult result = run(List.of(
				item(3, null, 1, 2, "子"),
				item(2, 50L, 0, null, "親")), List.of(), null, false, false);

		assertThat(result.errorCount()).isZero();
		ArgumentCaptor<Map> update = ArgumentCaptor.forClass(Map.class);
		verify(client).updateIssue(eq(50L), update.capture());
		assertThat((Map<String, Object>) update.getValue()).containsEntry("parent_issue_id", "");
		assertThat(capturedCreates(1).get(0)).containsEntry("parent_issue_id", 50L);
		assertThat(result.createdIssueIds()).containsExactly(Map.entry(3, 201L));
		verify(client, never()).getIssue(anyLong());
	}

	@Test
	@DisplayName("プロジェクトの一覧にないチケットID（存在しない・別プロジェクト）はエラーにして、子もスキップし他の行は続行する")
	void execute_updateErrorsAreReportedAndChildrenSkipped() {
		when(client.getIssue(60L)).thenReturn(null);
		when(client.getIssue(61L)).thenReturn(issue(61, 99, 1));
		projectIssues.put(62L, issue(62, 7, 1));

		SyncResult result = run(List.of(
				item(2, 60L, 0, null, "なし"),
				item(3, null, 1, 2, "なしの子"),
				item(4, 61L, 0, null, "別PJ"),
				item(5, 62L, 0, null, "正常")), List.of(), null, false, false);

		assertThat(result.errorCount()).isEqualTo(3);
		assertThat(result.errors().get(0)).contains("行2").contains("存在しません");
		assertThat(result.errors().get(1)).contains("行4").contains("別プロジェクト").contains("P99");
		assertThat(result.errors().get(2)).contains("行3").contains("親行(行2)");
		verify(client, never()).createIssue(anyMap());
		verify(client).updateIssue(eq(62L), anyMap());
		verify(client, never()).updateIssue(eq(60L), anyMap());
		verify(client, never()).updateIssue(eq(61L), anyMap());
		verify(client, never()).getIssue(62L);
	}

	@Test
	@DisplayName("Redmineの現在の値と送信内容が同じならスキップする（--force-updateなら更新する）")
	void execute_skipsUnchangedUpdates() {
		projectIssues.put(70L, sameAsItem(70, "同じ", null));
		projectIssues.put(71L, sameAsItem(71, "子", 70L));
		List<DiffItem> items = List.of(item(2, 70L, 0, null, "同じ"), item(3, 71L, 1, 2, "子"));

		SyncResult result = run(items, List.of(), null, false, false);
		assertThat(result.successCount()).isEqualTo(2);
		verify(client, never()).updateIssue(anyLong(), anyMap());

		run(items, List.of(), null, false, true);
		verify(client, times(1)).updateIssue(eq(70L), anyMap());
		verify(client, times(1)).updateIssue(eq(71L), anyMap());
	}

	@Test
	@DisplayName("件名・親・日付のいずれかがRedmineと違えば更新する")
	void execute_updatesWhenRedmineDiffers() {
		Map<String, Object> renamed = sameAsItem(80, "旧件名", null);
		Map<String, Object> reparented = sameAsItem(81, "子", 99L);
		Map<String, Object> redated = sameAsItem(82, "日付", null);
		redated.put("start_date", "2026-02-01");
		projectIssues.put(80L, renamed);
		projectIssues.put(81L, reparented);
		projectIssues.put(82L, redated);
		projectIssues.put(83L, sameAsItem(83, "最上位", null));

		SyncResult result = run(List.of(
				item(2, 80L, 0, null, "新件名"),
				item(3, 81L, 1, 2, "子"),
				item(4, 82L, 0, null, "日付"),
				item(5, 83L, 0, null, "最上位")), List.of(), null, false, false);

		assertThat(result.errorCount()).isZero();
		verify(client).updateIssue(eq(80L), anyMap());
		verify(client).updateIssue(eq(81L), anyMap());
		verify(client).updateIssue(eq(82L), anyMap());
		verify(client, never()).updateIssue(eq(83L), anyMap());
	}

	@Test
	@DisplayName("statusId設定時は論理削除候補のステータスを変更する（取得済みの一覧で既にそのステータスならスキップ）")
	@SuppressWarnings({ "unchecked", "rawtypes" })
	void execute_logicalDeleteWithStatusId() {
		projectIssues.put(20L, issue(20, 7, 1));
		projectIssues.put(30L, issue(30, 7, 6));

		SyncResult result = run(List.of(), List.of(20L, 30L), 6, false, false);

		assertThat(result.errorCount()).isZero();
		assertThat(result.successCount()).isEqualTo(2);
		ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
		verify(client).updateIssue(eq(20L), payload.capture());
		assertThat((Map<String, Object>) payload.getValue()).containsExactly(Map.entry("status_id", 6L));
		verify(client, never()).updateIssue(eq(30L), anyMap());
		verify(client, never()).getIssue(anyLong());
	}

	@Test
	@DisplayName("statusId未設定なら論理削除候補はログに出すだけで何も変更しない")
	void execute_logicalDeleteWithoutStatusIdOnlyWarns() {
		projectIssues.put(20L, issue(20, 7, 1));
		SyncResult result = run(List.of(), List.of(20L, 30L), null, false, false);

		assertThat(result.errorCount()).isZero();
		assertThat(result.totalCount()).isZero();
		verify(client, never()).getIssue(anyLong());
		verify(client, never()).updateIssue(anyLong(), anyMap());
	}

	@Test
	@DisplayName("dry-runではRedmineへの書き込みを行わない")
	void execute_dryRunDoesNotWrite() {
		projectIssues.put(50L, issue(50, 7, 1));
		projectIssues.put(20L, issue(20, 7, 1));

		SyncResult result = run(List.of(
				item(2, null, 0, null, "親"),
				item(3, null, 1, 2, "子"),
				item(4, 50L, 0, null, "既存")), List.of(20L), 6, true, false);

		assertThat(result.errorCount()).isZero();
		assertThat(result.createdIssueIds()).isEmpty();
		verify(client, never()).createIssue(anyMap());
		verify(client, never()).updateIssue(anyLong(), anyMap());
	}
}
