<#
.SYNOPSIS
    Probe Poke6 boot modes (fastboot / recovery / fastbootd) and dump what each one really offers.

.DESCRIPTION
    Non-destructive: only reboots and reads properties. Writes a log under out/.
    Use it to confirm/refute the "recovery and fastboot are crippled" claim before flashing anything.

.EXAMPLE
    .\tools\diag-boot.ps1 -Stage all
    .\tools\diag-boot.ps1 -Stage fastboot
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'fastboot', 'recovery', 'fastbootd')]
    [string]$Stage = 'all',

    [string]$Serial = '',

    [int]$TimeoutSec = 40
)

$ErrorActionPreference = 'Stop'
$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$RepoRoot = Split-Path -Parent $ScriptDir
$LogDir = Join-Path $RepoRoot 'out'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$LogFile = Join-Path $LogDir ('diag-boot-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

function Find-Tool([string]$name) {
    $candidate = Join-Path $RepoRoot $name
    if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "$name not found (repo root or PATH)"
}
$Adb = Find-Tool 'adb.exe'
$Fastboot = Find-Tool 'fastboot.exe'
$env:TMP = (Join-Path $RepoRoot '.adbtmp')
$env:TEMP = $env:TMP
New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null

function Say([string]$m) {
    $line = '{0}  {1}' -f (Get-Date -Format 'HH:mm:ss'), $m
    Write-Host $line
    Add-Content -LiteralPath $LogFile -Value $line -Encoding utf8
}

function A([string[]]$a) {
    $base = @(); if ($Serial) { $base += @('-s', $Serial) }
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Adb @base @a 2>&1
        $rc = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $prevEap }
    [pscustomobject]@{ rc = $rc; out = ($out -join "`n").Trim() }
}

function F([string[]]$a) {
    $base = @(); if ($Serial) { $base += @('-s', $Serial) }
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Fastboot @base @a 2>&1
        $rc = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $prevEap }
    [pscustomobject]@{ rc = $rc; out = ($out -join "`n").Trim() }
}

function Get-Serial {
    $t = A @('devices')
    foreach ($l in ($t.out -split "`n")) {
        if ($l -match '^(\S+)\s+device') { return $Matches[1] }
    }
    return $null
}

function Wait-Adb([string]$wantState = 'device') {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $t = A @('devices')
        foreach ($l in ($t.out -split "`n")) {
            if ($l -match '^(\S+)\s+(\S+)') {
                if (-not $Serial -or $Matches[1] -eq $Serial) { return $Matches[2] }
            }
        }
        Start-Sleep -Milliseconds 700
    }
    return $null
}

function Wait-Fastboot {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $t = F @('devices')
        if ($t.out -match '\S+\s+fastboot') { return $t.out }
        Start-Sleep -Milliseconds 700
    }
    return $null
}

function Back-To-System {
    Say 'rebooting back to Android'
    $fb = F @('devices')
    if ($fb.out -match 'fastboot') {
        F @('reboot') | Out-Null
    }
    else {
        $st = A @('devices')
        if ($st.out -match '\S+\s+(device|recovery|sideload)') {
            A @('reboot') | Out-Null
        }
        else {
            Say 'RESULT: no adb and no fastboot -> cannot reboot automatically.'
            Say '        Hold POWER ~10s to power off, then power on.'
            return
        }
    }
    Start-Sleep -Seconds 2
    for ($i = 0; $i -lt $TimeoutSec; $i++) {
        $p = A @('shell', 'getprop sys.boot_completed')
        if ($p.out.Trim() -eq '1') { Say 'Android booted'; return }
        Start-Sleep -Seconds 2
    }
    Say 'WARNING: Android did not report sys.boot_completed=1'
}

# ------------------------------------------------------------------ start
& $Adb start-server 2>&1 | Out-Null
$serial0 = Get-Serial
if (-not $serial0) { throw 'no adb device; connect Poke6 and authorize adb first' }
if (-not $Serial) { $Serial = $serial0 }
Say "serial=$Serial log=$LogFile"

Say '--- system props ---'
foreach ($k in 'ro.product.model', 'ro.build.version.release', 'ro.boot.slot_suffix',
    'ro.boot.verifiedbootstate', 'ro.boot.flash.locked', 'ro.boot.vbmeta.device_state',
    'ro.vendor.build.onyxfp', 'sys.boot_completed') {
    $v = (A @('shell', "getprop $k")).out
    Say ("  {0,-34} {1}" -f $k, $v)
}

if ($Stage -in @('all', 'fastboot')) {
    Say '=== stage: bootloader / fastboot ==='
    A @('reboot', 'bootloader') | Out-Null
    $fb = Wait-Fastboot
    if (-not $fb) {
        Say 'RESULT: fastboot device did NOT enumerate (ABL fastboot unavailable or USB driver issue)'
    }
    else {
        Say "fastboot: $fb"
        $gv = F @('getvar', 'all')
        Add-Content -LiteralPath $LogFile -Value $gv.out -Encoding utf8
        Say "getvar rc=$($gv.rc), lines=$(($gv.out -split "`n").Count) (full text in log)"
        foreach ($pat in 'is-userspace', 'max-download-size', 'slot-count', 'current-slot',
            'version-bootloader', 'secure', 'unlocked', 'battery-voltage') {
            $hit = ($gv.out -split "`n" | Where-Object { $_ -match "^$pat" }) -join ' | '
            if ($hit) { Say "  $hit" }
        }
        Say 'RESULT: fastboot works. Missing commands (flashing/set_active) are in ABL, see doc.'
    }
    Back-To-System
}

if ($Stage -in @('all', 'recovery')) {
    Say '=== stage: recovery ==='
    Say 'NOTE: stock recovery has no adb and no navigable menu; if it strands, hold POWER ~10s.'
    A @('reboot', 'recovery') | Out-Null
    $state = Wait-Adb
    if ($state) {
        Say "adb in recovery: state=$state"
        foreach ($k in 'ro.boot.mode', 'ro.boot.verifiedbootstate', 'init.svc.adbd', 'sys.usb.state') {
            Say ("  {0,-30} {1}" -f $k, (A @('shell', "getprop $k")).out)
        }
        $ls = A @('shell', 'ls -l /system/bin/recovery /system/bin/fastbootd 2>/dev/null')
        Say "  $($ls.out -replace "`n", ' | ')"
        Say 'RESULT: recovery reachable over adb (stock init.rc normally keeps adbd disabled - if you see this, it is already adb-enabled)'
    }
    else {
        Say 'RESULT: no adb in recovery. Expected for stock image: adbd is `disabled` in init.rc,'
        Say '        and with no volume keys the menu cannot be navigated -> use out/recovery_adb.img'
        $fb = Wait-Fastboot
        if ($fb) { Say "  (recovery exposed fastbootd instead: $fb)" }
    }
    Back-To-System
}

if ($Stage -in @('all', 'fastbootd')) {
    Say '=== stage: fastbootd (userspace) ==='
    A @('reboot', 'fastboot') | Out-Null
    $fb = Wait-Fastboot
    if (-not $fb) {
        Say 'RESULT: fastbootd did not enumerate'
    }
    else {
        Say "fastbootd: $fb"
        $gv = F @('getvar', 'all')
        Add-Content -LiteralPath $LogFile -Value $gv.out -Encoding utf8
        foreach ($pat in 'is-userspace', 'super-partition-name', 'current-slot', 'slot-count') {
            $hit = ($gv.out -split "`n" | Where-Object { $_ -match "^$pat" }) | Select-Object -First 3
            if ($hit) { Say "  $($hit -join ' | ')" }
        }
        Say 'RESULT: fastbootd can flash logical partitions (system/product/vendor/system_ext)'
    }
    Back-To-System
}

Say "done. log: $LogFile"
