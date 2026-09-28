#!/usr/bin/env bash
# Linux 用の app-image（Java 同梱）を作成します。Windows 版の動作確認用（配布の本命は package-windows.ps1）。
# 使い方: bash packaging/package-linux.sh   （JDK 17 以上の jpackage が必要）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${ROOT}"
APP_NAME="redmineUpster"
APP_VERSION="${APP_VERSION:-1.0.0}"
MODULES="$(tr -d '[:space:]' < packaging/modules.txt)"
OUT="${ROOT}/target/package"

if [[ "${SKIP_BUILD:-false}" != "true" ]]; then
  mvn -B -q package -DskipTests
fi
JAR="$(ls -t target/${APP_NAME}-*.jar | grep -v '\.original$' | head -n 1)"

rm -rf "${OUT}"
mkdir -p "${OUT}/input"
cp "${JAR}" "${OUT}/input/${APP_NAME}.jar"

jpackage --type app-image \
  --name "${APP_NAME}" \
  --app-version "${APP_VERSION}" \
  --input "${OUT}/input" \
  --main-jar "${APP_NAME}.jar" \
  --dest "${OUT}" \
  --add-modules "${MODULES}" \
  --jlink-options "--strip-debug --no-man-pages --no-header-files" \
  --java-options "-XX:TieredStopAtLevel=1" \
  --java-options "-XX:+UseSerialGC"

APP_DIR="${OUT}/${APP_NAME}"
cp packaging/dist/sync-config.yml "${APP_DIR}/"
cp docs/USER_GUIDE.md "${APP_DIR}/"
cp LICENSE "${APP_DIR}/"
cp samples/wbs_hierarchy.csv "${APP_DIR}/sample-wbs.csv"

(cd "${OUT}" && tar czf "${APP_NAME}-linux.tar.gz" "${APP_NAME}")
echo "Created: ${APP_DIR} and ${OUT}/${APP_NAME}-linux.tar.gz"
