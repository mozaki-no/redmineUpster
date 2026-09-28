# redmineUpster 利用者ガイド（Windows 版）

Excel/CSV の WBS を Redmine のチケットに反映するツールです。**Java やデータベースのインストールは不要**です（Java は同梱）。

## 1. ダウンロードと展開

1. GitHub のリポジトリで **Actions → 「package」→ 最新の成功した実行** を開き、下の Artifacts から **`redmineUpster-windows`** をダウンロードします（Release に zip が添付されている場合はそちらでも可）。
2. zip を好きなフォルダ（例: `C:\tools\redmineUpster`）に展開します。管理者権限は不要です。

展開すると次のファイルがあります。

| ファイル | 用途 |
|----------|------|
| `redmineUpster.exe` | 本体 |
| `sync-config.yml` | 設定ファイル（最初に編集する） |
| `run-dry-run.bat` | **確認用**。WBS ファイルをこの上にドラッグ＆ドロップすると、Redmine を変更せずに結果だけ表示 |
| `run.bat` | **本実行**。WBS ファイルをドラッグ＆ドロップすると Redmine に反映 |
| `sample-wbs.csv` | WBS の書き方の例 |
| `logs\` | 実行ログ（初回実行時に作成） |

## 2. 設定（sync-config.yml）

メモ帳で `sync-config.yml` を開き、次の3つを書き換えて **UTF-8 で保存** します。

- `baseUrl`: Redmine の URL（例: `https://redmine.example.com`）
- `projectId`: 同期先プロジェクトの識別子（プロジェクトの URL `/projects/xxxx` の `xxxx`）
- `apiKey`: Redmine の「個人設定」→「API アクセスキー」
  - 直接書く: `apiKey: "abcd1234..."`（ファイルを他人に渡さないこと）
  - 環境変数で渡す: `apiKey: "${REDMINE_API_KEY}"` のままにして、コマンドプロンプトで `setx REDMINE_API_KEY abcd1234...` を1回実行（新しく開いたウィンドウから有効）

トラッカー名や列名が違う場合は `trackerMap` / `columns` を合わせてください。

## 3. WBS ファイルの列

| 列 | 内容 |
|----|------|
| `チケットID` | 空欄＝新規作成（作成したチケット番号が自動で書き込まれる）、番号あり＝そのチケットを更新 |
| `トラッカー` | `タスク`、`サマリ` など |
| `大分類`〜`タスク` | 階層。一つ上の階層の行が親チケットになる（親の行も必ず書く） |
| `着手予定` / `完了予定` / `ステータス` / `進捗率` など | チケットの開始日・期日などに反映 |

## 4. 確認（dry-run）→ 本実行

1. **WBS ファイル（.xlsx / .csv）を閉じてから**、`run-dry-run.bat` の上にドラッグ＆ドロップします。
   - `DRY_RUN CREATE`（新規作成予定）、`DRY_RUN UPDATE ... changes=…`（変更予定の項目）、`DRY_RUN LOGICAL_DELETE`（削除扱い予定）が表示されます。Redmine は変更されません。
2. 内容に問題がなければ `run.bat` にドラッグ＆ドロップし、`Y` を押して実行します。
   - 新規作成したチケット番号が WBS の `チケットID` 列に書き込まれます（元のファイルは `ファイル名.bak` として残ります）。
   - もう一度実行しても、変更がない行は `skipped update ... (no changes)` となり何もしません。

コマンドプロンプトから直接実行することもできます。

```
cd C:\tools\redmineUpster
redmineUpster.exe --sync --config=sync-config.yml --file=WBS.xlsx --dry-run
redmineUpster.exe --sync --config=sync-config.yml --file=WBS.xlsx
```

`--config` を省略すると、今いるフォルダ → `redmineUpster.exe` のあるフォルダの `sync-config.yml` を使います。`--help` で全オプションを表示します。

## 5. 注意: 削除扱い（論理削除）

Redmine のプロジェクトにあって **WBS に `チケットID` が書かれていないチケット**は「削除候補」になります。**Redmine で手動作成したチケットも対象**です。

- 既定では候補をログに警告（`論理削除候補: #123 …`）として出すだけで、何も変更しません。
- `sync-config.yml` の `deletion: statusId: 6` を有効にすると、候補のステータスをその値（例: 却下）に変更します。チケットの削除はしません。

## 6. ログ

実行するたびに `logs\sync-日時.log` ができます。うまくいかないときはこのファイルを開発担当に送ってください。詳細なログが必要なときは `--debug` を付けて実行します。

## 7. よくあるエラー

| 表示 | 原因と対処 |
|------|------------|
| `設定ファイルが見つかりません` | `sync-config.yml` を `redmineUpster.exe` と同じフォルダに置くか、`--config=` で指定 |
| `401 Unauthorized` | API キーが違う・未設定。`apiKey` か環境変数 `REDMINE_API_KEY` を確認（`setx` の後はウィンドウを開き直す） |
| `403 Forbidden` / `404 Not Found`（開始直後） | `projectId` が違う、またはそのプロジェクトの権限がない |
| `Connection refused` / `UnknownHost` | `baseUrl` の誤り、社内ネットワーク・VPN に未接続 |
| `入力ファイルの検証エラー` | ログに行番号と理由（親の行がない、階層の重複、チケットIDの重複、トラッカー名の誤り）が出る。WBS を直して再実行（Redmine は変更されていない） |
| `チケット#123 がRedmineに存在しません` / `別プロジェクト` | その行の `チケットID` が誤り。他の行は反映済み |
| `チケットIDの書き戻しに失敗しました` | WBS を Excel で開いたまま実行した。Excel を閉じ、ログに出た「行 → チケット番号」を手で `チケットID` 列に入力する（**入力せずに再実行すると二重作成になる**） |
| 設定ファイルの日本語でエラー・文字化け | `sync-config.yml` を UTF-8 で保存し直す |
