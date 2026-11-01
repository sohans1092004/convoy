# Independent deadline for a full run when the reaper cannot be scheduled (REAPER=off): a Windows Scheduled Task that
# runs `full.sh down <run>` (destroy + teardown verification) at the deadline, whether or not any session is alive.
#   powershell -File scripts/watchdog.ps1 arm <run> [minutes=60]   arm (replaces an existing one for the run)
#   powershell -File scripts/watchdog.ps1 status <run>             armed? next run time, last result
#   powershell -File scripts/watchdog.ps1 cancel <run>             remove (after a verified full-down)
# Needs .run/watchdog.env (bash exports: PATH with terraform, TF_CLI_CONFIG_FILE, AWS_CONFIG_FILE, AWS_PROFILE).
param([Parameter(Mandatory)][ValidateSet('arm', 'status', 'cancel')][string]$Action, [Parameter(Mandatory)][string]$Run, [int]$Minutes = 60)
$name = "convoy-watchdog-$Run"
$root = Split-Path -Parent $PSScriptRoot
switch ($Action) {
  'arm' {
    $bash = 'C:\Program Files\Git\bin\bash.exe'
    $cmd = "set -a; . '$($root -replace '\','/')/.run/watchdog.env'; set +a; cd '$($root -replace '\','/')' && bash scripts/full.sh down $Run > .run/watchdog-$Run.log 2>&1"
    $act = New-ScheduledTaskAction -Execute $bash -Argument "-l -c `"$cmd`""
    $trg = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes($Minutes)
    $set = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -WakeToRun -ExecutionTimeLimit (New-TimeSpan -Hours 1)
    Register-ScheduledTask -TaskName $name -Action $act -Trigger $trg -Settings $set -Force | Out-Null
    "armed $name: full-down + verification at $((Get-Date).AddMinutes($Minutes).ToUniversalTime().ToString('HH:mm')) UTC"
  }
  'status' { $t = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue; if (-not $t) { "not armed: $name" } else { $i = $t | Get-ScheduledTaskInfo; "$name state=$($t.State) next=$($i.NextRunTime) last=$($i.LastRunTime) result=$($i.LastTaskResult)" } }
  'cancel' { Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue; "cancelled $name" }
}
