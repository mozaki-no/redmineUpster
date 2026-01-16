# Java 17 セットアップガイド

本ドキュメントでは、redmineUpsterを実行するために必要なJava 17のインストール方法を解説します。

---

## 目次

1. [Java 17 の概要](#1-java-17-の概要)
2. [Ubuntu でのインストール](#2-ubuntu-でのインストール)
3. [CentOS / Rocky Linux でのインストール](#3-centos--rocky-linux-でのインストール)
4. [SDKMANを使ったインストール](#4-sdkmanを使ったインストール)
5. [環境変数の設定](#5-環境変数の設定)
6. [バージョン確認方法](#6-バージョン確認方法)
7. [複数バージョンの切り替え](#7-複数バージョンの切り替え)
8. [トラブルシューティング](#8-トラブルシューティング)

---

## 1. Java 17 の概要

### 1.1 なぜJava 17が必要か

redmineUpsterはSpring Boot 3.5を使用しており、Java 17以上が必須要件です。

- Spring Boot 3.x はJava 17をベースラインとしています
- Java 17はLTS（Long Term Support）バージョンで、長期サポートが保証されています

### 1.2 推奨するJavaディストリビューション

| ディストリビューション | 特徴 | 推奨用途 |
|----------------------|------|---------|
| Eclipse Temurin (Adoptium) | オープンソース、広く使われている | 一般用途（推奨） |
| Amazon Corretto | AWS環境に最適化 | AWS環境での運用 |
| Oracle JDK | Oracle公式、商用サポートあり | 商用サポートが必要な場合 |
| OpenJDK | 純正オープンソース | 開発環境 |

### 1.3 バージョン要件

| 項目 | 要件 |
|------|------|
| 最小バージョン | Java 17 |
| 推奨バージョン | Java 17 LTS（最新パッチ） |
| 対応バージョン | Java 17, 21 |

---

## 2. Ubuntu でのインストール

### 2.1 APTパッケージマネージャーを使用

#### Eclipse Temurin（推奨）

```bash
# Adoptiumのリポジトリを追加
wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo apt-key add -
echo "deb https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list

# パッケージリストを更新
sudo apt update

# Java 17をインストール
sudo apt install -y temurin-17-jdk

# バージョン確認
java -version
```

#### OpenJDK

```bash
# パッケージリストを更新
sudo apt update

# OpenJDK 17をインストール
sudo apt install -y openjdk-17-jdk

# バージョン確認
java -version
```

### 2.2 手動インストール

Adoptiumのウェブサイトから直接ダウンロードしてインストールする方法です。

```bash
# ダウンロードディレクトリに移動
cd /tmp

# Temurin 17をダウンロード（バージョンは適宜更新してください）
wget https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.10_7.tar.gz

# 展開
sudo mkdir -p /usr/lib/jvm
sudo tar -xzf OpenJDK17U-jdk_x64_linux_hotspot_17.0.10_7.tar.gz -C /usr/lib/jvm

# シンボリックリンクの作成
sudo ln -s /usr/lib/jvm/jdk-17.0.10+7 /usr/lib/jvm/java-17-temurin

# update-alternativesで登録
sudo update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-17-temurin/bin/java 1
sudo update-alternatives --install /usr/bin/javac javac /usr/lib/jvm/java-17-temurin/bin/javac 1

# バージョン確認
java -version
```

---

## 3. CentOS / Rocky Linux でのインストール

### 3.1 DNFパッケージマネージャーを使用

#### Eclipse Temurin（推奨）

```bash
# Adoptiumのリポジトリを追加
cat << 'EOF' | sudo tee /etc/yum.repos.d/adoptium.repo
[Adoptium]
name=Adoptium
baseurl=https://packages.adoptium.net/artifactory/rpm/centos/$releasever/$basearch
enabled=1
gpgcheck=1
gpgkey=https://packages.adoptium.net/artifactory/api/gpg/key/public
EOF

# Java 17をインストール
sudo dnf install -y temurin-17-jdk

# バージョン確認
java -version
```

#### OpenJDK

```bash
# OpenJDK 17をインストール
sudo dnf install -y java-17-openjdk java-17-openjdk-devel

# バージョン確認
java -version
```

### 3.2 Amazon Corretto（AWS環境向け）

```bash
# Correttoリポジトリを追加
sudo rpm --import https://yum.corretto.aws/corretto.key
sudo curl -Lo /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo

# Amazon Corretto 17をインストール
sudo dnf install -y java-17-amazon-corretto-devel

# バージョン確認
java -version
```

---

## 4. SDKMANを使ったインストール

SDKMANは、複数のJavaバージョンを簡単に管理できるツールです。開発環境では特に便利です。

### 4.1 SDKMANのインストール

```bash
# SDKMANをインストール
curl -s "https://get.sdkman.io" | bash

# 設定を読み込み
source "$HOME/.sdkman/bin/sdkman-init.sh"

# インストール確認
sdk version
```

### 4.2 Java 17のインストール

```bash
# 利用可能なJavaバージョンを確認
sdk list java

# 出力例:
# ================================================================================
# Available Java Versions for Linux 64bit
# ================================================================================
#  Vendor        | Use | Version      | Dist    | Status     | Identifier
# --------------------------------------------------------------------------------
#  Temurin       |     | 21.0.2       | tem     |            | 21.0.2-tem
#  Temurin       |     | 17.0.10      | tem     |            | 17.0.10-tem
#  Amazon        |     | 17.0.10      | amzn    |            | 17.0.10-amzn
#  ...

# Temurin 17をインストール
sdk install java 17.0.10-tem

# デフォルトに設定
sdk default java 17.0.10-tem

# バージョン確認
java -version
```

### 4.3 SDKMANの便利なコマンド

```bash
# 現在使用中のJavaバージョンを確認
sdk current java

# インストール済みのJavaバージョンを一覧表示
sdk list java | grep installed

# 特定のシェルセッションでのみバージョンを変更
sdk use java 17.0.10-tem

# Javaをアンインストール
sdk uninstall java 17.0.10-tem
```

---

## 5. 環境変数の設定

### 5.1 JAVA_HOME の設定

#### bashの場合

`~/.bashrc` または `~/.bash_profile` に追加:

```bash
# APTでインストールした場合（Ubuntu）
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
# または
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# DNFでインストールした場合（CentOS/Rocky Linux）
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk
# または
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk

# PATHに追加
export PATH=$JAVA_HOME/bin:$PATH
```

設定を反映:

```bash
source ~/.bashrc
```

#### zshの場合

`~/.zshrc` に追加:

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

設定を反映:

```bash
source ~/.zshrc
```

### 5.2 システム全体の設定

全ユーザーに適用する場合は `/etc/environment` を編集:

```bash
sudo vi /etc/environment
```

以下を追加:

```
JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
```

### 5.3 SDKMANを使用している場合

SDKMANは自動的にJAVA_HOMEを管理するため、手動設定は不要です。

```bash
# 自動設定されていることを確認
echo $JAVA_HOME
# 出力例: /home/username/.sdkman/candidates/java/current
```

### 5.4 Jenkinsでの設定

Jenkinsfileで環境変数を設定:

```groovy
pipeline {
    environment {
        JAVA_HOME = '/usr/lib/jvm/temurin-17-jdk-amd64'
        PATH = "${JAVA_HOME}/bin:${PATH}"
    }
    // ...
}
```

または、Jenkinsの「Global Tool Configuration」でJDKを設定し、ツールとして使用:

```groovy
pipeline {
    tools {
        jdk 'JDK17'  // Jenkinsで設定した名前
    }
    // ...
}
```

---

## 6. バージョン確認方法

### 6.1 基本的な確認コマンド

```bash
# Javaバージョンを確認
java -version

# 出力例:
# openjdk version "17.0.10" 2024-01-16
# OpenJDK Runtime Environment Temurin-17.0.10+7 (build 17.0.10+7)
# OpenJDK 64-Bit Server VM Temurin-17.0.10+7 (build 17.0.10+7, mixed mode, sharing)

# Javaコンパイラのバージョンを確認
javac -version

# 出力例:
# javac 17.0.10

# JAVA_HOMEを確認
echo $JAVA_HOME

# Javaのインストールパスを確認
which java
```

### 6.2 詳細情報の確認

```bash
# Java実行環境の詳細情報
java -XshowSettings:all -version 2>&1 | head -50

# システムプロパティの確認
java -XshowSettings:properties -version 2>&1 | grep -E "(java.home|java.version|java.vendor)"
```

### 6.3 redmineUpsterでの確認

```bash
# アプリケーション起動時にJavaバージョンがログに出力される
java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar --version

# または起動ログで確認
java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar --sync --file=test.csv --dry-run 2>&1 | head -20
```

---

## 7. 複数バージョンの切り替え

### 7.1 update-alternatives を使用（Ubuntu/Debian）

```bash
# インストール済みのJavaバージョンを確認
sudo update-alternatives --list java

# 使用するJavaバージョンを選択
sudo update-alternatives --config java

# 出力例:
# There are 2 choices for the alternative java (providing /usr/bin/java).
#
#   Selection    Path                                         Priority   Status
# ------------------------------------------------------------
# * 0            /usr/lib/jvm/temurin-17-jdk-amd64/bin/java    1         auto mode
#   1            /usr/lib/jvm/java-11-openjdk-amd64/bin/java   1111      manual mode
#   2            /usr/lib/jvm/temurin-17-jdk-amd64/bin/java    1         manual mode
#
# Press <enter> to keep the current choice[*], or type selection number:

# javacも同様に切り替え
sudo update-alternatives --config javac
```

### 7.2 alternatives を使用（CentOS/Rocky Linux）

```bash
# インストール済みのJavaバージョンを確認
alternatives --list | grep java

# 使用するJavaバージョンを選択
sudo alternatives --config java
```

### 7.3 SDKMANを使用

```bash
# 利用可能なバージョンを確認
sdk list java | grep installed

# 現在のセッションでバージョンを切り替え
sdk use java 17.0.10-tem

# デフォルトバージョンを変更
sdk default java 17.0.10-tem

# プロジェクト固有のバージョン設定（.sdkmanrcファイル）
echo "java=17.0.10-tem" > .sdkmanrc

# .sdkmanrcを自動読み込みする設定
sdk config sdkman_auto_env=true
```

### 7.4 スクリプトでの切り替え

特定のJavaバージョンを使用するラッパースクリプト:

```bash
#!/bin/bash
# run-with-java17.sh

export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

echo "Using Java: $(java -version 2>&1 | head -1)"
exec "$@"
```

使用例:

```bash
./run-with-java17.sh java -jar target/redmineUpster-0.0.1-SNAPSHOT.jar --sync --file=test.csv
```

---

## 8. トラブルシューティング

### 8.1 Javaが見つからない

**症状:**

```
bash: java: command not found
```

**対処:**

```bash
# Javaがインストールされているか確認
dpkg -l | grep -i java      # Ubuntu
rpm -qa | grep -i java      # CentOS

# PATHを確認
echo $PATH

# Javaの場所を検索
find /usr -name "java" -type f 2>/dev/null

# PATHに追加（一時的）
export PATH=/usr/lib/jvm/temurin-17-jdk-amd64/bin:$PATH
```

### 8.2 JAVA_HOMEが設定されていない

**症状:**

```
Error: JAVA_HOME is not defined correctly.
```

**対処:**

```bash
# JAVA_HOMEを確認
echo $JAVA_HOME

# Javaのインストール場所を確認
java -XshowSettings:properties -version 2>&1 | grep java.home

# JAVA_HOMEを設定
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64

# ~/.bashrcに永続化
echo 'export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64' >> ~/.bashrc
source ~/.bashrc
```

### 8.3 バージョンが古い

**症状:**

```
Error: LinkageError occurred while loading main class mozaki.redmineUpster.RedmineUpsterApplication
        java.lang.UnsupportedClassVersionError: mozaki/redmineUpster/RedmineUpsterApplication
        has been compiled by a more recent version of the Java Runtime (class file version 61.0),
        this version of the Java Runtime only recognizes class file versions up to 55.0
```

このエラーは、Java 11（class file version 55）でJava 17用（class file version 61）のアプリケーションを実行しようとした場合に発生します。

**対処:**

```bash
# 現在のJavaバージョンを確認
java -version

# Java 17をインストール
sudo apt install temurin-17-jdk

# デフォルトをJava 17に変更
sudo update-alternatives --config java
# Java 17を選択

# 確認
java -version
```

### 8.4 複数のJavaが競合する

**症状:**

`which java`と`java -version`の結果が一致しない

**対処:**

```bash
# 全てのjavaコマンドの場所を確認
type -a java

# update-alternativesの優先順位を確認
sudo update-alternatives --display java

# 不要なバージョンを削除
sudo update-alternatives --remove java /usr/lib/jvm/java-11-openjdk-amd64/bin/java

# または、絶対パスで実行
/usr/lib/jvm/temurin-17-jdk-amd64/bin/java -version
```

### 8.5 Permission denied

**症状:**

```
bash: /usr/lib/jvm/temurin-17-jdk-amd64/bin/java: Permission denied
```

**対処:**

```bash
# 実行権限を確認
ls -la /usr/lib/jvm/temurin-17-jdk-amd64/bin/java

# 実行権限を付与
sudo chmod +x /usr/lib/jvm/temurin-17-jdk-amd64/bin/java
```

### 8.6 メモリ不足

**症状:**

```
Error occurred during initialization of VM
Could not reserve enough space for object heap
```

**対処:**

```bash
# 使用可能なメモリを確認
free -h

# ヒープサイズを明示的に指定
java -Xms256m -Xmx512m -jar target/redmineUpster-0.0.1-SNAPSHOT.jar --sync --file=test.csv
```

### 8.7 証明書エラー（HTTPS接続）

**症状:**

```
PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException
```

**対処:**

```bash
# 証明書ストアを確認
keytool -list -cacerts

# 自己署名証明書を追加する場合
sudo keytool -import -trustcacerts -keystore $JAVA_HOME/lib/security/cacerts \
    -storepass changeit -noprompt -alias myca -file /path/to/certificate.crt
```

---

## 付録

### A. バージョン対応表

| Class File Version | Java Version |
|-------------------|--------------|
| 55 | Java 11 |
| 61 | Java 17 |
| 65 | Java 21 |

### B. 有用なJVMオプション

```bash
# メモリ設定
java -Xms512m -Xmx1024m -jar app.jar

# GCログ出力
java -Xlog:gc*:file=gc.log -jar app.jar

# ヒープダンプ（OutOfMemoryError時）
java -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -jar app.jar

# リモートデバッグ
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar app.jar
```

### C. 参考リンク

- [Eclipse Temurin ダウンロード](https://adoptium.net/)
- [Amazon Corretto ダウンロード](https://aws.amazon.com/jp/corretto/)
- [SDKMAN 公式サイト](https://sdkman.io/)
- [Oracle JDK ダウンロード](https://www.oracle.com/java/technologies/downloads/)

---

最終更新日: 2026-01-16
