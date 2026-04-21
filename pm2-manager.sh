#!/usr/bin/env bash

set -euo pipefail

export NVM_DIR="${HOME}/.nvm"
if [ -s "${NVM_DIR}/nvm.sh" ]; then
  # shellcheck disable=SC1090
  source "${NVM_DIR}/nvm.sh"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGS_DIR="${SCRIPT_DIR}/logs"
API_DIR="${SCRIPT_DIR}/api"
CLAIM_MOD_DIR="${SCRIPT_DIR}/claim-mod"
ECOSYSTEM_FILE="${SCRIPT_DIR}/ecosystem.config.js"
APP_NAME="cabal-smp-api"
MC_SERVICE_NAME="minecraft"
MC_PORTS_DEFAULT="25565 25575"
MOD_SRC_JAR=""
MOD_DEST_JAR=""
MC_LOG_DIR="${SCRIPT_DIR}/server/logs"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok() { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_err() { echo -e "${RED}[ERROR]${NC} $1"; }

deny_minecraft_control() {
  log_err "This pm2-manager is API-only."
  log_err "Minecraft server control is disabled here and must be done via Docker Compose."
  log_err "Use: docker compose <up|stop|restart|ps|logs> ${MC_SERVICE_NAME}"
  return 1
}

is_port_in_use() {
  local port="$1"
  ss -ltnp "( sport = :${port} )" 2>/dev/null | tail -n +2 | grep -q .
}

ensure_minecraft_ports_free() {
  local ports="${MC_PORTS:-$MC_PORTS_DEFAULT}"
  local blocked=0
  local port
  for port in ${ports}; do
    if is_port_in_use "${port}"; then
      blocked=1
      log_err "Port ${port} is still in use."
      ss -ltnp "( sport = :${port} )" || true
    fi
  done
  if [ "${blocked}" -ne 0 ]; then
    log_err "Refusing to start ${MC_SERVICE_NAME} while required ports are occupied."
    return 1
  fi
  return 0
}

require_pm2() {
  if ! command -v pm2 >/dev/null 2>&1; then
    log_err "pm2 is not installed. Install with: npm install -g pm2"
    exit 1
  fi
}

ensure_logs_dir() {
  mkdir -p "${LOGS_DIR}"
}

clean_logs() {
  ensure_logs_dir
  rm -f "${LOGS_DIR}/${APP_NAME}-out.log" \
        "${LOGS_DIR}/${APP_NAME}-error.log" \
        "${LOGS_DIR}/${APP_NAME}-out-"*.log \
        "${LOGS_DIR}/${APP_NAME}-error-"*.log \
        2>/dev/null || true
  log_ok "Cleaned ${APP_NAME} logs in ${LOGS_DIR}"
}

ensure_ecosystem() {
  if [ ! -f "${ECOSYSTEM_FILE}" ]; then
    log_err "Missing ${ECOSYSTEM_FILE}. Use the committed ecosystem.config.js in the repo root."
    exit 1
  fi
  log_ok "Using PM2 ecosystem ${ECOSYSTEM_FILE}"
}

setup_logrotate() {
  require_pm2
  if ! pm2 module:list | grep -q "pm2-logrotate"; then
    log_info "Installing pm2-logrotate..."
    pm2 install pm2-logrotate
  fi

  pm2 set pm2-logrotate:max_size 10M >/dev/null
  pm2 set pm2-logrotate:retain 5 >/dev/null
  pm2 set pm2-logrotate:compress false >/dev/null
  pm2 set pm2-logrotate:dateFormat YYYY-MM-DD_HH-mm-ss >/dev/null
  pm2 set pm2-logrotate:workerInterval 30 >/dev/null
  pm2 set pm2-logrotate:rotateInterval '0 0 * * *' >/dev/null
  pm2 set pm2-logrotate:rotateModule true >/dev/null
  log_ok "Configured pm2-logrotate (10M, retain 5, no compression)"
}

build_api() {
  log_info "Building API..."
  (cd "${API_DIR}" && npm run build)
  log_ok "API build complete"
}

validate_zip() {
  local zip_path="$1"
  python3 - "${zip_path}" <<'PY'
import sys, zipfile
path = sys.argv[1]
with zipfile.ZipFile(path, "r") as z:
    bad = z.testzip()
    if bad is not None:
        print(f"corrupt entry: {bad}")
        raise SystemExit(1)
print("ok")
PY
}

find_mod_jar() {
  local jar
  jar="$(ls -1t "${CLAIM_MOD_DIR}"/build/libs/cabal-claim-*.jar 2>/dev/null | head -n 1 || true)"
  echo "${jar}"
}

build_claim_mod() {
  log_info "Building claim mod..."
  local fabric_api_version="0.145.5+local"
  local fabric_local_repo="${HOME}/.m2/repository/net/fabricmc/fabric-api/fabric-api/${fabric_api_version}"
  local fabric_local_jar="${fabric_local_repo}/fabric-api-${fabric_api_version}.jar"
  local fabric_local_pom="${fabric_local_repo}/fabric-api-${fabric_api_version}.pom"
  if [ ! -f "${fabric_local_jar}" ] || [ ! -f "${fabric_local_pom}" ]; then
    log_err "Missing local Fabric API artifact (${fabric_api_version})."
    log_err "Build/publish Fabric API 26.2 first (see README.md: 'Building the claim mod')."
    log_err "Expected files:"
    log_err "  ${fabric_local_jar}"
    log_err "  ${fabric_local_pom}"
    return 1
  fi
  if ! (cd "${CLAIM_MOD_DIR}" && JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./gradlew build >/dev/null); then
    log_err "Claim mod build failed"
    return 1
  fi
  MOD_SRC_JAR="$(find_mod_jar)"
  if [ -z "${MOD_SRC_JAR}" ] || [ ! -f "${MOD_SRC_JAR}" ]; then
    log_err "Built mod jar not found"
    return 1
  fi
  MOD_DEST_JAR="${SCRIPT_DIR}/server/mods/$(basename "${MOD_SRC_JAR}")"
  log_ok "Claim mod build complete"
}

deploy_mod_atomic() {
  local src="${1:-$MOD_SRC_JAR}"
  local dest="${2:-$MOD_DEST_JAR}"
  local tmp="${dest}.tmp.$$"
  local dest_dir
  local src_base
  local project_prefix
  dest_dir="$(dirname "${dest}")"
  src_base="$(basename "${src}")"
  project_prefix="$(echo "${src_base}" | sed -E 's/[0-9].*$//')"
  if [ -z "${project_prefix}" ]; then
    project_prefix="cabal-claim-"
  fi
  if [ ! -f "${src}" ]; then
    log_err "Source jar not found: ${src}"
    return 1
  fi
  if ! validate_zip "${src}" >/dev/null; then
    log_err "Source jar failed ZIP integrity check: ${src}"
    return 1
  fi
  if ! cp "${src}" "${tmp}"; then
    rm -f "${tmp}" || true
    log_err "Failed to copy built jar to temp path: ${tmp}"
    return 1
  fi
  if ! validate_zip "${tmp}" >/dev/null; then
    rm -f "${tmp}" || true
    log_err "Temporary jar failed ZIP integrity check: ${tmp}"
    return 1
  fi
  local stale
  for stale in "${dest_dir}/${project_prefix}"*.jar; do
    [ -e "${stale}" ] || continue
    if [ "${stale}" = "${dest}" ] || [ "${stale}" = "${tmp}" ]; then
      continue
    fi
    rm -f "${stale}" || true
  done
  if ! mv -f "${tmp}" "${dest}"; then
    rm -f "${tmp}" || true
    log_err "Failed to move temp jar into place: ${dest}"
    return 1
  fi
  log_ok "Atomically deployed mod jar to ${dest}"
}

rebuild_repo_mods() {
  # Rebuild all repo-managed server mods before restarts.
  # Current source-managed mod projects: claim-mod (cabal-claim-*.jar).
  if ! build_claim_mod; then
    return 1
  fi
  if ! deploy_mod_atomic "${MOD_SRC_JAR}" "${MOD_DEST_JAR}"; then
    return 1
  fi
  log_ok "Rebuilt and deployed repo-managed mods"
}

decompress_minecraft_logs() {
  if [ ! -d "${MC_LOG_DIR}" ]; then
    return 0
  fi
  python3 - "${MC_LOG_DIR}" <<'PY'
import gzip
import shutil
import sys
from pathlib import Path

log_dir = Path(sys.argv[1])
for gz in sorted(log_dir.glob("*.gz")):
    out_path = gz.with_suffix("")
    try:
        with gzip.open(gz, "rb") as src, open(out_path, "wb") as dst:
            shutil.copyfileobj(src, dst)
        gz.unlink()
        print(f"decompressed {gz.name} -> {out_path.name}")
    except Exception as e:
        print(f"failed to decompress {gz.name}: {e}", file=sys.stderr)
        raise
PY
}

# Biome map is generated offline; validate MAP_GEN_VERSION + spawn fingerprint (same rules as map-service).
ensure_map_cache() {
  if (cd "${API_DIR}" && node dist/validate-map-cache-cli.js); then
    log_ok "Map biome cache valid: ${API_DIR}/cache/map-biomes.json"
    return 0
  fi
  log_warn "Map cache missing or invalid (version / spawn mismatch) — regenerating..."
  log_info "Biome generation may take 1–3 minutes (~8GB Node heap)..."
  (cd "${API_DIR}" && npm run generate-map-cache)
  log_ok "Map cache generated"
}

init() {
  require_pm2
  ensure_logs_dir
  ensure_ecosystem
  setup_logrotate
  log_ok "PM2 init complete"
}

start() {
  require_pm2
  ensure_logs_dir
  ensure_ecosystem
  build_api
  ensure_map_cache
  pm2 start "${ECOSYSTEM_FILE}" --only "${APP_NAME}"
  log_ok "Started ${APP_NAME}"
}

stop() {
  require_pm2
  pm2 stop "${APP_NAME}" >/dev/null 2>&1 || true
  pm2 delete "${APP_NAME}" >/dev/null 2>&1 || true
  log_ok "Stopped ${APP_NAME}"
}

restart() {
  require_pm2
  stop
  clean_logs
  start
  log_ok "Restarted ${APP_NAME} with clean logs"
}

restart_minecraft_service_only() {
  log_info "Restarting docker compose service ${MC_SERVICE_NAME}..."
  if ! command -v docker >/dev/null 2>&1; then
    log_err "docker not found; cannot restart ${MC_SERVICE_NAME}."
    return 1
  fi
  if ! (cd "${SCRIPT_DIR}" && docker compose restart "${MC_SERVICE_NAME}" >/dev/null 2>&1); then
    log_err "Failed to restart docker compose service ${MC_SERVICE_NAME}."
    (cd "${SCRIPT_DIR}" && docker compose ps "${MC_SERVICE_NAME}") || true
    return 1
  fi
  if ! decompress_minecraft_logs; then
    log_warn "Failed to decompress one or more Minecraft logs in ${MC_LOG_DIR}"
  fi
  log_ok "Restarted docker compose service ${MC_SERVICE_NAME}"
  return 0
}

restart_minecraft() {
  if ! rebuild_repo_mods; then
    log_err "Aborting ${MC_SERVICE_NAME} restart because mod rebuild/deploy failed."
    return 1
  fi
  restart_minecraft_service_only
}

restart_api_with_deps() {
  if ! rebuild_repo_mods; then
    log_err "Aborting API restart because mod rebuild/deploy failed."
    return 1
  fi
  if ! restart_minecraft_service_only; then
    log_err "Aborting API restart because ${MC_SERVICE_NAME} restart failed."
    return 1
  fi
  restart
}

restart_all() {
  if ! rebuild_repo_mods; then
    log_err "Aborting restart-all because mod rebuild/deploy failed."
    return 1
  fi
  if ! restart_minecraft_service_only; then
    log_err "Aborting restart-all because Minecraft restart failed."
    return 1
  fi
  if ! restart; then
    log_err "API restart failed during restart-all."
    return 1
  fi
  log_ok "Restarted ${MC_SERVICE_NAME} and ${APP_NAME}"
}

status() {
  require_pm2
  pm2 status
}

logs_cmd() {
  require_pm2
  local lines="${1:-100}"
  pm2 logs "${APP_NAME}" --lines "${lines}"
}

save() {
  require_pm2
  pm2 save
  log_ok "Saved PM2 process list"
}

help() {
  cat <<EOF
PM2 Manager for ${APP_NAME}

Usage: $0 <command>

Commands:
  init          Create logs dir, verify ecosystem.config.js exists, pm2-logrotate setup
  start         Build API, generate map cache if missing, start process with PM2
  stop          Stop and delete PM2 process
  restart
  restart api   Restart API only (no mod build, no Minecraft restart)
  status        Show PM2 status for API
  logs [lines]  Tail PM2 logs for API (default: 100 lines)
  clean-logs    Delete API log files from logs/
  save          Save PM2 process list
  generate-map  Rebuild api/cache/map-biomes.json (heavy; run after world/claim changes)
  help          Show this help
EOF
}

generate_map() {
  build_api
  log_info "Regenerating biome map cache..."
  (cd "${API_DIR}" && npm run generate-map-cache)
  log_ok "Done — restart the API or wait for map file reload (~30s)"
}

deploy_mod() {
  if ! rebuild_repo_mods; then
    return 1
  fi
  restart_minecraft_service_only
}

case "${1:-help}" in
  init) init ;;
  start) start ;;
  stop) stop ;;
  restart)
    case "${2:-api}" in
      api) restart ;;
      api-with-deps)
        deny_minecraft_control
        ;;
      minecraft)
        deny_minecraft_control
        ;;
      *)
        log_err "Unknown restart target: ${2:-}"
        echo "" >&2
        help >&2
        exit 2
        ;;
    esac
    ;;
  restart-all)
    deny_minecraft_control
    ;;
  status) status ;;
  logs) logs_cmd "${2:-100}" ;;
  clean-logs) clean_logs ;;
  save) save ;;
  generate-map) generate_map ;;
  deploy-mod)
    deny_minecraft_control
    ;;
  help) help ;;
  *)
    log_err "Unknown command: ${1:-}"
    echo "" >&2
    help >&2
    exit 2
    ;;
esac
