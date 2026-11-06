package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.FullBackupConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.RedmineClientFactory;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.service.SyncConfigService;
import mozaki.redmineUpster.service.TicketIdWriter;

/**
 * 全内容のバックアップ（--backup）のテスト。ローカルの疑似 Redmine（HTTP サーバー）を相手に実際に通信する。
 */
class BackupTests {

	private static final String API_KEY = "secret-key";
	private static final int ISSUES = 250;

	@TempDir
	Path tempDir;

	private HttpServer server;
	private final ObjectMapper json = new ObjectMapper();
	/** 403 を返すパス（先頭一致） */
	private final Set<String> forbidden = ConcurrentHashMap.newKeySet();
	/** 受け取ったリクエスト（パス?クエリ） */
	private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
	private final Set<String> apiKeysSeen = ConcurrentHashMap.newKeySet();
	private String longDescription;

	@BeforeEach
	void setUp() throws IOException {
		longDescription = "長い説明".repeat(10_000) + "END";
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
		server.start();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	private String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	// ------------------------------------------------------------ 疑似 Redmine

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getRawPath();
		String query = exchange.getRequestURI().getRawQuery();
		requests.add(path + (query == null ? "" : "?" + query));
		String key = exchange.getRequestHeaders().getFirst("X-Redmine-API-Key");
		if (key != null) {
			apiKeysSeen.add(path.startsWith("/attachments/") ? "download:" + key : key);
		}
		Map<String, String> params = parseQuery(query);
		String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);
		for (String prefix : forbidden) {
			if (decodedPath.startsWith(prefix)) {
				send(exchange, 403, "{}");
				return;
			}
		}
		if (path.startsWith("/attachments/download/")) {
			String id = path.substring("/attachments/download/".length());
			if (id.equals("10")) {
				byte[] body = "添付の中身".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			} else {
				send(exchange, 404, "{}");
			}
			return;
		}
		Object body = switch (decodedPath) {
		case "/projects/proj.json" -> Map.of("project", Map.of("id", 7, "name", "テスト案件", "identifier", "proj",
				"issue_custom_fields", List.of(Map.of("id", 1, "name", "顧客")),
				"enabled_modules", List.of(Map.of("name", "issue_tracking"), Map.of("name", "wiki"))));
		case "/trackers.json" -> Map.of("trackers", List.of(Map.of("id", 2, "name", "タスク",
				"default_status", Map.of("id", 1, "name", "新規"))));
		case "/issue_statuses.json" -> Map.of("issue_statuses", List.of(Map.of("id", 1, "name", "新規", "is_closed", false),
				Map.of("id", 5, "name", "終了", "is_closed", true)));
		case "/enumerations/issue_priorities.json" -> Map.of("issue_priorities",
				List.of(Map.of("id", 2, "name", "通常", "is_default", true, "active", true)));
		case "/enumerations/time_entry_activities.json" -> Map.of("time_entry_activities",
				List.of(Map.of("id", 9, "name", "開発", "is_default", true, "active", true)));
		case "/projects/proj/versions.json" -> Map.of("versions", List.of(Map.of("id", 3, "name", "v1.0",
				"project", Map.of("id", 7, "name", "テスト案件"), "status", "open", "due_date", "2026-12-31",
				"sharing", "none")));
		case "/projects/proj/issue_categories.json" -> Map.of("issue_categories",
				List.of(Map.of("id", 4, "name", "設計", "project", Map.of("id", 7, "name", "テスト案件"))));
		case "/projects/proj/memberships.json" -> Map.of("memberships", List.of(Map.of("id", 1,
				"project", Map.of("id", 7, "name", "テスト案件"), "user", Map.of("id", 100, "name", "山田 太郎"),
				"roles", List.of(Map.of("id", 3, "name", "開発者")))), "total_count", 1, "offset", 0, "limit", 100);
		case "/time_entries.json" -> Map.of("time_entries", List.of(
				Map.of("id", 1, "project", Map.of("id", 7, "name", "テスト案件"), "issue", Map.of("id", 1),
						"user", Map.of("id", 100, "name", "山田 太郎"), "activity", Map.of("id", 9, "name", "開発"),
						"hours", 1.5, "comments", "作業", "spent_on", "2026-11-01", "created_on", "2026-11-01T01:00:00Z"),
				Map.of("id", 2, "project", Map.of("id", 7, "name", "テスト案件"),
						"user", Map.of("id", 100, "name", "山田 太郎"), "activity", Map.of("id", 9, "name", "開発"),
						"hours", 2, "spent_on", "2026-11-02")),
				"total_count", 2, "offset", 0, "limit", 100);
		case "/projects/proj/wiki/index.json" -> Map.of("wiki_pages", List.of(
				Map.of("title", "Wiki", "version", 2, "updated_on", "2026-11-01T00:00:00Z"),
				Map.of("title", "設計 メモ", "parent", Map.of("title", "Wiki"), "version", 1)));
		case "/projects/proj/wiki/Wiki.json" -> Map.of("wiki_page", Map.of("title", "Wiki", "text", "トップページ",
				"version", 2, "author", Map.of("id", 100, "name", "山田 太郎"), "attachments", List.of()));
		case "/projects/proj/wiki/設計 メモ.json" -> Map.of("wiki_page", Map.of("title", "設計 メモ", "text", "メモ本文",
				"parent", Map.of("title", "Wiki"), "version", 1));
		case "/issues.json" -> issuesPage(params);
		default -> decodedPath.matches("/issues/\\d+\\.json") ? issueDetail(Long.parseLong(
				decodedPath.substring("/issues/".length(), decodedPath.length() - ".json".length()))) : null;
		};
		if (body == null) {
			send(exchange, 404, "{}");
			return;
		}
		send(exchange, 200, json.writeValueAsString(body));
	}

	private Map<String, Object> issuesPage(Map<String, String> params) {
		assertThat(params.get("status_id")).isEqualTo("*");
		int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
		int limit = Integer.parseInt(params.getOrDefault("limit", "25"));
		List<Map<String, Object>> page = new ArrayList<>();
		for (int id = offset + 1; id <= Math.min(ISSUES, offset + limit); id++) {
			page.add(issue(id));
		}
		return Map.of("issues", page, "total_count", ISSUES, "offset", offset, "limit", limit);
	}

	private Map<String, Object> issue(long id) {
		Map<String, Object> issue = new LinkedHashMap<>();
		issue.put("id", id);
		issue.put("project", Map.of("id", 7, "name", "テスト案件"));
		issue.put("tracker", Map.of("id", 2, "name", "タスク"));
		boolean closed = id == 5;
		issue.put("status", closed ? Map.of("id", 5, "name", "終了") : Map.of("id", 1, "name", "新規"));
		issue.put("priority", Map.of("id", 2, "name", "通常"));
		issue.put("author", Map.of("id", 100, "name", "山田 太郎"));
		issue.put("subject", "チケット" + id);
		issue.put("description", id == 3 ? longDescription : "説明" + id);
		issue.put("start_date", "2026-11-01");
		issue.put("done_ratio", 10);
		issue.put("created_on", "2026-11-01T00:00:00Z");
		issue.put("updated_on", "2026-11-02T00:00:00Z");
		if (closed) {
			issue.put("closed_on", "2026-11-03T00:00:00Z");
		}
		if (id > 1) {
			issue.put("parent", Map.of("id", 1));
		}
		issue.put("custom_fields", List.of(Map.of("id", 1, "name", "顧客", "value", "A社" + id)));
		return issue;
	}

	private Map<String, Object> issueDetail(long id) {
		if (id > ISSUES) {
			return null;
		}
		Map<String, Object> issue = issue(id);
		issue.put("spent_hours", id == 1 ? 1.5 : 0.0);
		if (id == 1) {
			issue.put("journals", List.of(
					Map.of("id", 11, "user", Map.of("id", 100, "name", "山田 太郎"), "notes", "コメントです",
							"created_on", "2026-11-02T00:00:00Z", "private_notes", false,
							"details", List.of(
									Map.of("property", "attr", "name", "status_id", "old_value", "1", "new_value", "5"),
									Map.of("property", "cf", "name", "1", "old_value", "", "new_value", "A社1"))),
					Map.of("id", 12, "user", Map.of("id", 100, "name", "山田 太郎"), "notes", "メモだけ",
							"created_on", "2026-11-03T00:00:00Z", "private_notes", false, "details", List.of())));
			issue.put("watchers", List.of(Map.of("id", 100, "name", "山田 太郎")));
			issue.put("relations", List.of(Map.of("id", 50, "issue_id", 1, "issue_to_id", 2, "relation_type", "relates")));
		} else {
			issue.put("journals", List.of());
		}
		if (id == 2) {
			issue.put("relations", List.of(Map.of("id", 50, "issue_id", 1, "issue_to_id", 2, "relation_type", "relates")));
			issue.put("attachments", List.of(
					Map.of("id", 10, "filename", "報告:書?.txt", "filesize", 15, "content_type", "text/plain",
							"author", Map.of("id", 100, "name", "山田 太郎"), "created_on", "2026-11-01T00:00:00Z"),
					Map.of("id", 11, "filename", "消えた.pdf", "filesize", 1)));
		}
		return Map.of("issue", issue);
	}

	private static Map<String, String> parseQuery(String query) {
		Map<String, String> params = new LinkedHashMap<>();
		if (query == null) {
			return params;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			params.put(k, v);
		}
		return params;
	}

	private static void send(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	// ------------------------------------------------------------ 実行の補助

	private Path writeConfig(String fullBackup) throws IOException {
		Path config = tempDir.resolve("sync-config.yml");
		Files.writeString(config, String.join("\n",
				"projects:",
				"  - name: t",
				"    default: true",
				"    redmine: { baseUrl: '" + baseUrl() + "', apiKey: " + API_KEY + ", projectId: proj }",
				"    sync:",
				"      fullBackup:",
				fullBackup,
				""));
		return config;
	}

	private int runBackup(Path config, BackupRunner.Overrides overrides, int maxDataRows) {
		SyncConfigService configService = new SyncConfigService(new SyncConfigProperties());
		RedmineClientFactory factory = Mockito.mock(RedmineClientFactory.class);
		when(factory.createClient(any(ProjectConfig.class))).thenAnswer(invocation -> {
			ProjectConfig project = invocation.getArgument(0);
			RedmineClient client = new RedmineClient(project.getRedmine().getBaseUrl(), project.getRedmine().getApiKey(),
					project.getRedmine().getProjectId(), new RestTemplate());
			client.setDownloadRequestFactory(new SimpleClientHttpRequestFactory());
			return client;
		});
		SyncRunner syncRunner = new SyncRunner(configService, new SpreadsheetParser(), new DiffCalculator(),
				Mockito.mock(SyncExecutor.class), factory, new TicketIdWriter(), new DirectorySync());
		BackupRunner runner = new BackupRunner(syncRunner, configService, factory);
		return runner.run(config.toString(), null, false, overrides, tempDir.resolve("logs").toString(), false,
				maxDataRows);
	}

	private List<Path> backups(Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> p.getFileName().toString().matches("proj_\\d{8}_\\d{6}\\.xlsx")).sorted().toList();
		}
	}

	private static List<String> sheetNames(Workbook workbook) {
		List<String> names = new ArrayList<>();
		workbook.forEach(sheet -> names.add(sheet.getSheetName()));
		return names;
	}

	private static int column(Sheet sheet, String header) {
		for (Cell cell : sheet.getRow(0)) {
			if (header.equals(cell.getStringCellValue())) {
				return cell.getColumnIndex();
			}
		}
		throw new AssertionError("列がありません: " + header);
	}

	private static String cell(Sheet sheet, int row, String header) {
		Row r = sheet.getRow(row);
		Cell c = r == null ? null : r.getCell(column(sheet, header));
		return c == null ? "" : new DataFormatter().formatCellValue(c);
	}

	private static String info(Sheet sheet) {
		StringBuilder text = new StringBuilder();
		for (Row row : sheet) {
			text.append(new DataFormatter().formatCellValue(row.getCell(0))).append('=')
					.append(new DataFormatter().formatCellValue(row.getCell(1))).append('\n');
		}
		return text.toString();
	}

	// ------------------------------------------------------------ テスト

	@Test
	@DisplayName("全件をページングで取得し、チケット・履歴・関連・添付・作業時間などのシートを出力する（Wiki の 403 は警告して省略）")
	void backupWritesAllSheets() throws Exception {
		forbidden.add("/projects/proj/wiki/");
		forbidden.add("/custom_fields.json");
		forbidden.add("/users.json");
		forbidden.add("/groups.json");
		Path config = writeConfig("        dir: out");

		int exit = runBackup(config, BackupRunner.Overrides.none(), BackupSheet.EXCEL_MAX_DATA_ROWS);

		assertThat(exit).isZero();
		Path dir = tempDir.resolve("out");
		List<Path> backups = backups(dir);
		assertThat(backups).hasSize(1);
		try (Stream<Path> files = Files.list(dir)) {
			assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
		}
		// 3ページ（100件ずつ）取得
		assertThat(requests.stream().filter(r -> r.startsWith("/issues.json")).count()).isEqualTo(3);
		assertThat(requests).anyMatch(r -> r.startsWith("/issues.json") && r.contains("subproject_id=*"));
		assertThat(apiKeysSeen).contains(API_KEY, "download:" + API_KEY);

		String base = backups.get(0).getFileName().toString().replace(".xlsx", "");
		try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(backups.get(0)))) {
			assertThat(sheetNames(workbook)).containsExactly("情報", "チケット", "履歴", "ウォッチャー", "関連", "添付ファイル",
					"作業時間", "バージョン", "カテゴリ", "メンバー", "トラッカー", "ステータス", "優先度", "作業分類");

			Sheet tickets = workbook.getSheet("チケット");
			assertThat(tickets.getLastRowNum()).isEqualTo(ISSUES);
			assertThat(cell(tickets, 1, "ID")).isEqualTo("1");
			assertThat(cell(tickets, ISSUES, "ID")).isEqualTo(String.valueOf(ISSUES));
			assertThat(cell(tickets, 5, "ステータス")).isEqualTo("終了");
			assertThat(cell(tickets, 5, "終了日時")).isNotEmpty();
			assertThat(cell(tickets, 2, "CF:顧客")).isEqualTo("A社2");
			assertThat(cell(tickets, 2, "親チケット")).isEqualTo("1");
			assertThat(cell(tickets, 1, "作業時間")).isEqualTo("1.5");

			// 32,767 文字を超える説明は先頭だけ。全文は long_text のテキストファイル
			String description = cell(tickets, 3, "説明");
			assertThat(description.length()).isLessThanOrEqualTo(BackupSheet.EXCEL_MAX_CELL_CHARS);
			assertThat(description).startsWith("長い説明").contains("全文: " + base + "_files/long_text/");
			try (Stream<Path> longTexts = Files.list(dir.resolve(base + "_files/long_text"))) {
				Path text = longTexts.findFirst().orElseThrow();
				assertThat(Files.readString(text)).isEqualTo(longDescription);
			}

			Sheet journals = workbook.getSheet("履歴");
			assertThat(journals.getLastRowNum()).isEqualTo(3);
			assertThat(cell(journals, 1, "注記")).isEqualTo("コメントです");
			assertThat(cell(journals, 1, "変更後")).isEqualTo("終了 (5)");
			assertThat(cell(journals, 2, "注記")).isEmpty();
			assertThat(cell(journals, 2, "項目")).isEqualTo("CF:顧客 (1)");
			assertThat(cell(journals, 3, "注記")).isEqualTo("メモだけ");

			assertThat(workbook.getSheet("ウォッチャー").getLastRowNum()).isEqualTo(1);
			// 関連は両方のチケットにあるが1行
			assertThat(workbook.getSheet("関連").getLastRowNum()).isEqualTo(1);

			Sheet attachments = workbook.getSheet("添付ファイル");
			assertThat(attachments.getLastRowNum()).isEqualTo(2);
			// 日本語のファイル名を作れない環境（LANG 未設定の Linux）では 10.txt
			String saved = cell(attachments, 1, "保存先");
			assertThat(saved).isIn(base + "_files/2/10_報告_書_.txt", base + "_files/2/10.txt");
			assertThat(Files.readString(dir.resolve(saved))).isEqualTo("添付の中身");
			assertThat(cell(attachments, 2, "保存先")).startsWith("（保存できません");

			assertThat(workbook.getSheet("作業時間").getLastRowNum()).isEqualTo(2);
			assertThat(cell(workbook.getSheet("メンバー"), 1, "ロール")).isEqualTo("開発者");

			String infoText = info(workbook.getSheet("情報"));
			assertThat(infoText).contains("テスト案件").contains("チケット=250 件").contains("Wiki")
					.contains("HTTP 403").contains("シート「チケット」=250 行");
		}
	}

	@Test
	@DisplayName("Wiki のページと本文を出力する。--no-journals なら履歴を出さず、一覧の include で添付・関連を取る")
	void wikiAndNoJournals() throws Exception {
		Path config = writeConfig("        dir: out");

		int exit = runBackup(config, new BackupRunner.Overrides(null, null, null, false, false, false, null),
				BackupSheet.EXCEL_MAX_DATA_ROWS);

		assertThat(exit).isZero();
		assertThat(requests).noneMatch(r -> r.matches("/issues/\\d+\\.json.*"));
		assertThat(requests).anyMatch(r -> r.startsWith("/issues.json") && r.contains("include=attachments"));
		assertThat(requests).noneMatch(r -> r.startsWith("/attachments/"));
		assertThat(requests).noneMatch(r -> r.startsWith("/time_entries.json"));
		try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(backups(tempDir.resolve("out")).get(0)))) {
			assertThat(sheetNames(workbook)).doesNotContain("履歴", "ウォッチャー", "作業時間").contains("Wiki");
			Sheet wiki = workbook.getSheet("Wiki");
			assertThat(wiki.getLastRowNum()).isEqualTo(2);
			assertThat(cell(wiki, 2, "ページ名")).isEqualTo("設計 メモ");
			assertThat(cell(wiki, 2, "親ページ")).isEqualTo("Wiki");
			assertThat(cell(wiki, 2, "本文")).isEqualTo("メモ本文");
		}
	}

	@Test
	@DisplayName("1シートの行数の上限を超えたら「チケット(2)」以降のシートに続ける")
	void splitsSheetsAtRowLimit() throws Exception {
		Path config = writeConfig("        dir: out\n        journals: false");

		assertThat(runBackup(config, BackupRunner.Overrides.none(), 100)).isZero();

		try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(backups(tempDir.resolve("out")).get(0)))) {
			assertThat(sheetNames(workbook)).contains("チケット", "チケット(2)", "チケット(3)");
			assertThat(workbook.getSheet("チケット").getLastRowNum()).isEqualTo(100);
			assertThat(workbook.getSheet("チケット(3)").getLastRowNum()).isEqualTo(50);
			assertThat(cell(workbook.getSheet("チケット(3)"), 1, "ID")).isEqualTo("201");
		}
	}

	@Test
	@DisplayName("チケットを取得できなければ失敗（終了コード1）し、書き出し途中のファイルを残さない")
	void failureLeavesNoPartialFile() throws Exception {
		forbidden.add("/issues.json");
		Path config = writeConfig("        dir: out");

		assertThat(runBackup(config, BackupRunner.Overrides.none(), BackupSheet.EXCEL_MAX_DATA_ROWS)).isEqualTo(1);

		try (Stream<Path> files = Files.list(tempDir.resolve("out"))) {
			assertThat(files.toList()).isEmpty();
		}
	}

	@Test
	@DisplayName("成功後、同じプロジェクトの古いバックアップ（このツールの名前のものだけ）を keep 世代まで削除する")
	void retentionDeletesOnlyOlderMatchingFiles() throws Exception {
		Path dir = Files.createDirectories(tempDir.resolve("out"));
		Files.writeString(dir.resolve("proj_20200101_000000.xlsx"), "old");
		Files.createDirectories(dir.resolve("proj_20200101_000000_files/1"));
		Files.writeString(dir.resolve("proj_20200101_000000_files/1/a.txt"), "a");
		Files.writeString(dir.resolve("proj_20210101_000000.xlsx"), "newer");
		Files.writeString(dir.resolve("other_20200101_000000.xlsx"), "other project");
		Files.writeString(dir.resolve("proj_manual.xlsx"), "manual");
		Files.writeString(dir.resolve("proj_20190101_000000.xlsx.bak"), "not ours");
		Path config = writeConfig("        dir: out\n        keep: 2\n        journals: false\n        wiki: false");

		assertThat(runBackup(config, BackupRunner.Overrides.none(), BackupSheet.EXCEL_MAX_DATA_ROWS)).isZero();

		List<Path> backups = backups(dir);
		assertThat(backups).hasSize(2);
		assertThat(backups.get(0).getFileName().toString()).isEqualTo("proj_20210101_000000.xlsx");
		assertThat(dir.resolve("proj_20200101_000000.xlsx")).doesNotExist();
		assertThat(dir.resolve("proj_20200101_000000_files")).doesNotExist();
		assertThat(dir.resolve("other_20200101_000000.xlsx")).exists();
		assertThat(dir.resolve("proj_manual.xlsx")).exists();
		assertThat(dir.resolve("proj_20190101_000000.xlsx.bak")).exists();
	}

	@Test
	@DisplayName("--file 指定ならその名前で出力し、世代管理しない")
	void explicitFile() throws Exception {
		Path dir = Files.createDirectories(tempDir.resolve("out"));
		Files.writeString(dir.resolve("proj_20200101_000000.xlsx"), "old");
		Path config = writeConfig("        dir: out\n        keep: 1\n        journals: false");
		Path out = tempDir.resolve("manual/backup.xlsx");

		assertThat(runBackup(config, new BackupRunner.Overrides(out.toString(), null, null, null, null, null, null),
				BackupSheet.EXCEL_MAX_DATA_ROWS)).isZero();

		assertThat(out).exists();
		assertThat(dir.resolve("proj_20200101_000000.xlsx")).exists();
	}

	@Test
	@DisplayName("設定: 最上位の fullBackup にプロジェクトの sync.fullBackup を項目ごとに重ね、dir は設定ファイルのフォルダ基準")
	void configMerge() throws Exception {
		Path config = tempDir.resolve("conf/sync-config.yml");
		Files.createDirectories(config.getParent());
		Files.writeString(config, String.join("\n",
				"fullBackup:",
				"  dir: bk",
				"  keep: 5",
				"  wiki: false",
				"projects:",
				"  - name: a",
				"    redmine: { baseUrl: 'http://x', apiKey: k, projectId: a }",
				"    sync:",
				"      fullBackup: { keep: 2, attachments: false }",
				"  - name: b",
				"    redmine: { baseUrl: 'http://x', apiKey: k, projectId: b }",
				""));
		SyncConfigService service = new SyncConfigService(new SyncConfigProperties());
		service.loadConfig(config.toString());

		FullBackupConfig a = service.getProjectByName("a").orElseThrow().getFullBackup();
		FullBackupConfig b = service.getProjectByName("b").orElseThrow().getFullBackup();
		assertThat(a.getDir()).isEqualTo(tempDir.resolve("conf/bk").toAbsolutePath().normalize().toString());
		assertThat(a.getKeep()).isEqualTo(2);
		assertThat(a.isAttachments()).isFalse();
		assertThat(a.isWiki()).isFalse();
		assertThat(a.isJournals()).isTrue();
		assertThat(b.getKeep()).isEqualTo(5);
		assertThat(b.isAttachments()).isTrue();

		Files.writeString(config, "fullBackup: { keep: -1 }\nprojects:\n  - name: a\n"
				+ "    redmine: { baseUrl: 'http://x', apiKey: k, projectId: a }\n");
		assertThatThrownBy(() -> service.loadConfig(config.toString())).hasMessageContaining("fullBackup.keep");
	}

	@Test
	@DisplayName("CLI: --backup の上書きオプション")
	void parseOverrides() {
		BackupRunner.Overrides o = SyncCommand.parseBackupOverrides(new String[] { "--backup", "--backup-dir=D:\\bk",
				"--keep=3", "--no-journals", "--no-attachments", "--no-time-entries", "--wiki" });
		assertThat(o.dir()).isEqualTo("D:\\bk");
		assertThat(o.keep()).isEqualTo(3);
		assertThat(o.journals()).isFalse();
		assertThat(o.attachments()).isFalse();
		assertThat(o.timeEntries()).isFalse();
		assertThat(o.wiki()).isTrue();
		assertThat(o.file()).isNull();
		assertThatThrownBy(() -> SyncCommand.parseBackupOverrides(new String[] { "--backup", "--keep=x" }))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SyncCommand.parseBackupOverrides(new String[] { "--backup", "--file=a.xlsx",
				"--file=b.xlsx" })).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("ファイル名: Windows で使えない文字・予約名を置き換える")
	void sanitize() {
		assertThat(BackupNames.sanitize("a<b>:c\"d/e\\f|g?h*i.txt")).isEqualTo("a_b__c_d_e_f_g_h_i.txt");
		assertThat(BackupNames.sanitize("CON")).isEqualTo("_CON");
		assertThat(BackupNames.sanitize("name. ")).isEqualTo("name");
		assertThat(BackupNames.sanitize("")).isEqualTo("_");
		assertThat(BackupNames.sanitize("x".repeat(300) + ".pdf")).hasSize(120).endsWith(".pdf");
		assertThat(BackupNames.generationPattern("proj").matcher("proj_20261106_093000.xlsx").matches()).isTrue();
		assertThat(BackupNames.generationPattern("proj").matcher("proj2_20261106_093000.xlsx").matches()).isFalse();
	}
}
