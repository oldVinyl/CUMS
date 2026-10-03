#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
umask 077
# Prevent overlapping cron runs. The environment file is not read into shell code.
exec 9>deploy/.backup.lock
flock -n 9 || { echo 'Another backup is running.' >&2; exit 1; }
compose=(docker compose --env-file .env.production -f deploy/docker-compose.prod.yml)
"${compose[@]}" stop backend
trap '"${compose[@]}" start backend' EXIT
"${compose[@]}" run --rm -T -e BACKUP_QUIESCED=true backup /opt/cums/backup.sh
