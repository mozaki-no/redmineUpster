package mozaki.redmineUpster.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet;

/**
 * ユーザー・グループの Upsert（Excel のシート「ユーザー」「グループ」→ Redmine）。
 * <p>
 * ID 列が空欄の行は、同じログインID（グループはグループ名）の既存ユーザー・グループがあればそれを更新し、
 * なければ新規作成します。どちらの場合も ID を Excel に書き戻します。
 * 空欄のセルは「変更しない」の意味です。Excel から消えた行のユーザー・グループは削除もロックもしません
 * （ロックは「状態」列に「ロック」と書いた場合だけ行います）。
 * ユーザー・グループの取得・変更には Redmine の管理者の API キーが必要です。
 * </p>
 */
@Component
public class DirectorySync {

    /** ユーザーの既定のシート名 */
    public static final String DEFAULT_USERS_SHEET = "ユーザー";
    /** グループの既定のシート名 */
    public static final String DEFAULT_GROUPS_SHEET = "グループ";

    /** ID 列（ユーザー・グループ共通。空欄＝新規作成または名前で既存を検索） */
    public static final String COL_ID = "ID";
    public static final String COL_LOGIN = "ログインID";
    public static final String COL_LASTNAME = "姓";
    public static final String COL_FIRSTNAME = "名";
    public static final String COL_MAIL = "メールアドレス";
    public static final String COL_ADMIN = "管理者";
    public static final String COL_STATUS = "状態";
    /** 新規作成時だけ使うパスワード（空欄なら Redmine が自動生成してメールで通知） */
    public static final String COL_PASSWORD = "パスワード";
    public static final String COL_GROUP_NAME = "グループ名";
    /** グループのメンバー（ログインIDをカンマ・改行区切り。空欄なら変更しない） */
    public static final String COL_MEMBERS = "メンバー";

    /** ユーザーの列（出力順） */
    public static final List<String> USER_COLUMNS = List.of(COL_ID, COL_LOGIN, COL_LASTNAME, COL_FIRSTNAME, COL_MAIL,
            COL_ADMIN, COL_STATUS, COL_PASSWORD);
    /** グループの列（出力順） */
    public static final List<String> GROUP_COLUMNS = List.of(COL_ID, COL_GROUP_NAME, COL_MEMBERS);

    /** 状態の表示名（1=有効, 2=登録, 3=ロック） */
    public static final Map<Integer, String> STATUS_LABELS = Map.of(1, "有効", 2, "登録", 3, "ロック");

    /**
     * ユーザーの行。
     *
     * @param rowNumber 行番号
     * @param id 既存ユーザーのID（ID列の値、またはログインIDで見つかったID。新規作成はnull）
     * @param matchedByName ID列が空欄で、ログインIDで既存ユーザーが見つかった場合 true（IDを書き戻す）
     * @param login ログインID
     * @param lastname 姓（空欄＝変更しない）
     * @param firstname 名（空欄＝変更しない）
     * @param mail メールアドレス（空欄＝変更しない）
     * @param admin 管理者（null＝変更しない）
     * @param status 状態（null＝変更しない）
     * @param password 新規作成時のパスワード（空欄なら自動生成）
     * @param customFields 「CF:名前」列の値（{@code {id, value}}。空欄の列は含まない）
     */
    public record UserRow(int rowNumber, Long id, boolean matchedByName, String login, String lastname,
            String firstname, String mail, Boolean admin, Integer status, String password,
            List<Map<String, Object>> customFields) {
        public UserRow(int rowNumber, Long id, boolean matchedByName, String login, String lastname,
                String firstname, String mail, Boolean admin, Integer status, String password) {
            this(rowNumber, id, matchedByName, login, lastname, firstname, mail, admin, status, password, List.of());
        }

        String label() {
            return "ユーザー 行" + rowNumber + (id != null ? " #" + id : "") + " [" + login + "]";
        }
    }

    /**
     * グループの行。
     *
     * @param rowNumber 行番号
     * @param id 既存グループのID（新規作成はnull）
     * @param matchedByName ID列が空欄で、グループ名で既存グループが見つかった場合 true（IDを書き戻す）
     * @param name グループ名
     * @param members メンバーのログインID（null＝変更しない）
     * @param customFields 「CF:名前」列の値（{@code {id, value}}。空欄の列は含まない）
     */
    public record GroupRow(int rowNumber, Long id, boolean matchedByName, String name, List<String> members,
            List<Map<String, Object>> customFields) {
        public GroupRow(int rowNumber, Long id, boolean matchedByName, String name, List<String> members) {
            this(rowNumber, id, matchedByName, name, members, List.of());
        }

        String label() {
            return "グループ 行" + rowNumber + (id != null ? " #" + id : "") + " [" + name + "]";
        }
    }

    /**
     * 解析・検証の結果。
     *
     * @param <T> 行の型
     * @param rows 行
     * @param errors 検証エラー（あれば Redmine に書き込まない）
     */
    public record Plan<T>(List<T> rows, List<String> errors) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /**
     * 同期結果。
     *
     * @param writeBackIds 行番号 → ID（新規作成したもの、名前で既存を見つけたもの。Excel に書き戻す）
     * @param created 作成件数
     * @param updated 更新件数
     * @param unchanged 変更なし件数
     * @param errors 失敗した行
     */
    public record Result(Map<Integer, Long> writeBackIds, int created, int updated, int unchanged,
            List<String> errors) {
    }

    // ---------------------------------------------------------------- ユーザー

    /**
     * ユーザーのシートを解析・検証します。
     *
     * @param sheet シート
     * @param existingUsers Redmine のユーザー（ID → ユーザー）
     * @return 解析結果
     */
    public static Plan<UserRow> parseUsers(ParsedSheet sheet, Map<Long, Map<String, Object>> existingUsers) {
        List<String> errors = new ArrayList<>();
        List<UserRow> rows = new ArrayList<>();
        requireColumns(sheet, List.of(COL_ID, COL_LOGIN), "ユーザー", errors);
        if (!errors.isEmpty()) {
            return new Plan<>(rows, errors);
        }
        Map<String, Long> byLogin = loginIndex(existingUsers);
        Map<String, CustomFieldColumns.Definition> userFields = CustomFieldColumns.definitions(existingUsers.values());
        Map<String, Integer> seenLogins = new HashMap<>();
        Map<Long, Integer> seenIds = new HashMap<>();
        for (int i = 0; i < sheet.rows().size(); i++) {
            Map<String, String> row = sheet.rows().get(i);
            int rowNumber = sheet.rowNumbers().get(i);
            String prefix = "ユーザー 行" + rowNumber + ": ";
            String rawId = value(row, COL_ID);
            String login = value(row, COL_LOGIN);
            if (rawId.isEmpty() && login.isEmpty()) {
                if (!isBlankRow(row)) {
                    errors.add(prefix + "ID とログインIDがどちらも空欄です");
                }
                continue;
            }
            Long id = null;
            boolean matchedByName = false;
            if (!rawId.isEmpty()) {
                id = parseId(rawId);
                if (id == null) {
                    errors.add(prefix + "ID が数値ではありません: " + rawId);
                    continue;
                }
                if (!existingUsers.containsKey(id)) {
                    errors.add(prefix + "ユーザー #" + id + " が Redmine にありません（新規作成なら ID を空欄に）");
                    continue;
                }
            } else {
                id = byLogin.get(login.toLowerCase(Locale.ROOT));
                matchedByName = id != null;
            }
            if (login.isEmpty() && id != null) {
                login = text(existingUsers.get(id).get("login"));
            }
            Boolean admin;
            Integer status;
            try {
                admin = parseAdmin(value(row, COL_ADMIN));
                status = parseStatus(value(row, COL_STATUS));
            } catch (IllegalArgumentException ex) {
                errors.add(prefix + ex.getMessage());
                continue;
            }
            List<Map<String, Object>> customFields = new ArrayList<>();
            List<String> unknownFields = customFieldCells(row, userFields, customFields);
            if (!unknownFields.isEmpty()) {
                errors.add(prefix + "ユーザーのカスタムフィールドが Redmine にありません: " + unknownFields);
                continue;
            }
            UserRow user = new UserRow(rowNumber, id, matchedByName, login, value(row, COL_LASTNAME),
                    value(row, COL_FIRSTNAME), value(row, COL_MAIL), admin, status, value(row, COL_PASSWORD),
                    customFields);
            if (id == null) {
                List<String> missing = new ArrayList<>();
                for (String column : List.of(COL_LASTNAME, COL_FIRSTNAME, COL_MAIL)) {
                    if (value(row, column).isEmpty()) {
                        missing.add(column);
                    }
                }
                if (!missing.isEmpty()) {
                    errors.add(prefix + "新規作成には " + String.join("・", missing) + " が必要です [" + login + "]");
                    continue;
                }
            }
            Integer sameLogin = seenLogins.putIfAbsent(login.toLowerCase(Locale.ROOT), rowNumber);
            if (sameLogin != null) {
                errors.add(prefix + "ログインID「" + login + "」が行" + sameLogin + "と重複しています");
                continue;
            }
            if (id != null) {
                Integer sameId = seenIds.putIfAbsent(id, rowNumber);
                if (sameId != null) {
                    errors.add(prefix + "ユーザー #" + id + " が行" + sameId + "と重複しています");
                    continue;
                }
            }
            rows.add(user);
        }
        return new Plan<>(rows, errors);
    }

    /**
     * ユーザーを Upsert します。
     *
     * @param rows 検証済みの行
     * @param existingUsers Redmine のユーザー（作成したユーザーはここに追加する）
     * @param client クライアント
     * @param dryRun ドライランなら true
     * @param logger ロガー
     * @return 結果
     */
    public Result syncUsers(List<UserRow> rows, Map<Long, Map<String, Object>> existingUsers, RedmineClient client,
            boolean dryRun, FileLogger logger) {
        Map<Integer, Long> writeBack = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (UserRow row : rows) {
            try {
                if (row.id() == null) {
                    Map<String, Object> payload = createUserPayload(row);
                    if (dryRun) {
                        logger.info("DRY_RUN CREATE " + row.label());
                        created++;
                        continue;
                    }
                    Long newId = client.createUser(payload);
                    if (newId == null) {
                        addError(errors, logger, "作成失敗: " + row.label() + " 理由=レスポンスにIDがありません");
                        continue;
                    }
                    Map<String, Object> user = new LinkedHashMap<>(payload);
                    user.put("id", newId);
                    existingUsers.put(newId, user);
                    writeBack.put(row.rowNumber(), newId);
                    logger.info("created " + row.label() + " -> #" + newId
                            + (row.password().isEmpty() ? "（パスワードは自動生成してメールで通知）" : ""));
                    created++;
                    continue;
                }
                if (row.matchedByName()) {
                    writeBack.put(row.rowNumber(), row.id());
                }
                Map<String, Object> changes = userChanges(row, existingUsers.get(row.id()));
                if (changes.isEmpty()) {
                    logger.debug("skipped update " + row.label() + " (no changes)");
                    unchanged++;
                    continue;
                }
                if (dryRun) {
                    logger.info("DRY_RUN UPDATE " + row.label() + " changes=" + changes.keySet());
                } else {
                    client.updateUser(row.id(), changes);
                    existingUsers.get(row.id()).putAll(changes);
                    logger.info("updated " + row.label() + " changes=" + changes.keySet());
                }
                updated++;
            } catch (RuntimeException ex) {
                addError(errors, logger, formatError(row.label(), ex));
            }
        }
        return new Result(writeBack, created, updated, unchanged, errors);
    }

    static Map<String, Object> createUserPayload(UserRow row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("login", row.login());
        payload.put("lastname", row.lastname());
        payload.put("firstname", row.firstname());
        payload.put("mail", row.mail());
        if (row.admin() != null) {
            payload.put("admin", row.admin());
        }
        if (row.status() != null) {
            payload.put("status", row.status());
        }
        if (!row.customFields().isEmpty()) {
            payload.put("custom_fields", apiFields(row.customFields()));
        }
        if (row.password().isEmpty()) {
            payload.put("generate_password", true);
            payload.put("send_information", true);
        } else {
            payload.put("password", row.password());
        }
        return payload;
    }

    static Map<String, Object> userChanges(UserRow row, Map<String, Object> current) {
        Map<String, Object> changes = new LinkedHashMap<>();
        // ログインIDは大文字小文字の違いだけなら変更しない（Redmine でも同じログインIDとして扱う）
        if (!row.login().isEmpty() && !row.login().equalsIgnoreCase(text(current.get("login")))) {
            changes.put("login", row.login());
        }
        putIfChanged(changes, "lastname", row.lastname(), current.get("lastname"));
        putIfChanged(changes, "firstname", row.firstname(), current.get("firstname"));
        putIfChanged(changes, "mail", row.mail(), current.get("mail"));
        if (row.admin() != null && !row.admin().equals(Boolean.TRUE.equals(current.get("admin")))) {
            changes.put("admin", row.admin());
        }
        Long currentStatus = toLong(current.get("status"));
        if (row.status() != null && !Objects.equals(currentStatus, row.status().longValue())) {
            changes.put("status", row.status());
        }
        putCustomFieldChanges(changes, row.customFields(), current);
        return changes;
    }

    // ---------------------------------------------------------------- グループ

    /**
     * グループのシートを解析・検証します。
     *
     * @param sheet シート
     * @param existingGroups Redmine のグループ（ID → グループ）
     * @param knownLogins メンバーに指定できるログインID（Redmine のユーザーと、ユーザーのシートで作成するユーザー）
     * @return 解析結果
     */
    public static Plan<GroupRow> parseGroups(ParsedSheet sheet, Map<Long, Map<String, Object>> existingGroups,
            Set<String> knownLogins) {
        List<String> errors = new ArrayList<>();
        List<GroupRow> rows = new ArrayList<>();
        requireColumns(sheet, List.of(COL_ID, COL_GROUP_NAME), "グループ", errors);
        if (!errors.isEmpty()) {
            return new Plan<>(rows, errors);
        }
        Map<String, Long> byName = new HashMap<>();
        for (Map.Entry<Long, Map<String, Object>> entry : existingGroups.entrySet()) {
            byName.put(text(entry.getValue().get("name")).toLowerCase(Locale.ROOT), entry.getKey());
        }
        Set<String> known = new HashSet<>();
        for (String login : knownLogins) {
            known.add(login.toLowerCase(Locale.ROOT));
        }
        Map<String, CustomFieldColumns.Definition> groupFields = CustomFieldColumns.definitions(
                existingGroups.values());
        Map<String, Integer> seenNames = new HashMap<>();
        Map<Long, Integer> seenIds = new HashMap<>();
        for (int i = 0; i < sheet.rows().size(); i++) {
            Map<String, String> row = sheet.rows().get(i);
            int rowNumber = sheet.rowNumbers().get(i);
            String prefix = "グループ 行" + rowNumber + ": ";
            String rawId = value(row, COL_ID);
            String name = value(row, COL_GROUP_NAME);
            if (rawId.isEmpty() && name.isEmpty()) {
                if (!isBlankRow(row)) {
                    errors.add(prefix + "ID とグループ名がどちらも空欄です");
                }
                continue;
            }
            Long id = null;
            boolean matchedByName = false;
            if (!rawId.isEmpty()) {
                id = parseId(rawId);
                if (id == null) {
                    errors.add(prefix + "ID が数値ではありません: " + rawId);
                    continue;
                }
                if (!existingGroups.containsKey(id)) {
                    errors.add(prefix + "グループ #" + id + " が Redmine にありません（新規作成なら ID を空欄に）");
                    continue;
                }
            } else {
                id = byName.get(name.toLowerCase(Locale.ROOT));
                matchedByName = id != null;
            }
            if (name.isEmpty()) {
                name = text(existingGroups.get(id).get("name"));
            }
            List<String> members = null;
            String rawMembers = value(row, COL_MEMBERS);
            if (!rawMembers.isEmpty()) {
                members = new ArrayList<>(new LinkedHashSet<>(splitMembers(rawMembers)));
                List<String> unknown = new ArrayList<>();
                for (String login : members) {
                    if (!known.contains(login.toLowerCase(Locale.ROOT))) {
                        unknown.add(login);
                    }
                }
                if (!unknown.isEmpty()) {
                    errors.add(prefix + "メンバーのログインIDが見つかりません: " + String.join(", ", unknown)
                            + " [" + name + "]");
                    continue;
                }
            }
            Integer sameName = seenNames.putIfAbsent(name.toLowerCase(Locale.ROOT), rowNumber);
            if (sameName != null) {
                errors.add(prefix + "グループ名「" + name + "」が行" + sameName + "と重複しています");
                continue;
            }
            if (id != null) {
                Integer sameId = seenIds.putIfAbsent(id, rowNumber);
                if (sameId != null) {
                    errors.add(prefix + "グループ #" + id + " が行" + sameId + "と重複しています");
                    continue;
                }
            }
            List<Map<String, Object>> customFields = new ArrayList<>();
            List<String> unknownFields = customFieldCells(row, groupFields, customFields);
            if (!unknownFields.isEmpty()) {
                errors.add(prefix + "グループのカスタムフィールドが Redmine にありません: " + unknownFields);
                continue;
            }
            rows.add(new GroupRow(rowNumber, id, matchedByName, name, members, customFields));
        }
        return new Plan<>(rows, errors);
    }

    /**
     * グループを Upsert します（ユーザーの Upsert の後に実行する）。
     *
     * @param rows 検証済みの行
     * @param existingGroups Redmine のグループ（"users" にメンバー）
     * @param users Redmine のユーザー（今回作成したユーザーを含む）
     * @param client クライアント
     * @param dryRun ドライランなら true
     * @param logger ロガー
     * @return 結果
     */
    public Result syncGroups(List<GroupRow> rows, Map<Long, Map<String, Object>> existingGroups,
            Map<Long, Map<String, Object>> users, RedmineClient client, boolean dryRun, FileLogger logger) {
        Map<String, Long> byLogin = loginIndex(users);
        Map<Integer, Long> writeBack = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (GroupRow row : rows) {
            try {
                List<Long> memberIds = null;
                if (row.members() != null) {
                    memberIds = new ArrayList<>();
                    List<String> unresolved = new ArrayList<>();
                    for (String login : row.members()) {
                        Long userId = byLogin.get(login.toLowerCase(Locale.ROOT));
                        if (userId == null) {
                            unresolved.add(login);
                        } else {
                            memberIds.add(userId);
                        }
                    }
                    if (!unresolved.isEmpty() && !dryRun) {
                        addError(errors, logger, "同期失敗: " + row.label()
                                + " 理由=メンバーのユーザーがありません（ユーザーの作成に失敗した可能性）: "
                                + String.join(", ", unresolved));
                        continue;
                    }
                }
                if (row.id() == null) {
                    if (dryRun) {
                        logger.info("DRY_RUN CREATE " + row.label()
                                + (row.members() != null ? " members=" + row.members() : ""));
                        created++;
                        continue;
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("name", row.name());
                    if (memberIds != null) {
                        payload.put("user_ids", memberIds);
                    }
                    if (!row.customFields().isEmpty()) {
                        payload.put("custom_fields", apiFields(row.customFields()));
                    }
                    Long newId = client.createGroup(payload);
                    if (newId == null) {
                        addError(errors, logger, "作成失敗: " + row.label() + " 理由=レスポンスにIDがありません");
                        continue;
                    }
                    writeBack.put(row.rowNumber(), newId);
                    logger.info("created " + row.label() + " -> #" + newId);
                    created++;
                    continue;
                }
                if (row.matchedByName()) {
                    writeBack.put(row.rowNumber(), row.id());
                }
                Map<String, Object> current = existingGroups.get(row.id());
                Map<String, Object> changes = new LinkedHashMap<>();
                putIfChanged(changes, "name", row.name(), current.get("name"));
                if (row.members() != null && !sameMembers(row.members(), current, users)) {
                    changes.put("user_ids", memberIds);
                }
                putCustomFieldChanges(changes, row.customFields(), current);
                if (changes.isEmpty()) {
                    logger.debug("skipped update " + row.label() + " (no changes)");
                    unchanged++;
                    continue;
                }
                if (dryRun) {
                    logger.info("DRY_RUN UPDATE " + row.label() + " changes=" + changes.keySet()
                            + (changes.containsKey("user_ids") ? " members=" + row.members() : ""));
                } else {
                    client.updateGroup(row.id(), changes);
                    logger.info("updated " + row.label() + " changes=" + changes.keySet());
                }
                updated++;
            } catch (RuntimeException ex) {
                addError(errors, logger, formatError(row.label(), ex));
            }
        }
        return new Result(writeBack, created, updated, unchanged, errors);
    }

    /**
     * グループの現在のメンバー（ログインID）が指定と同じか判定します（大文字小文字は区別しない）。
     */
    static boolean sameMembers(List<String> members, Map<String, Object> group, Map<Long, Map<String, Object>> users) {
        Set<String> expected = new HashSet<>();
        for (String login : members) {
            expected.add(login.toLowerCase(Locale.ROOT));
        }
        return expected.equals(new HashSet<>(currentMemberLogins(group, users, true)));
    }

    /**
     * グループの現在のメンバーのログインIDを返します（ログインIDが分からないユーザーは "#ID"）。
     *
     * @param group グループ（"users" にメンバー）
     * @param users ユーザー（ID → ユーザー）
     * @param lowerCase 小文字にする場合 true
     * @return ログインID
     */
    public static List<String> currentMemberLogins(Map<String, Object> group, Map<Long, Map<String, Object>> users,
            boolean lowerCase) {
        List<String> logins = new ArrayList<>();
        if (!(group.get("users") instanceof List<?> list)) {
            return logins;
        }
        for (Object element : list) {
            if (element instanceof Map<?, ?> member && member.get("id") instanceof Number id) {
                Map<String, Object> user = users.get(id.longValue());
                String login = user != null ? text(user.get("login")) : "";
                login = login.isEmpty() ? "#" + id.longValue() : login;
                logins.add(lowerCase ? login.toLowerCase(Locale.ROOT) : login);
            }
        }
        return logins;
    }

    /**
     * メンバーの指定をログインIDに分割します（カンマ・読点・セミコロン・改行区切り）。
     *
     * @param raw セルの値
     * @return ログインID
     */
    static List<String> splitMembers(String raw) {
        List<String> result = new ArrayList<>();
        for (String token : raw.split("[,、;；\\r\\n]+")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 共通

    /**
     * ログインID（小文字）→ ユーザーID の索引を作ります。
     *
     * @param users ユーザー
     * @return 索引
     */
    public static Map<String, Long> loginIndex(Map<Long, Map<String, Object>> users) {
        Map<String, Long> index = new HashMap<>();
        for (Map.Entry<Long, Map<String, Object>> entry : users.entrySet()) {
            String login = text(entry.getValue().get("login"));
            if (!login.isEmpty()) {
                index.put(login.toLowerCase(Locale.ROOT), entry.getKey());
            }
        }
        return index;
    }

    /**
     * 「管理者」列の値を解釈します。
     *
     * @param raw セルの値
     * @return true / false、空欄なら null（変更しない）
     */
    static Boolean parseAdmin(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "" -> null;
            case "はい", "yes", "true", "1", "○", "〇", "管理者" -> Boolean.TRUE;
            case "いいえ", "no", "false", "0", "×", "-" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("管理者の値が不正です（はい／いいえ）: " + raw);
        };
    }

    /**
     * 「状態」列の値を解釈します。
     *
     * @param raw セルの値
     * @return 1=有効, 2=登録, 3=ロック、空欄なら null（変更しない）
     */
    static Integer parseStatus(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "" -> null;
            case "有効", "active", "1" -> 1;
            case "登録", "未承認", "registered", "2" -> 2;
            case "ロック", "ロック中", "locked", "3" -> 3;
            default -> throw new IllegalArgumentException("状態の値が不正です（有効／登録／ロック）: " + raw);
        };
    }

    /**
     * 行の「CF:名前」列を {@code {id, value}} に変換します。
     *
     * @return 見つからないカスタムフィールド名
     */
    private static List<String> customFieldCells(Map<String, String> row,
            Map<String, CustomFieldColumns.Definition> definitions, List<Map<String, Object>> out) {
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, String> cell : CustomFieldColumns.cellValues(row).entrySet()) {
            CustomFieldColumns.Definition definition = CustomFieldColumns.find(definitions, cell.getKey());
            if (definition == null) {
                unknown.add(cell.getKey());
            } else {
                Map<String, Object> field = CustomFieldColumns.payload(definition, cell.getValue());
                field.put("multiple", definition.multiple());
                field.put("cell", cell.getValue());
                out.add(field);
            }
        }
        return unknown;
    }

    private static List<Map<String, Object>> apiFields(List<Map<String, Object>> fields) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            result.add(Map.of("id", field.get("id"), "value", field.get("value")));
        }
        return result;
    }

    /**
     * 現在の値と違う「CF:名前」列だけを custom_fields として changes に入れます。
     */
    private static void putCustomFieldChanges(Map<String, Object> changes, List<Map<String, Object>> fields,
            Map<String, Object> current) {
        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            long id = ((Number) field.get("id")).longValue();
            CustomFieldColumns.Definition definition = new CustomFieldColumns.Definition(id, "",
                    Boolean.TRUE.equals(field.get("multiple")));
            if (!CustomFieldColumns.same(definition, String.valueOf(field.get("cell")), current)) {
                changed.add(Map.of("id", id, "value", field.get("value")));
            }
        }
        if (!changed.isEmpty()) {
            changes.put("custom_fields", changed);
        }
    }

    private static void requireColumns(ParsedSheet sheet, List<String> columns, String kind, List<String> errors) {
        List<String> missing = new ArrayList<>();
        for (String column : columns) {
            if (!sheet.headers().contains(column)) {
                missing.add(column);
            }
        }
        if (!missing.isEmpty()) {
            errors.add(kind + "の表に列がありません: " + String.join(", ", missing) + "（見出し: " + sheet.headers() + "）");
        }
    }

    private static void putIfChanged(Map<String, Object> changes, String key, String value, Object current) {
        if (!value.isEmpty() && !value.equals(text(current))) {
            changes.put(key, value);
        }
    }

    private static boolean isBlankRow(Map<String, String> row) {
        return row.values().stream().allMatch(v -> v == null || v.isBlank());
    }

    private static Long parseId(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty() || !trimmed.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : parseId(String.valueOf(value));
    }

    private static String value(Map<String, String> row, String key) {
        String raw = row.get(key);
        return raw == null ? "" : raw.trim();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void addError(List<String> errors, FileLogger logger, String message) {
        errors.add(message);
        logger.error(message);
    }

    private static String formatError(String label, RuntimeException ex) {
        if (ex instanceof RestClientResponseException responseEx) {
            return "同期失敗: " + label + " 理由=" + responseEx.getStatusCode().value() + " "
                    + responseEx.getResponseBodyAsString();
        }
        String message = ex.getMessage();
        return "同期失敗: " + label + " 理由=" + (message == null ? ex.getClass().getSimpleName() : message);
    }
}
