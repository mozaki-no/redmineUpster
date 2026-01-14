#!/usr/bin/env bash
set -euo pipefail

JAVA17="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64/bin/java}"
APP_JAR="${APP_JAR:-/var/lib/jenkins/redmine-upster/app.jar}"
APP_DIR="${APP_DIR:-/var/lib/jenkins/redmine-upster}"
APP_ARGS="${APP_ARGS:---spring.config.additional-location=file:${APP_DIR}/}"
APP_PORT="${APP_PORT:-3004}"
AUTO_KILL_PORT="${AUTO_KILL_PORT:-false}"
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

if [[ "${AUTO_KILL_PORT}" == "true" ]]; then
  if [[ -f "${PID_FILE}" ]]; then
    PID_FROM_FILE="$(cat "${PID_FILE}" || true)"
    if [[ -n "${PID_FROM_FILE}" ]] && kill -0 "${PID_FROM_FILE}" >/dev/null 2>&1; then
      echo "Stopping existing pid from file: ${PID_FROM_FILE}"
      kill "${PID_FROM_FILE}" || true
      sleep 2
    fi
  fi

  PORT_PID=""
  if command -v ss >/dev/null 2>&1; then
    PORT_PID="$(ss -lntp "sport = :${APP_PORT}" 2>/dev/null | rg -o "pid=([0-9]+)" -r '$1' | head -n 1 || true)"
  elif command -v lsof >/dev/null 2>&1; then
    PORT_PID="$(lsof -ti ":${APP_PORT}" | head -n 1 || true)"
  else
    echo "ss/lsof not available; skipping port kill." >&2
  fi

  if [[ -n "${PORT_PID}" ]]; then
    echo "Port ${APP_PORT} is in use by pid=${PORT_PID}, stopping it."
    kill "${PORT_PID}" || true
    sleep 2
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
  cd "${APP_DIR}"
  exec "${JAVA17}" -jar "${APP_JAR}" ${APP_ARGS}
fi

ORIGINAL_DIR="$(pwd)"
cd "${APP_DIR}"
if [[ "${LOG_TO_STDOUT}" == "true" ]]; then
  nohup "${JAVA17}" -jar "${APP_JAR}" ${APP_ARGS} >/dev/stdout 2>&1 &
else
  nohup "${JAVA17}" -jar "${APP_JAR}" ${APP_ARGS} >"${LOG_FILE}" 2>&1 &
fi
STARTED_PID=$!
cd "${ORIGINAL_DIR}"
echo "${STARTED_PID}" > "${PID_FILE}"
STARTED_PID="$(cat "${PID_FILE}")"
echo "Started pid=${STARTED_PID}"
sleep 2
if kill -0 "${STARTED_PID}" >/dev/null 2>&1; then
  echo "Process is running (pid=${STARTED_PID})."
else
  echo "Process is not running (pid=${STARTED_PID})." >&2
  exit 1
fi
