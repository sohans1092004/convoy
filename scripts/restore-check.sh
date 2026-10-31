#!/usr/bin/env bash
# Restore drill (P6.D), run from the laptop: the VM can only write backups, so reading and restoring use ~/.oci/config.
#   1. download the newest (or the named) backup from convoy-backups (Python OCI SDK)
#   2. decrypt it with the age private key (kept on this laptop) in a throwaway container, gunzip
#   3. load it into a scratch Postgres container here
#   4. compare the row count of every table with the live database on the VM (over SSH, admin key from ssh-agent)
#   scripts/restore-check.sh <age-identity-file> <vm-ip> [object-name]
set -euo pipefail
cd "$(dirname "$0")/.."
key=$1 vm=$2 obj=${3:-}
export MSYS_NO_PATHCONV=1 MSYS2_ENV_CONV_EXCL='*'
py=$(command -v python3 || command -v python)
work=$(mktemp -d)
trap 'docker rm -f convoy-restore-check >/dev/null 2>&1; rm -rf "$work"' EXIT
win() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

obj=$("$py" -I - "$obj" "$(win "$work/b.age")" <<'PY'
import sys, oci
want, dest = sys.argv[1], sys.argv[2]
cfg = oci.config.from_file()
client = oci.object_storage.ObjectStorageClient(cfg)
ns = client.get_namespace().data
if not want:
    objs = oci.pagination.list_call_get_all_results(client.list_objects, ns, "convoy-backups", prefix="convoy-").data.objects
    want = sorted(o.name for o in objs)[-1]
with open(dest, "wb") as f:
    for chunk in client.get_object(ns, "convoy-backups", want).data.raw.stream(1 << 20, decode_content=False):
        f.write(chunk)
print(want)
PY
)
echo "restoring $obj ($(stat -c %s "$work/b.age") bytes, encrypted)"
docker run --rm -i -v "$(win "$key"):/k:ro" alpine:3.22 sh -c 'apk add -q --no-cache age >/dev/null && age -d -i /k' < "$work/b.age" | gunzip > "$work/b.sql"

docker run -d --name convoy-restore-check -e POSTGRES_PASSWORD=scratch postgres:16.15-alpine >/dev/null
until docker exec convoy-restore-check pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
sleep 2
docker exec -i convoy-restore-check psql -q -U postgres -d postgres -v ON_ERROR_STOP=0 < "$work/b.sql" > "$work/restore.log" 2>&1 || true

# Every table except Flyway history, with exact counts, in both databases
counts='do $$ declare r record; n bigint; begin for r in select schemaname s, tablename t from pg_tables
  where schemaname not in ($q$pg_catalog$q$, $q$information_schema$q$) and tablename <> $q$flyway_schema_history$q$ order by 1, 2 loop
  execute format($q$select count(*) from %I.%I$q$, r.s, r.t) into n; raise notice $q$%|%|%$q$, r.s, r.t, n; end loop; end $$;'
docker exec -i convoy-restore-check psql -q -U postgres -d convoy -c "$counts" 2>&1 | sed -n 's/^NOTICE:  //p' > "$work/restored.txt"
ssh -o BatchMode=yes "ubuntu@$vm" "sudo docker exec -i \$(sudo docker ps -qf name=convoy-lite-postgres) psql -q -U convoy_admin -d convoy" <<< "$counts" 2>&1 \
  | sed -n 's/^NOTICE:  //p' > "$work/live.txt"

tables=$(wc -l < "$work/restored.txt")
rows=$(awk -F'|' '{s+=$3} END {print s+0}' "$work/restored.txt")
echo "tables restored: $tables ($rows rows); restore errors: $(grep -c ERROR "$work/restore.log" || true)"
if diff "$work/live.txt" "$work/restored.txt" > "$work/diff.txt"; then
  echo "row counts: identical in all $tables tables"
else
  echo "row counts differ (rows written since the backup show here too):"; cat "$work/diff.txt"
fi
