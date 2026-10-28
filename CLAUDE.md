# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
あなたはマネージャーでAgentオーケストレーターです。あなたは絶対に実装せず、全てsubaagentやtask agentに委託すること
タスクは超細分化し、PDCAサイクルを構築すること。
codexスキルを有効に使用し、Claudeのトークン消費をなるべく控えること。

## 実行計画（DB廃止とWindows配布版：属人化の解消）
背景: むさしさんの指示。Java + PostgreSQL + Docker + Jenkins は他の人には重く、ツールが1人に依存している。Windowsの非開発者が Java もDBも入れずに使えるようにする。
方針（リード推奨の既定案。DB廃止は独立したコミットに分離し、むさしさんの確認待ち）: DBを廃止し、Redmineの現在の状態を正とする。
1. DB廃止: Spring Data JPA・Flyway・PostgreSQL・H2・issue_link（エンティティ／リポジトリ／V1〜V4）を削除。DataSource なしで起動する。
2. 取得: 差分の前に同期先プロジェクトのチケットを1回のページング取得で全件取得（`GET /issues.json?project_id=...&subproject_id=!*&status_id=*&limit=100&offset=...`）。
3. 更新確認: 更新対象の存在・プロジェクト確認は取得済み一覧で行う（一覧にない場合のみ個別GETで「存在しない／別プロジェクト」を判別）。
4. 変更なしスキップ: ハッシュではなく、送信する項目（件名・トラッカー・ステータス・親・日付・進捗率・担当者・カスタムフィールド）をRedmineの現在値と比較（IssueComparator）。`--force-update` は維持。
5. 論理削除: プロジェクト内でExcelのチケットIDに無いチケット（手動作成分も含む。既に `deletion.statusId` のものは除外）。statusId 未設定なら警告ログのみ。
6. CLI専用化: spring-boot-starter-web を外し `web-application-type: none`。`--config` 省略時は SYNC_CONFIG_PATH → カレント → exe/jar と同じフォルダの sync-config.yml。ログ既定は `./logs`。`--help`。
7. 配布: jpackage の app-image（Java同梱、`--win-console`）。`packaging/package-windows.ps1`・`package-linux.sh`・`modules.txt`・`dist/`（設定サンプル・bat）。GitHub Actions `package.yml` で ubuntu テスト → windows-latest でビルドし artifact `redmineUpster-windows`。
8. 文書: `docs/USER_GUIDE.md`（日本語1ページ）、README・DEPLOY・setup から PostgreSQL/Docker 手順を削除。

### 進捗
- 2026-09-28: DB廃止・Redmine全件取得・比較によるスキップ・手動作成分を含む論理削除を実装。テスト更新・追加（比較、候補選定、ページング、一覧にない更新対象）。`mvn -B test` 91件成功。
- 2026-09-28: CLI既定値（設定ファイル自動検出・logs・--help）、DB関連設定/文書の削除、配布スクリプト・ワークフロー・利用者ガイドを追加。
- 2026-09-28: Linux で jpackage app-image（jlink ランタイム）を作成し、疑似Redmineで通し確認（dry-run → 作成・書き戻し → 再実行で全件スキップ → 件名/日付/親の変更と行削除で更新・論理削除 → 別PJ/存在しないIDはその行だけエラー → 検証エラーで未更新、Shift_JIS CSV・xlsx も確認）。起動〜完了 約1.3秒。
- 2026-09-28: 階層の飛ばしに対応（大分類・中分類の直下のタスクなど）。階層列の前行値補完を既定で廃止し、xlsx の縦セル結合は結合範囲の値として読む。旧動作は `sync.columns.fillDownHierarchy: true`。大分類が空欄の行は検証エラー。
- 2026-09-28: 不具合修正: 設定の階層列（大分類〜タスク）のうち共通の「タスク」列だけがファイルにあると設定がそのまま使われ、Lv.* 形式のCSVが「階層列がすべて空」になっていた。ファイルにある列が最も多い候補（設定／既定／Lv.01〜06＋タスク）を使い、警告ログに出すよう変更。fillDownHierarchy の補完は DiffCalculator で決定済みの階層列に対して行う。
- 2026-09-28: Excel の読み込み元を選べるようにした（`sync.excel.table` ＞ `sync.excel.sheet` ＞ 先頭シート、CLI `--table`/`--sheet`）。数式は計算済みの値を読み（単一セル参照の参照先が空欄なら空欄、日付シリアル値も日付として扱う）、チケットIDの書き戻しは同じシート・テーブルの行へ。チケットID列が単一セル参照の数式なら参照先へ書き込み、それ以外の数式は上書きせずエラー。テーブルにチケットID列がなければRedmineに書き込む前にエラー。
- 2026-09-28: Excel のサンプル `samples/sample-wbs.xlsx` / `.xlsm`（マクロなし）を追加（1枚目「WBS」=人が見る表: タイトル行・大分類/中分類の縦結合・色・未使用列・区切り行、2枚目「取込」=テーブル「取込表」: WBS への単一セル参照の数式。大分類・中分類の直下のタスクを含む）。作成は `src/test/.../samples/SampleWorkbookGenerator`（POI、計算結果を保存＋開いたときに再計算）。配布版の設定は `excel.table: "取込表"` を有効にし、zip に `sample-wbs.xlsx` を同梱（CSV では設定のテーブル指定は info ログで無視）。jar と疑似Redmineで xlsx・xlsm とも dry-run → 作成（飛ばし階層の親も正しい）→ WBS シートへ書き戻し → 再実行で全件スキップを確認。xlsm は書き戻し後も xlsm のまま。
- 2026-09-28: タグ `v*` の push で GitHub Release を作成し `redmineUpster-windows-<タグ>.zip` を添付（package.yml に release ジョブ、`contents: write` はそのジョブだけ。jpackage のバージョンはタグの数字部分）。ブランチの push は従来どおり artifact。README・USER_GUIDE に Releases からのダウンロード（ログイン不要）と自前ビルド手順を記載。タグの作成・push は未実施。
- 2026-09-28: 仮想親チケットの作成モードを追加（むさしさんの依頼。既定は無効＝従来どおり親行なしは検証エラー）。`sync.virtualParents.enabled` / `trackerId`（名前またはID、既定「サマリ」）と CLI `--virtual-parents` / `--no-virtual-parents`。親行がない行の祖先を最上位まで仮想親として補い（ファイルの行が優先、飛ばし階層は空欄のまま）、日付は子の最小/最大・進捗率は子の平均（切り捨て）、ステータスは送らない、作成時だけ説明に目印。DBがないため毎回 Redmine から（親チケット・件名完全一致・トラッカー、Excel行のIDと論理削除ステータスは除外、目印付き優先→最小ID）で見つけ直す（`VirtualParentMatcher`）。今回必要な仮想親は論理削除しない・不要になったものは候補。テスト `VirtualParentTests` 追加で `mvn -B test` 128件成功。jar と疑似Redmineでユーザーの Lv.* CSV（実装の祖先行なし）を確認: 仮想親4件作成→書き戻し11件（仮想親は書き戻さない）→再実行で15件全スキップ・書き込み0→実装の行を消すと仮想親を含む7件を論理削除→戻すと仮想親を新規作成して子を付け替え→再実行で全スキップ。
- 2026-10-02: 複数の Excel で1つのプロジェクトを順に更新できるようにした（設定は1つ）。`--file` の複数指定（指定順）と `sync.files`（相対パスは設定ファイルのフォルダ基準。`--file` 省略時に書いた順に同期）。先に全ファイルを解析・検証し、エラーがあれば書き込まない。ファイルごとに同期・書き戻し（2つ目以降は Redmine を取得し直して再検証）。論理削除は全ファイルの後に1回だけ、対象は「同期したファイル・`sync.files` のファイル（未同期分もチケットIDと必要な仮想親を読む）・今回作成したチケット（仮想親含む）」のどれにもないチケット。`sync.files` のファイルを読めない／検証エラーなら論理削除しない。run.bat / run-dry-run.bat は複数ドロップ対応（ドロップなしは `sync.files`）。テスト `MultiFileSyncTests` 追加で `mvn -B test` 159件成功。
- 2026-10-19: WBSごとのルートチケットを追加。`sync.files` の要素に `{ path, rootIssueId }`（`rootIssue`・数字の文字列も可）と CLI `--root-issue=<番号>`（全ファイルに適用・設定より優先）。最上位の行（最上位の仮想親を含む）の親をルートにし（作成・更新・比較スキップ、仮想親の再識別も親=ルートで探す）、書き込み前に存在（一覧になければ個別GET、別PJ可）・論理削除ステータスでない・行のチケットIDと重複しないことを検証。ルートとその祖先は論理削除しない。今回同期する全ファイルにルートがあれば論理削除はルートの子孫に限定（1つでもなければプロジェクト全体）。テスト `RootIssueTests` 追加で `mvn -B test` 173件成功。
- 2026-10-20: 親チケットの自動完了を追加（むさしさんの依頼。既定は無効）。`sync.autoCloseParents.enabled` / `status`（名前またはID、省略時は statusMap「完了」→ Redmine「完了」「終了」→ 最初の終了ステータス）。全ファイルの同期・論理削除の後、子がすべて is_closed の親を深い順に完了（`ParentAutoCloser`。Redmineだけの子も数える、未完了に戻さない、対象は今回同期したチケットとその祖先、ルートチケットとその祖先は除外）。dry-run は作成・更新・論理削除の予定（作成予定は負の仮ID）を反映して DRY_RUN AUTO_CLOSE を出す。完了にした行のステータス列（ある場合だけ）へステータス名を書き戻し。ステータス列が空欄で完了済みの親の行は現在のステータスを送り、開き直さない。テスト `AutoCloseParentsTests`。続けて、子に新規でない（進行中・完了など）チケットがある新規（Redmineのステータス一覧で最初の未完了ステータス）の親を再帰的に進行中にする処理を追加（`inProgressStatus`、省略時は statusMap「進行中」→ Redmine「進行中」。`inProgress: false` で無効。新規に戻さない）。
- 2026-10-23: 同期結果の書き戻しを追加（むさしさんの依頼）。チケットの表の各行に「最終同期日時」（yyyy/MM/dd HH:mm:ss の文字列、実行内で同じ値）・「同期結果」（作成／更新／変更なし／エラー／未実行）・「エラー理由」（ログと同じ理由。成功時は空にして前回のエラーを消す）を、チケットIDと同じ1回の書き込み（.bak も1つ）で書く。列名は `sync.columns.lastSyncedAtColumn` / `syncResultColumn` / `syncErrorColumn`、無効化は `sync.resultColumns.enabled: false` / CLI `--no-result-columns`。列がなければ末尾に追加（テーブルは右に拡張、すぐ右が使用中なら警告して追加しない）。3列は解析時に取り除き差分に使わない。数式セルは上書きせず警告。入力エラーで中止した場合もエラーの行=エラー＋理由、ほかの行=未実行（`DiffPlan.rowErrors`）。中止（例外）時も未実行＋理由。書き込み失敗はエラーログのみ（IDを書けなかった場合だけ終了コード1）。dry-run では書かない。テスト `SyncResultColumnsTests`・`TicketIdWriterResultTests` 追加で `mvn -B test` 187件成功。jar と疑似Redmineで xlsx（取込表テーブル）・xlsm・CSV の作成→再実行で全行変更なし（書き込み0）→入力エラー→行のAPIエラー（422／存在しないID、子は未実行）→修正後にエラー理由が消えることを確認。develop の親チケット自動完了（#10）とのマージ: 自動完了が有効なら書き戻しを自動完了の後へ後回しにし、チケットID・同期結果・自動で変えたステータス名をファイルごとに1回で書く（.bak は1つ。中止時も後回し分を書く）。自動で変えた行は「更新」（作成の行はそのまま）、変更に失敗した行は「エラー」＋理由。テスト2件追加。親チケットの自動完了に `sync.autoCloseParents.ignoreNotInExcel`（既定 false）を追加: true なら「子がすべて完了」「新規でない子がある」の判定を Excel で管理しているチケット（同期したファイル・`sync.files` のファイルのチケットID、今回作成したチケット・仮想親）だけで行い、Redmine だけの子は無視（変更しない。判定に使う子がない親は対象外。`sync.files` を読めなければ自動完了しない）。テスト3件追加で `mvn -B test` 204件成功。疑似Redmineで手動の未完了の子: 無効→親は未完了のまま、有効→親を完了・手動の子は変更なしを確認。
- 2026-10-25: export でルートチケット指定（--root-issue / sync.files の rootIssueId）に対応。ルート配下だけをルート基準の階層で出力（ルート自身は出力しない）。
- 2026-10-28: `--export` のグループメンバーを別シート「グループメンバー」（1行に グループ名・ログインID・氏名。メンバーなしはログインID空欄の行）に出力し、「グループ」シートの `メンバー` 列は出力しない（取り込みは従来どおり可）。取り込みは、シートに書いたグループはシートの行がメンバーのすべて・書いていないグループは変更しない・ログインID空欄の行だけなら0人・`メンバー` 列との二重指定やグループのシートにない名前はエラー（`sync.groupMembers.sheet` で変更可）。参照用シート「トラッカー」（標準フィールドの ○）「ステータス」「カスタムフィールド」（トラッカーの ○・取込列名）「カスタムフィールド選択肢」（1行に1つ）を追加（`--sync` では読まない。custom_fields が 403 なら警告して省略）。標準フィールドの取込列名を `TicketColumns` に集約し、`subjectColumn`（タスク）・`assigneeColumn`（担当）・`startActualColumn`（着手実績）・`dueActualColumn`（完了実績）を追加。サンプル・配布版の設定に全キーを既定値で明記、設定画面のタブ名を「取込列名」にして項目を追加。`mvn -B test` 233件成功。
- 2026-10-29: 「ユーザー」シートに設定の列を追加（`UserSettingColumns`）。ユーザーの属性（メール通知・通知するプロジェクト・言語・認証方式・パスワード変更を要求）と個人設定 pref（メール非公開・タイムゾーン・コメントの表示順・未保存で移動時に警告・自分の変更は通知しない・高優先度チケットを通知・自動ウォッチ・テキストエリアのフォント・最近使ったプロジェクトの表示数・履歴の既定のタブ・コードハイライトの言語・既定のチケット/プロジェクトクエリ）、パスワード再発行。空欄は変更しない、不正な値は書き込む前に検証エラー、作成・更新とも `pref` と `send_information` は "user" の外で送る（従来 send_information を user 内に送っていた不具合も修正）。API が現在値を返さない項目は値があれば毎回送る（返す場合は比較）。--export は列見出しを出力し、返す値だけ書く。テスト追加で `mvn -B test` 全件成功。
- 未実施: Windows ジョブの実行（GitHub Actions 上でのみ確認可能）、Windows 実機での exe・bat の動作確認、実Redmineでの確認。

## 実行計画（設定画面）
背景: むさしさんの指示。sync-config.yml を手で書くのがつらいため、設定を編集する画面（Swing、配布版の jlink ランタイムに java.desktop あり）を用意する。
1. 起動: `--settings [--config=...]` を `RedmineUpsterApplication.main` で Spring 起動前に判定（`settings/SettingsLauncher`）。設定ファイルは同期と同じ探し方（`SyncRunner.defaultConfigCandidates`）。headless ならエラー終了（1）。`--help` に追加。
2. 配布: `packaging/dist/settings.bat` を追加し、package-windows.ps1 で zip に同梱。
3. 読み書き: `settings/SettingsDocument`（Swing 非依存）。SnakeYAML で LinkedHashMap の木に読み、画面の項目だけ変更して全体を書き戻す（未知のキー・`${...}` は保持、空欄はキー削除、キー順は固定、文字列は "..."）。保存前に `.bak`、先頭に説明コメント。
4. 画面: `settings/SettingsWindow`。左にプロジェクト一覧（追加・複製・削除・既定）、右にタブ（接続・ファイル・列・Excel・ステータス・削除・親チケット）、保存時に検証、閉じるときに未保存の確認。
5. テスト `SettingsDocumentTests`（往復・未知キー・${...}・files の2形式・空欄の削除・.bak・SyncConfigService での再読み込み・配布版設定の往復）、文書（USER_GUIDE・README）。

### 進捗
- 2026-10-28: 設定画面・settings.bat・SettingsDocument とテストを追加。Xvfb で画面表示（日本語の表示）と、配布版 sync-config.yml の読み込み→保存→CLI（--sync --dry-run）での読み込みを確認。

## 実行計画（Excel側にRedmineチケットIDを持つ方式への変更）
背景: むさしさんの指示。Excel側に独自ID（`id`/`WBS_ID`）を持たず、階層・トラッカー・RedmineチケットIDを持つ。ExcelとRedmineのチケットを完全に一致させる。
1. 仕様確定: チケットIDあり=更新／空欄=新規。親子は階層列のみで決定（一番深い値の列=レベル、浅い列が同じで1つ浅いレベルの行が親）。
2. 列・設定: `sync.columns.ticketIdColumn`（既定「チケットID」）、`trackerColumn`（既定「トラッカー」）、`sync.trackerMap`（名前→ID、ないものは `/trackers.json`）、`sync.deletion.statusId`（論理削除ステータス）を追加。`externalKeyColumn`・`virtualParentTrackerId` は廃止。
3. 解析: 行番号（Excel表示行／CSVレコード番号）を保持。階層列の前行値補完は「より深い列に値がある空欄」だけに限定。CSVの文字コード（UTF-8/BOM/Windows-31J）を自動判定。
4. 検証（Redmineに書き込む前に失敗させる）: 親行なし・階層パス重複・チケットIDの非数値/重複・不明なトラッカー。仮想親の自動生成とWBS番号からの親推定は削除。
5. 同期実行: 親→子の順に作成し、作成した親IDを同じ実行内で子の `parent_issue_id` に使う。更新時も階層から親を再設定。更新対象が存在しない/別プロジェクトの場合はその行をエラーにして続行。
6. 書き戻し: 新規作成したIDを入力ファイルへ書き戻す（xlsxはPOI、CSVは文字コード・BOM・改行を保持）。元ファイルは `.bak`。dry-runでは書き戻さない。列がなければ末尾に追加。
7. 削除: 物理削除を廃止。issue_link（同じproject_id）にあってExcelにないチケットを論理削除候補とし、`deletion.statusId` があればステータス変更、なければ警告ログのみ。
8. DB: issue_link を (issue_id, project_id) で一意に変更（Flyway V4、external_keyはNULL可、重複行は整理）。
9. CLI: `--relink-parent`・`--reset-sync` を廃止（警告して無視）。`--dry-run`・`--debug`・`--force-update` は維持。
10. テスト・サンプル・README・本ファイルを更新し、`mvn -B test` の全件成功を確認。

### 進捗
- 2026-09-28: 計画を作成（`/mnt/project-files/plans/excel-ticket-id-plan.md`）。Q1〜Q3 をむさしさんが決定（書き戻しあり／仮想親廃止・親不在はエラー／論理削除）。
- 2026-09-28: 列・設定、解析（行番号・補完ルール・文字コード判定）、DiffCalculator／SyncExecutor の書き換え、書き戻し（TicketIdWriter）、issue_link の V4 マイグレーションを実装。
- 2026-09-28: テストを新仕様に更新・追加（新規/更新判定、親子判定、トラッカー解決、論理削除、書き戻し、親IDの受け渡し）。`mvn -B test` 全件成功。
- 2026-09-28: PostgreSQL 16 で V1〜V4 の適用と重複整理を確認。疑似Redmineでの通し確認（作成→書き戻し→再実行で全件スキップ→付け替え更新・論理削除→検証エラーで未更新）を実施。
- 2026-09-28: samples（CSV・設定）、README、docs/DEPLOY.md を新しい列構成に更新。
- 未実施: 実Redmine（テスト環境）での dry-run → 本実行 → 再実行の確認。

## 実行計画（本番バグ対応）
1. `testResults/sync-config.yml` と `testResults/test.csv` を確認し、階層列と日付形式、ステータス列の実データを整理する。
2. 親子関係推定の原因を特定し、必要なら親キー推定方式（WBS_ID派生 or 親列の追加）を決定する。
3. 日付パースを `yyyy/M/d` と `yyyy/M/dd` などゼロ埋めなしにも対応させる。
4. `start_date`/`due_date` の取り込み列名を `sync.columns` で指定可能にし、既存デフォルトと互換を維持する。
5. CSVのステータス列（例: `ステータス`）を優先できるようにし、更新不可時はログで判別可能にする。
6. 進捗があったらこの計画を更新する。

## 実行計画（仮想親チケットの自動生成）
1. 親不在の判定条件を確定（CSVに親行なし、issue_linkにも無し）。
2. 親外部キーはWBSの親キー（例: `1.1.1.1.1` → `1.1.1.1`）で固定。
3. 親のsubjectは階層名、トラッカーはサマリ（ID=6）、ステータスはBY_DATES。
4. 親の `start_date` / `due_date` は子の最小/最大から毎回集計して更新する。
5. 仮想親DiffItemの生成と同期順序制御（親→子）を実装し、ログで判別できるようにする。
6. 設定/READMEを更新し、必要ならテストを追加する。

### 進捗
- 2026-01-16: `testResults` の設定/CSVを確認。日付・ステータス列の仕様追加、親子推定の補助ロジック追加に着手。
- 2026-01-16: 列指定/親子推定/日付パース/ステータス反映を実装し、設定読み込み・日付パーサ・DiffCalculatorのテストを追加。
- 2026-01-16: Mockitoの実行環境制約に対応し、テスト設定を調整して検証完了。
- 2026-01-16: 全テスト実行で成功を確認。
- 2026-01-16: 同期終了時のエラー詳細（外部キーと理由）を出力するよう改善。
- 2026-01-16: 日付型カスタムフィールドを正規化する設定を追加。
- 2026-01-16: 追加修正分のテストを実行して成功を確認。
- 2026-01-16: CSVの日本語ステータスをIDに変換するstatusMapを追加。
- 2026-01-16: 仮想親チケット自動生成の方針を確定（親キー/subject/集計/トラッカー/ステータス）。
- 2026-01-16: 仮想親チケットの自動生成と日付集計、トラッカー上書きを実装。
- 2026-01-16: 仮想親トラッカーIDを設定化し、テストを更新。
- 2026-01-16: 仮想親は更新時にステータスを送らないよう変更。
- 2026-01-16: 仮想親の外部キーをカスタムフィールドに反映。
- 2026-01-16: 進捗率の集計とdone_ratio反映、仮想親のステータス自動更新を追加。
- 2026-01-16: Excel日付セルをISO日付として読み取るよう修正。
- 2026-01-16: BY_DATES時に既定のstatus_idへ変換するロジックを追加。
- 2026-01-16: 仮想親のステータスを子の状態集計で更新するよう変更。
- 2026-01-16: CSV未掲載の外部キーを削除対象としてRedmine/issue_linkから削除する処理を追加。
- 2026-01-16: 削除時の並び順が子→親になるよう深さ判定を調整。
- 2026-01-16: DELETEでもAPIキーを送るようRedmineClientを修正。
- 2026-01-16: issue_linkにハッシュとprojectIdを保存し、同一内容の更新をスキップ。
- 2026-01-16: 削除対象をproject_id一致のissue_linkに限定。
- 2026-01-16: 親子再紐づけ用のオプションを追加し、relink時は削除をスキップ。
- 2026-01-16: 更新スキップを無効化するオプションを追加。

## プロジェクト概要

RedmineのチケットをCSV/Excelファイルから同期するSpring BootのCLI（Webサーバー・DBなし）。Excelの「チケットID」列（空欄=新規作成、値あり=更新）でRedmineのチケットと対応させ、階層列（大分類→中分類→小分類→成果物→タスク）から親子関係を決める。新規作成したIDは入力ファイルへ書き戻す。

## 開発コマンド

```bash
# ビルド
./mvnw clean package

# CLI実行（同期）
java -jar target/redmineUpster.jar --sync --config=sync-config.yml --project="プロジェクト名" --file=input.csv

# CLI実行（dry-run）
java -jar target/redmineUpster.jar --sync --config=sync-config.yml --project="プロジェクト名" --file=input.csv --dry-run

# 全テスト実行
./mvnw test

# 単一テストクラス実行
./mvnw test -Dtest=SpreadsheetParserTests

# 単一テストメソッド実行
./mvnw test -Dtest=SpreadsheetParserTests#testMethodName

# 設定画面（sync-config.yml を編集。配布版は settings.bat）
java -jar target/redmineUpster.jar --settings --config=sync-config.yml

# Windows 配布版（Java同梱 exe）の作成（Windows上）／Linux版（動作確認用）
powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1
bash packaging/package-linux.sh
```

## アーキテクチャ

### 技術スタック
- Java 17 / Spring Boot 3.5
- DBなし（Redmineの現在の状態を正とする）
- jpackage（Java同梱のWindows配布版）
- OpenCSV / Apache POI（CSV/Excel解析）
- Lombok

### レイヤー構造
- `cli/`: CLIコマンド・同期実行
- `settings/`: 設定画面（`--settings`。Swing、Spring なし）と sync-config.yml の読み書き
- `service/`: ビジネスロジック
- `repository/`: JPA リポジトリ
- `domain/`: エンティティ
- `config/`: 設定クラス
- `util/`: ユーティリティ

### データフロー（CLI実行）
1. `SyncCommand`がCLI引数を解析
2. `SyncRunner`が設定・ファイルを読み込み
3. `SpreadsheetParser`でCSV/Excel解析
4. `DiffCalculator`で差分計算・検証（インメモリ。エラーがあればRedmineに書き込まず終了）
5. `RedmineClient.listProjectIssues`で同期先プロジェクトのチケットを全件取得（ページング）
6. `SyncExecutor`でRedmine同期実行（親→子の順に作成・更新。取得した現在値と同じならスキップ、Excelにないプロジェクト内のチケットは論理削除）
7. `TicketIdWriter`で新規作成したチケットIDを入力ファイルへ書き戻し（`.bak`を保存）
8. `FileLogger`でログ出力（既定: `./logs`）

### テスト環境
テストは`@ActiveProfiles("test")`を使用（`application-test.yml`はテスト用設定ファイルのパスのみ。DBは不要）。

## 環境変数

- `SYNC_CONFIG_PATH`: `--config` 省略時の設定ファイル（任意）
- `REDMINE_BASE_URL`: RedmineベースURL
- `REDMINE_API_KEY`: Redmine APIキー
- `REDMINE_PROJECT_ID`: 同期先プロジェクトID

---

## 移行完了

Web API方式から**設定ファイル + Jar + Jenkins**によるCLI実行方式への移行が完了。

### 実装済みコンポーネント
- `cli/SyncCommand.java` - CommandLineRunner
- `cli/SyncRunner.java` - 統合フロー
- `cli/DiffCalculator.java` - 差分計算（インメモリ）
- `cli/SyncExecutor.java` - 同期実行
- `cli/FileLogger.java` - ファイルログ出力

### DBスキーマ
DBは廃止（2026-09）。旧 `issue_link` テーブルは使用しない。

---

## 🔄 現在の作業状況（2026-01-16）

### 完了タスク
| タスク | 状態 |
|--------|------|
| セットアップガイド作成（redmine, docker, java17, jenkins） | ✅ 完了 |
| フォルダ構成の最適化 | ✅ 完了 |

### 進行中タスク
| タスク | 状態 | 備考 |
|--------|------|------|
| コードリファクタリング | 🔄 中断 | カバレッジ前に中断 |
| テストカバレッジ100%達成 | ⏳ 未着手 | |

### フォルダ構成最適化の結果
```
redmineUpster/
├── .env.example          # 環境変数テンプレート
├── CLAUDE.md
├── README.md
├── .github/workflows/package.yml  # テスト → Windows配布版ビルド（artifact、タグ v* で Release に zip 添付）
├── docs/
│   ├── USER_GUIDE.md     # 利用者ガイド（Windows配布版）
│   ├── DEPLOY.md         # デプロイ手順書
│   ├── ops/              # 運用関連
│   └── setup/            # セットアップガイド
│       ├── README.md
│       ├── java17.md
│       ├── jenkins.md
│       └── redmine-apache-passenger.md
├── packaging/            # jpackage 配布スクリプト・同梱ファイル（dist/ に run.bat・settings.bat など）
├── samples/
│   ├── sync-config.example.yml  # 設定ファイルサンプル
│   └── wbs_*.csv         # WBSサンプル
└── src/
```

### 次のアクション
1. コードリファクタリング（cli/, service/, config/）
2. テストカバレッジ100%達成（分岐網羅）
3. コミット・プッシュ
