#!/usr/bin/env bash
# Restore drill (P6.D), run from the laptop: the VM can only write backups, so reading and restoring use ~/.oci/config.
#   1. download the newest (or the named) backup from convoy-backups (Python OCI SDK)
#   2. decrypt it with the age private key (kept on this laptop) in a throwaway container, gunzip
#   3. load it into a scratch Postgres container here
#   4. compare the row count of every table with the live database on the host (read-only query through Systems Manager)
#   aws login --profile convoy-aws && scripts/restore-check.sh <age-identity-file> [object-name]     (LITE_INSTANCE, AWS_PROFILE override the defaults)
set -euo pipefail
cd "$(dirname "$0")/.."
key=$1 obj=${2:-}
instance=${LITE_INSTANCE:-i-0829475b795c8667e}
aws_() { aws --profile "${AWS_PROFILE:-convoy-aws}" --region ap-southeast-2 "$@" | tr -d '\r'; } # aws.exe writes CRLF on Windows
export MSYS_NO_PATHCONV=1 MSYS2_ENV_CONV_EXCL='*'
py=$(command -v python3 || command -v python)
work=$(mktemp -d)
trap 'docker rm -f convoy-restore-check >/dev/null 2>&1; rm -rf "$work"' EXIT
win() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

obj=$("$py" -P - "$obj" "$(win "$work/b.age")" <<'PY'
import sys, oci
def oci_config():
    # ~/.oci/config as written by the console sample (quoted key_file, "# TODO" comments), read without echoing values
    import configparser, os, re
    p = configparser.ConfigParser()
    p.read(os.path.expanduser("~/.oci/config"))
    c = {k: re.sub(r"\s+#.*$", "", v).strip().strip("\"'") for k, v in p["DEFAULT"].items()}
    c["key_file"] = os.path.expanduser(c["key_file"])
    try:
        oci.config.validate_config(c)
    except Exception as e:
        sys.exit(f"~/.oci/config not usable ({type(e).__name__}); values not shown")
    return c
want, dest = sys.argv[1], sys.argv[2]
cfg = oci_config()
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
# Base64 keeps the query's quotes and $$ intact on the way through SSM; psql reports the counts as notices, on stderr.
remote="printf %s '$(printf '%s' "$counts" | base64 -w0)' | base64 -d | docker exec -i \$(docker ps -qf name=convoy-lite-postgres) psql -q -U convoy_admin -d convoy 2>&1"
command=$(aws_ ssm send-command --document-name AWS-RunShellScript --instance-ids "$instance" --comment "restore-check: live row counts" \
  --parameters "$("$py" -c 'import json,sys; print(json.dumps({"commands": [sys.argv[1]]}))' "$remote")" --query Command.CommandId --output text) \
  || { echo "could not query the host. AWS session expired? Run: aws login --profile ${AWS_PROFILE:-convoy-aws}" >&2; exit 1; }
status=Pending
for _ in $(seq 1 60); do
  sleep 2
  status=$(aws_ ssm get-command-invocation --command-id "$command" --instance-id "$instance" --query Status --output text 2>/dev/null || echo Pending)
  case "$status" in Success|Failed|Cancelled|TimedOut|DeliveryTimedOut) break ;; esac
done
[ "$status" = Success ] || { echo "live row count query: $status (SSM command $command)" >&2; exit 1; }
aws_ ssm get-command-invocation --command-id "$command" --instance-id "$instance" --query StandardOutputContent --output text \
  | sed -n 's/^NOTICE:  //p' > "$work/live.txt"

tables=$(wc -l < "$work/restored.txt")
rows=$(awk -F'|' '{s+=$3} END {print s+0}' "$work/restored.txt")
echo "tables restored: $tables ($rows rows); restore errors: $(grep -c ERROR "$work/restore.log" || true)"
grep ERROR "$work/restore.log" | sed -E 's/^psql:[^:]*:[0-9]+: //' | sort | uniq -c | sed 's/^/  /' || true # each distinct restore error, so a drill can tell expected ones from real ones
if diff "$work/live.txt" "$work/restored.txt" > "$work/diff.txt"; then
  echo "row counts: identical in all $tables tables"
else
  echo "row counts differ (rows written since the backup show here too):"; cat "$work/diff.txt"
fi
