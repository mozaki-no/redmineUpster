package mozaki.redmineUpster.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.ColumnsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.RedmineConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.SyncConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.TrackerConfig;

/**
 * 同期設定サービスクラス。
 * <p>
 * YAMLファイルから同期設定を読み込み、プロジェクト設定の管理を行います。
 * 環境変数の展開、設定のバリデーション機能を提供します。
 * </p>
 *
 * @see SyncConfigProperties
 */
@Service
public class SyncConfigService {

	/**
	 * 環境変数パターン。
	 * ${VAR_NAME} または ${VAR_NAME:default} 形式をサポート。
	 */
	private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");

	private final SyncConfigProperties properties;

	private List<ProjectConfig> loadedProjects = new ArrayList<>();

	/**
	 * コンストラクタ。
	 *
	 * @param properties 同期設定プロパティ
	 */
	public SyncConfigService(SyncConfigProperties properties) {
		this.properties = properties;
	}

	/**
	 * 初期化処理。
	 * <p>
	 * アプリケーション起動時に設定ファイルを読み込みます。
	 * 設定ファイルが見つからない場合は警告ログを出力し、空の設定で初期化します。
	 * </p>
	 */
	@PostConstruct
	public void init() {
		String configPath = properties.getConfigPath();
		if (configPath == null || configPath.isBlank()) {
			return;
		}
		try {
			loadConfig(configPath);
		} catch (IllegalArgumentException e) {
			// 設定ファイルが見つからない場合は警告のみ（CLI実行時に指定される想定）
			System.err.println("WARN: " + e.getMessage() + " (will be loaded at runtime)");
		}
	}

	/**
	 * 指定されたパスからYAML設定ファイルを読み込みます。
	 * <p>
	 * ファイルシステムのパスを最初に確認し、存在しない場合は
	 * クラスパスリソースとして読み込みを試みます。
	 * </p>
	 *
	 * @param configPath 設定ファイルのパス（ファイルシステムまたはクラスパス）
	 * @throws IllegalArgumentException 設定ファイルが見つからない場合
	 * @throws RuntimeException 設定ファイルの読み込みに失敗した場合
	 */
	public void loadConfig(String configPath) {
		Path path = Paths.get(configPath);
		if (!Files.exists(path)) {
			// Try as classpath resource
			try (InputStream is = getClass().getClassLoader().getResourceAsStream(configPath)) {
				if (is != null) {
					loadFromInputStream(is);
					return;
				}
			} catch (IOException e) {
				// Fall through to throw exception
			}
			throw new IllegalArgumentException("Configuration file not found: " + configPath);
		}

		try (InputStream is = Files.newInputStream(path)) {
			loadFromInputStream(is);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load configuration file: " + configPath, e);
		}
	}

	/**
	 * InputStreamからYAML設定を読み込みます。
	 *
	 * @param inputStream 読み込み元のInputStream
	 */
	@SuppressWarnings("unchecked")
	private void loadFromInputStream(InputStream inputStream) {
		Yaml yaml = new Yaml();
		Map<String, Object> root = yaml.load(inputStream);
		if (root == null) {
			loadedProjects = new ArrayList<>();
			return;
		}

		List<Map<String, Object>> projectsList = (List<Map<String, Object>>) root.get("projects");
		if (projectsList == null) {
			loadedProjects = new ArrayList<>();
			return;
		}

		loadedProjects = new ArrayList<>();
		for (Map<String, Object> projectMap : projectsList) {
			ProjectConfig projectConfig = parseProjectConfig(projectMap);
			validate(projectConfig);
			loadedProjects.add(projectConfig);
		}
	}

	/**
	 * MapからProjectConfigを解析します。
	 *
	 * @param map プロジェクト設定のMap
	 * @return 解析されたProjectConfig
	 */
	@SuppressWarnings("unchecked")
	private ProjectConfig parseProjectConfig(Map<String, Object> map) {
		ProjectConfig config = new ProjectConfig();
		config.setName(expandEnvVars((String) map.get("name")));
		config.setDefaultProject(Boolean.TRUE.equals(map.get("default")));

		Map<String, Object> redmineMap = (Map<String, Object>) map.get("redmine");
		if (redmineMap != null) {
			config.setRedmine(parseRedmineConfig(redmineMap));
		}

		Map<String, Object> syncMap = (Map<String, Object>) map.get("sync");
		if (syncMap != null) {
			config.setSync(parseSyncConfig(syncMap));
		}

		return config;
	}

	/**
	 * MapからRedmineConfigを解析します。
	 *
	 * @param map Redmine設定のMap
	 * @return 解析されたRedmineConfig
	 */
	private RedmineConfig parseRedmineConfig(Map<String, Object> map) {
		RedmineConfig config = new RedmineConfig();
		config.setBaseUrl(expandEnvVars((String) map.get("baseUrl")));
		config.setApiKey(expandEnvVars((String) map.get("apiKey")));
		config.setProjectId(expandEnvVars((String) map.get("projectId")));
		return config;
	}

	/**
	 * MapからSyncConfigを解析します。
	 *
	 * @param map 同期設定のMap
	 * @return 解析されたSyncConfig
	 */
	@SuppressWarnings("unchecked")
	private SyncConfig parseSyncConfig(Map<String, Object> map) {
		SyncConfig config = new SyncConfig();

		Map<String, Object> trackerMap = (Map<String, Object>) map.get("tracker");
		if (trackerMap != null) {
			config.setTracker(parseTrackerConfig(trackerMap));
		}

		Map<String, Object> statusMap = (Map<String, Object>) map.get("status");
		if (statusMap != null) {
			config.setStatus(parseStatusConfig(statusMap));
		}

		Map<String, Object> customFieldMap = (Map<String, Object>) map.get("customFieldMap");
		if (customFieldMap != null) {
			Map<String, String> expandedMap = new HashMap<>();
			for (Map.Entry<String, Object> entry : customFieldMap.entrySet()) {
				String key = expandEnvVars(entry.getKey());
				String value = expandEnvVars(String.valueOf(entry.getValue()));
				expandedMap.put(key, value);
			}
			config.setCustomFieldMap(expandedMap);
		}

		List<String> customFieldDateColumns = (List<String>) map.get("customFieldDateColumns");
		if (customFieldDateColumns != null) {
			config.setCustomFieldDateColumns(new ArrayList<>(customFieldDateColumns));
		}

		Map<String, Object> columnsMap = (Map<String, Object>) map.get("columns");
		if (columnsMap != null) {
			config.setColumns(parseColumnsConfig(columnsMap));
		}

		return config;
	}

	/**
	 * MapからColumnsConfigを解析します。
	 *
	 * @param map 列設定のMap
	 * @return 解析されたColumnsConfig
	 */
	@SuppressWarnings("unchecked")
	private ColumnsConfig parseColumnsConfig(Map<String, Object> map) {
		ColumnsConfig config = new ColumnsConfig();

		List<String> hierarchy = (List<String>) map.get("hierarchy");
		if (hierarchy != null) {
			config.setHierarchy(new ArrayList<>(hierarchy));
		}

		List<String> required = (List<String>) map.get("required");
		if (required != null) {
			config.setRequired(new ArrayList<>(required));
		}

		List<String> customFieldColumns = (List<String>) map.get("customFieldColumns");
		if (customFieldColumns != null) {
			config.setCustomFieldColumns(new ArrayList<>(customFieldColumns));
		}

		String externalKeyColumn = (String) map.get("externalKeyColumn");
		if (externalKeyColumn != null && !externalKeyColumn.isBlank()) {
			config.setExternalKeyColumn(externalKeyColumn);
		}

		String startDateColumn = (String) map.get("startDateColumn");
		if (startDateColumn != null && !startDateColumn.isBlank()) {
			config.setStartDateColumn(startDateColumn);
		}

		String dueDateColumn = (String) map.get("dueDateColumn");
		if (dueDateColumn != null && !dueDateColumn.isBlank()) {
			config.setDueDateColumn(dueDateColumn);
		}

		String statusColumn = (String) map.get("statusColumn");
		if (statusColumn != null && !statusColumn.isBlank()) {
			config.setStatusColumn(statusColumn);
		}

		return config;
	}

	/**
	 * MapからTrackerConfigを解析します。
	 *
	 * @param map トラッカー設定のMap
	 * @return 解析されたTrackerConfig
	 */
	private TrackerConfig parseTrackerConfig(Map<String, Object> map) {
		TrackerConfig config = new TrackerConfig();
		config.setEnabled(Boolean.TRUE.equals(map.get("enabled")));
		config.setValue(expandEnvVars((String) map.get("value")));
		return config;
	}

	/**
	 * MapからStatusConfigを解析します。
	 *
	 * @param map ステータス設定のMap
	 * @return 解析されたStatusConfig
	 */
	private StatusConfig parseStatusConfig(Map<String, Object> map) {
		StatusConfig config = new StatusConfig();
		config.setEnabled(Boolean.TRUE.equals(map.get("enabled")));
		config.setMode(expandEnvVars((String) map.get("mode")));
		config.setFixed(expandEnvVars((String) map.get("fixed")));

		Map<String, Object> statusMap = (Map<String, Object>) map.get("statusMap");
		if (statusMap != null) {
			Map<String, String> expandedMap = new HashMap<>();
			for (Map.Entry<String, Object> entry : statusMap.entrySet()) {
				String key = expandEnvVars(entry.getKey());
				String value = expandEnvVars(String.valueOf(entry.getValue()));
				expandedMap.put(key, value);
			}
			config.setStatusMap(expandedMap);
		}
		return config;
	}

	/**
	 * 文字列内の環境変数を展開します。
	 * <p>
	 * ${VAR_NAME} 形式と ${VAR_NAME:default} 形式をサポートします。
	 * 存在しない環境変数はデフォルト値が指定されていない場合、空文字列に置換されます。
	 * </p>
	 *
	 * @param value 環境変数参照を含む可能性のある文字列
	 * @return 環境変数が展開された文字列。nullが渡された場合はnullを返す
	 */
	public String expandEnvVars(String value) {
		if (value == null) {
			return null;
		}

		Matcher matcher = ENV_VAR_PATTERN.matcher(value);
		StringBuilder result = new StringBuilder();

		while (matcher.find()) {
			String varName = matcher.group(1);
			String defaultValue = matcher.group(2);
			String envValue = System.getenv(varName);

			String replacement;
			if (envValue != null) {
				replacement = envValue;
			} else if (defaultValue != null) {
				replacement = defaultValue;
			} else {
				replacement = "";
			}

			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);

		return result.toString();
	}

	/**
	 * 指定された名前のプロジェクト設定を取得します。
	 *
	 * @param name プロジェクト名
	 * @return プロジェクト設定。見つからない場合は空のOptional
	 */
	public Optional<ProjectConfig> getProjectByName(String name) {
		return loadedProjects.stream()
				.filter(p -> name.equals(p.getName()))
				.findFirst();
	}

	/**
	 * デフォルトプロジェクトの設定を取得します。
	 *
	 * @return デフォルトプロジェクトの設定。設定されていない場合は空のOptional
	 */
	public Optional<ProjectConfig> getDefaultProject() {
		return loadedProjects.stream()
				.filter(ProjectConfig::isDefaultProject)
				.findFirst();
	}

	/**
	 * 読み込まれた全てのプロジェクト設定を取得します。
	 *
	 * @return プロジェクト設定のリスト（コピー）
	 */
	public List<ProjectConfig> getAllProjects() {
		return new ArrayList<>(loadedProjects);
	}

	/**
	 * プロジェクト設定のバリデーションを行います。
	 * <p>
	 * 以下の項目を検証します:
	 * <ul>
	 *   <li>プロジェクト名が設定されていること</li>
	 *   <li>Redmine設定が存在すること</li>
	 *   <li>RedmineのbaseUrlが設定されていること</li>
	 *   <li>RedmineのprojectIdが設定されていること</li>
	 * </ul>
	 * </p>
	 *
	 * @param config 検証するプロジェクト設定
	 * @throws IllegalArgumentException 必須フィールドが未設定の場合
	 */
	public void validate(ProjectConfig config) {
		List<String> errors = new ArrayList<>();

		if (config.getName() == null || config.getName().isBlank()) {
			errors.add("Project name is required");
		}

		if (config.getRedmine() == null) {
			errors.add("Redmine configuration is required");
		} else {
			if (config.getRedmine().getBaseUrl() == null || config.getRedmine().getBaseUrl().isBlank()) {
				errors.add("Redmine baseUrl is required");
			}
			if (config.getRedmine().getProjectId() == null || config.getRedmine().getProjectId().isBlank()) {
				errors.add("Redmine projectId is required");
			}
		}

		if (!errors.isEmpty()) {
			throw new IllegalArgumentException(
					"Invalid project configuration '" + config.getName() + "': " + String.join(", ", errors));
		}
	}

	/**
	 * 全体設定のバリデーションを行います。
	 * <p>
	 * プロジェクトが1つ以上設定されていることを検証します。
	 * </p>
	 *
	 * @throws IllegalArgumentException プロジェクトが設定されていない場合
	 */
	public void validateConfig() {
		if (loadedProjects.isEmpty()) {
			throw new IllegalArgumentException("At least one project must be configured");
		}
	}
}
