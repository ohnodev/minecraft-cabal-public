#!/usr/bin/env bash
# Install minecraft-cabal unit and optional cabal-smp target.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root (sudo)." >&2
  exit 1
fi

for unit in \
  "$SCRIPT_DIR/minecraft-cabal.service" \
  "$SCRIPT_DIR/cabal-smp.target"; do
  if [[ ! -f "$unit" ]]; then
    echo "install-systemd-minecraft.sh: error: missing systemd unit file: $unit" >&2
    exit 1
  fi
done

install -m 0644 "$SCRIPT_DIR/minecraft-cabal.service" /etc/systemd/system/minecraft-cabal.service
install -m 0644 "$SCRIPT_DIR/cabal-smp.target" /etc/systemd/system/cabal-smp.target

systemctl daemon-reload

echo "Installed units. Typical next steps:"
echo "  sudo systemctl enable --now minecraft-cabal   # boot"
echo "  sudo systemctl enable cabal-smp.target        # optional"
echo "  sudo systemctl restart minecraft-cabal        # after unit changes"
echo "  # convenience (start/stop/restart/status): $REPO_ROOT/scripts/cabal-stack.sh ..."
