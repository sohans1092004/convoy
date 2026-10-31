#!/usr/bin/env bash
# Restore drill (P6.D): fetch a backup from Object Storage, decrypt it, load it into a scratch Postgres container and
# compare the row count of every table with the live database. Run on the host; the age private key is passed in for
# this run only and never stored there.
#   restore-check.sh <age-identity-file> [object-name]     (default: the newest backup)
set -euo pipefail
set -a; . /opt/convoy/convoy.env; set +a
key=$1 obj=${2:-}
work=$(mktemp -d)
trap 'docker rm -f convoy-restore-check >/dev/null 2>&1; rm -rf "$work"' EXIT
if [ -z "$obj" ]; then
  obj=$(curl -fsS "${BACKUP_PAR_URL%/}/" | jq -r '.objects[].name' | sort | tail -1)
fi
echo "restoring $obj"
curl -fsS "${BACKUP_PAR_URL%/}/$obj" -o "$work/b.age"
age -d -i "$key" "$work/b.age" | gunzip > "$work/b.sql"

docker run -d --name convoy-restore-check -e POSTGRES_PASSWORD=scratch postgres:16.15-alpine >/dev/null
until docker exec convoy-restore-check pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
sleep 2
docker exec -i convoy-restore-check psql -q -U postgres -d postgres -v ON_ERROR_STOP=0 < "$work/b.sql" > "$work/restore.log" 2>&1 || true

# Every table except Flyway history, with exact counts, in both databases
counts='do $$ declare r record; n bigint; begin for r in select schemaname s, tablename t from pg_tables
  where schemaname not in ($q$pg_catalog$q$, $q$information_schema$q$) and tablename <> $q$flyway_schema_history$q$ order by 1, 2 loop
  execute format($q$select count(*) from %I.%I$q$, r.s, r.t) into n; raise notice $q$%|%|%$q$, r.s, r.t, n; end loop; end $$;'
docker exec -i convoy-restore-check psql -q -U postgres -d convoy -c "$counts" 2>&1 | sed -n 's/^NOTICE:  //p' > "$work/restored.txt"
docker exec -i "$(docker ps -qf name=convoy-lite-postgres)" psql -q -U convoy_admin -d convoy -c "$counts" 2>&1 | sed -n 's/^NOTICE:  //p' > "$work/live.txt"

tables=$(wc -l < "$work/restored.txt")
echo "tables restored: $tables; restore errors: $(grep -c ERROR "$work/restore.log" || true)"
if diff "$work/live.txt" "$work/restored.txt" > "$work/diff.txt"; then
  echo "row counts: identical in all $tables tables"
else
  echo "row counts differ (rows written since the backup show here too):"; cat "$work/diff.txt"
fi
