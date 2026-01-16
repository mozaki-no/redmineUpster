# Redmine + Apache + Passenger セットアップガイド

本ドキュメントでは、Ubuntu Server上にRedmineをApache + Passengerで構築する手順を解説します。

---

## 目次

1. [前提条件](#1-前提条件)
2. [必要パッケージのインストール](#2-必要パッケージのインストール)
3. [Ruby/rbenvのインストール](#3-rubyrbenvのインストール)
4. [PostgreSQLの設定](#4-postgresqlの設定)
5. [Redmineのダウンロードと設定](#5-redmineのダウンロードと設定)
6. [Passengerのインストール](#6-passengerのインストール)
7. [Apacheの設定](#7-apacheの設定)
8. [起動と動作確認](#8-起動と動作確認)
9. [REST APIの有効化](#9-rest-apiの有効化)
10. [トラブルシューティング](#10-トラブルシューティング)

---

## 1. 前提条件

### 1.1 対象OS

- Ubuntu 22.04 LTS（推奨）
- Ubuntu 20.04 LTS
- CentOS Stream 9 / Rocky Linux 9（別途注記あり）

### 1.2 必要スペック

| 項目 | 最小要件 | 推奨 |
|------|---------|------|
| CPU | 1コア | 2コア以上 |
| メモリ | 2GB | 4GB以上 |
| ディスク | 10GB | 20GB以上 |

### 1.3 必要な権限

- root権限またはsudo権限

### 1.4 バージョン情報

本ガイドは以下のバージョンを対象としています。

| ソフトウェア | バージョン |
|-------------|-----------|
| Redmine | 5.1.x |
| Ruby | 3.2.x |
| PostgreSQL | 15以上 |
| Apache | 2.4.x |
| Passenger | 6.0.x |

---

## 2. 必要パッケージのインストール

### 2.1 Ubuntu の場合

```bash
# システムの更新
sudo apt update && sudo apt upgrade -y

# 必要なパッケージをインストール
sudo apt install -y \
  build-essential \
  curl \
  git \
  libssl-dev \
  libreadline-dev \
  zlib1g-dev \
  libpq-dev \
  libxml2-dev \
  libxslt1-dev \
  libyaml-dev \
  libffi-dev \
  imagemagick \
  libmagickwand-dev \
  apache2 \
  apache2-dev \
  postgresql \
  postgresql-contrib
```

### 2.2 CentOS / Rocky Linux の場合

```bash
# システムの更新
sudo dnf update -y

# EPELリポジトリの有効化
sudo dnf install -y epel-release

# 開発ツールグループのインストール
sudo dnf groupinstall -y "Development Tools"

# 必要なパッケージをインストール
sudo dnf install -y \
  curl \
  git \
  openssl-devel \
  readline-devel \
  zlib-devel \
  libpq-devel \
  libxml2-devel \
  libxslt-devel \
  libyaml-devel \
  libffi-devel \
  ImageMagick \
  ImageMagick-devel \
  httpd \
  httpd-devel \
  postgresql-server \
  postgresql-contrib
```

---

## 3. Ruby/rbenvのインストール

### 3.1 rbenvのインストール

```bash
# rbenvをインストール
git clone https://github.com/rbenv/rbenv.git ~/.rbenv

# ruby-buildプラグインをインストール
git clone https://github.com/rbenv/ruby-build.git ~/.rbenv/plugins/ruby-build

# PATHの設定（bashの場合）
echo 'export PATH="$HOME/.rbenv/bin:$PATH"' >> ~/.bashrc
echo 'eval "$(rbenv init -)"' >> ~/.bashrc
source ~/.bashrc

# インストール確認
rbenv --version
```

### 3.2 Rubyのインストール

```bash
# 利用可能なバージョンの確認
rbenv install --list

# Ruby 3.2.x をインストール（最新のパッチバージョンを確認してください）
rbenv install 3.2.2

# グローバルに設定
rbenv global 3.2.2

# バージョン確認
ruby --version
# 出力例: ruby 3.2.2 (2023-03-30 revision e51014f9c0) [x86_64-linux]
```

### 3.3 Bundlerのインストール

```bash
# Bundlerをインストール
gem install bundler

# rehash（新しいコマンドを認識させる）
rbenv rehash

# バージョン確認
bundler --version
```

---

## 4. PostgreSQLの設定

### 4.1 PostgreSQLの初期化と起動

#### Ubuntu の場合

```bash
# サービスの起動と自動起動設定
sudo systemctl start postgresql
sudo systemctl enable postgresql
```

#### CentOS / Rocky Linux の場合

```bash
# データベースの初期化
sudo postgresql-setup --initdb

# サービスの起動と自動起動設定
sudo systemctl start postgresql
sudo systemctl enable postgresql
```

### 4.2 Redmine用データベースの作成

```bash
# postgresユーザーでpsqlに接続
sudo -u postgres psql
```

psqlプロンプトで以下を実行:

```sql
-- Redmine用ユーザーの作成
CREATE USER redmine WITH PASSWORD 'your_secure_password';

-- Redmine用データベースの作成
CREATE DATABASE redmine WITH ENCODING 'UTF8' OWNER redmine;

-- 権限の付与
GRANT ALL PRIVILEGES ON DATABASE redmine TO redmine;

-- 終了
\q
```

### 4.3 PostgreSQL認証設定

`pg_hba.conf`を編集して、パスワード認証を有効にします。

```bash
# ファイルの場所を確認
sudo -u postgres psql -c "SHOW hba_file;"

# Ubuntu の場合（通常 /etc/postgresql/15/main/pg_hba.conf）
sudo vi /etc/postgresql/15/main/pg_hba.conf

# CentOS の場合（通常 /var/lib/pgsql/data/pg_hba.conf）
sudo vi /var/lib/pgsql/data/pg_hba.conf
```

以下の行を追加または変更:

```
# IPv4 local connections:
host    redmine         redmine         127.0.0.1/32            md5
```

設定を反映:

```bash
sudo systemctl restart postgresql
```

---

## 5. Redmineのダウンロードと設定

### 5.1 Redmineのダウンロード

```bash
# インストール先ディレクトリの作成
sudo mkdir -p /var/www
cd /var/www

# Redmineのダウンロード（バージョンは適宜変更してください）
sudo wget https://www.redmine.org/releases/redmine-5.1.2.tar.gz

# 展開
sudo tar xzf redmine-5.1.2.tar.gz

# シンボリックリンクの作成（バージョンアップを容易にするため）
sudo ln -s redmine-5.1.2 redmine

# 所有者の変更
sudo chown -R $USER:$USER /var/www/redmine-5.1.2
```

### 5.2 データベース設定

```bash
cd /var/www/redmine

# 設定ファイルの作成
cp config/database.yml.example config/database.yml
```

`config/database.yml`を編集:

```yaml
production:
  adapter: postgresql
  database: redmine
  host: localhost
  username: redmine
  password: "your_secure_password"
  encoding: utf8
```

### 5.3 Bundlerでのgemインストール

```bash
cd /var/www/redmine

# bundle install（本番環境向け）
bundle config set --local without 'development test'
bundle install
```

### 5.4 セッション秘密鍵の生成

```bash
bundle exec rake generate_secret_token
```

### 5.5 データベースのマイグレーション

```bash
RAILS_ENV=production bundle exec rake db:migrate
```

### 5.6 デフォルトデータの投入

```bash
RAILS_ENV=production bundle exec rake redmine:load_default_data

# 言語選択プロンプトが表示されたら「ja」を入力
```

### 5.7 ディレクトリのパーミッション設定

```bash
# 必要なディレクトリの作成
mkdir -p tmp tmp/pdf public/plugin_assets

# パーミッション設定
chmod -R 755 files log tmp public/plugin_assets

# 本番環境ではwww-dataユーザーに所有権を変更
sudo chown -R www-data:www-data /var/www/redmine-5.1.2
```

---

## 6. Passengerのインストール

### 6.1 Passengerのインストール

```bash
# Passenger gemをインストール
gem install passenger

# rehash
rbenv rehash
```

### 6.2 Apache用Passengerモジュールのコンパイル

```bash
# Passengerのインストールコマンドを実行
passenger-install-apache2-module

# プロンプトに従って進める
# - Rubyを選択（Enter）
# - コンパイルが完了するまで待つ
```

コンパイルが完了すると、Apacheの設定に追加すべき行が表示されます。この内容をメモしておいてください。

**出力例:**

```
LoadModule passenger_module /home/username/.rbenv/versions/3.2.2/lib/ruby/gems/3.2.0/gems/passenger-6.0.18/buildout/apache2/mod_passenger.so
<IfModule mod_passenger.c>
  PassengerRoot /home/username/.rbenv/versions/3.2.2/lib/ruby/gems/3.2.0/gems/passenger-6.0.18
  PassengerDefaultRuby /home/username/.rbenv/versions/3.2.2/bin/ruby
</IfModule>
```

---

## 7. Apacheの設定

### 7.1 Passengerモジュールの設定

Passenger設定ファイルを作成します。

```bash
sudo vi /etc/apache2/mods-available/passenger.load
```

以下の内容を追加（パスは環境に合わせて変更）:

```apache
LoadModule passenger_module /home/username/.rbenv/versions/3.2.2/lib/ruby/gems/3.2.0/gems/passenger-6.0.18/buildout/apache2/mod_passenger.so
```

```bash
sudo vi /etc/apache2/mods-available/passenger.conf
```

以下の内容を追加:

```apache
<IfModule mod_passenger.c>
  PassengerRoot /home/username/.rbenv/versions/3.2.2/lib/ruby/gems/3.2.0/gems/passenger-6.0.18
  PassengerDefaultRuby /home/username/.rbenv/versions/3.2.2/bin/ruby
</IfModule>
```

モジュールを有効化:

```bash
sudo a2enmod passenger
```

### 7.2 VirtualHost設定

VirtualHost設定ファイルを作成します。

```bash
sudo vi /etc/apache2/sites-available/redmine.conf
```

以下の内容を追加:

```apache
<VirtualHost *:80>
    ServerName redmine.example.com
    DocumentRoot /var/www/redmine/public

    <Directory /var/www/redmine/public>
        AllowOverride all
        Options -MultiViews
        Require all granted
    </Directory>

    # ログ設定
    ErrorLog ${APACHE_LOG_DIR}/redmine_error.log
    CustomLog ${APACHE_LOG_DIR}/redmine_access.log combined

    # Passenger設定
    PassengerAppRoot /var/www/redmine
    PassengerRuby /home/username/.rbenv/versions/3.2.2/bin/ruby

    # パフォーマンスチューニング
    PassengerMinInstances 2
    PassengerMaxPoolSize 6
</VirtualHost>
```

### 7.3 HTTPS設定（推奨）

本番環境ではHTTPSを使用することを強く推奨します。

```bash
# SSL関連モジュールの有効化
sudo a2enmod ssl
sudo a2enmod rewrite

# Let's Encryptを使用する場合
sudo apt install certbot python3-certbot-apache
sudo certbot --apache -d redmine.example.com
```

HTTPS用VirtualHost設定例:

```apache
<VirtualHost *:443>
    ServerName redmine.example.com
    DocumentRoot /var/www/redmine/public

    SSLEngine on
    SSLCertificateFile /etc/letsencrypt/live/redmine.example.com/fullchain.pem
    SSLCertificateKeyFile /etc/letsencrypt/live/redmine.example.com/privkey.pem

    <Directory /var/www/redmine/public>
        AllowOverride all
        Options -MultiViews
        Require all granted
    </Directory>

    ErrorLog ${APACHE_LOG_DIR}/redmine_ssl_error.log
    CustomLog ${APACHE_LOG_DIR}/redmine_ssl_access.log combined

    PassengerAppRoot /var/www/redmine
    PassengerRuby /home/username/.rbenv/versions/3.2.2/bin/ruby
    PassengerMinInstances 2
    PassengerMaxPoolSize 6
</VirtualHost>

# HTTPからHTTPSへリダイレクト
<VirtualHost *:80>
    ServerName redmine.example.com
    Redirect permanent / https://redmine.example.com/
</VirtualHost>
```

### 7.4 設定の有効化と確認

```bash
# サイトを有効化
sudo a2ensite redmine.conf

# デフォルトサイトを無効化（必要に応じて）
sudo a2dissite 000-default.conf

# 設定ファイルの構文チェック
sudo apache2ctl configtest

# Apacheの再起動
sudo systemctl restart apache2
```

---

## 8. 起動と動作確認

### 8.1 Apacheの状態確認

```bash
# ステータス確認
sudo systemctl status apache2

# ポートのリッスン状態確認
sudo ss -tlnp | grep apache
```

### 8.2 ブラウザでアクセス

ブラウザで `http://redmine.example.com` にアクセスし、Redmineのログイン画面が表示されることを確認します。

### 8.3 初期ログイン

- ユーザー名: `admin`
- パスワード: `admin`

**重要:** 初回ログイン後、必ずパスワードを変更してください。

### 8.4 動作確認のチェックリスト

- [ ] ログインできる
- [ ] プロジェクトを作成できる
- [ ] チケットを作成できる
- [ ] ファイルをアップロードできる
- [ ] メール通知が動作する（SMTP設定済みの場合）

---

## 9. REST APIの有効化

redmineUpsterはRedmineのREST APIを使用してチケットを操作します。APIを有効化する必要があります。

### 9.1 管理画面でAPIを有効化

1. Redmineに管理者としてログイン
2. 「管理」 > 「設定」 > 「API」タブを開く
3. 以下のオプションを有効化:
   - [x] RESTによるWebサービスを有効にする
   - [x] JSONPを有効にする（オプション）

4. 「保存」をクリック

### 9.2 APIキーの取得

各ユーザーは個別のAPIキーを持ちます。

1. 右上のユーザー名をクリック
2. 「個人設定」を選択
3. 右サイドバーの「APIアクセスキー」セクションで「表示」をクリック
4. 表示されたAPIキーをコピー

**注意:** APIキーはパスワードと同等の機密情報です。適切に管理してください。

### 9.3 APIアクセスのテスト

APIが正常に動作することを確認します。

```bash
# プロジェクト一覧の取得
curl -H "X-Redmine-API-Key: YOUR_API_KEY" \
     https://redmine.example.com/projects.json

# 特定プロジェクトの情報取得
curl -H "X-Redmine-API-Key: YOUR_API_KEY" \
     https://redmine.example.com/projects/your-project-id.json

# チケット一覧の取得
curl -H "X-Redmine-API-Key: YOUR_API_KEY" \
     "https://redmine.example.com/issues.json?project_id=your-project-id&limit=5"
```

期待される応答:

```json
{
  "issues": [
    {
      "id": 1,
      "project": {"id": 1, "name": "Sample Project"},
      "subject": "First Issue",
      ...
    }
  ],
  "total_count": 1,
  "offset": 0,
  "limit": 5
}
```

### 9.4 APIユーザーの権限設定

redmineUpster用のAPIユーザーには、以下の権限が必要です。

1. 「管理」 > 「ロールと権限」
2. 使用するロールを選択
3. 以下の権限を付与:
   - [x] チケットの閲覧
   - [x] チケットの追加
   - [x] チケットの編集
   - [x] ステータスの変更（必要な場合）
   - [x] 担当者の変更（必要な場合）

---

## 10. トラブルシューティング

### 10.1 Apacheが起動しない

**症状:**

```
Job for apache2.service failed because the control process exited with error code.
```

**対処:**

```bash
# エラーログを確認
sudo journalctl -xeu apache2.service
sudo cat /var/log/apache2/error.log

# 設定ファイルの構文チェック
sudo apache2ctl configtest
```

### 10.2 Passengerがロードされない

**症状:**

```
Invalid command 'PassengerRoot'
```

**対処:**

```bash
# モジュールが有効か確認
apache2ctl -M | grep passenger

# モジュールファイルが存在するか確認
ls -la /home/username/.rbenv/versions/3.2.2/lib/ruby/gems/3.2.0/gems/passenger-*/buildout/apache2/

# パスが正しいか passenger.load を確認
cat /etc/apache2/mods-available/passenger.load
```

### 10.3 Redmineが500エラーを返す

**症状:**

ブラウザで「Internal Server Error」が表示される

**対処:**

```bash
# Redmineのログを確認
tail -f /var/www/redmine/log/production.log

# よくある原因
# 1. データベース接続エラー → database.yml を確認
# 2. パーミッションエラー → ディレクトリ所有者を確認
sudo chown -R www-data:www-data /var/www/redmine

# 3. secret_token未生成
cd /var/www/redmine
RAILS_ENV=production bundle exec rake generate_secret_token
```

### 10.4 データベース接続エラー

**症状:**

```
PG::ConnectionBad: could not connect to server
```

**対処:**

```bash
# PostgreSQLが起動しているか確認
sudo systemctl status postgresql

# データベースに接続できるか確認
psql -h localhost -U redmine -d redmine

# pg_hba.conf の設定を確認
sudo cat /etc/postgresql/15/main/pg_hba.conf | grep redmine
```

### 10.5 APIアクセスが403を返す

**症状:**

```
{"errors":["You are not authorized to access this page."]}
```

**対処:**

1. APIが有効化されているか確認（管理 > 設定 > API）
2. APIキーが正しいか確認
3. ユーザーがプロジェクトのメンバーか確認
4. ユーザーのロールに必要な権限があるか確認

### 10.6 文字化け

**症状:**

日本語が正しく表示されない

**対処:**

```bash
# データベースのエンコーディングを確認
sudo -u postgres psql -c "SELECT datname, pg_encoding_to_char(encoding) FROM pg_database;"

# database.ymlでエンコーディングを指定
# encoding: utf8

# Apacheの設定に追加
# AddDefaultCharset UTF-8
```

### 10.7 メモリ不足

**症状:**

Passengerがメモリ不足でプロセスをkillする

**対処:**

VirtualHost設定でPassengerのメモリ制限を調整:

```apache
PassengerMaxPoolSize 4
PassengerMinInstances 1
PassengerMaxInstancesPerApp 2
```

または、スワップを追加:

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

---

## 付録

### A. 設定ファイル一覧

| ファイル | 説明 |
|---------|------|
| `/var/www/redmine/config/database.yml` | データベース接続設定 |
| `/var/www/redmine/config/configuration.yml` | Redmine全般設定 |
| `/etc/apache2/sites-available/redmine.conf` | ApacheのVirtualHost設定 |
| `/etc/apache2/mods-available/passenger.*` | Passengerモジュール設定 |

### B. 有用なコマンド

```bash
# Apacheの再起動
sudo systemctl restart apache2

# Passengerのステータス確認
passenger-status

# Passengerのアプリケーション再起動
touch /var/www/redmine/tmp/restart.txt

# Redmineのキャッシュクリア
cd /var/www/redmine
RAILS_ENV=production bundle exec rake tmp:cache:clear
```

### C. 参考リンク

- [Redmine公式インストールガイド](https://www.redmine.org/projects/redmine/wiki/RedmineInstall)
- [Passenger公式ドキュメント](https://www.phusionpassenger.com/docs/)
- [Ruby公式サイト](https://www.ruby-lang.org/ja/)

---

最終更新日: 2026-01-16
