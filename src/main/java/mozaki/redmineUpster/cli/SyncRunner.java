package mozaki.redmineUpster.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import mozaki.redmineUpster.config.SyncConfigProperties.ExcelConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.SyncConfig;
import mozaki.redmineUpster.service.ExcelSource;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.RedmineClientFactory;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet;
import mozaki.redmineUpster.service.SyncConfigService;
import mozaki.redmineUpster.service.TicketIdWriter;

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
    private final DirectorySync directorySync;

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
        return run(configPath, projectName, filePath, dryRun, logDir, debug, forceUpdate, null);
    }

    /**
     * 同期を実行します（Excel の読み込み元を CLI で指定）。
     *
     * @param configPath 設定ファイルパス（nullの場合は既定の場所の sync-config.yml）
     * @param projectName プロジェクト名（nullの場合はデフォルトプロジェクトを使用）
     * @param filePath CSV/Excelファイルパス
     * @param dryRun ドライランモードの場合はtrue
     * @param logDir ログ出力ディレクトリ（nullの場合はカレントディレクトリの logs）
     * @param debug デバッグモードの場合はtrue
     * @param forceUpdate 変更なしスキップを無効化する場合はtrue
     * @param cliExcelSource CLI の --sheet / --table（null可。設定ファイルの sync.excel より優先）
     * @return 成功の場合は0、失敗の場合は1
     */
    public int run(String configPath, String projectName, String filePath, boolean dryRun, String logDir, boolean debug,
            boolean forceUpdate, ExcelSource cliExcelSource) {
        return run(configPath, projectName, filePath, dryRun, logDir, debug, forceUpdate, cliExcelSource, null);
    }

    /**
     * 同期を実行します（仮想親チケットの作成有無を CLI で指定）。
     *
     * @param configPath 設定ファイルパス（nullの場合は既定の場所の sync-config.yml）
     * @param projectName プロジェクト名（nullの場合はデフォルトプロジェクトを使用）
     * @param filePath CSV/Excelファイルパス
     * @param dryRun ドライランモードの場合はtrue
     * @param logDir ログ出力ディレクトリ（nullの場合はカレントディレクトリの logs）
     * @param debug デバッグモードの場合はtrue
     * @param forceUpdate 変更なしスキップを無効化する場合はtrue
     * @param cliExcelSource CLI の --sheet / --table（null可。設定ファイルの sync.excel より優先）
     * @param cliVirtualParents CLI の --virtual-parents（true）/ --no-virtual-parents（false）。
     *        null なら設定 sync.virtualParents.enabled に従う
     * @return 成功の場合は0、失敗の場合は1
     */
    public int run(String configPath, String projectName, String filePath, boolean dryRun, String logDir, boolean debug,
            boolean forceUpdate, ExcelSource cliExcelSource, Boolean cliVirtualParents) {
        return run(configPath, projectName, filePath, dryRun, logDir, debug, forceUpdate, cliExcelSource,
                cliVirtualParents, null);
    }

    /**
     * 同期を実行します（対象を CLI で指定）。
     * <p>
     * Excel にシート「ユーザー」「グループ」（設定 sync.users / sync.groups で変更可）があれば、
     * チケットの前にユーザー → グループの順に Upsert します。すべての表を検証してから Redmine に書き込みます。
     * </p>
     *
     * @param configPath 設定ファイルパス（nullの場合は既定の場所の sync-config.yml）
     * @param projectName プロジェクト名（nullの場合はデフォルトプロジェクトを使用）
     * @param filePath CSV/Excelファイルパス
     * @param dryRun ドライランモードの場合はtrue
     * @param logDir ログ出力ディレクトリ（nullの場合はカレントディレクトリの logs）
     * @param debug デバッグモードの場合はtrue
     * @param forceUpdate 変更なしスキップを無効化する場合はtrue
     * @param cliExcelSource CLI の --sheet / --table（null可。設定ファイルの sync.excel より優先）
     * @param cliVirtualParents CLI の --virtual-parents（true）/ --no-virtual-parents（false）。
     *        null なら設定 sync.virtualParents.enabled に従う
     * @param targets 対象（null ならファイルにある表すべて）
     * @return 成功の場合は0、失敗の場合は1
     */
    public int run(String configPath, String projectName, String filePath, boolean dryRun, String logDir, boolean debug,
            boolean forceUpdate, ExcelSource cliExcelSource, Boolean cliVirtualParents, Set<SyncTarget> targets) {
        FileLogger logger = null;
        Set<SyncTarget> selected = targets == null ? EnumSet.allOf(SyncTarget.class) : targets;
        try {
            // 1. ロガーの初期化
            logger = new FileLogger(logDir == null || logDir.isBlank() ? DEFAULT_LOG_DIR : logDir);
            logger.setDebugEnabled(debug);
            logger.info("=== Redmine Sync Started ===");
            logger.info("File: " + filePath);
            logger.info("Dry Run: " + dryRun);
            logger.info("Debug: " + debug);
            logger.info("Force Update: " + forceUpdate);
            logger.info("Targets: " + selected);
            logger.info("Log File: " + logger.getLogFile());

            // 2. 設定ファイル読み込み
            //    --config 指定 → そのファイル。未指定で SYNC_CONFIG_PATH から読み込み済み → それを使う。
            //    どちらもなければカレントディレクトリ → 実行ファイルと同じフォルダの sync-config.yml
            if (!loadConfig(configPath, logger)) {
                return 1;
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

            // 4. ユーザー・グループの表（Excel のみ）を決める
            boolean isCsv = filePath.toLowerCase().endsWith(".csv");
            List<String> sheetNames = isCsv ? List.of() : spreadsheetParser.sheetNames(filePath);
            SyncConfig syncConfig = projectConfig.getSync();
            ExcelSource usersSource = selected.contains(SyncTarget.USERS) && !isCsv
                    ? directorySource(syncConfig != null ? syncConfig.getUsers() : null,
                            DirectorySync.DEFAULT_USERS_SHEET, sheetNames)
                    : null;
            ExcelSource groupsSource = selected.contains(SyncTarget.GROUPS) && !isCsv
                    ? directorySource(syncConfig != null ? syncConfig.getGroups() : null,
                            DirectorySync.DEFAULT_GROUPS_SHEET, sheetNames)
                    : null;
            if (isCsv && (targets != null && (selected.contains(SyncTarget.USERS)
                    || selected.contains(SyncTarget.GROUPS)))) {
                logger.warn("CSVファイルのため、ユーザー・グループは同期しません（Excel のシートで指定してください）");
            }

            // 5. チケットの表を解析（階層列の決定・fillDownHierarchy の補完は差分計算で行う）
            ExcelSource excelSource = resolveExcelSource(projectConfig, cliExcelSource);
            if (isCsv && !excelSource.isDefault()) {
                String message = "CSVファイルのため、シート・テーブルの指定（" + excelSource.describe() + "）は無視します";
                if (cliExcelSource != null && !cliExcelSource.isDefault()) {
                    logger.warn(message);
                } else {
                    // 設定ファイルの sync.excel（配布版の既定は table: 取込表）は CSV では使わないだけなので警告にしない
                    logger.info(message);
                }
                excelSource = ExcelSource.DEFAULT;
            }
            boolean doTickets = selected.contains(SyncTarget.TICKETS);
            if (doTickets && !isCsv && excelSource.isDefault() && !sheetNames.isEmpty()
                    && isDirectorySheet(sheetNames.get(0), usersSource, groupsSource)) {
                logger.info("先頭シート「" + sheetNames.get(0) + "」はユーザー・グループの表のため、チケットは同期しません");
                doTickets = false;
            }
            if (doTickets && !isCsv && targets == null && (usersSource != null || groupsSource != null)
                    && excelSource.table() != null && !hasTable(filePath, excelSource)) {
                // ユーザー・グループだけの Excel（チケットの表なし）を、既定の table: 取込表 のままで使う場合
                logger.info("テーブル「" + excelSource.table() + "」がないため、チケットは同期しません");
                doTickets = false;
            }
            if (!doTickets && usersSource == null && groupsSource == null) {
                logger.error("同期する表がありません（対象: " + selected + "。ユーザー・グループはシート「"
                        + DirectorySync.DEFAULT_USERS_SHEET + "」「" + DirectorySync.DEFAULT_GROUPS_SHEET + "」）");
                return 1;
            }

            ParsedSheet parsed = null;
            String ticketIdColumn = DiffCalculator.getTicketIdColumn(projectConfig);
            if (doTickets) {
                logger.info("Parsing file: " + filePath + (isCsv ? "" : "（" + excelSource.describe() + "）"));
                parsed = spreadsheetParser.parseFromPath(filePath, excelSource);
                if (parsed.sheetName() != null) {
                    logger.info("Sheet: " + parsed.sheetName() + "（メッセージの行番号はこのシートの行番号です）");
                }
                logger.info("Parsed " + parsed.rows().size() + " rows");
                if (excelSource.table() != null && !parsed.headers().contains(ticketIdColumn)) {
                    // テーブルへの列の追加は行わない。Redmine に書き込む前に止める
                    logger.error("テーブル「" + excelSource.table() + "」にチケットID列「" + ticketIdColumn
                            + "」がありません。テーブルに列を追加してから実行してください（Redmineは更新していません）");
                    return 1;
                }
                if (!parsed.headers().contains(ticketIdColumn)) {
                    logger.warn("チケットID列「" + ticketIdColumn + "」がファイルにありません。全行を新規作成として扱い、"
                            + "書き戻し時に列を末尾へ追加します");
                }
                if (logger.isDebugEnabled()) {
                    for (int i = 0; i < parsed.rows().size(); i++) {
                        logger.debug("Row " + parsed.rowNumbers().get(i) + ": " + parsed.rows().get(i));
                    }
                }
            }
            ParsedSheet usersSheet = null;
            if (usersSource != null) {
                usersSheet = spreadsheetParser.parseFromPath(filePath, usersSource);
                logger.info("Users: " + usersSource.describe() + " " + usersSheet.rows().size() + " rows");
            }
            ParsedSheet groupsSheet = null;
            if (groupsSource != null) {
                groupsSheet = spreadsheetParser.parseFromPath(filePath, groupsSource);
                logger.info("Groups: " + groupsSource.describe() + " " + groupsSheet.rows().size() + " rows");
            }

            // 6. Redmineクライアント作成（トラッカー名の解決・存在確認に使用）
            RedmineClient client = redmineClientFactory.createClient(projectConfig);
            client.setLogger(logger);
            logger.info("Redmine URL: " + client.getBaseUrl());
            logger.info("Redmine Project: " + client.getProjectId());

            // 7. 検証（エラーがあればRedmineに一切書き込まない）
            List<String> validationErrors = new ArrayList<>();
            Map<Long, Map<String, Object>> users = null;
            Map<Long, Map<String, Object>> groups = null;
            DirectorySync.Plan<DirectorySync.UserRow> userPlan = null;
            DirectorySync.Plan<DirectorySync.GroupRow> groupPlan = null;
            if (usersSheet != null || groupsSheet != null) {
                logger.info("Fetching users" + (groupsSheet != null ? " and groups" : "")
                        + " from Redmine（管理者の API キーが必要です）...");
                users = client.listUsers();
                logger.info("Fetched " + users.size() + " users");
            }
            if (usersSheet != null) {
                userPlan = DirectorySync.parseUsers(usersSheet, users);
                validationErrors.addAll(userPlan.errors());
            }
            if (groupsSheet != null) {
                groups = client.listGroups();
                logger.info("Fetched " + groups.size() + " groups");
                Set<String> knownLogins = new HashSet<>(DirectorySync.loginIndex(users).keySet());
                if (userPlan != null) {
                    userPlan.rows().forEach(row -> knownLogins.add(row.login()));
                }
                groupPlan = DirectorySync.parseGroups(groupsSheet, groups, knownLogins);
                validationErrors.addAll(groupPlan.errors());
            }

            DiffPlan plan = null;
            if (doTickets) {
                logger.info("Calculating diff...");
                Map<String, String> trackerMap = syncConfig != null ? syncConfig.getTrackerMap() : Map.of();
                TrackerResolver trackerResolver = new TrackerResolver(trackerMap, client);
                boolean virtualParents = cliVirtualParents != null ? cliVirtualParents
                        : DiffCalculator.isVirtualParentsEnabled(projectConfig);
                logger.info("Virtual Parents: " + virtualParents
                        + (cliVirtualParents != null ? "（コマンドライン指定）" : "（設定 sync.virtualParents.enabled）"));
                plan = diffCalculator.calculate(parsed, projectConfig, trackerResolver, logger, virtualParents);
                validationErrors.addAll(plan.errors());
            }
            if (!validationErrors.isEmpty()) {
                logger.error("入力ファイルの検証エラー: " + validationErrors.size() + "件（Redmineは更新していません）");
                for (String error : validationErrors) {
                    logger.error("  - " + error);
                }
                return 1;
            }

            // 8. ユーザー → グループの Upsert と ID の書き戻し
            WriteBackState writeBackState = new WriteBackState();
            List<String> resultErrors = new ArrayList<>();
            List<String> summary = new ArrayList<>();
            if (userPlan != null) {
                logger.info("Syncing users...");
                DirectorySync.Result userResult = directorySync.syncUsers(userPlan.rows(), users, client, dryRun,
                        logger);
                summary.add(describe("Users", userResult));
                resultErrors.addAll(userResult.errors());
                writeBackIds(filePath, DirectorySync.COL_ID, userResult.writeBackIds(), usersSource, dryRun,
                        writeBackState, logger);
            }
            if (groupPlan != null) {
                logger.info("Syncing groups...");
                DirectorySync.Result groupResult = directorySync.syncGroups(groupPlan.rows(), groups, users, client,
                        dryRun, logger);
                summary.add(describe("Groups", groupResult));
                resultErrors.addAll(groupResult.errors());
                writeBackIds(filePath, DirectorySync.COL_ID, groupResult.writeBackIds(), groupsSource, dryRun,
                        writeBackState, logger);
            }

            SyncResult result = null;
            if (doTickets) {
                List<DiffItem> items = plan.items();
                // 担当（ログインID・グループ名）とステータス名をIDに変換
                Set<String> plannedLogins = new HashSet<>();
                if (userPlan != null) {
                    userPlan.rows().stream().filter(row -> row.id() == null)
                            .forEach(row -> plannedLogins.add(row.login()));
                }
                items = new TicketValueResolver(client, logger, users, groups, plannedLogins)
                        .resolve(items, syncConfig != null ? syncConfig.getStatus() : null);
                long createCount = items.stream().filter(i -> SyncConstants.ACTION_CREATE.equals(i.action())).count();
                long updateCount = items.stream().filter(i -> SyncConstants.ACTION_UPDATE.equals(i.action())).count();
                logger.info("Diff items: " + items.size());
                logger.info("  CREATE: " + createCount);
                logger.info("  UPDATE: " + updateCount);

                // 9. 同期先プロジェクトのチケットを全件取得（DBの代わりにRedmineの現在の状態を正とする）
                logger.info("Fetching issues of project " + client.getProjectId() + " from Redmine...");
                Map<Long, Map<String, Object>> projectIssues = client.listProjectIssues();
                logger.info("Fetched " + projectIssues.size() + " issues (closed included, subprojects excluded)");

                // 仮想親（ファイルに行がない祖先）を既存チケットに対応付ける（見つからなければ新規作成）
                Integer deleteStatusId = syncConfig != null && syncConfig.getDeletion() != null
                        ? syncConfig.getDeletion().getStatusId() : null;
                if (items.stream().anyMatch(DiffItem::virtual)) {
                    items = VirtualParentMatcher.match(items, projectIssues, deleteStatusId, logger);
                    long virtualTotal = items.stream().filter(DiffItem::virtual).count();
                    long virtualExisting = items.stream().filter(i -> i.virtual() && i.issueId() != null).count();
                    logger.info("  VIRTUAL_PARENT: " + virtualTotal + "（既存 " + virtualExisting + " / 新規作成 "
                            + (virtualTotal - virtualExisting) + "。上の CREATE 件数に含まれます）");
                }

                // Excelの行と、今回も必要な仮想親のチケットは論理削除しない
                Set<Long> excelIssueIds = new HashSet<>();
                for (DiffItem item : items) {
                    if (item.issueId() != null) {
                        excelIssueIds.add(item.issueId());
                    }
                }
                List<Long> deleteCandidates = DiffCalculator.findLogicalDeleteCandidates(excelIssueIds, projectIssues,
                        deleteStatusId);
                logger.info("  LOGICAL_DELETE candidates: " + deleteCandidates.size()
                        + "（Excelにないプロジェクト内のチケット。Redmineで手動作成したチケットも含みます）");

                // 10. 同期実行
                logger.info("Executing sync...");
                result = syncExecutor.execute(items, projectIssues, deleteCandidates, projectConfig, client,
                        dryRun, logger, forceUpdate);

                // 11. 新規作成したチケットIDを入力ファイルへ書き戻す
                if (!dryRun && !result.createdIssueIds().isEmpty()) {
                    try {
                        TicketIdWriter.WriteBackResult writeBack = ticketIdWriter.writeBack(filePath, ticketIdColumn,
                                result.createdIssueIds(), excelSource, !writeBackState.backupMade);
                        writeBackState.backupMade |= writeBack.backup() != null;
                        for (String note : writeBack.notes()) {
                            logger.info(note);
                        }
                        if (writeBack.backup() != null) {
                            logger.info("Wrote " + (result.createdIssueIds().size() - writeBack.failures().size())
                                    + " ticket IDs back to " + filePath + " (backup: " + writeBack.backup() + ")");
                        }
                        if (!writeBack.failures().isEmpty()) {
                            writeBackState.failed = true;
                            logger.error("チケットIDを書き戻せなかった行があります。次回実行で重複作成しないよう、"
                                    + "以下のチケット番号を手で「" + ticketIdColumn + "」列（または数式の参照先）に入力してください:");
                            for (String failure : writeBack.failures()) {
                                logger.error("  - " + failure);
                            }
                        }
                    } catch (IOException | RuntimeException e) {
                        writeBackState.failed = true;
                        logger.error("チケットIDの書き戻しに失敗しました: " + e.getMessage());
                        logger.error("次回実行で重複作成しないよう、以下のIDを手動で「" + ticketIdColumn + "」列に入力してください:");
                        String sheetLabel = parsed.sheetName() != null ? "シート「" + parsed.sheetName() + "」" : "";
                        for (Map.Entry<Integer, Long> entry : result.createdIssueIds().entrySet()) {
                            logger.error("  " + sheetLabel + "行" + entry.getKey() + " -> " + entry.getValue());
                        }
                    }
                }
            }

            // 12. 結果出力
            logger.info("=== Sync Complete ===");
            for (String line : summary) {
                logger.info(line);
            }
            int errorCount = resultErrors.size();
            if (result != null) {
                logger.info("Total: " + result.totalCount());
                logger.info("Success: " + result.successCount());
                logger.info("Errors: " + result.errorCount());
                errorCount += result.errorCount();
                resultErrors.addAll(result.errors());
            }
            if (!resultErrors.isEmpty()) {
                logger.warn("Error details:");
                for (String error : resultErrors) {
                    logger.warn("  - " + error);
                }
            }
            return errorCount > 0 || writeBackState.failed ? 1 : 0;

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

    /** 1回の実行での書き戻しの状態（バックアップは最初の書き込みの前に1回だけ作る） */
    private static final class WriteBackState {
        private boolean backupMade;
        private boolean failed;
    }

    /**
     * ユーザー・グループのIDを書き戻します。
     */
    private void writeBackIds(String filePath, String idColumn, Map<Integer, Long> ids, ExcelSource source,
            boolean dryRun, WriteBackState state, FileLogger logger) {
        if (dryRun || ids.isEmpty()) {
            return;
        }
        try {
            TicketIdWriter.WriteBackResult writeBack = ticketIdWriter.writeBack(filePath, idColumn, ids, source,
                    !state.backupMade);
            state.backupMade |= writeBack.backup() != null;
            writeBack.notes().forEach(logger::info);
            logger.info("Wrote " + (ids.size() - writeBack.failures().size()) + " IDs back to " + source.describe()
                    + (writeBack.backup() != null ? " (backup: " + writeBack.backup() + ")" : ""));
            if (!writeBack.failures().isEmpty()) {
                state.failed = true;
                logger.error(source.describe() + "の「" + idColumn + "」列に書き戻せなかった行があります。手で入力してください:");
                writeBack.failures().forEach(failure -> logger.error("  - " + failure));
            }
        } catch (IOException | RuntimeException e) {
            state.failed = true;
            logger.error(source.describe() + "へのIDの書き戻しに失敗しました: " + e.getMessage());
            logger.error("次回実行で名前から見つけ直すので重複作成にはなりませんが、以下のIDを「" + idColumn + "」列に入力してください:");
            ids.forEach((row, id) -> logger.error("  行" + row + " -> " + id));
        }
    }

    private static String describe(String kind, DirectorySync.Result result) {
        return kind + ": created=" + result.created() + " updated=" + result.updated() + " unchanged="
                + result.unchanged() + " errors=" + result.errors().size();
    }

    /**
     * ユーザー・グループの読み込み元を決めます。
     * <p>
     * 設定（sync.users / sync.groups）があればそれを使い、なければ既定のシート名のシートがある場合だけ使います。
     * </p>
     *
     * @return 読み込み元（同期しない場合は null）
     */
    static ExcelSource directorySource(ExcelConfig config, String defaultSheet, List<String> sheetNames) {
        if (config != null) {
            ExcelSource source = new ExcelSource(config.getSheet(), config.getTable());
            if (!source.isDefault()) {
                return source;
            }
        }
        return sheetNames.contains(defaultSheet) ? new ExcelSource(defaultSheet, null) : null;
    }

    private static boolean isDirectorySheet(String sheetName, ExcelSource usersSource, ExcelSource groupsSource) {
        for (ExcelSource source : new ExcelSource[] { usersSource, groupsSource }) {
            if (source != null && source.table() == null && sheetName.equals(source.sheet())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTable(String filePath, ExcelSource source) {
        try {
            spreadsheetParser.parseFromPath(filePath, source);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    boolean loadConfig(String configPath, FileLogger logger) {
        if (configPath != null && !configPath.isBlank()) {
            logger.info("Loading config from: " + configPath);
            syncConfigService.loadConfig(configPath);
        } else if (syncConfigService.getAllProjects().isEmpty()) {
            List<Path> candidates = defaultConfigCandidates();
            Optional<Path> found = candidates.stream().filter(Files::isRegularFile).findFirst();
            if (found.isEmpty()) {
                logger.error("設定ファイルが見つかりません。--config=<パス> で指定するか、次のいずれかに "
                        + DEFAULT_CONFIG_FILE + " を置いてください: " + candidates);
                return false;
            }
            logger.info("Loading config from: " + found.get());
            syncConfigService.loadConfig(found.get().toString());
        }
        return true;
    }

    /**
     * プロジェクト設定を解決します。
     *
     * @param projectName プロジェクト名
     * @param logger ロガー
     * @return プロジェクト設定
     */
    ProjectConfig resolveProject(String projectName, FileLogger logger) {
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
     * Excel の読み込み元を決定します（CLI ＞ 設定ファイル ＞ 先頭シート）。
     */
    static ExcelSource resolveExcelSource(ProjectConfig projectConfig, ExcelSource cli) {
        ExcelSource config = ExcelSource.DEFAULT;
        if (projectConfig != null && projectConfig.getSync() != null && projectConfig.getSync().getExcel() != null) {
            config = new ExcelSource(projectConfig.getSync().getExcel().getSheet(),
                    projectConfig.getSync().getExcel().getTable());
        }
        return ExcelSource.merge(config, cli);
    }
}
