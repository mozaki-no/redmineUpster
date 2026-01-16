# Docker / Docker Compose セットアップガイド

本ドキュメントでは、redmineUpsterで使用するPostgreSQLデータベースをDocker Composeで管理する方法を解説します。

---

## 目次

1. [Docker/Docker Composeのインストール](#1-dockerdocker-composeのインストール)
2. [docker-compose.ymlの説明](#2-docker-composeymlの説明)
3. [基本コマンド](#3-基本コマンド)
4. [PostgreSQLコンテナの管理](#4-postgresqlコンテナの管理)
5. [データ永続化](#5-データ永続化)
6. [バックアップとリストア](#6-バックアップとリストア)
7. [トラブルシューティング](#7-トラブルシューティング)

---

## 1. Docker/Docker Composeのインストール

### 1.1 Ubuntu でのインストール

#### 古いバージョンの削除

```bash
# 古いバージョンがインストールされている場合は削除
sudo apt remove docker docker-engine docker.io containerd runc
```

#### Docker公式リポジトリの設定

```bash
# 必要なパッケージをインストール
sudo apt update
sudo apt install -y ca-certificates curl gnupg lsb-release

# Dockerの公式GPGキーを追加
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# リポジトリを追加
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
```

#### Dockerのインストール

```bash
# パッケージインデックスを更新
sudo apt update

# Docker Engineをインストール
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# バージョン確認
docker --version
docker compose version
```

#### ユーザーをdockerグループに追加

```bash
# 現在のユーザーをdockerグループに追加
sudo usermod -aG docker $USER

# グループの変更を反映（ログアウト/ログインが必要な場合もあります）
newgrp docker

# sudoなしでdockerコマンドが実行できることを確認
docker ps
```

### 1.2 CentOS / Rocky Linux でのインストール

#### 古いバージョンの削除

```bash
sudo dnf remove docker docker-client docker-client-latest docker-common docker-latest docker-latest-logrotate docker-logrotate docker-engine
```

#### Docker公式リポジトリの設定

```bash
# 必要なパッケージをインストール
sudo dnf install -y dnf-plugins-core

# Dockerリポジトリを追加
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
```

#### Dockerのインストール

```bash
# Docker Engineをインストール
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Dockerサービスの起動と自動起動設定
sudo systemctl start docker
sudo systemctl enable docker

# バージョン確認
docker --version
docker compose version
```

#### ユーザーをdockerグループに追加

```bash
sudo usermod -aG docker $USER
newgrp docker
```

### 1.3 インストール確認

```bash
# Dockerの動作確認
docker run hello-world

# 出力例:
# Hello from Docker!
# This message shows that your installation appears to be working correctly.
```

---

## 2. docker-compose.ymlの説明

redmineUpsterプロジェクトには、以下の`docker-compose.yml`が含まれています。

### 2.1 ファイル内容

```yaml
services:
  postgres:
    image: postgres:16
    container_name: redmine_upster_postgres
    environment:
      POSTGRES_DB: redmine_upster
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5433:5432"
    volumes:
      - redmine_upster_pgdata:/var/lib/postgresql/data

volumes:
  redmine_upster_pgdata:
```

### 2.2 各設定項目の説明

| 項目 | 説明 |
|------|------|
| `image: postgres:16` | 使用するPostgreSQLのDockerイメージ。バージョン16を指定 |
| `container_name` | コンテナ名。`docker ps`などで表示される名前 |
| `POSTGRES_DB` | 作成するデータベース名 |
| `POSTGRES_USER` | 管理者ユーザー名 |
| `POSTGRES_PASSWORD` | 管理者パスワード |
| `ports: "5433:5432"` | ホストの5433ポートをコンテナの5432ポートにマッピング |
| `volumes` | データ永続化用のボリューム設定 |

### 2.3 ポートが5433である理由

- ホストにPostgreSQLがインストールされている場合、デフォルトポート5432と競合する可能性があります
- 競合を避けるため、ホスト側のポートを5433に設定しています
- アプリケーションからは `localhost:5433` で接続します

### 2.4 本番環境向けの設定例

本番環境では、セキュリティとパフォーマンスを考慮した設定を使用してください。

```yaml
services:
  postgres:
    image: postgres:16
    container_name: redmine_upster_postgres
    environment:
      POSTGRES_DB: redmine_upster
      POSTGRES_USER: ${DB_USER:-postgres}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
      # パフォーマンス設定
      POSTGRES_INITDB_ARGS: "--encoding=UTF-8 --locale=ja_JP.UTF-8"
    ports:
      - "127.0.0.1:5433:5432"  # ローカルホストのみからアクセス可能
    volumes:
      - redmine_upster_pgdata:/var/lib/postgresql/data
      - ./backup:/backup  # バックアップ用ディレクトリ
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d redmine_upster"]
      interval: 10s
      timeout: 5s
      retries: 5
    # リソース制限
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
        reservations:
          cpus: '0.5'
          memory: 512M

volumes:
  redmine_upster_pgdata:
    driver: local
```

---

## 3. 基本コマンド

### 3.1 コンテナの起動

```bash
# プロジェクトディレクトリに移動
cd /path/to/redmineUpster

# バックグラウンドでコンテナを起動
docker compose up -d

# 出力例:
# [+] Running 2/2
#  ✔ Network redmineupster_default       Created
#  ✔ Container redmine_upster_postgres   Started
```

### 3.2 コンテナの状態確認

```bash
# 起動中のコンテナを確認
docker compose ps

# 出力例:
# NAME                       STATUS          PORTS
# redmine_upster_postgres    Up 10 minutes   0.0.0.0:5433->5432/tcp

# 全てのコンテナを確認（停止中も含む）
docker compose ps -a
```

### 3.3 コンテナの停止

```bash
# コンテナを停止（データは保持）
docker compose stop

# コンテナを停止して削除（データは保持）
docker compose down

# コンテナとボリューム（データ）を削除
# 注意: データが全て削除されます
docker compose down -v
```

### 3.4 コンテナの再起動

```bash
# 全てのコンテナを再起動
docker compose restart

# 特定のサービスのみ再起動
docker compose restart postgres
```

### 3.5 ログの確認

```bash
# 全てのサービスのログを表示
docker compose logs

# 特定のサービスのログを表示
docker compose logs postgres

# リアルタイムでログを追跡
docker compose logs -f postgres

# 最新100行のみ表示
docker compose logs --tail 100 postgres
```

### 3.6 コンテナへの接続

```bash
# bashでコンテナに接続
docker compose exec postgres bash

# psqlで直接PostgreSQLに接続
docker compose exec postgres psql -U postgres -d redmine_upster
```

---

## 4. PostgreSQLコンテナの管理

### 4.1 データベースへの接続

#### Docker exec を使用する方法

```bash
# psqlクライアントでデータベースに接続
docker compose exec postgres psql -U postgres -d redmine_upster

# SQLを直接実行
docker compose exec postgres psql -U postgres -d redmine_upster -c "SELECT * FROM issue_link;"
```

#### ホストマシンから接続する方法

```bash
# ホストにpsqlがインストールされている場合
psql -h localhost -p 5433 -U postgres -d redmine_upster
```

### 4.2 データベースの状態確認

```bash
# データベース一覧の確認
docker compose exec postgres psql -U postgres -c "\l"

# テーブル一覧の確認
docker compose exec postgres psql -U postgres -d redmine_upster -c "\dt"

# テーブルのレコード数確認
docker compose exec postgres psql -U postgres -d redmine_upster -c "SELECT COUNT(*) FROM issue_link;"
```

### 4.3 データベースの初期化

**警告:** この操作はすべてのデータを削除します。

```bash
# コンテナとボリュームを削除
docker compose down -v

# 再起動（新しいデータベースが作成される）
docker compose up -d
```

### 4.4 SQL実行例

```bash
# issue_linkテーブルの内容を確認
docker compose exec postgres psql -U postgres -d redmine_upster << 'EOF'
SELECT
    id,
    external_key,
    issue_id,
    created_at
FROM issue_link
ORDER BY id DESC
LIMIT 10;
EOF

# 特定のexternal_keyを検索
docker compose exec postgres psql -U postgres -d redmine_upster \
    -c "SELECT * FROM issue_link WHERE external_key = 'T-001';"
```

---

## 5. データ永続化

### 5.1 Dockerボリュームの仕組み

Docker Composeで定義したボリューム（`redmine_upster_pgdata`）は、コンテナが削除されてもデータを保持します。

```
ホストマシン                    Dockerコンテナ
+-----------------+            +------------------+
| Dockerボリューム  | <-------> | /var/lib/        |
| (redmine_upster | マウント    | postgresql/data  |
|  _pgdata)       |            |                  |
+-----------------+            +------------------+
        |
        v
  データ永続化
  (コンテナ削除後も保持)
```

### 5.2 ボリュームの確認

```bash
# ボリューム一覧を確認
docker volume ls

# 出力例:
# DRIVER    VOLUME NAME
# local     redmineupster_redmine_upster_pgdata

# ボリュームの詳細情報
docker volume inspect redmineupster_redmine_upster_pgdata

# 出力例:
# [
#     {
#         "CreatedAt": "2026-01-16T10:00:00Z",
#         "Driver": "local",
#         "Labels": {...},
#         "Mountpoint": "/var/lib/docker/volumes/redmineupster_redmine_upster_pgdata/_data",
#         "Name": "redmineupster_redmine_upster_pgdata",
#         "Options": null,
#         "Scope": "local"
#     }
# ]
```

### 5.3 ボリュームの使用量確認

```bash
# ボリュームの使用量を確認
docker system df -v | grep redmine_upster

# より詳細な確認
sudo du -sh /var/lib/docker/volumes/redmineupster_redmine_upster_pgdata
```

### 5.4 バインドマウントを使用する場合

ホストの特定ディレクトリにデータを保存したい場合は、バインドマウントを使用します。

```yaml
services:
  postgres:
    image: postgres:16
    container_name: redmine_upster_postgres
    environment:
      POSTGRES_DB: redmine_upster
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5433:5432"
    volumes:
      # バインドマウント（ホストの絶対パスを指定）
      - /data/redmine_upster/postgres:/var/lib/postgresql/data
```

**注意:** バインドマウントを使用する場合は、事前にディレクトリを作成し、適切な権限を設定してください。

```bash
sudo mkdir -p /data/redmine_upster/postgres
sudo chown -R 999:999 /data/redmine_upster/postgres  # PostgreSQLユーザーのUID
```

---

## 6. バックアップとリストア

### 6.1 バックアップの作成

#### pg_dumpを使用したバックアップ

```bash
# 現在の日時でバックアップファイルを作成
docker compose exec postgres pg_dump -U postgres -d redmine_upster > backup_$(date +%Y%m%d_%H%M%S).sql

# 圧縮してバックアップ
docker compose exec postgres pg_dump -U postgres -d redmine_upster | gzip > backup_$(date +%Y%m%d_%H%M%S).sql.gz

# カスタムフォーマット（並列リストア可能）
docker compose exec postgres pg_dump -U postgres -d redmine_upster -Fc > backup_$(date +%Y%m%d_%H%M%S).dump
```

#### バックアップスクリプト例

```bash
#!/bin/bash
# backup.sh

BACKUP_DIR="/backup/redmine_upster"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RETENTION_DAYS=7

# バックアップディレクトリの作成
mkdir -p $BACKUP_DIR

# バックアップの実行
cd /path/to/redmineUpster
docker compose exec -T postgres pg_dump -U postgres -d redmine_upster | gzip > "${BACKUP_DIR}/backup_${TIMESTAMP}.sql.gz"

# 古いバックアップの削除
find $BACKUP_DIR -name "backup_*.sql.gz" -mtime +$RETENTION_DAYS -delete

echo "Backup completed: ${BACKUP_DIR}/backup_${TIMESTAMP}.sql.gz"
```

cronでの定期実行設定:

```bash
# crontabを編集
crontab -e

# 毎日午前2時にバックアップを実行
0 2 * * * /path/to/backup.sh >> /var/log/redmine_upster_backup.log 2>&1
```

### 6.2 リストア

#### SQLファイルからのリストア

```bash
# 通常のSQLファイル
cat backup_20260116_100000.sql | docker compose exec -T postgres psql -U postgres -d redmine_upster

# 圧縮ファイル
gunzip -c backup_20260116_100000.sql.gz | docker compose exec -T postgres psql -U postgres -d redmine_upster

# カスタムフォーマット
docker compose exec -T postgres pg_restore -U postgres -d redmine_upster < backup_20260116_100000.dump
```

#### データベースを再作成してリストア

```bash
# 既存のデータベースを削除して再作成
docker compose exec postgres psql -U postgres -c "DROP DATABASE IF EXISTS redmine_upster;"
docker compose exec postgres psql -U postgres -c "CREATE DATABASE redmine_upster;"

# リストア
cat backup_20260116_100000.sql | docker compose exec -T postgres psql -U postgres -d redmine_upster
```

---

## 7. トラブルシューティング

### 7.1 コンテナが起動しない

**症状:**

```
docker compose up -d
Error response from daemon: driver failed programming external connectivity
```

**対処:**

```bash
# ポートが使用中か確認
sudo ss -tlnp | grep 5433

# 他のプロセスが使用している場合はポートを変更
# docker-compose.yml で ports: "5434:5432" に変更
```

### 7.2 データベースに接続できない

**症状:**

```
psql: error: connection refused
```

**対処:**

```bash
# コンテナが起動しているか確認
docker compose ps

# コンテナのログを確認
docker compose logs postgres

# コンテナが起動していない場合は起動
docker compose up -d

# ヘルスチェック
docker compose exec postgres pg_isready -U postgres
```

### 7.3 権限エラー

**症状:**

```
ERROR: permission denied for table issue_link
```

**対処:**

```bash
# 権限を確認
docker compose exec postgres psql -U postgres -d redmine_upster -c "\dp issue_link"

# 権限を付与
docker compose exec postgres psql -U postgres -d redmine_upster -c "GRANT ALL ON issue_link TO postgres;"
```

### 7.4 ディスク容量不足

**症状:**

```
ERROR: could not extend file: No space left on device
```

**対処:**

```bash
# Dockerのディスク使用量を確認
docker system df

# 不要なイメージ・コンテナ・ボリュームを削除
docker system prune -a

# 使用していないボリュームのみ削除（注意して実行）
docker volume prune
```

### 7.5 コンテナが突然停止する

**症状:**

コンテナがOOM Killerによって強制終了される

**対処:**

```bash
# コンテナのログを確認
docker compose logs postgres | tail -50

# メモリ使用量を確認
docker stats redmine_upster_postgres

# docker-compose.ymlでメモリ制限を設定
# deploy:
#   resources:
#     limits:
#       memory: 2G
```

### 7.6 データが消えた

**症状:**

コンテナを再起動したらデータがなくなった

**原因と対処:**

1. `docker compose down -v` を実行した → ボリュームも削除された
   - バックアップからリストアが必要

2. ボリュームが正しく設定されていない
   ```bash
   # ボリュームの存在を確認
   docker volume ls | grep redmine_upster

   # ボリュームの内容を確認
   docker run --rm -v redmineupster_redmine_upster_pgdata:/data alpine ls -la /data
   ```

### 7.7 Docker Composeのバージョン問題

**症状:**

```
The Compose file is invalid because:
Unsupported config option for services.postgres: 'deploy'
```

**対処:**

```bash
# Docker Composeのバージョンを確認
docker compose version

# 古いバージョンの場合はアップデート
# または、docker-compose.ymlからdeployセクションを削除
```

### 7.8 ネットワーク関連の問題

**症状:**

コンテナ間の通信ができない

**対処:**

```bash
# ネットワークの状態を確認
docker network ls
docker network inspect redmineupster_default

# ネットワークを再作成
docker compose down
docker network prune
docker compose up -d
```

---

## 付録

### A. 便利なエイリアス設定

`~/.bashrc` に追加:

```bash
# Docker Compose エイリアス
alias dc='docker compose'
alias dcup='docker compose up -d'
alias dcdown='docker compose down'
alias dclogs='docker compose logs -f'
alias dcps='docker compose ps'

# PostgreSQL接続エイリアス
alias dcpsql='docker compose exec postgres psql -U postgres -d redmine_upster'
```

### B. docker-compose.ymlの完全版

```yaml
# docker-compose.yml (本番環境向け完全版)
services:
  postgres:
    image: postgres:16
    container_name: redmine_upster_postgres
    environment:
      POSTGRES_DB: redmine_upster
      POSTGRES_USER: ${DB_USER:-postgres}
      POSTGRES_PASSWORD: ${DB_PASSWORD:-postgres}
      PGDATA: /var/lib/postgresql/data/pgdata
      TZ: Asia/Tokyo
    ports:
      - "127.0.0.1:5433:5432"
    volumes:
      - redmine_upster_pgdata:/var/lib/postgresql/data
      - ./backup:/backup
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d redmine_upster"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
        reservations:
          cpus: '0.5'
          memory: 512M

volumes:
  redmine_upster_pgdata:
    driver: local
```

### C. 参考リンク

- [Docker公式ドキュメント](https://docs.docker.com/)
- [Docker Compose公式ドキュメント](https://docs.docker.com/compose/)
- [PostgreSQL Dockerイメージ](https://hub.docker.com/_/postgres)

---

最終更新日: 2026-01-16
