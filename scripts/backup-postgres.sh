#!/usr/bin/env sh
set -eu
umask 077
mkdir -p backups
timestamp=$(date +%Y%m%d-%H%M%S)
base="backups/.poker-${timestamp}-$$"
final="backups/poker-${timestamp}.sql.gz"
trap 'rm -f "${base}.sql" "${base}.sql.gz"' EXIT INT TERM
docker compose exec -T postgres pg_dump -U poker -d poker > "${base}.sql"
test -s "${base}.sql"
gzip -9 "${base}.sql"
gzip -t "${base}.sql.gz"
chmod 600 "${base}.sql.gz"
mv "${base}.sql.gz" "$final"
find backups -type f -name 'poker-*.sql.gz' -mtime +14 -delete
echo "Backup written to $final"
