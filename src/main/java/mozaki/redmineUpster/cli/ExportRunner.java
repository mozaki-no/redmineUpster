package mozaki.redmineUpster.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import lombok.RequiredArgsConstructor;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.RedmineClientFactory;

/**
 * Excel 出力（{@code --export}）の実行クラス。
 * <p>
 * 設定ファイルのプロジェクトのチケットと、Redmine のユーザー・グループを取得して .xlsx に出力します。
 * 出力したファイルはそのまま {@code --sync} の入力に使えます。
 * チケットを出力する場合は、参照用のシート（トラッカー・ステータス・カスタムフィールドとその選択肢）も出力します
 * （カスタムフィールドの取得には管理者の API キーが必要。取得できなければ警告してそのシートを省略）。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class ExportRunner {

    private final SyncRunner syncRunner;
    private final RedmineClientFactory redmineClientFactory;
    private final WorkbookExporter workbookExporter;

    /**
     * Excel に出力します。
     *
     * @param configPath 設定ファイルパス（null なら既定の場所）
     * @param projectName プロジェクト名（null ならデフォルトプロジェクト）
     * @param filePath 出力先（.xlsx。既にあれば {@code <file>.bak} に退避して上書き）
     * @param logDir ログ出力ディレクトリ（null ならカレントディレクトリの logs）
     * @param debug デバッグモードの場合 true
     * @param targets 出力する対象（null ならすべて）
     * @return 成功なら 0、失敗なら 1
     */
    public int run(String configPath, String projectName, String filePath, String logDir, boolean debug,
            Set<SyncTarget> targets) {
        return run(configPath, projectName, filePath, logDir, debug, targets, null);
    }

    /**
     * Excel に出力します（ルートチケットを CLI で指定）。
     * <p>
     * ルートチケットは、--root-issue ＞ 設定 sync.files で出力先と同じファイルに指定した rootIssueId の順に決めます。
     * ルートチケットがある場合は、その配下（ルート自身は含まない）だけをルートの子を最上位として出力します
     * （同期でルートの子になる最上位の行と同じ形なので、そのまま取り込めます）。
     * </p>
     *
     * @param configPath 設定ファイルパス（null なら既定の場所）
     * @param projectName プロジェクト名（null ならデフォルトプロジェクト）
     * @param filePath 出力先（.xlsx。既にあれば {@code <file>.bak} に退避して上書き）
     * @param logDir ログ出力ディレクトリ（null ならカレントディレクトリの logs）
     * @param debug デバッグモードの場合 true
     * @param targets 出力する対象（null ならすべて）
     * @param cliRootIssueId CLI の --root-issue（null なら設定 sync.files の rootIssueId に従う）
     * @return 成功なら 0、失敗なら 1
     */
    public int run(String configPath, String projectName, String filePath, String logDir, boolean debug,
            Set<SyncTarget> targets, Long cliRootIssueId) {
        Set<SyncTarget> selected = targets == null ? EnumSet.allOf(SyncTarget.class) : targets;
        FileLogger logger = null;
        try {
            logger = new FileLogger(logDir == null || logDir.isBlank() ? SyncRunner.DEFAULT_LOG_DIR : logDir);
            logger.setDebugEnabled(debug);
            logger.info("=== Redmine Export Started ===");
            logger.info("Output: " + filePath);
            logger.info("Targets: " + selected);
            logger.info("Log File: " + logger.getLogFile());
            if (!filePath.toLowerCase().endsWith(".xlsx")) {
                logger.error("出力先は .xlsx にしてください: " + filePath);
                return 1;
            }
            if (!syncRunner.loadConfig(configPath, logger)) {
                return 1;
            }
            ProjectConfig projectConfig = syncRunner.resolveProject(projectName, logger);
            if (projectConfig == null) {
                logger.error("No project configuration found");
                return 1;
            }
            logger.info("Project: " + projectConfig.getName());
            RedmineClient client = redmineClientFactory.createClient(projectConfig);
            client.setLogger(logger);
            logger.info("Redmine URL: " + client.getBaseUrl());

            Map<Long, Map<String, Object>> issues = null;
            Long rootIssueId = null;
            if (selected.contains(SyncTarget.TICKETS)) {
                rootIssueId = cliRootIssueId != null ? cliRootIssueId
                        : SyncRunner.configuredRootIssueId(projectConfig, filePath);
                logger.info("Fetching issues of project " + client.getProjectId() + "...");
                issues = client.listProjectIssues();
                logger.info("Fetched " + issues.size() + " issues (closed included, subprojects excluded)");
                if (rootIssueId != null && !checkRootIssue(rootIssueId, issues, client, logger)) {
                    return 1;
                }
            }
            // ユーザー・グループは担当の表示（ログインID・グループ名）にも使うので、チケットだけの出力でも取得を試みる
            Map<Long, Map<String, Object>> users = fetchOptional("users", client::listUsers, logger,
                    selected.contains(SyncTarget.USERS) || selected.contains(SyncTarget.GROUPS));
            Map<Long, Map<String, Object>> groups = fetchOptional("groups", client::listGroups, logger,
                    selected.contains(SyncTarget.GROUPS));
            if (selected.contains(SyncTarget.GROUPS) && groups != null && users == null) {
                logger.warn("ユーザーを取得できないため、グループのメンバーは #ID で出力します");
            }

            WorkbookExporter.ReferenceData reference = selected.contains(SyncTarget.TICKETS)
                    ? fetchReference(client, logger) : null;
            WorkbookExporter.ExportData data = new WorkbookExporter.ExportData(issues,
                    selected.contains(SyncTarget.USERS) ? users : null,
                    selected.contains(SyncTarget.GROUPS) ? groups : null, rootIssueId, reference);
            if (data.issues() == null && data.users() == null && data.groups() == null) {
                logger.error("出力できるデータがありません（ユーザー・グループの取得には管理者の API キーが必要です）");
                return 1;
            }
            Path out = Paths.get(filePath);
            if (Files.exists(out)) {
                Path backup = out.resolveSibling(out.getFileName() + ".bak");
                Files.copy(out, backup, StandardCopyOption.REPLACE_EXISTING);
                logger.info("既存のファイルを " + backup + " に退避して上書きします");
            }
            // 担当のログインID・グループ名の変換用に、出力しない表のデータも渡す
            WorkbookExporter.ExportResult result = workbookExporter.export(out, data, projectConfig, users, groups);
            for (String warning : result.warnings()) {
                logger.warn(warning);
            }
            logger.info("=== Export Complete ===");
            for (Map.Entry<String, Integer> sheet : result.sheets().entrySet()) {
                logger.info("  シート「" + sheet.getKey() + "」: " + sheet.getValue() + " 行");
            }
            logger.info("Wrote " + out.toAbsolutePath());
            return 0;
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
     * ルートチケットを確認します。プロジェクトのチケットになければ個別に取得します（別プロジェクトのチケットでもよい）。
     * 配下のチケットがない場合は警告します（見出しだけのチケットのシートを出力）。
     *
     * @return ルートチケットがあれば true
     */
    private static boolean checkRootIssue(Long rootIssueId, Map<Long, Map<String, Object>> issues,
            RedmineClient client, FileLogger logger) {
        Map<String, Object> root = issues.get(rootIssueId);
        if (root == null) {
            root = client.getIssue(rootIssueId);
            if (root == null || root.isEmpty()) {
                logger.error("ルートチケット #" + rootIssueId + " が見つかりません");
                return false;
            }
            logger.info("ルートチケット #" + rootIssueId + " は出力するプロジェクト外のチケットです");
        }
        logger.info("ルートチケット #" + rootIssueId + " 「" + root.get("subject") + "」の配下を出力します（ルート自身は出力しません）");
        if (WorkbookExporter.descendants(issues, rootIssueId).isEmpty()) {
            logger.warn("ルートチケット #" + rootIssueId + " の配下にプロジェクトのチケットがありません（チケットのシートは見出しだけです）");
        }
        return true;
    }

    private interface ListFetcher {
        List<Map<String, Object>> fetch();
    }

    /**
     * 参照用のデータ（トラッカー・ステータス・カスタムフィールド）を取得します。取得できないものは警告して省略します。
     */
    static WorkbookExporter.ReferenceData fetchReference(RedmineClient client, FileLogger logger) {
        List<Map<String, Object>> trackers = fetchReferenceList("trackers", "トラッカー", client::listTrackerDetails,
                logger, "");
        List<Map<String, Object>> statuses = fetchReferenceList("issue statuses", "ステータス",
                client::listIssueStatusDetails, logger, "");
        List<Map<String, Object>> customFields = fetchReferenceList("custom fields", "カスタムフィールド",
                client::listCustomFields, logger, "カスタムフィールドの一覧の取得には管理者の API キーが必要です。");
        return new WorkbookExporter.ReferenceData(trackers, statuses, customFields);
    }

    private static List<Map<String, Object>> fetchReferenceList(String kind, String label, ListFetcher fetcher,
            FileLogger logger, String hint) {
        try {
            logger.info("Fetching " + kind + "（参照用シート）...");
            List<Map<String, Object>> result = fetcher.fetch();
            logger.info("Fetched " + (result == null ? 0 : result.size()) + " " + kind);
            return result;
        } catch (RestClientResponseException ex) {
            logger.warn(label + "を取得できません（" + ex.getStatusCode().value() + "。" + hint
                    + "参照用のシート「" + label + "」は出力しません）");
            return null;
        } catch (RuntimeException ex) {
            logger.warn(label + "を取得できません（" + ex.getMessage() + "。参照用のシート「" + label + "」は出力しません）");
            return null;
        }
    }

    private interface Fetcher {
        Map<Long, Map<String, Object>> fetch();
    }

    /**
     * ユーザー・グループを取得します。権限がない（401/403）場合は、必須なら警告、そうでなければデバッグログにして null。
     */
    private static Map<Long, Map<String, Object>> fetchOptional(String kind, Fetcher fetcher, FileLogger logger,
            boolean required) {
        try {
            logger.info("Fetching " + kind + "...");
            Map<Long, Map<String, Object>> result = fetcher.fetch();
            logger.info("Fetched " + result.size() + " " + kind);
            return result;
        } catch (RestClientResponseException ex) {
            String message = kind + " を取得できません（" + ex.getStatusCode().value()
                    + "。ユーザー・グループの取得には管理者の API キーが必要です）";
            if (required) {
                logger.warn(message + "。このシートは出力しません");
            } else {
                logger.info(message + "。担当はユーザーIDで出力します");
            }
            return null;
        }
    }
}
