#!/usr/bin/env sh
set -eu
mkdir -p backups
timestamp=$(date +%Y%m%d-%H%M%S)
docker compose exec -T postgres pg_dump -U poker -d poker | gzip > "backups/poker-${timestamp}.sql.gz"
find backups -type f -name 'poker-*.sql.gz' -mtime +14 -delete
echo "Backup written to backups/poker-${timestamp}.sql.gz"
