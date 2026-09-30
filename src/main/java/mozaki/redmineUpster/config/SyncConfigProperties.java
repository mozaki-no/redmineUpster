package mozaki.redmineUpster.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * 同期設定プロパティクラス。
 * <p>
 * YAMLファイルから読み込まれた同期設定を保持します。
 * 設定ファイルのパスと複数のプロジェクト設定を管理します。
 * </p>
 *
 * @see ProjectConfig
 * @see RedmineConfig
 * @see SyncConfig
 */
@Data
@ConfigurationProperties(prefix = "sync")
public class SyncConfigProperties {

	/**
	 * 同期設定ファイルのパス。
	 * デフォルト値は "sync-config.yml"。
	 */
	private String configPath = "sync-config.yml";

	/**
	 * プロジェクト設定のリスト。
	 */
	private List<ProjectConfig> projects = new ArrayList<>();

	/**
	 * プロジェクト設定クラス。
	 * <p>
	 * 個別のプロジェクトに関する設定を保持します。
	 * Redmine接続情報と同期設定を含みます。
	 * </p>
	 */
	@Data
	public static class ProjectConfig {
		/**
		 * プロジェクト名。
		 */
		private String name;

		/**
		 * デフォルトプロジェクトかどうかのフラグ。
		 */
		private boolean defaultProject;

		/**
		 * Redmine接続設定。
		 */
		private RedmineConfig redmine;

		/**
		 * 同期設定。
		 */
		private SyncConfig sync;
	}

	/**
	 * Redmine接続設定クラス。
	 * <p>
	 * RedmineサーバーへのAPI接続に必要な情報を保持します。
	 * </p>
	 */
	@Data
	public static class RedmineConfig {
		/**
		 * RedmineサーバーのベースURL。
		 * 例: "https://redmine.example.com"
		 */
		private String baseUrl;

		/**
		 * Redmine APIキー。
		 */
		private String apiKey;

		/**
		 * 同期対象のRedmineプロジェクトID。
		 */
		private String projectId;
	}

	/**
	 * 同期設定クラス。
	 * <p>
	 * チケット同期時のトラッカー、ステータス、カスタムフィールドの
	 * マッピング設定を保持します。
	 * </p>
	 */
	@Data
	public static class SyncConfig {
		/**
		 * トラッカー設定。
		 */
		private TrackerConfig tracker;

		/**
		 * ステータス設定。
		 */
		private StatusConfig status;

		/**
		 * カスタムフィールドのマッピング。
		 * キーはCSV/Excelの列名、値はRedmineのカスタムフィールド名。
		 */
		private Map<String, String> customFieldMap = new HashMap<>();

		/**
		 * 日付として扱うカスタムフィールド列のリスト。
		 * 指定された列は日付正規化されます。
		 */
		private List<String> customFieldDateColumns = new ArrayList<>();

		/**
		 * トラッカー名 → トラッカーIDの対応表。
		 * Excelの「トラッカー」列の値を変換します。
		 * ここにない名前は Redmine の /trackers.json から解決します。
		 * 例: {"タスク": "2", "サマリ": "6"}
		 */
		private Map<String, String> trackerMap = new HashMap<>();

		/**
		 * 論理削除（Excelから消えたチケットのステータス変更）設定。
		 */
		private DeletionConfig deletion;

		/**
		 * 列設定。
		 */
		private ColumnsConfig columns;

		/**
		 * Excel の読み込み元（シート・テーブル）。
		 */
		private ExcelConfig excel;

		/**
		 * ユーザーの読み込み元（シート・テーブル）。省略時はシート「ユーザー」（なければユーザーは同期しない）。
		 */
		private ExcelConfig users;

		/**
		 * グループの読み込み元（シート・テーブル）。省略時はシート「グループ」（なければグループは同期しない）。
		 */
		private ExcelConfig groups;

		/**
		 * 仮想親チケットの自動作成（Excelに親行がない場合）の設定。
		 */
		private VirtualParentsConfig virtualParents;
	}

	/**
	 * 仮想親チケットの設定クラス。
	 * <p>
	 * 有効にすると、親行がファイルにない行（例: 大分類の行がないタスク）の祖先を
	 * 「仮想親チケット」として自動作成・更新します。無効（既定）の場合は親行がないと検証エラーです。
	 * 仮想親はExcelに行がないため、次回以降は Redmine の同期先プロジェクトから
	 * （親チケット・件名・トラッカーが同じチケットとして）見つけ直します。
	 * </p>
	 */
	@Data
	public static class VirtualParentsConfig {
		/**
		 * 仮想親チケットを作成するかどうか（既定: false。CLI の --virtual-parents / --no-virtual-parents が優先）。
		 */
		private boolean enabled;

		/**
		 * 仮想親チケットのトラッカー（名前またはID。名前は trackerMap → Redmine のトラッカー名で解決）。
		 * 未設定の場合は「サマリ」。
		 */
		private String tracker;
	}

	/**
	 * Excel の読み込み元の設定クラス（.xlsx / .xlsm。CSV では無視）。
	 * <p>
	 * 優先順位は table ＞ sheet ＞ 先頭シート。CLI の --table / --sheet で上書きできます。
	 * </p>
	 */
	@Data
	public static class ExcelConfig {
		/**
		 * シート名、または1始まりのシート番号。値のある最初の行をヘッダとして読みます。
		 */
		private String sheet;

		/**
		 * Excel のテーブル（挿入 → テーブル）の名前。テーブルの見出し行・範囲だけを読みます。
		 */
		private String table;
	}

	/**
	 * 列設定クラス。
	 * <p>
	 * CSV/Excelの列に関する設定を保持します。
	 * 階層列、必須列、カスタムフィールド対象列を定義します。
	 * </p>
	 */
	@Data
	public static class ColumnsConfig {
		/**
		 * 階層列のリスト（親子関係推定に使用、順番が重要）。
		 * 例: ["大分類", "中分類", "小分類", "成果物", "タスク"]
		 */
		private List<String> hierarchy = new ArrayList<>();

		/**
		 * 必須列のリスト（CSVに必ず含める列）。
		 * 例: ["チケットID", "トラッカー", "チーム", "工程", ...]
		 */
		private List<String> required = new ArrayList<>();

		/**
		 * カスタムフィールドマッピング対象列のリスト。
		 * 例: ["チーム", "工程", ...]
		 */
		private List<String> customFieldColumns = new ArrayList<>();

		/**
		 * チケットID列名（Redmineのチケット番号）。
		 * 空欄なら新規作成、値があればそのチケットを更新します。
		 * 新規作成したチケットのIDはこの列へ書き戻されます。
		 * デフォルト値は "チケットID"。
		 */
		private String ticketIdColumn = "チケットID";

		/**
		 * トラッカー列名（行ごとのトラッカー名またはID）。
		 * デフォルト値は "トラッカー"。
		 */
		private String trackerColumn = "トラッカー";

		/**
		 * 開始日列名（Redmineのstart_dateに反映）。
		 * デフォルト値は "着手予定"。
		 */
		private String startDateColumn = "着手予定";

		/**
		 * 期限列名（Redmineのdue_dateに反映）。
		 * デフォルト値は "完了予定"。
		 */
		private String dueDateColumn = "完了予定";

		/**
		 * ステータス列名（CSVのステータス値を優先する場合に使用）。
		 * デフォルト値は "ステータス"。
		 */
		private String statusColumn = "ステータス";

		/**
		 * 進捗率列名（Redmineのdone_ratioに反映）。
		 * デフォルト値は "進捗率"。
		 */
		private String progressColumn = "進捗率";

		/**
		 * 階層列の空欄を前行の値で補完するか（旧来の動作）。デフォルト false。
		 * <p>
		 * false: 階層列の空欄は「その階層を飛ばした」ことを表します（例: 大分類の直下のタスク）。
		 * 同じ値が縦に続く箇所はセル結合するか、各行に値を入れてください。
		 * true: 一番深い値より左の空欄を前行の値で補完します。この場合、階層を飛ばした行は作れません。
		 * </p>
		 */
		private boolean fillDownHierarchy = false;
	}

	/**
	 * 論理削除設定クラス。
	 * <p>
	 * このツールが作成・更新したチケット（issue_link に記録されたもの）のうち、
	 * 今回のExcelに存在しないものを、指定ステータスへ変更して論理削除します。
	 * statusId を設定しない場合は、候補をログに警告として出すだけで何も変更しません。
	 * </p>
	 */
	@Data
	public static class DeletionConfig {
		/**
		 * 論理削除時に設定するステータスID（未設定の場合は変更しない）。
		 */
		private Integer statusId;
	}

	/**
	 * トラッカー設定クラス。
	 * <p>
	 * トラッカー列が空欄の行に使用する既定トラッカーの設定を保持します。
	 * </p>
	 */
	@Data
	public static class TrackerConfig {
		/**
		 * トラッカー設定が有効かどうか。
		 */
		private boolean enabled;

		/**
		 * 使用するトラッカーの値（名前またはID）。
		 */
		private String value;
	}

	/**
	 * ステータス設定クラス。
	 * <p>
	 * チケットのステータス決定方法の設定を保持します。
	 * </p>
	 */
	@Data
	public static class StatusConfig {
		/**
		 * ステータス設定が有効かどうか。
		 */
		private boolean enabled;

		/**
		 * ステータス決定モード。
		 * "BY_DATES": 日付に基づいて自動決定
		 * "FIXED": 固定値を使用
		 */
		private String mode;

		/**
		 * 固定ステータス値。
		 * modeが"FIXED"の場合に使用されます。
		 */
		private String fixed;

		/**
		 * CSVのステータス名をRedmineのステータスID/名称に変換するマップ。
		 * 例: {"進行中": "2"}
		 */
		private Map<String, String> statusMap = new HashMap<>();
	}
}
