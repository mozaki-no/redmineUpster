package mozaki.redmineUpster.util;

import java.util.List;

public final class ColumnDefinitions {
	public static final String COL_ID = "id";
	public static final String COL_TEAM = "チーム";
	public static final String COL_PROCESS = "工程";
	public static final String COL_MAJOR = "大分類";
	public static final String COL_MIDDLE = "中分類";
	public static final String COL_MINOR = "小分類";
	public static final String COL_OUTPUT = "成果物";
	public static final String COL_TASK = "タスク";
	public static final String COL_ORG = "社/組織";
	public static final String COL_ASSIGNEE = "担当";
	public static final String COL_START_PLAN = "着手予定";
	public static final String COL_START_ACTUAL = "着手実績";
	public static final String COL_DUE_PLAN = "完了予定";
	public static final String COL_DUE_ACTUAL = "完了実績";
	public static final String COL_STATUS = "ステータス";

	public static final List<String> REQUIRED_HEADERS = List.of(
			COL_ID,
			COL_TEAM,
			COL_PROCESS,
			COL_MAJOR,
			COL_MIDDLE,
			COL_MINOR,
			COL_OUTPUT,
			COL_TASK,
			COL_ORG,
			COL_ASSIGNEE,
			COL_START_PLAN,
			COL_START_ACTUAL,
			COL_DUE_PLAN,
			COL_DUE_ACTUAL);

	public static final List<String> HIERARCHY_COLUMNS = List.of(
			COL_MAJOR,
			COL_MIDDLE,
			COL_MINOR,
			COL_OUTPUT,
			COL_TASK);

	public static final List<String> CUSTOM_FIELD_COLUMNS = List.of(
			COL_ID,
			COL_TEAM,
			COL_PROCESS,
			COL_ORG,
			COL_START_ACTUAL,
			COL_DUE_ACTUAL,
			COL_OUTPUT);

	private ColumnDefinitions() {
	}
}
