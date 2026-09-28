# RedmineUpster デプロイ手順書

本ドキュメントは、RedmineUpsterをJenkins環境でデプロイ・実行するための詳細な手順を記載しています。

---

## 目次

1. [前提条件](#1-前提条件)
2. [ビルド手順](#2-ビルド手順)
3. [環境変数の設定](#3-環境変数の設定)
4. [設定ファイル（sync-config.yml）](#4-設定ファイルsync-configyml)
5. [データベース設定](#5-データベース設定)
6. [CLI引数の説明](#6-cli引数の説明)
7. [Jenkins Pipeline設定例](#7-jenkins-pipeline設定例)
8. [ログファイル](#8-ログファイル)
9. [トラブルシューティング](#9-トラブルシューティング)

---

## 1. 前提条件

### 1.1 必要なソフトウェア

| ソフトウェア | バージョン | 用途 |
|-------------|-----------|------|
| Java | 17以上 | アプリケーション実行 |
| PostgreSQL | 16推奨 | データ永続化 |
| Maven | 3.6以上 | ビルドツール（Maven Wrapperを使用する場合は不要） |
| Jenkins | 2.x | CI/CD環境 |

### 1.2 Java 17のインストール確認

```bash
java -version
# 出力例: openjdk version "17.0.x" ...
```

### 1.3 PostgreSQLの準備

PostgreSQLが稼働していることを確認してください。Docker Composeを使用する場合は以下のコマンドで起動できます。

```bash
cd /path/to/redmineUpster
docker compose up -d
```

これにより、以下の設定でPostgreSQLコンテナが起動します。
- ホスト: `localhost`
- ポート: `5433`
- データベース名: `redmine_upster`
- ユーザー名: `postgres`
- パスワード: `postgres`

### 1.4 Jenkinsの要件

- Jenkins 2.x以上
- 以下のプラグインが必要:
  - Pipeline plugin
  - Git plugin
  - Credentials Binding plugin（シークレット管理用）
  - File Parameter plugin（CSVアップロード用、オプション）

---

## 2. ビルド手順

### 2.1 ソースコードの取得

```bash
git clone <repository-url>
cd redmineUpster
```

### 2.2 Mavenでのビルド

Maven Wrapperを使用してビルドを行います。

```bash
# クリーンビルド（推奨）
./mvnw clean package

# テストをスキップする場合（緊急時のみ）
./mvnw clean package -DskipTests
```

**Windowsの場合:**
```cmd
mvnw.cmd clean package
```

### 2.3 成果物の場所

ビルドが成功すると、以下の場所にJARファイルが生成されます。

```
target/redmineUpster-0.0.1-SNAPSHOT.jar
```

このJARファイルが実行可能なアプリケーションです。

### 2.4 ビルド成功の確認

```bash
ls -la target/redmineUpster*.jar
# 出力例: -rw-r--r-- 1 user user 12345678 Jan 16 10:00 target/redmineUpster-0.0.1-SNAPSHOT.jar
```

---

## 3. 環境変数の設定

### 3.1 .envファイルの概要

本プロジェクトでは、環境変数を`.env`ファイルで管理できます。これにより、機密情報（APIキー、パスワード等）をコードから分離し、安全に管理できます。

#### 3.1.1 ファイル構成

| ファイル | 用途 | Git管理 |
|---------|------|---------|
| `.env.example` | テンプレート。必要な環境変数の一覧を示す | する（コミット対象） |
| `.env` | 本番/個人用の実際の値を設定 | しない（.gitignoreに含む） |
| `.env.test` | ローカルテスト用の設定例 | しない（.gitignoreに含む） |
| `.env.local` | ローカル開発用（オプション） | しない（.gitignoreに含む） |

### 3.2 .envファイルの作成

#### 3.2.1 テンプレートからコピー

```bash
# .env.exampleをコピーして.envを作成
cp .env.example .env

# エディタで編集
vi .env
```

#### 3.2.2 .env.exampleの内容

```bash
# Database
DB_URL=jdbc:postgresql://localhost:5433/redmine_upster
DB_USER=postgres
DB_PASSWORD=postgres

# Redmine API (sync-config.ymlで${VAR}形式で参照)
REDMINE_API_KEY=your_api_key_here
REDMINE_TEST_API_KEY=your_test_api_key_here
```

### 3.3 環境変数の読み込み方法

#### 3.3.1 sourceコマンドでの読み込み（Linux/Mac）

```bash
# .envファイルを現在のシェルに読み込む
source .env

# または
. .env

# 確認
echo $REDMINE_API_KEY
```

**注意:** `.env`ファイルを`source`で読み込むには、各行が`export`なしでも動作しますが、明示的にexportする場合は以下のようにします。

```bash
# exportを付けて読み込む方法
export $(cat .env | grep -v '^#' | xargs)

# または.envファイル自体にexportを記述
export DB_URL=jdbc:postgresql://localhost:5433/redmine_upster
export DB_USER=postgres
# ...
```

#### 3.3.2 envコマンドでの直接指定

```bash
# 一時的に環境変数を設定して実行
env $(cat .env | grep -v '^#' | xargs) java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar --sync --file=input.csv
```

#### 3.3.3 シェルスクリプトでの利用例

```bash
#!/bin/bash
# run-sync.sh

# .envファイルを読み込み
if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi

# 実行
java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar \
    --sync \
    --file="$1" \
    --project="${PROJECT:-本番環境}"
```

### 3.4 Jenkinsでの環境変数設定

#### 3.4.1 Credentials Bindingを使用（推奨）

Jenkinsでは機密情報をCredentialsとして管理し、Pipelineで参照します。

```groovy
pipeline {
    agent any

    environment {
        // Secret Text タイプのCredentialを参照
        REDMINE_API_KEY = credentials('redmine-api-key')
        REDMINE_TEST_API_KEY = credentials('redmine-test-api-key')

        // Username/Password タイプのCredentialを参照
        DB_USER = credentials('db-credentials-usr')
        DB_PASSWORD = credentials('db-credentials-psw')

        // 固定値
        DB_URL = 'jdbc:postgresql://db-server:5432/redmine_upster'
    }

    stages {
        stage('Sync') {
            steps {
                sh '''
                    java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar \
                        --sync \
                        --file=input.csv
                '''
            }
        }
    }
}
```

#### 3.4.2 Jenkinsでのcredential登録方法

1. Jenkinsダッシュボード > 「Jenkinsの管理」 > 「Credentials」
2. 適切なドメインを選択（グローバルまたはフォルダスコープ）
3. 「Add Credentials」をクリック
4. 設定:
   - **Kind**: Secret text（APIキー等）またはUsername with password（DB認証等）
   - **Secret**: 実際の値
   - **ID**: `redmine-api-key`のような識別子（Pipelineで参照する名前）
   - **Description**: 説明文

#### 3.4.3 .envファイルをJenkinsで使用する場合

開発環境でのテストと同様に`.env`ファイルを使用したい場合:

```groovy
stage('Load Environment') {
    steps {
        // Credentialsから.envファイルを生成
        withCredentials([
            string(credentialsId: 'redmine-api-key', variable: 'API_KEY'),
            string(credentialsId: 'redmine-test-api-key', variable: 'TEST_API_KEY')
        ]) {
            sh '''
                cat > .env << EOF
DB_URL=jdbc:postgresql://db-server:5432/redmine_upster
DB_USER=postgres
DB_PASSWORD=postgres
REDMINE_API_KEY=${API_KEY}
REDMINE_TEST_API_KEY=${TEST_API_KEY}
EOF
                source .env
            '''
        }
    }
}
```

### 3.5 sync-config.ymlとの連携

`sync-config.yml`では`${VAR}`形式で環境変数を参照できます。`.env`ファイルで設定した値が自動的に展開されます。

#### 3.5.1 sync-config.ymlでの環境変数参照

```yaml
projects:
  - name: "本番環境"
    default: true
    redmine:
      baseUrl: "https://redmine.example.com"
      apiKey: "${REDMINE_API_KEY}"        # .envのREDMINE_API_KEYが展開される
      projectId: "project-production"
    sync:
      # ...

  - name: "テスト環境"
    default: false
    redmine:
      baseUrl: "https://redmine-test.example.com"
      apiKey: "${REDMINE_TEST_API_KEY}"   # .envのREDMINE_TEST_API_KEYが展開される
      projectId: "project-test"
    sync:
      # ...
```

#### 3.5.2 デフォルト値付きの参照

環境変数が未設定の場合のフォールバック値を指定できます。

```yaml
redmine:
  baseUrl: "${REDMINE_URL:https://default-redmine.example.com}"
  apiKey: "${REDMINE_API_KEY}"  # デフォルト値なし（必須）
```

### 3.6 ローカル開発での使用例

```bash
# 1. テンプレートからコピー
cp .env.example .env

# 2. .envを編集してAPIキーを設定
vi .env

# 3. 環境変数を読み込み
source .env

# 4. Docker Composeでデータベース起動
docker compose up -d

# 5. アプリケーション実行
java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar \
    --sync \
    --file=deploy/sample_test.csv \
    --dry-run
```

### 3.7 セキュリティ上の注意

- `.env`ファイルは**絶対にGitにコミットしない**でください
- `.gitignore`に`.env`が含まれていることを確認してください
- 本番環境のAPIキーは、開発環境とは別のキーを使用してください
- Jenkinsでは必ずCredentials機能を使用し、平文でのパスワード記述を避けてください

---

## 4. 設定ファイル（sync-config.yml）

### 4.1 設定ファイルの概要

`sync-config.yml`は、Redmine同期の設定を定義するYAMLファイルです。複数のプロジェクト環境（本番、テスト等）を一つのファイルで管理できます。

### 4.2 設定ファイルの基本構造

```yaml
projects:
  - name: "プロジェクト名"
    default: true/false
    redmine:
      baseUrl: "RedmineのURL"
      apiKey: "APIキー"
      projectId: "プロジェクト識別子"
    sync:
      tracker:
        enabled: true/false
        value: "トラッカー名"
      status:
        enabled: true/false
        mode: "BY_DATES/FIXED"
        fixed: "ステータス名"
      customFieldMap:
        列名: "カスタムフィールドID"
```

### 4.3 設定項目の詳細説明

#### 4.3.1 プロジェクト基本設定

| 項目 | 説明 | 必須 |
|------|------|------|
| `name` | プロジェクトの表示名。CLI引数 `--project` で指定する際に使用 | はい |
| `default` | `true`の場合、`--project`を省略した際にこのプロジェクトが使用される | いいえ |

#### 4.3.2 Redmine接続設定（redmine）

| 項目 | 説明 | 必須 |
|------|------|------|
| `baseUrl` | RedmineのベースURL（例: `https://redmine.example.com`） | はい |
| `apiKey` | Redmine APIキー。ユーザー設定ページで発行可能 | はい |
| `projectId` | 同期先のRedmineプロジェクト識別子 | はい |

#### 4.3.3 同期設定（sync）

**トラッカー設定（tracker）**

| 項目 | 説明 | デフォルト |
|------|------|-----------|
| `enabled` | トラッカーを設定するかどうか | false |
| `value` | 設定するトラッカー名（例: `タスク`、`バグ`） | - |

**ステータス設定（status）**

| 項目 | 説明 | デフォルト |
|------|------|-----------|
| `enabled` | ステータスを自動設定するかどうか | false |
| `mode` | `BY_DATES`: 着手日・完了日から自動判定、`FIXED`: 固定値を使用 | - |
| `fixed` | `mode=FIXED`の場合に設定するステータス名 | - |

**カスタムフィールドマッピング（customFieldMap）**

CSVの列名とRedmineカスタムフィールドIDの対応を定義します。

```yaml
customFieldMap:
  チーム: "12"        # CSVの「チーム」列 → カスタムフィールドID 12
  工程: "13"          # CSVの「工程」列 → カスタムフィールドID 13
  社/組織: "14"       # CSVの「社/組織」列 → カスタムフィールドID 14
```

### 4.4 環境変数の使い方

設定ファイル内で環境変数を参照できます。

#### 4.4.1 基本形式

```yaml
apiKey: "${REDMINE_API_KEY}"
```

#### 4.4.2 デフォルト値付き形式

```yaml
baseUrl: "${REDMINE_URL:https://default-redmine.example.com}"
```

環境変数が未設定の場合、コロン以降のデフォルト値が使用されます。

#### 4.4.3 環境変数の設定例

```bash
# Linux/Mac
export REDMINE_API_KEY="your-api-key-here"
export REDMINE_TEST_API_KEY="your-test-api-key"

# Windows
set REDMINE_API_KEY=your-api-key-here
```

### 4.5 複数プロジェクト設定例

```yaml
projects:
  # 本番環境
  - name: "本番環境"
    default: true
    redmine:
      baseUrl: "https://redmine.example.com"
      apiKey: "${REDMINE_API_KEY}"
      projectId: "project-production"
    sync:
      tracker:
        enabled: true
        value: "タスク"
      status:
        enabled: true
        mode: "BY_DATES"
        fixed: "New"
      customFieldMap:
        チーム: "12"
        工程: "13"
        社/組織: "14"
        着手実績: "15"
        完了実績: "16"
        成果物: "17"

  # テスト環境
  - name: "テスト環境"
    default: false
    redmine:
      baseUrl: "https://redmine-test.example.com"
      apiKey: "${REDMINE_TEST_API_KEY}"
      projectId: "project-test"
    sync:
      tracker:
        enabled: false
      status:
        enabled: true
        mode: "FIXED"
        fixed: "New"
      customFieldMap: {}

  # 開発環境
  - name: "開発環境"
    default: false
    redmine:
      baseUrl: "${DEV_REDMINE_URL:http://localhost:3000}"
      apiKey: "${DEV_REDMINE_API_KEY}"
      projectId: "dev-project"
    sync:
      tracker:
        enabled: true
        value: "開発タスク"
      status:
        enabled: false
      customFieldMap:
        担当チーム: "20"
```

---

## 5. データベース設定

### 5.1 PostgreSQL接続設定

データベース接続は環境変数または`application.yml`で設定します。

#### 5.1.1 環境変数での設定（推奨）

```bash
export DB_URL="jdbc:postgresql://hostname:5432/redmine_upster"
export DB_USER="postgres"
export DB_PASSWORD="your-password"
```

#### 5.1.2 デフォルト値

環境変数を設定しない場合、以下のデフォルト値が使用されます。

| 環境変数 | デフォルト値 |
|---------|-------------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/redmine_upster` |
| `DB_USER` | `postgres` |
| `DB_PASSWORD` | `postgres` |

**注意:** Docker Composeで起動したPostgreSQLはポート`5433`を使用するため、以下のように設定してください。

```bash
export DB_URL="jdbc:postgresql://localhost:5433/redmine_upster"
```

### 5.2 issue_linkテーブルの説明

アプリケーション起動時にFlywayが自動的にテーブルを作成します。

#### 5.2.1 テーブル構造

V1〜V4 のマイグレーション適用後の構造:

```sql
CREATE TABLE issue_link (
  id BIGSERIAL PRIMARY KEY,
  external_key TEXT,            -- 旧方式の名残（新方式では未使用、NULL可）
  issue_id BIGINT NOT NULL,
  payload_hash TEXT,
  project_id TEXT,
  CONSTRAINT issue_link_issue_id_project_id_key UNIQUE (issue_id, project_id)
);
```

#### 5.2.2 カラム説明

| カラム名 | 型 | 説明 |
|---------|-----|------|
| `id` | BIGSERIAL | 自動採番の主キー |
| `external_key` | TEXT | 旧方式（CSVの`id`列で紐付け）の値。新方式では使用しない |
| `issue_id` | BIGINT | このツールが作成・更新したRedmineチケットのID |
| `payload_hash` | TEXT | 前回送信内容のハッシュ（変更なしスキップ用）。論理削除済みは `logical-delete:<statusId>` |
| `project_id` | TEXT | 同期先のRedmineプロジェクトID（設定の`projectId`） |

#### 5.2.3 用途

Excel/CSVの行とRedmineチケットの紐付けは、ファイルの「チケットID」列で行います。
このテーブルは「このツールが管理しているチケット」の記録です。

- **新規作成・更新時**: `(issue_id, project_id)` と送信内容のハッシュを記録
- **更新時**: 前回と同じ内容なら更新をスキップ（`--force-update` で無効化）
- **論理削除**: 記録されているチケットのうちExcelにないものを削除候補とし、
  `sync.deletion.statusId` が設定されていればそのステータスへ変更する（物理削除はしない）
- V4 マイグレーションで、同じ `(issue_id, project_id)` の重複行は id が最大の1行だけ残る

### 5.3 データベースの手動作成（必要な場合）

```bash
# PostgreSQLに接続
psql -h localhost -p 5433 -U postgres

# データベース作成
CREATE DATABASE redmine_upster;

# 接続終了
\q
```

---

## 6. CLI引数の説明

### 6.1 基本的な使用方法

```bash
java -jar redmineUpster.jar --sync [オプション]
```

### 6.2 引数一覧

| 引数 | 必須 | 説明 |
|------|------|------|
| `--sync` | はい | CLI同期モードで実行。この引数がない場合はWebサーバーモードで起動 |
| `--config=<path>` | いいえ | 設定ファイルのパス。省略時は`sync-config.yml` |
| `--project=<name>` | いいえ | 使用するプロジェクト名。省略時は`default=true`のプロジェクト |
| `--file=<path>` | はい | 同期するCSV/Excelファイルのパス |
| `--dry-run` | いいえ | ドライランモード。実際のRedmine更新を行わない |
| `--log-dir=<path>` | いいえ | ログ出力ディレクトリ。省略時はカレントディレクトリ |

### 6.3 各引数の詳細説明と使用例

#### 6.3.1 --sync

CLI同期モードを有効にします。この引数がない場合、アプリケーションはWebサーバーとして起動します。

```bash
# CLI同期モード
java -jar redmineUpster.jar --sync --file=input.csv

# Webサーバーモード（--syncなし）
java -jar redmineUpster.jar
```

#### 6.3.2 --config

設定ファイルのパスを指定します。絶対パスまたは相対パスが使用できます。

```bash
# 絶対パス
java -jar redmineUpster.jar --sync --config=/etc/redmine-sync/sync-config.yml --file=input.csv

# 相対パス
java -jar redmineUpster.jar --sync --config=./config/production.yml --file=input.csv

# 省略時はsync-config.ymlを使用
java -jar redmineUpster.jar --sync --file=input.csv
```

#### 6.3.3 --project

設定ファイル内の特定のプロジェクトを指定します。

```bash
# 本番環境プロジェクトを使用
java -jar redmineUpster.jar --sync --project="本番環境" --file=input.csv

# テスト環境プロジェクトを使用
java -jar redmineUpster.jar --sync --project="テスト環境" --file=input.csv

# 省略時はdefault=trueのプロジェクトを使用
java -jar redmineUpster.jar --sync --file=input.csv
```

**注意:** プロジェクト名に空白が含まれる場合は引用符で囲んでください。

#### 6.3.4 --file

同期するCSVまたはExcelファイルのパスを指定します。この引数は**必須**です。

```bash
# CSVファイル
java -jar redmineUpster.jar --sync --file=/data/tasks.csv

# Excelファイル
java -jar redmineUpster.jar --sync --file=/data/tasks.xlsx

# 相対パス
java -jar redmineUpster.jar --sync --file=./input/tasks.csv
```

**対応ファイル形式:**
- CSV（.csv）- UTF-8エンコーディング推奨
- Excel（.xlsx）

#### 6.3.5 --dry-run

実際のRedmine更新を行わずに、処理内容を確認できます。本番実行前のテストに使用してください。

```bash
# ドライランモードで実行
java -jar redmineUpster.jar --sync --file=input.csv --dry-run
```

ドライランモードでは以下が確認できます。
- CSVファイルの解析結果
- 作成/更新されるチケット数
- エラーになる行の検出

#### 6.3.6 --log-dir

ログファイルの出力先ディレクトリを指定します。

```bash
# 指定したディレクトリにログを出力
java -jar redmineUpster.jar --sync --file=input.csv --log-dir=/var/log/redmine-sync/

# Jenkinsワークスペースに出力
java -jar redmineUpster.jar --sync --file=input.csv --log-dir=${WORKSPACE}/logs/

# 省略時はカレントディレクトリに出力
java -jar redmineUpster.jar --sync --file=input.csv
```

### 6.4 実行例の組み合わせ

```bash
# 最小構成（必須引数のみ）
java -jar redmineUpster.jar --sync --file=tasks.csv

# 全オプション指定
java -jar redmineUpster.jar \
  --sync \
  --config=/etc/redmine-sync/sync-config.yml \
  --project="本番環境" \
  --file=/data/input/tasks.csv \
  --log-dir=/var/log/redmine-sync/

# ドライラン（本番実行前の確認）
java -jar redmineUpster.jar \
  --sync \
  --config=sync-config.yml \
  --project="本番環境" \
  --file=tasks.csv \
  --dry-run \
  --log-dir=./logs/
```

---

## 7. Jenkins Pipeline設定例

### 7.1 基本的なJenkinsfile

```groovy
pipeline {
    agent any

    environment {
        // データベース接続設定
        DB_URL = 'jdbc:postgresql://db-server:5432/redmine_upster'
        DB_USER = credentials('db-user')
        DB_PASSWORD = credentials('db-password')

        // Redmine API設定
        REDMINE_API_KEY = credentials('redmine-api-key')
    }

    parameters {
        choice(
            name: 'PROJECT',
            choices: ['本番環境', 'テスト環境', '開発環境'],
            description: '同期先のプロジェクトを選択'
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: true,
            description: 'チェックを入れるとドライランモードで実行（実際の更新なし）'
        )
        file(
            name: 'CSV_FILE',
            description: '同期するCSVファイルをアップロード'
        )
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh './mvnw clean package -DskipTests'
            }
        }

        stage('Prepare CSV') {
            steps {
                script {
                    // アップロードされたファイルを作業ディレクトリにコピー
                    sh "cp ${CSV_FILE} ${WORKSPACE}/input.csv"
                }
            }
        }

        stage('Sync') {
            steps {
                script {
                    def dryRunFlag = params.DRY_RUN ? '--dry-run' : ''

                    sh """
                        java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar \\
                            --sync \\
                            --config=sync-config.yml \\
                            --project="${params.PROJECT}" \\
                            --file=${WORKSPACE}/input.csv \\
                            --log-dir=${WORKSPACE}/logs/ \\
                            ${dryRunFlag}
                    """
                }
            }
        }
    }

    post {
        always {
            // ログファイルをアーカイブ
            archiveArtifacts artifacts: 'logs/*.log', allowEmptyArchive: true
        }
        success {
            echo 'Sync completed successfully!'
        }
        failure {
            echo 'Sync failed. Check the logs for details.'
            // メール通知などを追加可能
            // mail to: 'team@example.com', subject: 'Redmine Sync Failed', body: '...'
        }
    }
}
```

### 7.2 パラメータ化ビルドの詳細設定

Jenkinsのパラメータ化ビルドを使用すると、実行時に値を指定できます。

#### 7.2.1 Jenkinsジョブ設定画面での設定

1. ジョブ設定 > 「このプロジェクトはパラメータ化されています」にチェック
2. 以下のパラメータを追加:

**選択パラメータ（PROJECT）**
- 名前: `PROJECT`
- 選択肢:
  ```
  本番環境
  テスト環境
  開発環境
  ```
- 説明: 同期先のプロジェクトを選択

**真偽値パラメータ（DRY_RUN）**
- 名前: `DRY_RUN`
- デフォルト値: チェックあり
- 説明: ドライランモードで実行

**ファイルパラメータ（CSV_FILE）**
- 名前: `CSV_FILE`
- 説明: 同期するCSVファイル

### 7.3 CSVファイルのアップロード方法

#### 7.3.1 File Parameterプラグインを使用する場合

Jenkinsfileでの設定:
```groovy
parameters {
    file(name: 'CSV_FILE', description: '同期するCSVファイル')
}
```

ビルド実行時:
1. 「ビルドのパラメータ化」画面でファイルを選択
2. 「ビルド実行」をクリック

#### 7.3.2 外部ストレージからダウンロードする場合

```groovy
stage('Download CSV') {
    steps {
        // S3からダウンロード
        sh 'aws s3 cp s3://bucket/path/tasks.csv ${WORKSPACE}/input.csv'

        // または共有フォルダからコピー
        sh 'cp /mnt/shared/exports/tasks.csv ${WORKSPACE}/input.csv'
    }
}
```

#### 7.3.3 SCMから取得する場合

```groovy
stage('Checkout CSV') {
    steps {
        checkout([
            $class: 'GitSCM',
            branches: [[name: '*/main']],
            userRemoteConfigs: [[
                url: 'https://github.com/org/csv-repo.git',
                credentialsId: 'git-credentials'
            ]],
            extensions: [[
                $class: 'SparseCheckoutPaths',
                sparseCheckoutPaths: [[path: 'exports/']]
            ]]
        ])
    }
}
```

### 7.4 成功/失敗時の処理

#### 7.4.1 メール通知

```groovy
post {
    failure {
        mail(
            to: 'team@example.com',
            subject: "[FAILED] Redmine Sync - ${env.JOB_NAME} #${env.BUILD_NUMBER}",
            body: """
                ビルドが失敗しました。

                ジョブ: ${env.JOB_NAME}
                ビルド番号: ${env.BUILD_NUMBER}
                プロジェクト: ${params.PROJECT}

                詳細: ${env.BUILD_URL}
            """
        )
    }
    success {
        mail(
            to: 'team@example.com',
            subject: "[SUCCESS] Redmine Sync - ${env.JOB_NAME} #${env.BUILD_NUMBER}",
            body: """
                同期が完了しました。

                ジョブ: ${env.JOB_NAME}
                ビルド番号: ${env.BUILD_NUMBER}
                プロジェクト: ${params.PROJECT}
                ドライラン: ${params.DRY_RUN}
            """
        )
    }
}
```

#### 7.4.2 Slack通知

```groovy
post {
    failure {
        slackSend(
            channel: '#redmine-sync',
            color: 'danger',
            message: "Redmine同期失敗: ${env.JOB_NAME} #${env.BUILD_NUMBER}\nプロジェクト: ${params.PROJECT}\n${env.BUILD_URL}"
        )
    }
    success {
        slackSend(
            channel: '#redmine-sync',
            color: 'good',
            message: "Redmine同期完了: ${env.JOB_NAME} #${env.BUILD_NUMBER}\nプロジェクト: ${params.PROJECT}"
        )
    }
}
```

### 7.5 定期実行の設定

```groovy
pipeline {
    triggers {
        // 毎日午前9時に実行
        cron('0 9 * * *')

        // 毎週月曜日の午前8時に実行
        // cron('0 8 * * 1')
    }

    // ... 以降は通常通り
}
```

### 7.6 本番運用向け完全版Jenkinsfile

```groovy
pipeline {
    agent any

    options {
        // ビルド履歴を10件保持
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // タイムアウト30分
        timeout(time: 30, unit: 'MINUTES')
        // 同時実行を禁止
        disableConcurrentBuilds()
    }

    environment {
        DB_URL = 'jdbc:postgresql://db-server:5432/redmine_upster'
        DB_USER = credentials('db-user')
        DB_PASSWORD = credentials('db-password')
        REDMINE_API_KEY = credentials('redmine-api-key')
        REDMINE_TEST_API_KEY = credentials('redmine-test-api-key')
    }

    parameters {
        choice(
            name: 'PROJECT',
            choices: ['本番環境', 'テスト環境'],
            description: '同期先プロジェクト'
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: true,
            description: 'ドライランモード'
        )
        file(
            name: 'CSV_FILE',
            description: 'CSVファイル'
        )
    }

    stages {
        stage('Validate') {
            steps {
                script {
                    if (!params.CSV_FILE) {
                        error 'CSVファイルが指定されていません'
                    }

                    // 本番環境でドライラン無効の場合は警告
                    if (params.PROJECT == '本番環境' && !params.DRY_RUN) {
                        input message: '本番環境に実際に同期しますか？', ok: '実行'
                    }
                }
            }
        }

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh './mvnw clean package -DskipTests'
            }
        }

        stage('Prepare') {
            steps {
                sh 'mkdir -p ${WORKSPACE}/logs'
                sh "cp ${CSV_FILE} ${WORKSPACE}/input.csv"

                // CSVの行数を確認
                script {
                    def lineCount = sh(
                        script: 'wc -l < ${WORKSPACE}/input.csv',
                        returnStdout: true
                    ).trim()
                    echo "CSVファイル: ${lineCount} 行"
                }
            }
        }

        stage('Sync') {
            steps {
                script {
                    def dryRunFlag = params.DRY_RUN ? '--dry-run' : ''

                    def exitCode = sh(
                        script: """
                            java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar \\
                                --sync \\
                                --config=sync-config.yml \\
                                --project="${params.PROJECT}" \\
                                --file=${WORKSPACE}/input.csv \\
                                --log-dir=${WORKSPACE}/logs/ \\
                                ${dryRunFlag}
                        """,
                        returnStatus: true
                    )

                    if (exitCode != 0) {
                        error "同期処理が失敗しました (exit code: ${exitCode})"
                    }
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'logs/*.log', allowEmptyArchive: true

            // ログファイルの内容をコンソールに出力
            script {
                def logFiles = findFiles(glob: 'logs/*.log')
                logFiles.each { logFile ->
                    echo "=== ${logFile.name} ==="
                    echo readFile(logFile.path)
                }
            }
        }
        success {
            echo 'Sync completed successfully!'
        }
        failure {
            mail(
                to: 'admin@example.com',
                subject: "[FAILED] Redmine Sync - ${params.PROJECT}",
                body: "詳細: ${env.BUILD_URL}console"
            )
        }
    }
}
```

---

## 8. ログファイル

### 8.1 出力場所

ログファイルは`--log-dir`で指定したディレクトリに出力されます。
省略した場合は、コマンドを実行したカレントディレクトリに出力されます。

```
<log-dir>/sync-YYYYMMDD-HHmmss.log
```

**例:**
```
./logs/sync-20260116-103045.log
```

### 8.2 ログフォーマット

```
[YYYY-MM-DD HH:mm:ss] [LEVEL] メッセージ
```

**例:**
```
[2026-01-16 10:30:45] [INFO] === Redmine Sync Started ===
[2026-01-16 10:30:45] [INFO] File: /data/input/tasks.csv
[2026-01-16 10:30:45] [INFO] Dry Run: false
[2026-01-16 10:30:45] [INFO] Log File: /var/log/redmine-sync/sync-20260116-103045.log
[2026-01-16 10:30:46] [INFO] Loading config from: sync-config.yml
[2026-01-16 10:30:46] [INFO] Project: 本番環境
[2026-01-16 10:30:46] [INFO] Parsing file: /data/input/tasks.csv
[2026-01-16 10:30:47] [INFO] Parsed 150 rows
[2026-01-16 10:30:47] [INFO] Calculating diff...
[2026-01-16 10:30:48] [INFO] Diff items: 150
[2026-01-16 10:30:48] [INFO]   CREATE: 45
[2026-01-16 10:30:48] [INFO]   UPDATE: 105
[2026-01-16 10:30:48] [INFO] Redmine URL: https://redmine.example.com
[2026-01-16 10:30:48] [INFO] Redmine Project: project-a
[2026-01-16 10:30:48] [INFO] Executing sync...
[2026-01-16 10:35:12] [INFO] === Sync Complete ===
[2026-01-16 10:35:12] [INFO] Total: 150
[2026-01-16 10:35:12] [INFO] Success: 148
[2026-01-16 10:35:12] [INFO] Errors: 2
[2026-01-16 10:35:12] [WARN] Error details:
[2026-01-16 10:35:12] [WARN]   - Row T-045: Invalid date format
[2026-01-16 10:35:12] [WARN]   - Row T-089: Required field missing
```

### 8.3 ログレベル

| レベル | 説明 |
|--------|------|
| INFO | 通常の処理情報 |
| WARN | 警告（処理は継続） |
| ERROR | エラー（処理失敗） |

### 8.4 ログローテーション

アプリケーション自体はログローテーション機能を持ちません。
長期運用する場合は、logrotateなどの外部ツールを使用してください。

```bash
# /etc/logrotate.d/redmine-sync
/var/log/redmine-sync/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
}
```

---

## 9. トラブルシューティング

### 9.1 ビルドエラー

#### Maven Wrapperの実行権限がない

**症状:**
```
bash: ./mvnw: Permission denied
```

**対処:**
```bash
chmod +x mvnw
```

#### Javaバージョンが古い

**症状:**
```
Error: A JNI error has occurred, please check your installation and try again
Exception in thread "main" java.lang.UnsupportedClassVersionError
```

**対処:**
```bash
# Javaバージョン確認
java -version

# Java 17以上をインストール・設定
export JAVA_HOME=/path/to/java17
export PATH=$JAVA_HOME/bin:$PATH
```

### 9.2 データベース接続エラー

#### PostgreSQLに接続できない

**症状:**
```
Connection refused to host: localhost, port: 5432
```

**対処:**
1. PostgreSQLが起動しているか確認
   ```bash
   docker compose ps
   # または
   systemctl status postgresql
   ```

2. 接続設定を確認
   ```bash
   # Docker Composeの場合はポート5433
   export DB_URL="jdbc:postgresql://localhost:5433/redmine_upster"
   ```

#### 認証エラー

**症状:**
```
FATAL: password authentication failed for user "postgres"
```

**対処:**
- 環境変数`DB_USER`と`DB_PASSWORD`が正しいか確認
- PostgreSQLの`pg_hba.conf`の認証設定を確認

### 9.3 設定ファイルエラー

#### 設定ファイルが見つからない

**症状:**
```
Configuration file not found: sync-config.yml
```

**対処:**
- ファイルが存在するか確認
- パスが正しいか確認（絶対パスを推奨）
  ```bash
  java -jar redmineUpster.jar --sync --config=/absolute/path/to/sync-config.yml --file=input.csv
  ```

#### 環境変数が展開されない

**症状:**
APIキーが空でRedmine接続に失敗

**対処:**
1. 環境変数が設定されているか確認
   ```bash
   echo $REDMINE_API_KEY
   ```

2. 設定ファイルの記法を確認
   ```yaml
   # 正しい記法
   apiKey: "${REDMINE_API_KEY}"

   # 間違い（シングルクォート）
   apiKey: '${REDMINE_API_KEY}'
   ```

### 9.4 CSV解析エラー

#### 文字化け

**症状:**
日本語が正しく表示されない、パースエラー

**対処:**
- CSVファイルをUTF-8で保存
- BOMなしUTF-8を推奨
- Excelで保存する場合は「CSV UTF-8」形式を選択

#### 必須列がない

**症状:**
```
[ERROR] 入力ファイルの検証エラー: N件（Redmineは更新していません）
```

**対処:**
続けて出力される各エラー（行番号と階層パス付き）を確認し、ファイルを修正する:
- 親行がない（階層の上位の行をファイルに追加する）
- 同じ階層パスの行が重複している
- `チケットID` が数値でない、または重複している
- `トラッカー` の名前が `trackerMap` / Redmine に存在しない

### 9.5 Redmine API エラー

#### 401 Unauthorized

**症状:**
```
Redmine API error: 401 Unauthorized
```

**対処:**
- APIキーが正しいか確認
- APIキーが有効か確認（Redmineユーザー設定で再生成）
- Redmine側でREST APIが有効か確認（管理 > 設定 > API）

#### 403 Forbidden

**症状:**
```
Redmine API error: 403 Forbidden
```

**対処:**
- APIキーのユーザーがプロジェクトにアクセス権を持っているか確認
- チケットの作成/編集権限があるか確認

#### 404 Not Found

**症状:**
```
Redmine API error: 404 Not Found
```

**対処:**
- `projectId`が正しいか確認
- プロジェクトが存在するか確認
- URLが正しいか確認（末尾のスラッシュに注意）

### 9.6 Jenkins固有の問題

#### ファイルパラメータが空

**症状:**
CSVファイルが見つからない

**対処:**
- ビルド実行時にファイルを選択したか確認
- Jenkinsfileでファイルパスが正しく参照されているか確認
  ```groovy
  sh "cp ${CSV_FILE} ${WORKSPACE}/input.csv"
  ```

#### 環境変数が渡らない

**症状:**
Credentials Bindingが機能しない

**対処:**
- `credentials()`関数が正しく使われているか確認
- Credentialが存在するか確認（Jenkinsの認証情報管理）
  ```groovy
  environment {
      REDMINE_API_KEY = credentials('redmine-api-key')
  }
  ```

### 9.7 エラーコード

| 終了コード | 意味 |
|-----------|------|
| 0 | 正常終了 |
| 1 | エラーあり（一部または全部の処理が失敗） |

### 9.8 デバッグ方法

#### 詳細ログの確認

1. 実行時のログファイルを確認
   ```bash
   cat logs/sync-*.log
   ```

2. Spring Bootのデバッグログを有効化
   ```bash
   java -jar redmineUpster.jar --sync --file=input.csv --logging.level.mozaki=DEBUG
   ```

#### ドライランでのテスト

本番実行前に必ずドライランで確認してください。

```bash
java -jar redmineUpster.jar --sync --file=input.csv --dry-run
```

---

## 付録

### A. CSVファイルフォーマット例

```csv
チケットID,トラッカー,チーム,工程,大分類,中分類,小分類,成果物,タスク,社/組織,担当,着手予定,着手実績,完了予定,完了実績
120,サマリ,基盤,設計,UI,,,,,開発1課,,,,,
121,サマリ,基盤,設計,UI,画面,,,,開発1課,,,,,
,サマリ,基盤,設計,UI,画面,ログイン,,,開発1課,,,,,
,サマリ,基盤,設計,UI,画面,ログイン,画面設計書,,開発1課,,,,,
,タスク,基盤,設計,UI,画面,ログイン,画面設計書,ログイン画面作成,開発1課,山田,2026-01-10,2026-01-11,2026-01-20,2026-01-19
```

チケットIDが空欄の行は新規作成され、作成されたIDがファイルへ書き戻されます（元ファイルは `.bak`）。

### B. クイックスタート

```bash
# 1. 環境変数設定
export DB_URL="jdbc:postgresql://localhost:5433/redmine_upster"
export REDMINE_API_KEY="your-api-key"

# 2. データベース起動
docker compose up -d

# 3. ビルド
./mvnw clean package

# 4. ドライラン実行
java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar \
  --sync \
  --file=deploy/sample_test.csv \
  --dry-run

# 5. 本番実行
java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar \
  --sync \
  --file=deploy/sample_test.csv
```

---

最終更新日: 2026-01-16
