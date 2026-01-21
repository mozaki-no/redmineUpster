#!/usr/bin/env bash
set -euo pipefail

# Jenkins job sets APP_JAR to the built artifact path.
APP_JAR="${APP_JAR:-target/redmineUpster-0.0.1-SNAPSHOT.jar}"
APP_CONFIG="${APP_CONFIG:-src/main/resources/application.yml}"
BUILD_CMD="${BUILD_CMD:-./mvnw package -DskipTests}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/redmine-upster}"
SERVICE_NAME="${SERVICE_NAME:-redmine-upster}"
SUDO_CMD="${SUDO_CMD:-}"
FORCE_BUILD="${FORCE_BUILD:-true}"
SKIP_SYSTEMCTL="${SKIP_SYSTEMCTL:-false}"

run_cmd() {
  if [[ -n "${SUDO_CMD}" ]]; then
    ${SUDO_CMD} "$@"
  else
    "$@"
  fi
}

if [[ -z "${SUDO_CMD}" ]]; then
  if [[ -d "${DEPLOY_DIR}" ]]; then
    if [[ ! -w "${DEPLOY_DIR}" ]]; then
      echo "DEPLOY_DIR is not writable: ${DEPLOY_DIR}. Set DEPLOY_DIR or SUDO_CMD=sudo." >&2
      exit 1
    fi
  else
    PARENT_DIR="$(dirname "${DEPLOY_DIR}")"
    if [[ ! -w "${PARENT_DIR}" ]]; then
      echo "Parent dir is not writable: ${PARENT_DIR}. Set DEPLOY_DIR or SUDO_CMD=sudo." >&2
      exit 1
    fi
  fi
fi

if [[ "${FORCE_BUILD}" == "true" ]]; then
  echo "FORCE_BUILD=true: building artifact..." >&2
fi
if [[ "${FORCE_BUILD}" == "true" || ! -f "${APP_JAR}" ]]; then
  if [[ ! -x "./mvnw" ]]; then
    if command -v mvn >/dev/null 2>&1; then
      BUILD_CMD="mvn package -DskipTests"
    else
      echo "mvnw not found and mvn is not available. Set APP_JAR or BUILD_CMD." >&2
      exit 1
    fi
  fi
  eval "${BUILD_CMD}"
  CANDIDATE_JAR="$(ls -t target/*.jar 2>/dev/null | head -n 1 || true)"
  if [[ -n "${CANDIDATE_JAR}" ]]; then
    APP_JAR="${CANDIDATE_JAR}"
  else
    echo "No jar found under target/ after build." >&2
    exit 1
  fi
fi
if [[ ! -f "${APP_JAR}" ]]; then
  echo "APP_JAR not found: ${APP_JAR}" >&2
  exit 1
fi
if [[ ! -f "${APP_CONFIG}" ]]; then
  echo "APP_CONFIG not found: ${APP_CONFIG}" >&2
  exit 1
fi

if [[ ! -d "${DEPLOY_DIR}" ]]; then
  run_cmd mkdir -p "${DEPLOY_DIR}"
fi
run_cmd install -m 0644 "${APP_JAR}" "${DEPLOY_DIR}/app.jar"
run_cmd install -m 0644 "${APP_CONFIG}" "${DEPLOY_DIR}/application.yml"

if [[ "${SKIP_SYSTEMCTL}" == "true" ]]; then
  echo "SKIP_SYSTEMCTL=true: skipping systemctl operations." >&2
else
  run_cmd systemctl daemon-reload
  run_cmd systemctl restart "${SERVICE_NAME}"
  run_cmd systemctl status "${SERVICE_NAME}" --no-pager
fi
