#!/usr/bin/env bash
set -euo pipefail

JAVA17="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64/bin/java}"
APP_JAR="${APP_JAR:-/var/lib/jenkins/redmine-upster/app.jar}"
LOG_FILE="${LOG_FILE:-/var/lib/jenkins/redmine-upster/app.log}"
PID_FILE="${PID_FILE:-/var/lib/jenkins/redmine-upster/app.pid}"

if [[ ! -x "${JAVA17}" ]]; then
  echo "JAVA17 not found or not executable: ${JAVA17}" >&2
  exit 1
fi
if [[ ! -f "${APP_JAR}" ]]; then
  echo "APP_JAR not found: ${APP_JAR}" >&2
  exit 1
fi

if [[ -f "${PID_FILE}" ]]; then
  EXISTING_PID="$(cat "${PID_FILE}" || true)"
  if [[ -n "${EXISTING_PID}" ]] && kill -0 "${EXISTING_PID}" >/dev/null 2>&1; then
    echo "Already running (pid=${EXISTING_PID})." >&2
    exit 0
  fi
fi

nohup "${JAVA17}" -jar "${APP_JAR}" >"${LOG_FILE}" 2>&1 &
echo $! > "${PID_FILE}"
echo "Started pid=$(cat "${PID_FILE}")"
