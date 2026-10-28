package mozaki.redmineUpster.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import mozaki.redmineUpster.config.SyncConfigProperties.ColumnsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ExcelConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.SyncConfig;
import mozaki.redmineUpster.util.ColumnDefinitions;

/**
 * Redmine のチケット・ユーザー・グループを Excel（.xlsx）に出力するクラス。
 * <p>
 * 出力したファイルはそのまま {@code --sync} の入力に使えます（シート「チケット」「ユーザー」「グループ」「グループメンバー」）。
 * 列名・階層列・シート名・テーブル名は同期と同じ設定（sync.columns / sync.excel / sync.users / sync.groups /
 * sync.groupMembers）に従います。
 * </p>
 * <p>
 * 参照用のシート「トラッカー」「ステータス」「カスタムフィールド」「カスタムフィールド選択肢」も出力します。
 * これらは見るためだけのシートで、{@code --sync} では読みません（値を変えても Redmine は変わりません）。
 * </p>
 */
@Component
public class WorkbookExporter {

    /** チケットの既定のシート名 */
    public static final String DEFAULT_TICKETS_SHEET = "チケット";
    /** 参照用: トラッカーのシート名 */
    public static final String TRACKERS_SHEET = "トラッカー";
    /** 参照用: ステータスのシート名 */
    public static final String STATUSES_SHEET = "ステータス";
    /** 参照用: カスタムフィールドのシート名 */
    public static final String CUSTOM_FIELDS_SHEET = "カスタムフィールド";
    /** 参照用: カスタムフィールドの選択肢のシート名 */
    public static final String CUSTOM_FIELD_VALUES_SHEET = "カスタムフィールド選択肢";

    /** 参照用シートの見出しの注記 */
    private static final String REFERENCE_NOTE = "（参照用。--sync では読みません）";

    /** トラッカーの標準フィールド（enabled_standard_fields のキー → 表示名。出力順） */
    static final Map<String, String> STANDARD_FIELD_LABELS;
    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("assigned_to_id", "担当者");
        labels.put("category_id", "カテゴリ");
        labels.put("fixed_version_id", "対象バージョン");
        labels.put("parent_issue_id", "親チケット");
        labels.put("start_date", "開始日");
        labels.put("due_date", "期日");
        labels.put("estimated_hours", "予定工数");
        labels.put("done_ratio", "進捗率");
        labels.put("description", "説明");
        labels.put("priority_id", "優先度");
        STANDARD_FIELD_LABELS = java.util.Collections.unmodifiableMap(labels);
    }

    /** カスタムフィールドの対象（customized_type → 表示名） */
    private static final Map<String, String> CUSTOMIZED_TYPE_LABELS = Map.of("issue", "チケット", "user", "ユーザー",
            "group", "グループ", "project", "プロジェクト", "version", "バージョン", "time_entry", "作業時間",
            "document", "文書", "issue_priority", "優先度", "time_entry_activity", "作業分類");

    /** カスタムフィールドの形式（field_format → 表示名） */
    private static final Map<String, String> FIELD_FORMAT_LABELS = Map.ofEntries(Map.entry("string", "文字列"),
            Map.entry("text", "長いテキスト"), Map.entry("int", "整数"), Map.entry("float", "小数"),
            Map.entry("date", "日付"), Map.entry("bool", "真偽値"), Map.entry("list", "リスト"),
            Map.entry("enumeration", "キー・バリュー リスト"), Map.entry("user", "ユーザー"),
            Map.entry("version", "バージョン"), Map.entry("link", "リンク"), Map.entry("attachment", "ファイル"));

    /**
     * 参照用のデータ（Redmine の設定。--sync では使わない）。
     *
     * @param trackers トラッカー（/trackers.json。null ならシートを作らない）
     * @param statuses ステータス（/issue_statuses.json。null ならシートを作らない）
     * @param customFields カスタムフィールド（/custom_fields.json。管理者のみ。null ならシートを作らない）
     */
    public record ReferenceData(List<Map<String, Object>> trackers, List<Map<String, Object>> statuses,
            List<Map<String, Object>> customFields) {
    }

    /**
     * 出力するデータ。
     *
     * @param issues チケット（null ならシートを作らない）
     * @param users ユーザー（null ならシートを作らない）
     * @param groups グループ（"users" にメンバー。null ならシートを作らない）
     * @param rootIssueId ルートチケットのID（null ならプロジェクトのすべてのチケット）。指定するとルートの配下だけを、
     *        ルートの子を最上位（階層の1列目）として出力する（ルート自身は出力しない）
     * @param reference 参照用のデータ（null なら参照用のシートを作らない）
     */
    public record ExportData(Map<Long, Map<String, Object>> issues, Map<Long, Map<String, Object>> users,
            Map<Long, Map<String, Object>> groups, Long rootIssueId, ReferenceData reference) {

        /**
         * 参照用のデータなしで作ります。
         */
        public ExportData(Map<Long, Map<String, Object>> issues, Map<Long, Map<String, Object>> users,
                Map<Long, Map<String, Object>> groups, Long rootIssueId) {
            this(issues, users, groups, rootIssueId, null);
        }

        /**
         * ルートチケットなし（プロジェクトのすべてのチケット）で作ります。
         */
        public ExportData(Map<Long, Map<String, Object>> issues, Map<Long, Map<String, Object>> users,
                Map<Long, Map<String, Object>> groups) {
            this(issues, users, groups, null);
        }
    }

    /**
     * 出力結果。
     *
     * @param warnings 警告（再取り込みで問題になりそうな点）
     * @param sheets 作成したシートと行数（シート名 → 行数）
     */
    public record ExportResult(List<String> warnings, Map<String, Integer> sheets) {
    }

    /**
     * Excel に出力します。
     *
     * @param out 出力先（.xlsx）
     * @param data データ
     * @param projectConfig プロジェクト設定（列名など）
     * @return 結果
     * @throws IOException 書き込みに失敗した場合、チケットの階層が階層列より深い場合
     */
    public ExportResult export(Path out, ExportData data, ProjectConfig projectConfig) throws IOException {
        return export(out, data, projectConfig, data.users(), data.groups());
    }

    /**
     * Excel に出力します（担当・メンバーの表示に使うユーザー・グループを別に渡す）。
     *
     * @param out 出力先（.xlsx）
     * @param data データ
     * @param projectConfig プロジェクト設定（列名など）
     * @param allUsers 担当・グループのメンバーをログインIDで表示するためのユーザー（null可）
     * @param allGroups 担当をグループ名で表示するためのグループ（null可）
     * @return 結果
     * @throws IOException 書き込みに失敗した場合、チケットの階層が階層列より深い場合
     */
    public ExportResult export(Path out, ExportData data, ProjectConfig projectConfig,
            Map<Long, Map<String, Object>> allUsers, Map<Long, Map<String, Object>> allGroups) throws IOException {
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> sheets = new LinkedHashMap<>();
        SyncConfig sync = projectConfig != null ? projectConfig.getSync() : null;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            if (data.issues() != null) {
                List<List<Object>> rows = new ArrayList<>();
                List<String> headers = ticketRows(data.issues(), data.rootIssueId(),
                        assigneeNames(allUsers, allGroups), projectConfig, rows, warnings);
                String name = sheetName(sync != null ? sync.getExcel() : null, DEFAULT_TICKETS_SHEET);
                writeSheet(workbook, styles, name, tableName(sync != null ? sync.getExcel() : null), headers, rows);
                sheets.put(name, rows.size());
            }
            if (data.users() != null) {
                List<List<Object>> rows = new ArrayList<>();
                Map<String, CustomFieldColumns.Definition> cf = CustomFieldColumns.definitions(data.users().values());
                for (Map.Entry<Long, Map<String, Object>> entry : sortedById(data.users())) {
                    Map<String, Object> user = entry.getValue();
                    Long status = user.get("status") instanceof Number n ? n.longValue() : null;
                    List<Object> row = new ArrayList<>(List.of(entry.getKey(), text(user.get("login")),
                            text(user.get("lastname")), text(user.get("firstname")), text(user.get("mail")),
                            Boolean.TRUE.equals(user.get("admin")) ? "はい" : "いいえ",
                            status == null ? "" : DirectorySync.STATUS_LABELS.getOrDefault(status.intValue(), ""),
                            ""));
                    cf.values().forEach(d -> row.add(CustomFieldColumns.exportValue(user, d.id())));
                    rows.add(row);
                }
                String name = sheetName(sync != null ? sync.getUsers() : null, DirectorySync.DEFAULT_USERS_SHEET);
                writeSheet(workbook, styles, name, tableName(sync != null ? sync.getUsers() : null),
                        withCustomFields(DirectorySync.USER_COLUMNS, cf), rows);
                sheets.put(name, rows.size());
            }
            if (data.groups() != null) {
                Map<Long, Map<String, Object>> users = allUsers != null ? allUsers : Map.of();
                List<List<Object>> rows = new ArrayList<>();
                List<List<Object>> memberRows = new ArrayList<>();
                Map<String, CustomFieldColumns.Definition> cf = CustomFieldColumns.definitions(data.groups().values());
                for (Map.Entry<Long, Map<String, Object>> entry : sortedById(data.groups())) {
                    String groupName = text(entry.getValue().get("name"));
                    List<String> members = DirectorySync.currentMemberLogins(entry.getValue(), users, false);
                    if (members.stream().anyMatch(m -> m.startsWith("#"))) {
                        warnings.add("グループ「" + groupName
                                + "」のメンバーにログインIDが分からないユーザーがあります（#ID で出力。取り込み前に直してください）");
                    }
                    List<Object> row = new ArrayList<>(List.of(entry.getKey(), groupName));
                    cf.values().forEach(d -> row.add(CustomFieldColumns.exportValue(entry.getValue(), d.id())));
                    rows.add(row);
                    // メンバーは1行に1人。メンバーがいないグループはログインIDが空欄の行（取り込むとメンバー0人のまま）
                    if (members.isEmpty()) {
                        memberRows.add(List.of(groupName, "", ""));
                    }
                    for (String login : members) {
                        memberRows.add(List.of(groupName, login, memberName(login, users)));
                    }
                }
                String name = sheetName(sync != null ? sync.getGroups() : null, DirectorySync.DEFAULT_GROUPS_SHEET);
                writeSheet(workbook, styles, name, tableName(sync != null ? sync.getGroups() : null),
                        withCustomFields(DirectorySync.GROUP_COLUMNS, cf), rows);
                sheets.put(name, rows.size());
                String membersName = sheetName(sync != null ? sync.getGroupMembers() : null,
                        DirectorySync.DEFAULT_GROUP_MEMBERS_SHEET);
                writeSheet(workbook, styles, membersName, tableName(sync != null ? sync.getGroupMembers() : null),
                        DirectorySync.GROUP_MEMBER_COLUMNS, memberRows);
                sheets.put(membersName, memberRows.size());
            }
            if (data.reference() != null) {
                writeReferenceSheets(workbook, styles, data.reference(), sheets);
            }
            if (workbook.getNumberOfSheets() == 0) {
                throw new IOException("出力するデータがありません");
            }
            try (OutputStream os = Files.newOutputStream(out)) {
                workbook.write(os);
            }
        }
        return new ExportResult(warnings, sheets);
    }

    /**
     * チケットの行を作ります（親 → 子の順）。
     * <p>
     * ルートチケットを指定した場合は、ルートの配下（子孫）だけを出力し、階層はルートの子を1階層目として数えます。
     * </p>
     *
     * @return ヘッダ
     */
    private List<String> ticketRows(Map<Long, Map<String, Object>> allIssues, Long rootIssueId,
            Map<Long, String> assigneeNames, ProjectConfig projectConfig, List<List<Object>> rows,
            List<String> warnings) throws IOException {
        Map<Long, Map<String, Object>> issues = rootIssueId == null ? allIssues
                : descendants(allIssues, rootIssueId);
        SyncConfig sync = projectConfig != null ? projectConfig.getSync() : null;
        ColumnsConfig columns = sync != null && sync.getColumns() != null ? sync.getColumns() : new ColumnsConfig();
        List<String> hierarchy = DiffCalculator.resolveHierarchyColumns(projectConfig, List.of()).columns();
        TicketColumns names = TicketColumns.of(columns);
        String idColumn = names.ticketId();
        String trackerColumn = names.tracker();
        String statusColumn = names.status();
        String assigneeColumn = names.assignee();
        String startColumn = names.startDate();
        String dueColumn = names.dueDate();
        String progressColumn = names.progress();
        String descriptionColumn = names.description();

        Set<String> headers = new LinkedHashSet<>();
        headers.add(idColumn);
        headers.add(trackerColumn);
        headers.addAll(hierarchy);
        headers.add(statusColumn);
        headers.add(assigneeColumn);
        headers.add(startColumn);
        headers.add(dueColumn);
        headers.add(progressColumn);
        headers.add(descriptionColumn);
        // カスタムフィールド（customFieldMap に対応がある列だけ。階層列などと同じ名前の列は出力しない）
        Map<String, String> customFieldMap = sync != null && sync.getCustomFieldMap() != null
                ? sync.getCustomFieldMap() : Map.of();
        List<String> customFieldColumns = columns.getCustomFieldColumns() != null
                && !columns.getCustomFieldColumns().isEmpty() ? columns.getCustomFieldColumns()
                        : ColumnDefinitions.CUSTOM_FIELD_COLUMNS;
        Map<String, String> exportedCustomFields = new LinkedHashMap<>();
        for (String column : customFieldColumns) {
            String field = customFieldMap.get(column);
            if (field == null || field.isBlank()) {
                continue;
            }
            if (headers.contains(column)) {
                warnings.add("カスタムフィールドの列「" + column + "」は階層などの列と同じ名前のため出力しません");
                continue;
            }
            headers.add(column);
            exportedCustomFields.put(column, field);
        }
        // それ以外のカスタムフィールドは「CF:名前」列で出力する（そのまま取り込める）
        Map<String, CustomFieldColumns.Definition> cfColumns = new LinkedHashMap<>();
        for (CustomFieldColumns.Definition definition : CustomFieldColumns.definitions(issues.values()).values()) {
            boolean mapped = exportedCustomFields.values().stream().anyMatch(
                    f -> f.equals(String.valueOf(definition.id())) || f.equals(definition.name()));
            if (!mapped && headers.add(CustomFieldColumns.PREFIX + definition.name())) {
                cfColumns.put(CustomFieldColumns.PREFIX + definition.name(), definition);
            }
        }

        Map<Long, List<Long>> children = new HashMap<>();
        List<Long> roots = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Object>> entry : sortedById(issues)) {
            Long parent = IssueComparator.nestedId(entry.getValue(), "parent");
            if (parent != null && issues.containsKey(parent)) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(entry.getKey());
            } else if (rootIssueId == null || rootIssueId.equals(parent)) {
                roots.add(entry.getKey());
            }
        }
        List<Object[]> ordered = new ArrayList<>(); // {issueId, path}
        for (Long root : roots) {
            walk(root, new ArrayList<>(), issues, children, ordered);
        }
        int maxDepth = ordered.stream().mapToInt(o -> ((List<?>) o[1]).size()).max().orElse(0);
        if (maxDepth > hierarchy.size()) {
            String target = rootIssueId == null ? "Redmine のチケットの親子" : "ルートチケット #" + rootIssueId + " の配下";
            throw new IOException(target + "が " + maxDepth + " 階層あり、階層列 " + hierarchy + "（"
                    + hierarchy.size() + " 列）より深いため出力できません。sync-config.yml の columns.hierarchy に列を追加してください");
        }

        Map<String, Integer> pathRows = new HashMap<>();
        for (Object[] entry : ordered) {
            Long id = (Long) entry[0];
            @SuppressWarnings("unchecked")
            List<String> path = (List<String>) entry[1];
            Map<String, Object> issue = issues.get(id);
            Map<String, Object> values = new HashMap<>();
            values.put(idColumn, id);
            values.put(trackerColumn, nestedText(issue, "tracker", "name"));
            for (int i = 0; i < path.size(); i++) {
                values.put(hierarchy.get(i), path.get(i));
            }
            values.put(statusColumn, nestedText(issue, "status", "name"));
            Long assignee = IssueComparator.nestedId(issue, "assigned_to");
            if (assignee != null) {
                values.put(assigneeColumn, assigneeNames.getOrDefault(assignee, String.valueOf(assignee)));
            }
            values.put(startColumn, date(issue.get("start_date")));
            values.put(dueColumn, date(issue.get("due_date")));
            if (issue.get("done_ratio") instanceof Number ratio) {
                values.put(progressColumn, ratio.longValue());
            }
            // 説明は Markdown などをそのまま出力（Excel のセル内改行は LF）
            Object description = issue.get("description");
            if (description != null) {
                values.put(descriptionColumn, String.valueOf(description).replace("\r\n", "\n").replace('\r', '\n'));
            }
            for (Map.Entry<String, CustomFieldColumns.Definition> field : cfColumns.entrySet()) {
                values.put(field.getKey(), CustomFieldColumns.exportValue(issue, field.getValue().id()));
            }
            for (Map.Entry<String, String> field : exportedCustomFields.entrySet()) {
                values.put(field.getKey(), customFieldValue(issue, field.getValue()));
            }
            List<Object> row = new ArrayList<>();
            for (String header : headers) {
                Object value = values.get(header);
                row.add(value == null ? "" : value);
            }
            String key = String.join("\u001F", path);
            Integer same = pathRows.putIfAbsent(key, rows.size());
            if (same != null) {
                warnings.add("チケット #" + id + " [" + String.join(" > ", path) + "] は同じ親の下に同じ件名のチケットがあります"
                        + "（#" + rows.get(same).get(0) + "）。取り込むと階層の重複エラーになるので、どちらかの件名を変えてください");
            }
            rows.add(row);
        }
        return new ArrayList<>(headers);
    }

    /**
     * ルートチケットの子孫（ルート自身は含まない）を、元の順のまま返します。親IDをたどって探します。
     *
     * @param issues プロジェクトのチケット
     * @param rootIssueId ルートチケットのID（プロジェクト外のチケットでもよい）
     * @return 子孫のチケット
     */
    static Map<Long, Map<String, Object>> descendants(Map<Long, Map<String, Object>> issues, Long rootIssueId) {
        Map<Long, List<Long>> children = new HashMap<>();
        for (Map.Entry<Long, Map<String, Object>> entry : issues.entrySet()) {
            Long parent = IssueComparator.nestedId(entry.getValue(), "parent");
            if (parent != null) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(entry.getKey());
            }
        }
        Set<Long> found = new LinkedHashSet<>();
        List<Long> queue = new ArrayList<>(children.getOrDefault(rootIssueId, List.of()));
        while (!queue.isEmpty()) {
            Long id = queue.remove(queue.size() - 1);
            // 親子が循環していても止まるように、見つけ済みのチケットはたどらない
            if (!id.equals(rootIssueId) && found.add(id)) {
                queue.addAll(children.getOrDefault(id, List.of()));
            }
        }
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        issues.forEach((id, issue) -> {
            if (found.contains(id)) {
                result.put(id, issue);
            }
        });
        return result;
    }

    private static void walk(Long id, List<String> parentPath, Map<Long, Map<String, Object>> issues,
            Map<Long, List<Long>> children, List<Object[]> ordered) {
        List<String> path = new ArrayList<>(parentPath);
        path.add(text(issues.get(id).get("subject")));
        ordered.add(new Object[] { id, path });
        for (Long child : children.getOrDefault(id, List.of())) {
            walk(child, path, issues, children, ordered);
        }
    }

    /**
     * 担当の表示（ユーザーはログインID、グループはグループ名。分からなければID）。
     */
    private static Map<Long, String> assigneeNames(Map<Long, Map<String, Object>> users,
            Map<Long, Map<String, Object>> groups) {
        Map<Long, String> names = new HashMap<>();
        if (groups != null) {
            groups.forEach((id, group) -> {
                if (!text(group.get("name")).isEmpty()) {
                    names.put(id, text(group.get("name")));
                }
            });
        }
        if (users != null) {
            users.forEach((id, user) -> {
                if (!text(user.get("login")).isEmpty()) {
                    names.put(id, text(user.get("login")));
                }
            });
        }
        return names;
    }

    /**
     * 参照用のシート（トラッカー・ステータス・カスタムフィールド・選択肢）を出力します。
     */
    private static void writeReferenceSheets(XSSFWorkbook workbook, Styles styles, ReferenceData reference,
            Map<String, Integer> sheets) {
        List<Map<String, Object>> trackers = reference.trackers();
        if (trackers != null && !trackers.isEmpty()) {
            // 標準フィールドの列は、Redmine が enabled_standard_fields を返す場合（Redmine 5 以降）だけ出力する
            boolean hasFields = trackers.stream().anyMatch(t -> t.get("enabled_standard_fields") instanceof List<?>);
            List<String> fieldKeys = new ArrayList<>(STANDARD_FIELD_LABELS.keySet());
            if (hasFields) {
                for (Map<String, Object> tracker : trackers) {
                    if (tracker.get("enabled_standard_fields") instanceof List<?> fields) {
                        fields.forEach(f -> {
                            if (!fieldKeys.contains(text(f))) {
                                fieldKeys.add(text(f));
                            }
                        });
                    }
                }
            }
            List<String> headers = new ArrayList<>(List.of("ID", "トラッカー名", "既定のステータス", "説明"));
            if (hasFields) {
                fieldKeys.forEach(key -> headers.add(STANDARD_FIELD_LABELS.getOrDefault(key, key)));
            }
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> tracker : trackers) {
                List<Object> row = new ArrayList<>(List.of(id(tracker), text(tracker.get("name")),
                        nestedText(tracker, "default_status", "name"), text(tracker.get("description"))));
                if (hasFields) {
                    List<String> enabled = new ArrayList<>();
                    if (tracker.get("enabled_standard_fields") instanceof List<?> fields) {
                        fields.forEach(f -> enabled.add(text(f)));
                    }
                    fieldKeys.forEach(key -> row.add(enabled.contains(key) ? "○" : ""));
                }
                rows.add(row);
            }
            writeSheet(workbook, styles, TRACKERS_SHEET, null, headers, rows);
            note(workbook, TRACKERS_SHEET, hasFields
                    ? "○＝このトラッカーで使う標準フィールド" + REFERENCE_NOTE : REFERENCE_NOTE);
            sheets.put(TRACKERS_SHEET, rows.size());
        }
        List<Map<String, Object>> statuses = reference.statuses();
        if (statuses != null && !statuses.isEmpty()) {
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> status : statuses) {
                rows.add(List.of(id(status), text(status.get("name")),
                        Boolean.TRUE.equals(status.get("is_closed")) ? "はい" : "いいえ"));
            }
            writeSheet(workbook, styles, STATUSES_SHEET, null, List.of("ID", "ステータス名", "終了扱い"), rows);
            note(workbook, STATUSES_SHEET, REFERENCE_NOTE);
            sheets.put(STATUSES_SHEET, rows.size());
        }
        List<Map<String, Object>> customFields = reference.customFields();
        if (customFields != null && !customFields.isEmpty()) {
            // トラッカーごとの列（チケットのカスタムフィールドを使うトラッカーに ○）
            List<String> trackerNames = new ArrayList<>();
            if (trackers != null) {
                trackers.forEach(t -> trackerNames.add(text(t.get("name"))));
            }
            for (Map<String, Object> field : customFields) {
                for (String name : fieldTrackerNames(field)) {
                    if (!trackerNames.contains(name)) {
                        trackerNames.add(name);
                    }
                }
            }
            List<String> headers = new ArrayList<>(List.of("ID", "名前", "取込列名", "対象", "形式", "複数選択", "必須",
                    "既定値", "選択肢の数"));
            trackerNames.forEach(name -> headers.add("トラッカー:" + name));
            List<List<Object>> rows = new ArrayList<>();
            List<List<Object>> valueRows = new ArrayList<>();
            for (Map<String, Object> field : customFields) {
                String type = text(field.get("customized_type"));
                String format = text(field.get("field_format"));
                String name = text(field.get("name"));
                List<Map<?, ?>> values = possibleValues(field);
                boolean importable = "issue".equals(type) || "user".equals(type) || "group".equals(type);
                List<Object> row = new ArrayList<>(List.of(id(field), name,
                        importable ? CustomFieldColumns.PREFIX + name : "",
                        CUSTOMIZED_TYPE_LABELS.getOrDefault(type, type), FIELD_FORMAT_LABELS.getOrDefault(format, format),
                        Boolean.TRUE.equals(field.get("multiple")) ? "はい" : "いいえ",
                        Boolean.TRUE.equals(field.get("is_required")) ? "はい" : "いいえ",
                        text(field.get("default_value")), values.isEmpty() ? "" : (Object) (long) values.size()));
                List<String> enabledTrackers = fieldTrackerNames(field);
                trackerNames.forEach(t -> row.add("issue".equals(type) && enabledTrackers.contains(t) ? "○" : ""));
                rows.add(row);
                for (Map<?, ?> value : values) {
                    String raw = text(value.get("value"));
                    String label = value.containsKey("label") ? text(value.get("label")) : raw;
                    valueRows.add(List.of(id(field), name, CUSTOMIZED_TYPE_LABELS.getOrDefault(type, type), raw, label));
                }
            }
            writeSheet(workbook, styles, CUSTOM_FIELDS_SHEET, null, headers, rows);
            note(workbook, CUSTOM_FIELDS_SHEET, "取込列名＝チケット・ユーザー・グループのシートにこの見出しの列を作ると取り込める。"
                    + "トラッカーの列の ○＝そのトラッカーで使う" + REFERENCE_NOTE);
            sheets.put(CUSTOM_FIELDS_SHEET, rows.size());
            writeSheet(workbook, styles, CUSTOM_FIELD_VALUES_SHEET, null,
                    List.of("カスタムフィールドID", "カスタムフィールド名", "対象", "値", "表示名"), valueRows);
            note(workbook, CUSTOM_FIELD_VALUES_SHEET, "セルには「値」を書く（キー・バリュー リストは表示名も可）" + REFERENCE_NOTE);
            sheets.put(CUSTOM_FIELD_VALUES_SHEET, valueRows.size());
        }
    }

    /**
     * 表の右（1列空けた見出し行）に注記を書きます。
     */
    private static void note(XSSFWorkbook workbook, String sheetName, String text) {
        XSSFSheet sheet = workbook.getSheet(sheetName);
        Row header = sheet.getRow(0);
        int column = Math.max(header.getLastCellNum(), 0) + 1;
        header.createCell(column).setCellValue(text);
    }

    private static List<Map<?, ?>> possibleValues(Map<String, Object> field) {
        List<Map<?, ?>> values = new ArrayList<>();
        if (field.get("possible_values") instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> value) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static List<String> fieldTrackerNames(Map<String, Object> field) {
        List<String> names = new ArrayList<>();
        if (field.get("trackers") instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> tracker) {
                    names.add(text(tracker.get("name")));
                }
            }
        }
        return names;
    }

    private static Object id(Map<String, Object> map) {
        return map.get("id") instanceof Number n ? (Object) n.longValue() : text(map.get("id"));
    }

    /**
     * グループメンバーの氏名（参照用。分からなければ空欄）。
     */
    private static String memberName(String login, Map<Long, Map<String, Object>> users) {
        for (Map<String, Object> user : users.values()) {
            if (login.equalsIgnoreCase(text(user.get("login")))) {
                return (text(user.get("lastname")) + " " + text(user.get("firstname"))).trim();
            }
        }
        return "";
    }

    private static Object customFieldValue(Map<String, Object> issue, String field) {
        if (!(issue.get("custom_fields") instanceof List<?> list)) {
            return "";
        }
        boolean byId = field.chars().allMatch(Character::isDigit);
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> cf)) {
                continue;
            }
            boolean match = byId ? cf.get("id") instanceof Number n && String.valueOf(n.longValue()).equals(field)
                    : field.equals(text(cf.get("name")));
            if (match) {
                Object value = cf.get("value");
                if (value instanceof Collection<?> values) {
                    List<String> texts = new ArrayList<>();
                    values.forEach(v -> texts.add(text(v)));
                    return String.join(", ", texts);
                }
                return text(value);
            }
        }
        return "";
    }

    private static Object date(Object value) {
        String text = text(value);
        if (text.isEmpty()) {
            return "";
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ex) {
            return text;
        }
    }

    private static void writeSheet(XSSFWorkbook workbook, Styles styles, String name, String tableName,
            List<String> headers, List<List<Object>> rows) {
        XSSFSheet sheet = workbook.createSheet(name);
        Row headerRow = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(headers.get(c));
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(c, 14 * 256);
        }
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r + 1);
            List<Object> values = rows.get(r);
            for (int c = 0; c < values.size(); c++) {
                Object value = values.get(c);
                Cell cell = row.createCell(c);
                if (value instanceof Number number) {
                    cell.setCellValue(number.doubleValue());
                } else if (value instanceof LocalDate date) {
                    cell.setCellValue(date);
                    cell.setCellStyle(styles.date);
                } else {
                    String text = String.valueOf(value);
                    cell.setCellValue(text);
                    if (text.indexOf('\n') >= 0) {
                        cell.setCellStyle(styles.wrap);
                    }
                }
            }
        }
        sheet.createFreezePane(0, 1);
        if (tableName != null) {
            // テーブルは見出し行＋1行以上が必要
            int lastRow = Math.max(rows.size(), 1);
            if (rows.isEmpty()) {
                sheet.createRow(1);
            }
            AreaReference area = new AreaReference(new CellReference(0, 0),
                    new CellReference(lastRow, headers.size() - 1), SpreadsheetVersion.EXCEL2007);
            XSSFTable table = sheet.createTable(area);
            table.setName(tableName);
            table.setDisplayName(tableName);
            table.getCTTable().addNewTableStyleInfo().setName("TableStyleMedium2");
            table.getCTTable().getTableStyleInfo().setShowRowStripes(true);
        } else {
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(rows.size(), 0), 0,
                    headers.size() - 1));
        }
    }

    private static List<String> withCustomFields(List<String> columns,
            Map<String, CustomFieldColumns.Definition> definitions) {
        List<String> headers = new ArrayList<>(columns);
        headers.addAll(CustomFieldColumns.headers(definitions));
        return headers;
    }

    private static String sheetName(ExcelConfig config, String defaultName) {
        if (config != null && config.getSheet() != null && !config.getSheet().isBlank()
                && !config.getSheet().chars().allMatch(Character::isDigit)) {
            return config.getSheet().trim();
        }
        return defaultName;
    }

    private static String tableName(ExcelConfig config) {
        return config != null && config.getTable() != null && !config.getTable().isBlank() ? config.getTable().trim()
                : null;
    }

    private static List<Map.Entry<Long, Map<String, Object>>> sortedById(Map<Long, Map<String, Object>> map) {
        List<Map.Entry<Long, Map<String, Object>>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        return entries;
    }

    private static String nestedText(Map<String, Object> issue, String key, String field) {
        return issue.get(key) instanceof Map<?, ?> map ? text(map.get(field)) : "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static final class Styles {
        private final CellStyle header;
        private final CellStyle date;
        private final CellStyle wrap;

        private Styles(XSSFWorkbook workbook) {
            header = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);
            date = workbook.createCellStyle();
            date.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy/mm/dd"));
            wrap = workbook.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);
        }
    }
}
