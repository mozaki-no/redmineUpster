package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.service.ExcelSource;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.RedmineClientFactory;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.service.SyncConfigService;
import mozaki.redmineUpster.service.TicketIdWriter;

/**
 * 同期結果（最終同期日時・同期結果・エラー理由）の入力ファイルへの書き戻しのテスト（疑似Redmine）。
 */
class SyncResultColumnsTests {

	private static final String HEADER = "チケットID,トラッカー,大分類,タスク";
	private static final String RESULT_HEADER = HEADER + ",最終同期日時,同期結果,エラー理由";
	private static final String TIME = "\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}";

	@TempDir
	Path tempDir;

	private Map<Long, Map<String, Object>> redmine;
	private final AtomicLong nextId = new AtomicLong(100);
	private RedmineClient client;
	private RedmineClientFactory factory;
	private TicketIdWriter writer;

	@BeforeEach
	void setUp() {
		redmine = new LinkedHashMap<>();
		client = Mockito.mock(RedmineClient.class);
		when(client.getProjectId()).thenReturn("p");
		when(client.getBaseUrl()).thenReturn("http://redmine.local");
		when(client.listProjectIssues()).thenAnswer(invocation -> {
			Map<Long, Map<String, Object>> copy = new LinkedHashMap<>();
			redmine.forEach((id, issue) -> copy.put(id, new HashMap<>(issue)));
			return copy;
		});
		when(client.createIssue(anyMap())).thenAnswer(invocation -> {
			Map<String, Object> payload = invocation.getArgument(0);
			String subject = (String) payload.get("subject");
			if (subject.startsWith("NG")) {
				throw new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity",
						"{\"errors\":[\"題名を入力してください\"]}".getBytes(StandardCharsets.UTF_8),
						StandardCharsets.UTF_8);
			}
			long id = nextId.getAndIncrement();
			Object parent = payload.get("parent_issue_id");
			redmine.put(id, issue(id, subject, ((Number) payload.get("tracker_id")).longValue(),
					parent instanceof Number number ? number.longValue() : null));
			return id;
		});
		factory = Mockito.mock(RedmineClientFactory.class);
		when(factory.createClient(any())).thenReturn(client);
		writer = Mockito.spy(new TicketIdWriter());
	}

	private static Map<String, Object> issue(long id, String subject, long trackerId, Long parentId) {
		Map<String, Object> issue = new HashMap<>();
		issue.put("id", id);
		issue.put("subject", subject);
		issue.put("tracker", Map.of("id", trackerId));
		issue.put("status", Map.of("id", 1L));
		if (parentId != null) {
			issue.put("parent", Map.of("id", parentId));
		}
		return issue;
	}

	private Path csv(String name, String header, String... lines) throws IOException {
		Path file = tempDir.resolve(name);
		Files.writeString(file, header + "\n" + String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		return file;
	}

	private Path config(String... extraSync) throws IOException {
		List<String> lines = new ArrayList<>(List.of(
				"projects:",
				"  - name: t",
				"    default: true",
				"    redmine: { baseUrl: 'http://127.0.0.1:1', apiKey: k, projectId: p }",
				"    sync:",
				"      trackerMap: { サマリ: 6, タスク: 2 }",
				"      columns:",
				"        hierarchy: [大分類, タスク]"));
		lines.addAll(List.of(extraSync));
		Path config = tempDir.resolve("sync-config.yml");
		Files.writeString(config, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		return config;
	}

	private int run(Path config, Path file, boolean dryRun, boolean noResultColumns) {
		SyncConfigService configService = new SyncConfigService(new SyncConfigProperties());
		SyncRunner runner = new SyncRunner(configService, new SpreadsheetParser(), new DiffCalculator(),
				new SyncExecutor(), factory, writer, new DirectorySync());
		return runner.runFiles(config.toString(), null, List.of(file.toString()), dryRun,
				tempDir.resolve("logs").toString(), false, false, null, null, null, null, noResultColumns);
	}

	private List<String> lines(Path file) throws IOException {
		return Files.readAllLines(file, StandardCharsets.UTF_8);
	}

	private String logText() throws IOException {
		try (Stream<Path> files = Files.list(tempDir.resolve("logs"))) {
			StringBuilder text = new StringBuilder();
			for (Path log : files.toList()) {
				text.append(Files.readString(log, StandardCharsets.UTF_8));
			}
			return text.toString();
		}
	}

	@Test
	@DisplayName("作成 → 再実行で変更なし。最終同期日時は全行同じ値、IDと結果は1回の書き込み（.bak は元のファイル）")
	void createThenUnchanged() throws IOException {
		Path file = csv("a.csv", HEADER, ",サマリ,A,", ",タスク,A,a-task");
		Path config = config();

		assertThat(run(config, file, false, false)).isEqualTo(0);

		List<String> lines = lines(file);
		assertThat(lines.get(0)).isEqualTo(RESULT_HEADER);
		assertThat(lines.get(1)).matches("100,サマリ,A,," + TIME + ",作成,");
		assertThat(lines.get(2)).matches("101,タスク,A,a-task," + TIME + ",作成,");
		String syncedAt = lines.get(1).split(",")[4];
		assertThat(lines.get(2).split(",")[4]).isEqualTo(syncedAt);
		assertThat(tempDir.resolve("a.csv.bak")).hasContent(HEADER + "\n,サマリ,A,\n,タスク,A,a-task");
		Mockito.verify(writer, Mockito.times(1)).writeBack(anyString(), anyString(), anyMap(),
				any(ExcelSource.class), Mockito.anyBoolean(), any(TicketIdWriter.SyncResults.class));

		// 再実行: Redmineと同じ値なので変更なし（同期結果の列は差分に使わない）
		assertThat(run(config, file, false, false)).isEqualTo(0);
		lines = lines(file);
		assertThat(lines.get(1)).matches("100,サマリ,A,," + TIME + ",変更なし,");
		assertThat(lines.get(2)).matches("101,タスク,A,a-task," + TIME + ",変更なし,");
		verify(client, never()).updateIssue(anyLong(), anyMap());
	}

	@Test
	@DisplayName("行のAPIエラーはエラーと理由、その子は未実行。前回のエラー理由は成功した行で消える")
	void rowApiError_childNotRun_oldErrorCleared() throws IOException {
		Path file = csv("a.csv", RESULT_HEADER,
				",サマリ,A,,2000/01/01 00:00:00,エラー,前回のエラー",
				",サマリ,NG,,,,",
				",タスク,NG,child,,,");
		Path config = config();

		assertThat(run(config, file, false, false)).isEqualTo(1);

		List<String> lines = lines(file);
		assertThat(lines.get(0)).isEqualTo(RESULT_HEADER);
		assertThat(lines.get(1)).matches("100,サマリ,A,," + TIME + ",作成,");
		assertThat(lines.get(2)).matches(",サマリ,NG,," + TIME
				+ ",エラー,\"422 Unprocessable Entity \\{\"\"errors\"\":\\[\"\"題名を入力してください\"\"\\]\\}\"");
		assertThat(lines.get(3)).matches(",タスク,NG,child," + TIME + ",未実行,親行\\(行3\\)の同期に失敗したためスキップしました");
		assertThat(lines.get(1)).doesNotContain("2000/01/01");
		assertThat(logText()).contains("理由=422 Unprocessable Entity {\"errors\":[\"題名を入力してください\"]}");
	}

	@Test
	@DisplayName("入力エラー: Redmineに書き込まず、エラーの行はエラーと理由、ほかの行は未実行")
	void inputError_rowsMarked() throws IOException {
		Path file = csv("a.csv", HEADER, "1,サマリ,A,", ",不明,A,x", ",タスク,B,orphan");
		redmine.put(1L, issue(1, "A", 6, null));
		Path config = config();

		assertThat(run(config, file, false, false)).isEqualTo(1);

		verify(client, never()).createIssue(anyMap());
		verify(client, never()).updateIssue(anyLong(), anyMap());
		List<String> lines = lines(file);
		assertThat(lines.get(0)).isEqualTo(RESULT_HEADER);
		assertThat(lines.get(1)).matches("1,サマリ,A,," + TIME + ",未実行,"
				+ java.util.regex.Pattern.quote(SyncRunner.ABORTED_BY_INPUT_ERROR));
		assertThat(lines.get(2)).matches(",不明,A,x," + TIME + ",エラー,トラッカー「不明」が見つかりません.*");
		assertThat(lines.get(3)).matches(",タスク,B,orphan," + TIME + ",エラー,親行 \\[B\\] がファイルにありません.*");
	}

	@Test
	@DisplayName("dry-run では何も書き込まない（.bak も作らない）")
	void dryRun_noWrite() throws IOException {
		Path file = csv("a.csv", HEADER, ",サマリ,A,");
		String before = Files.readString(file);

		assertThat(run(config(), file, true, false)).isEqualTo(0);

		assertThat(file).hasContent(before);
		assertThat(tempDir.resolve("a.csv.bak")).doesNotExist();
	}

	@Test
	@DisplayName("--no-result-columns・sync.resultColumns.enabled: false では同期結果を書かない（IDは書き戻す）")
	void disabled() throws IOException {
		Path file = csv("a.csv", HEADER, ",サマリ,A,");
		assertThat(run(config(), file, false, true)).isEqualTo(0);
		assertThat(lines(file)).containsExactly(HEADER, "100,サマリ,A,");

		Path file2 = csv("b.csv", HEADER, ",サマリ,B,");
		assertThat(run(config("      resultColumns: { enabled: false }"), file2, false, false)).isEqualTo(0);
		assertThat(lines(file2)).containsExactly(HEADER, "101,サマリ,B,");

		// 変更がなければファイルは書き換えない
		assertThat(run(config("      resultColumns: { enabled: false }"), file2, false, false)).isEqualTo(0);
		assertThat(lines(file2)).containsExactly(HEADER, "101,サマリ,B,");
	}

	@Test
	@DisplayName("列名は sync.columns で変更でき、同期結果の列だけが残った行は無視する")
	void customColumnNames_andResultOnlyRowIgnored() throws IOException {
		Path file = csv("a.csv", HEADER + ",結果,日時,理由", ",サマリ,A,,,,", ",,,,未実行,2000/01/01 00:00:00,x");
		Path config = config("        lastSyncedAtColumn: 日時", "        syncResultColumn: 結果",
				"        syncErrorColumn: 理由");

		assertThat(run(config, file, false, false)).isEqualTo(0);

		List<String> lines = lines(file);
		assertThat(lines.get(0)).isEqualTo(HEADER + ",結果,日時,理由");
		assertThat(lines.get(1)).matches("100,サマリ,A,,作成," + TIME + ",");
		assertThat(lines.get(2)).isEqualTo(",,,,未実行,2000/01/01 00:00:00,x");
		assertThat(redmine).hasSize(1);
	}

	@Test
	@DisplayName("書き込みに失敗しても（Excelで開いているなど）Redmineへの同期結果はそのまま。エラーをログに出す")
	void writeFailure_logged() throws IOException {
		Path file = csv("a.csv", HEADER, "1,サマリ,A,");
		redmine.put(1L, issue(1, "A", 6, null));
		Mockito.doThrow(new IOException("The process cannot access the file")).when(writer).writeBack(anyString(),
				anyString(), anyMap(), any(ExcelSource.class), Mockito.anyBoolean(),
				any(TicketIdWriter.SyncResults.class));

		assertThat(run(config(), file, false, false)).isEqualTo(0);

		assertThat(logText()).contains("同期結果を入力ファイルに書き込めませんでした")
				.contains("Excel などでファイルを開いている場合は閉じてください");
		assertThat(lines(file)).containsExactly(HEADER, "1,サマリ,A,");
	}

	@Test
	@DisplayName("Redmineに接続できず中止した場合は、各行に未実行と理由を書く")
	void abortedRun_notRun() throws IOException {
		Path file = csv("a.csv", HEADER, ",サマリ,A,");
		when(client.listProjectIssues()).thenThrow(new IllegalStateException("Connection refused"));

		assertThat(run(config(), file, false, false)).isEqualTo(1);

		assertThat(lines(file).get(1)).matches(",サマリ,A,," + TIME + ",未実行,エラーのため同期を中止しました: Connection refused");
		verify(client, never()).createIssue(anyMap());
	}
}
