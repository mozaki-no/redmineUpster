package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet;

class DirectorySyncTests {

	@TempDir
	Path tempDir;

	private FileLogger logger;
	private RedmineClient client;

	@BeforeEach
	void setUp() throws Exception {
		logger = new FileLogger(tempDir.toString());
		client = Mockito.mock(RedmineClient.class);
	}

	@AfterEach
	void tearDown() {
		logger.close();
	}

	private static ParsedSheet sheet(List<String> headers, List<List<String>> values) {
		List<Map<String, String>> rows = new ArrayList<>();
		List<Integer> rowNumbers = new ArrayList<>();
		for (int i = 0; i < values.size(); i++) {
			Map<String, String> row = new LinkedHashMap<>();
			for (int c = 0; c < headers.size(); c++) {
				row.put(headers.get(c), c < values.get(i).size() ? values.get(i).get(c) : "");
			}
			rows.add(row);
			rowNumbers.add(i + 2);
		}
		return new ParsedSheet(headers, rows, rowNumbers);
	}

	private static Map<Long, Map<String, Object>> existingUsers() {
		Map<Long, Map<String, Object>> users = new LinkedHashMap<>();
		users.put(5L, new LinkedHashMap<>(Map.of("id", 5, "login", "tanaka", "lastname", "田中", "firstname", "太郎",
				"mail", "tanaka@example.com", "admin", false, "status", 1)));
		users.put(6L, new LinkedHashMap<>(Map.of("id", 6, "login", "suzuki", "lastname", "鈴木", "firstname", "花子",
				"mail", "suzuki@example.com", "admin", false, "status", 1)));
		return users;
	}

	@Test
	@DisplayName("ユーザー: ID空欄は新規作成（パスワード空欄なら自動生成）、ログインIDが既存なら更新してIDを書き戻す")
	void syncUsers_upsert() {
		ParsedSheet sheet = sheet(DirectorySync.USER_COLUMNS, List.of(
				List.of("", "sato", "佐藤", "次郎", "sato@example.com", "", "", ""),
				List.of("", "TANAKA", "田中", "太郎", "tanaka@example.org", "", "", ""),
				List.of("6", "suzuki", "", "", "", "", "ロック", "")));
		Map<Long, Map<String, Object>> users = existingUsers();
		DirectorySync.Plan<DirectorySync.UserRow> plan = DirectorySync.parseUsers(sheet, users);
		assertThat(plan.errors()).isEmpty();

		when(client.createUser(anyMap())).thenReturn(10L);
		DirectorySync.Result result = new DirectorySync().syncUsers(plan.rows(), users, client, false, logger);

		assertThat(result.errors()).isEmpty();
		assertThat(result.created()).isEqualTo(1);
		assertThat(result.updated()).isEqualTo(2);
		assertThat(result.writeBackIds()).containsExactly(Map.entry(2, 10L), Map.entry(3, 5L));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> created = ArgumentCaptor.forClass(Map.class);
		verify(client).createUser(created.capture());
		assertThat(created.getValue()).containsEntry("login", "sato").containsEntry("generate_password", true)
				.containsEntry("send_information", true).doesNotContainKey("password");
		// ログインIDの大文字小文字の違いは変更しない。空欄の項目は送らない
		verify(client).updateUser(5L, Map.of("mail", "tanaka@example.org"));
		verify(client).updateUser(6L, Map.of("status", 3));
		assertThat(users).containsKey(10L);
	}

	@Test
	@DisplayName("ユーザー: 変更がなければ更新しない。dry-run では書き込まない")
	void syncUsers_noChangesAndDryRun() {
		ParsedSheet sheet = sheet(DirectorySync.USER_COLUMNS, List.of(
				List.of("5", "tanaka", "田中", "太郎", "tanaka@example.com", "いいえ", "有効", ""),
				List.of("", "sato", "佐藤", "次郎", "sato@example.com", "", "", "")));
		Map<Long, Map<String, Object>> users = existingUsers();
		DirectorySync.Plan<DirectorySync.UserRow> plan = DirectorySync.parseUsers(sheet, users);
		DirectorySync.Result result = new DirectorySync().syncUsers(plan.rows(), users, client, true, logger);

		assertThat(result.unchanged()).isEqualTo(1);
		assertThat(result.created()).isEqualTo(1);
		assertThat(result.writeBackIds()).isEmpty();
		verify(client, never()).createUser(any());
		verify(client, never()).updateUser(any(), any());
	}

	@Test
	@DisplayName("ユーザー: 検証エラー（新規作成の必須項目、ログインIDの重複、存在しないID、状態の値）")
	void parseUsers_errors() {
		ParsedSheet sheet = sheet(DirectorySync.USER_COLUMNS, List.of(
				List.of("", "new1", "", "名", "", "", "", ""),
				List.of("", "suzuki", "", "", "", "", "", ""),
				List.of("6", "suzuki", "", "", "", "", "", ""),
				List.of("99", "ghost", "", "", "", "", "", ""),
				List.of("5", "tanaka", "", "", "", "", "休止", "")));
		DirectorySync.Plan<DirectorySync.UserRow> plan = DirectorySync.parseUsers(sheet, existingUsers());
		assertThat(plan.errors()).hasSize(4);
		assertThat(plan.errors().get(0)).contains("行2").contains("姓・メールアドレス");
		assertThat(plan.errors().get(1)).contains("行4").contains("重複");
		assertThat(plan.errors().get(2)).contains("#99");
		assertThat(plan.errors().get(3)).contains("状態");
	}

	@Test
	@DisplayName("グループ: 名前で既存を見つけてメンバーを置き換え、新規グループは作成（メンバーは今回作成したユーザーも可）")
	void syncGroups_upsert() {
		Map<Long, Map<String, Object>> users = existingUsers();
		users.put(10L, new LinkedHashMap<>(Map.of("id", 10, "login", "sato")));
		Map<Long, Map<String, Object>> groups = new LinkedHashMap<>();
		groups.put(20L, new LinkedHashMap<>(Map.of("id", 20, "name", "開発",
				"users", List.of(Map.of("id", 5, "name", "田中 太郎")))));
		groups.put(21L, new LinkedHashMap<>(Map.of("id", 21, "name", "営業",
				"users", List.of(Map.of("id", 6, "name", "鈴木 花子")))));
		ParsedSheet sheet = sheet(DirectorySync.GROUP_COLUMNS, List.of(
				List.of("", "開発", "tanaka, sato"),
				List.of("21", "営業", "Suzuki"),
				List.of("", "品質保証", "suzuki\ntanaka"),
				List.of("", "総務", "")));
		DirectorySync.Plan<DirectorySync.GroupRow> plan = DirectorySync.parseGroups(sheet, groups,
				Set.of("tanaka", "suzuki", "sato"));
		assertThat(plan.errors()).isEmpty();

		when(client.createGroup(anyMap())).thenReturn(30L, 31L);
		DirectorySync.Result result = new DirectorySync().syncGroups(plan.rows(), groups, users, client, false, logger);

		assertThat(result.errors()).isEmpty();
		assertThat(result.updated()).isEqualTo(1);
		assertThat(result.unchanged()).isEqualTo(1);
		assertThat(result.created()).isEqualTo(2);
		verify(client).updateGroup(20L, Map.of("user_ids", List.of(5L, 10L)));
		verify(client).createGroup(Map.of("name", "品質保証", "user_ids", List.of(6L, 5L)));
		verify(client).createGroup(Map.of("name", "総務"));
		verify(client, never()).updateGroup(eq(21L), any());
		assertThat(result.writeBackIds()).containsExactly(Map.entry(2, 20L), Map.entry(4, 30L), Map.entry(5, 31L));
	}

	@Test
	@DisplayName("グループ: メンバーに存在しないログインIDがあれば検証エラー")
	void parseGroups_unknownMember() {
		ParsedSheet sheet = sheet(DirectorySync.GROUP_COLUMNS, List.of(List.of("", "開発", "tanaka, nobody")));
		DirectorySync.Plan<DirectorySync.GroupRow> plan = DirectorySync.parseGroups(sheet, Map.of(), Set.of("tanaka"));
		assertThat(plan.errors()).singleElement().asString().contains("nobody");
	}

	@Test
	@DisplayName("管理者・状態の値の解釈（空欄は変更しない）")
	void parseAdminAndStatus() {
		assertThat(DirectorySync.parseAdmin("はい")).isTrue();
		assertThat(DirectorySync.parseAdmin("FALSE")).isFalse();
		assertThat(DirectorySync.parseAdmin(" ")).isNull();
		assertThat(DirectorySync.parseStatus("ロック")).isEqualTo(3);
		assertThat(DirectorySync.parseStatus("active")).isEqualTo(1);
		assertThat(DirectorySync.parseStatus("")).isNull();
	}

	@Test
	@DisplayName("--targets の解析")
	void parseTargets() {
		assertThat(SyncTarget.parse(null)).containsExactlyInAnyOrder(SyncTarget.values());
		assertThat(SyncTarget.parse("users, グループ")).containsExactlyInAnyOrder(SyncTarget.USERS, SyncTarget.GROUPS);
	}
}
