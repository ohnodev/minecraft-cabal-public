#!/usr/bin/env bash
# Install Cabal SMP nginx config (API reverse proxy only). Run from repo root.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGE="${1:-bootstrap}"
SITE="thecabal-smp"
AVAIL="/etc/nginx/sites-available/${SITE}"
ENABLED="/etc/nginx/sites-enabled/${SITE}"

case "$STAGE" in
  bootstrap|1) CONF="${ROOT}/deploy/nginx/01-bootstrap.conf" ;;
  api-tls|2)   CONF="${ROOT}/deploy/nginx/02-api-tls.conf" ;;
  *)
    echo "Usage: $0 {bootstrap|api-tls}"
    echo "  bootstrap — HTTP API + ACME webroot (before first certbot)"
    echo "  api-tls   — HTTPS API (after cert for minecraftapi.thecabal.app)"
    exit 1
    ;;
esac

if [ ! -f "$CONF" ]; then
  echo "error: config file not found: $CONF" >&2
  exit 1
fi

BACKUP=""
if [ -f "$AVAIL" ]; then
  BACKUP="/tmp/${SITE}-sites-available.bak.$$"
  sudo cp "$AVAIL" "$BACKUP"
fi

sudo mkdir -p /var/www/certbot
sudo cp "$CONF" "$AVAIL"
sudo ln -sf "$AVAIL" "$ENABLED"

if ! sudo nginx -t; then
  echo "error: nginx -t failed; restoring previous configuration" >&2
  if [ -n "$BACKUP" ] && [ -f "$BACKUP" ]; then
    sudo cp "$BACKUP" "$AVAIL"
    sudo rm -f "$BACKUP"
  else
    sudo rm -f "$AVAIL" "$ENABLED"
  fi
  exit 1
fi

if [ -n "$BACKUP" ] && [ -f "$BACKUP" ]; then
  sudo rm -f "$BACKUP"
fi

sudo systemctl reload nginx
echo "Installed stage '${STAGE}' → ${ENABLED}"
