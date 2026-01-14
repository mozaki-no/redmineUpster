# redmineUpster

Redmine のチケットを CSV/Excel から同期する Spring Boot サービスです。`id` を external_key として管理し、
階層列（大分類 → 中分類 → 小分類 → 成果物 → タスク）から親子関係を推定します。

## 起動手順
1. 依存サービスを起動します。
   - `docker compose up -d`
2. アプリを起動します。
   - `./mvnw spring-boot:run`

## 環境変数
- `DB_URL` / `DB_USER` / `DB_PASSWORD`: Postgres 接続先
- `REDMINE_BASE_URL`: Redmine ベース URL（例: `http://localhost:3000`）
- `REDMINE_API_KEY`: Redmine API キー
- `REDMINE_PROJECT_ID`: 同期先プロジェクト ID

## 本番/テストのDB切り替え
- 本番/ローカル: Docker の `postgres:16` を使う（`docker compose up -d`）。
  - `DB_URL` / `DB_USER` / `DB_PASSWORD` で接続先は上書き可能。
- テスト: `application-test.yml` を使い、H2（in-memory）で実行。
  - テストは `@ActiveProfiles("test")` で `test` プロファイル固定。

## 本番運用例（DB を Docker で管理）
- DB 起動: `docker compose up -d`
- systemd 連携: `deploy/redmine-upster.service` を利用し、`/etc/redmine-upster.env` に接続情報を置く。

`/etc/redmine-upster.env` 例:
```
DB_URL=jdbc:postgresql://localhost:5433/redmine_upster
DB_USER=postgres
DB_PASSWORD=postgres
```

## 主要API
- `GET /api/configs` / `PUT /api/configs`: 設定キーの取得と更新
- `POST /api/probe-headers`: CSV/Excel のヘッダ確認（`file` を multipart で送信）
- `GET /api/diffs` / `POST /api/diffs`: 差分の一覧/作成（`file` を multipart で送信）
- `GET /api/diffs/{id}/items`: 差分詳細
- `GET /api/runs` / `POST /api/runs`: 同期実行
- `GET /api/logs?runId=...`: 実行ログ

## 設定キー例
- `customFieldMap`: `{"チーム":"12","工程":"13","社/組織":"14","着手実績":"15","完了実績":"16","成果物":"17"}`
- `tracker.auto.enabled`: `true` / `false`
- `tracker.auto.value`: トラッカー ID か名称
- `status.auto.enabled`: `true` / `false`
- `status.auto.mode`: `FIXED` / `BY_DATES`
- `status.auto.fixed`: `New` など固定ステータス名

## ヘッダ仕様
`id,チーム,工程,大分類,中分類,小分類,成果物,タスク,社/組織,担当,着手予定,着手実績,完了予定,完了実績`
