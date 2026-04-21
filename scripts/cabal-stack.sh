#!/usr/bin/env bash
# Convenience wrapper for Minecraft Docker runtime.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cmd="${1:-}"
case "$cmd" in
  start)
    (cd "${REPO_ROOT}" && docker compose up -d minecraft)
    ;;
  stop)
    (cd "${REPO_ROOT}" && docker compose stop minecraft)
    ;;
  restart)
    (cd "${REPO_ROOT}" && docker compose restart minecraft)
    ;;
  status)
    (cd "${REPO_ROOT}" && docker compose ps minecraft)
    ;;
  logs)
    (cd "${REPO_ROOT}" && docker compose logs -f minecraft)
    ;;
  *)
    echo "Usage: $0 start|stop|restart|status|logs" >&2
    exit 1
    ;;
esac
