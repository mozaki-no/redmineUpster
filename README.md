# redmineUpster

RedmineのチケットをCSV/Excelから同期するCLIツールです。`id`をexternal_keyとして管理し、階層列（大分類 → 中分類 → 小分類 → 成果物 → タスク）から親子関係を推定します。

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
      customFieldMap:
        チーム: "12"
        工程: "13"
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

```
id,チーム,工程,大分類,中分類,小分類,成果物,タスク,社/組織,担当,着手予定,着手実績,完了予定,完了実績
```

## 詳細

Jenkins連携、設定ファイルの詳細、トラブルシューティングについては [docs/DEPLOY.md](./docs/DEPLOY.md) を参照してください。

設定ファイルのサンプルは [samples/sync-config.example.yml](./samples/sync-config.example.yml) を参照してください。
