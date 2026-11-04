package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IssueComparatorTests {

	private static Map<String, Object> redmineIssue() {
		Map<String, Object> issue = new HashMap<>();
		issue.put("id", 10L);
		issue.put("subject", "設計");
		issue.put("project", Map.of("id", 7, "name", "P"));
		issue.put("tracker", Map.of("id", 2, "name", "タスク"));
		issue.put("status", Map.of("id", 1, "name", "新規"));
		issue.put("assigned_to", Map.of("id", 5, "name", "むさし"));
		issue.put("parent", Map.of("id", 3));
		issue.put("start_date", "2026-01-03");
		issue.put("due_date", "2026-01-31");
		issue.put("done_ratio", 40);
		issue.put("custom_fields", List.of(
				Map.of("id", 11, "name", "WBS", "value", "1.1"),
				Map.of("id", 12, "name", "タグ", "value", List.of("b", "a"))));
		return issue;
	}

	private static Map<String, Object> payload() {
		Map<String, Object> payload = new HashMap<>();
		payload.put("project_id", "proj");
		payload.put("subject", "設計");
		payload.put("tracker_id", 2L);
		payload.put("status_id", 1L);
		payload.put("assigned_to_id", 5L);
		payload.put("parent_issue_id", 3L);
		payload.put("start_date", "2026-01-03");
		payload.put("due_date", "2026-01-31");
		payload.put("done_ratio", 40);
		payload.put("custom_fields", List.of(
				Map.of("id", 11L, "value", "1.1"),
				Map.of("name", "タグ", "value", List.of("a", "b")),
				Map.of("id", 99L, "value", "トラッカーで無効なフィールド")));
		return payload;
	}

	@Test
	@DisplayName("送信する項目がすべてRedmineと同じなら変更なし")
	void changedFields_sameValues() {
		assertThat(IssueComparator.changedFields(payload(), redmineIssue())).isEmpty();
	}

	@Test
	@DisplayName("違う項目の名前を返す")
	void changedFields_reportsDifferences() {
		Map<String, Object> payload = payload();
		payload.put("subject", "設計（改）");
		payload.put("tracker_id", 6L);
		payload.put("status_id", 5L);
		payload.put("due_date", "2026-02-28");
		payload.put("done_ratio", 100);
		payload.put("custom_fields", List.of(Map.of("id", 11L, "value", "1.2")));

		assertThat(IssueComparator.changedFields(payload, redmineIssue()))
				.containsExactlyInAnyOrder("subject", "tracker_id", "status_id", "due_date", "done_ratio",
						"custom_fields");
	}

	@Test
	@DisplayName("親の空文字は親なしと同じ、親の付け替え・解除は変更あり")
	void changedFields_parent() {
		Map<String, Object> issue = redmineIssue();
		Map<String, Object> payload = Map.of("parent_issue_id", "");
		assertThat(IssueComparator.changedFields(payload, issue)).containsExactly("parent_issue_id");
		issue.remove("parent");
		assertThat(IssueComparator.changedFields(payload, issue)).isEmpty();
		assertThat(IssueComparator.changedFields(Map.of("parent_issue_id", 8L), issue))
				.containsExactly("parent_issue_id");
	}

	@Test
	@DisplayName("説明はRedmineのCRLF・末尾の空白の違いを無視して比較する")
	void changedFields_description() {
		Map<String, Object> issue = redmineIssue();
		issue.put("description", "# 見出し\r\n\r\n- a\r\n");
		assertThat(IssueComparator.changedFields(Map.of("description", "# 見出し\n\n- a"), issue)).isEmpty();
		assertThat(IssueComparator.changedFields(Map.of("description", "# 見出し\n\n- b"), issue))
				.containsExactly("description");
	}

	@Test
	@DisplayName("送信しない項目（空欄の日付など）は比較しない。Redmineで空の項目に値を送る場合は変更あり")
	void changedFields_onlySentFields() {
		Map<String, Object> issue = redmineIssue();
		issue.remove("start_date");
		assertThat(IssueComparator.changedFields(Map.of("subject", "設計"), issue)).isEmpty();
		assertThat(IssueComparator.changedFields(Map.of("start_date", "2026-01-03"), issue))
				.containsExactly("start_date");
		assertThat(IssueComparator.changedFields(Map.of("unknown", "x"), issue)).containsExactly("unknown");
	}

	@Test
	@DisplayName("ステータス名で送る場合は名前で比較する")
	void changedFields_statusName() {
		assertThat(IssueComparator.changedFields(Map.of("status", "新規"), redmineIssue())).isEmpty();
		assertThat(IssueComparator.changedFields(Map.of("status", "完了"), redmineIssue())).containsExactly("status");
	}
}
