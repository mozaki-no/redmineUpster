# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
あなたはマネージャーでAgentオーケストレーターです。あなたは絶対に実装せず、全てsubaagentやtask agentに委託すること
タスクは超細分化し、PDCAサイクルを構築すること。
codexスキルを有効に使用し、Claudeのトークン消費をなるべく控えること。

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

RedmineのチケットをCSV/Excelファイルから同期するSpring Bootサービス。`id`をexternal_keyとして管理し、階層列（大分類→中分類→小分類→成果物→タスク）から親子関係を推定する。

## 開発コマンド

```bash
# 依存サービス起動（PostgreSQL）
docker compose up -d

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
```

## アーキテクチャ

### 技術スタック
- Java 17 / Spring Boot 3.5
- PostgreSQL 16（本番）/ H2（テスト）
- Flyway（DBマイグレーション）
- OpenCSV / Apache POI（CSV/Excel解析）
- Lombok

### レイヤー構造
- `cli/`: CLIコマンド・同期実行
- `service/`: ビジネスロジック
- `repository/`: JPA リポジトリ
- `domain/`: エンティティ
- `config/`: 設定クラス
- `util/`: ユーティリティ

### データフロー（CLI実行）
1. `SyncCommand`がCLI引数を解析
2. `SyncRunner`が設定・ファイルを読み込み
3. `SpreadsheetParser`でCSV/Excel解析
4. `DiffCalculator`で差分計算（インメモリ）
5. `SyncExecutor`でRedmine同期実行
6. `FileLogger`でログ出力
7. `issue_link`テーブルでexternal_keyとissue_idを紐付け

### テスト環境
テストは`@ActiveProfiles("test")`を使用し、`application-test.yml`でH2インメモリDBを使用（Flyway無効、Hibernateでスキーマ自動生成）。

## 環境変数

- `DB_URL` / `DB_USER` / `DB_PASSWORD`: PostgreSQL接続情報
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
```sql
create table if not exists issue_link (
  id bigserial primary key,
  external_key text not null unique,
  issue_id bigint not null
);
```

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
├── docs/
│   ├── DEPLOY.md         # デプロイ手順書
│   ├── ops/              # 運用関連
│   └── setup/            # セットアップガイド
│       ├── README.md
│       ├── docker.md
│       ├── java17.md
│       ├── jenkins.md
│       └── redmine-apache-passenger.md
├── samples/
│   ├── sync-config.example.yml  # 設定ファイルサンプル
│   └── wbs_*.csv         # WBSサンプル
└── src/
```

### 次のアクション
1. コードリファクタリング（cli/, service/, config/）
2. テストカバレッジ100%達成（分岐網羅）
3. コミット・プッシュ
