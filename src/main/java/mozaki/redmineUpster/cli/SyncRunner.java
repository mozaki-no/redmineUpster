package mozaki.redmineUpster.cli;

import java.io.IOException;
import java.util.HashSet;
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

/**
 * 同期実行サービス。
 * <p>
 * CLI同期モードの統合フローを実装します。
 * 設定ファイル読み込み、CSV/Excel解析、差分計算、同期実行を行います。
 * DBには差分/履歴を保存せず、インメモリで処理します。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class SyncRunner {

    private final SyncConfigService syncConfigService;
    private final SpreadsheetParser spreadsheetParser;
    private final DiffCalculator diffCalculator;
    private final SyncExecutor syncExecutor;
    private final RedmineClientFactory redmineClientFactory;

    /**
     * 同期を実行します。
     *
     * @param configPath 設定ファイルパス（nullの場合はデフォルト設定を使用）
     * @param projectName プロジェクト名（nullの場合はデフォルトプロジェクトを使用）
     * @param filePath CSV/Excelファイルパス
     * @param dryRun ドライランモードの場合はtrue
     * @param logDir ログ出力ディレクトリ
     * @param debug デバッグモードの場合はtrue
     * @return 成功の場合は0、失敗の場合は1
     */
        public int run(String configPath, String projectName, String filePath, boolean dryRun, String logDir, boolean debug,
            boolean relinkOnly, boolean forceUpdate, boolean resetSync) {
        FileLogger logger = null;
        try {
            // 1. ロガーの初期化
            logger = new FileLogger(logDir);
            logger.setDebugEnabled(debug);
            logger.info("=== Redmine Sync Started ===");
            logger.info("File: " + filePath);
            logger.info("Dry Run: " + dryRun);
            logger.info("Debug: " + debug);
            logger.info("Reset Sync: " + resetSync);
            logger.info("Log File: " + logger.getLogFile());

            // 2. 設定ファイル読み込み
            if (configPath != null && !configPath.isBlank()) {
                logger.info("Loading config from: " + configPath);
                logger.debug("Config file path: " + configPath);
                syncConfigService.loadConfig(configPath);
            }

            // 3. プロジェクト設定を取得
            ProjectConfig projectConfig = resolveProject(projectName, logger);
            if (projectConfig == null) {
                logger.error("No project configuration found");
                return 1;
            }
            logger.info("Project: " + projectConfig.getName());
            logger.debug("Config loaded: " + projectConfig.getName());
            if (projectConfig.getRedmine() != null) {
                logger.debug("Redmine URL: " + projectConfig.getRedmine().getBaseUrl());
                logger.debug("Project ID: " + projectConfig.getRedmine().getProjectId());
            }

            // 4. CSV/Excel解析（階層列のみfill-down補完する）
            Set<String> fillDownColumns = resolveFillDownColumns(projectConfig);
            logger.info("Parsing file: " + filePath);
            logger.debug("Fill-down columns: " + fillDownColumns);
            ParsedSheet parsed = spreadsheetParser.parseFromPath(filePath, new java.util.ArrayList<>(fillDownColumns));
            List<Map<String, String>> rows = parsed.rows();
            logger.info("Parsed " + rows.size() + " rows");

            // デバッグ: 各行のデータを出力
            for (int i = 0; i < rows.size(); i++) {
                Map<String, String> row = rows.get(i);
                String id = row.get("id");
                String subject = row.get("タスク");
                if (subject == null) {
                    subject = row.get("成果物");
                }
                logger.debug("Parsing row " + (i + 1) + ": id=" + id + ", subject=" + subject);
            }

            if (rows.isEmpty()) {
                logger.warn("No data rows found in file");
                return 0;
            }

            // 5. 差分計算（インメモリ）
            logger.info("Calculating diff...");
            List<DiffItem> items = diffCalculator.calculate(rows, projectConfig, logger, relinkOnly, resetSync);
            logger.info("Diff items: " + items.size());

            long createCount = items.stream().filter(i -> "CREATE".equals(i.action())).count();
            long updateCount = items.stream().filter(i -> "UPDATE".equals(i.action())).count();
            long deleteCount = items.stream().filter(i -> "DELETE".equals(i.action())).count();
            logger.info("  CREATE: " + createCount);
            logger.info("  UPDATE: " + updateCount);
            logger.info("  DELETE: " + deleteCount);

            // 6. Redmineクライアント作成
            RedmineClient client = redmineClientFactory.createClient(projectConfig);
            client.setLogger(logger);
            logger.info("Redmine URL: " + client.getBaseUrl());
            logger.info("Redmine Project: " + client.getProjectId());

            // 7. 同期実行
            logger.info("Executing sync...");
            SyncResult result = syncExecutor.execute(items, projectConfig, client, dryRun, logger, forceUpdate);

            // 8. 結果出力
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

            return result.errorCount() > 0 ? 1 : 0;

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
     * fill-down（前行値補完）対象の列名セットを返します。
     * 階層列のみが対象です。
     */
    private Set<String> resolveFillDownColumns(ProjectConfig projectConfig) {
        if (projectConfig.getSync() != null && projectConfig.getSync().getColumns() != null) {
            List<String> hierarchy = projectConfig.getSync().getColumns().getHierarchy();
            if (hierarchy != null && !hierarchy.isEmpty()) {
                return new HashSet<>(hierarchy);
            }
        }
        return new HashSet<>(mozaki.redmineUpster.util.ColumnDefinitions.HIERARCHY_COLUMNS);
    }
}
