package mozaki.redmineUpster.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.RedmineClientFactory;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet;
import mozaki.redmineUpster.service.SyncConfigService;
import mozaki.redmineUpster.service.TicketIdWriter;
import mozaki.redmineUpster.util.ColumnDefinitions;

/**
 * 同期実行サービス。
 * <p>
 * CLI同期モードの統合フローを実装します。
 * 設定ファイル読み込み、CSV/Excel解析、差分計算・検証、同期実行、チケットIDの書き戻しを行います。
 * DBは使わず、同期開始時にRedmineから取得したプロジェクトのチケットを正として処理します。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class SyncRunner {

    /** 既定の設定ファイル名 */
    static final String DEFAULT_CONFIG_FILE = "sync-config.yml";
    /** 既定のログ出力先（カレントディレクトリからの相対パス） */
    static final String DEFAULT_LOG_DIR = "logs";

    private final SyncConfigService syncConfigService;
    private final SpreadsheetParser spreadsheetParser;
    private final DiffCalculator diffCalculator;
    private final SyncExecutor syncExecutor;
    private final RedmineClientFactory redmineClientFactory;
    private final TicketIdWriter ticketIdWriter;

    /**
     * 同期を実行します。
     *
     * @param configPath 設定ファイルパス（nullの場合は既定の場所の sync-config.yml）
     * @param projectName プロジェクト名（nullの場合はデフォルトプロジェクトを使用）
     * @param filePath CSV/Excelファイルパス
     * @param dryRun ドライランモードの場合はtrue
     * @param logDir ログ出力ディレクトリ（nullの場合はカレントディレクトリの logs）
     * @param debug デバッグモードの場合はtrue
     * @param forceUpdate 変更なしスキップを無効化する場合はtrue
     * @return 成功の場合は0、失敗の場合は1
     */
    public int run(String configPath, String projectName, String filePath, boolean dryRun, String logDir, boolean debug,
            boolean forceUpdate) {
        FileLogger logger = null;
        try {
            // 1. ロガーの初期化
            logger = new FileLogger(logDir == null || logDir.isBlank() ? DEFAULT_LOG_DIR : logDir);
            logger.setDebugEnabled(debug);
            logger.info("=== Redmine Sync Started ===");
            logger.info("File: " + filePath);
            logger.info("Dry Run: " + dryRun);
            logger.info("Debug: " + debug);
            logger.info("Force Update: " + forceUpdate);
            logger.info("Log File: " + logger.getLogFile());

            // 2. 設定ファイル読み込み
            //    --config 指定 → そのファイル。未指定で SYNC_CONFIG_PATH から読み込み済み → それを使う。
            //    どちらもなければカレントディレクトリ → 実行ファイルと同じフォルダの sync-config.yml
            if (configPath != null && !configPath.isBlank()) {
                logger.info("Loading config from: " + configPath);
                syncConfigService.loadConfig(configPath);
            } else if (syncConfigService.getAllProjects().isEmpty()) {
                List<Path> candidates = defaultConfigCandidates();
                Optional<Path> found = candidates.stream().filter(Files::isRegularFile).findFirst();
                if (found.isEmpty()) {
                    logger.error("設定ファイルが見つかりません。--config=<パス> で指定するか、次のいずれかに "
                            + DEFAULT_CONFIG_FILE + " を置いてください: " + candidates);
                    return 1;
                }
                logger.info("Loading config from: " + found.get());
                syncConfigService.loadConfig(found.get().toString());
            }

            // 3. プロジェクト設定を取得
            ProjectConfig projectConfig = resolveProject(projectName, logger);
            if (projectConfig == null) {
                logger.error("No project configuration found");
                return 1;
            }
            logger.info("Project: " + projectConfig.getName());
            if (projectConfig.getRedmine() != null) {
                logger.debug("Redmine URL: " + projectConfig.getRedmine().getBaseUrl());
                logger.debug("Project ID: " + projectConfig.getRedmine().getProjectId());
            }

            // 4. CSV/Excel解析（階層列の空欄=階層を飛ばす。fillDownHierarchy: true のときのみ前行値で補完）
            List<String> hierarchyColumns = resolveHierarchyColumns(projectConfig);
            boolean fillDownHierarchy = projectConfig.getSync() != null
                    && projectConfig.getSync().getColumns() != null
                    && projectConfig.getSync().getColumns().isFillDownHierarchy();
            logger.info("Parsing file: " + filePath);
            logger.debug("Hierarchy columns: " + hierarchyColumns + " fillDownHierarchy=" + fillDownHierarchy);
            ParsedSheet parsed = spreadsheetParser.parseFromPath(filePath, hierarchyColumns, fillDownHierarchy);
            List<Map<String, String>> rows = parsed.rows();
            logger.info("Parsed " + rows.size() + " rows");
            String ticketIdColumn = DiffCalculator.getTicketIdColumn(projectConfig);
            if (!parsed.headers().contains(ticketIdColumn)) {
                logger.warn("チケットID列「" + ticketIdColumn + "」がファイルにありません。全行を新規作成として扱い、"
                        + "書き戻し時に列を末尾へ追加します");
            }
            if (logger.isDebugEnabled()) {
                for (int i = 0; i < rows.size(); i++) {
                    logger.debug("Row " + parsed.rowNumbers().get(i) + ": " + rows.get(i));
                }
            }

            // 5. Redmineクライアント作成（トラッカー名の解決・存在確認に使用）
            RedmineClient client = redmineClientFactory.createClient(projectConfig);
            client.setLogger(logger);
            logger.info("Redmine URL: " + client.getBaseUrl());
            logger.info("Redmine Project: " + client.getProjectId());

            // 6. 差分計算と検証（エラーがあればRedmineに一切書き込まない）
            logger.info("Calculating diff...");
            Map<String, String> trackerMap = projectConfig.getSync() != null
                    ? projectConfig.getSync().getTrackerMap() : Map.of();
            TrackerResolver trackerResolver = new TrackerResolver(trackerMap, client);
            DiffPlan plan = diffCalculator.calculate(parsed, projectConfig, trackerResolver, logger);
            if (plan.hasErrors()) {
                logger.error("入力ファイルの検証エラー: " + plan.errors().size() + "件（Redmineは更新していません）");
                for (String error : plan.errors()) {
                    logger.error("  - " + error);
                }
                return 1;
            }
            List<DiffItem> items = plan.items();
            long createCount = items.stream().filter(i -> SyncConstants.ACTION_CREATE.equals(i.action())).count();
            long updateCount = items.stream().filter(i -> SyncConstants.ACTION_UPDATE.equals(i.action())).count();
            logger.info("Diff items: " + items.size());
            logger.info("  CREATE: " + createCount);
            logger.info("  UPDATE: " + updateCount);

            // 7. 同期先プロジェクトのチケットを全件取得（DBの代わりにRedmineの現在の状態を正とする）
            logger.info("Fetching issues of project " + client.getProjectId() + " from Redmine...");
            Map<Long, Map<String, Object>> projectIssues = client.listProjectIssues();
            logger.info("Fetched " + projectIssues.size() + " issues (closed included, subprojects excluded)");

            Set<Long> excelIssueIds = new HashSet<>();
            for (DiffItem item : items) {
                if (item.issueId() != null) {
                    excelIssueIds.add(item.issueId());
                }
            }
            Integer deleteStatusId = projectConfig.getSync() != null && projectConfig.getSync().getDeletion() != null
                    ? projectConfig.getSync().getDeletion().getStatusId() : null;
            List<Long> deleteCandidates = DiffCalculator.findLogicalDeleteCandidates(excelIssueIds, projectIssues,
                    deleteStatusId);
            logger.info("  LOGICAL_DELETE candidates: " + deleteCandidates.size()
                    + "（Excelにないプロジェクト内のチケット。Redmineで手動作成したチケットも含みます）");

            // 8. 同期実行
            logger.info("Executing sync...");
            SyncResult result = syncExecutor.execute(items, projectIssues, deleteCandidates, projectConfig, client,
                    dryRun, logger, forceUpdate);

            // 9. 新規作成したチケットIDを入力ファイルへ書き戻す
            boolean writeBackFailed = false;
            if (!dryRun && !result.createdIssueIds().isEmpty()) {
                try {
                    Path backup = ticketIdWriter.writeBack(filePath, ticketIdColumn, result.createdIssueIds());
                    logger.info("Wrote " + result.createdIssueIds().size() + " ticket IDs back to " + filePath
                            + " (backup: " + backup + ")");
                } catch (IOException | RuntimeException e) {
                    writeBackFailed = true;
                    logger.error("チケットIDの書き戻しに失敗しました: " + e.getMessage());
                    logger.error("次回実行で重複作成しないよう、以下のIDを手動で「" + ticketIdColumn + "」列に入力してください:");
                    for (Map.Entry<Integer, Long> entry : result.createdIssueIds().entrySet()) {
                        logger.error("  行" + entry.getKey() + " -> " + entry.getValue());
                    }
                }
            }

            // 10. 結果出力
            logger.info("=== Sync Complete ===");
            logger.info("Total: " + result.totalCount());
            logger.info("Success: " + result.successCount());
            logger.info("Errors: " + result.errorCount());
            if (!result.errors().isEmpty()) {
                logger.warn("Error details:");
                for (String error : result.errors()) {
                    logger.warn("  - " + error);
                }
            }
            return result.errorCount() > 0 || writeBackFailed ? 1 : 0;

        } catch (IOException e) {
            if (logger != null) {
                logger.error("IO error: " + e.getMessage());
            } else {
                System.err.println("IO error: " + e.getMessage());
            }
            return 1;
        } catch (Exception e) {
            if (logger != null) {
                logger.error("Unexpected error: " + e.getMessage());
            } else {
                System.err.println("Unexpected error: " + e.getMessage());
            }
            return 1;
        } finally {
            if (logger != null) {
                logger.close();
            }
        }
    }

    /**
     * プロジェクト設定を解決します。
     *
     * @param projectName プロジェクト名
     * @param logger ロガー
     * @return プロジェクト設定
     */
    private ProjectConfig resolveProject(String projectName, FileLogger logger) {
        if (projectName != null && !projectName.isBlank()) {
            Optional<ProjectConfig> byName = syncConfigService.getProjectByName(projectName);
            if (byName.isPresent()) {
                return byName.get();
            }
            logger.warn("Project not found: " + projectName + ", trying default project");
        }

        Optional<ProjectConfig> defaultProject = syncConfigService.getDefaultProject();
        if (defaultProject.isPresent()) {
            return defaultProject.get();
        }

        // デフォルトがない場合は最初のプロジェクトを使用
        List<ProjectConfig> allProjects = syncConfigService.getAllProjects();
        if (!allProjects.isEmpty()) {
            logger.warn("No default project, using first project: " + allProjects.get(0).getName());
            return allProjects.get(0);
        }

        return null;
    }

    /**
     * 設定ファイルの既定の置き場所（優先順）を返します。
     * <ol>
     *   <li>カレントディレクトリの sync-config.yml</li>
     *   <li>実行ファイル（jpackageのランチャー。なければjar）と同じフォルダの sync-config.yml</li>
     * </ol>
     *
     * @return 候補のパス（重複なし）
     */
    static List<Path> defaultConfigCandidates() {
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(Paths.get(DEFAULT_CONFIG_FILE).toAbsolutePath().normalize());
        Path appDir = applicationDirectory();
        if (appDir != null) {
            candidates.add(appDir.resolve(DEFAULT_CONFIG_FILE).toAbsolutePath().normalize());
        }
        return new ArrayList<>(candidates);
    }

    /**
     * 実行ファイルのあるフォルダを返します。
     * <p>
     * jpackageで作ったランチャーから起動した場合はシステムプロパティ {@code jpackage.app-path}
     * （Windowsは redmineUpster.exe、Linuxは bin/redmineUpster）のフォルダ。
     * Linuxの bin フォルダの場合はその1つ上。jarで起動した場合はjarのあるフォルダ。
     * </p>
     */
    private static Path applicationDirectory() {
        try {
            String launcher = System.getProperty("jpackage.app-path");
            if (launcher != null && !launcher.isBlank()) {
                Path dir = Paths.get(launcher).toAbsolutePath().getParent();
                if (dir != null && dir.getFileName() != null && "bin".equals(dir.getFileName().toString())
                        && dir.getParent() != null) {
                    dir = dir.getParent();
                }
                return dir;
            }
            java.security.CodeSource source = SyncRunner.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            String location = source.getLocation().toString();
            // Spring Boot の fat jar では "jar:nested:/path/app.jar/!BOOT-INF/classes/!/" の形式になる
            int nested = location.indexOf("nested:");
            if (nested >= 0) {
                String path = location.substring(nested + "nested:".length());
                int end = path.indexOf("!");
                path = end >= 0 ? path.substring(0, end) : path;
                if (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                return Paths.get(java.net.URI.create("file:" + path)).getParent();
            }
            Path path = Paths.get(source.getLocation().toURI());
            return Files.isRegularFile(path) ? path.getParent() : null;
        } catch (RuntimeException | java.net.URISyntaxException e) {
            return null;
        }
    }

    /**
     * 前行値補完の対象とする階層列（浅い順）を返します。
     */
    private List<String> resolveHierarchyColumns(ProjectConfig projectConfig) {
        if (projectConfig.getSync() != null && projectConfig.getSync().getColumns() != null) {
            List<String> hierarchy = projectConfig.getSync().getColumns().getHierarchy();
            if (hierarchy != null && !hierarchy.isEmpty()) {
                return hierarchy;
            }
        }
        return ColumnDefinitions.HIERARCHY_COLUMNS;
    }
}
