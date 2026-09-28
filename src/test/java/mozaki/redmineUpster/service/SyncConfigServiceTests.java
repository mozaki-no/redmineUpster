package mozaki.redmineUpster.service;

import static org.assertj.core.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.ColumnsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.RedmineConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.StatusConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.SyncConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.TrackerConfig;

/**
 * SyncConfigServiceのテストクラス。
 */
@ActiveProfiles("test")
class SyncConfigServiceTests {

	private SyncConfigService service;

	@BeforeEach
	void setUp() {
		SyncConfigProperties properties = new SyncConfigProperties();
		properties.setConfigPath("test-sync-config.yml");
		service = new SyncConfigService(properties);
	}

	@Nested
	@DisplayName("loadConfig テスト")
	class LoadConfigTests {

		@Test
		@DisplayName("クラスパスから正常に設定ファイルを読み込む")
		void loadConfig_fromClasspath_success() {
			service.loadConfig("test-sync-config.yml");

			List<ProjectConfig> projects = service.getAllProjects();
			assertThat(projects).isNotEmpty();
			assertThat(projects).hasSize(1);
			assertThat(projects.get(0).getName()).isEqualTo("テスト用プロジェクト");
		}

		@Test
		@DisplayName("存在しないファイルを読み込もうとするとIllegalArgumentExceptionがスローされる")
		void loadConfig_fileNotFound_throwsException() {
			assertThatThrownBy(() -> service.loadConfig("non-existing-file.yml"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Configuration file not found");
		}

		@Test
		@DisplayName("空の設定ファイルを読み込むとプロジェクトリストが空になる")
		void loadConfig_emptyConfig_returnsEmptyProjects() {
			service.loadConfig("test-empty-config.yml");

			assertThat(service.getAllProjects()).isEmpty();
		}

		@Test
		@DisplayName("複数プロジェクトの設定ファイルを正常に読み込む")
		void loadConfig_multipleProjects_success() {
			service.loadConfig("test-multiple-projects.yml");

			List<ProjectConfig> projects = service.getAllProjects();
			assertThat(projects).hasSize(2);
			assertThat(projects.get(0).getName()).isEqualTo("project-1");
			assertThat(projects.get(1).getName()).isEqualTo("project-2");
		}

		@Test
		@DisplayName("環境変数展開用設定ファイルを読み込みデフォルト値が適用される")
		void loadConfig_withEnvVars_usesDefaults() {
			service.loadConfig("test-env-config.yml");

			List<ProjectConfig> projects = service.getAllProjects();
			assertThat(projects).hasSize(1);

			ProjectConfig project = projects.get(0);
			// デフォルト値が適用されていることを確認
			assertThat(project.getName()).isEqualTo("default-project");
			assertThat(project.getRedmine().getBaseUrl()).isEqualTo("http://default.example.com");
			assertThat(project.getRedmine().getProjectId()).isEqualTo("default-id");
			// 環境変数なし、デフォルトなしの場合は空文字
			assertThat(project.getRedmine().getApiKey()).isEmpty();
		}

		@Test
		@DisplayName("sync設定（tracker, status, customFieldMap）が正しく読み込まれる")
		void loadConfig_withSyncSettings_success() {
			service.loadConfig("test-multiple-projects.yml");

			List<ProjectConfig> projects = service.getAllProjects();
			ProjectConfig project1 = projects.get(0);
			ProjectConfig project2 = projects.get(1);

			// project-1のsync設定
			assertThat(project1.getSync()).isNotNull();
			assertThat(project1.getSync().getTracker()).isNotNull();
			assertThat(project1.getSync().getTracker().isEnabled()).isTrue();
			assertThat(project1.getSync().getTracker().getValue()).isEqualTo("Task");
			assertThat(project1.getSync().getStatus()).isNotNull();
			assertThat(project1.getSync().getStatus().isEnabled()).isFalse();

			// project-2のsync設定
			assertThat(project2.getSync()).isNotNull();
			assertThat(project2.getSync().getTracker()).isNotNull();
			assertThat(project2.getSync().getTracker().isEnabled()).isFalse();
			assertThat(project2.getSync().getStatus()).isNotNull();
			assertThat(project2.getSync().getStatus().isEnabled()).isTrue();
			assertThat(project2.getSync().getStatus().getMode()).isEqualTo("BY_DATES");
			assertThat(project2.getSync().getStatus().getStatusMap()).containsEntry("進行中", "2");

			// customFieldMap
			assertThat(project2.getSync().getCustomFieldMap()).containsEntry("担当者", "assignee");
			assertThat(project2.getSync().getCustomFieldMap()).containsEntry("優先度", "priority");
		}
	}

	@Nested
	@DisplayName("expandEnvVars テスト")
	class ExpandEnvVarsTests {

		@Test
		@DisplayName("既存の環境変数が正しく展開される")
		void expandEnvVars_withExistingEnvVar() {
			// PATHは常に設定されている環境変数
			String result = service.expandEnvVars("${PATH}");
			assertThat(result).isNotNull();
			assertThat(result).doesNotContain("${");
		}

		@Test
		@DisplayName("存在しない環境変数は空文字に置換される")
		void expandEnvVars_withNonExistingEnvVar() {
			String result = service.expandEnvVars("${NON_EXISTING_VAR_12345}");
			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("デフォルト値付き環境変数でデフォルト値が使用される")
		void expandEnvVars_withDefaultValue() {
			String result = service.expandEnvVars("${NON_EXISTING_VAR_12345:default_value}");
			assertThat(result).isEqualTo("default_value");
		}

		@Test
		@DisplayName("プレフィックスとサフィックス付きの環境変数展開")
		void expandEnvVars_withMixedContent() {
			String result = service.expandEnvVars("prefix-${NON_EXISTING_VAR_12345:default}-suffix");
			assertThat(result).isEqualTo("prefix-default-suffix");
		}

		@Test
		@DisplayName("nullが渡された場合はnullを返す")
		void expandEnvVars_withNull() {
			assertThat(service.expandEnvVars(null)).isNull();
		}

		@Test
		@DisplayName("環境変数を含まない文字列はそのまま返される")
		void expandEnvVars_withNoEnvVars() {
			String result = service.expandEnvVars("plain text");
			assertThat(result).isEqualTo("plain text");
		}

		@Test
		@DisplayName("複数の環境変数が正しく展開される")
		void expandEnvVars_multipleVars() {
			String result = service.expandEnvVars("${VAR1:val1}-${VAR2:val2}");
			assertThat(result).isEqualTo("val1-val2");
		}

		@Test
		@DisplayName("空のデフォルト値も正しく処理される")
		void expandEnvVars_emptyDefault() {
			String result = service.expandEnvVars("${NON_EXISTING_VAR:}");
			assertThat(result).isEmpty();
		}
	}

	@Nested
	@DisplayName("getProjectByName テスト")
	class GetProjectByNameTests {

		@Test
		@DisplayName("存在するプロジェクト名で正しくプロジェクトを取得する")
		void getProjectByName_existingProject() {
			service.loadConfig("test-sync-config.yml");

			Optional<ProjectConfig> project = service.getProjectByName("テスト用プロジェクト");

			assertThat(project).isPresent();
			assertThat(project.get().getName()).isEqualTo("テスト用プロジェクト");
			assertThat(project.get().getRedmine().getBaseUrl()).isEqualTo("http://localhost:3000");
		}

		@Test
		@DisplayName("存在しないプロジェクト名で空のOptionalを返す")
		void getProjectByName_nonExistingProject() {
			service.loadConfig("test-sync-config.yml");

			Optional<ProjectConfig> project = service.getProjectByName("存在しないプロジェクト");

			assertThat(project).isEmpty();
		}

		@Test
		@DisplayName("複数プロジェクトから正しいプロジェクトを取得する")
		void getProjectByName_fromMultipleProjects() {
			service.loadConfig("test-multiple-projects.yml");

			Optional<ProjectConfig> project1 = service.getProjectByName("project-1");
			Optional<ProjectConfig> project2 = service.getProjectByName("project-2");

			assertThat(project1).isPresent();
			assertThat(project1.get().getRedmine().getBaseUrl()).isEqualTo("http://redmine1.example.com");

			assertThat(project2).isPresent();
			assertThat(project2.get().getRedmine().getBaseUrl()).isEqualTo("http://redmine2.example.com");
		}
	}

	@Nested
	@DisplayName("getDefaultProject テスト")
	class GetDefaultProjectTests {

		@Test
		@DisplayName("デフォルトプロジェクトが設定されている場合に正しく取得する")
		void getDefaultProject_exists() {
			service.loadConfig("test-sync-config.yml");

			Optional<ProjectConfig> defaultProject = service.getDefaultProject();

			assertThat(defaultProject).isPresent();
			assertThat(defaultProject.get().getName()).isEqualTo("テスト用プロジェクト");
			assertThat(defaultProject.get().isDefaultProject()).isTrue();
		}

		@Test
		@DisplayName("デフォルトプロジェクトが設定されていない場合に空のOptionalを返す")
		void getDefaultProject_notExists() {
			service.loadConfig("test-no-default-config.yml");

			Optional<ProjectConfig> defaultProject = service.getDefaultProject();

			assertThat(defaultProject).isEmpty();
		}

		@Test
		@DisplayName("複数プロジェクトから正しいデフォルトプロジェクトを取得する")
		void getDefaultProject_fromMultipleProjects() {
			service.loadConfig("test-multiple-projects.yml");

			Optional<ProjectConfig> defaultProject = service.getDefaultProject();

			assertThat(defaultProject).isPresent();
			assertThat(defaultProject.get().getName()).isEqualTo("project-1");
		}
	}

	@Nested
	@DisplayName("validate テスト")
	class ValidateTests {

		@Test
		@DisplayName("正常な設定はバリデーションを通過する")
		void validate_validConfig_noException() {
			ProjectConfig config = new ProjectConfig();
			config.setName("test-project");
			RedmineConfig redmine = new RedmineConfig();
			redmine.setBaseUrl("http://localhost:3000");
			redmine.setProjectId("test");
			config.setRedmine(redmine);

			assertThatCode(() -> service.validate(config)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("name未設定でIllegalArgumentExceptionがスローされる")
		void validate_missingName_throwsException() {
			ProjectConfig config = new ProjectConfig();
			config.setName("");
			RedmineConfig redmine = new RedmineConfig();
			redmine.setBaseUrl("http://localhost:3000");
			redmine.setProjectId("test");
			config.setRedmine(redmine);

			assertThatThrownBy(() -> service.validate(config))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Project name is required");
		}

		@Test
		@DisplayName("nameがnullでIllegalArgumentExceptionがスローされる")
		void validate_nullName_throwsException() {
			ProjectConfig config = new ProjectConfig();
			config.setName(null);
			RedmineConfig redmine = new RedmineConfig();
			redmine.setBaseUrl("http://localhost:3000");
			redmine.setProjectId("test");
			config.setRedmine(redmine);

			assertThatThrownBy(() -> service.validate(config))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Project name is required");
		}

		@Test
		@DisplayName("redmine設定がnullでIllegalArgumentExceptionがスローされる")
		void validate_missingRedmine_throwsException() {
			ProjectConfig config = new ProjectConfig();
			config.setName("test-project");
			config.setRedmine(null);

			assertThatThrownBy(() -> service.validate(config))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Redmine configuration is required");
		}

		@Test
		@DisplayName("redmine.baseUrl未設定でIllegalArgumentExceptionがスローされる")
		void validate_missingBaseUrl_throwsException() {
			ProjectConfig config = new ProjectConfig();
			config.setName("test-project");
			RedmineConfig redmine = new RedmineConfig();
			redmine.setBaseUrl("");
			redmine.setProjectId("test");
			config.setRedmine(redmine);

			assertThatThrownBy(() -> service.validate(config))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Redmine baseUrl is required");
		}

		@Test
		@DisplayName("redmine.baseUrlがnullでIllegalArgumentExceptionがスローされる")
		void validate_nullBaseUrl_throwsException() {
			ProjectConfig config = new ProjectConfig();
			config.setName("test-project");
			RedmineConfig redmine = new RedmineConfig();
			redmine.setBaseUrl(null);
			redmine.setProjectId("test");
			config.setRedmine(redmine);

			assertThatThrownBy(() -> service.validate(config))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Redmine baseUrl is required");
		}

		@Test
		@DisplayName("redmine.projectId未設定でIllegalArgumentExceptionがスローされる")
		void validate_missingProjectId_throwsException() {
			ProjectConfig config = new ProjectConfig();
			config.setName("test-project");
			RedmineConfig redmine = new RedmineConfig();
			redmine.setBaseUrl("http://localhost:3000");
			redmine.setProjectId("");
			config.setRedmine(redmine);

			assertThatThrownBy(() -> service.validate(config))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Redmine projectId is required");
		}

		@Test
		@DisplayName("redmine.projectIdがnullでIllegalArgumentExceptionがスローされる")
		void validate_nullProjectId_throwsException() {
			ProjectConfig config = new ProjectConfig();
			config.setName("test-project");
			RedmineConfig redmine = new RedmineConfig();
			redmine.setBaseUrl("http://localhost:3000");
			redmine.setProjectId(null);
			config.setRedmine(redmine);

			assertThatThrownBy(() -> service.validate(config))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Redmine projectId is required");
		}

		@Test
		@DisplayName("複数のバリデーションエラーがある場合、全てのエラーメッセージを含む")
		void validate_multipleErrors_containsAllMessages() {
			ProjectConfig config = new ProjectConfig();
			config.setName("");
			RedmineConfig redmine = new RedmineConfig();
			redmine.setBaseUrl("");
			redmine.setProjectId("");
			config.setRedmine(redmine);

			assertThatThrownBy(() -> service.validate(config))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Project name is required")
					.hasMessageContaining("Redmine baseUrl is required")
					.hasMessageContaining("Redmine projectId is required");
		}

		@Test
		@DisplayName("バリデーションエラーのある設定ファイルを読み込むと例外がスローされる")
		void loadConfig_invalidConfig_throwsException() {
			assertThatThrownBy(() -> service.loadConfig("test-invalid-config.yml"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Project name is required");
		}

		@Test
		@DisplayName("Redmine設定がない設定ファイルを読み込むと例外がスローされる")
		void loadConfig_noRedmine_throwsException() {
			assertThatThrownBy(() -> service.loadConfig("test-invalid-no-redmine.yml"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Redmine configuration is required");
		}

		@Test
		@DisplayName("baseUrl未設定の設定ファイルを読み込むと例外がスローされる")
		void loadConfig_noBaseUrl_throwsException() {
			assertThatThrownBy(() -> service.loadConfig("test-invalid-no-baseurl.yml"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Redmine baseUrl is required");
		}

		@Test
		@DisplayName("projectId未設定の設定ファイルを読み込むと例外がスローされる")
		void loadConfig_noProjectId_throwsException() {
			assertThatThrownBy(() -> service.loadConfig("test-invalid-no-projectid.yml"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Redmine projectId is required");
		}

		@Test
		@DisplayName("trackerMapの値が数値でない設定ファイルを読み込むと例外がスローされる")
		void loadConfig_nonNumericTrackerMap_throwsException() {
			assertThatThrownBy(() -> service.loadConfig("test-invalid-tracker-map.yml"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("trackerMap value must be numeric");
		}
	}

	@Nested
	@DisplayName("validateConfig テスト")
	class ValidateConfigTests {

		@Test
		@DisplayName("プロジェクトが読み込まれている場合はバリデーションを通過する")
		void validateConfig_withProjects_noException() {
			service.loadConfig("test-sync-config.yml");

			assertThatCode(() -> service.validateConfig()).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("プロジェクトが空の場合はIllegalArgumentExceptionがスローされる")
		void validateConfig_emptyProjects_throwsException() {
			service.loadConfig("test-empty-config.yml");

			assertThatThrownBy(() -> service.validateConfig())
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("At least one project must be configured");
		}
	}

	@Nested
	@DisplayName("getAllProjects テスト")
	class GetAllProjectsTests {

		@Test
		@DisplayName("読み込んだプロジェクトのコピーを返す")
		void getAllProjects_returnsCopy() {
			service.loadConfig("test-sync-config.yml");

			List<ProjectConfig> projects1 = service.getAllProjects();
			List<ProjectConfig> projects2 = service.getAllProjects();

			assertThat(projects1).isNotSameAs(projects2);
			assertThat(projects1).hasSize(projects2.size());
		}

		@Test
		@DisplayName("空のプロジェクトリストを返す")
		void getAllProjects_emptyList() {
			service.loadConfig("test-empty-config.yml");

			assertThat(service.getAllProjects()).isEmpty();
		}
	}

	@Nested
	@DisplayName("SyncConfigProperties テスト")
	class SyncConfigPropertiesTests {

		@Test
		@DisplayName("デフォルト値が正しく設定されている")
		void defaultValues() {
			SyncConfigProperties props = new SyncConfigProperties();

			assertThat(props.getConfigPath()).isEqualTo("sync-config.yml");
			assertThat(props.getProjects()).isNotNull();
			assertThat(props.getProjects()).isEmpty();
		}

		@Test
		@DisplayName("configPathのgetter/setterが正しく動作する")
		void configPath_getterSetter() {
			SyncConfigProperties props = new SyncConfigProperties();
			props.setConfigPath("custom-config.yml");

			assertThat(props.getConfigPath()).isEqualTo("custom-config.yml");
		}

		@Test
		@DisplayName("ProjectConfigのgetter/setterが正しく動作する")
		void projectConfig_getterSetter() {
			ProjectConfig config = new ProjectConfig();
			config.setName("test");
			config.setDefaultProject(true);

			RedmineConfig redmine = new RedmineConfig();
			config.setRedmine(redmine);

			SyncConfig sync = new SyncConfig();
			config.setSync(sync);

			assertThat(config.getName()).isEqualTo("test");
			assertThat(config.isDefaultProject()).isTrue();
			assertThat(config.getRedmine()).isEqualTo(redmine);
			assertThat(config.getSync()).isEqualTo(sync);
		}

		@Test
		@DisplayName("RedmineConfigのgetter/setterが正しく動作する")
		void redmineConfig_getterSetter() {
			RedmineConfig config = new RedmineConfig();
			config.setBaseUrl("http://test.com");
			config.setApiKey("api-key");
			config.setProjectId("proj-id");

			assertThat(config.getBaseUrl()).isEqualTo("http://test.com");
			assertThat(config.getApiKey()).isEqualTo("api-key");
			assertThat(config.getProjectId()).isEqualTo("proj-id");
		}

		@Test
		@DisplayName("SyncConfigのgetter/setterが正しく動作する")
		void syncConfig_getterSetter() {
			SyncConfig config = new SyncConfig();

			TrackerConfig tracker = new TrackerConfig();
			config.setTracker(tracker);

			StatusConfig status = new StatusConfig();
			config.setStatus(status);

			Map<String, String> customFieldMap = new HashMap<>();
			customFieldMap.put("key", "value");
			config.setCustomFieldMap(customFieldMap);
			config.setCustomFieldDateColumns(List.of("開始日", "期限"));
			config.setTrackerMap(Map.of("タスク", "2"));
			SyncConfigProperties.DeletionConfig deletion = new SyncConfigProperties.DeletionConfig();
			deletion.setStatusId(6);
			config.setDeletion(deletion);

			assertThat(config.getTracker()).isEqualTo(tracker);
			assertThat(config.getStatus()).isEqualTo(status);
			assertThat(config.getCustomFieldMap()).containsEntry("key", "value");
			assertThat(config.getCustomFieldDateColumns()).containsExactly("開始日", "期限");
			assertThat(config.getTrackerMap()).containsEntry("タスク", "2");
			assertThat(config.getDeletion().getStatusId()).isEqualTo(6);
		}

		@Test
		@DisplayName("SyncConfigのcustomFieldMapはデフォルトで空のHashMap")
		void syncConfig_defaultCustomFieldMap() {
			SyncConfig config = new SyncConfig();

			assertThat(config.getCustomFieldMap()).isNotNull();
			assertThat(config.getCustomFieldMap()).isEmpty();
			assertThat(config.getCustomFieldDateColumns()).isNotNull();
			assertThat(config.getCustomFieldDateColumns()).isEmpty();
			assertThat(config.getTrackerMap()).isNotNull().isEmpty();
			assertThat(config.getDeletion()).isNull();
		}

		@Test
		@DisplayName("TrackerConfigのgetter/setterが正しく動作する")
		void trackerConfig_getterSetter() {
			TrackerConfig config = new TrackerConfig();
			config.setEnabled(true);
			config.setValue("Bug");

			assertThat(config.isEnabled()).isTrue();
			assertThat(config.getValue()).isEqualTo("Bug");
		}

		@Test
		@DisplayName("StatusConfigのgetter/setterが正しく動作する")
		void statusConfig_getterSetter() {
			StatusConfig config = new StatusConfig();
			config.setEnabled(true);
			config.setMode("FIXED");
			config.setFixed("Closed");
			config.setStatusMap(Map.of("進行中", "2"));

			assertThat(config.isEnabled()).isTrue();
			assertThat(config.getMode()).isEqualTo("FIXED");
			assertThat(config.getFixed()).isEqualTo("Closed");
			assertThat(config.getStatusMap()).containsEntry("進行中", "2");
		}

		@Test
		@DisplayName("ColumnsConfigのgetter/setterが正しく動作する")
		void columnsConfig_getterSetter() {
			ColumnsConfig config = new ColumnsConfig();
			config.setHierarchy(List.of("A", "B", "C"));
			config.setRequired(List.of("id", "name"));
			config.setCustomFieldColumns(List.of("field1", "field2"));
			config.setStartDateColumn("開始日");
			config.setDueDateColumn("期限");
			config.setStatusColumn("状態");
			config.setProgressColumn("進捗率");

			assertThat(config.getHierarchy()).containsExactly("A", "B", "C");
			assertThat(config.getRequired()).containsExactly("id", "name");
			assertThat(config.getCustomFieldColumns()).containsExactly("field1", "field2");
			assertThat(config.getStartDateColumn()).isEqualTo("開始日");
			assertThat(config.getDueDateColumn()).isEqualTo("期限");
			assertThat(config.getStatusColumn()).isEqualTo("状態");
			assertThat(config.getProgressColumn()).isEqualTo("進捗率");
		}

		@Test
		@DisplayName("ColumnsConfigはデフォルトで空のリストを持つ")
		void columnsConfig_defaultEmptyLists() {
			ColumnsConfig config = new ColumnsConfig();

			assertThat(config.getHierarchy()).isNotNull();
			assertThat(config.getHierarchy()).isEmpty();
			assertThat(config.getRequired()).isNotNull();
			assertThat(config.getRequired()).isEmpty();
			assertThat(config.getCustomFieldColumns()).isNotNull();
			assertThat(config.getCustomFieldColumns()).isEmpty();
			assertThat(config.getStartDateColumn()).isEqualTo("着手予定");
			assertThat(config.getDueDateColumn()).isEqualTo("完了予定");
			assertThat(config.getStatusColumn()).isEqualTo("ステータス");
			assertThat(config.getProgressColumn()).isEqualTo("進捗率");
			assertThat(config.getTicketIdColumn()).isEqualTo("チケットID");
			assertThat(config.getTrackerColumn()).isEqualTo("トラッカー");
		}

		@Test
		@DisplayName("SyncConfigにColumnsConfigを設定できる")
		void syncConfig_withColumnsConfig() {
			SyncConfig syncConfig = new SyncConfig();
			ColumnsConfig columnsConfig = new ColumnsConfig();
			columnsConfig.setHierarchy(List.of("大分類", "中分類", "小分類"));

			syncConfig.setColumns(columnsConfig);

			assertThat(syncConfig.getColumns()).isNotNull();
			assertThat(syncConfig.getColumns().getHierarchy()).containsExactly("大分類", "中分類", "小分類");
		}
	}

	@Nested
	@DisplayName("ColumnsConfig読み込みテスト")
	class LoadColumnsConfigTests {

		@Test
		@DisplayName("columns設定が正しく読み込まれる")
		void loadConfig_withColumnsSettings_success() {
			service.loadConfig("test-sync-config.yml");

			List<ProjectConfig> projects = service.getAllProjects();
			ProjectConfig project = projects.get(0);

			assertThat(project.getSync()).isNotNull();
			assertThat(project.getSync().getColumns()).isNotNull();

			ColumnsConfig columns = project.getSync().getColumns();
			assertThat(columns.getHierarchy()).containsExactly("大分類", "中分類", "小分類", "成果物", "タスク");
			assertThat(columns.getStartDateColumn()).isEqualTo("開始日");
			assertThat(columns.getDueDateColumn()).isEqualTo("期限");
			assertThat(columns.getStatusColumn()).isEqualTo("状態");
			assertThat(columns.getProgressColumn()).isEqualTo("進捗率");
			assertThat(columns.getTicketIdColumn()).isEqualTo("Redmine番号");
			assertThat(columns.getTrackerColumn()).isEqualTo("種別");
			assertThat(project.getSync().getTrackerMap()).containsEntry("タスク", "2").containsEntry("サマリ", "6");
			assertThat(project.getSync().getDeletion().getStatusId()).isEqualTo(6);
			assertThat(columns.getRequired()).containsExactly("チケットID", "チーム", "工程");
			assertThat(columns.getCustomFieldColumns()).containsExactly("チーム", "工程");
		}

		@Test
		@DisplayName("columns設定がない場合はnullを返す")
		void loadConfig_withoutColumnsSettings_returnsNull() {
			service.loadConfig("test-multiple-projects.yml");

			List<ProjectConfig> projects = service.getAllProjects();
			ProjectConfig project = projects.get(0);

			// columns設定がない場合はnull
			assertThat(project.getSync().getColumns()).isNull();
			// deletion設定がない場合はnull（論理削除は候補のログ出力のみ）
			assertThat(project.getSync().getDeletion()).isNull();
		}
	}
}
