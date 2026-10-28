package mozaki.redmineUpster.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

/**
 * 設定画面で編集する sync-config.yml の内容（Swing に依存しない）。
 * <p>
 * YAML を {@link LinkedHashMap} の木として読み込み、画面が扱うキーだけを変更して、木全体を書き戻します。
 * 画面にない項目（customFieldMap など）や {@code ${REDMINE_API_KEY}} のような値はそのまま残ります。
 * 空欄にした項目はキーごと削除し、既定値が使われるようにします（空文字は書きません）。
 * コメントは保存時に失われるため、保存前に元のファイルを {@code sync-config.yml.bak} にコピーします。
 * </p>
 */
public class SettingsDocument {

    /** 保存したファイルの先頭に書くコメント */
    static final String HEADER = """
            # redmineUpster 設定ファイル（設定画面で保存しました。UTF-8）
            # 項目の説明は USER_GUIDE.md、または設定画面（settings.bat）の各項目にマウスを置くと表示されます。
            # 画面で保存すると、このファイルのコメントは消えます（保存前の内容は sync-config.yml.bak に残ります）。
            """;

    /** 環境変数参照（${NAME} / ${NAME:既定値}） */
    private static final Pattern ENV_REF = Pattern.compile(".*\\$\\{[^}]+}.*");

    /** キーの並び順（ここにないキーは後ろに元の順で並べる） */
    private static final List<String> ROOT_ORDER = List.of("projects");
    private static final List<String> PROJECT_ORDER = List.of("name", "default", "redmine", "sync");
    private static final List<String> REDMINE_ORDER = List.of("baseUrl", "apiKey", "projectId");
    private static final List<String> SYNC_ORDER = List.of("files", "trackerMap", "deletion", "virtualParents",
            "autoCloseParents", "resultColumns", "status", "excel", "users", "groups", "columns", "customFieldMap",
            "customFieldDateColumns", "tracker");
    private static final List<String> COLUMNS_ORDER = List.of("ticketIdColumn", "trackerColumn", "hierarchy",
            "fillDownHierarchy", "startDateColumn", "dueDateColumn", "statusColumn", "progressColumn",
            "descriptionColumn", "lastSyncedAtColumn", "syncResultColumn", "syncErrorColumn");
    private static final List<String> STATUS_ORDER = List.of("enabled", "mode", "fixed", "statusMap");
    private static final List<String> AUTO_CLOSE_ORDER = List.of("enabled", "status", "inProgress",
            "inProgressStatus", "ignoreNotInExcel");
    private static final List<String> VIRTUAL_PARENTS_ORDER = List.of("enabled", "trackerId");
    private static final List<String> FILE_ORDER = List.of("path", "rootIssueId");

    /** ファイル一覧の1行（sync.files の要素） */
    public record FileEntry(String path, String rootIssueId) {
    }

    private final Path path;
    private Map<String, Object> root;

    private SettingsDocument(Path path, Map<String, Object> root) {
        this.path = path;
        this.root = root;
    }

    /**
     * 設定ファイルを読み込みます。ファイルがない（または空の）場合は空のプロジェクトを1つ持つ状態で始めます。
     *
     * @param path 設定ファイルのパス
     * @return 読み込んだ設定
     * @throws IOException 読み込みに失敗した場合
     * @throws IllegalArgumentException YAML の形式が正しくない場合
     */
    @SuppressWarnings("unchecked")
    public static SettingsDocument load(Path path) throws IOException {
        Map<String, Object> root = null;
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
                if (loaded instanceof Map<?, ?> map) {
                    root = (Map<String, Object>) map;
                } else if (loaded != null) {
                    throw new IllegalArgumentException("設定ファイルの形式が正しくありません（先頭が projects: ではありません）");
                }
            } catch (org.yaml.snakeyaml.error.YAMLException e) {
                throw new IllegalArgumentException("設定ファイル（YAML）の書き方に誤りがあります: " + e.getMessage(), e);
            }
        }
        if (root == null) {
            root = new LinkedHashMap<>();
        }
        SettingsDocument doc = new SettingsDocument(path, root);
        if (doc.projects().isEmpty()) {
            Map<String, Object> project = doc.addProject();
            project.put("default", Boolean.TRUE);
        }
        return doc;
    }

    /** @return 設定ファイルのパス */
    public Path getPath() {
        return path;
    }

    /** @return 設定ファイルのフォルダ（相対パスの基準） */
    public Path getBaseDir() {
        Path parent = path.toAbsolutePath().normalize().getParent();
        return parent != null ? parent : path.toAbsolutePath();
    }

    /** @return YAML 全体（テスト用） */
    Map<String, Object> getRoot() {
        return root;
    }

    // ---------------------------------------------------------------- プロジェクト

    /**
     * @return プロジェクトの一覧（projects の要素。変更すると保存内容に反映される）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> projects() {
        Object value = root.get("projects");
        if (!(value instanceof List<?>)) {
            value = new ArrayList<>();
            root.put("projects", value);
        }
        List<Object> list = (List<Object>) value;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?>)) {
                list.set(i, new LinkedHashMap<String, Object>());
            }
        }
        return (List<Map<String, Object>>) value;
    }

    /**
     * 空のプロジェクトを末尾に追加します。
     *
     * @return 追加したプロジェクト
     */
    public Map<String, Object> addProject() {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("name", uniqueName("新しいプロジェクト"));
        projects().add(project);
        return project;
    }

    /**
     * プロジェクトを複製して、元のプロジェクトの次に追加します（既定の印は付けない）。
     *
     * @param index 複製元の位置
     * @return 追加したプロジェクト
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> duplicateProject(int index) {
        Map<String, Object> copy = (Map<String, Object>) deepCopy(projects().get(index));
        copy.remove("default");
        String name = getString(copy, "name");
        copy.put("name", uniqueName((name == null || name.isBlank() ? "プロジェクト" : name) + " のコピー"));
        projects().add(index + 1, copy);
        return copy;
    }

    /**
     * プロジェクトを削除します。既定のプロジェクトを削除した場合は先頭のプロジェクトを既定にします。
     *
     * @param index 削除する位置
     */
    public void removeProject(int index) {
        Map<String, Object> removed = projects().remove(index);
        if (isDefault(removed) && !projects().isEmpty()) {
            setDefaultProject(0);
        }
    }

    /**
     * @param project プロジェクト
     * @return 既定のプロジェクト（default: true）か
     */
    public static boolean isDefault(Map<String, Object> project) {
        return Boolean.TRUE.equals(project.get("default"));
    }

    /**
     * 指定したプロジェクトだけを既定（default: true）にし、ほかのプロジェクトの default を消します。
     *
     * @param index 既定にする位置
     */
    public void setDefaultProject(int index) {
        List<Map<String, Object>> projects = projects();
        for (int i = 0; i < projects.size(); i++) {
            if (i == index) {
                projects.get(i).put("default", Boolean.TRUE);
            } else {
                projects.get(i).remove("default");
            }
        }
    }

    private String uniqueName(String base) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> project : projects()) {
            names.add(String.valueOf(project.get("name")));
        }
        String name = base;
        for (int i = 2; names.contains(name); i++) {
            name = base + " " + i;
        }
        return name;
    }

    // ---------------------------------------------------------------- 値の読み書き（"redmine.baseUrl" のような . 区切りのパス）

    /**
     * @param node 起点（プロジェクトなど）
     * @param dottedPath "sync.columns.ticketIdColumn" のようなパス
     * @return 値の文字列表現（なければnull）
     */
    public static String getString(Map<String, Object> node, String dottedPath) {
        Object value = get(node, dottedPath);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 文字列を設定します。空欄（null・空白のみ）の場合はキーを削除し、空になった親も削除します。
     *
     * @param node 起点
     * @param dottedPath パス
     * @param value 値
     */
    public static void setString(Map<String, Object> node, String dottedPath, String value) {
        if (value == null || value.isBlank()) {
            remove(node, dottedPath);
        } else {
            put(node, dottedPath, value.trim());
        }
    }

    /**
     * 数値（チケット番号・ステータス番号など）を設定します。数値なら数値として、
     * {@code ${...}} を含む場合は文字列のまま書きます。空欄はキーを削除します。
     *
     * @param node 起点
     * @param dottedPath パス
     * @param value 値
     */
    public static void setNumber(Map<String, Object> node, String dottedPath, String value) {
        if (value == null || value.isBlank()) {
            remove(node, dottedPath);
        } else {
            put(node, dottedPath, toNumberOrString(value));
        }
    }

    /**
     * @param node 起点
     * @param dottedPath パス
     * @param defaultValue キーがない場合の値
     * @return 真偽値（"true" の文字列も true）
     */
    public static boolean getBoolean(Map<String, Object> node, String dottedPath, boolean defaultValue) {
        Object value = get(node, dottedPath);
        if (value == null) {
            return defaultValue;
        }
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    /**
     * 真偽値を設定します。既定値と同じ場合はキーを削除します（ファイルを短く保つ）。
     *
     * @param node 起点
     * @param dottedPath パス
     * @param value 値
     * @param defaultValue アプリの既定値
     */
    public static void setBoolean(Map<String, Object> node, String dottedPath, boolean value, boolean defaultValue) {
        if (value == defaultValue) {
            remove(node, dottedPath);
        } else {
            put(node, dottedPath, value);
        }
    }

    /**
     * @param node 起点
     * @param dottedPath パス
     * @return 文字列のリスト（なければ空）
     */
    public static List<String> getStringList(Map<String, Object> node, String dottedPath) {
        Object value = get(node, dottedPath);
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        } else if (value != null) {
            result.add(String.valueOf(value));
        }
        return result;
    }

    /**
     * 文字列のリストを設定します。空欄の要素は除き、空になったらキーを削除します。
     *
     * @param node 起点
     * @param dottedPath パス
     * @param values 値
     */
    public static void setStringList(Map<String, Object> node, String dottedPath, List<String> values) {
        List<Object> cleaned = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        if (cleaned.isEmpty()) {
            remove(node, dottedPath);
        } else {
            put(node, dottedPath, cleaned);
        }
    }

    /**
     * @param node 起点
     * @param dottedPath パス（statusMap・trackerMap など）
     * @return 名前→値（文字列。書いてある順）
     */
    public static LinkedHashMap<String, String> getStringMap(Map<String, Object> node, String dottedPath) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (get(node, dottedPath) instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()),
                        entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    /**
     * 名前→値の表を設定します。名前か値が空欄の行は除き、空になったらキーを削除します。
     *
     * @param node 起点
     * @param dottedPath パス
     * @param values 名前→値
     * @param numericValues true: 数値は数値として書く（trackerMap）／false: 文字列として書く（statusMap）
     */
    public static void setStringMap(Map<String, Object> node, String dottedPath, Map<String, String> values,
            boolean numericValues) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                cleaned.put(key, numericValues ? toNumberOrString(value) : value);
            }
        }
        if (cleaned.isEmpty()) {
            remove(node, dottedPath);
        } else {
            put(node, dottedPath, cleaned);
        }
    }

    /**
     * @param project プロジェクト
     * @return sync.files の一覧（文字列の要素も { path, rootIssueId } の要素も読む）
     */
    public static List<FileEntry> getFiles(Map<String, Object> project) {
        List<FileEntry> files = new ArrayList<>();
        Object value = get(project, "sync.files");
        if (value == null) {
            return files;
        }
        List<?> list = value instanceof List<?> l ? l : List.of(value);
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object rawPath = map.get("path") != null ? map.get("path") : map.get("file");
                Object rawRoot = map.get("rootIssueId") != null ? map.get("rootIssueId") : map.get("rootIssue");
                files.add(new FileEntry(rawPath == null ? "" : String.valueOf(rawPath),
                        rawRoot == null ? "" : String.valueOf(rawRoot)));
            } else if (item != null) {
                files.add(new FileEntry(String.valueOf(item), ""));
            }
        }
        return files;
    }

    /**
     * sync.files を設定します。ルートチケット番号がある行は { path, rootIssueId }、ない行は文字列で書きます。
     * パスが空欄の行は除き、空になったらキーを削除します。
     *
     * @param project プロジェクト
     * @param files ファイルの一覧
     */
    public static void setFiles(Map<String, Object> project, List<FileEntry> files) {
        List<Object> list = new ArrayList<>();
        for (FileEntry file : files) {
            String filePath = file.path() == null ? "" : file.path().trim();
            if (filePath.isEmpty()) {
                continue;
            }
            String rootIssue = file.rootIssueId() == null ? "" : file.rootIssueId().trim();
            if (rootIssue.startsWith("#")) {
                rootIssue = rootIssue.substring(1).trim();
            }
            if (rootIssue.isEmpty()) {
                list.add(filePath);
            } else {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", filePath);
                entry.put("rootIssueId", toNumberOrString(rootIssue));
                list.add(entry);
            }
        }
        if (list.isEmpty()) {
            remove(project, "sync.files");
        } else {
            put(project, "sync.files", list);
        }
    }

    /**
     * ファイル一覧に書くパスを返します。設定ファイルのフォルダの中なら相対パス（区切りは /）、外なら絶対パス。
     *
     * @param file 選んだファイル
     * @return 設定に書くパス
     */
    public String toConfigPath(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path base = getBaseDir();
        if (absolute.startsWith(base)) {
            return base.relativize(absolute).toString().replace('\\', '/');
        }
        return absolute.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object get(Map<String, Object> node, String dottedPath) {
        Object current = node;
        for (String key : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> node, String dottedPath, Object value) {
        String[] keys = dottedPath.split("\\.");
        Map<String, Object> current = node;
        for (int i = 0; i < keys.length - 1; i++) {
            Object child = current.get(keys[i]);
            if (!(child instanceof Map<?, ?>)) {
                child = new LinkedHashMap<String, Object>();
                current.put(keys[i], child);
            }
            current = (Map<String, Object>) child;
        }
        current.put(keys[keys.length - 1], value);
    }

    /**
     * キーを削除し、それによって空になった親のマップも削除します。
     */
    @SuppressWarnings("unchecked")
    static void remove(Map<String, Object> node, String dottedPath) {
        String[] keys = dottedPath.split("\\.");
        List<Map<String, Object>> chain = new ArrayList<>();
        Map<String, Object> current = node;
        for (int i = 0; i < keys.length - 1; i++) {
            chain.add(current);
            Object child = current.get(keys[i]);
            if (!(child instanceof Map<?, ?>)) {
                return;
            }
            current = (Map<String, Object>) child;
        }
        current.remove(keys[keys.length - 1]);
        for (int i = keys.length - 2; i >= 0; i--) {
            Map<String, Object> parent = chain.get(i);
            if (parent.get(keys[i]) instanceof Map<?, ?> child && child.isEmpty()) {
                parent.remove(keys[i]);
            } else {
                break;
            }
        }
    }

    private static Object toNumberOrString(String value) {
        String text = value.trim();
        if (text.matches("\\d{1,9}")) {
            return Integer.parseInt(text);
        }
        if (text.matches("\\d{10,18}")) {
            return Long.parseLong(text);
        }
        return text;
    }

    /**
     * @param value 入力値
     * @return 空欄・正の整数・{@code ${...}} のいずれかなら true
     */
    public static boolean isNumberOrEnv(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String text = value.trim();
        if (text.startsWith("#")) {
            text = text.substring(1).trim();
        }
        return text.matches("\\d{1,18}") || ENV_REF.matcher(text).matches();
    }

    // ---------------------------------------------------------------- 検証

    /**
     * 保存前の検証を行います。
     *
     * @return エラーメッセージ（なければ空）
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> projects = projects();
        if (projects.isEmpty()) {
            errors.add("プロジェクトを1つ以上作成してください");
            return errors;
        }
        Set<String> names = new LinkedHashSet<>();
        int defaults = 0;
        for (int i = 0; i < projects.size(); i++) {
            Map<String, Object> project = projects.get(i);
            String name = getString(project, "name");
            String label = name == null || name.isBlank() ? (i + 1) + "番目のプロジェクト" : "「" + name + "」";
            if (name == null || name.isBlank()) {
                errors.add(label + ": プロジェクト名を入力してください");
            } else if (!names.add(name.trim())) {
                errors.add(label + ": プロジェクト名が重複しています（ほかのプロジェクトと違う名前にしてください）");
            }
            if (isDefault(project)) {
                defaults++;
            }
            if (isBlank(getString(project, "redmine.baseUrl"))) {
                errors.add(label + ": Redmine の URL を入力してください");
            }
            if (isBlank(getString(project, "redmine.projectId"))) {
                errors.add(label + ": プロジェクト識別子を入力してください");
            }
            if (!isNumberOrEnv(getString(project, "sync.deletion.statusId"))) {
                errors.add(label + ": 削除扱いのステータス番号は数値で入力してください");
            }
            for (Map.Entry<String, String> entry : getStringMap(project, "sync.trackerMap").entrySet()) {
                if (!isNumberOrEnv(entry.getValue()) || entry.getValue().isBlank()) {
                    errors.add(label + ": トラッカー対応表の「" + entry.getKey() + "」の ID は数値で入力してください");
                }
            }
            for (FileEntry file : getFiles(project)) {
                if (!isNumberOrEnv(file.rootIssueId())) {
                    errors.add(label + ": ファイル「" + file.path() + "」のルートチケット番号は数値で入力してください");
                }
            }
        }
        if (defaults != 1) {
            errors.add("既定のプロジェクトを1つだけ選んでください（現在 " + defaults + " 件）");
        }
        return errors;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ---------------------------------------------------------------- 保存

    /**
     * 設定ファイルに保存します。既存のファイルは {@code <ファイル名>.bak} にコピーしてから上書きします。
     *
     * @return バックアップのパス（元のファイルがなかった場合はnull）
     * @throws IOException 書き込みに失敗した場合
     */
    public Path save() throws IOException {
        Path backup = null;
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.isRegularFile(path)) {
            backup = path.resolveSibling(path.getFileName() + ".bak");
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(path, toYaml(), StandardCharsets.UTF_8);
        return backup;
    }

    /**
     * @return 保存する YAML（先頭のコメント付き）
     */
    public String toYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setAllowUnicode(true);
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        options.setLineBreak(DumperOptions.LineBreak.UNIX);
        Yaml yaml = new Yaml(new QuotingRepresenter(options), options);
        StringWriter writer = new StringWriter();
        writer.append(HEADER);
        yaml.dump(ordered(root, "root"), writer);
        return writer.toString();
    }

    /**
     * キーを決まった順に並べ替えた写しを作ります（既知のキー → それ以外は元の順）。
     */
    @SuppressWarnings("unchecked")
    private static Object ordered(Object value, String context) {
        if (value instanceof Map<?, ?> map) {
            List<String> order = switch (context) {
            case "root" -> ROOT_ORDER;
            case "project" -> PROJECT_ORDER;
            case "redmine" -> REDMINE_ORDER;
            case "sync" -> SYNC_ORDER;
            case "columns" -> COLUMNS_ORDER;
            case "status" -> STATUS_ORDER;
            case "autoCloseParents" -> AUTO_CLOSE_ORDER;
            case "virtualParents" -> VIRTUAL_PARENTS_ORDER;
            case "file" -> FILE_ORDER;
            default -> List.of();
            };
            Map<Object, Object> source = (Map<Object, Object>) map;
            Map<Object, Object> result = new LinkedHashMap<>();
            for (String key : order) {
                if (source.containsKey(key)) {
                    result.put(key, ordered(source.get(key), childContext(context, key)));
                }
            }
            for (Map.Entry<Object, Object> entry : source.entrySet()) {
                if (!result.containsKey(entry.getKey())) {
                    result.put(entry.getKey(), ordered(entry.getValue(), childContext(context, entry.getKey())));
                }
            }
            return result;
        }
        if (value instanceof List<?> list) {
            String itemContext = switch (context) {
            case "projects" -> "project";
            case "files" -> "file";
            default -> "";
            };
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(ordered(item, itemContext));
            }
            return result;
        }
        return value;
    }

    private static String childContext(String context, Object key) {
        String name = String.valueOf(key);
        return switch (context) {
        case "root" -> "projects".equals(name) ? "projects" : "";
        case "project" -> "redmine".equals(name) || "sync".equals(name) ? name : "";
        case "sync" -> switch (name) {
            case "columns", "status", "autoCloseParents", "virtualParents", "files" -> name;
            default -> "";
            };
        default -> "";
        };
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(k, deepCopy(v)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(v -> copy.add(deepCopy(v)));
            return copy;
        }
        return value;
    }

    /**
     * 値の文字列を "..." で囲んで書く Representer（キーと数値・真偽値はそのまま）。
     * 元のサンプル（packaging/dist/sync-config.yml）と同じ見た目にし、"001" や "true" のような文字列も
     * 文字列のまま読み戻せるようにする。
     */
    private static class QuotingRepresenter extends Representer {

        QuotingRepresenter(DumperOptions options) {
            super(options);
        }

        @Override
        protected Node representMapping(Tag tag, Map<?, ?> mapping, DumperOptions.FlowStyle flowStyle) {
            Node node = super.representMapping(tag, mapping, flowStyle);
            if (node instanceof MappingNode mappingNode) {
                List<NodeTuple> tuples = mappingNode.getValue();
                for (int i = 0; i < tuples.size(); i++) {
                    NodeTuple tuple = tuples.get(i);
                    tuples.set(i, new NodeTuple(tuple.getKeyNode(), quoted(tuple.getValueNode())));
                }
            }
            return node;
        }

        @Override
        protected Node representSequence(Tag tag, Iterable<?> sequence, DumperOptions.FlowStyle flowStyle) {
            Node node = super.representSequence(tag, sequence, flowStyle);
            if (node instanceof SequenceNode sequenceNode) {
                List<Node> items = sequenceNode.getValue();
                for (int i = 0; i < items.size(); i++) {
                    items.set(i, quoted(items.get(i)));
                }
            }
            return node;
        }

        private static Node quoted(Node node) {
            if (node instanceof ScalarNode scalar && Tag.STR.equals(scalar.getTag())
                    && scalar.getScalarStyle() != DumperOptions.ScalarStyle.DOUBLE_QUOTED
                    && !scalar.getValue().contains("\n")) {
                return new ScalarNode(Tag.STR, scalar.getValue(), scalar.getStartMark(), scalar.getEndMark(),
                        DumperOptions.ScalarStyle.DOUBLE_QUOTED);
            }
            return node;
        }
    }
}
