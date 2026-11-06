<#
.SYNOPSIS
    Starts (or checks) the Foyer input helper on the projector.

.DESCRIPTION
    The phone's D-pad/OK has to become a real key event to reach YouTube, Spotify and the other TV
    apps. Android 12 will not let the Foyer app inject one (INJECT_EVENTS is signature-only), and
    accessibility cannot fake a D-pad for apps that publish no node tree. So the commands go to a
    tiny helper that runs in the adb shell uid - the only identity allowed to inject without root.

    This script pushes the helper scripts, starts the daemon with setsid/nohup (so it outlives the
    adb session) and verifies it end to end. Nothing on /system is modified and nothing needs root.

    It must be re-run after a projector reboot and after `adb root`/`adb unroot` (adbd restarts). It
    survives an APK install (the listener keeps running; verified by PID across `adb install -r`).
    Foyer itself reports whether the helper is up:
        http://<projector>:8484/api/state  ->  "input": {"helper": true}

.PARAMETER Serial
    adb serial of the projector, either a USB serial or "ip:port" for network adb. The default is a
    placeholder - pass your own with -Serial.

.PARAMETER Status
    Only report whether the helper is running; change nothing.

.EXAMPLE
    .\provision-input-helper.ps1
    .\provision-input-helper.ps1 -Status
#>
[CmdletBinding()]
param(
    [string]$Serial = '192.168.1.50:5555',
    [switch]$Status
)

$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$files = @('foyer-inputd.sh', 'foyer-input-handle.sh', 'foyer-input-ping.sh', 'foyer-input-stop.sh')
$tmp = '/data/local/tmp'

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    $out = & adb -s $Serial @AdbArgs 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "adb $($AdbArgs -join ' ') failed (exit $LASTEXITCODE): $($out.Trim())" }
    return $out.Trim()
}

$state = (& adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($state -ne 'device') { throw "projector '$Serial' is not available (adb get-state: $state)" }

# The daemon execs into `nc -L -s 127.0.0.1 -p 8486`, so match both the script and the listener.
$match = 'foyer-inputd.sh|nc .*-p 8486'
$daemon = (& adb -s $Serial shell "ps -A -o PID,ARGS | grep -E '$match' | grep -v grep" 2>&1 | Out-String).Trim()
$ping = ''
if ((& adb -s $Serial shell "ls $tmp/foyer-input-ping.sh" 2>&1 | Out-String) -match 'foyer-input-ping') {
    $ping = (& adb -s $Serial shell "sh $tmp/foyer-input-ping.sh" 2>&1 | Out-String).Trim()
}

if ($Status) {
    Write-Host "projector : $Serial"
    Write-Host "daemon    : $(if ($daemon) { $daemon } else { 'not running' })"
    Write-Host "ping      : $(if ($ping) { $ping } else { 'ping script not pushed yet' })"
    exit $(if ($ping -eq 'OK ping') { 0 } else { 1 })
}

# Stop everything from a previous run. `pkill -f` is not usable in this toybox build, so the pushed
# stop script finds the PIDs by command line; a survivor would keep the old (wildcard-bound) listener
# and stop the new one from binding.
Start-Sleep -Milliseconds 200

foreach ($f in $files) {
    $src = Join-Path $here $f
    if (-not (Test-Path $src)) { throw "missing helper script: $src" }
    Invoke-Adb push $src "$tmp/$f" | Out-Null
    Invoke-Adb shell "chmod 755 $tmp/$f" | Out-Null
}

$left = (Invoke-Adb shell "sh $tmp/foyer-input-stop.sh").Trim()
if ($left -ne '0') {
    Write-Host "warning: $left old listener(s) survived the stop step" -ForegroundColor Yellow
}

# setsid + nohup: the daemon must outlive the adb shell that started it.
Invoke-Adb shell "setsid nohup /system/bin/sh $tmp/foyer-inputd.sh 8486 >/dev/null 2>&1 &" | Out-Null
Start-Sleep -Milliseconds 1200

$ping = (& adb -s $Serial shell "sh $tmp/foyer-input-ping.sh" 2>&1 | Out-String).Trim()
$daemon = (& adb -s $Serial shell "ps -A -o PID,ARGS | grep -E '$match' | grep -v grep" 2>&1 | Out-String).Trim()
$log = (& adb -s $Serial shell "tail -n 3 $tmp/foyer-inputd.log" 2>&1 | Out-String).Trim()
# Verify the bind address instead of trusting the script: 0100007F = 127.0.0.1, all zeroes = wildcard.
$tcp = (& adb -s $Serial shell "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i ':2126' | grep -w 0A" 2>&1 | Out-String).Trim()
$bind = if ($tcp -match '0{32}:2126') { 'ALL interfaces (LAN reachable, token is the only gate)' }
        elseif ($tcp -match '0100007F:2126|FFFF00000100007F:2126') { '127.0.0.1 only' }
        elseif ($tcp) { 'unrecognised' }
        else { 'not listening' }

Write-Host "projector : $Serial"
Write-Host "daemon    : $(if ($daemon) { $daemon } else { 'not running' })"
Write-Host "helper    : $ping"
Write-Host "bind      : $bind"
Write-Host "log       : $log"
if ($ping -ne 'OK ping') {
    Write-Host ''
    Write-Host 'The helper did not answer. Usual causes:' -ForegroundColor Yellow
    Write-Host '  * Foyer has never run, so it has not written input-helper.token - open Foyer once.'
    Write-Host '  * adbd was restarted (adb root/unroot) or the projector rebooted - just re-run this script.'
    exit 1
}

Write-Host ''
Write-Host "Confirm Foyer agrees:  http://$($Serial.Split(':')[0]):8484/api/state  ->  `"input`": {`"helper`": true}" -ForegroundColor Green
exit 0
