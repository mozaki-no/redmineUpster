#!/usr/bin/env bash
set -euo pipefail

JAVA17="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64/bin/java}"
APP_JAR="${APP_JAR:-/var/lib/jenkins/redmine-upster/app.jar}"
LOG_FILE="${LOG_FILE:-/var/lib/jenkins/redmine-upster/app.log}"
PID_FILE="${PID_FILE:-/var/lib/jenkins/redmine-upster/app.pid}"
RUN_IN_FOREGROUND="${RUN_IN_FOREGROUND:-false}"
LOG_TO_STDOUT="${LOG_TO_STDOUT:-false}"
START_DOCKER="${START_DOCKER:-false}"
DOCKER_CMD="${DOCKER_CMD:-docker compose up -d}"

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

if [[ "${START_DOCKER}" == "true" ]]; then
  if command -v docker >/dev/null 2>&1; then
    eval "${DOCKER_CMD}"
  else
    echo "docker command not found; skipping docker startup." >&2
  fi
fi

if [[ "${RUN_IN_FOREGROUND}" == "true" ]]; then
  exec "${JAVA17}" -jar "${APP_JAR}"
fi

if [[ "${LOG_TO_STDOUT}" == "true" ]]; then
  nohup "${JAVA17}" -jar "${APP_JAR}" >/dev/stdout 2>&1 &
else
  nohup "${JAVA17}" -jar "${APP_JAR}" >"${LOG_FILE}" 2>&1 &
fi
echo $! > "${PID_FILE}"
echo "Started pid=$(cat "${PID_FILE}")"
