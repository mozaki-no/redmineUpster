# Repository Guidelines

## Project Structure & Module Organization
- ソースコードは `src/main/java/mozaki/redmineUpster` にあり、`domain`（JPA エンティティ）、`repository`（Spring Data リポジトリ）、`dto`、`config` のパッケージ構成です。
- リソースは `src/main/resources` にあり、Flyway のマイグレーションは `src/main/resources/db/migration`（例: `V1__init.sql`）。`application.yml`、`static`、`templates` もここにあります。
- テストは `src/test/java/mozaki/redmineUpster` にあり、Spring Boot の標準的な構成に従います。

## Build, Test, and Development Commands
- `./mvnw spring-boot:run` で Spring Boot プラグインを使ってローカル起動します。
- `./mvnw test` で JUnit 5 テストを実行します。
- `./mvnw package` で `target/` 配下に jar を作成します。
- `docker-compose up` で `application.yml` に合わせたローカル Postgres を起動します。

## Coding Style & Naming Conventions
- 言語: Java 17、Spring Boot 3.5.x、Maven。
- インデント: Java 標準（4 スペース）。import の整理とワイルドカード import の回避。
- 命名: パッケージは小文字（`mozaki.redmineUpster`）、クラスは `UpperCamelCase`、エンティティは末尾に `Entity` を付けるのが基本。
- Lombok を有効化しています。既存で使われている箇所は Lombok を優先してボイラープレートを避けます。

## Testing Guidelines
- フレームワーク: JUnit 5、`@SpringBootTest` による結合テスト寄り。
- 命名: テストクラスは `*Tests`（例: `RedmineUpsterApplicationTests`）。
- 実行: `./mvnw test`。カバレッジ閾値は特に設定されていません。

## Commit & Pull Request Guidelines
- この環境では Git 履歴が参照できないため、確立されたコミット規約は不明です。簡潔な命令形（例: “Add diff repository”）を推奨します。
- PR には概要、検証手順（コマンドやテスト結果）、DB や設定変更の有無を記載してください。

## Configuration & Environment
- DB 設定は `src/main/resources/application.yml` にあり、`DB_URL`、`DB_USER`、`DB_PASSWORD` で上書き可能です。
- Redmine 連携は `REDMINE_BASE_URL`、`REDMINE_API_KEY`、`REDMINE_PROJECT_ID` を使用します。

## Agent Response Guidelines
- 返答は日本語で、簡潔に回答してください。
