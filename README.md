# redmineUpster

RedmineのチケットをCSV/Excelから同期するCLIツールです。Excel/CSVの1行がRedmineのチケット1件に対応し、**Excelのチケット一覧とRedmineのチケットを同じ状態に保つ**ことを目的にしています。

## 同期の仕組み

- **チケットID列**（既定: `チケットID`）: 値があればそのチケットを更新、空欄なら新規作成します。
  新規作成したチケットのIDは、同期後に入力ファイルの同じ行へ自動で書き戻します（元ファイルは `<ファイル名>.bak` に保存。`--dry-run` では書き戻しません）。チケットID列がないファイルは、書き戻し時に列をヘッダの末尾へ追加します。
- **トラッカー列**（既定: `トラッカー`）: 行ごとのトラッカー名（またはID）。`sync.trackerMap`（名前→ID）で変換し、そこにない名前は Redmine の `GET /trackers.json` から解決します。空欄の場合は `sync.tracker`（`enabled: true` の場合）の値を使い、それもなければ新規作成はエラー・更新はトラッカーを変更しません。
- **親子関係**: 親チケットIDは持たず、階層列（既定: 大分類 → 中分類 → 小分類 → 成果物 → タスク）だけで決めます。値が入っている一番深い階層列がその行のレベルで、それより浅い階層列の値がすべて同じ、1つ浅いレベルの行が親になります。親→子の順に作成し、同じ実行内で作成した親のIDを子に設定します。更新時も毎回階層から親を設定するため、行を移動すると親子関係も付け替わります（最上位へ移動した場合は親を外します）。
- **件名**: `タスク` 列、なければ一番深い階層の値。
- **検証**: 次の場合は Redmine に一切書き込まずに終了します（行番号と階層パスをログに出力、終了コード1）。
  - 親の行がファイルにない（仮想親の自動生成は廃止しました）
  - 階層パスがまったく同じ行が2つ以上ある／階層列がすべて空の行がある
  - チケットIDが数値でない（`#123` 形式は可）、または同じチケットIDが複数行にある
  - トラッカー名が見つからない
- **Redmineの現在の状態が正**: データベースは使いません。同期開始時に同期先プロジェクトのチケットを全件取得し（`GET /issues.json?project_id=...&subproject_id=!*&status_id=*` を100件ずつページング。クローズ済みを含み、サブプロジェクトは含まない）、以下の判定に使います。
- **更新対象の確認**: チケットIDが同期先プロジェクトのチケットにない（Redmine に存在しない、または別プロジェクト・サブプロジェクトのチケット）場合は、その行をエラーとしてスキップし（子の行もスキップ）、他の行は続行します。エラーは最後にまとめて出力します。
- **論理削除**: 同期先プロジェクトのチケットのうち、今回のファイルのチケットID列に記載のないものを削除候補とします。**Redmine で手動作成したチケットも候補になります**（ファイルに行を追加してチケットIDを書けば対象外）。物理削除はせず、`sync.deletion.statusId` が設定されていればそのステータスへ変更します（既にそのステータスのチケットは候補にしません）。未設定の場合は候補を警告ログに出すだけで何も変更しません。
- **変更なしスキップ**: 送信する項目（件名・トラッカー・ステータス・親・開始日・期日・進捗率・担当者・カスタムフィールド）を Redmine の現在の値と比較し、すべて同じなら更新をスキップします（`--force-update` で無効化）。更新した場合はログに変わった項目名（`changes=subject,due_date` など）を出力します。ファイルで空欄の日付などは送信しないため比較もしません。
  - Redmine の設定で親チケットの開始日・期日・進捗率を子から算出する場合、親の行にそれと異なる値を書くと毎回「更新」になります（Redmine 側で無視されるだけで害はありません）。気になる場合は親の行の日付・進捗率を空欄にしてください。
- **前行値の補完**: セル結合などで空欄になっている階層列は前の行の値で補完します。ただし、補完するのは「その行でより深い階層列に値がある」空欄だけです（一番深い値より右の空欄はレベルを表すため補完しません）。

## ビルド

```bash
./mvnw clean package
```

成果物: `target/redmineUpster-0.0.1-SNAPSHOT.jar`

## CLI実行

```bash
java -jar redmineUpster.jar --sync [オプション]
```

### 引数

| 引数 | 必須 | 説明 |
|------|------|------|
| `--sync` | いいえ | 同期を実行（`--file` を指定すれば省略可。引数なし・`--help` は使い方を表示） |
| `--config=<path>` | いいえ | 設定ファイルパス（省略時: 環境変数 `SYNC_CONFIG_PATH` → カレントディレクトリ → 実行ファイル/jarと同じフォルダの `sync-config.yml`） |
| `--project=<name>` | いいえ | 使用するプロジェクト名（デフォルト: `default=true`のプロジェクト） |
| `--file=<path>` | はい | 同期するCSV/Excelファイルのパス |
| `--dry-run` | いいえ | ドライランモード（実際のRedmine更新なし） |
| `--log-dir=<path>` | いいえ | ログ出力ディレクトリ（デフォルト: カレントディレクトリの `logs`） |
| `--debug` | いいえ | デバッグログを出力（APIリクエスト/レスポンス等） |
| `--force-update` | いいえ | 更新スキップを無効化して全件Update |
| ~~`--relink-parent`~~ | - | **廃止**（親子は毎回階層から再設定するため不要。指定しても警告を出して無視） |
| ~~`--reset-sync`~~ | - | **廃止**（物理削除を行わない方針のため。指定しても警告を出して無視） |

### 実行例

```bash
# 最小構成
java -jar redmineUpster.jar --sync --file=tasks.csv

# 全オプション指定
java -jar redmineUpster.jar \
  --sync \
  --config=/etc/redmine-sync/sync-config.yml \
  --project="本番環境" \
  --file=/data/tasks.csv \
  --log-dir=/var/log/redmine-sync/

# ドライラン（本番実行前の確認）
java -jar redmineUpster.jar --sync --file=tasks.csv --dry-run

# デバッグログ付き実行
java -jar redmineUpster.jar --sync --file=tasks.csv --debug

# 更新スキップを無効化（全件Update）
java -jar redmineUpster.jar --sync --file=tasks.csv --force-update
```

## 設定ファイル（sync-config.yml）

複数のプロジェクト環境を1ファイルで管理できます。

```yaml
projects:
  - name: "本番環境"
    default: true
    redmine:
      baseUrl: "https://redmine.example.com"
      apiKey: "${REDMINE_API_KEY}"
      projectId: "project-id"
    sync:
      tracker:            # トラッカー列が空欄の行に使う既定値
        enabled: true
        value: "タスク"
      trackerMap:         # トラッカー名 → ID（ないものは /trackers.json から解決）
        "タスク": 2
        "サマリ": 6
      deletion:           # Excelから消えたチケットの論理削除
        statusId: 6       # 省略時は候補をログに出すだけ
      status:
        enabled: true
        mode: "BY_DATES"  # または "FIXED"
        fixed: "New"
        statusMap:
          "未着手": "1"
          "進行中": "2"
          "完了": "5"
      customFieldMap:
        チーム: "12"
        工程: "13"
      customFieldDateColumns:
        - "着手実績"
        - "完了実績"
      columns:
        ticketIdColumn: "チケットID"  # Redmineのチケット番号列
        trackerColumn: "トラッカー"    # トラッカー列
        hierarchy:  # 親子関係を決める列（浅い順）
          - "大分類"
          - "中分類"
          - "小分類"
          - "成果物"
          - "タスク"
        startDateColumn: "着手予定"  # Redmine start_date
        dueDateColumn: "完了予定"    # Redmine due_date
        statusColumn: "ステータス"  # CSVのステータス列（優先）
        progressColumn: "進捗率"    # Redmine done_ratio
```

環境変数は `${VAR_NAME}` 形式で参照可能です。

## 環境変数

データベースは不要です（PostgreSQL・Docker・`DB_URL` などの設定は廃止しました）。
APIキーは設定ファイルに `${REDMINE_API_KEY}` のように書いて環境変数から渡せます（直接書くこともできます）。

| 変数 | 説明 |
|------|------|
| `SYNC_CONFIG_PATH` | `--config` を省略したときの設定ファイルパス（任意） |
| 任意（例: `REDMINE_API_KEY`） | 設定ファイル中の `${VAR}` で参照 |

## ヘッダ仕様

デフォルトのCSVヘッダ:
```
チケットID,トラッカー,チーム,工程,大分類,中分類,小分類,成果物,タスク,社/組織,担当,着手予定,着手実績,完了予定,完了実績,ステータス,進捗率
```

例（親の行も明示的に書きます）:
```
チケットID,トラッカー,大分類,中分類,タスク
120,サマリ,認証システム,,
,サマリ,認証システム,ユーザー認証,
,タスク,認証システム,ユーザー認証,ログイン画面設計
```

※ チケットID列、トラッカー列、階層列、カスタムフィールド列、開始日/期限/ステータス/進捗率列は設定ファイルで変更可能です。
※ CSVの文字コードは UTF-8（BOMあり/なし）と Shift_JIS（Windows-31J）を自動判定し、書き戻し時も元の文字コード・BOM・改行コードを保ちます。Excelは先頭シートを読み書きします。

## 旧方式（id列）からの移行

1. 既存チケットの行には、対応する Redmine のチケット番号を `チケットID` 列に入力します（旧 `id`/`WBS_ID` 列は同期には使われなくなります。カスタムフィールドとして送りたい場合は `customFieldColumns` に残してください）。
   同期先プロジェクトのチケットでファイルに `チケットID` が書かれていないものは、論理削除の候補になります（手動作成したチケットを含む）。
2. `トラッカー` 列を追加します（または `sync.tracker` で既定値を設定）。
3. 親の行がない階層（旧方式では仮想親が自動生成されていたもの）は、親の行をファイルに追加します。仮想親として作られたチケットがある場合は、そのチケット番号を親の行の `チケットID` に入力してください。
4. 設定から `virtualParentTrackerId` と `externalKeyColumn` を削除します（残っていても無視されます）。
5. まず `--dry-run` で検証エラーと論理削除候補（`DRY_RUN LOGICAL_DELETE`）を確認してから実行します。旧バージョンのデータベース（`issue_link`）は使わないため、不要なら削除して構いません。

## 日付形式

`start_date` / `due_date` は以下の形式を受け付けます。

- `yyyy-MM-dd`
- `yyyy/M/d`（例: `2026-1-3` / `2026/1/3`）

日付型カスタムフィールドは `customFieldDateColumns` に列名を指定すると同様に正規化されます。

## ステータスマッピング

CSVの日本語ステータスをRedmineのステータスIDへ変換したい場合は `statusMap` を指定します。
`statusMap` の値が数値の場合は `status_id` として送信されます。

`BY_DATES` の場合は、デフォルトで `New=1` / `In Progress=2` / `Closed=5` のIDに変換されます。

## 詳細

Jenkins連携、設定ファイルの詳細、トラブルシューティングについては [docs/DEPLOY.md](./docs/DEPLOY.md) を参照してください。

設定ファイルのサンプルは [samples/sync-config.example.yml](./samples/sync-config.example.yml) を参照してください。
