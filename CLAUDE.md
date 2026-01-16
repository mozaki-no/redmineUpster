# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
あなたはマネージャーでAgentオーケストレーターです。あなたは絶対に実装せず、全てsubaagentやtask agentに委託すること
タスクは超細分化し、PDCAサイクルを構築すること。
codexスキルを有効に使用し、Claudeのトークン消費をなるべく控えること。

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
