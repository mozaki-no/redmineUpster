package mozaki.redmineUpster.cli;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import mozaki.redmineUpster.config.SyncConfigProperties.FullBackupConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.RedmineClientFactory;
import mozaki.redmineUpster.service.SyncConfigService;

/**
 * 全内容のバックアップ（{@code --backup}）の実行クラス。
 * <p>
 * Redmine が使えなくなったときに中身を確認できるよう、プロジェクトのチケット（クローズ済み・サブプロジェクトを含む）・
 * 履歴・関連・添付ファイル・作業時間・バージョン・カテゴリ・メンバー・Wiki などを読み取り専用の .xlsx に書き出します
 * （{@code --sync} の入力にはなりません。取り込める形の出力は {@code --export}）。
 * 既定の出力先は {@code <fullBackup.dir または ./backup>/<プロジェクト識別子>_yyyyMMdd_HHmmss.xlsx}、
 * 添付ファイルと長い文字列の全文はその横の {@code <同じ名前>_files} フォルダです。
 * 書き出し中は {@code .tmp} の名前で書き、成功したときだけ正式な名前に変えます（失敗したら消す）。
 * 成功後、{@code keep} 世代より古い同じプロジェクトのバックアップ（このツールの名前のものだけ）を削除します。
 * 対話の確認はなく、Jenkins などから実行できます（終了コード: 成功 0（警告があっても）、失敗 1）。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class BackupRunner {

    /** 出力先フォルダの既定（カレントディレクトリ基準） */
    static final String DEFAULT_DIR = "backup";

    private final SyncRunner syncRunner;
    private final SyncConfigService syncConfigService;
    private final RedmineClientFactory redmineClientFactory;

    /**
     * CLI で設定を上書きする値（null は設定に従う）。
     *
     * @param file 出力先の .xlsx（1プロジェクトのときだけ。指定時は世代管理しない）
     * @param dir 出力先フォルダ
     * @param keep 残す世代数
     * @param journals 履歴・ウォッチャー
     * @param attachments 添付ファイルのダウンロード
     * @param timeEntries 作業時間
     * @param wiki Wiki
     */
    public record Overrides(String file, String dir, Integer keep, Boolean journals, Boolean attachments,
            Boolean timeEntries, Boolean wiki) {
        /** 上書きなし */
        public static Overrides none() {
            return new Overrides(null, null, null, null, null, null, null);
        }
    }

    /**
     * バックアップを実行します。
     *
     * @param configPath 設定ファイルパス（null なら既定の場所）
     * @param projectName プロジェクト名（null ならデフォルトプロジェクト。allProjects なら無視）
     * @param allProjects 設定のすべてのプロジェクトをバックアップする場合 true（1プロジェクト1ファイル）
     * @param overrides CLI の上書き
     * @param logDir ログ出力ディレクトリ（null ならカレントディレクトリの logs）
     * @param debug デバッグモード
     * @return 成功（警告があっても）なら 0、1つでも失敗したら 1
     */
    public int run(String configPath, String projectName, boolean allProjects, Overrides overrides, String logDir,
            boolean debug) {
        return run(configPath, projectName, allProjects, overrides, logDir, debug, BackupSheet.EXCEL_MAX_DATA_ROWS);
    }

    int run(String configPath, String projectName, boolean allProjects, Overrides overrides, String logDir,
            boolean debug, int maxDataRows) {
        Overrides o = overrides == null ? Overrides.none() : overrides;
        FileLogger logger = null;
        try {
            logger = new FileLogger(logDir == null || logDir.isBlank() ? SyncRunner.DEFAULT_LOG_DIR : logDir);
            logger.setDebugEnabled(debug);
            logger.info("=== Redmine Full Backup Started ===");
            logger.info("Log File: " + logger.getLogFile());
            if (o.file() != null && !o.file().toLowerCase().endsWith(".xlsx")) {
                logger.error("出力先は .xlsx にしてください: " + o.file());
                return 1;
            }
            if (allProjects && o.file() != null) {
                logger.error("--all-projects と --file は同時に指定できません（--backup-dir を使ってください）");
                return 1;
            }
            if (!syncRunner.loadConfig(configPath, logger)) {
                return 1;
            }
            List<ProjectConfig> projects = new ArrayList<>();
            if (allProjects) {
                projects.addAll(syncConfigService.getAllProjects());
            } else {
                ProjectConfig project = syncRunner.resolveProject(projectName, logger);
                if (project != null) {
                    projects.add(project);
                }
            }
            if (projects.isEmpty()) {
                logger.error("No project configuration found");
                return 1;
            }
            int failed = 0;
            LocalDateTime now = LocalDateTime.now().withNano(0);
            for (ProjectConfig project : projects) {
                if (!backupProject(project, o, logger, now, maxDataRows)) {
                    failed++;
                }
            }
            if (projects.size() > 1) {
                logger.info("=== Backup finished: " + (projects.size() - failed) + " / " + projects.size()
                        + " projects succeeded ===");
            }
            return failed == 0 ? 0 : 1;
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

    private boolean backupProject(ProjectConfig project, Overrides o, FileLogger logger, LocalDateTime now,
            int maxDataRows) {
        logger.info("--- Project: " + project.getName() + " ---");
        if (project.getRedmine() == null || project.getRedmine().getProjectId() == null
                || project.getRedmine().getProjectId().isBlank()) {
            logger.error("redmine.projectId が設定されていません: " + project.getName());
            return false;
        }
        FullBackupConfig config = project.getFullBackup() != null ? project.getFullBackup() : new FullBackupConfig();
        FullBackupWriter.Options options = new FullBackupWriter.Options(
                o.journals() != null ? o.journals() : config.isJournals(),
                o.attachments() != null ? o.attachments() : config.isAttachments(),
                o.timeEntries() != null ? o.timeEntries() : config.isTimeEntries(),
                o.wiki() != null ? o.wiki() : config.isWiki(), config.getThreads(), maxDataRows);
        int keep = o.keep() != null ? o.keep() : config.getKeep();
        String identifier = project.getRedmine().getProjectId().trim();

        Path target;
        if (o.file() != null) {
            target = Paths.get(o.file()).toAbsolutePath().normalize();
        } else {
            String dir = o.dir() != null ? o.dir() : config.getDir() != null ? config.getDir() : DEFAULT_DIR;
            target = Paths.get(dir).toAbsolutePath().normalize()
                    .resolve(BackupNames.baseName(identifier, now) + ".xlsx");
        }
        Path dir = target.getParent();
        String fileName = target.getFileName().toString();
        String base = fileName.substring(0, fileName.length() - ".xlsx".length());
        Path filesDir = dir.resolve(base + BackupNames.FILES_SUFFIX);
        Path tempXlsx = dir.resolve(fileName + BackupNames.TEMP_SUFFIX);
        Path tempFiles = dir.resolve(base + BackupNames.FILES_SUFFIX + BackupNames.TEMP_SUFFIX);
        logger.info("Output: " + target);
        logger.info("Options: " + options.describe() + (o.file() == null ? "、残す世代数=" + (keep == 0 ? "すべて" : keep) : ""));
        try {
            Files.createDirectories(dir);
            Files.deleteIfExists(tempXlsx);
            deleteTree(tempFiles);
            RedmineClient client = redmineClientFactory.createClient(project);
            client.setLogger(logger);
            logger.info("Redmine URL: " + client.getBaseUrl());
            FullBackupWriter writer = new FullBackupWriter(client, logger, options, tempFiles,
                    base + BackupNames.FILES_SUFFIX + "/");
            FullBackupWriter.Result result = writer.write(tempXlsx, project.getName(), now);

            // 添付ファイルのフォルダ → .xlsx の順に正式な名前にする（.xlsx があれば揃っている）
            deleteTree(filesDir);
            if (Files.exists(tempFiles)) {
                move(tempFiles, filesDir);
            }
            move(tempXlsx, target);

            logger.info("=== Backup Complete: " + target + " ===");
            for (Map.Entry<String, Long> sheet : result.counts().entrySet()) {
                logger.info("  シート「" + sheet.getKey() + "」: " + sheet.getValue() + " 行");
            }
            if (options.attachments()) {
                logger.info("  添付ファイル: " + result.attachmentsSaved() + " 件（" + result.attachmentBytes() + " バイト）"
                        + (result.attachmentsFailed() > 0 ? "、保存できない " + result.attachmentsFailed() + " 件" : "")
                        + (Files.exists(filesDir) ? " → " + filesDir : ""));
            }
            if (!result.warnings().isEmpty()) {
                logger.warn("警告 " + result.warnings().size() + " 件（シート「情報」にも記載）");
            }
            if (o.file() == null && keep > 0) {
                prune(dir, identifier, keep, logger);
            }
            return true;
        } catch (Exception e) {
            logger.error("バックアップに失敗しました（" + project.getName() + "）: " + FullBackupWriter.describe(e));
            try {
                Files.deleteIfExists(tempXlsx);
                deleteTree(tempFiles);
            } catch (IOException cleanup) {
                logger.warn("書き出し途中のファイルを削除できません: " + cleanup.getMessage());
            }
            return false;
        }
    }

    /**
     * 同じプロジェクトのバックアップのうち、新しい {@code keep} 件より古いもの（.xlsx とその _files フォルダ）を削除します。
     * このツールが付けた名前（{@code <識別子>_yyyyMMdd_HHmmss.xlsx}）のファイルだけが対象です。
     *
     * @param dir 出力先フォルダ
     * @param identifier プロジェクト識別子
     * @param keep 残す世代数（1以上）
     * @param logger ロガー
     * @return 削除した .xlsx
     */
    static List<Path> prune(Path dir, String identifier, int keep, FileLogger logger) {
        Pattern pattern = BackupNames.generationPattern(identifier);
        List<Path> deleted = new ArrayList<>();
        List<Path> generations;
        try (Stream<Path> files = Files.list(dir)) {
            generations = files.filter(Files::isRegularFile)
                    .filter(p -> pattern.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            logger.warn("古いバックアップを確認できません: " + e.getMessage());
            return deleted;
        }
        for (Path old : generations.stream().skip(keep).toList()) {
            String name = old.getFileName().toString();
            Path files = dir.resolve(name.substring(0, name.length() - ".xlsx".length()) + BackupNames.FILES_SUFFIX);
            try {
                deleteTree(files);
                Files.delete(old);
                deleted.add(old);
                logger.info("古いバックアップを削除しました（" + keep + " 世代を残す）: " + old);
            } catch (IOException e) {
                logger.warn("古いバックアップを削除できません: " + old + "（" + e.getMessage() + "）");
            }
        }
        return deleted;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
