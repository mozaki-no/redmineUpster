# AGENTS.md

AI エージェント向けのプロジェクト概要。

---

## プロジェクト目的

**RedmineのチケットをCSV/Excelから一括同期するCLIツール**

- WBS（Excel/CSV）からRedmineチケットを自動作成・更新
- 階層列（大分類→中分類→小分類→成果物→タスク）から親子関係を推定
- `external_key`（CSVのid列）でRedmineチケットと紐付け管理
- Jenkinsから定期実行を想定

## ClaudeCodeと作業中

- ClaudeCodeは以下を参照しているから行動原理は合わせて
    - CLAUDE.md

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

## 実行計画（本番バグ対応）
1. `testResults/sync-config.yml` と `testResults/test.csv` を確認し、階層列と日付形式、ステータス列の実データを整理する。
2. 親子関係推定の原因を特定し、必要なら親キー推定方式（WBS_ID派生 or 親列の追加）を決定する。
3. 日付パースを `yyyy/M/d` と `yyyy/M/dd` などゼロ埋めなしにも対応させる。
4. `start_date`/`due_date` の取り込み列名を `sync.columns` で指定可能にし、既存デフォルトと互換を維持する。
5. CSVのステータス列（例: `ステータス`）を優先できるようにし、更新不可時はログで判別可能にする。
6. 進捗があったらこの計画を更新する。

---

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
