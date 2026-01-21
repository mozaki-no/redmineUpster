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
		 * 仮想親チケットに使用するトラッカーID。
		 */
		private Integer virtualParentTrackerId = 6;

		/**
		 * 列設定。
		 */
		private ColumnsConfig columns;
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
		 * 例: ["id", "チーム", "工程", ...]
		 */
		private List<String> required = new ArrayList<>();

		/**
		 * カスタムフィールドマッピング対象列のリスト。
		 * 例: ["id", "チーム", "工程", ...]
		 */
		private List<String> customFieldColumns = new ArrayList<>();

		/**
		 * 外部キー列名（Redmineチケットと紐付けるためのID列）。
		 * デフォルト値は "id"。
		 * 例: "WBS番号" などに変更可能。
		 */
		private String externalKeyColumn = "id";

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
	}

	/**
	 * トラッカー設定クラス。
	 * <p>
	 * チケット作成時に使用するトラッカーの設定を保持します。
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
