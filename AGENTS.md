# AGENTS.md

AI エージェント向けのプロジェクト概要。

---

## プロジェクト目的

**RedmineのチケットをCSV/Excelから一括同期するCLIツール**

- WBS（Excel/CSV）からRedmineチケットを自動作成・更新
- 階層列（大分類→中分類→小分類→成果物→タスク）から親子関係を推定
- `external_key`（CSVのid列）でRedmineチケットと紐付け管理
- Jenkinsから定期実行を想定

---

## 現状

### 動くもの
- CLI同期実行（`--sync`）
- dry-runモード（`--dry-run`）
- デバッグログ（`--debug`）
- YAML設定ファイルによる複数プロジェクト対応
- 階層列・外部キー列のカスタマイズ
- 環境変数展開（`${VAR_NAME}`形式）

### 未実装・課題
- テストカバレッジ100%未達成
- 本番環境でRedmine更新されない問題を調査中（`--debug`で原因特定予定）

---

## これまでの意思決定

| 決定事項 | 理由 |
|----------|------|
| Web UI（admin.html）を廃止 | Jenkins実行に移行、UIは不要 |
| 設定をYAMLファイルに統一 | DB管理からファイル管理へ、Git管理可能に |
| 差分/履歴をDBに保存しない | ログファイルで十分、DBスキーマを簡素化 |
| `issue_link`テーブルのみ維持 | 更新判定（CREATE/UPDATE）に必須 |
| 列設定を外部化 | プロジェクトごとにCSVフォーマットが異なるため |

---

## 重要ファイルの地図

### 入口ファイル
| ファイル | 役割 |
|----------|------|
| `cli/SyncCommand.java` | CLIエントリーポイント（CommandLineRunner） |
| `cli/SyncRunner.java` | 同期フロー統合 |
| `RedmineUpsterApplication.java` | Spring Boot起動クラス |

### 設定
| ファイル | 役割 |
|----------|------|
| `samples/sync-config.example.yml` | 設定ファイルサンプル |
| `config/SyncConfigProperties.java` | 設定クラス（@ConfigurationProperties） |
| `service/SyncConfigService.java` | 設定読み込み・環境変数展開 |
| `application.yml` | Spring Boot設定 |
| `.env.example` | 環境変数テンプレート |

### DB
| ファイル | 役割 |
|----------|------|
| `domain/IssueLinkEntity.java` | external_key ↔ issue_id 紐付け |
| `repository/IssueLinkRepository.java` | JPA リポジトリ |
| `db/migration/V1__issue_link.sql` | Flywayマイグレーション |

### 同期ロジック
| ファイル | 役割 |
|----------|------|
| `cli/DiffCalculator.java` | 差分計算（インメモリ） |
| `cli/SyncExecutor.java` | Redmine API呼び出し・IssueLink保存 |
| `service/RedmineClient.java` | Redmine REST APIクライアント |
| `service/SpreadsheetParser.java` | CSV/Excel解析 |

### CI/デプロイ
| ファイル | 役割 |
|----------|------|
| `docs/DEPLOY.md` | デプロイ手順書 |
| `docs/setup/` | 環境セットアップガイド |
| `deploy/` | Jenkinsスクリプト・systemdユニット |

---

## 実行コマンド

```bash
# ビルド
./mvnw clean package

# テスト
./mvnw test

# 単一テスト
./mvnw test -Dtest=SyncConfigServiceTests

# CLI実行（dry-run）
java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar \
  --sync --file=input.csv --dry-run

# CLI実行（デバッグログ付き）
java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar \
  --sync --file=input.csv --debug

# Docker Compose（PostgreSQL）
docker compose up -d
```

---

## まず最初に読むべき文書

1. **README.md** - プロジェクト概要・CLI引数
2. **CLAUDE.md** - 作業状況・アーキテクチャ詳細
3. **docs/DEPLOY.md** - デプロイ・Jenkins設定
4. **samples/sync-config.example.yml** - 設定ファイル仕様
