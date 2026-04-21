#!/usr/bin/env bash
# Convenience wrapper for minecraft-cabal (systemd).
set -euo pipefail

cmd="${1:-}"
case "$cmd" in
  start)
    systemctl start minecraft-cabal
    ;;
  stop)
    systemctl stop minecraft-cabal
    ;;
  restart)
    systemctl restart minecraft-cabal
    ;;
  status)
    systemctl status minecraft-cabal
    ;;
  *)
    echo "Usage: $0 start|stop|restart|status" >&2
    exit 1
    ;;
esac
