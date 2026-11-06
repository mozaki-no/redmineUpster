package mozaki.redmineUpster.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import mozaki.redmineUpster.service.RedmineClient;

/**
 * プロジェクトの全内容を1つの .xlsx（読み取り専用のバックアップ）に書き出すクラス。
 * <p>
 * チケットは {@code GET /issues.json}（クローズ済み・サブプロジェクトを含む）を100件ずつ取得し、
 * 取得したページごとにシートへ書き出します（SXSSF。チケットの件数が多くてもメモリはほぼ一定）。
 * 履歴・ウォッチャーはチケットごとの {@code GET /issues/{id}.json?include=journals,watchers,...} で取得します
 * （複数スレッドで同時に取得し、書き出しは ID 順）。
 * 作業時間・バージョン・カテゴリ・メンバー・Wiki・ユーザー・グループ・参照用の一覧は、取得できない（モジュールが無効・
 * 権限がない 403/404 など）場合は警告して、そのシートを出力しません。チケットを取得できない場合だけ失敗にします。
 * </p>
 */
final class FullBackupWriter {

    static final String INFO_SHEET = "情報";
    static final String TICKETS_SHEET = "チケット";
    static final String JOURNALS_SHEET = "履歴";
    static final String WATCHERS_SHEET = "ウォッチャー";
    static final String RELATIONS_SHEET = "関連";
    static final String ATTACHMENTS_SHEET = "添付ファイル";
    static final String TIME_ENTRIES_SHEET = "作業時間";
    static final String VERSIONS_SHEET = "バージョン";
    static final String CATEGORIES_SHEET = "カテゴリ";
    static final String MEMBERS_SHEET = "メンバー";
    static final String WIKI_SHEET = "Wiki";
    static final String USERS_SHEET = "ユーザー";
    static final String GROUPS_SHEET = "グループ";
    static final String TRACKERS_SHEET = "トラッカー";
    static final String STATUSES_SHEET = "ステータス";
    static final String PRIORITIES_SHEET = "優先度";
    static final String ACTIVITIES_SHEET = "作業分類";
    static final String CUSTOM_FIELDS_SHEET = "カスタムフィールド";
    /** 列の一覧にないカスタムフィールド（サブプロジェクトだけのものなど）をまとめる列 */
    static final String OTHER_CF_COLUMN = "CF:(その他)";

    private static final int PAGE_SIZE = 100;
    private static final int PROGRESS_EVERY = 1000;
    private static final int MAX_LISTED_FAILURES = 20;
    private static final int RETRIES = 3;
    private static final DateTimeFormatter INFO_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    /**
     * 出力する内容。
     *
     * @param journals 履歴・ウォッチャーを出力する
     * @param attachments 添付ファイルをダウンロードする
     * @param timeEntries 作業時間を出力する
     * @param wiki Wiki を出力する
     * @param threads チケットの詳細を同時に取得する数
     * @param maxDataRows 1シートのデータ行の上限（テスト用。通常は Excel の上限）
     */
    record Options(boolean journals, boolean attachments, boolean timeEntries, boolean wiki, int threads,
            int maxDataRows) {
        String describe() {
            return "履歴・ウォッチャー=" + onOff(journals) + "、添付ファイルのダウンロード=" + onOff(attachments)
                    + "、作業時間=" + onOff(timeEntries) + "、Wiki=" + onOff(wiki) + "、同時取得数=" + threads;
        }

        private static String onOff(boolean value) {
            return value ? "あり" : "なし";
        }
    }

    /**
     * 結果。
     *
     * @param counts シート名 → データ行の数（出力したシートだけ）
     * @param warnings 警告（情報シートにも書いたもの）
     * @param issues チケットの件数
     * @param attachmentsSaved 保存した添付ファイルの数
     * @param attachmentsFailed 保存に失敗した添付ファイルの数
     * @param attachmentBytes 保存した添付ファイルの合計バイト数
     */
    record Result(Map<String, Long> counts, List<String> warnings, long issues, int attachmentsSaved,
            int attachmentsFailed, long attachmentBytes) {
    }

    private final RedmineClient client;
    private final FileLogger logger;
    private final Options options;
    /** 添付ファイル・長い文字列の保存先（書き出し中は一時フォルダ） */
    private final Path filesDir;
    /** .xlsx から見た保存先フォルダの名前（シートに書く相対パスの先頭。例: {@code abc_20261106_093000_files/}） */
    private final String filesPrefix;

    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private BackupSheet.Context context;

    // 名前の解決用（取得できたものだけ）
    private final Map<Long, String> userNames = new HashMap<>();
    private final Map<String, String> statusNames = new HashMap<>();
    private final Map<String, String> trackerNames = new HashMap<>();
    private final Map<String, String> priorityNames = new HashMap<>();
    private final Map<String, String> versionNames = new HashMap<>();
    private final Map<String, String> categoryNames = new HashMap<>();
    private final Map<Long, Map<String, Object>> customFieldDefs = new LinkedHashMap<>();
    /** チケットのカスタムフィールドの列（ID → 列名） */
    private final Map<Long, String> issueCfColumns = new LinkedHashMap<>();

    private int attachmentsSaved;
    private int attachmentsFailed;
    private long attachmentBytes;
    private BackupSheet attachmentSheet;

    FullBackupWriter(RedmineClient client, FileLogger logger, Options options, Path filesDir, String filesPrefix) {
        this.client = client;
        this.logger = logger;
        this.options = options;
        this.filesDir = filesDir;
        this.filesPrefix = filesPrefix;
    }

    /**
     * バックアップを書き出します。
     *
     * @param out 書き出し先の .xlsx（一時ファイル）
     * @param projectLabel 設定のプロジェクト名
     * @param startedAt 実行日時
     * @return 結果
     * @throws IOException 書き込めない場合
     * @throws RuntimeException チケットを取得できない場合
     */
    Result write(Path out, String projectLabel, LocalDateTime startedAt) throws IOException {
        Instant started = Instant.now();
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try {
            context = new BackupSheet.Context(workbook, headerStyle(workbook), format(workbook, "yyyy/mm/dd"),
                    format(workbook, "yyyy/mm/dd hh:mm:ss"), filesDir.resolve("long_text"),
                    filesPrefix + "long_text/", options.maxDataRows());
            // 「情報」は先頭のシートにする（中身は最後に書く）
            SXSSFSheet info = workbook.createSheet(INFO_SHEET);
            String projectId = client.getProjectId();
            // Redmine に接続できない場合は（ほかの一覧も取れないので）ここで失敗にする
            Map<String, Object> project;
            try {
                project = map(retry(() -> client.getJson("/projects/" + projectId + ".json", Map.of("include",
                        "trackers,issue_categories,enabled_modules,time_entry_activities,issue_custom_fields")))
                        .get("project"));
            } catch (ResourceAccessException e) {
                throw e;
            } catch (RuntimeException e) {
                warn("プロジェクトの情報を取得できません（" + describe(e) + "）。出力しません");
                project = null;
            }

            // 名前の解決に使う一覧を先に取得する（シートは後で書く）
            List<Map<String, Object>> trackers = optionalList("トラッカー", TRACKERS_SHEET, "/trackers.json", "trackers");
            List<Map<String, Object>> statuses = optionalList("ステータス", STATUSES_SHEET, "/issue_statuses.json",
                    "issue_statuses");
            List<Map<String, Object>> priorities = optionalList("優先度", PRIORITIES_SHEET,
                    "/enumerations/issue_priorities.json", "issue_priorities");
            List<Map<String, Object>> activities = optionalList("作業分類", ACTIVITIES_SHEET,
                    "/enumerations/time_entry_activities.json", "time_entry_activities");
            List<Map<String, Object>> customFields = optionalList("カスタムフィールド（管理者の API キーが必要）",
                    CUSTOM_FIELDS_SHEET, "/custom_fields.json", "custom_fields");
            List<Map<String, Object>> versions = optionalList("バージョン", VERSIONS_SHEET,
                    "/projects/" + projectId + "/versions.json", "versions");
            List<Map<String, Object>> categories = optionalList("カテゴリ", CATEGORIES_SHEET,
                    "/projects/" + projectId + "/issue_categories.json", "issue_categories");
            Map<Long, Map<String, Object>> users = optional("ユーザー（管理者の API キーが必要）", client::listUsers);
            Map<Long, Map<String, Object>> groups = optional("グループ（管理者の API キーが必要）", client::listGroups);
            fillLookups(trackers, statuses, priorities, versions, categories, customFields, users, groups);
            collectIssueCustomFieldColumns(project, customFields);

            long issues = writeTickets();
            if (options.timeEntries()) {
                writeTimeEntries(projectId);
            }
            writeVersions(versions);
            writeCategories(categories);
            writeMembers(projectId);
            if (options.wiki()) {
                writeWiki(projectId);
            }
            count(attachmentSheet);
            writeUsers(users);
            writeGroups(groups, users);
            writeReference(trackers, statuses, priorities, activities, customFields);
            writeInfo(info, projectLabel, project, startedAt, Duration.between(started, Instant.now()), issues);

            Files.createDirectories(out.toAbsolutePath().getParent());
            try (OutputStream stream = Files.newOutputStream(out)) {
                workbook.write(stream);
            }
            return new Result(counts, List.copyOf(warnings), issues, attachmentsSaved, attachmentsFailed,
                    attachmentBytes);
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    // ---------------------------------------------------------------- チケット

    private long writeTickets() throws IOException {
        String projectId = client.getProjectId();
        List<String> ticketHeaders = new ArrayList<>(List.of("ID", "プロジェクト", "トラッカー", "ステータス", "優先度", "題名",
                "説明", "作成者", "担当者", "カテゴリ", "対象バージョン", "親チケット", "開始日", "期日", "進捗率", "予定工数",
                "合計予定工数", "作業時間", "合計作業時間", "プライベート", "作成日時", "更新日時", "終了日時"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project_id", projectId);
        params.put("subproject_id", "*");
        params.put("status_id", "*");
        params.put("sort", "id");
        if (!options.journals()) {
            params.put("include", "attachments,relations");
        }
        // 1ページ目を先に取得して、カスタムフィールドの列を決める（取得できなければ失敗）
        logger.info("Fetching issues of project " + projectId + " (closed and subprojects included)...");
        Map<String, Object> firstPage = fetchPage("/issues.json", params, 0);
        int totalCount = firstPage.get("total_count") instanceof Number n ? n.intValue() : -1;
        for (Map<String, Object> issue : list(firstPage.get("issues"))) {
            for (Map<String, Object> cf : list(issue.get("custom_fields"))) {
                Long id = longValue(cf.get("id"));
                if (id != null && !issueCfColumns.containsKey(id)) {
                    addCfColumn(id, text(cf.get("name")));
                }
            }
        }
        List<Long> cfIds = new ArrayList<>(issueCfColumns.keySet());
        issueCfColumns.values().forEach(ticketHeaders::add);
        ticketHeaders.add(OTHER_CF_COLUMN);
        logger.info("Issues: " + (totalCount >= 0 ? totalCount : "?") + " (custom field columns: " + cfIds.size() + ")");

        int[] widths = new int[ticketHeaders.size()];
        java.util.Arrays.fill(widths, 14);
        widths[0] = 8;
        widths[5] = 40;
        widths[6] = 50;
        BackupSheet tickets = new BackupSheet(context, TICKETS_SHEET, ticketHeaders, widths);
        BackupSheet journals = options.journals() ? new BackupSheet(context, JOURNALS_SHEET, List.of("チケットID",
                "履歴ID", "ユーザー", "日時", "プライベート注記", "注記", "種別", "項目", "変更前", "変更後"),
                9, 9, 14, 18, 8, 50, 10, 16, 24, 24) : null;
        BackupSheet watchers = options.journals() ? new BackupSheet(context, WATCHERS_SHEET,
                List.of("チケットID", "ユーザーID", "ユーザー"), 9, 9, 20) : null;
        BackupSheet relations = new BackupSheet(context, RELATIONS_SHEET,
                List.of("関連ID", "チケットID", "関連先チケットID", "種類", "遅延（日）"), 9, 9, 12, 14, 9);
        attachmentSheet = new BackupSheet(context, ATTACHMENTS_SHEET, List.of("対象", "チケットID", "Wikiページ", "添付ID",
                "ファイル名", "サイズ（バイト）", "種類", "説明", "作成者", "作成日時", "保存先"),
                8, 9, 20, 9, 30, 12, 16, 30, 14, 18, 50);

        Set<Long> seenIssues = new HashSet<>();
        Set<Long> seenRelations = new HashSet<>();
        List<Long> failedDetails = new ArrayList<>();
        int failedDetailCount = 0;
        long written = 0;
        int offset = 0;
        Map<String, Object> page = firstPage;
        ExecutorService executor = options.journals() ? Executors.newFixedThreadPool(Math.max(1, options.threads()))
                : null;
        try {
            while (true) {
                List<Map<String, Object>> items = list(page.get("issues"));
                List<Map<String, Object>> details = fetchDetails(items, executor);
                for (int i = 0; i < items.size(); i++) {
                    Map<String, Object> issue = items.get(i);
                    Long id = longValue(issue.get("id"));
                    if (id == null || !seenIssues.add(id)) {
                        continue;
                    }
                    Map<String, Object> detail = details.get(i);
                    if (options.journals() && detail == null) {
                        failedDetailCount++;
                        if (failedDetails.size() < MAX_LISTED_FAILURES) {
                            failedDetails.add(id);
                        }
                    }
                    Map<String, Object> merged = issue;
                    if (detail != null) {
                        merged = new LinkedHashMap<>(issue);
                        merged.putAll(detail);
                    }
                    tickets.addRow(ticketRow(merged, cfIds));
                    if (detail != null) {
                        writeJournals(journals, id, detail);
                        for (Map<String, Object> watcher : list(detail.get("watchers"))) {
                            watchers.addRow(id, longValue(watcher.get("id")), text(watcher.get("name")));
                        }
                    }
                    for (Map<String, Object> relation : list(merged.get("relations"))) {
                        Long relationId = longValue(relation.get("id"));
                        if (relationId == null || seenRelations.add(relationId)) {
                            relations.addRow(relationId, longValue(relation.get("issue_id")),
                                    longValue(relation.get("issue_to_id")), text(relation.get("relation_type")),
                                    longValue(relation.get("delay")));
                        }
                    }
                    for (Map<String, Object> attachment : list(merged.get("attachments"))) {
                        writeAttachment("チケット", id, null, attachment, id.toString());
                    }
                    written++;
                    if (written % PROGRESS_EVERY == 0) {
                        logger.info("  チケット " + written + (totalCount >= 0 ? " / " + totalCount : "") + " 件を出力");
                    }
                }
                offset += items.size();
                if (items.isEmpty() || (totalCount >= 0 && offset >= totalCount)
                        || (totalCount < 0 && items.size() < PAGE_SIZE)) {
                    break;
                }
                page = fetchPage("/issues.json", params, offset);
            }
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
        logger.info("  チケット " + written + " 件を出力しました");
        if (totalCount >= 0 && written < totalCount) {
            warn("チケットの件数が Redmine の件数（" + totalCount + "）より少なく " + written
                    + " 件です（取得中にチケットが削除・移動された可能性があります）");
        }
        if (failedDetailCount > 0) {
            warn("チケット " + failedDetailCount + " 件の履歴・ウォッチャーを取得できませんでした（一覧の内容だけ出力）: #"
                    + String.join(", #", failedDetails.stream().map(String::valueOf).toList())
                    + (failedDetailCount > failedDetails.size() ? " ほか" : ""));
        }
        count(tickets);
        if (journals != null) {
            count(journals);
            count(watchers);
        }
        count(relations);
        return written;
    }

    private Map<String, Object> fetchPage(String path, Map<String, Object> params, int offset) {
        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("limit", PAGE_SIZE);
        pageParams.put("offset", offset);
        return retry(() -> client.getJson(path, pageParams));
    }

    /**
     * ページのチケットの詳細（履歴・ウォッチャー・添付・関連）を取得します（取得できなかったものは null）。
     */
    private List<Map<String, Object>> fetchDetails(List<Map<String, Object>> items, ExecutorService executor) {
        List<Map<String, Object>> details = new ArrayList<>();
        if (executor == null) {
            items.forEach(item -> details.add(null));
            return details;
        }
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Long id = longValue(item.get("id"));
            futures.add(executor.submit(() -> id == null ? null
                    : map(retry(() -> client.getJson("/issues/" + id + ".json",
                            Map.of("include", "journals,watchers,attachments,relations"))).get("issue"))));
        }
        for (int i = 0; i < futures.size(); i++) {
            try {
                details.add(futures.get(i).get());
            } catch (ExecutionException e) {
                logger.warn("チケット #" + items.get(i).get("id") + " の詳細を取得できません: " + describe(e.getCause()));
                details.add(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("中断されました", e);
            }
        }
        return details;
    }

    private Object[] ticketRow(Map<String, Object> issue, List<Long> cfIds) {
        List<Object> row = new ArrayList<>(List.of(
                orEmpty(longValue(issue.get("id"))),
                nestedName(issue, "project"),
                nestedName(issue, "tracker"),
                nestedName(issue, "status"),
                nestedName(issue, "priority"),
                text(issue.get("subject")),
                text(issue.get("description")),
                nestedName(issue, "author"),
                nestedName(issue, "assigned_to"),
                nestedName(issue, "category"),
                nestedName(issue, "fixed_version"),
                issue.get("parent") instanceof Map<?, ?> parent ? orEmpty(longValue(parent.get("id"))) : "",
                date(issue.get("start_date")),
                date(issue.get("due_date")),
                orEmpty(issue.get("done_ratio")),
                orEmpty(issue.get("estimated_hours")),
                orEmpty(issue.get("total_estimated_hours")),
                orEmpty(issue.get("spent_hours")),
                orEmpty(issue.get("total_spent_hours")),
                issue.get("is_private") instanceof Boolean b ? b : "",
                dateTime(issue.get("created_on")),
                dateTime(issue.get("updated_on")),
                dateTime(issue.get("closed_on"))));
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        List<String> others = new ArrayList<>();
        for (Map<String, Object> cf : list(issue.get("custom_fields"))) {
            Long id = longValue(cf.get("id"));
            if (id != null && issueCfColumns.containsKey(id)) {
                byId.put(id, cf);
            } else {
                String value = cfValue(cf);
                if (!value.isEmpty()) {
                    others.add(text(cf.get("name")) + ": " + value);
                }
            }
        }
        for (Long id : cfIds) {
            Map<String, Object> cf = byId.get(id);
            row.add(cf == null ? "" : cfValue(cf));
        }
        row.add(String.join("\n", others));
        return row.toArray();
    }

    private void writeJournals(BackupSheet sheet, Long issueId, Map<String, Object> detail) throws IOException {
        for (Map<String, Object> journal : list(detail.get("journals"))) {
            Long journalId = longValue(journal.get("id"));
            String user = nestedName(journal, "user");
            Object createdOn = dateTime(journal.get("created_on"));
            Object privateNotes = journal.get("private_notes") instanceof Boolean b ? b : "";
            String notes = text(journal.get("notes"));
            List<Map<String, Object>> details = list(journal.get("details"));
            if (details.isEmpty()) {
                sheet.addRow(issueId, journalId, user, createdOn, privateNotes, notes, "", "", "", "");
                continue;
            }
            boolean first = true;
            for (Map<String, Object> change : details) {
                String property = text(change.get("property"));
                String name = text(change.get("name"));
                sheet.addRow(issueId, journalId, user, createdOn, privateNotes, first ? notes : "", property,
                        detailName(property, name), resolve(property, name, text(change.get("old_value"))),
                        resolve(property, name, text(change.get("new_value"))));
                first = false;
            }
        }
    }

    private String detailName(String property, String name) {
        if ("cf".equals(property)) {
            Long id = parseLong(name);
            String column = id == null ? null : issueCfColumns.get(id);
            if (column != null) {
                return column + " (" + name + ")";
            }
            Map<String, Object> def = id == null ? null : customFieldDefs.get(id);
            return def != null ? "CF:" + text(def.get("name")) + " (" + name + ")" : name;
        }
        return name;
    }

    /** 履歴の値の ID に名前を付けます（例: {@code 進行中 (2)}）。名前が分からなければそのまま。 */
    private String resolve(String property, String name, String value) {
        if (value.isEmpty() || !"attr".equals(property)) {
            if ("cf".equals(property) && !value.isEmpty()) {
                Long id = parseLong(name);
                String display = id == null ? value : cfDisplay(id, value);
                return display.equals(value) ? value : display + " (" + value + ")";
            }
            return value;
        }
        Map<String, String> names = switch (name) {
        case "status_id" -> statusNames;
        case "tracker_id" -> trackerNames;
        case "priority_id" -> priorityNames;
        case "fixed_version_id" -> versionNames;
        case "category_id" -> categoryNames;
        default -> null;
        };
        String resolved = null;
        if (names != null) {
            resolved = names.get(value);
        } else if ("assigned_to_id".equals(name) || "author_id".equals(name)) {
            Long id = parseLong(value);
            resolved = id == null ? null : userNames.get(id);
        }
        return resolved == null ? value : resolved + " (" + value + ")";
    }

    // ---------------------------------------------------------------- 添付ファイル

    private void writeAttachment(String kind, Long issueId, String wikiTitle, Map<String, Object> attachment,
            String folder) throws IOException {
        Long attachmentId = longValue(attachment.get("id"));
        String fileName = text(attachment.get("filename"));
        String saved;
        if (attachmentId == null) {
            saved = "";
        } else if (!options.attachments()) {
            saved = "（ダウンロードしない設定）";
        } else {
            String relative = BackupNames.portable(filesDir,
                    folder + "/" + BackupNames.sanitize(attachmentId + "_" + fileName),
                    (issueId != null ? issueId.toString() : "wiki/" + Integer.toHexString(folder.hashCode())) + "/"
                            + attachmentId + BackupNames.asciiExtension(fileName));
            Path target = filesDir.resolve(relative);
            try {
                Files.createDirectories(target.getParent());
                long bytes = retry(() -> client.downloadAttachment(attachmentId, target));
                attachmentsSaved++;
                attachmentBytes += bytes;
                saved = filesPrefix + relative;
            } catch (RuntimeException | IOException e) {
                attachmentsFailed++;
                String reason = describe(e);
                logger.warn("添付ファイル #" + attachmentId + "「" + fileName + "」（" + kind
                        + (issueId != null ? " #" + issueId : " " + wikiTitle) + "）を保存できません: " + reason);
                saved = "（保存できません: " + reason + "）";
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // 途中までのファイルを消せなくても続ける
                }
            }
        }
        attachmentSheet.addRow(kind, issueId, wikiTitle == null ? "" : wikiTitle, attachmentId, fileName,
                orEmpty(attachment.get("filesize")), text(attachment.get("content_type")),
                text(attachment.get("description")), nestedName(attachment, "author"),
                dateTime(attachment.get("created_on")), saved);
    }

    // ---------------------------------------------------------------- 作業時間・プロジェクトの一覧

    private void writeTimeEntries(String projectId) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project_id", projectId);
        Map<String, Object> page = optional("作業時間", () -> fetchPage("/time_entries.json", params, 0));
        if (page == null) {
            return;
        }
        BackupSheet sheet = new BackupSheet(context, TIME_ENTRIES_SHEET, List.of("ID", "プロジェクト", "チケットID",
                "ユーザー", "作業分類", "時間", "日付", "コメント", "作成日時", "更新日時", "カスタムフィールド"),
                8, 16, 9, 14, 12, 8, 12, 40, 18, 18, 30);
        int total = page.get("total_count") instanceof Number n ? n.intValue() : -1;
        int offset = 0;
        while (true) {
            List<Map<String, Object>> items = list(page.get("time_entries"));
            for (Map<String, Object> entry : items) {
                sheet.addRow(longValue(entry.get("id")), nestedName(entry, "project"),
                        entry.get("issue") instanceof Map<?, ?> issue ? orEmpty(longValue(issue.get("id"))) : "",
                        nestedName(entry, "user"), nestedName(entry, "activity"), orEmpty(entry.get("hours")),
                        date(entry.get("spent_on")), text(entry.get("comments")), dateTime(entry.get("created_on")),
                        dateTime(entry.get("updated_on")), cfSummary(entry));
            }
            offset += items.size();
            if (items.isEmpty() || (total >= 0 && offset >= total) || (total < 0 && items.size() < PAGE_SIZE)) {
                break;
            }
            int nextOffset = offset;
            page = optional("作業時間（" + offset + " 件目以降）", () -> fetchPage("/time_entries.json", params, nextOffset));
            if (page == null) {
                break;
            }
        }
        count(sheet);
    }

    private void writeVersions(List<Map<String, Object>> versions) throws IOException {
        if (versions == null) {
            return;
        }
        BackupSheet sheet = new BackupSheet(context, VERSIONS_SHEET, List.of("ID", "プロジェクト", "名前", "説明", "ステータス",
                "期日", "共有", "Wikiページ", "作成日時", "更新日時", "カスタムフィールド"), 8, 16, 20, 30, 10, 12, 12, 16, 18, 18, 30);
        for (Map<String, Object> version : versions) {
            sheet.addRow(longValue(version.get("id")), nestedName(version, "project"), text(version.get("name")),
                    text(version.get("description")), text(version.get("status")), date(version.get("due_date")),
                    text(version.get("sharing")), text(version.get("wiki_page_title")),
                    dateTime(version.get("created_on")), dateTime(version.get("updated_on")), cfSummary(version));
        }
        count(sheet);
    }

    private void writeCategories(List<Map<String, Object>> categories) throws IOException {
        if (categories == null) {
            return;
        }
        BackupSheet sheet = new BackupSheet(context, CATEGORIES_SHEET, List.of("ID", "プロジェクト", "名前", "担当者"),
                8, 16, 24, 16);
        for (Map<String, Object> category : categories) {
            sheet.addRow(longValue(category.get("id")), nestedName(category, "project"), text(category.get("name")),
                    nestedName(category, "assigned_to"));
        }
        count(sheet);
    }

    private void writeMembers(String projectId) throws IOException {
        String path = "/projects/" + projectId + "/memberships.json";
        Map<String, Object> page = optional("メンバー", () -> fetchPage(path, Map.of(), 0));
        if (page == null) {
            return;
        }
        BackupSheet sheet = new BackupSheet(context, MEMBERS_SHEET, List.of("ID", "プロジェクト", "種別", "ユーザー/グループID",
                "名前", "ロール"), 8, 16, 10, 12, 20, 40);
        int total = page.get("total_count") instanceof Number n ? n.intValue() : -1;
        int offset = 0;
        while (true) {
            List<Map<String, Object>> items = list(page.get("memberships"));
            for (Map<String, Object> membership : items) {
                boolean group = membership.get("group") instanceof Map;
                Map<String, Object> principal = map(group ? membership.get("group") : membership.get("user"));
                List<String> roles = new ArrayList<>();
                for (Map<String, Object> role : list(membership.get("roles"))) {
                    roles.add(text(role.get("name")) + (Boolean.TRUE.equals(role.get("inherited")) ? "（継承）" : ""));
                }
                sheet.addRow(longValue(membership.get("id")), nestedName(membership, "project"),
                        group ? "グループ" : "ユーザー", principal == null ? "" : orEmpty(longValue(principal.get("id"))),
                        principal == null ? "" : text(principal.get("name")), String.join(", ", roles));
            }
            offset += items.size();
            if (items.isEmpty() || (total >= 0 && offset >= total) || (total < 0 && items.size() < PAGE_SIZE)) {
                break;
            }
            int nextOffset = offset;
            page = optional("メンバー（" + offset + " 件目以降）", () -> fetchPage(path, Map.of(), nextOffset));
            if (page == null) {
                break;
            }
        }
        count(sheet);
    }

    private void writeWiki(String projectId) throws IOException {
        List<Map<String, Object>> pages = optionalList("Wiki", WIKI_SHEET, "/projects/" + projectId + "/wiki/index.json",
                "wiki_pages");
        if (pages == null) {
            return;
        }
        BackupSheet sheet = new BackupSheet(context, WIKI_SHEET, List.of("ページ名", "親ページ", "版", "作成者", "作成日時",
                "更新日時", "コメント", "本文"), 24, 20, 6, 14, 18, 18, 30, 80);
        int failed = 0;
        for (Map<String, Object> summary : pages) {
            String title = text(summary.get("title"));
            Map<String, Object> page;
            try {
                page = map(retry(() -> client.getJsonBySegments(
                        List.of("projects", projectId, "wiki", title + ".json"), Map.of("include", "attachments")))
                        .get("wiki_page"));
            } catch (RuntimeException e) {
                failed++;
                logger.warn("Wiki ページ「" + title + "」を取得できません: " + describe(e));
                page = null;
            }
            Map<String, Object> source = page != null ? page : summary;
            String parent = source.get("parent") instanceof Map<?, ?> p ? text(p.get("title")) : "";
            sheet.addRow(title, parent, orEmpty(source.get("version")), nestedName(source, "author"),
                    dateTime(source.get("created_on")), dateTime(source.get("updated_on")), text(source.get("comments")),
                    page != null ? text(page.get("text")) : "（取得できません）");
            if (page != null) {
                for (Map<String, Object> attachment : list(page.get("attachments"))) {
                    writeAttachment("Wiki", null, title, attachment, "wiki/" + BackupNames.sanitize(title));
                }
            }
        }
        if (failed > 0) {
            warn("Wiki の " + failed + " ページを取得できませんでした（ページ名だけ出力）");
        }
        count(sheet);
    }

    private void writeUsers(Map<Long, Map<String, Object>> users) throws IOException {
        if (users == null) {
            return;
        }
        BackupSheet sheet = new BackupSheet(context, USERS_SHEET, List.of("ID", "ログインID", "姓", "名", "メールアドレス",
                "管理者", "状態", "認証方式", "作成日時", "最終ログイン", "パスワード変更日", "カスタムフィールド"),
                8, 14, 10, 10, 24, 8, 8, 10, 18, 18, 18, 30);
        for (Map<String, Object> user : users.values()) {
            sheet.addRow(longValue(user.get("id")), text(user.get("login")), text(user.get("lastname")),
                    text(user.get("firstname")), text(user.get("mail")),
                    user.get("admin") instanceof Boolean b ? b : "", userStatus(user.get("status")),
                    orEmpty(user.get("auth_source_id")), dateTime(user.get("created_on")),
                    dateTime(user.get("last_login_on")), dateTime(user.get("passwd_changed_on")), cfSummary(user));
        }
        count(sheet);
    }

    private void writeGroups(Map<Long, Map<String, Object>> groups, Map<Long, Map<String, Object>> users)
            throws IOException {
        if (groups == null) {
            return;
        }
        BackupSheet sheet = new BackupSheet(context, GROUPS_SHEET, List.of("ID", "名前", "メンバー（ID）", "メンバー（名前）"),
                8, 20, 30, 50);
        for (Map<String, Object> group : groups.values()) {
            List<String> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Map<String, Object> member : list(group.get("users"))) {
                ids.add(text(member.get("id")));
                names.add(text(member.get("name")));
            }
            sheet.addRow(longValue(group.get("id")), text(group.get("name")), String.join(", ", ids),
                    String.join(", ", names));
        }
        count(sheet);
    }

    private void writeReference(List<Map<String, Object>> trackers, List<Map<String, Object>> statuses,
            List<Map<String, Object>> priorities, List<Map<String, Object>> activities,
            List<Map<String, Object>> customFields) throws IOException {
        if (trackers != null) {
            BackupSheet sheet = new BackupSheet(context, TRACKERS_SHEET, List.of("ID", "名前", "既定のステータス", "説明"),
                    8, 16, 14, 40);
            for (Map<String, Object> tracker : trackers) {
                sheet.addRow(longValue(tracker.get("id")), text(tracker.get("name")),
                        nestedName(tracker, "default_status"), text(tracker.get("description")));
            }
            count(sheet);
        }
        if (statuses != null) {
            BackupSheet sheet = new BackupSheet(context, STATUSES_SHEET, List.of("ID", "名前", "終了"), 8, 16, 8);
            for (Map<String, Object> status : statuses) {
                sheet.addRow(longValue(status.get("id")), text(status.get("name")),
                        status.get("is_closed") instanceof Boolean b ? b : "");
            }
            count(sheet);
        }
        writeEnumeration(PRIORITIES_SHEET, priorities);
        writeEnumeration(ACTIVITIES_SHEET, activities);
        if (customFields != null) {
            BackupSheet sheet = new BackupSheet(context, CUSTOM_FIELDS_SHEET, List.of("ID", "名前", "対象", "形式", "必須",
                    "複数選択", "既定値", "選択肢", "トラッカー"), 8, 20, 12, 12, 8, 8, 12, 40, 30);
            for (Map<String, Object> field : customFields) {
                List<String> values = new ArrayList<>();
                for (Map<String, Object> value : list(field.get("possible_values"))) {
                    String label = text(value.get("label"));
                    String raw = text(value.get("value"));
                    values.add(label.isEmpty() || label.equals(raw) ? raw : label + " (" + raw + ")");
                }
                List<String> trackerList = new ArrayList<>();
                for (Map<String, Object> tracker : list(field.get("trackers"))) {
                    trackerList.add(text(tracker.get("name")));
                }
                sheet.addRow(longValue(field.get("id")), text(field.get("name")), text(field.get("customized_type")),
                        text(field.get("field_format")), field.get("is_required") instanceof Boolean b ? b : "",
                        field.get("multiple") instanceof Boolean m ? m : "", text(field.get("default_value")),
                        String.join("\n", values), String.join(", ", trackerList));
            }
            count(sheet);
        }
    }

    private void writeEnumeration(String name, List<Map<String, Object>> items) throws IOException {
        if (items == null) {
            return;
        }
        BackupSheet sheet = new BackupSheet(context, name, List.of("ID", "名前", "既定", "有効"), 8, 16, 8, 8);
        for (Map<String, Object> item : items) {
            sheet.addRow(longValue(item.get("id")), text(item.get("name")),
                    item.get("is_default") instanceof Boolean b ? b : "",
                    item.get("active") instanceof Boolean a ? a : "");
        }
        count(sheet);
    }

    private void writeInfo(SXSSFSheet sheet, String projectLabel, Map<String, Object> project, LocalDateTime startedAt,
            Duration elapsed, long issues) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] { "内容", "Redmine プロジェクトの全内容のバックアップ（読み取り専用。--sync では取り込めません）" });
        rows.add(new Object[] { "設定のプロジェクト名", projectLabel });
        rows.add(new Object[] { "プロジェクト識別子", client.getProjectId() });
        if (project != null) {
            rows.add(new Object[] { "プロジェクト名", text(project.get("name")) });
            rows.add(new Object[] { "プロジェクトID", orEmpty(longValue(project.get("id"))) });
            rows.add(new Object[] { "説明", text(project.get("description")) });
            rows.add(new Object[] { "ホームページ", text(project.get("homepage")) });
            rows.add(new Object[] { "親プロジェクト", nestedName(project, "parent") });
            rows.add(new Object[] { "公開", project.get("is_public") instanceof Boolean b ? (b ? "はい" : "いいえ") : "" });
            rows.add(new Object[] { "作成日時", dateTimeText(project.get("created_on")) });
            rows.add(new Object[] { "モジュール", joinNames(project.get("enabled_modules")) });
            rows.add(new Object[] { "トラッカー", joinNames(project.get("trackers")) });
            rows.add(new Object[] { "カスタムフィールド（プロジェクト）", cfSummary(project) });
        }
        rows.add(new Object[] { "Redmine URL", client.getBaseUrl() });
        rows.add(new Object[] { "実行日時", startedAt.format(INFO_TIME) + "（" + ZoneId.systemDefault() + "。シートの日時も同じ）" });
        rows.add(new Object[] { "所要時間（秒）", elapsed.toSeconds() });
        rows.add(new Object[] { "オプション", options.describe() });
        rows.add(new Object[] { "チケット", issues + " 件（クローズ済み・サブプロジェクトを含む）" });
        rows.add(new Object[] { "添付ファイル", options.attachments()
                ? attachmentsSaved + " 件を保存（" + attachmentBytes + " バイト）" + (attachmentsFailed > 0
                        ? "、" + attachmentsFailed + " 件は保存できません" : "") + "。保存先: " + filesPrefix
                : "ダウンロードしない設定" });
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            rows.add(new Object[] { "シート「" + entry.getKey() + "」", entry.getValue() + " 行" });
        }
        if (warnings.isEmpty()) {
            rows.add(new Object[] { "警告", "なし" });
        }
        for (String warning : warnings) {
            rows.add(new Object[] { "警告", warning });
        }
        rows.add(new Object[] { "復元について", "このファイルから Redmine へ自動では戻せません。必要な内容を見て手作業で登録してください"
                + "（チケットの階層は「親チケット」列、添付ファイルは「添付ファイル」シートの保存先を参照）。" });
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("項目");
        header.createCell(1).setCellValue("値");
        header.getCell(0).setCellStyle(context.header());
        header.getCell(1).setCellStyle(context.header());
        int index = 1;
        for (Object[] values : rows) {
            Row row = sheet.createRow(index++);
            row.createCell(0).setCellValue(values[0].toString());
            Object value = values[1];
            String textValue = value == null ? "" : value.toString();
            if (value instanceof Number number) {
                row.createCell(1).setCellValue(number.doubleValue());
            } else {
                row.createCell(1).setCellValue(textValue.length() > BackupSheet.EXCEL_MAX_CELL_CHARS
                        ? textValue.substring(0, BackupSheet.EXCEL_MAX_CELL_CHARS) : textValue);
            }
        }
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 120 * 256);
    }

    // ---------------------------------------------------------------- 取得の補助

    private <T> T optional(String label, Supplier<T> fetcher) {
        try {
            return fetcher.get();
        } catch (RuntimeException e) {
            warn(label + "を取得できません（" + describe(e) + "）。出力しません");
            return null;
        }
    }

    private List<Map<String, Object>> optionalList(String label, String sheet, String path, String key) {
        return optional(label + "（シート「" + sheet + "」）", () -> list(retry(() -> client.getJson(path, Map.of())).get(key)));
    }

    /** 通信エラー・5xx は最大3回まで再試行します（4xx はすぐに失敗）。 */
    private <T> T retry(Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= RETRIES; attempt++) {
            try {
                return action.get();
            } catch (HttpStatusCodeException e) {
                if (!e.getStatusCode().is5xxServerError()) {
                    throw e;
                }
                last = e;
            } catch (ResourceAccessException e) {
                last = e;
            }
            if (attempt < RETRIES) {
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    private void warn(String message) {
        warnings.add(message);
        logger.warn(message);
    }

    private void count(BackupSheet sheet) {
        counts.put(sheet.name(), sheet.totalRows());
        if (sheet.parts() > 1) {
            logger.info("  シート「" + sheet.name() + "」は行数が多いため " + sheet.parts() + " シートに分けました");
        }
        if (sheet.longTexts() > 0) {
            logger.info("  シート「" + sheet.name() + "」の " + sheet.longTexts() + " セルは長いため全文を " + filesPrefix
                    + "long_text/ に保存しました");
        }
    }

    static String describe(Throwable e) {
        if (e instanceof RestClientResponseException response) {
            int code = response.getStatusCode().value();
            String hint = code == 403 ? "。権限がないか、モジュールが無効です" : code == 404 ? "。モジュールが無効か、存在しません" : "";
            return "HTTP " + code + hint;
        }
        return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }

    // ---------------------------------------------------------------- 名前の解決

    private void fillLookups(List<Map<String, Object>> trackers, List<Map<String, Object>> statuses,
            List<Map<String, Object>> priorities, List<Map<String, Object>> versions,
            List<Map<String, Object>> categories, List<Map<String, Object>> customFields,
            Map<Long, Map<String, Object>> users, Map<Long, Map<String, Object>> groups) {
        putNames(trackerNames, trackers);
        putNames(statusNames, statuses);
        putNames(priorityNames, priorities);
        putNames(versionNames, versions);
        putNames(categoryNames, categories);
        if (users != null) {
            users.forEach((id, user) -> userNames.put(id,
                    (text(user.get("lastname")) + " " + text(user.get("firstname"))).trim()));
        }
        if (groups != null) {
            groups.forEach((id, group) -> userNames.put(id, text(group.get("name"))));
        }
        if (customFields != null) {
            for (Map<String, Object> field : customFields) {
                Long id = longValue(field.get("id"));
                if (id != null) {
                    customFieldDefs.put(id, field);
                }
            }
        }
    }

    private void collectIssueCustomFieldColumns(Map<String, Object> project, List<Map<String, Object>> customFields) {
        if (project != null) {
            for (Map<String, Object> field : list(project.get("issue_custom_fields"))) {
                Long id = longValue(field.get("id"));
                if (id != null) {
                    addCfColumn(id, text(field.get("name")));
                }
            }
        }
        if (customFields != null) {
            for (Map<String, Object> field : customFields) {
                Long id = longValue(field.get("id"));
                if (id != null && "issue".equals(text(field.get("customized_type")))) {
                    addCfColumn(id, text(field.get("name")));
                }
            }
        }
    }

    private void addCfColumn(Long id, String name) {
        if (issueCfColumns.containsKey(id)) {
            return;
        }
        String column = "CF:" + name;
        if (issueCfColumns.containsValue(column)) {
            column = column + "(#" + id + ")";
        }
        issueCfColumns.put(id, column);
    }

    private static void putNames(Map<String, String> target, List<Map<String, Object>> items) {
        if (items == null) {
            return;
        }
        for (Map<String, Object> item : items) {
            Long id = longValue(item.get("id"));
            if (id != null) {
                target.put(id.toString(), text(item.get("name")));
            }
        }
    }

    private String cfValue(Map<String, Object> cf) {
        Long id = longValue(cf.get("id"));
        Object value = cf.get("value");
        if (value instanceof Collection<?> values) {
            List<String> texts = new ArrayList<>();
            values.forEach(v -> texts.add(id == null ? text(v) : cfDisplay(id, text(v))));
            return String.join(", ", texts);
        }
        return id == null ? text(value) : cfDisplay(id, text(value));
    }

    /** カスタムフィールドの値を表示用にします（ユーザー・バージョン・キー/バリューリストは名前に。定義が取れない場合はそのまま）。 */
    private String cfDisplay(Long fieldId, String raw) {
        Map<String, Object> def = customFieldDefs.get(fieldId);
        if (def == null || raw.isEmpty()) {
            return raw;
        }
        String format = text(def.get("field_format"));
        switch (format) {
        case "user": {
            Long id = parseLong(raw);
            return id != null && userNames.containsKey(id) ? userNames.get(id) : raw;
        }
        case "version":
            return versionNames.getOrDefault(raw, raw);
        case "enumeration":
            for (Map<String, Object> value : list(def.get("possible_values"))) {
                if (raw.equals(text(value.get("value"))) && !text(value.get("label")).isEmpty()) {
                    return text(value.get("label"));
                }
            }
            return raw;
        case "bool":
            return "1".equals(raw) ? "はい" : "0".equals(raw) ? "いいえ" : raw;
        default:
            return raw;
        }
    }

    /** ユーザー・バージョンなどのカスタムフィールドを「名前: 値」の行にまとめます。 */
    private String cfSummary(Map<String, Object> item) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> cf : list(item.get("custom_fields"))) {
            String value = cfValue(cf);
            if (!value.isEmpty()) {
                lines.add(text(cf.get("name")) + ": " + value);
            }
        }
        return String.join("\n", lines);
    }

    private static String userStatus(Object status) {
        Long value = longValue(status);
        if (value == null) {
            return text(status);
        }
        return switch (value.intValue()) {
        case 1 -> "有効";
        case 2 -> "登録";
        case 3 -> "ロック";
        default -> value.toString();
        };
    }

    // ---------------------------------------------------------------- 値の変換

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String joinNames(Object value) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> item : list(value)) {
            names.add(text(item.get("name")));
        }
        return String.join(", ", names);
    }

    private static String nestedName(Map<String, Object> item, String key) {
        return item.get(key) instanceof Map<?, ?> nested ? text(nested.get("name")) : "";
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : parseLong(value.toString());
    }

    private static Long parseLong(String text) {
        try {
            return text == null || text.isBlank() ? null : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Object orEmpty(Object value) {
        return value == null ? "" : value;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Object date(Object value) {
        String text = text(value);
        if (text.isEmpty()) {
            return "";
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return text;
        }
    }

    /** Redmine の日時（例: {@code 2026-01-16T03:04:05Z}）を、この PC のタイムゾーンの日時にします。 */
    static Object dateTime(Object value) {
        String text = text(value);
        if (text.isEmpty()) {
            return "";
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException ignored) {
                return text;
            }
        }
    }

    private static String dateTimeText(Object value) {
        Object dateTime = dateTime(value);
        return dateTime instanceof LocalDateTime local ? local.format(INFO_TIME) : text(dateTime);
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static CellStyle format(SXSSFWorkbook workbook, String pattern) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(pattern));
        return style;
    }
}
