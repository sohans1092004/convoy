#!/usr/bin/env bash
# Nightly (cron, bootstrap.sh) and before every deploy: pg_dumpall, gzip, encrypt to AGE_RECIPIENT (public key only
# on the host; the private key never is), upload to OCI Object Storage via the pre-authenticated request. The bucket's
# lifecycle rule keeps 7 days; the host keeps the last 2 locally.
#   backup.sh [label]
set -euo pipefail
set -a; . /opt/convoy/convoy.env; set +a
: "${AGE_RECIPIENT:?}" "${BACKUP_PAR_URL:?}"
cd /opt/convoy/backups
name="convoy-$(date -u +%Y%m%dT%H%M%SZ)-${1:-nightly}.sql.gz.age"
docker exec "$(docker ps -qf name=convoy-lite-postgres)" pg_dumpall -U convoy_admin | gzip -6 | age -r "$AGE_RECIPIENT" > "$name"
curl -fsS --max-time 300 -T "$name" "${BACKUP_PAR_URL%/}/$name"
ls -1t convoy-*.age | tail -n +3 | xargs -r rm -f
echo "$(date -u +%FT%TZ) backup $name $(stat -c %s "$name") bytes uploaded"
