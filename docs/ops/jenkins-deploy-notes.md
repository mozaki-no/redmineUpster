# Jenkins Deploy Notes

## 概要
- Jenkins から配布・起動・チェックするために `deploy/` 配下のスクリプトを使用する。
- デフォルトの待受ポートは `3004`。
- Postgres は `docker compose` の `5433:5432` で起動する前提。

## Jenkins 実行例
```
DEPLOY_DIR=/var/lib/jenkins/redmine-upster \
SKIP_SYSTEMCTL=true \
bash deploy/jenkins-deploy.sh

export DB_URL=jdbc:postgresql://localhost:5433/redmine_upster
export DB_USER=postgres
export DB_PASSWORD=postgres
export REDMINE_BASE_URL=http://avsp012a11a-11.jpn.mds.honda.com:3000
export REDMINE_API_KEY=...
export REDMINE_PROJECT_ID=...

AUTO_KILL_PORT=true APP_PORT=3004 START_DOCKER=true LOG_TO_STDOUT=true \
JAVA17=/usr/lib/jvm/java-17-openjdk-amd64/bin/java \
APP_JAR=/var/lib/jenkins/redmine-upster/app.jar \
bash deploy/jenkins-start.sh

APP_HOST=localhost APP_PORT=3004 bash deploy/jenkins-check.sh
```

## メモ
- 管理画面URL: `http://<host>:3004/admin.html`
- DBポートは `5433:5432`（`DB_URL=jdbc:postgresql://localhost:5433/redmine_upster`）。
- Jenkins で使う Java 17 パス: `/usr/lib/jvm/java-17-openjdk-amd64/bin/java`
- 必要に応じて `APP_ARGS="--spring.config.location=file:/var/lib/jenkins/redmine-upster/application.yml"` を指定する。
- 外部疎通できない場合は FW/セキュリティグループと `ss -lntp` / `curl` を確認する。
- `deploy/jenkins-start.sh` は Jenkins のプロセス掃除を避けるため `BUILD_ID` / `JENKINS_NODE_COOKIE` を設定する。
- `LOG_TO_STDOUT=true` は `app.log` に出力しつつ直近ログをコンソールに表示する。
- 3004 が使用中なら `AUTO_KILL_PORT=true` で自動停止を試みる。
