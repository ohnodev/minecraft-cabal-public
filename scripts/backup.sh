#!/usr/bin/env bash
set -euo pipefail

SERVER_DIR="/root/minecraft-cabal/server"
BACKUP_DIR="/root/minecraft-cabal/backups"
TIMESTAMP=$(date +"%Y-%m-%d_%H-%M-%S")
BACKUP_FILE="${BACKUP_DIR}/world-backup-${TIMESTAMP}.tar.gz"
KEEP=7

DIRS=(world)
[ -d "$SERVER_DIR/world_nether" ] && DIRS+=(world_nether)
[ -d "$SERVER_DIR/world_the_end" ] && DIRS+=(world_the_end)

echo "Backing up world data (${DIRS[*]})..."
tar -czf "$BACKUP_FILE" -C "$SERVER_DIR" "${DIRS[@]}"

echo "Backup saved: $BACKUP_FILE"
echo "Size: $(du -h "$BACKUP_FILE" | cut -f1)"

TOTAL=$(ls -1t "${BACKUP_DIR}"/world-backup-*.tar.gz 2>/dev/null | wc -l)
if [ "$TOTAL" -gt "$KEEP" ]; then
  ls -1t "${BACKUP_DIR}"/world-backup-*.tar.gz | tail -n +$(( KEEP + 1 )) | xargs rm -f
  echo "Pruned old backups, keeping latest ${KEEP}."
fi

echo "Done."
