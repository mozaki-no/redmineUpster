package mozaki.redmineUpster.settings;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

/**
 * sync-config.yml の設定画面（Swing）。
 * <p>
 * 左にプロジェクトの一覧、右に選んだプロジェクトの設定（タブ）を表示します。
 * 値の読み書きは {@link SettingsDocument} が行い、画面にない項目はそのまま残します。
 * </p>
 */
public class SettingsWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    private final transient SettingsDocument document;
    private final DefaultListModel<String> projectListModel = new DefaultListModel<>();
    private final JList<String> projectList = new JList<>(projectListModel);
    private final transient List<Binding> bindings = new ArrayList<>();
    private final JTabbedPane tabs = new JTabbedPane();
    private int currentIndex = -1;
    private boolean updatingList;
    private String savedYaml;

    /**
     * 設定画面を開きます（イベントスレッドから呼ぶ）。
     *
     * @param configPath 設定ファイル
     */
    public static void open(Path configPath) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // 既定の見た目のまま
        }
        SettingsDocument document;
        try {
            document = SettingsDocument.load(configPath);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "設定ファイルを読み込めませんでした。\n" + configPath + "\n\n" + e.getMessage()
                            + "\n\nテキストエディタで誤りを直すか、ファイルの名前を変えてから開き直してください。",
                    "redmineUpster 設定", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        SettingsWindow window = new SettingsWindow(document);
        window.setVisible(true);
    }

    SettingsWindow(SettingsDocument document) {
        super("redmineUpster 設定 - " + document.getPath().getFileName());
        this.document = document;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeWindow();
            }
        });

        JLabel pathLabel = new JLabel("設定ファイル: " + document.getPath());
        pathLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        tabs.addTab("接続", scroll(connectionTab()));
        tabs.addTab("ファイル", scroll(filesTab()));
        tabs.addTab("取込列名", scroll(columnsTab()));
        tabs.addTab("Excel", scroll(excelTab()));
        tabs.addTab("ステータス・削除", scroll(statusTab()));
        tabs.addTab("親チケット", scroll(parentsTab()));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, projectPanel(), tabs);
        split.setDividerLocation(230);

        JButton save = new JButton("保存");
        save.addActionListener(e -> save());
        JButton close = new JButton("閉じる");
        close.addActionListener(e -> closeWindow());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(save);
        buttons.add(close);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(pathLabel, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);

        // 画面に読み込んで書き戻した状態を「変更なし」とする（既定値と同じ項目の削除などで差が出ないように）
        for (Map<String, Object> project : document.projects()) {
            bindings.forEach(b -> b.load.accept(project));
            bindings.forEach(b -> b.store.accept(project));
        }
        savedYaml = document.toYaml();
        refreshProjectList();
        selectProject(defaultIndex());

        setSize(1000, 700);
        setMinimumSize(new Dimension(760, 520));
        setLocationRelativeTo(null);
    }

    // ---------------------------------------------------------------- プロジェクト一覧

    private JComponent projectPanel() {
        projectList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        projectList.setToolTipText("同期先のプロジェクト。run.bat は「既定」のプロジェクトを使います（--project= で別のプロジェクトも指定可）");
        projectList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updatingList && projectList.getSelectedIndex() >= 0
                    && projectList.getSelectedIndex() != currentIndex) {
                selectProject(projectList.getSelectedIndex());
            }
        });

        JButton add = new JButton("追加");
        add.setToolTipText("空のプロジェクトを追加します");
        add.addActionListener(e -> {
            storeCurrent();
            document.addProject();
            refreshProjectList();
            selectProject(document.projects().size() - 1);
        });
        JButton duplicate = new JButton("複製");
        duplicate.setToolTipText("選んでいるプロジェクトの設定をコピーして追加します（本番とテスト環境など）");
        duplicate.addActionListener(e -> {
            if (currentIndex < 0) {
                return;
            }
            storeCurrent();
            document.duplicateProject(currentIndex);
            refreshProjectList();
            selectProject(currentIndex + 1);
        });
        JButton remove = new JButton("削除");
        remove.setToolTipText("選んでいるプロジェクトを設定から削除します");
        remove.addActionListener(e -> removeProject());
        JButton makeDefault = new JButton("既定にする");
        makeDefault.setToolTipText("run.bat などで --project= を指定しないときに使うプロジェクトにします（既定は1つだけ）");
        makeDefault.addActionListener(e -> {
            if (currentIndex < 0) {
                return;
            }
            storeCurrent();
            document.setDefaultProject(currentIndex);
            refreshProjectList();
        });

        JPanel buttons = new JPanel(new java.awt.GridLayout(2, 2, 4, 4));
        buttons.add(add);
        buttons.add(duplicate);
        buttons.add(remove);
        buttons.add(makeDefault);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(new JLabel("プロジェクト"), BorderLayout.NORTH);
        panel.add(new JScrollPane(projectList), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void removeProject() {
        if (currentIndex < 0) {
            return;
        }
        if (document.projects().size() <= 1) {
            JOptionPane.showMessageDialog(this, "プロジェクトは1つ以上必要です", getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        String name = SettingsDocument.getString(document.projects().get(currentIndex), "name");
        if (JOptionPane.showConfirmDialog(this, "プロジェクト「" + name + "」を削除しますか？", getTitle(),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        int index = currentIndex;
        currentIndex = -1;
        document.removeProject(index);
        refreshProjectList();
        selectProject(Math.min(index, document.projects().size() - 1));
    }

    private int defaultIndex() {
        List<Map<String, Object>> projects = document.projects();
        for (int i = 0; i < projects.size(); i++) {
            if (SettingsDocument.isDefault(projects.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private void refreshProjectList() {
        updatingList = true;
        try {
            projectListModel.clear();
            for (Map<String, Object> project : document.projects()) {
                String name = SettingsDocument.getString(project, "name");
                projectListModel.addElement((name == null || name.isBlank() ? "（名前なし）" : name)
                        + (SettingsDocument.isDefault(project) ? "（既定）" : ""));
            }
            if (currentIndex >= 0 && currentIndex < projectListModel.size()) {
                projectList.setSelectedIndex(currentIndex);
            }
        } finally {
            updatingList = false;
        }
    }

    private void selectProject(int index) {
        storeCurrent();
        currentIndex = index;
        if (index >= 0 && index < document.projects().size()) {
            Map<String, Object> project = document.projects().get(index);
            bindings.forEach(b -> b.load.accept(project));
        }
        refreshProjectList();
    }

    /** 画面の入力を選んでいるプロジェクトに書き込みます */
    private void storeCurrent() {
        if (currentIndex < 0 || currentIndex >= document.projects().size()) {
            return;
        }
        Map<String, Object> project = document.projects().get(currentIndex);
        bindings.forEach(b -> b.store.accept(project));
    }

    // ---------------------------------------------------------------- 保存・閉じる

    private boolean save() {
        storeCurrent();
        refreshProjectList();
        List<String> errors = document.validate();
        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(this, "保存できません。次の項目を直してください。\n\n・" + String.join("\n・", errors),
                    getTitle(), JOptionPane.ERROR_MESSAGE);
            return false;
        }
        try {
            Path backup = document.save();
            savedYaml = document.toYaml();
            JOptionPane.showMessageDialog(this, "保存しました。\n" + document.getPath()
                    + (backup != null ? "\n\n保存前の内容: " + backup : ""), getTitle(),
                    JOptionPane.INFORMATION_MESSAGE);
            return true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "保存できませんでした。\n" + document.getPath() + "\n\n" + e.getMessage(),
                    getTitle(), JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void closeWindow() {
        storeCurrent();
        if (!document.toYaml().equals(savedYaml)) {
            int answer = JOptionPane.showConfirmDialog(this, "変更が保存されていません。保存しますか？", getTitle(),
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
                return;
            }
            if (answer == JOptionPane.YES_OPTION && !save()) {
                return;
            }
        }
        dispose();
        System.exit(0);
    }

    // ---------------------------------------------------------------- タブ

    private JComponent connectionTab() {
        Form form = new Form();
        form.text("プロジェクト名", "name", null,
                "この設定の名前（自由に付けてよい）。run.bat などで --project=名前 と指定して使い分けます");
        form.text("Redmine の URL", "redmine.baseUrl", "例: https://redmine.example.com",
                "Redmine の URL（ブラウザで開くトップページの URL）");

        JPasswordField apiKey = new JPasswordField(30);
        JCheckBox show = new JCheckBox("表示");
        char echo = apiKey.getEchoChar();
        show.addActionListener(e -> apiKey.setEchoChar(show.isSelected() ? (char) 0 : echo));
        String apiTip = "API キー（Redmine の「個人設定」→「APIアクセスキー」）。"
                + "環境変数 REDMINE_API_KEY に入れておく場合は ${REDMINE_API_KEY} と書きます";
        apiKey.setToolTipText(apiTip);
        JPanel apiPanel = new JPanel(new BorderLayout(4, 0));
        apiPanel.add(apiKey, BorderLayout.CENTER);
        apiPanel.add(show, BorderLayout.EAST);
        form.row("API キー", apiPanel, apiTip);
        form.note("環境変数を使う場合は ${REDMINE_API_KEY} のまま（キーをファイルに直接書かずに済みます）");
        bind(p -> apiKey.setText(nz(SettingsDocument.getString(p, "redmine.apiKey"))),
                p -> SettingsDocument.setString(p, "redmine.apiKey", new String(apiKey.getPassword())));

        form.text("プロジェクト識別子", "redmine.projectId", "例: my-project",
                "同期先プロジェクトの識別子（プロジェクトの URL の /projects/xxxx の xxxx）");
        return form.panel();
    }

    private JComponent filesTab() {
        DefaultTableModel model = new DefaultTableModel(new Object[] { "パス", "ルートチケット番号（省略可）" }, 0);
        JTable table = table(model);
        table.getColumnModel().getColumn(0).setPreferredWidth(420);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.setToolTipText("相対パスはこの設定ファイルのフォルダが基準。ルートチケット番号を書くと、WBS の一番上の行がそのチケットの子になります");

        JButton add = new JButton("追加...");
        add.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(document.getBaseDir().toFile());
            chooser.setMultiSelectionEnabled(true);
            chooser.setFileFilter(new FileNameExtensionFilter("WBS ファイル（*.xlsx, *.xlsm, *.csv）", "xlsx", "xlsm",
                    "csv"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                stopEditing(table);
                for (File file : chooser.getSelectedFiles()) {
                    model.addRow(new Object[] { document.toConfigPath(file.toPath()), "" });
                }
            }
        });

        bind(p -> {
            model.setRowCount(0);
            for (SettingsDocument.FileEntry file : SettingsDocument.getFiles(p)) {
                model.addRow(new Object[] { file.path(), file.rootIssueId() });
            }
        }, p -> {
            stopEditing(table);
            List<SettingsDocument.FileEntry> files = new ArrayList<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                files.add(new SettingsDocument.FileEntry(cell(model, i, 0), cell(model, i, 1)));
            }
            SettingsDocument.setFiles(p, files);
        });

        Form form = new Form();
        form.note("このプロジェクトを更新する WBS ファイル（Excel・CSV）。複数ある場合はすべて書きます。");
        form.note("書いておくと、1つのファイルだけを同期しても、ほかのファイルのチケットは削除扱いになりません。");
        form.note("run.bat にファイルをドロップせずに実行すると、ここに書いた順にすべて同期します。");
        form.wide(tableWithButtons(table, model, add));
        return form.panel();
    }

    private JComponent columnsTab() {
        Form form = new Form();
        form.note("WBS ファイルの見出し（1行目）の名前（標準フィールドの取込列名）。空欄なら「既定」の名前を使います。");
        form.note("--export で出力する Excel もこの列名で出力するので、そのまま取り込めます。");
        form.text("チケットID 列", "sync.columns.ticketIdColumn", "チケットID",
                "空欄の行は新規作成（作成したチケット番号が書き戻される）、値がある行は更新");
        form.text("トラッカー列", "sync.columns.trackerColumn", "トラッカー", "トラッカー名を書く列");

        DefaultTableModel hierarchy = new DefaultTableModel(new Object[] { "階層列（浅い順）" }, 0);
        JTable hierarchyTable = table(hierarchy);
        hierarchyTable.setToolTipText("親子関係を決める列（浅い順）。空なら 大分類 → 中分類 → 小分類 → 成果物 → タスク");
        bind(p -> {
            hierarchy.setRowCount(0);
            for (String column : SettingsDocument.getStringList(p, "sync.columns.hierarchy")) {
                hierarchy.addRow(new Object[] { column });
            }
        }, p -> {
            stopEditing(hierarchyTable);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < hierarchy.getRowCount(); i++) {
                values.add(cell(hierarchy, i, 0));
            }
            SettingsDocument.setStringList(p, "sync.columns.hierarchy", values);
        });
        JButton addLevel = new JButton("追加");
        addLevel.addActionListener(e -> {
            stopEditing(hierarchyTable);
            hierarchy.addRow(new Object[] { "" });
            int row = hierarchy.getRowCount() - 1;
            hierarchyTable.editCellAt(row, 0);
        });
        JComponent hierarchyPanel = tableWithButtons(hierarchyTable, hierarchy, addLevel);
        hierarchyPanel.setPreferredSize(new Dimension(400, 170));
        form.row("階層列", hierarchyPanel,
                "親子関係を決める列（浅い順）。空なら 大分類 → 中分類 → 小分類 → 成果物 → タスク");
        form.check("階層列の空欄を前の行の値で補う", "sync.columns.fillDownHierarchy", false,
                "オフ（既定）: 空欄は「その階層を飛ばす」（例: 大分類の直下のタスク）。"
                        + "オン: 空欄を前の行の値で補う（旧来の動作。階層を飛ばせない）");
        form.text("件名列", "sync.columns.subjectColumn", "タスク",
                "値があればチケットの件名にする列（空欄の行は一番深い階層列の値が件名）");
        form.text("ステータス列", "sync.columns.statusColumn", "ステータス", "ステータス名を書く列");
        form.text("担当者列", "sync.columns.assigneeColumn", "担当",
                "担当者（ログインID・氏名・メールアドレス・グループ名）を書く列");
        form.text("着手予定（開始日）列", "sync.columns.startDateColumn", "着手予定", "開始日にする列");
        form.text("完了予定（期日）列", "sync.columns.dueDateColumn", "完了予定", "期日にする列");
        form.text("着手実績 列", "sync.columns.startActualColumn", "着手実績",
                "ステータスの決め方が BY_DATES でステータス列が空欄のとき、値があれば「進行中」にする列");
        form.text("完了実績 列", "sync.columns.dueActualColumn", "完了実績",
                "ステータスの決め方が BY_DATES でステータス列が空欄のとき、値があれば「完了」にする列");
        form.text("進捗率列", "sync.columns.progressColumn", "進捗率", "進捗率（0〜100）を書く列");
        form.text("説明列", "sync.columns.descriptionColumn", "説明", "チケットの説明にする列");
        form.note("同期結果を書き戻す列（なければ見出しの末尾に追加。値を変えても Redmine は更新されません）");
        form.text("最終同期日時 列", "sync.columns.lastSyncedAtColumn", "最終同期日時",
                "最終同期日時（yyyy/MM/dd HH:mm:ss）を書き戻す列");
        form.text("同期結果 列", "sync.columns.syncResultColumn", "同期結果", "作成／更新／変更なし／エラー／未実行 を書き戻す列");
        form.text("エラー理由 列", "sync.columns.syncErrorColumn", "エラー理由", "エラー・未実行の理由を書き戻す列");
        return form.panel();
    }

    private JComponent excelTab() {
        Form form = new Form();
        form.note("Excel の読み込み元（優先順位は テーブル ＞ シート ＞ 先頭シート。CSV では使いません）");
        form.text("テーブル名", "sync.excel.table", "（なし）",
                "Excel のテーブル名（見出し行と範囲だけを読む。数式は計算済みの値を読む）。"
                        + "同梱の sample-wbs.xlsx は「取込表」");
        form.text("シート", "sync.excel.sheet", "先頭シート", "シート名 または 1始まりの番号（値のある最初の行が見出し）");
        form.note("ユーザー・グループの読み込み元（そのシートがなければ同期しません）");
        form.text("ユーザーのシート", "sync.users.sheet", "ユーザー", "ユーザーを読み込むシート名");
        form.text("グループのシート", "sync.groups.sheet", "グループ", "グループを読み込むシート名");
        form.text("グループメンバーのシート", "sync.groupMembers.sheet", "グループメンバー",
                "グループのメンバー（1行に グループ名・ログインID）を読み込むシート名。ここに書いたグループは、"
                        + "シートの行がメンバーのすべてになる");
        return form.panel();
    }

    private JComponent statusTab() {
        Form form = new Form();
        form.check("ステータスを反映する", "sync.status.enabled", false,
                "オン: ステータス列（または日付）からチケットのステータスを決めて Redmine に送る");
        JComboBox<String> mode = new JComboBox<>(new String[] { "", "BY_DATES", "FIXED" });
        mode.setEditable(true);
        String modeTip = "BY_DATES（既定）: ステータス列が空欄なら着手実績・完了実績の有無から決める。FIXED: 固定値を使う";
        mode.setToolTipText(modeTip);
        form.row("決め方（mode）", mode, modeTip);
        bind(p -> mode.setSelectedItem(nz(SettingsDocument.getString(p, "sync.status.mode"))),
                p -> SettingsDocument.setString(p, "sync.status.mode", String.valueOf(mode.getSelectedItem())));
        form.text("固定値（FIXED のとき）", "sync.status.fixed", "New", "mode が FIXED のときに使うステータス（番号または名前）");
        form.row("ステータスの対応表", mapTable("sync.status.statusMap", "ステータス名（WBS）", "Redmine のステータス（番号または名前）", false),
                "WBS のステータス名 → Redmine のステータス番号。ここにない名前は Redmine のステータス名から探す");
        form.number("削除扱いのステータス番号", "sync.deletion.statusId", "（なし＝警告のみ）",
                "Excel から消えたチケット（Redmine で手動作成したものを含む）を、このステータスに変更する。"
                        + "空欄なら候補をログに警告として出すだけで何も変更しない");
        form.row("トラッカーの対応表", mapTable("sync.trackerMap", "トラッカー名（WBS）", "トラッカーID（番号）", true),
                "トラッカー列の名前 → トラッカーID。ここにない名前は Redmine のトラッカー名から自動で探す");
        return form.panel();
    }

    private JComponent parentsTab() {
        Form form = new Form();
        form.note("仮想親チケット: WBS に親の行がない階層（例: 大分類の行がないタスク）の親チケットを自動で作る");
        form.check("仮想親チケットを作る", "sync.virtualParents.enabled", false,
                "オフ（既定）: 親の行がないとエラー。オン: 仮想親チケットを作る（仮想親の件名は Redmine で変えないこと）");
        // 別名 tracker: で書かれていた場合も読み、保存時は trackerId: にそろえる
        HintTextField trackerField = new HintTextField("既定: サマリ");
        form.row("仮想親のトラッカー", trackerField, "仮想親のトラッカー（名前または番号）");
        bind(p -> {
            String value = SettingsDocument.getString(p, "sync.virtualParents.trackerId");
            trackerField.setText(nz(value != null ? value : SettingsDocument.getString(p, "sync.virtualParents.tracker")));
        }, p -> {
            SettingsDocument.remove(p, "sync.virtualParents.tracker");
            SettingsDocument.setString(p, "sync.virtualParents.trackerId", trackerField.getText());
        });

        form.note("親チケットの自動完了: 子チケットがすべて完了した親チケットを、同期の最後に自動で完了にする");
        form.check("親チケットを自動で完了にする", "sync.autoCloseParents.enabled", false,
                "子は Redmine 上のチケットすべて（Excel にないものも含む）で判定。完了した親を未完了に戻すことはしない。"
                        + "ルートチケットは完了にしない");
        form.text("完了にするステータス", "sync.autoCloseParents.status", "完了",
                "名前または番号。空欄なら statusMap の「完了」→ Redmine の「完了」「終了」");
        form.check("子が着手済みなら親を「進行中」にする", "sync.autoCloseParents.inProgress", true,
                "子に着手済み（新規でない）のチケットがある新規の親を「進行中」にする");
        form.text("進行中にするステータス", "sync.autoCloseParents.inProgressStatus", "進行中",
                "名前または番号。空欄なら statusMap の「進行中」→ Redmine の「進行中」");
        form.check("Excel にない子チケットを無視して判定する", "sync.autoCloseParents.ignoreNotInExcel", false,
                "オン: Redmine で手動作成した子チケットなどを無視して判定する（無視した子は変更しない）");

        form.note("同期結果の書き戻し");
        form.check("各行に同期結果を書き戻す", "sync.resultColumns.enabled", true,
                "各行に「最終同期日時」「同期結果」「エラー理由」を書き戻す（dry-run では書かない）");
        return form.panel();
    }

    // ---------------------------------------------------------------- 部品

    /** 画面の部品とプロジェクトの値の対応 */
    private static final class Binding {
        final Consumer<Map<String, Object>> load;
        final Consumer<Map<String, Object>> store;

        Binding(Consumer<Map<String, Object>> load,
                Consumer<Map<String, Object>> store) {
            this.load = load;
            this.store = store;
        }
    }

    private void bind(Consumer<Map<String, Object>> load,
            Consumer<Map<String, Object>> store) {
        bindings.add(new Binding(load, store));
    }

    /** ラベルと入力欄を縦に並べるフォーム */
    private final class Form {
        private final JPanel panel = new JPanel(new GridBagLayout());
        private int row;

        Form() {
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        }

        void text(String label, String key, String hint, String tooltip) {
            // 「（…）」「例: …」はそのまま、それ以外は「既定: …」と表示する
            HintTextField field = new HintTextField(hint == null || hint.startsWith("（") || hint.startsWith("例:")
                    ? hint : "既定: " + hint);
            row(label, field, tooltip);
            bind(p -> field.setText(nz(SettingsDocument.getString(p, key))),
                    p -> SettingsDocument.setString(p, key, field.getText()));
        }

        void number(String label, String key, String hint, String tooltip) {
            HintTextField field = new HintTextField(hint);
            row(label, field, tooltip + "（数値）");
            bind(p -> field.setText(nz(SettingsDocument.getString(p, key))),
                    p -> SettingsDocument.setNumber(p, key, field.getText()));
        }

        void check(String label, String key, boolean defaultValue, String tooltip) {
            JCheckBox box = new JCheckBox(label);
            box.setToolTipText(tooltip);
            GridBagConstraints c = constraints(1);
            panel.add(box, c);
            row++;
            bind(p -> box.setSelected(SettingsDocument.getBoolean(p, key, defaultValue)),
                    p -> SettingsDocument.setBoolean(p, key, box.isSelected(), defaultValue));
        }

        void row(String label, JComponent field, String tooltip) {
            JLabel l = new JLabel(label);
            l.setToolTipText(tooltip);
            field.setToolTipText(tooltip);
            GridBagConstraints lc = constraints(0);
            lc.fill = GridBagConstraints.NONE;
            lc.weightx = 0;
            lc.anchor = GridBagConstraints.NORTHWEST;
            lc.insets = new Insets(7, 0, 3, 10);
            panel.add(l, lc);
            panel.add(field, constraints(1));
            row++;
        }

        void note(String text) {
            JLabel l = new JLabel(text);
            l.setForeground(UIManager.getColor("Label.disabledForeground") != null
                    ? UIManager.getColor("Label.disabledForeground").darker() : Color.DARK_GRAY);
            GridBagConstraints c = constraints(0);
            c.gridwidth = 2;
            c.insets = new Insets(row == 0 ? 0 : 10, 0, 2, 0);
            panel.add(l, c);
            row++;
        }

        void wide(JComponent component) {
            GridBagConstraints c = constraints(0);
            c.gridwidth = 2;
            c.fill = GridBagConstraints.BOTH;
            c.weighty = 1;
            panel.add(component, c);
            row++;
        }

        private GridBagConstraints constraints(int column) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = column;
            c.gridy = row;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = column == 0 ? 0 : 1;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(3, 0, 3, 0);
            return c;
        }

        JComponent panel() {
            // 下に余白を入れて上詰めにする
            GridBagConstraints c = constraints(0);
            c.gridwidth = 2;
            c.weighty = 1;
            c.fill = GridBagConstraints.BOTH;
            panel.add(Box.createVerticalGlue(), c);
            return panel;
        }
    }

    /** 空欄のときに薄い文字で既定値を表示する入力欄 */
    private static final class HintTextField extends JTextField {
        private static final long serialVersionUID = 1L;
        String hint;

        HintTextField(String hint) {
            super(30);
            this.hint = hint;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (hint != null && getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(Color.GRAY);
                g2.setFont(getFont());
                Insets insets = getInsets();
                int y = insets.top + (getHeight() - insets.top - insets.bottom + g2.getFontMetrics().getAscent()
                        - g2.getFontMetrics().getDescent()) / 2;
                g2.drawString(hint, insets.left + 2, y);
                g2.dispose();
            }
        }
    }

    private JComponent mapTable(String key, String keyLabel, String valueLabel, boolean numeric) {
        DefaultTableModel model = new DefaultTableModel(new Object[] { keyLabel, valueLabel }, 0);
        JTable table = table(model);
        bind(p -> {
            model.setRowCount(0);
            SettingsDocument.getStringMap(p, key).forEach((k, v) -> model.addRow(new Object[] { k, v }));
        }, p -> {
            stopEditing(table);
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                values.put(cell(model, i, 0), cell(model, i, 1));
            }
            SettingsDocument.setStringMap(p, key, values, numeric);
        });
        JButton add = new JButton("追加");
        add.addActionListener(e -> {
            stopEditing(table);
            model.addRow(new Object[] { "", "" });
            table.editCellAt(model.getRowCount() - 1, 0);
        });
        JComponent panel = tableWithButtons(table, model, add);
        panel.setPreferredSize(new Dimension(400, 150));
        return panel;
    }

    private static JTable table(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setRowHeight(Math.max(table.getRowHeight(), table.getFontMetrics(table.getFont()).getHeight() + 6));
        table.setFillsViewportHeight(true);
        return table;
    }

    /** 表と「追加／削除／上へ／下へ」ボタン */
    private static JComponent tableWithButtons(JTable table, DefaultTableModel model, JButton add) {
        JButton remove = new JButton("削除");
        remove.addActionListener(e -> {
            stopEditing(table);
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeRow(row);
            }
        });
        JButton up = new JButton("上へ");
        up.addActionListener(e -> move(table, model, -1));
        JButton down = new JButton("下へ");
        down.addActionListener(e -> move(table, model, 1));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        for (JButton button : new JButton[] { add, remove, up, down }) {
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
            buttons.add(button);
            buttons.add(Box.createVerticalStrut(4));
        }
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(400, 200));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    private static void move(JTable table, DefaultTableModel model, int delta) {
        stopEditing(table);
        int row = table.getSelectedRow();
        int target = row + delta;
        if (row < 0 || target < 0 || target >= model.getRowCount()) {
            return;
        }
        model.moveRow(row, row, target);
        table.setRowSelectionInterval(target, target);
    }

    private static void stopEditing(JTable table) {
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private static String cell(DefaultTableModel model, int row, int column) {
        Object value = model.getValueAt(row, column);
        return value == null ? "" : value.toString();
    }

    private static JScrollPane scroll(JComponent component) {
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

}
