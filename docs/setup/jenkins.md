# Jenkins セットアップガイド

本ドキュメントでは、redmineUpsterをJenkinsで定期実行するための環境構築手順を解説します。

---

## 目次

1. [Jenkinsのインストール](#1-jenkinsのインストール)
2. [初期設定ウィザード](#2-初期設定ウィザード)
3. [必要なプラグイン](#3-必要なプラグイン)
4. [Credentialsの設定](#4-credentialsの設定)
5. [パイプラインジョブの作成](#5-パイプラインジョブの作成)
6. [Jenkinsfileの配置](#6-jenkinsfileの配置)
7. [定期実行の設定](#7-定期実行の設定)
8. [トラブルシューティング](#8-トラブルシューティング)

---

## 1. Jenkinsのインストール

### 1.1 前提条件

| 要件 | 仕様 |
|------|------|
| Java | Java 17以上（Java 17推奨） |
| メモリ | 最小2GB、推奨4GB以上 |
| ディスク | 最小10GB |

### 1.2 Ubuntu でのインストール

#### 公式リポジトリを使用

```bash
# GPGキーの追加
sudo wget -O /usr/share/keyrings/jenkins-keyring.asc \
  https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key

# リポジトリの追加
echo "deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc]" \
  https://pkg.jenkins.io/debian-stable binary/ | sudo tee \
  /etc/apt/sources.list.d/jenkins.list > /dev/null

# パッケージリストの更新
sudo apt update

# Jenkinsのインストール
sudo apt install -y jenkins

# サービスの起動と自動起動設定
sudo systemctl start jenkins
sudo systemctl enable jenkins

# ステータス確認
sudo systemctl status jenkins
```

#### Javaが未インストールの場合

```bash
# Java 17のインストール
sudo apt install -y temurin-17-jdk

# Jenkinsサービスを再起動
sudo systemctl restart jenkins
```

### 1.3 CentOS / Rocky Linux でのインストール

#### 公式リポジトリを使用

```bash
# リポジトリファイルの作成
sudo wget -O /etc/yum.repos.d/jenkins.repo \
    https://pkg.jenkins.io/redhat-stable/jenkins.repo

# GPGキーのインポート
sudo rpm --import https://pkg.jenkins.io/redhat-stable/jenkins.io-2023.key

# Jenkinsのインストール
sudo dnf install -y jenkins

# ファイアウォールの設定（必要な場合）
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --reload

# サービスの起動と自動起動設定
sudo systemctl start jenkins
sudo systemctl enable jenkins

# ステータス確認
sudo systemctl status jenkins
```

### 1.4 Dockerでのインストール

開発環境やテスト用途には、Dockerでの実行も便利です。

```bash
# Jenkinsコンテナの起動
docker run -d \
  --name jenkins \
  -p 8080:8080 \
  -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  jenkins/jenkins:lts-jdk17

# 初期パスワードの確認
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

docker-compose.ymlの例:

```yaml
services:
  jenkins:
    image: jenkins/jenkins:lts-jdk17
    container_name: jenkins
    ports:
      - "8080:8080"
      - "50000:50000"
    volumes:
      - jenkins_home:/var/jenkins_home
    restart: unless-stopped
    environment:
      - JAVA_OPTS=-Djenkins.install.runSetupWizard=true

volumes:
  jenkins_home:
```

### 1.5 インストール確認

ブラウザで `http://your-server:8080` にアクセスし、Jenkinsの画面が表示されることを確認します。

---

## 2. 初期設定ウィザード

### 2.1 初期パスワードの取得

初回アクセス時に管理者パスワードの入力を求められます。

```bash
# 初期パスワードを確認
sudo cat /var/lib/jenkins/secrets/initialAdminPassword
```

表示されたパスワードをブラウザに入力します。

### 2.2 プラグインのインストール

「Install suggested plugins」を選択すると、一般的に使用されるプラグインが自動インストールされます。

後から個別にインストールすることも可能です。

### 2.3 管理者ユーザーの作成

- ユーザー名
- パスワード
- 名前
- メールアドレス

を入力して管理者ユーザーを作成します。

### 2.4 Jenkins URLの設定

Jenkins URLを設定します。後から変更も可能です。

- 例: `http://jenkins.example.com:8080/`

### 2.5 設定完了

「Start using Jenkins」をクリックして設定を完了します。

---

## 3. 必要なプラグイン

### 3.1 必須プラグイン

redmineUpsterの運用に必要なプラグインをインストールします。

| プラグイン名 | 用途 |
|-------------|------|
| Pipeline | パイプラインジョブの実行 |
| Git | Gitリポジトリとの連携 |
| Credentials Binding | シークレット情報の管理 |
| Timestamper | ログへのタイムスタンプ追加 |

### 3.2 推奨プラグイン

| プラグイン名 | 用途 |
|-------------|------|
| File Parameter | CSVファイルのアップロード |
| Slack Notification | Slack通知 |
| Email Extension | 拡張メール通知 |
| Blue Ocean | モダンなUI |
| Locale | 日本語化 |

### 3.3 プラグインのインストール方法

1. 「Jenkinsの管理」 > 「Plugins」を選択
2. 「Available plugins」タブを選択
3. 検索ボックスでプラグイン名を検索
4. インストールするプラグインにチェック
5. 「Install」をクリック
6. インストール完了後、必要に応じてJenkinsを再起動

```bash
# Jenkinsの再起動
sudo systemctl restart jenkins
```

### 3.4 プラグインの一括インストール（CLI）

jenkins-cliを使用した一括インストール:

```bash
# jenkins-cliのダウンロード
wget http://localhost:8080/jnlpJars/jenkins-cli.jar

# プラグインの一括インストール
java -jar jenkins-cli.jar -s http://localhost:8080/ -auth admin:password install-plugin \
  git \
  pipeline-stage-view \
  credentials-binding \
  timestamper \
  file-parameters \
  slack \
  email-ext \
  blueocean \
  locale
```

---

## 4. Credentialsの設定

### 4.1 Credentialsとは

Jenkinsのcredentials機能を使用すると、APIキーやパスワードなどの機密情報を安全に管理できます。

### 4.2 Credentialの種類

| 種類 | 用途 |
|------|------|
| Secret text | APIキー、トークン |
| Username with password | データベース認証など |
| SSH Username with private key | Gitリポジトリへのアクセス |
| Secret file | 設定ファイルなど |

### 4.3 Credentialの登録手順

#### Secret text（APIキー）の登録

1. 「Jenkinsの管理」 > 「Credentials」を選択
2. 「System」 > 「Global credentials (unrestricted)」を選択
3. 「Add Credentials」をクリック
4. 以下を入力:
   - **Kind**: Secret text
   - **Scope**: Global
   - **Secret**: 実際のAPIキー
   - **ID**: `redmine-api-key`（Pipelineで参照する名前）
   - **Description**: Redmine API Key for Production

5. 「Create」をクリック

### 4.4 redmineUpster用のCredential一覧

以下のCredentialを登録することを推奨します。

| ID | Kind | 説明 |
|----|------|------|
| `redmine-api-key` | Secret text | 本番環境Redmine APIキー |
| `redmine-test-api-key` | Secret text | テスト環境Redmine APIキー |
| `git-credentials` | Username with password or SSH | Gitリポジトリアクセス |

### 4.5 Pipelineでの使用方法

```groovy
pipeline {
    environment {
        // Secret textの参照
        REDMINE_API_KEY = credentials('redmine-api-key')
    }

    stages {
        stage('Example') {
            steps {
                sh '''
                    echo "API Key: ${REDMINE_API_KEY}"
                '''
            }
        }
    }
}
```

---

## 5. パイプラインジョブの作成

### 5.1 新規ジョブの作成

1. Jenkinsダッシュボードで「新規ジョブ作成」をクリック
2. ジョブ名を入力（例: `redmineUpster-sync`）
3. 「Pipeline」を選択
4. 「OK」をクリック

### 5.2 ジョブの設定

#### 全般設定

- [x] このビルドはパラメータ化されています

#### パラメータの追加

**選択パラメータ（PROJECT）**
- 名前: `PROJECT`
- 選択肢:
  ```
  本番環境
  テスト環境
  ```
- 説明: 同期先のプロジェクトを選択

**真偽値パラメータ（DRY_RUN）**
- 名前: `DRY_RUN`
- デフォルト値: チェックあり
- 説明: ドライランモードで実行（実際の更新なし）

**ファイルパラメータ（CSV_FILE）**（File Parameterプラグイン使用時）
- 名前: `CSV_FILE`
- 説明: 同期するCSVファイルをアップロード

#### Pipeline定義

「Pipeline script from SCM」を選択する場合:

1. **SCM**: Git
2. **Repository URL**: リポジトリのURL
3. **Credentials**: git-credentials（必要な場合）
4. **Branch**: */main
5. **Script Path**: Jenkinsfile

「Pipeline script」を直接記述する場合は、Jenkinsfileの内容をそのまま入力します。

### 5.3 保存と実行

1. 「保存」をクリック
2. 「パラメータ付きビルド」をクリック
3. パラメータを設定して「ビルド」をクリック

---

## 6. Jenkinsfileの配置

### 6.1 Jenkinsfileの作成

プロジェクトのルートディレクトリに `Jenkinsfile` を作成します。

```groovy
// Jenkinsfile

pipeline {
    agent any

    options {
        // ビルド履歴を10件保持
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // タイムアウト30分
        timeout(time: 30, unit: 'MINUTES')
        // 同時実行を禁止
        disableConcurrentBuilds()
        // タイムスタンプを追加
        timestamps()
    }

    environment {
        // Redmine API設定
        REDMINE_API_KEY = credentials('redmine-api-key')
        REDMINE_TEST_API_KEY = credentials('redmine-test-api-key')
    }

    parameters {
        choice(
            name: 'PROJECT',
            choices: ['本番環境', 'テスト環境'],
            description: '同期先のプロジェクトを選択'
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: true,
            description: 'ドライランモード（実際の更新なし）'
        )
        file(
            name: 'CSV_FILE',
            description: '同期するCSVファイル'
        )
    }

    stages {
        stage('Validate') {
            steps {
                script {
                    // パラメータの検証
                    if (!fileExists(env.CSV_FILE ?: '')) {
                        // ファイルパラメータが指定されていない場合
                        // 固定パスから取得するなどの代替処理
                        echo "CSV file not uploaded, using default location"
                    }

                    // 本番環境への同期時は確認
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
                echo 'Building application...'
                sh './mvnw clean package -DskipTests'
            }
        }

        stage('Prepare') {
            steps {
                echo 'Preparing environment...'

                // ログディレクトリの作成
                sh 'mkdir -p ${WORKSPACE}/logs'

                // CSVファイルの準備
                script {
                    if (fileExists(env.CSV_FILE ?: '')) {
                        sh "cp ${CSV_FILE} ${WORKSPACE}/input.csv"
                    } else {
                        // デフォルトCSVを使用（必要に応じて変更）
                        sh "cp deploy/sample_test.csv ${WORKSPACE}/input.csv"
                    }
                }

                // CSVの行数を確認
                script {
                    def lineCount = sh(
                        script: 'wc -l < ${WORKSPACE}/input.csv',
                        returnStdout: true
                    ).trim()
                    echo "CSV file: ${lineCount} lines"
                }
            }
        }

        stage('Sync') {
            steps {
                echo "Syncing to ${params.PROJECT}..."

                script {
                    def dryRunFlag = params.DRY_RUN ? '--dry-run' : ''

                    // 追加の環境変数が必要ならここで指定（DB接続情報は不要になりました）
                    withEnv([]) {
                        def exitCode = sh(
                            script: """
                                java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar \\
                                    --sync \\
                                    --config=sync-config.yml \\
                                    --project="${params.PROJECT}" \\
                                    --file=\${WORKSPACE}/input.csv \\
                                    --log-dir=\${WORKSPACE}/logs/ \\
                                    ${dryRunFlag}
                            """,
                            returnStatus: true
                        )

                        if (exitCode != 0) {
                            error "Sync failed with exit code: ${exitCode}"
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            // ログファイルをアーカイブ
            archiveArtifacts artifacts: 'logs/*.log', allowEmptyArchive: true

            // ログファイルの内容をコンソールに出力
            script {
                def logFiles = findFiles(glob: 'logs/*.log')
                logFiles.each { logFile ->
                    echo "=== ${logFile.name} ==="
                    echo readFile(logFile.path)
                }
            }

            // ワークスペースのクリーンアップ
            cleanWs()
        }

        success {
            echo 'Sync completed successfully!'

            // 成功時の通知（Slack）
            // slackSend(
            //     channel: '#redmine-sync',
            //     color: 'good',
            //     message: "Redmine同期完了: ${env.JOB_NAME} #${env.BUILD_NUMBER}\nプロジェクト: ${params.PROJECT}"
            // )
        }

        failure {
            echo 'Sync failed. Check the logs for details.'

            // 失敗時の通知
            // mail(
            //     to: 'admin@example.com',
            //     subject: "[FAILED] Redmine Sync - ${params.PROJECT}",
            //     body: "詳細: ${env.BUILD_URL}console"
            // )
        }
    }
}
```

### 6.2 Jenkinsfileのコミット

```bash
# Jenkinsfileをリポジトリに追加
git add Jenkinsfile
git commit -m "Add Jenkinsfile for CI/CD"
git push origin main
```

### 6.3 シンプルなJenkinsfile

最小構成のJenkinsfile例:

```groovy
pipeline {
    agent any

    environment {
        REDMINE_API_KEY = credentials('redmine-api-key')
    }

    stages {
        stage('Build & Sync') {
            steps {
                checkout scm
                sh './mvnw clean package -DskipTests'
                sh '''
                    java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar \
                        --sync \
                        --file=deploy/sample_test.csv \
                        --dry-run
                '''
            }
        }
    }
}
```

---

## 7. 定期実行の設定

### 7.1 Jenkinsfileでの設定

triggersブロックを追加:

```groovy
pipeline {
    agent any

    triggers {
        // 毎日午前9時に実行
        cron('0 9 * * *')
    }

    // ... 以降は通常通り
}
```

### 7.2 cron式の書き方

```
分 時 日 月 曜日
```

| フィールド | 値の範囲 |
|-----------|---------|
| 分 | 0-59 |
| 時 | 0-23 |
| 日 | 1-31 |
| 月 | 1-12 |
| 曜日 | 0-7（0と7は日曜日） |

### 7.3 cron式の例

```groovy
triggers {
    // 毎日午前9時
    cron('0 9 * * *')

    // 毎週月曜日の午前8時
    cron('0 8 * * 1')

    // 平日（月-金）の午前9時と午後6時
    cron('0 9,18 * * 1-5')

    // 毎月1日の午前0時
    cron('0 0 1 * *')

    // 15分ごと
    cron('H/15 * * * *')  // Hは負荷分散のためのハッシュ
}
```

### 7.4 ジョブ設定画面での設定

Jenkinsfileを使用しない場合、ジョブ設定画面で設定できます。

1. ジョブの設定画面を開く
2. 「ビルドトリガ」セクションで「定期的に実行」にチェック
3. スケジュールを入力（例: `0 9 * * *`）
4. 保存

### 7.5 SCMポーリングとの組み合わせ

コード変更時にも実行したい場合:

```groovy
triggers {
    // 定期実行
    cron('0 9 * * *')

    // SCMの変更を5分ごとにチェック
    pollSCM('H/5 * * * *')
}
```

---

## 8. トラブルシューティング

### 8.1 Jenkinsが起動しない

**症状:**

```
Job for jenkins.service failed because the control process exited with error code.
```

**対処:**

```bash
# ログを確認
sudo journalctl -xeu jenkins.service
sudo cat /var/log/jenkins/jenkins.log

# Javaバージョンを確認
java -version

# Java 17以上であることを確認
# 古い場合はJava 17をインストール
sudo apt install temurin-17-jdk

# Jenkinsを再起動
sudo systemctl restart jenkins
```

### 8.2 初期パスワードが見つからない

**症状:**

初期パスワードファイルが存在しない

**対処:**

```bash
# ファイルの存在確認
sudo ls -la /var/lib/jenkins/secrets/

# Jenkinsログから確認
sudo grep "initialAdminPassword" /var/log/jenkins/jenkins.log

# ファイルが存在しない場合は再インストール
sudo apt remove --purge jenkins
sudo apt install jenkins
```

### 8.3 プラグインのインストールに失敗する

**症状:**

```
Failed to download plugin
```

**対処:**

```bash
# プロキシ設定を確認
# 「Jenkinsの管理」 > 「Plugins」 > 「Advanced settings」

# 更新センターのURLを確認
# https://updates.jenkins.io/update-center.json

# プラグインを手動でダウンロードしてインストール
# 1. https://plugins.jenkins.io/ からダウンロード
# 2. 「Advanced settings」 > 「Deploy Plugin」でアップロード
```

### 8.4 パイプラインがPermission deniedで失敗

**症状:**

```
./mvnw: Permission denied
```

**対処:**

```bash
# mvnwの実行権限を確認（リポジトリ内）
chmod +x mvnw

# または、Jenkinsfile内で権限を付与
stage('Build') {
    steps {
        sh 'chmod +x ./mvnw'
        sh './mvnw clean package'
    }
}
```

### 8.5 Credentialが参照できない

**症状:**

```
ERROR: Could not find credentials entry with ID 'redmine-api-key'
```

**対処:**

1. Credentialが登録されているか確認
   - 「Jenkinsの管理」 > 「Credentials」

2. IDが正確か確認（大文字小文字に注意）

3. スコープを確認
   - 「Global」スコープであることを確認

### 8.6 メモリ不足でビルドが失敗

**症状:**

```
java.lang.OutOfMemoryError: Java heap space
```

**対処:**

```bash
# Jenkinsのメモリ設定を変更
sudo vi /etc/default/jenkins

# JAVA_ARGSを編集
JAVA_ARGS="-Djava.awt.headless=true -Xmx2g -Xms512m"

# Jenkinsを再起動
sudo systemctl restart jenkins
```

### 8.7 ワークスペースの権限問題

**症状:**

```
Permission denied: '/var/lib/jenkins/workspace/...'
```

**対処:**

```bash
# ワークスペースの所有者を確認
ls -la /var/lib/jenkins/workspace/

# 所有者をjenkinsユーザーに変更
sudo chown -R jenkins:jenkins /var/lib/jenkins/workspace/
```

### 8.8 CSVファイルがアップロードできない

**症状:**

ファイルパラメータでアップロードしたファイルが見つからない

**対処:**

1. File Parameterプラグインがインストールされているか確認

2. Jenkinsfileでのファイル参照方法を確認:
```groovy
// 正しい参照方法
sh "cat ${env.CSV_FILE}"

// 環境変数として参照
sh "cat $CSV_FILE"
```

3. ワークスペースにコピーして使用:
```groovy
sh "cp ${CSV_FILE} ${WORKSPACE}/input.csv"
sh "cat ${WORKSPACE}/input.csv"
```

### 8.9 ログが表示されない

**症状:**

ビルドログが途中で切れる

**対処:**

```groovy
// コンソール出力のバッファリングを無効化
options {
    ansiColor('xterm')
}

// または、出力を都度フラッシュ
sh 'java -jar app.jar 2>&1 | tee output.log'
```

### 8.10 定期実行が動かない

**症状:**

設定したスケジュールで実行されない

**対処:**

1. cron式の構文を確認
   - ジョブ設定画面で構文エラーが表示されていないか確認

2. タイムゾーンを確認:
```bash
# Jenkinsのタイムゾーン設定
# 「Jenkinsの管理」 > 「System」 > 「System Admin e-mail address」付近

# システムのタイムゾーン確認
date
timedatectl
```

3. ビルド履歴を確認
   - 「ビルド履歴」で過去の実行状況を確認

---

## 付録

### A. セキュリティ設定

#### CSRF保護の有効化

「Jenkinsの管理」 > 「Security」 > 「CSRF Protection」

#### ユーザー認証の設定

「Jenkinsの管理」 > 「Security」 > 「Security Realm」

推奨設定:
- Jenkins' own user database
- ユーザーにサインアップを許可しない

#### 権限の設定

「Jenkinsの管理」 > 「Security」 > 「Authorization」

推奨設定:
- Matrix-based security
- 管理者以外は最小限の権限

### B. バックアップ

```bash
# Jenkinsホームディレクトリをバックアップ
sudo tar -czvf jenkins_backup_$(date +%Y%m%d).tar.gz /var/lib/jenkins

# または、重要なディレクトリのみ
sudo tar -czvf jenkins_config_$(date +%Y%m%d).tar.gz \
  /var/lib/jenkins/config.xml \
  /var/lib/jenkins/credentials.xml \
  /var/lib/jenkins/jobs \
  /var/lib/jenkins/users \
  /var/lib/jenkins/secrets
```

### C. 参考リンク

- [Jenkins公式ドキュメント](https://www.jenkins.io/doc/)
- [Jenkins Pipeline Syntax](https://www.jenkins.io/doc/book/pipeline/syntax/)
- [Jenkins プラグイン一覧](https://plugins.jenkins.io/)
- [Pipeline Steps Reference](https://www.jenkins.io/doc/pipeline/steps/)

---

最終更新日: 2026-01-16
