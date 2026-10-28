package mozaki.redmineUpster.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import mozaki.redmineUpster.config.SyncConfigProperties.ColumnsConfig;
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
import mozaki.redmineUpster.util.StringUtils;

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
     * @param filePath CSV/Excelファイルパス（null・空欄なら設定 sync.files のファイル）
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
        List<String> files = filePath == null || filePath.isBlank() ? List.of() : List.of(filePath);
        return runFiles(configPath, projectName, files, dryRun, logDir, debug, forceUpdate, cliExcelSource,
                cliVirtualParents, targets);
    }

    /**
     * 複数のファイルを指定した順に1つずつ同期します（並列・結合はしません）。
     * <p>
     * 先にすべてのファイルを解析・検証し、1つでも検証エラーがあれば Redmine に一切書き込みません。
     * その後ファイルごとに同期（ユーザー・グループ・チケット、IDの書き戻しと .bak）を行い、
     * 最後に1回だけ論理削除を行います。論理削除の対象は、今回同期したファイル・設定 sync.files のファイル・
     * 今回作成したチケット（仮想親を含む）のどれにもないプロジェクト内のチケットだけです。
     * </p>
     *
     * @param configPath 設定ファイルパス（nullの場合は既定の場所の sync-config.yml）
     * @param projectName プロジェクト名（nullの場合はデフォルトプロジェクトを使用）
     * @param filePaths CSV/Excelファイルパス（空なら設定 sync.files のファイル）
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
    public int runFiles(String configPath, String projectName, List<String> filePaths, boolean dryRun, String logDir,
            boolean debug, boolean forceUpdate, ExcelSource cliExcelSource, Boolean cliVirtualParents,
            Set<SyncTarget> targets) {
        return runFiles(configPath, projectName, filePaths, dryRun, logDir, debug, forceUpdate, cliExcelSource,
                cliVirtualParents, targets, null);
    }

    /**
     * 複数のファイルを指定した順に1つずつ同期します（ルートチケットを CLI で指定）。
     * <p>
     * ルートチケットがあるファイルの最上位の行（親行がない行・最上位の仮想親）は、ルートチケットの子として同期します。
     * ルートチケットは、--root-issue（今回のすべてのファイル）＞ 設定 sync.files の rootIssueId の順に決めます。
     * 今回同期するすべてのファイルにルートチケットがある場合、論理削除の範囲はそれらのルートチケットの配下に限ります。
     * </p>
     *
     * @param configPath 設定ファイルパス（nullの場合は既定の場所の sync-config.yml）
     * @param projectName プロジェクト名（nullの場合はデフォルトプロジェクトを使用）
     * @param filePaths CSV/Excelファイルパス（空なら設定 sync.files のファイル）
     * @param dryRun ドライランモードの場合はtrue
     * @param logDir ログ出力ディレクトリ（nullの場合はカレントディレクトリの logs）
     * @param debug デバッグモードの場合はtrue
     * @param forceUpdate 変更なしスキップを無効化する場合はtrue
     * @param cliExcelSource CLI の --sheet / --table（null可。設定ファイルの sync.excel より優先）
     * @param cliVirtualParents CLI の --virtual-parents（true）/ --no-virtual-parents（false）。
     *        null なら設定 sync.virtualParents.enabled に従う
     * @param targets 対象（null ならファイルにある表すべて）
     * @param cliRootIssueId CLI の --root-issue（null なら設定 sync.files の rootIssueId に従う）
     * @return 成功の場合は0、失敗の場合は1
     */
    public int runFiles(String configPath, String projectName, List<String> filePaths, boolean dryRun, String logDir,
            boolean debug, boolean forceUpdate, ExcelSource cliExcelSource, Boolean cliVirtualParents,
            Set<SyncTarget> targets, Long cliRootIssueId) {
        return runFiles(configPath, projectName, filePaths, dryRun, logDir, debug, forceUpdate, cliExcelSource,
                cliVirtualParents, targets, cliRootIssueId, false);
    }

    /**
     * 複数のファイルを指定した順に1つずつ同期します（同期結果の書き戻しを CLI で無効化）。
     * <p>
     * dry-run でなければ、チケットの表の各行に「最終同期日時」「同期結果」「エラー理由」を書き戻します
     * （新規作成したチケットIDと同じ1回の書き込み）。入力エラーで中止した場合も、エラーの行には「エラー」と理由、
     * ほかの行には「未実行」を書きます。
     * </p>
     *
     * @param configPath 設定ファイルパス（nullの場合は既定の場所の sync-config.yml）
     * @param projectName プロジェクト名（nullの場合はデフォルトプロジェクトを使用）
     * @param filePaths CSV/Excelファイルパス（空なら設定 sync.files のファイル）
     * @param dryRun ドライランモードの場合はtrue
     * @param logDir ログ出力ディレクトリ（nullの場合はカレントディレクトリの logs）
     * @param debug デバッグモードの場合はtrue
     * @param forceUpdate 変更なしスキップを無効化する場合はtrue
     * @param cliExcelSource CLI の --sheet / --table（null可。設定ファイルの sync.excel より優先）
     * @param cliVirtualParents CLI の --virtual-parents（true）/ --no-virtual-parents（false）。
     *        null なら設定 sync.virtualParents.enabled に従う
     * @param targets 対象（null ならファイルにある表すべて）
     * @param cliRootIssueId CLI の --root-issue（null なら設定 sync.files の rootIssueId に従う）
     * @param noResultColumns CLI の --no-result-columns（true なら同期結果を書き戻さない）
     * @return 成功の場合は0、失敗の場合は1
     */
    public int runFiles(String configPath, String projectName, List<String> filePaths, boolean dryRun, String logDir,
            boolean debug, boolean forceUpdate, ExcelSource cliExcelSource, Boolean cliVirtualParents,
            Set<SyncTarget> targets, Long cliRootIssueId, boolean noResultColumns) {
        FileLogger logger = null;
        Set<SyncTarget> selected = targets == null ? EnumSet.allOf(SyncTarget.class) : targets;
        List<String> givenFiles = filePaths == null ? List.of() : filePaths;
        List<FileJob> jobs = new ArrayList<>();
        RunState state = new RunState();
        state.syncedAt = LocalDateTime.now().format(SYNCED_AT_FORMAT);
        state.dryRun = dryRun;
        try {
            // 1. ロガーの初期化
            logger = new FileLogger(logDir == null || logDir.isBlank() ? DEFAULT_LOG_DIR : logDir);
            logger.setDebugEnabled(debug);
            logger.info("=== Redmine Sync Started ===");
            logger.info("File: " + (givenFiles.isEmpty() ? "(設定 sync.files)" : String.join(", ", givenFiles)));
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
            SyncConfig syncConfig = projectConfig.getSync();
            state.resultColumns = resultColumns(projectConfig, noResultColumns, logger);
            state.projectConfig = projectConfig;

            // 4. 同期するファイルを決める（--file ＞ sync.files）
            List<String> listedFiles = listedFiles(projectConfig);
            List<String> files = resolveFiles(givenFiles, listedFiles, logger);
            if (files.isEmpty()) {
                logger.error("同期するファイルがありません。--file=<CSV/Excel> を指定するか、設定ファイルの sync.files に"
                        + "ファイルを書いてください");
                return 1;
            }
            boolean multi = files.size() > 1;
            if (multi) {
                logger.info("Files: " + files.size() + "件（この順に1つずつ同期します）");
                for (int i = 0; i < files.size(); i++) {
                    logger.info("  " + (i + 1) + ". " + files.get(i));
                }
            }

            // 5. すべてのファイルを解析（Redmineには接続しない）
            for (String file : files) {
                FileJob job = parseFile(file, projectConfig, cliExcelSource, targets, selected, logger);
                if (job == null) {
                    if (multi) {
                        logger.error("ファイル「" + file + "」を読み込めないため中止します（Redmineは更新していません）");
                        writeNotRunResults(jobs, projectConfig, "ファイル「" + Paths.get(file).getFileName()
                                + "」を読み込めないため同期を中止しました（ログを確認してください）", state, logger);
                    }
                    return 1;
                }
                job.rootIssueId = cliRootIssueId != null ? cliRootIssueId
                        : configuredRootIssueIds(projectConfig).get(normalize(file));
                if (job.rootIssueId != null) {
                    logger.info("Root Issue: #" + job.rootIssueId + (multi ? "（" + job.fileName() + "）" : "")
                            + (cliRootIssueId != null ? "（コマンドライン指定）" : "（設定 sync.files の rootIssueId）"));
                }
                jobs.add(job);
            }

            // 6. Redmineクライアント作成（トラッカー名の解決・存在確認に使用）
            RedmineClient client = redmineClientFactory.createClient(projectConfig);
            client.setLogger(logger);
            logger.info("Redmine URL: " + client.getBaseUrl());
            logger.info("Redmine Project: " + client.getProjectId());

            // 7. すべてのファイルを検証（エラーがあればRedmineに一切書き込まない）
            List<String> validationErrors = new ArrayList<>();
            for (FileJob job : jobs) {
                if (multi) {
                    logger.info("--- 検証: " + job.filePath + " ---");
                }
                for (String error : validateFile(job, projectConfig, client, cliVirtualParents, logger)) {
                    validationErrors.add(multi ? "[" + job.fileName() + "] " + error : error);
                }
            }
            validationErrors.addAll(validateRootIssues(jobs, client, deleteStatusId(syncConfig), state, logger));
            if (!validationErrors.isEmpty()) {
                logger.error("入力ファイルの検証エラー: " + validationErrors.size() + "件（Redmineは更新していません）");
                for (String error : validationErrors) {
                    logger.error("  - " + error);
                }
                // 入力ファイルにも書き戻す（エラーの行は理由、ほかの行は未実行）
                writeNotRunResults(jobs, projectConfig, ABORTED_BY_INPUT_ERROR, state, logger);
                return 1;
            }

            // 8. ファイルごとに同期（ユーザー → グループ → チケット、IDの書き戻し）
            for (int i = 0; i < jobs.size(); i++) {
                FileJob job = jobs.get(i);
                if (multi) {
                    logger.info("=== File " + (i + 1) + "/" + jobs.size() + ": " + job.filePath + " ===");
                }
                if (i > 0 && !dryRun) {
                    // 前のファイルで作成したユーザー・チケット（親など）を反映するため、Redmineの現在の状態で検証し直す
                    List<String> errors = validateFile(job, projectConfig, client, cliVirtualParents, logger);
                    if (!errors.isEmpty()) {
                        state.skippedFile = true;
                        logger.error("ファイル「" + job.filePath + "」は再検証でエラーになったため同期しません:");
                        for (String error : errors) {
                            logger.error("  - " + error);
                            state.errors.add("[" + job.fileName() + "] " + error);
                        }
                        writeNotRunResults(List.of(job), projectConfig, ABORTED_BY_INPUT_ERROR, state, logger);
                        continue;
                    }
                }
                syncFile(job, multi, projectConfig, syncConfig, client, dryRun, forceUpdate, state, logger);
            }

            // 9. 論理削除（すべてのファイルの同期後に1回だけ）
            if (state.ticketsSynced) {
                logicalDelete(jobs, listedFiles, projectConfig, cliExcelSource, cliVirtualParents, client, dryRun,
                        state, logger);
            }

            // 10. 親チケットの自動完了（子チケットがすべて完了した親を完了にする。設定 sync.autoCloseParents）
            ParentAutoCloser.Result autoClosed = null;
            if (state.ticketsSynced && isAutoCloseEnabled(syncConfig)) {
                autoClosed = autoCloseParents(jobs, listedFiles, projectConfig, cliExcelSource, cliVirtualParents,
                        client, dryRun, state, logger);
            }
            // 自動完了のために後回しにした書き戻し（チケットID・同期結果・ステータス）をファイルごとに1回で書く
            flushPendingWriteBacks(jobs, projectConfig, autoClosed, state, logger);

            // 11. 結果出力
            logger.info("=== Sync Complete ===");
            for (String line : state.summary) {
                logger.info(line);
            }
            if (state.ticketsSynced) {
                logger.info("Total: " + state.total);
                logger.info("Success: " + state.success);
                logger.info("Errors: " + state.ticketErrors);
            }
            if (!state.errors.isEmpty()) {
                logger.warn("Error details:");
                for (String error : state.errors) {
                    logger.warn("  - " + error);
                }
            }
            return !state.errors.isEmpty() || state.writeBack.failed ? 1 : 0;

        } catch (IOException e) {
            if (logger != null) {
                logger.error("IO error: " + e.getMessage());
                writeNotRunResults(jobs, null, "エラーのため同期を中止しました: " + e.getMessage(), state, logger);
            } else {
                System.err.println("IO error: " + e.getMessage());
            }
            return 1;
        } catch (Exception e) {
            if (logger != null) {
                logger.error("Unexpected error: " + e.getMessage());
                writeNotRunResults(jobs, null, "エラーのため同期を中止しました: " + e.getMessage(), state, logger);
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

    /** 1ファイル分の解析・検証結果 */
    private static final class FileJob {
        private final String filePath;
        private boolean isCsv;
        private ExcelSource excelSource;
        private ExcelSource usersSource;
        private ExcelSource groupsSource;
        /** グループメンバーのシート（グループを同期し、シートがある場合だけ） */
        private ExcelSource groupMembersSource;
        private boolean doTickets;
        private ParsedSheet parsed;
        private ParsedSheet usersSheet;
        private ParsedSheet groupsSheet;
        private ParsedSheet groupMembersSheet;
        private Map<Long, Map<String, Object>> users;
        private Map<Long, Map<String, Object>> groups;
        private DirectorySync.Plan<DirectorySync.UserRow> userPlan;
        private DirectorySync.Plan<DirectorySync.GroupRow> groupPlan;
        private DiffPlan plan;
        /** ルートチケットのID（null なら最上位の行は親なし） */
        private Long rootIssueId;
        /** 行に結び付く検証エラー（行番号 → 理由） */
        private Map<Integer, List<String>> rowErrors = Map.of();
        /** 同期結果を書き戻した（または書き戻しを試みた） */
        private boolean resultsWritten;
        /** 書き戻しの状態（ユーザー・グループ・チケットで .bak は1回） */
        private final WriteBackState writeBack = new WriteBackState();
        /** 同期したチケットID → ファイル上の行番号（仮想親を除く。ステータスの書き戻しに使用） */
        private final Map<Long, Integer> rowByIssueId = new HashMap<>();
        /** 親チケットの自動完了の後に書き戻す、新規作成したチケットID（後回しにしていない場合は null） */
        private Map<Integer, Long> pendingCreatedIssueIds;
        /** 親チケットの自動完了の後に書き戻す、行ごとの結果（後回しにしていない場合は null） */
        private Map<Integer, RowOutcome> pendingOutcomes;

        private FileJob(String filePath) {
            this.filePath = filePath;
        }

        private String fileName() {
            Path name = Paths.get(filePath).getFileName();
            return name == null ? filePath : name.toString();
        }
    }

    /** 1回の実行全体の状態 */
    private static final class RunState {
        private final WriteBackState writeBack = new WriteBackState();
        private final List<String> summary = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        /** 論理削除しないチケット（同期したファイルの行・対応付けた仮想親・今回作成したチケット） */
        private final Set<Long> keepIssueIds = new HashSet<>();
        /** 最後に取得したプロジェクトのチケット（dry-run の論理削除候補に使用） */
        private Map<Long, Map<String, Object>> lastIssues = Map.of();
        /** 検証で取得したルートチケット（同期先プロジェクト外のものを含む。チケットID → チケット情報） */
        private final Map<Long, Map<String, Object>> rootIssues = new HashMap<>();
        private boolean ticketsSynced;
        /** 再検証エラーで同期しなかったファイルがある（安全のため論理削除しない） */
        private boolean skippedFile;
        private int total;
        private int success;
        private int ticketErrors;
        /** 同期結果の列（書き戻さない場合は null） */
        private ResultColumnNames resultColumns;
        /** プロジェクト設定（中止時の書き戻しに使用） */
        private ProjectConfig projectConfig;
        /** 最終同期日時（この実行のすべての行で同じ値） */
        private String syncedAt;
        private boolean dryRun;
        /** 同期したチケットの同期後の状態（親チケットの自動完了に使用） */
        private final List<SyncResult.PlannedIssue> planned = new ArrayList<>();
        /** 論理削除したチケット（dry-run では論理削除する予定のチケット）と、そのステータス */
        private List<Long> deletedIssueIds = List.of();
        private Integer deletedStatusId;
        /** 終了ステータスのID（親チケットの自動完了が有効な場合だけ取得。取得できなければ null） */
        private Set<Long> closedStatusIds;
        /** 論理削除のために読み込んだ（同期しない）sync.files のファイルのチケットID（親チケットの自動完了でも使用） */
        private final Map<Path, Set<Long>> listedIssueIds = new HashMap<>();
    }

    /**
     * 同期するファイルを決めます（--file があればその順、なければ sync.files）。同じファイルは1回だけ同期します。
     */
    static List<String> resolveFiles(List<String> givenFiles, List<String> listedFiles, FileLogger logger) {
        List<String> source = givenFiles.isEmpty() ? listedFiles : givenFiles;
        Set<Path> listed = new HashSet<>();
        listedFiles.forEach(file -> listed.add(normalize(file)));
        Set<Path> seen = new HashSet<>();
        List<String> files = new ArrayList<>();
        for (String file : source) {
            if (file == null || file.isBlank()) {
                continue;
            }
            if (!seen.add(normalize(file))) {
                if (logger != null) {
                    logger.warn("同じファイルが複数回指定されています。1回だけ同期します: " + file);
                }
                continue;
            }
            if (!givenFiles.isEmpty() && !listed.isEmpty() && !listed.contains(normalize(file)) && logger != null) {
                logger.warn("ファイル「" + file + "」は設定 sync.files にありません。ほかのファイルを同期したときに"
                        + "このファイルのチケットが論理削除されないよう、sync.files に追加してください");
            }
            files.add(file);
        }
        return files;
    }

    /**
     * 設定 sync.files のファイルごとのルートチケットを返します（正規化した絶対パス → チケットID）。
     */
    static Map<Path, Long> configuredRootIssueIds(ProjectConfig projectConfig) {
        SyncConfig syncConfig = projectConfig.getSync();
        Map<Path, Long> roots = new HashMap<>();
        if (syncConfig != null && syncConfig.getFileRootIssueIds() != null) {
            syncConfig.getFileRootIssueIds().forEach((file, id) -> roots.put(normalize(file), id));
        }
        return roots;
    }

    /**
     * 設定 sync.files でファイルに指定したルートチケットを返します（パスは同期と同じく絶対パスに正規化して比べる）。
     *
     * @param projectConfig プロジェクト設定
     * @param file ファイルパス
     * @return ルートチケットのID（指定なしはnull）
     */
    static Long configuredRootIssueId(ProjectConfig projectConfig, String file) {
        return configuredRootIssueIds(projectConfig).get(normalize(file));
    }

    /**
     * ルートチケットを検証します（Redmineには読み取りのみ）。
     * <p>
     * ルートチケットは同期先プロジェクトのチケットから探し、なければ個別に取得します
     * （親プロジェクトなど別プロジェクトのチケットも指定できます）。
     * 存在しない・論理削除ステータスのルートチケットはエラーです。
     * </p>
     *
     * @return 検証エラー（なければ空）
     */
    private List<String> validateRootIssues(List<FileJob> jobs, RedmineClient client, Integer deleteStatusId,
            RunState state, FileLogger logger) {
        Set<Long> roots = new LinkedHashSet<>();
        for (FileJob job : jobs) {
            if (job.doTickets && job.rootIssueId != null) {
                roots.add(job.rootIssueId);
            }
        }
        List<String> errors = new ArrayList<>();
        if (roots.isEmpty()) {
            return errors;
        }
        logger.info("Fetching issues of project " + client.getProjectId() + " from Redmine (root issue check)...");
        Map<Long, Map<String, Object>> projectIssues = client.listProjectIssues();
        for (Long root : roots) {
            Map<String, Object> issue = projectIssues.get(root);
            if (issue == null) {
                issue = client.getIssue(root);
                if (issue != null && issue.isEmpty()) {
                    issue = null;
                }
                if (issue != null) {
                    logger.info("ルートチケット #" + root + " は同期先プロジェクト外のチケットです（"
                            + projectName(issue) + "）");
                }
            }
            if (issue == null) {
                errors.add("ルートチケット #" + root + " が見つかりません");
                continue;
            }
            state.rootIssues.put(root, issue);
            Long statusId = IssueComparator.nestedId(issue, "status");
            if (deleteStatusId != null && statusId != null && statusId.longValue() == deleteStatusId.longValue()) {
                errors.add("ルートチケット #" + root + " は論理削除ステータス（status_id=" + deleteStatusId
                        + "）です");
                continue;
            }
            logger.info("ルートチケット #" + root + " 「" + issue.get("subject") + "」を確認しました");
        }
        return errors;
    }

    private static String projectName(Map<String, Object> issue) {
        Object project = issue.get("project");
        return project instanceof Map<?, ?> map && map.get("name") != null ? String.valueOf(map.get("name"))
                : "project_id=" + IssueComparator.nestedId(issue, "project");
    }

    private static List<String> listedFiles(ProjectConfig projectConfig) {
        SyncConfig syncConfig = projectConfig.getSync();
        return syncConfig != null && syncConfig.getFiles() != null ? syncConfig.getFiles() : List.of();
    }

    private static Path normalize(String file) {
        return Paths.get(file).toAbsolutePath().normalize();
    }

    /**
     * 1つのファイルの表（チケット・ユーザー・グループ）を決めて解析します（Redmineには接続しません）。
     *
     * @return 解析結果（同期する表がない・テーブルにチケットID列がない場合は null。理由はログに出力済み）
     */
    private FileJob parseFile(String filePath, ProjectConfig projectConfig, ExcelSource cliExcelSource,
            Set<SyncTarget> targets, Set<SyncTarget> selected, FileLogger logger) throws IOException {
        FileJob job = new FileJob(filePath);
        // ユーザー・グループの表（Excel のみ）を決める
        boolean isCsv = filePath.toLowerCase().endsWith(".csv");
        job.isCsv = isCsv;
        List<String> sheetNames = isCsv ? List.of() : spreadsheetParser.sheetNames(filePath);
        SyncConfig syncConfig = projectConfig.getSync();
        job.usersSource = selected.contains(SyncTarget.USERS) && !isCsv
                ? directorySource(syncConfig != null ? syncConfig.getUsers() : null,
                        DirectorySync.DEFAULT_USERS_SHEET, sheetNames)
                : null;
        job.groupsSource = selected.contains(SyncTarget.GROUPS) && !isCsv
                ? directorySource(syncConfig != null ? syncConfig.getGroups() : null,
                        DirectorySync.DEFAULT_GROUPS_SHEET, sheetNames)
                : null;
        job.groupMembersSource = job.groupsSource != null
                ? directorySource(syncConfig != null ? syncConfig.getGroupMembers() : null,
                        DirectorySync.DEFAULT_GROUP_MEMBERS_SHEET, sheetNames)
                : null;
        if (isCsv && (targets != null && (selected.contains(SyncTarget.USERS)
                || selected.contains(SyncTarget.GROUPS)))) {
            logger.warn("CSVファイルのため、ユーザー・グループは同期しません（Excel のシートで指定してください）");
        }

        // チケットの表を解析（階層列の決定・fillDownHierarchy の補完は差分計算で行う）
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
        job.excelSource = excelSource;
        boolean doTickets = selected.contains(SyncTarget.TICKETS);
        if (doTickets && !isCsv && excelSource.isDefault() && !sheetNames.isEmpty()
                && isDirectorySheet(sheetNames.get(0), job.usersSource, job.groupsSource, job.groupMembersSource)) {
            logger.info("先頭シート「" + sheetNames.get(0) + "」はユーザー・グループの表のため、チケットは同期しません");
            doTickets = false;
        }
        if (doTickets && !isCsv && targets == null && (job.usersSource != null || job.groupsSource != null)
                && excelSource.table() != null && !hasTable(filePath, excelSource)) {
            // ユーザー・グループだけの Excel（チケットの表なし）を、既定の table: 取込表 のままで使う場合
            logger.info("テーブル「" + excelSource.table() + "」がないため、チケットは同期しません");
            doTickets = false;
        }
        if (!doTickets && job.usersSource == null && job.groupsSource == null) {
            logger.error("同期する表がありません（対象: " + selected + "。ユーザー・グループはシート「"
                    + DirectorySync.DEFAULT_USERS_SHEET + "」「" + DirectorySync.DEFAULT_GROUPS_SHEET + "」）");
            return null;
        }
        job.doTickets = doTickets;

        String ticketIdColumn = DiffCalculator.getTicketIdColumn(projectConfig);
        if (doTickets) {
            logger.info("Parsing file: " + filePath + (isCsv ? "" : "（" + excelSource.describe() + "）"));
            ParsedSheet parsed = withoutResultColumns(spreadsheetParser.parseFromPath(filePath, excelSource),
                    resultColumnNames(projectConfig));
            if (parsed.sheetName() != null) {
                logger.info("Sheet: " + parsed.sheetName() + "（メッセージの行番号はこのシートの行番号です）");
            }
            logger.info("Parsed " + parsed.rows().size() + " rows");
            if (excelSource.table() != null && !parsed.headers().contains(ticketIdColumn)) {
                // テーブルへの列の追加は行わない。Redmine に書き込む前に止める
                logger.error("テーブル「" + excelSource.table() + "」にチケットID列「" + ticketIdColumn
                        + "」がありません。テーブルに列を追加してから実行してください（Redmineは更新していません）");
                return null;
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
            job.parsed = parsed;
        }
        if (job.usersSource != null) {
            job.usersSheet = spreadsheetParser.parseFromPath(filePath, job.usersSource);
            logger.info("Users: " + job.usersSource.describe() + " " + job.usersSheet.rows().size() + " rows");
        }
        if (job.groupsSource != null) {
            job.groupsSheet = spreadsheetParser.parseFromPath(filePath, job.groupsSource);
            logger.info("Groups: " + job.groupsSource.describe() + " " + job.groupsSheet.rows().size() + " rows");
        }
        if (job.groupMembersSource != null) {
            job.groupMembersSheet = spreadsheetParser.parseFromPath(filePath, job.groupMembersSource);
            logger.info("Group Members: " + job.groupMembersSource.describe() + " "
                    + job.groupMembersSheet.rows().size() + " rows（ここに書いたグループはこの行がメンバーのすべてになります）");
        }
        return job;
    }

    /**
     * 1つのファイルを検証し、ユーザー・グループ・チケットの計画を作ります（Redmineには読み取りのみ）。
     *
     * @return 検証エラー（なければ空）
     */
    /**
     * ユーザーのシートの「通知するプロジェクト」に識別子・名前があれば、プロジェクトを取得して ID に解決する関数を返します。
     * 数値（ID）だけなら取得しません（null）。
     */
    private static java.util.function.Function<String, Long> projectResolver(
            mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet sheet, RedmineClient client, FileLogger logger) {
        boolean needed = false;
        for (Map<String, String> row : sheet.rows()) {
            String raw = row.get(UserSettingColumns.COL_NOTIFIED_PROJECTS);
            if (raw != null) {
                for (String token : UserSettingColumns.splitList(raw)) {
                    if (!token.matches("#?\\d+") && !"なし".equals(token) && !"none".equalsIgnoreCase(token)) {
                        needed = true;
                    }
                }
            }
        }
        if (!needed) {
            return null;
        }
        Map<String, Long> index = new HashMap<>();
        Map<Long, Map<String, Object>> projects = client.listProjects();
        logger.info("Fetched " + projects.size() + " projects（通知するプロジェクトの解決）");
        projects.forEach((id, project) -> {
            for (String key : List.of("identifier", "name")) {
                Object value = project.get(key);
                if (value != null) {
                    index.putIfAbsent(String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT), id);
                }
            }
            index.putIfAbsent(String.valueOf(id), id);
        });
        return token -> index.get(token.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private List<String> validateFile(FileJob job, ProjectConfig projectConfig, RedmineClient client,
            Boolean cliVirtualParents, FileLogger logger) {
        List<String> validationErrors = new ArrayList<>();
        job.users = null;
        job.groups = null;
        job.userPlan = null;
        job.groupPlan = null;
        job.plan = null;
        job.rowErrors = Map.of();
        if (job.usersSheet != null || job.groupsSheet != null) {
            logger.info("Fetching users" + (job.groupsSheet != null ? " and groups" : "")
                    + " from Redmine（管理者の API キーが必要です）...");
            job.users = client.listUsers();
            logger.info("Fetched " + job.users.size() + " users");
        }
        if (job.usersSheet != null) {
            job.userPlan = DirectorySync.parseUsers(job.usersSheet, job.users,
                    projectResolver(job.usersSheet, client, logger));
            validationErrors.addAll(job.userPlan.errors());
        }
        if (job.groupsSheet != null) {
            job.groups = client.listGroups();
            logger.info("Fetched " + job.groups.size() + " groups");
            Set<String> knownLogins = new HashSet<>(DirectorySync.loginIndex(job.users).keySet());
            if (job.userPlan != null) {
                job.userPlan.rows().forEach(row -> knownLogins.add(row.login()));
            }
            job.groupPlan = DirectorySync.parseGroups(job.groupsSheet, job.groupMembersSheet, job.groups,
                    knownLogins);
            validationErrors.addAll(job.groupPlan.errors());
        }
        if (job.doTickets) {
            logger.info("Calculating diff...");
            job.plan = calculatePlan(job.parsed, projectConfig, client, cliVirtualParents, logger);
            validationErrors.addAll(job.plan.errors());
            Map<Integer, List<String>> rowErrors = new HashMap<>();
            job.plan.rowErrors().forEach((row, reasons) -> rowErrors.put(row, new ArrayList<>(reasons)));
            if (job.rootIssueId != null) {
                for (DiffItem item : job.plan.items()) {
                    if (!item.virtual() && job.rootIssueId.equals(item.issueId())) {
                        String reason = "チケットID #" + job.rootIssueId
                                + " はこのファイルのルートチケットです（ルートチケットは行に書かず、"
                                + "sync.files の rootIssueId または --root-issue で指定してください）";
                        validationErrors.add("行" + item.rowNumber() + ": " + reason);
                        rowErrors.computeIfAbsent(item.rowNumber(), k -> new ArrayList<>()).add(reason);
                    }
                }
            }
            job.rowErrors = rowErrors;
        }
        return validationErrors;
    }

    private DiffPlan calculatePlan(ParsedSheet parsed, ProjectConfig projectConfig, RedmineClient client,
            Boolean cliVirtualParents, FileLogger logger) {
        SyncConfig syncConfig = projectConfig.getSync();
        Map<String, String> trackerMap = syncConfig != null ? syncConfig.getTrackerMap() : Map.of();
        TrackerResolver trackerResolver = new TrackerResolver(trackerMap, client);
        boolean virtualParents = cliVirtualParents != null ? cliVirtualParents
                : DiffCalculator.isVirtualParentsEnabled(projectConfig);
        logger.info("Virtual Parents: " + virtualParents
                + (cliVirtualParents != null ? "（コマンドライン指定）" : "（設定 sync.virtualParents.enabled）"));
        return diffCalculator.calculate(parsed, projectConfig, trackerResolver, logger, virtualParents);
    }

    private static Integer deleteStatusId(SyncConfig syncConfig) {
        return syncConfig != null && syncConfig.getDeletion() != null ? syncConfig.getDeletion().getStatusId() : null;
    }

    /**
     * 1つのファイルを同期します（ユーザー → グループの Upsert、チケットの作成・更新、IDの書き戻し）。
     * 論理削除はここでは行いません（すべてのファイルの同期後に1回だけ行う）。
     */
    private void syncFile(FileJob job, boolean multi, ProjectConfig projectConfig, SyncConfig syncConfig,
            RedmineClient client, boolean dryRun, boolean forceUpdate, RunState state, FileLogger logger)
            throws IOException {
        String filePath = job.filePath;
        String prefix = multi ? "[" + job.fileName() + "] " : "";
        WriteBackState writeBackState = job.writeBack;
        // ユーザー → グループの Upsert と ID の書き戻し
        if (job.userPlan != null) {
            logger.info("Syncing users...");
            DirectorySync.Result userResult = directorySync.syncUsers(job.userPlan.rows(), job.users, client, dryRun,
                    logger);
            state.summary.add(prefix + describe("Users", userResult));
            userResult.errors().forEach(error -> state.errors.add(prefix + error));
            writeBackIds(filePath, DirectorySync.COL_ID, userResult.writeBackIds(), job.usersSource, dryRun,
                    writeBackState, logger);
        }
        if (job.groupPlan != null) {
            logger.info("Syncing groups...");
            DirectorySync.Result groupResult = directorySync.syncGroups(job.groupPlan.rows(), job.groups, job.users,
                    client, dryRun, logger);
            state.summary.add(prefix + describe("Groups", groupResult));
            groupResult.errors().forEach(error -> state.errors.add(prefix + error));
            writeBackIds(filePath, DirectorySync.COL_ID, groupResult.writeBackIds(), job.groupsSource, dryRun,
                    writeBackState, logger);
        }

        if (job.doTickets) {
            List<DiffItem> items = job.plan.items();
            // 担当（ログインID・グループ名）とステータス名をIDに変換
            Set<String> plannedLogins = new HashSet<>();
            if (job.userPlan != null) {
                job.userPlan.rows().stream().filter(row -> row.id() == null)
                        .forEach(row -> plannedLogins.add(row.login()));
            }
            items = new TicketValueResolver(client, logger, job.users, job.groups, plannedLogins)
                    .resolve(items, syncConfig != null ? syncConfig.getStatus() : null);
            long createCount = items.stream().filter(i -> SyncConstants.ACTION_CREATE.equals(i.action())).count();
            long updateCount = items.stream().filter(i -> SyncConstants.ACTION_UPDATE.equals(i.action())).count();
            logger.info("Diff items: " + items.size());
            logger.info("  CREATE: " + createCount);
            logger.info("  UPDATE: " + updateCount);

            // 同期先プロジェクトのチケットを全件取得（DBの代わりにRedmineの現在の状態を正とする。
            // 複数ファイルの場合はファイルごとに取得し直し、前のファイルで作成したチケットも見えるようにする）
            logger.info("Fetching issues of project " + client.getProjectId() + " from Redmine...");
            Map<Long, Map<String, Object>> projectIssues = client.listProjectIssues();
            logger.info("Fetched " + projectIssues.size() + " issues (closed included, subprojects excluded)");
            state.lastIssues = projectIssues;

            if (job.rootIssueId != null) {
                Map<String, Object> root = projectIssues.containsKey(job.rootIssueId)
                        ? projectIssues.get(job.rootIssueId) : state.rootIssues.get(job.rootIssueId);
                logger.info((dryRun ? "DRY_RUN " : "") + "ルートチケット #" + job.rootIssueId
                        + (root != null && root.get("subject") != null ? " 「" + root.get("subject") + "」" : "")
                        + " の配下に同期します（最上位の行の親をこのチケットにします）");
            }

            // 仮想親（ファイルに行がない祖先）を既存チケットに対応付ける（見つからなければ新規作成）
            Integer deleteStatusId = deleteStatusId(syncConfig);
            if (items.stream().anyMatch(DiffItem::virtual)) {
                items = VirtualParentMatcher.match(items, projectIssues, deleteStatusId, logger, job.rootIssueId);
                long virtualTotal = items.stream().filter(DiffItem::virtual).count();
                long virtualExisting = items.stream().filter(i -> i.virtual() && i.issueId() != null).count();
                logger.info("  VIRTUAL_PARENT: " + virtualTotal + "（既存 " + virtualExisting + " / 新規作成 "
                        + (virtualTotal - virtualExisting) + "。上の CREATE 件数に含まれます）");
            }

            // 親チケットの自動完了が有効な場合、親の行はステータス列が空欄なら Redmine の現在のステータスのままにする
            // （日付から決めたステータスで毎回戻して、また自動で完了・進行中にする、を繰り返さないため）
            if (isAutoCloseEnabled(syncConfig)) {
                items = keepParentStatuses(items, projectIssues, logger);
            }

            // Excelの行と、今回も必要な仮想親のチケットは論理削除しない
            for (DiffItem item : items) {
                if (item.issueId() != null) {
                    state.keepIssueIds.add(item.issueId());
                }
            }

            // 同期実行（論理削除は全ファイルの同期後）
            logger.info("Executing sync...");
            SyncResult result = syncExecutor.execute(items, projectIssues, List.of(), projectConfig, client,
                    dryRun, logger, forceUpdate, job.rootIssueId);
            state.ticketsSynced = true;
            if (result.plannedIssues() != null) {
                state.planned.addAll(result.plannedIssues());
                for (SyncResult.PlannedIssue planned : result.plannedIssues()) {
                    if (!planned.virtual() && planned.rowNumber() > 0) {
                        job.rowByIssueId.put(planned.issueId(), planned.rowNumber());
                    }
                }
            }
            state.keepIssueIds.addAll(result.createdIssueIds().values());
            if (result.createdVirtualIssueIds() != null) {
                state.keepIssueIds.addAll(result.createdVirtualIssueIds());
            }
            state.total += result.totalCount();
            state.success += result.successCount();
            state.ticketErrors += result.errorCount();
            result.errors().forEach(error -> state.errors.add(prefix + error));
            if (multi) {
                state.summary.add(prefix + "Tickets: total=" + result.totalCount() + " success="
                        + result.successCount() + " errors=" + result.errorCount());
            }

            // 新規作成したチケットIDと同期結果を入力ファイルへ書き戻す（1回の書き込み）
            if (!dryRun && isAutoCloseEnabled(syncConfig)) {
                // 親チケットの自動完了の後に、変更したステータスと合わせて1回で書き戻す
                job.pendingCreatedIssueIds = new HashMap<>(result.createdIssueIds());
                job.pendingOutcomes = new HashMap<>(result.rowOutcomes() != null ? result.rowOutcomes() : Map.of());
            } else if (!dryRun) {
                writeBackTicketIds(job, projectConfig, result.createdIssueIds(),
                        syncResults(job, result.rowOutcomes(), state, null), null, writeBackState, logger);
            } else if (state.resultColumns != null) {
                logger.info("dry-run のため、チケットID・同期結果は入力ファイルに書き込みません");
            }
        }
        state.writeBack.failed |= writeBackState.failed;
    }

    private void writeBackTicketIds(FileJob job, ProjectConfig projectConfig, Map<Integer, Long> createdIssueIds,
            TicketIdWriter.SyncResults syncResults, TicketIdWriter.ColumnValues statusValues,
            WriteBackState writeBackState, FileLogger logger) {
        String filePath = job.filePath;
        String ticketIdColumn = DiffCalculator.getTicketIdColumn(projectConfig);
        job.resultsWritten = true;
        if (createdIssueIds.isEmpty() && syncResults == null && statusValues == null) {
            return;
        }
        try {
            TicketIdWriter.WriteBackResult writeBack = statusValues == null
                    ? ticketIdWriter.writeBack(filePath, ticketIdColumn, createdIssueIds, job.excelSource,
                            !writeBackState.backupMade, syncResults)
                    : ticketIdWriter.writeBack(filePath, ticketIdColumn, createdIssueIds, job.excelSource,
                            !writeBackState.backupMade, syncResults, statusValues);
            writeBackState.backupMade |= writeBack.backup() != null;
            for (String note : writeBack.notes()) {
                logger.info(note);
            }
            for (String warning : writeBack.warnings()) {
                logger.warn(warning);
            }
            if (writeBack.backup() != null) {
                if (!createdIssueIds.isEmpty()) {
                    logger.info("Wrote " + (createdIssueIds.size() - writeBack.failures().size())
                            + " ticket IDs back to " + filePath + " (backup: " + writeBack.backup() + ")");
                }
                if (syncResults != null) {
                    logger.info("同期結果（" + String.join("・", syncResults.columns()) + "）を "
                            + syncResults.rows().size() + "行に書き込みました: " + filePath + "（最終同期日時 "
                            + syncResults.syncedAt() + "、backup: " + writeBack.backup() + "）");
                }
                if (statusValues != null) {
                    logger.info("Wrote " + (statusValues.rows().size() - writeBack.failures().size())
                            + " statuses back to " + filePath + " (backup: " + writeBack.backup() + ")");
                }
            }
            if (!writeBack.failures().isEmpty()) {
                writeBackState.failed = true;
                logger.error(createdIssueIds.isEmpty()
                        ? "ステータスを書き戻せなかった行があります。手で入力してください:"
                        : "チケットIDを書き戻せなかった行があります。次回実行で重複作成しないよう、"
                        + "以下のチケット番号を手で「" + ticketIdColumn + "」列（または数式の参照先）に入力してください:");
                for (String failure : writeBack.failures()) {
                    logger.error("  - " + failure);
                }
            }
        } catch (IOException | RuntimeException e) {
            String hint = "（Excel などでファイルを開いている場合は閉じてください）";
            if (createdIssueIds.isEmpty()) {
                // 同期結果・ステータスだけの書き込みの失敗は、Redmine への同期結果（ログ）には影響しない
                logger.error("同期結果" + (statusValues != null ? "・ステータス" : "") + "を入力ファイルに書き込めませんでした（" + filePath + "）: " + e.getMessage() + hint
                        + "。Redmine への同期は完了しています。各行の結果はログを確認してください");
                return;
            }
            writeBackState.failed = true;
            logger.error("チケットIDの書き戻しに失敗しました（" + filePath + "）: " + e.getMessage() + hint);
            logger.error("次回実行で重複作成しないよう、以下のIDを手動で「" + ticketIdColumn + "」列に入力してください:");
            String sheetLabel = job.parsed.sheetName() != null ? "シート「" + job.parsed.sheetName() + "」" : "";
            for (Map.Entry<Integer, Long> entry : createdIssueIds.entrySet()) {
                logger.error("  " + sheetLabel + "行" + entry.getKey() + " -> " + entry.getValue());
            }
        }
    }

    /** 書き戻しの日時の形式（文字列として書く） */
    static final DateTimeFormatter SYNCED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    /** 入力エラーで中止したときの「エラー理由」 */
    static final String ABORTED_BY_INPUT_ERROR = "入力エラーのため同期を中止しました（「エラー」の行・ログを確認してください）";

    /** 同期結果の列名 */
    record ResultColumnNames(String lastSyncedAt, String result, String error) {
        List<String> all() {
            return List.of(lastSyncedAt, result, error);
        }
    }

    /**
     * 同期結果の列名を返します（書き戻さない場合は null）。
     */
    static ResultColumnNames resultColumns(ProjectConfig projectConfig, boolean noResultColumns, FileLogger logger) {
        SyncConfig syncConfig = projectConfig.getSync();
        boolean enabled = !noResultColumns && (syncConfig == null || syncConfig.getResultColumns() == null
                || syncConfig.getResultColumns().isEnabled());
        if (logger != null) {
            logger.info("Result Columns: " + enabled + (noResultColumns ? "（コマンドライン --no-result-columns）"
                    : "（設定 sync.resultColumns.enabled）"));
        }
        return enabled ? resultColumnNames(projectConfig) : null;
    }

    /**
     * 設定の同期結果の列名（書き戻しの有無にかかわらず。解析時に無視する列）。
     */
    static ResultColumnNames resultColumnNames(ProjectConfig projectConfig) {
        ColumnsConfig columns = projectConfig != null && projectConfig.getSync() != null
                && projectConfig.getSync().getColumns() != null ? projectConfig.getSync().getColumns()
                        : new ColumnsConfig();
        ColumnsConfig defaults = new ColumnsConfig();
        return new ResultColumnNames(
                StringUtils.valueOrDefault(columns.getLastSyncedAtColumn(), defaults.getLastSyncedAtColumn()),
                StringUtils.valueOrDefault(columns.getSyncResultColumn(), defaults.getSyncResultColumn()),
                StringUtils.valueOrDefault(columns.getSyncErrorColumn(), defaults.getSyncErrorColumn()));
    }

    /**
     * 同期結果の列を解析結果から取り除きます（同期結果の列は差分・カスタムフィールドなどに使わない）。
     * 同期結果の列以外がすべて空の行も取り除きます。
     */
    static ParsedSheet withoutResultColumns(ParsedSheet parsed, ResultColumnNames names) {
        Set<String> ignored = new HashSet<>(names.all());
        if (parsed.headers().stream().noneMatch(ignored::contains)) {
            return parsed;
        }
        List<String> headers = parsed.headers().stream().filter(h -> !ignored.contains(h)).toList();
        List<Map<String, String>> rows = new ArrayList<>();
        List<Integer> rowNumbers = new ArrayList<>();
        for (int i = 0; i < parsed.rows().size(); i++) {
            Map<String, String> row = new LinkedHashMap<>(parsed.rows().get(i));
            row.keySet().removeAll(ignored);
            if (row.values().stream().allMatch(v -> v == null || v.isBlank())) {
                continue;
            }
            rows.add(row);
            rowNumbers.add(parsed.rowNumbers().get(i));
        }
        return new ParsedSheet(headers, rows, rowNumbers, parsed.sheetName());
    }

    /**
     * 書き戻す同期結果を作ります（書き戻さない設定なら null）。
     *
     * @param outcomes 行ごとの結果（ない行は notRunReason の未実行。検証エラーの行はエラー）
     * @param notRunReason 結果のない行の理由（null なら「同期されませんでした」）
     */
    private static TicketIdWriter.SyncResults syncResults(FileJob job, Map<Integer, RowOutcome> outcomes,
            RunState state, String notRunReason) {
        if (state.resultColumns == null || job.parsed == null) {
            return null;
        }
        Map<Integer, TicketIdWriter.RowResult> rows = new LinkedHashMap<>();
        for (Integer row : job.parsed.rowNumbers()) {
            RowOutcome outcome = outcomes.get(row);
            List<String> errors = job.rowErrors.get(row);
            if (outcome == null && errors != null && !errors.isEmpty()) {
                outcome = new RowOutcome(RowOutcome.Status.ERROR, String.join(" / ", errors));
            }
            if (outcome == null) {
                outcome = new RowOutcome(RowOutcome.Status.NOT_RUN,
                        notRunReason != null ? notRunReason : "同期されませんでした（ログを確認してください）");
            }
            rows.put(row, new TicketIdWriter.RowResult(outcome.status().label(), outcome.message()));
        }
        ResultColumnNames names = state.resultColumns;
        return new TicketIdWriter.SyncResults(names.lastSyncedAt(), names.result(), names.error(), state.syncedAt,
                rows);
    }

    /**
     * 同期しなかった（中止した）ファイルの各行に同期結果を書き戻します（dry-run・書き戻さない設定では何もしない）。
     * 行に結び付く検証エラーがあればその行は「エラー」、ほかの行は「未実行」と reason です。
     */
    private void writeNotRunResults(List<FileJob> jobs, ProjectConfig projectConfig, String reason, RunState state,
            FileLogger logger) {
        if (state.dryRun) {
            return;
        }
        if (projectConfig == null) {
            projectConfig = state.projectConfig;
        }
        for (FileJob job : jobs) {
            if (job.pendingOutcomes != null) {
                // 同期済みのファイル（自動完了のために書き戻しを後回しにしていた）はその結果を書く
                Map<Integer, Long> createdIssueIds = job.pendingCreatedIssueIds;
                Map<Integer, RowOutcome> outcomes = job.pendingOutcomes;
                job.pendingOutcomes = null;
                job.pendingCreatedIssueIds = null;
                writeBackTicketIds(job, projectConfig, createdIssueIds, syncResults(job, outcomes, state, reason),
                        null, job.writeBack, logger);
                continue;
            }
            if (state.resultColumns == null || job.resultsWritten || !job.doTickets || job.parsed == null) {
                continue;
            }
            writeBackTicketIds(job, projectConfig, Map.of(), syncResults(job, Map.of(), state, reason), null,
                    job.writeBack, logger);
        }
    }

    /**
     * 論理削除を行います（すべてのファイルの同期後に1回だけ）。
     * <p>
     * 対象は、同期先プロジェクトのチケットのうち、次のどれにも含まれないものです。
     * <ul>
     *   <li>今回同期したファイルの行のチケットIDと、対応付けた仮想親</li>
     *   <li>今回作成したチケット（仮想親を含む）</li>
     *   <li>設定 sync.files のファイル（今回同期しなかったものも読み込み、行のチケットIDと必要な仮想親を除外）</li>
     * </ul>
     * sync.files のファイルを読めない・検証エラーがある場合や、同期しなかったファイルがある場合は、
     * 安全のため論理削除を行いません。
     * 今回同期したファイル・sync.files のファイルのルートチケットとその祖先は、論理削除しません。
     * また今回同期したすべてのファイルにルートチケットがある場合は、それらのルートチケットの子孫だけを対象にします
     * （ルートチケットのないファイルが1つでもあればプロジェクト全体）。
     * </p>
     */
    private void logicalDelete(List<FileJob> jobs, List<String> listedFiles, ProjectConfig projectConfig,
            ExcelSource cliExcelSource, Boolean cliVirtualParents, RedmineClient client, boolean dryRun,
            RunState state, FileLogger logger) {
        if (state.skippedFile) {
            logger.warn("同期しなかったファイルがあるため、論理削除は行いません");
            return;
        }
        SyncConfig syncConfig = projectConfig.getSync();
        Integer deleteStatusId = deleteStatusId(syncConfig);
        // 作成したチケットも含めて判定するため、取得し直す（dry-run では何も作成していないので最後に取得した一覧）
        Map<Long, Map<String, Object>> issues = state.lastIssues;
        if (!dryRun) {
            logger.info("Fetching issues of project " + client.getProjectId() + " from Redmine (logical delete)...");
            issues = client.listProjectIssues();
        }
        Set<Long> keep = new HashSet<>(state.keepIssueIds);
        Map<Path, Long> configuredRoots = configuredRootIssueIds(projectConfig);
        // ルートチケット（今回同期したファイル・sync.files のファイル）とその祖先は論理削除しない
        Set<Long> roots = new LinkedHashSet<>();
        jobs.stream().filter(job -> job.rootIssueId != null).forEach(job -> roots.add(job.rootIssueId));
        for (String listed : listedFiles) {
            Long root = configuredRoots.get(normalize(listed));
            if (root != null) {
                roots.add(root);
            }
        }
        if (!roots.isEmpty()) {
            Set<Long> protectedIds = rootsAndAncestors(roots, issues, state.rootIssues, client);
            logger.info("ルートチケットとその祖先 " + protectedIds.size() + "件を論理削除の対象外にします: "
                    + protectedIds.stream().sorted().map(id -> "#" + id).toList());
            keep.addAll(protectedIds);
        }
        // 論理削除の範囲（今回同期したすべてのファイルにルートチケットがあれば、その配下だけ）
        List<FileJob> ticketJobs = jobs.stream().filter(job -> job.doTickets).toList();
        Set<Long> scope = null;
        if (!ticketJobs.isEmpty() && ticketJobs.stream().allMatch(job -> job.rootIssueId != null)) {
            scope = new LinkedHashSet<>();
            for (FileJob job : ticketJobs) {
                scope.add(job.rootIssueId);
            }
            logger.info("論理削除の範囲: ルートチケット " + scope.stream().map(id -> "#" + id).toList()
                    + " の配下（今回同期したすべてのファイルにルートチケットがあるため）");
        } else if (ticketJobs.stream().anyMatch(job -> job.rootIssueId != null)) {
            logger.info("論理削除の範囲: プロジェクト全体（ルートチケットのないファイルがあるため）");
        } else {
            logger.info("論理削除の範囲: プロジェクト全体");
        }
        Set<Path> processed = new HashSet<>();
        jobs.forEach(job -> processed.add(normalize(job.filePath)));
        for (String listed : listedFiles) {
            if (processed.contains(normalize(listed))) {
                continue;
            }
            logger.info("論理削除の対象外にするため、sync.files のファイルを読み込みます（同期はしません）: " + listed);
            try {
                Set<Long> ids = listedFileIssueIds(listed, projectConfig, cliExcelSource, cliVirtualParents, client,
                        issues, deleteStatusId, logger, configuredRoots.get(normalize(listed)));
                if (ids == null) {
                    logger.warn("sync.files のファイル「" + listed + "」を読み込めないため、安全のため論理削除は行いません");
                    return;
                }
                logger.info("  チケット " + ids.size() + "件を論理削除の対象外にします");
                state.listedIssueIds.put(normalize(listed), ids);
                keep.addAll(ids);
            } catch (IOException | RuntimeException e) {
                logger.warn("sync.files のファイル「" + listed + "」を読み込めないため、安全のため論理削除は行いません: "
                        + e.getMessage());
                return;
            }
        }
        List<Long> candidates = DiffCalculator.findLogicalDeleteCandidates(keep, issues, deleteStatusId, scope);
        logger.info("LOGICAL_DELETE candidates: " + candidates.size()
                + "（同期したファイル" + (listedFiles.isEmpty() ? "" : "・sync.files のファイル")
                + "のどれにもない" + (scope != null ? "ルートチケット配下" : "プロジェクト内")
                + "のチケット。Redmineで手動作成したチケットも含みます）");
        SyncResult deleted = syncExecutor.executeLogicalDelete(candidates, issues, projectConfig, client, dryRun,
                logger);
        if (deleteStatusId != null) {
            state.deletedIssueIds = candidates;
            state.deletedStatusId = deleteStatusId;
        }
        if (deleted == null) {
            return;
        }
        state.total += deleted.totalCount();
        state.success += deleted.successCount();
        state.ticketErrors += deleted.errorCount();
        state.errors.addAll(deleted.errors());
    }

    private static boolean isAutoCloseEnabled(SyncConfig syncConfig) {
        return syncConfig != null && syncConfig.getAutoCloseParents() != null
                && syncConfig.getAutoCloseParents().isEnabled();
    }

    /** 終了ステータスのIDを取得します（1回の実行で1回だけ。取得できなければ null） */
    private static Set<Long> closedStatusIds(RedmineClient client, RunState state, FileLogger logger) {
        if (state.closedStatusIds == null) {
            try {
                state.closedStatusIds = client.listClosedIssueStatusIds();
            } catch (RuntimeException ex) {
                logger.warn("ステータスの一覧を取得できません（親チケットの自動完了は行いません）: " + ex.getMessage());
                state.closedStatusIds = Set.of();
            }
        }
        return state.closedStatusIds;
    }

    /**
     * 親の行のうち、ステータス列が空欄の行は Redmine の現在のステータスを送るようにします
     * （親のステータスは子から決めるため）。
     */
    static List<DiffItem> keepParentStatuses(List<DiffItem> items, Map<Long, Map<String, Object>> issues,
            FileLogger logger) {
        Set<Integer> parentRows = new HashSet<>();
        items.forEach(item -> {
            if (item.parentRowNumber() != null) {
                parentRows.add(item.parentRowNumber());
            }
        });
        List<DiffItem> result = new ArrayList<>(items.size());
        for (DiffItem item : items) {
            Long current = item.issueId() == null ? null : IssueComparator.nestedId(issues.get(item.issueId()), "status");
            if (!item.virtual() && SyncConstants.ACTION_UPDATE.equals(item.action())
                    && isBlank(item.payload().get("statusCell")) && parentRows.contains(item.rowNumber())
                    && current != null) {
                logger.debug("keep status of parent " + item.label() + " (status_id=" + current + ")");
                result.add(new DiffItem(item.rowNumber(), item.issueId(), item.subject(), item.levelPath(),
                        item.depth(), item.parentRowNumber(), item.action(), String.valueOf(current),
                        item.trackerId(), item.payload(), item.virtual()));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    /**
     * 親チケットの自動完了（すべてのファイルの同期と論理削除の後に1回だけ）。
     * <p>
     * 子チケットがすべて終了ステータスになった親を、深い階層から順に完了にします（未完了には戻さない）。
     * 子は同期後の Redmine のチケットすべて（Excelにないチケットを含む）で判定し、dry-run では
     * 今回の作成・更新・論理削除の予定を反映して判定します。
     * 対象は今回同期したチケットとその祖先で、ルートチケットとその祖先は完了にしません。
     * 完了にした親がファイルの行なら、ステータス列（ある場合だけ）にステータス名を書き戻します。
     * </p>
     */
    private ParentAutoCloser.Result autoCloseParents(List<FileJob> jobs, List<String> listedFiles,
            ProjectConfig projectConfig, ExcelSource cliExcelSource, Boolean cliVirtualParents, RedmineClient client,
            boolean dryRun, RunState state, FileLogger logger) {
        SyncConfig syncConfig = projectConfig.getSync();
        Set<Long> closedStatusIds = closedStatusIds(client, state, logger);
        if (closedStatusIds.isEmpty()) {
            logger.warn("終了ステータスが見つからないため、親チケットの自動完了は行いません");
            return null;
        }
        ParentAutoCloser.TargetStatus target;
        try {
            target = ParentAutoCloser.resolveTarget(syncConfig.getAutoCloseParents(), syncConfig.getStatus(),
                    client.listIssueStatuses(), closedStatusIds);
        } catch (RuntimeException ex) {
            logger.error("親チケットの自動完了を行いません: " + ex.getMessage());
            state.errors.add("親チケットの自動完了: " + ex.getMessage());
            return null;
        }
        if (target == null) {
            logger.warn("完了にするステータスが決められないため、親チケットの自動完了は行いません");
            return null;
        }

        Map<Long, Map<String, Object>> issues = dryRun ? state.lastIssues : client.listProjectIssues();
        Map<Long, ParentAutoCloser.Node> nodes = ParentAutoCloser.nodes(issues);
        if (dryRun) {
            // 今回の作成・更新・論理削除の予定を反映する（作成予定のチケットは負の仮ID）
            for (SyncResult.PlannedIssue planned : state.planned) {
                ParentAutoCloser.Node current = nodes.get(planned.issueId());
                Long status = planned.statusId() != null ? planned.statusId()
                        : current != null ? current.statusId() : null;
                nodes.put(planned.issueId(), new ParentAutoCloser.Node(planned.parentId(), status, planned.subject()));
            }
            if (state.deletedStatusId != null) {
                for (Long id : state.deletedIssueIds) {
                    ParentAutoCloser.Node current = nodes.get(id);
                    if (current != null) {
                        nodes.put(id, new ParentAutoCloser.Node(current.parentId(),
                                state.deletedStatusId.longValue(), current.subject()));
                    }
                }
            }
        }

        Set<Long> roots = new LinkedHashSet<>();
        jobs.stream().filter(job -> job.rootIssueId != null).forEach(job -> roots.add(job.rootIssueId));
        Map<Path, Long> configuredRoots = configuredRootIssueIds(projectConfig);
        for (String listed : listedFiles) {
            Long root = configuredRoots.get(normalize(listed));
            if (root != null) {
                roots.add(root);
            }
        }
        Set<Long> protectedIds = roots.isEmpty() ? Set.of()
                : rootsAndAncestors(roots, issues, state.rootIssues, client);
        Set<Long> synced = new HashSet<>();
        state.planned.forEach(planned -> synced.add(planned.issueId()));
        Set<Long> candidates = ParentAutoCloser.candidates(synced, nodes, protectedIds);
        boolean ignoreNotInExcel = syncConfig.getAutoCloseParents().isIgnoreNotInExcel();
        Set<Long> managed = ignoreNotInExcel ? excelManagedIssueIds(jobs, listedFiles, projectConfig, cliExcelSource,
                cliVirtualParents, client, issues, state, logger) : null;
        if (ignoreNotInExcel) {
            if (managed == null) {
                return null;
            }
            long ignored = nodes.keySet().stream().filter(id -> !managed.contains(id)).count();
            logger.info("autoCloseParents.ignoreNotInExcel: Excelにないチケット " + ignored
                    + "件を子チケットの判定に使いません（変更もしません）");
        }
        List<ParentAutoCloser.Change> changes = new ArrayList<>();
        List<Long> closing = ParentAutoCloser.plan(nodes, closedStatusIds, candidates, protectedIds, managed);
        closing.forEach(id -> changes.add(new ParentAutoCloser.Change(id, target, true)));
        logger.info("AUTO_CLOSE candidates: " + closing.size() + "（子チケットがすべて完了した親チケット。ステータス「"
                + target.label() + "」(status_id=" + target.id() + ") にします）");
        if (syncConfig.getAutoCloseParents().isInProgress()) {
            Map<String, Long> statuses = client.listIssueStatuses();
            Long newStatusId = ParentAutoCloser.newStatusId(statuses, closedStatusIds);
            ParentAutoCloser.TargetStatus inProgress;
            try {
                inProgress = ParentAutoCloser.resolveInProgress(syncConfig.getAutoCloseParents(),
                        syncConfig.getStatus(), statuses, closedStatusIds);
            } catch (RuntimeException ex) {
                logger.error("親チケットを進行中にする処理を行いません: " + ex.getMessage());
                state.errors.add("親チケットの進行中: " + ex.getMessage());
                inProgress = null;
            }
            if (inProgress == null || newStatusId == null) {
                logger.info("進行中のステータスが見つからないため、親チケットを進行中にする処理は行いません"
                        + "（autoCloseParents.inProgressStatus で指定できます）");
            } else {
                List<Long> starting = ParentAutoCloser.planInProgress(nodes, newStatusId, closing, candidates,
                        protectedIds, managed);
                for (Long id : starting) {
                    changes.add(new ParentAutoCloser.Change(id, inProgress, false));
                }
                logger.info("AUTO_IN_PROGRESS candidates: " + starting.size() + "（子チケットに着手済みのものがある新規の"
                        + "親チケット。ステータス「" + inProgress.label() + "」(status_id=" + inProgress.id() + ") にします）");
            }
        }
        if (changes.isEmpty()) {
            return null;
        }
        ParentAutoCloser.Result result = ParentAutoCloser.execute(changes, nodes, client, dryRun, logger);
        state.total += changes.size();
        state.success += changes.size() - result.errors().size();
        state.ticketErrors += result.errors().size();
        state.errors.addAll(result.errors());
        return dryRun ? null : result;
    }

    /**
     * Excel で管理しているチケット（同期したファイル・sync.files のファイルにあるチケットと、今回作成したチケット。
     * 仮想親を含む）を返します（autoCloseParents.ignoreNotInExcel 用）。
     *
     * @return チケットID（sync.files のファイルを読めない場合は null。安全のため自動完了しない）
     */
    private Set<Long> excelManagedIssueIds(List<FileJob> jobs, List<String> listedFiles,
            ProjectConfig projectConfig, ExcelSource cliExcelSource, Boolean cliVirtualParents, RedmineClient client,
            Map<Long, Map<String, Object>> issues, RunState state, FileLogger logger) {
        Set<Long> managed = new HashSet<>(state.keepIssueIds);
        state.planned.forEach(planned -> managed.add(planned.issueId()));
        Set<Path> processed = new HashSet<>();
        jobs.forEach(job -> processed.add(normalize(job.filePath)));
        Map<Path, Long> configuredRoots = configuredRootIssueIds(projectConfig);
        for (String listed : listedFiles) {
            Path key = normalize(listed);
            if (processed.contains(key)) {
                continue;
            }
            Set<Long> ids = state.listedIssueIds.get(key);
            if (ids == null) {
                try {
                    ids = listedFileIssueIds(listed, projectConfig, cliExcelSource, cliVirtualParents, client, issues,
                            deleteStatusId(projectConfig.getSync()), logger, configuredRoots.get(key));
                } catch (IOException | RuntimeException e) {
                    logger.warn("sync.files のファイル「" + listed + "」を読み込めません: " + e.getMessage());
                    ids = null;
                }
                if (ids == null) {
                    logger.warn("sync.files のファイル「" + listed + "」を読み込めないため、安全のため親チケットの"
                            + "自動完了は行いません（autoCloseParents.ignoreNotInExcel）");
                    return null;
                }
                state.listedIssueIds.put(key, ids);
            }
            managed.addAll(ids);
        }
        return managed;
    }

    /**
     * 親チケットの自動完了のために後回しにした書き戻しを行います（ファイルごとに1回の書き込み・.bak も1つ）。
     * <p>
     * 新規作成したチケットID・同期結果に加えて、自動でステータスを変えた親の行はステータス列（ある場合だけ）に
     * ステータス名を書き、同期結果を「更新」（作成・更新の行はそのまま）にします。ステータスの変更に失敗した
     * 行は「エラー」と理由にします。
     * </p>
     *
     * @param autoClosed 自動完了の結果（行わなかった場合は null）
     */
    private void flushPendingWriteBacks(List<FileJob> jobs, ProjectConfig projectConfig,
            ParentAutoCloser.Result autoClosed, RunState state, FileLogger logger) {
        String statusColumn = statusColumn(projectConfig);
        for (FileJob job : jobs) {
            if (job.pendingOutcomes == null) {
                continue;
            }
            Map<Integer, RowOutcome> outcomes = new HashMap<>(job.pendingOutcomes);
            Map<Integer, String> statuses = new TreeMap<>();
            if (autoClosed != null) {
                for (ParentAutoCloser.Change change : autoClosed.completed()) {
                    Integer row = job.rowByIssueId.get(change.issueId());
                    if (row == null) {
                        continue;
                    }
                    statuses.put(row, change.target().label());
                    RowOutcome current = outcomes.get(row);
                    if (current == null || current.status() == RowOutcome.Status.UNCHANGED) {
                        outcomes.put(row, RowOutcome.of(RowOutcome.Status.UPDATED));
                    }
                }
                autoClosed.failures().forEach((issueId, reason) -> {
                    Integer row = job.rowByIssueId.get(issueId);
                    if (row != null) {
                        outcomes.put(row, new RowOutcome(RowOutcome.Status.ERROR, reason));
                    }
                });
            }
            TicketIdWriter.ColumnValues statusValues = null;
            if (!statuses.isEmpty()) {
                if (job.parsed != null && job.parsed.headers().contains(statusColumn)) {
                    statusValues = new TicketIdWriter.ColumnValues(statusColumn, statuses);
                    logger.info("自動で変更したステータスを「" + statusColumn + "」列の " + statuses.size() + "行に書き戻します: "
                            + job.filePath);
                } else {
                    logger.info("「" + statusColumn + "」列がないため、自動で変更したステータスは書き戻しません: "
                            + job.filePath);
                }
            }
            Map<Integer, Long> createdIssueIds = job.pendingCreatedIssueIds;
            job.pendingOutcomes = null;
            job.pendingCreatedIssueIds = null;
            writeBackTicketIds(job, projectConfig, createdIssueIds, syncResults(job, outcomes, state, null),
                    statusValues, job.writeBack, logger);
            state.writeBack.failed |= job.writeBack.failed;
        }
    }

    private static String statusColumn(ProjectConfig projectConfig) {
        return TicketColumns.of(projectConfig).status();
    }

    /**
     * 今回同期しない sync.files のファイルから、論理削除しないチケットID（行のチケットIDと必要な仮想親）を集めます。
     *
     * @return チケットID（ファイルを読めない・検証エラーがある場合は null）
     */
    Set<Long> listedFileIssueIds(String filePath, ProjectConfig projectConfig, ExcelSource cliExcelSource,
            Boolean cliVirtualParents, RedmineClient client, Map<Long, Map<String, Object>> issues,
            Integer deleteStatusId, FileLogger logger, Long rootIssueId) throws IOException {
        if (!Files.isRegularFile(Paths.get(filePath))) {
            logger.warn("ファイルがありません: " + filePath);
            return null;
        }
        FileJob job = parseFile(filePath, projectConfig, cliExcelSource, null, EnumSet.allOf(SyncTarget.class),
                logger);
        if (job == null) {
            return null;
        }
        Set<Long> ids = new HashSet<>();
        if (!job.doTickets) {
            return ids;
        }
        DiffPlan plan = calculatePlan(job.parsed, projectConfig, client, cliVirtualParents, logger);
        if (!plan.errors().isEmpty()) {
            logger.warn("ファイル「" + filePath + "」に検証エラーがあります（" + plan.errors().size() + "件。例: "
                    + plan.errors().get(0) + "）");
            return null;
        }
        List<DiffItem> items = VirtualParentMatcher.match(plan.items(), issues, deleteStatusId, logger, rootIssueId);
        for (DiffItem item : items) {
            if (item.issueId() != null) {
                ids.add(item.issueId());
            }
        }
        return ids;
    }

    /**
     * ルートチケットとその祖先のチケットIDを返します（親チケットを順にたどる）。
     * <p>
     * 同期先プロジェクトのチケット → 検証で取得したルートチケット → 個別取得の順に探します。
     * </p>
     */
    static Set<Long> rootsAndAncestors(Set<Long> roots, Map<Long, Map<String, Object>> issues,
            Map<Long, Map<String, Object>> fetched, RedmineClient client) {
        Set<Long> result = new LinkedHashSet<>();
        for (Long root : roots) {
            Long current = root;
            while (current != null && result.add(current)) {
                Map<String, Object> issue = issues.get(current);
                if (issue == null) {
                    issue = fetched.get(current);
                }
                if (issue == null && client != null) {
                    try {
                        issue = client.getIssue(current);
                    } catch (RuntimeException e) {
                        issue = null;
                    }
                    if (issue != null) {
                        fetched.put(current, issue);
                    }
                }
                current = IssueComparator.nestedId(issue, "parent");
            }
        }
        return result;
    }

    /** 1ファイルでの書き戻しの状態（バックアップは最初の書き込みの前に1回だけ作る） */
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

    private static boolean isDirectorySheet(String sheetName, ExcelSource... sources) {
        for (ExcelSource source : sources) {
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
    public static List<Path> defaultConfigCandidates() {
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
