#!/usr/bin/env bash
# Create the lite host's WRITE-ONLY backup link (OCI pre-authenticated request, AnyObjectWrite, no listing) and write it
# straight into the git-ignored env file. Never printed, never in Terraform state. Uses ~/.oci/config (Python OCI SDK:
# pip install --user oci). The VM can only upload; versioning on convoy-backups keeps any overwritten object, and
# reading/restoring happens from this laptop (scripts/restore-check.sh).
#   scripts/create-backup-par.sh [env-file] [days]      default deploy/lite/convoy.env, 90 days
#   scripts/create-backup-par.sh --cleanup [env-file]   after the VM runs on the newest link: delete the older ones
# Rotation: run it again before BACKUP_PAR_EXPIRES (in the env file), copy the env file to the VM, then --cleanup.
set -euo pipefail
cd "$(dirname "$0")/.."
cleanup=0; [ "${1:-}" = --cleanup ] && { cleanup=1; shift; }
env_file=${1:-deploy/lite/convoy.env} days=${2:-90}
git check-ignore -q "$env_file" || { echo "refused: $env_file is not git-ignored"; exit 2; }
py=$(command -v python3 || command -v python)
"$py" -P - "$env_file" "$days" "$cleanup" <<'PY'
import datetime, re, sys
import oci
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
path, days, cleanup = sys.argv[1], int(sys.argv[2]), sys.argv[3] == "1"
cfg = oci_config()
client = oci.object_storage.ObjectStorageClient(cfg)
ns, bucket = client.get_namespace().data, "convoy-backups"
mine = sorted([p for p in oci.pagination.list_call_get_all_results(client.list_preauthenticated_requests, ns, bucket).data
               if p.name.startswith("convoy-lite-write-")], key=lambda p: p.time_created)
if cleanup:
    for p in mine[:-1]:
        client.delete_preauthenticated_request(ns, bucket, p.id)
    print(f"deleted {len(mine) - 1} older link(s); kept the newest (expires {mine[-1].time_expires:%Y-%m-%d})" if mine else "no links")
    sys.exit(0)
now = datetime.datetime.now(datetime.timezone.utc)
details = oci.object_storage.models.CreatePreauthenticatedRequestDetails(
    name=f"convoy-lite-write-{now:%Y%m%dT%H%M%SZ}", access_type="AnyObjectWrite",
    bucket_listing_action="Deny", time_expires=now + datetime.timedelta(days=days))
par = client.create_preauthenticated_request(ns, bucket, details).data
values = {"BACKUP_PAR_URL": f"https://objectstorage.{cfg['region']}.oraclecloud.com{par.access_uri}",
          "BACKUP_PAR_EXPIRES": f"{par.time_expires:%Y-%m-%d}"}
text = open(path, encoding="utf-8").read()
for name, value in values.items():
    text, n = re.subn(rf"^{name}=.*$", lambda _: f"{name}={value}", text, flags=re.M)
    if n == 0:
        text += ("" if text.endswith("\n") else "\n") + f"{name}={value}\n"
open(path, "w", encoding="utf-8").write(text)
print(f"write-only backup link written to {path} (AnyObjectWrite, no listing, expires {par.time_expires:%Y-%m-%d}); "
      f"{len(mine)} older link(s) exist: run --cleanup once the VM has the new one")
PY
