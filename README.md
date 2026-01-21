# redmineUpster

RedmineのチケットをCSV/Excelから同期するCLIツールです。`id`をexternal_keyとして管理し、階層列（大分類 → 中分類 → 小分類 → 成果物 → タスク）から親子関係を推定します。親が推定できない場合は、外部キーの末尾区切り（例: `1.2.3` → 親 `1.2`）から補助的に推定します。

親がCSV/Redmineに存在しない場合は、外部キー（例: `1.1.1` → 親 `1.1`）から仮想親を自動生成し、親の`start_date`/`due_date`は子の最小/最大を集計して更新します。仮想親のトラッカーは `virtualParentTrackerId` で指定します（デフォルト: 6）。件名は階層名です。仮想親のステータスは初回作成のみ設定され、以降の更新では変更されません。

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
| `--sync` | はい | CLI同期モードで実行 |
| `--config=<path>` | いいえ | 設定ファイルパス（デフォルト: `sync-config.yml`） |
| `--project=<name>` | いいえ | 使用するプロジェクト名（デフォルト: `default=true`のプロジェクト） |
| `--file=<path>` | はい | 同期するCSV/Excelファイルのパス |
| `--dry-run` | いいえ | ドライランモード（実際のRedmine更新なし） |
| `--log-dir=<path>` | いいえ | ログ出力ディレクトリ（デフォルト: カレントディレクトリ） |
| `--debug` | いいえ | デバッグログを出力（APIリクエスト/レスポンス等） |

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
      tracker:
        enabled: true
        value: "タスク"
      status:
        enabled: true
        mode: "BY_DATES"  # または "FIXED"
        fixed: "New"
        statusMap:
          "未着手": "1"
          "進行中": "2"
          "完了": "5"
      virtualParentTrackerId: 6
      customFieldMap:
        チーム: "12"
        工程: "13"
      customFieldDateColumns:
        - "着手実績"
        - "完了実績"
      columns:
        externalKeyColumn: "id"  # 外部キー列（"WBS番号"などに変更可能）
        hierarchy:  # 親子関係推定に使用する列
          - "大分類"
          - "中分類"
          - "小分類"
          - "成果物"
          - "タスク"
        startDateColumn: "着手予定"  # Redmine start_date
        dueDateColumn: "完了予定"    # Redmine due_date
        statusColumn: "ステータス"  # CSVのステータス列（優先）
```

環境変数は `${VAR_NAME}` 形式で参照可能です。

## 環境変数

| 変数 | 説明 |
|------|------|
| `DB_URL` | PostgreSQL接続URL（デフォルト: `jdbc:postgresql://localhost:5432/redmine_upster`） |
| `DB_USER` | DBユーザー名（デフォルト: `postgres`） |
| `DB_PASSWORD` | DBパスワード（デフォルト: `postgres`） |

Docker Composeでローカル起動する場合はポート`5433`を使用:
```bash
docker compose up -d
export DB_URL="jdbc:postgresql://localhost:5433/redmine_upster"
```

## ヘッダ仕様

デフォルトのCSVヘッダ:
```
id,チーム,工程,大分類,中分類,小分類,成果物,タスク,社/組織,担当,着手予定,着手実績,完了予定,完了実績,ステータス
```

※ 外部キー列（`id`）、階層列、カスタムフィールド列、開始日/期限/ステータス列は設定ファイルで変更可能です。

## 日付形式

`start_date` / `due_date` は以下の形式を受け付けます。

- `yyyy-MM-dd`
- `yyyy/M/d`（例: `2026-1-3` / `2026/1/3`）

日付型カスタムフィールドは `customFieldDateColumns` に列名を指定すると同様に正規化されます。

## ステータスマッピング

CSVの日本語ステータスをRedmineのステータスIDへ変換したい場合は `statusMap` を指定します。
`statusMap` の値が数値の場合は `status_id` として送信されます。

## 詳細

Jenkins連携、設定ファイルの詳細、トラブルシューティングについては [docs/DEPLOY.md](./docs/DEPLOY.md) を参照してください。

設定ファイルのサンプルは [samples/sync-config.example.yml](./samples/sync-config.example.yml) を参照してください。
