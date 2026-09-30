package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.ExcelConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.SyncConfig;
import mozaki.redmineUpster.service.ExcelSource;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.RedmineClientFactory;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet;
import mozaki.redmineUpster.service.SyncConfigService;
import mozaki.redmineUpster.service.TicketIdWriter;

/**
 * Excel 出力（--export）と、出力したファイルの取り込み（--sync）のテスト。
 */
class ExportImportTests {

	@TempDir
	Path tempDir;

	private FileLogger logger;

	@BeforeEach
	void setUp() throws Exception {
		logger = new FileLogger(tempDir.resolve("logs").toString());
	}

	@AfterEach
	void tearDown() {
		logger.close();
	}

	private static Map<String, Object> issue(long id, String subject, Long parent, String tracker) {
		Map<String, Object> issue = new LinkedHashMap<>();
		issue.put("id", id);
		issue.put("subject", subject);
		issue.put("tracker", Map.of("id", "サマリ".equals(tracker) ? 6 : 2, "name", tracker));
		issue.put("status", Map.of("id", 1, "name", "新規"));
		if (parent != null) {
			issue.put("parent", Map.of("id", parent));
		}
		return issue;
	}

	private static Map<Long, Map<String, Object>> issues() {
		Map<Long, Map<String, Object>> issues = new LinkedHashMap<>();
		issues.put(4L, issue(4, "実装", null, "サマリ"));
		issues.put(1L, issue(1, "設計", null, "サマリ"));
		issues.put(2L, issue(2, "画面設計", 1L, "サマリ"));
		Map<String, Object> task = issue(3, "ログイン画面", 2L, "タスク");
		task.put("assigned_to", Map.of("id", 5, "name", "田中 太郎"));
		task.put("start_date", "2026-10-01");
		task.put("due_date", "2026-10-10");
		task.put("done_ratio", 50);
		issues.put(3L, task);
		return issues;
	}

	private static Map<Long, Map<String, Object>> users() {
		Map<Long, Map<String, Object>> users = new LinkedHashMap<>();
		users.put(5L, new LinkedHashMap<>(Map.of("id", 5, "login", "tanaka", "lastname", "田中", "firstname", "太郎",
				"mail", "tanaka@example.com", "admin", true, "status", 1)));
		return users;
	}

	private static Map<Long, Map<String, Object>> groups() {
		Map<Long, Map<String, Object>> groups = new LinkedHashMap<>();
		groups.put(20L, new LinkedHashMap<>(Map.of("id", 20, "name", "開発",
				"users", List.of(Map.of("id", 5, "name", "田中 太郎")))));
		return groups;
	}

	private static ProjectConfig config(String table) {
		ProjectConfig config = new ProjectConfig();
		config.setName("t");
		SyncConfig sync = new SyncConfig();
		sync.setTrackerMap(Map.of("サマリ", "6", "タスク", "2"));
		if (table != null) {
			ExcelConfig excel = new ExcelConfig();
			excel.setTable(table);
			sync.setExcel(excel);
		}
		StatusConfig status = new StatusConfig();
		status.setEnabled(true);
		sync.setStatus(status);
		config.setSync(sync);
		return config;
	}

	@Test
	@DisplayName("出力した Excel をそのまま取り込める（親子・件名・担当のログインID、ユーザー・グループは変更なし）")
	void exportThenImport_roundTrip() throws Exception {
		Path xlsx = tempDir.resolve("redmine.xlsx");
		ProjectConfig config = config("取込表");
		WorkbookExporter.ExportResult result = new WorkbookExporter().export(xlsx,
				new WorkbookExporter.ExportData(issues(), users(), groups()), config);
		assertThat(result.warnings()).isEmpty();
		assertThat(result.sheets()).containsExactly(Map.entry("チケット", 4), Map.entry("ユーザー", 1),
				Map.entry("グループ", 1));

		SpreadsheetParser parser = new SpreadsheetParser();
		ParsedSheet tickets = parser.parseFromPath(xlsx.toString(), new ExcelSource(null, "取込表"));
		DiffPlan plan = new DiffCalculator().calculate(tickets, config,
				new TrackerResolver(config.getSync().getTrackerMap(), null), logger, false);
		assertThat(plan.errors()).isEmpty();
		assertThat(plan.items()).extracting(DiffItem::issueId).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
		// 親 → 子の順（設計, 画面設計, ログイン画面, 実装）に出力される
		assertThat(plan.items()).extracting(DiffItem::rowNumber).contains(2, 3, 4, 5);
		DiffItem task = plan.items().stream().filter(i -> i.issueId() == 3L).findFirst().orElseThrow();
		DiffItem parent = plan.items().stream().filter(i -> i.issueId() == 2L).findFirst().orElseThrow();
		assertThat(task.rowNumber()).isEqualTo(4);
		assertThat(task.subject()).isEqualTo("ログイン画面");
		assertThat(task.levelPath()).isEqualTo("設計 > 画面設計 > ログイン画面");
		assertThat(task.parentRowNumber()).isEqualTo(parent.rowNumber());
		assertThat(task.trackerId()).isEqualTo(2L);
		assertThat(task.status()).isEqualTo("新規");
		assertThat(task.payload()).containsEntry("assignee", "tanaka").containsEntry("startDate", "2026-10-01")
				.containsEntry("dueDate", "2026-10-10").containsEntry("progress", 50);

		ParsedSheet usersSheet = parser.parseFromPath(xlsx.toString(), new ExcelSource("ユーザー", null));
		DirectorySync.Plan<DirectorySync.UserRow> userPlan = DirectorySync.parseUsers(usersSheet, users());
		assertThat(userPlan.errors()).isEmpty();
		assertThat(DirectorySync.userChanges(userPlan.rows().get(0), users().get(5L))).isEmpty();

		ParsedSheet groupsSheet = parser.parseFromPath(xlsx.toString(), new ExcelSource("グループ", null));
		DirectorySync.Plan<DirectorySync.GroupRow> groupPlan = DirectorySync.parseGroups(groupsSheet, groups(),
				Set.of("tanaka"));
		assertThat(groupPlan.errors()).isEmpty();
		assertThat(groupPlan.rows().get(0).members()).containsExactly("tanaka");
		assertThat(DirectorySync.sameMembers(groupPlan.rows().get(0).members(), groups().get(20L), users())).isTrue();
	}

	@Test
	@DisplayName("親子が階層列より深い場合は出力しない")
	void export_tooDeep() {
		Map<Long, Map<String, Object>> issues = new LinkedHashMap<>();
		for (long id = 1; id <= 6; id++) {
			issues.put(id, issue(id, "L" + id, id == 1 ? null : id - 1, "タスク"));
		}
		assertThatThrownBy(() -> new WorkbookExporter().export(tempDir.resolve("deep.xlsx"),
				new WorkbookExporter.ExportData(issues, null, null), config(null)))
				.hasMessageContaining("6 階層");
	}

	@Test
	@DisplayName("担当のログインIDとステータス名をIDに変換する")
	void ticketValueResolver() {
		RedmineClient client = Mockito.mock(RedmineClient.class);
		when(client.listIssueStatuses()).thenReturn(Map.of("新規", 1L, "進行中", 2L));
		when(client.listGroups()).thenReturn(groups());
		Map<String, Object> payload = new LinkedHashMap<>(Map.of("assignee", "Tanaka"));
		Map<String, Object> byName = new LinkedHashMap<>(Map.of("assignee", "田中　太郎"));
		Map<String, Object> byGroup = new LinkedHashMap<>(Map.of("assignee", "開発"));
		List<DiffItem> items = List.of(
				new DiffItem(2, 3L, "s", "s", 0, null, SyncConstants.ACTION_UPDATE, "進行中", 2L, payload),
				new DiffItem(3, 4L, "s", "s", 0, null, SyncConstants.ACTION_UPDATE, "New", 2L, byName),
				new DiffItem(4, 5L, "s", "s", 0, null, SyncConstants.ACTION_UPDATE, "2", 2L, byGroup));
		List<DiffItem> resolved = new TicketValueResolver(client, logger, users(), null, Set.of())
				.resolve(items, config(null).getSync().getStatus());
		assertThat(resolved.get(0).payload()).containsEntry("assignee", "5");
		assertThat(resolved.get(0).status()).isEqualTo("2");
		assertThat(resolved.get(1).payload()).containsEntry("assignee", "5");
		assertThat(resolved.get(1).status()).isEqualTo("New"); // BY_DATES の既定値はそのまま
		assertThat(resolved.get(2).payload()).containsEntry("assignee", "20");
		assertThat(resolved.get(2)).isNotSameAs(items.get(2));
	}

	@Test
	@DisplayName("--sync: ユーザー・グループだけの Excel は、ユーザー → グループの順に Upsert してIDを書き戻す")
	void syncRunner_usersAndGroupsOnly() throws Exception {
		Path xlsx = tempDir.resolve("users.xlsx");
		try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
			Sheet users = wb.createSheet("ユーザー");
			Row header = users.createRow(0);
			for (int c = 0; c < DirectorySync.USER_COLUMNS.size(); c++) {
				header.createCell(c).setCellValue(DirectorySync.USER_COLUMNS.get(c));
			}
			Row row = users.createRow(1);
			List<String> values = List.of("", "sato", "佐藤", "次郎", "sato@example.com");
			for (int c = 0; c < values.size(); c++) {
				row.createCell(c).setCellValue(values.get(c));
			}
			Sheet groups = wb.createSheet("グループ");
			Row gHeader = groups.createRow(0);
			gHeader.createCell(0).setCellValue("ID");
			gHeader.createCell(1).setCellValue("グループ名");
			gHeader.createCell(2).setCellValue("メンバー");
			Row gRow = groups.createRow(1);
			gRow.createCell(0).setCellValue("");
			gRow.createCell(1).setCellValue("開発");
			gRow.createCell(2).setCellValue("tanaka, sato");
			wb.write(out);
		}
		Path configFile = tempDir.resolve("sync-config.yml");
		Files.writeString(configFile, String.join("\n",
				"projects:",
				"  - name: t",
				"    default: true",
				"    redmine: { baseUrl: 'http://127.0.0.1:1', apiKey: k, projectId: p }",
				"    sync:",
				"      excel:",
				"        table: 取込表",
				""));
		RedmineClient client = Mockito.mock(RedmineClient.class);
		when(client.listUsers()).thenReturn(users());
		when(client.listGroups()).thenReturn(groups());
		when(client.createUser(anyMap())).thenReturn(10L);
		RedmineClientFactory factory = Mockito.mock(RedmineClientFactory.class);
		when(factory.createClient(Mockito.any())).thenReturn(client);
		SyncExecutor executor = Mockito.mock(SyncExecutor.class);

		SyncConfigService configService = new SyncConfigService(new SyncConfigProperties());
		SyncRunner runner = new SyncRunner(configService, new SpreadsheetParser(), new DiffCalculator(), executor,
				factory, new TicketIdWriter(), new DirectorySync());
		int exit = runner.run(configFile.toString(), null, xlsx.toString(), false,
				tempDir.resolve("logs").toString(), false, false, null, null, null);

		assertThat(exit).isEqualTo(0);
		Mockito.verify(client).updateGroup(20L, Map.of("user_ids", List.of(5L, 10L)));
		Mockito.verifyNoInteractions(executor);
		Mockito.verify(client, Mockito.never()).listProjectIssues();
		try (InputStream in = Files.newInputStream(xlsx); Workbook wb = WorkbookFactory.create(in)) {
			assertThat(wb.getSheet("ユーザー").getRow(1).getCell(0).getNumericCellValue()).isEqualTo(10.0);
			assertThat(wb.getSheet("グループ").getRow(1).getCell(0).getNumericCellValue()).isEqualTo(20.0);
		}
		assertThat(tempDir.resolve("users.xlsx.bak")).exists();
		try (InputStream in = Files.newInputStream(tempDir.resolve("users.xlsx.bak"));
				Workbook wb = WorkbookFactory.create(in)) {
			// バックアップは実行前の元ファイル（ユーザーのIDの書き戻し前）
			assertThat(wb.getSheet("ユーザー").getRow(1).getCell(0).getStringCellValue()).isEmpty();
		}
	}

	@Test
	@DisplayName("カスタムフィールドは「CF:名前」列で出力し、そのまま取り込める（ユーザーは値を変えた列だけ更新）")
	void customFieldColumns_roundTrip() throws Exception {
		Map<Long, Map<String, Object>> issues = issues();
		issues.get(3L).put("custom_fields", List.of(Map.of("id", 12, "name", "工程", "value", "設計"),
				Map.of("id", 14, "name", "締切", "value", "2026-11-01")));
		Map<Long, Map<String, Object>> users = users();
		users.get(5L).put("custom_fields", List.of(Map.of("id", 7, "name", "社員番号", "value", "E001")));
		Path xlsx = tempDir.resolve("cf.xlsx");
		new WorkbookExporter().export(xlsx, new WorkbookExporter.ExportData(issues, users, null), config(null));

		SpreadsheetParser parser = new SpreadsheetParser();
		ParsedSheet tickets = parser.parseFromPath(xlsx.toString(), new ExcelSource("チケット", null));
		assertThat(tickets.headers()).contains("CF:工程", "CF:締切");
		DiffPlan plan = new DiffCalculator().calculate(tickets, config(null),
				new TrackerResolver(Map.of("サマリ", "6", "タスク", "2"), null), logger, false);
		DiffItem task = plan.items().stream().filter(i -> i.issueId() == 3L).findFirst().orElseThrow();
		assertThat(task.payload().get("cfColumns")).isEqualTo(Map.of("工程", "設計", "締切", "2026-11-01"));

		ParsedSheet usersSheet = parser.parseFromPath(xlsx.toString(), new ExcelSource("ユーザー", null));
		assertThat(usersSheet.headers()).contains("CF:社員番号");
		DirectorySync.Plan<DirectorySync.UserRow> same = DirectorySync.parseUsers(usersSheet, users);
		assertThat(same.errors()).isEmpty();
		assertThat(DirectorySync.userChanges(same.rows().get(0), users.get(5L))).isEmpty();

		usersSheet.rows().get(0).put("CF:社員番号", "E002");
		usersSheet.rows().get(0).put("CF:部署", "");
		DirectorySync.Plan<DirectorySync.UserRow> changed = DirectorySync.parseUsers(usersSheet, users);
		assertThat(DirectorySync.userChanges(changed.rows().get(0), users.get(5L)))
				.isEqualTo(Map.of("custom_fields", List.of(Map.of("id", 7L, "value", "E002"))));

		usersSheet.rows().get(0).put("CF:部署", "開発部");
		assertThat(DirectorySync.parseUsers(usersSheet, users).errors()).singleElement().asString().contains("部署");
	}
}
