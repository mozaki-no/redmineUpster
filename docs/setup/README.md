# セットアップガイド

本ディレクトリには、redmineUpsterを運用するために必要な各種ソフトウェアのセットアップ手順をまとめています。

---

## 目次

| ドキュメント | 概要 |
|-------------|------|
| [Redmine + Apache + Passenger](./redmine-apache-passenger.md) | Redmine本体のセットアップ手順。Apache + Passengerでの運用環境構築 |
| [Java 17](./java17.md) | redmineUpster実行に必要なJava 17のインストール方法 |
| [Jenkins](./jenkins.md) | CI/CDツールJenkinsのセットアップとredmineUpsterの定期実行設定 |

---

## 構成図

```
                                    +-------------------+
                                    |   CSV/Excel       |
                                    |   ファイル         |
                                    +--------+----------+
                                             |
                                             v
+-------------------+              +-------------------+              +-------------------+
|                   |              |                   |              |                   |
|     Jenkins       +------------->+   redmineUpster   +------------->+     Redmine       |
|                   |   定期実行    |   (Java 17)       |   REST API   |  (Apache+Pass.)  |
+-------------------+              +--------+----------+              +-------------------+
```

---

## セットアップの順序

以下の順序でセットアップを進めることを推奨します。

### 1. Redmineサーバーのセットアップ

同期先となるRedmineサーバーが必要です。既存のRedmineがある場合はこの手順はスキップできます。

- [Redmine + Apache + Passenger セットアップ](./redmine-apache-passenger.md)

### 2. Java 17のセットアップ

redmineUpsterはJava 17で動作します（データベース・Dockerは不要です）。
Windowsで使うだけなら、Java同梱の配布版（exe）を使えば Java のインストールも不要です（[利用者ガイド](../USER_GUIDE.md)）。

- [Java 17 セットアップ](./java17.md)

### 3. Jenkinsのセットアップ（オプション）

定期実行やCI/CD環境が必要な場合に設定します。

- [Jenkins セットアップ](./jenkins.md)

---

## クイックスタート

すべてのセットアップが完了している場合、以下のコマンドでredmineUpsterを実行できます。

```bash
# 1. リポジトリのクローン
git clone <repository-url>
cd redmineUpster

# 2. 環境変数の設定
export REDMINE_API_KEY="your-api-key"

# 3. ビルド
./mvnw clean package

# 4. ドライラン実行（テスト）
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

## 関連ドキュメント

- [README.md](../../README.md) - プロジェクト概要
- [DEPLOY.md](../DEPLOY.md) - デプロイ手順の詳細
- [CLAUDE.md](../../CLAUDE.md) - 開発者向け情報

---

## サポート

問題が発生した場合は、各ドキュメントの「トラブルシューティング」セクションを参照してください。
それでも解決しない場合は、Issueを作成してください。
