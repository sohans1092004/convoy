#!/usr/bin/env bash
# Run by the Windows Scheduled Task "convoy-oci-retry" (at registration and at each logon). Every 2 minutes it asks
# Oracle's compute capacity report (capacity.py) whether the Always Free A1 shape is AVAILABLE; only then does it run
# retry-apply.sh (same guards: plan must be exactly the 1 instance; the 2 OCPU / 12 GB precondition). Stops itself and
# deletes its own task once the VM exists. Everything it uses is in the repo or the user profile (no session paths).
# Logs to .run/oci-retry.log.
set -u
here=$(cd "$(dirname "$0")" && pwd); cd "$here"
root=$(cd ../.. && pwd); log="$root/.run/oci-retry.log"; mkdir -p "$root/.run"
export PATH="$root/tools/oci-shim:/c/Users/$USERNAME/AppData/Local/Programs/DockerDesktop/resources/bin:$PATH"
py=$(command -v python3 || command -v python)
say() { echo "$(date -u +%FT%TZ) $*" >> "$log"; }
done_for_good() { say "$1: convoy-oci-retry deletes itself"; schtasks.exe //Delete //TN convoy-oci-retry //F >> "$log" 2>&1; exit 0; }

terraform state list 2>/dev/null | grep -qx oci_core_instance.lite && done_for_good "VM exists"
say "task started (capacity check every 2 min; plan/apply only when AVAILABLE)"
n=2160; [ "${RETRY_TASK_ONCE:-}" = 1 ] && n=1   # 3 days of 2-minute checks per run (1 for a manual test)
for i in $(seq 1 $n); do
  if out=$("$py" -P capacity.py 2>&1); then
    say "capacity AVAILABLE ($(echo "$out" | tr '\n' ' ')): running retry-apply"
    bash retry-apply.sh 3 >> "$log" 2>&1; rc=$?
    say "retry-apply exited $rc"
    [ $rc -eq 0 ] && done_for_good "instance created"
  elif [ $((i % 15)) -eq 1 ]; then
    say "capacity: $(echo "$out" | tr '\n' ' ')"   # logged every 30 min while unavailable
  fi
  [ $n -gt 1 ] && sleep 120
done
[ $n -gt 1 ] && say "run ended after 3 days without capacity" || say "one check done (manual test)"
