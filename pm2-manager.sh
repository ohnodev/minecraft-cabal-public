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
ECOSYSTEM_FILE="${SCRIPT_DIR}/ecosystem.config.js"
APP_NAME="cabal-smp-api"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok() { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_err() { echo -e "${RED}[ERROR]${NC} $1"; }

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
  restart       Clean restart for API only
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

case "${1:-help}" in
  init) init ;;
  start) start ;;
  stop) stop ;;
  restart)
    if [[ -n "${2:-}" ]]; then
      log_err "Invalid target for restart: ${2}"
      log_err "Usage: $0 restart"
      exit 1
    fi
    restart
    ;;
  status) status ;;
  logs) logs_cmd "${2:-100}" ;;
  clean-logs) clean_logs ;;
  save) save ;;
  generate-map) generate_map ;;
  help) help ;;
  *)
    log_err "Unknown command: ${1:-}"
    echo "" >&2
    help >&2
    exit 2
    ;;
esac
