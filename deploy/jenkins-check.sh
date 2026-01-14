#!/usr/bin/env bash
set -euo pipefail

APP_HOST="${APP_HOST:-localhost}"
APP_PORT="${APP_PORT:-3004}"
APP_URL="${APP_URL:-http://${APP_HOST}:${APP_PORT}/admin.html}"
PID_FILE="${PID_FILE:-/var/lib/jenkins/redmine-upster/app.pid}"
WAIT_SECONDS="${WAIT_SECONDS:-20}"
SLEEP_INTERVAL="${SLEEP_INTERVAL:-2}"

echo "Checking process..."
if [[ -f "${PID_FILE}" ]]; then
  PID="$(cat "${PID_FILE}" || true)"
  if [[ -n "${PID}" ]] && kill -0 "${PID}" >/dev/null 2>&1; then
    echo "Process is running (pid=${PID})."
  else
    echo "Process is not running (pid file=${PID_FILE})." >&2
    exit 1
  fi
else
  echo "PID file not found: ${PID_FILE}" >&2
  exit 1
fi

echo "Checking port ${APP_PORT}..."
if command -v ss >/dev/null 2>&1; then
  ELAPSED=0
  until ss -lntp 2>/dev/null | rg -q ":${APP_PORT}" || [[ "${ELAPSED}" -ge "${WAIT_SECONDS}" ]]; do
    sleep "${SLEEP_INTERVAL}"
    ELAPSED=$((ELAPSED + SLEEP_INTERVAL))
  done
  if ! ss -lntp 2>/dev/null | rg -q ":${APP_PORT}"; then
    echo "Port ${APP_PORT} is not listening after ${WAIT_SECONDS}s." >&2
    exit 1
  fi
else
  echo "ss command not available; skipping port check." >&2
fi

echo "Checking URL ${APP_URL}..."
if command -v curl >/dev/null 2>&1; then
  ELAPSED=0
  STATUS="000"
  while [[ "${ELAPSED}" -lt "${WAIT_SECONDS}" ]]; do
    STATUS="$(curl -sS -o /dev/null -w "%{http_code}" "${APP_URL}" || true)"
    if [[ "${STATUS}" == "200" ]]; then
      break
    fi
    sleep "${SLEEP_INTERVAL}"
    ELAPSED=$((ELAPSED + SLEEP_INTERVAL))
  done
  if [[ "${STATUS}" != "200" ]]; then
    echo "HTTP check failed (status=${STATUS}) after ${WAIT_SECONDS}s." >&2
    exit 1
  fi
else
  echo "curl command not available; skipping HTTP check." >&2
fi

echo "Check OK."
