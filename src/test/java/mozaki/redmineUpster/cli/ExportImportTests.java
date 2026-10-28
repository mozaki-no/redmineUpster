package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.ArrayList;
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
				Map.entry("グループ", 1), Map.entry("グループメンバー", 1));

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
		assertThat(groupsSheet.headers()).containsExactly("ID", "グループ名");
		ParsedSheet membersSheet = parser.parseFromPath(xlsx.toString(), new ExcelSource("グループメンバー", null));
		assertThat(membersSheet.headers()).containsExactly("グループ名", "ログインID", "氏名");
		assertThat(membersSheet.rows()).singleElement()
				.isEqualTo(Map.of("グループ名", "開発", "ログインID", "tanaka", "氏名", "田中 太郎"));
		DirectorySync.Plan<DirectorySync.GroupRow> groupPlan = DirectorySync.parseGroups(groupsSheet, membersSheet,
				groups(), Set.of("tanaka"));
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
	@DisplayName("担当のメールアドレス（大文字小文字を区別しない）をユーザーIDに変換し、重複・不明は変更しない")
	void ticketValueResolver_mail() {
		RedmineClient client = Mockito.mock(RedmineClient.class);
		when(client.listGroups()).thenReturn(groups());
		Map<Long, Map<String, Object>> users = users();
		users.put(6L, new LinkedHashMap<>(Map.of("id", 6, "login", "sato", "lastname", "佐藤", "firstname", "花子",
				"mail", "Shared@Example.com")));
		users.put(7L, new LinkedHashMap<>(Map.of("id", 7, "login", "suzuki", "lastname", "鈴木", "firstname", "一郎",
				"mail", "shared@example.com")));
		List<DiffItem> items = new ArrayList<>();
		int row = 2;
		for (String value : List.of(" TANAKA@example.com ", "shared@example.com", "nobody@example.com")) {
			items.add(new DiffItem(row, (long) row, "s", "s", 0, null, SyncConstants.ACTION_UPDATE, "2", 2L,
					new LinkedHashMap<>(Map.of("assignee", value))));
			row++;
		}
		List<DiffItem> resolved = new TicketValueResolver(client, logger, users, null, Set.of())
				.resolve(items, config(null).getSync().getStatus());
		assertThat(resolved.get(0).payload()).containsEntry("assignee", "5");
		assertThat(resolved.get(1).payload()).containsEntry("assignee", "");
		assertThat(resolved.get(2).payload()).containsEntry("assignee", "");
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

	/**
	 * ルートチケットの出力用のチケット（プロジェクト）。
	 * ルート #10 の配下は 子A(#11) > 孫(#12) と 子B(#13)。別の木 #1〜#6 は6階層（階層列5列より深い）。
	 */
	private static Map<Long, Map<String, Object>> rootIssues() {
		Map<Long, Map<String, Object>> issues = new LinkedHashMap<>();
		for (long id = 1; id <= 6; id++) {
			issues.put(id, issue(id, "L" + id, id == 1 ? null : id - 1, "タスク"));
		}
		issues.put(10L, issue(10, "ルート", null, "サマリ"));
		issues.put(11L, issue(11, "子A", 10L, "サマリ"));
		issues.put(12L, issue(12, "孫", 11L, "タスク"));
		issues.put(13L, issue(13, "子B", 10L, "タスク"));
		return issues;
	}

	private static List<List<String>> sheetRows(Path xlsx, String sheetName) throws IOException {
		List<List<String>> rows = new ArrayList<>();
		try (InputStream in = Files.newInputStream(xlsx); Workbook wb = WorkbookFactory.create(in)) {
			Sheet sheet = wb.getSheet(sheetName);
			for (Row row : sheet) {
				List<String> values = new ArrayList<>();
				for (int c = 0; c < row.getLastCellNum(); c++) {
					values.add(row.getCell(c) == null ? "" : new org.apache.poi.ss.usermodel.DataFormatter()
							.formatCellValue(row.getCell(c)));
				}
				rows.add(values);
			}
		}
		return rows;
	}

	private ExportRunner exportRunner(RedmineClient client) {
		RedmineClientFactory factory = Mockito.mock(RedmineClientFactory.class);
		when(factory.createClient(Mockito.any())).thenReturn(client);
		SyncRunner runner = new SyncRunner(new SyncConfigService(new SyncConfigProperties()), new SpreadsheetParser(),
				new DiffCalculator(), Mockito.mock(SyncExecutor.class), factory, new TicketIdWriter(),
				new DirectorySync());
		return new ExportRunner(runner, factory, new WorkbookExporter());
	}

	private Path exportConfig(String... files) throws IOException {
		List<String> lines = new ArrayList<>(List.of(
				"projects:",
				"  - name: t",
				"    default: true",
				"    redmine: { baseUrl: 'http://127.0.0.1:1', apiKey: k, projectId: p }",
				"    sync:",
				"      trackerMap: { サマリ: 6, タスク: 2 }"));
		if (files.length > 0) {
			lines.add("      files:");
			for (String file : files) {
				lines.add("        - " + file);
			}
		}
		Path config = tempDir.resolve("sync-config.yml");
		Files.writeString(config, String.join("\n", lines) + "\n");
		return config;
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
	@DisplayName("ルートチケット指定: 配下だけをルート基準の階層で出力し（ルート自身は出さない）、別の深い木はエラーにしない")
	void export_rootIssue_subtreeOnly() throws Exception {
		Path xlsx = tempDir.resolve("root.xlsx");
		WorkbookExporter.ExportResult result = new WorkbookExporter().export(xlsx,
				new WorkbookExporter.ExportData(rootIssues(), null, null, 10L), config(null));
		assertThat(result.sheets()).containsExactly(Map.entry("チケット", 3));
		List<List<String>> rows = sheetRows(xlsx, "チケット");
		List<String> header = rows.get(0);
		int id = header.indexOf("チケットID");
		int level1 = header.indexOf("大分類");
		int level2 = header.indexOf("中分類");
		assertThat(rows.subList(1, rows.size())).extracting(r -> r.get(id)).containsExactly("11", "12", "13");
		assertThat(rows.get(1).get(level1)).isEqualTo("子A");
		assertThat(rows.get(2).get(level1)).isEqualTo("子A");
		assertThat(rows.get(2).get(level2)).isEqualTo("孫");
		assertThat(rows.get(3).get(level1)).isEqualTo("子B");

		// 取り込むと、ルートの子が最上位の行（親行なし）になる
		ParsedSheet tickets = new SpreadsheetParser().parseFromPath(xlsx.toString(), new ExcelSource("チケット", null));
		DiffPlan plan = new DiffCalculator().calculate(tickets, config(null),
				new TrackerResolver(Map.of("サマリ", "6", "タスク", "2"), null), logger, false);
		assertThat(plan.errors()).isEmpty();
		assertThat(plan.items()).extracting(DiffItem::issueId).containsExactlyInAnyOrder(11L, 12L, 13L);
	}

	@Test
	@DisplayName("ルートチケット指定: ルートの配下が階層列より深い場合は、ルートを含むメッセージでエラー")
	void export_rootIssue_tooDeep() {
		Map<Long, Map<String, Object>> issues = rootIssues();
		issues.put(7L, issue(7, "L7", 6L, "タスク"));
		// L1 の配下（L2〜L7）は6階層で、階層列（5列）より深い
		assertThatThrownBy(() -> new WorkbookExporter().export(tempDir.resolve("deep.xlsx"),
				new WorkbookExporter.ExportData(issues, null, null, 1L), config(null)))
				.hasMessageContaining("ルートチケット #1 の配下が 6 階層");
		assertThat(WorkbookExporter.descendants(issues, 1L)).containsOnlyKeys(2L, 3L, 4L, 5L, 6L, 7L);
		assertThat(tempDir.resolve("deep.xlsx")).doesNotExist();
	}

	@Test
	@DisplayName("--export --root-issue: 別プロジェクトのルートは個別に取得して確認し、配下だけを出力する")
	void exportRunner_rootInAnotherProject() throws Exception {
		Map<Long, Map<String, Object>> issues = rootIssues();
		issues.remove(10L); // ルートは親プロジェクトのチケット
		RedmineClient client = Mockito.mock(RedmineClient.class);
		when(client.listProjectIssues()).thenReturn(issues);
		when(client.getIssue(10L)).thenReturn(issue(10, "ルート", null, "サマリ"));
		Path xlsx = tempDir.resolve("out.xlsx");
		int exit = exportRunner(client).run(exportConfig().toString(), null, xlsx.toString(),
				tempDir.resolve("logs").toString(), false, Set.of(SyncTarget.TICKETS), 10L);
		assertThat(exit).isEqualTo(0);
		Mockito.verify(client).getIssue(10L);
		List<List<String>> rows = sheetRows(xlsx, "チケット");
		int id = rows.get(0).indexOf("チケットID");
		assertThat(rows.subList(1, rows.size())).extracting(r -> r.get(id)).containsExactly("11", "12", "13");
	}

	@Test
	@DisplayName("--export --root-issue: ルートが見つからない場合はエラーで、ファイルを作らない")
	void exportRunner_rootNotFound() throws Exception {
		RedmineClient client = Mockito.mock(RedmineClient.class);
		when(client.listProjectIssues()).thenReturn(rootIssues());
		when(client.getIssue(99L)).thenReturn(null);
		Path xlsx = tempDir.resolve("out.xlsx");
		int exit = exportRunner(client).run(exportConfig().toString(), null, xlsx.toString(),
				tempDir.resolve("logs").toString(), false, Set.of(SyncTarget.TICKETS), 99L);
		assertThat(exit).isEqualTo(1);
		assertThat(xlsx).doesNotExist();
		assertThat(logText()).contains("ルートチケット #99 が見つかりません");
	}

	@Test
	@DisplayName("--export: 出力先が sync.files にあればその rootIssueId を使い、配下がなければ警告して見出しだけ出力")
	void exportRunner_rootFromSyncFiles() throws Exception {
		RedmineClient client = Mockito.mock(RedmineClient.class);
		when(client.listProjectIssues()).thenReturn(rootIssues());
		Path config = exportConfig("{ path: wbs-a.xlsx, rootIssueId: 10 }", "{ path: wbs-b.xlsx, rootIssueId: 13 }");
		Path a = tempDir.resolve("wbs-a.xlsx");
		assertThat(exportRunner(client).run(config.toString(), null, a.toString(),
				tempDir.resolve("logs").toString(), false, Set.of(SyncTarget.TICKETS), null)).isEqualTo(0);
		List<List<String>> rows = sheetRows(a, "チケット");
		int id = rows.get(0).indexOf("チケットID");
		assertThat(rows.subList(1, rows.size())).extracting(r -> r.get(id)).containsExactly("11", "12", "13");

		// CLI の --root-issue が優先
		assertThat(exportRunner(client).run(config.toString(), null, a.toString(),
				tempDir.resolve("logs").toString(), false, Set.of(SyncTarget.TICKETS), 11L)).isEqualTo(0);
		rows = sheetRows(a, "チケット");
		assertThat(rows.subList(1, rows.size())).extracting(r -> r.get(id)).containsExactly("12");

		// 配下なし（#13 は子がない）: 見出しだけ
		Path b = tempDir.resolve("wbs-b.xlsx");
		assertThat(exportRunner(client).run(config.toString(), null, b.toString(),
				tempDir.resolve("logs").toString(), false, Set.of(SyncTarget.TICKETS), null)).isEqualTo(0);
		rows = sheetRows(b, "チケット");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).contains("チケットID", "大分類");
		assertThat(logText()).contains("ルートチケット #13 の配下にプロジェクトのチケットがありません");
		Mockito.verify(client, Mockito.never()).getIssue(Mockito.any());
	}

	@Test
	@DisplayName("--export: ルートチケットなし（sync.files にない出力先）は従来どおりプロジェクト全体を出力する")
	void exportRunner_withoutRoot_unchanged() throws Exception {
		RedmineClient client = Mockito.mock(RedmineClient.class);
		when(client.listProjectIssues()).thenReturn(issues());
		Path config = exportConfig("{ path: wbs-a.xlsx, rootIssueId: 10 }");
		Path out = tempDir.resolve("all.xlsx");
		assertThat(exportRunner(client).run(config.toString(), null, out.toString(),
				tempDir.resolve("logs").toString(), false, Set.of(SyncTarget.TICKETS), null)).isEqualTo(0);
		List<List<String>> rows = sheetRows(out, "チケット");
		int id = rows.get(0).indexOf("チケットID");
		assertThat(rows.subList(1, rows.size())).extracting(r -> r.get(id)).containsExactly("1", "2", "3", "4");
		Mockito.verify(client, Mockito.never()).getIssue(Mockito.any());

		// プロジェクト全体では6階層の木があるとエラー（従来どおり）
		when(client.listProjectIssues()).thenReturn(rootIssues());
		assertThat(exportRunner(client).run(config.toString(), null, out.toString(),
				tempDir.resolve("logs").toString(), false, Set.of(SyncTarget.TICKETS), null)).isEqualTo(1);
		assertThat(logText()).contains("Redmine のチケットの親子が 6 階層");
	}

	@Test
	@DisplayName("参照用シート: トラッカー（標準フィールドの ○）・ステータス・カスタムフィールド（トラッカーの ○）・選択肢を1行ずつ出力")
	void export_referenceSheets() throws Exception {
		List<Map<String, Object>> trackers = List.of(
				new LinkedHashMap<>(Map.of("id", 2, "name", "タスク", "default_status", Map.of("id", 1, "name", "新規"),
						"description", "作業", "enabled_standard_fields", List.of("assigned_to_id", "start_date",
								"due_date", "done_ratio"))),
				new LinkedHashMap<>(Map.of("id", 6, "name", "サマリ", "default_status", Map.of("id", 1, "name", "新規"),
						"enabled_standard_fields", List.of("start_date", "due_date"))));
		List<Map<String, Object>> statuses = List.of(Map.of("id", 1, "name", "新規", "is_closed", false),
				Map.of("id", 5, "name", "完了", "is_closed", true));
		List<Map<String, Object>> customFields = List.of(
				new LinkedHashMap<>(Map.of("id", 12, "name", "工程", "customized_type", "issue", "field_format", "list",
						"multiple", false, "is_required", true,
						"possible_values", List.of(Map.of("value", "設計"), Map.of("value", "実装")),
						"trackers", List.of(Map.of("id", 2, "name", "タスク")))),
				new LinkedHashMap<>(Map.of("id", 13, "name", "重要度", "customized_type", "issue", "field_format",
						"enumeration", "multiple", true, "is_required", false,
						"possible_values", List.of(Map.of("value", "3", "label", "高"), Map.of("value", "4", "label", "低")),
						"trackers", List.of(Map.of("id", 2, "name", "タスク"), Map.of("id", 6, "name", "サマリ")))),
				new LinkedHashMap<>(Map.of("id", 7, "name", "社員番号", "customized_type", "user", "field_format",
						"string", "multiple", false, "is_required", false)),
				new LinkedHashMap<>(Map.of("id", 9, "name", "予算", "customized_type", "project", "field_format",
						"int")));
		Path xlsx = tempDir.resolve("ref.xlsx");
		WorkbookExporter.ExportResult result = new WorkbookExporter().export(xlsx,
				new WorkbookExporter.ExportData(issues(), null, null, null,
						new WorkbookExporter.ReferenceData(trackers, statuses, customFields)),
				config(null));
		assertThat(result.sheets()).containsExactly(Map.entry("チケット", 4), Map.entry("トラッカー", 2),
				Map.entry("ステータス", 2), Map.entry("カスタムフィールド", 4), Map.entry("カスタムフィールド選択肢", 4));

		List<List<String>> trackerRows = sheetRows(xlsx, "トラッカー");
		assertThat(trackerRows.get(0)).startsWith("ID", "トラッカー名", "既定のステータス", "説明", "担当者", "カテゴリ");
		int assignee = trackerRows.get(0).indexOf("担当者");
		int start = trackerRows.get(0).indexOf("開始日");
		assertThat(trackerRows.get(1)).startsWith("2", "タスク", "新規", "作業");
		assertThat(trackerRows.get(1).get(assignee)).isEqualTo("○");
		assertThat(trackerRows.get(2).get(assignee)).isEmpty();
		assertThat(trackerRows.get(2).get(start)).isEqualTo("○");
		assertThat(String.join(",", trackerRows.get(0))).contains("参照用");

		assertThat(sheetRows(xlsx, "ステータス").get(2)).startsWith("5", "完了", "はい");

		List<List<String>> fieldRows = sheetRows(xlsx, "カスタムフィールド");
		List<String> header = fieldRows.get(0);
		assertThat(header).startsWith("ID", "名前", "取込列名", "対象", "形式", "複数選択", "必須", "既定値", "選択肢の数",
				"トラッカー:タスク", "トラッカー:サマリ");
		assertThat(fieldRows.get(1)).startsWith("12", "工程", "CF:工程", "チケット", "リスト", "いいえ", "はい", "", "2",
				"○", "");
		assertThat(fieldRows.get(2)).startsWith("13", "重要度", "CF:重要度", "チケット", "キー・バリュー リスト", "はい",
				"いいえ", "", "2", "○", "○");
		assertThat(fieldRows.get(3)).startsWith("7", "社員番号", "CF:社員番号", "ユーザー", "文字列");
		assertThat(fieldRows.get(4)).startsWith("9", "予算", "", "プロジェクト", "整数");

		List<List<String>> valueRows = sheetRows(xlsx, "カスタムフィールド選択肢");
		assertThat(valueRows.get(0)).startsWith("カスタムフィールドID", "カスタムフィールド名", "対象", "値", "表示名");
		assertThat(valueRows.subList(1, valueRows.size())).extracting(r -> r.subList(0, 5)).containsExactly(
				List.of("12", "工程", "チケット", "設計", "設計"), List.of("12", "工程", "チケット", "実装", "実装"),
				List.of("13", "重要度", "チケット", "3", "高"), List.of("13", "重要度", "チケット", "4", "低"));

		// 取り込みは参照用シートを読まない（先頭のチケットのシートだけ）
		ParsedSheet tickets = new SpreadsheetParser().parseFromPath(xlsx.toString(), ExcelSource.DEFAULT);
		DiffPlan plan = new DiffCalculator().calculate(tickets, config(null),
				new TrackerResolver(config(null).getSync().getTrackerMap(), null), logger, false);
		assertThat(plan.errors()).isEmpty();
		assertThat(plan.items()).hasSize(4);
	}

	@Test
	@DisplayName("--export: カスタムフィールドを取得できない（403）場合は警告してそのシートだけ省略する")
	void exportRunner_referenceForbidden() throws Exception {
		RedmineClient client = Mockito.mock(RedmineClient.class);
		when(client.listProjectIssues()).thenReturn(issues());
		when(client.listTrackerDetails()).thenReturn(List.of(Map.of("id", 2, "name", "タスク")));
		when(client.listIssueStatusDetails()).thenReturn(List.of(Map.of("id", 1, "name", "新規")));
		when(client.listCustomFields()).thenThrow(org.springframework.web.client.HttpClientErrorException.create(
				org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden", null, null, null));
		Path xlsx = tempDir.resolve("out.xlsx");
		int exit = exportRunner(client).run(exportConfig().toString(), null, xlsx.toString(),
				tempDir.resolve("logs").toString(), false, Set.of(SyncTarget.TICKETS), null);
		assertThat(exit).isEqualTo(0);
		try (InputStream in = Files.newInputStream(xlsx); Workbook wb = WorkbookFactory.create(in)) {
			assertThat(wb.getSheet("トラッカー")).isNotNull();
			assertThat(wb.getSheet("ステータス")).isNotNull();
			assertThat(wb.getSheet("カスタムフィールド")).isNull();
			assertThat(wb.getSheet("カスタムフィールド選択肢")).isNull();
		}
		assertThat(logText()).contains("カスタムフィールドを取得できません（403。カスタムフィールドの一覧の取得には管理者の API キーが必要です。");
	}

	@Test
	@DisplayName("標準フィールドの取込列名を変えると、出力も取り込みもその列名を使う")
	void configurableColumns_roundTrip() throws Exception {
		ProjectConfig config = config(null);
		SyncConfigProperties.ColumnsConfig columns = new SyncConfigProperties.ColumnsConfig();
		columns.setTicketIdColumn("#");
		columns.setTrackerColumn("種別");
		columns.setHierarchy(List.of("L1", "L2", "L3"));
		columns.setSubjectColumn("件名");
		columns.setStatusColumn("状態");
		columns.setAssigneeColumn("担当者");
		columns.setStartDateColumn("開始日");
		columns.setDueDateColumn("期日");
		columns.setProgressColumn("進捗");
		columns.setDescriptionColumn("メモ");
		config.getSync().setColumns(columns);
		Map<Long, Map<String, Object>> issues = issues();
		issues.get(3L).put("description", "説明文");
		Path xlsx = tempDir.resolve("cols.xlsx");
		new WorkbookExporter().export(xlsx, new WorkbookExporter.ExportData(issues, users(), null), config, users(),
				null);
		List<List<String>> rows = sheetRows(xlsx, "チケット");
		assertThat(rows.get(0)).startsWith("#", "種別", "L1", "L2", "L3", "状態", "担当者", "開始日", "期日", "進捗", "メモ");

		ParsedSheet tickets = new SpreadsheetParser().parseFromPath(xlsx.toString(), new ExcelSource("チケット", null));
		DiffPlan plan = new DiffCalculator().calculate(tickets, config,
				new TrackerResolver(config.getSync().getTrackerMap(), null), logger, false);
		assertThat(plan.errors()).isEmpty();
		DiffItem task = plan.items().stream().filter(i -> i.issueId() == 3L).findFirst().orElseThrow();
		assertThat(task.subject()).isEqualTo("ログイン画面");
		assertThat(task.trackerId()).isEqualTo(2L);
		assertThat(task.status()).isEqualTo("新規");
		assertThat(task.payload()).containsEntry("assignee", "tanaka").containsEntry("startDate", "2026-10-01")
				.containsEntry("dueDate", "2026-10-10").containsEntry("progress", 50)
				.containsEntry("description", "説明文");
	}

	@Test
	@DisplayName("--sync: 出力したグループメンバーのシートに行を足すとメンバーを追加し、行を消したグループ以外は変更しない")
	void syncRunner_groupMembersSheet() throws Exception {
		Map<Long, Map<String, Object>> users = users();
		users.put(6L, new LinkedHashMap<>(Map.of("id", 6, "login", "sato", "lastname", "佐藤", "firstname", "次郎",
				"mail", "sato@example.com", "admin", false, "status", 1)));
		Map<Long, Map<String, Object>> groups = groups();
		groups.put(21L, new LinkedHashMap<>(Map.of("id", 21, "name", "営業", "users", List.of(Map.of("id", 6)))));
		Path xlsx = tempDir.resolve("groups.xlsx");
		new WorkbookExporter().export(xlsx, new WorkbookExporter.ExportData(null, users, groups), config(null));
		try (InputStream in = Files.newInputStream(xlsx); Workbook wb = WorkbookFactory.create(in)) {
			Sheet members = wb.getSheet("グループメンバー");
			assertThat(members.getLastRowNum()).isEqualTo(2);
			// 開発に sato を追加、営業（sato）の行は消す（営業のメンバーは変更しない）
			members.getRow(2).getCell(0).setCellValue("開発");
			try (OutputStream out = Files.newOutputStream(xlsx)) {
				wb.write(out);
			}
		}
		RedmineClient client = Mockito.mock(RedmineClient.class);
		when(client.listUsers()).thenReturn(users);
		when(client.listGroups()).thenReturn(groups);
		RedmineClientFactory factory = Mockito.mock(RedmineClientFactory.class);
		when(factory.createClient(Mockito.any())).thenReturn(client);
		SyncRunner runner = new SyncRunner(new SyncConfigService(new SyncConfigProperties()), new SpreadsheetParser(),
				new DiffCalculator(), Mockito.mock(SyncExecutor.class), factory, new TicketIdWriter(),
				new DirectorySync());
		int exit = runner.run(exportConfig().toString(), null, xlsx.toString(), false,
				tempDir.resolve("logs").toString(), false, false, null, null, null);
		assertThat(exit).isEqualTo(0);
		Mockito.verify(client).updateGroup(20L, Map.of("user_ids", List.of(5L, 6L)));
		Mockito.verify(client, Mockito.never()).updateGroup(Mockito.eq(21L), anyMap());
		Mockito.verify(client, Mockito.never()).updateUser(Mockito.any(), anyMap());
		assertThat(logText()).contains("Group Members: ");
	}
}
