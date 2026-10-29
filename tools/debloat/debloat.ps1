<#
.SYNOPSIS
    BOOX Poke6 per-user debloat / rollback (never touches the system image).

.DESCRIPTION
    Uses only `pm disable-user --user 0` or `pm uninstall --user 0`, both reversible
    (`pm enable` / `pm install-existing`). Does not modify /system, super or AVB.
    Package list + rationale: tools/debloat-list.json.

    Requires root (Magisk su) because toggling system apps needs root.

.EXAMPLE
    .\tools\debloat.ps1 list
    .\tools\debloat.ps1 status
    .\tools\debloat.ps1 apply -Tier t1 -WhatIf
    .\tools\debloat.ps1 apply -Tier t1
    .\tools\debloat.ps1 apply -Tier t1,t2 -Uninstall
    .\tools\debloat.ps1 rollback -Tier all
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [ValidateSet('list', 'status', 'apply', 'rollback')]
    [string]$Action = 'list',

    [string[]]$Tier = @('t1'),

    # Use pm uninstall --user 0 instead of pm disable-user (stronger, still reversible)
    [switch]$Uninstall,

    [string]$Serial = '',

    [string]$ListFile = '',

    [string]$LogDir = ''
)

$ErrorActionPreference = 'Stop'

# $PSScriptRoot is empty when the script is started with a relative -File path
$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$RepoRoot = Split-Path -Parent $ScriptDir
if (-not $ListFile) { $ListFile = Join-Path $ScriptDir 'debloat-list.json' }
if (-not $LogDir) { $LogDir = Join-Path $RepoRoot 'out' }

$script:Adb = $null
$script:LogFile = $null

function Write-Log([string]$msg) {
    $line = '{0}  {1}' -f (Get-Date -Format 'HH:mm:ss'), $msg
    Write-Host $line
    if ($script:LogFile) { Add-Content -LiteralPath $script:LogFile -Value $line -Encoding utf8 }
}

function Resolve-Adb {
    $candidates = @((Join-Path $RepoRoot 'adb.exe'), 'adb.exe')
    foreach ($c in $candidates) {
        if (Test-Path $c) { return (Resolve-Path $c).Path }
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    throw 'adb not found (expected .\adb.exe or adb on PATH)'
}

function Invoke-Adb {
    param([string[]]$AdbArgs, [switch]$AllowFail)
    $base = @()
    if ($Serial) { $base += @('-s', $Serial) }
    # native stderr becomes an ErrorRecord under 2>&1 and would terminate with EAP=Stop
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $script:Adb @base @AdbArgs 2>&1
        $rc = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $prevEap }
    if ($rc -ne 0 -and -not $AllowFail) {
        throw ("adb {0} failed rc={1}: {2}" -f ($AdbArgs -join ' '), $rc, ($out -join ' '))
    }
    [pscustomobject]@{ rc = $rc; out = ($out -join "`n") }
}

function Get-SuShell([string]$cmd, [switch]$AllowFail) {
    # adb shell su -c '<cmd>'
    Invoke-Adb -AdbArgs @('shell', "su -c '$cmd'") -AllowFail:$AllowFail
}

function Assert-Ready {
    $dev = Invoke-Adb -AdbArgs @('get-state') -AllowFail
    if ($dev.out.Trim() -ne 'device') {
        throw "no adb device (state=$($dev.out.Trim())). Run adb devices and authorize debugging."
    }
    $idr = Get-SuShell 'id' -AllowFail
    if ($idr.out -notmatch 'uid=0') {
        throw "su unavailable: $($idr.out). Grant su to adb shell in Magisk."
    }
    $model = (Invoke-Adb -AdbArgs @('shell', 'getprop ro.product.model')).out.Trim()
    $fp = (Invoke-Adb -AdbArgs @('shell', 'getprop ro.vendor.build.onyxfp')).out.Trim()
    Write-Log "device: $model | $fp"
    if ($fp -and $fp -notmatch 'Poke6') {
        Write-Log "WARNING: fingerprint is not Poke6, continuing anyway."
    }
}

function Get-DisabledPackages {
    $r = Get-SuShell 'pm list packages -d' -AllowFail
    $set = @{}
    foreach ($l in ($r.out -split "`n")) {
        if ($l -match '^package:(.+)$') { $set[$Matches[1].Trim()] = $true }
    }
    return $set
}

function Get-InstalledForUser {
    $r = Get-SuShell 'pm list packages' -AllowFail
    $set = @{}
    foreach ($l in ($r.out -split "`n")) {
        if ($l -match '^package:(.+)$') { $set[$Matches[1].Trim()] = $true }
    }
    return $set
}

function Load-List {
    if (-not (Test-Path $ListFile)) { throw "list file missing: $ListFile" }
    Get-Content -LiteralPath $ListFile -Raw -Encoding utf8 | ConvertFrom-Json
}

function Show-List($db) {
    '{0,-42} {1,-4} {2,-10} {3,7}  {4}' -f 'package', 'tier', 'action', 'MB', 'note'
    '-' * 120
    foreach ($p in ($db.packages | Sort-Object tier, @{Expression = 'size_mb'; Descending = $true })) {
        '{0,-42} {1,-4} {2,-10} {3,7:N1}  {4}' -f $p.pkg, $p.tier, $p.action, $p.size_mb, $p.why
    }
    ''
    'KEEP (never touched):'
    foreach ($k in $db.keep) { '  {0,-42} {1}' -f $k.pkg, $k.why }
}

function Show-Status($db) {
    $dis = Get-DisabledPackages
    $inst = Get-InstalledForUser
    $rows = foreach ($p in $db.packages) {
        $state = 'absent'
        if ($dis.ContainsKey($p.pkg)) { $state = 'disabled' }
        elseif ($inst.ContainsKey($p.pkg)) { $state = 'enabled' }
        [pscustomobject]@{ pkg = $p.pkg; tier = $p.tier; state = $state }
    }
    $rows | Sort-Object tier, pkg | Format-Table -AutoSize | Out-String -Width 120 | Write-Host
    $rows | Group-Object tier | ForEach-Object {
        Write-Log ('{0}: {1} disabled / {2}' -f $_.Name, ($_.Group | Where-Object { $_.state -eq 'disabled' }).Count, $_.Count)
    }
}

function Invoke-Apply($db) {
    $want = $db.packages | Where-Object { $Tier -contains $_.tier }
    if (-not $want) { throw "no packages for tier(s): $($Tier -join ',')" }

    $dis = Get-DisabledPackages
    $inst = Get-InstalledForUser
    $ok = 0; $skip = 0; $fail = 0
    foreach ($p in $want) {
        if ($dis.ContainsKey($p.pkg)) { $skip++; Write-Log "skip (already disabled) $($p.pkg)"; continue }
        if (-not $inst.ContainsKey($p.pkg)) { $skip++; Write-Log "skip (not installed)    $($p.pkg)"; continue }

        if ($Uninstall) { $cmd = "pm uninstall --user 0 $($p.pkg)" }
        else { $cmd = "pm disable-user --user 0 $($p.pkg)" }

        if (-not $PSCmdlet.ShouldProcess($p.pkg, $cmd)) { continue }
        $r = Get-SuShell $cmd -AllowFail
        if ($r.out -match 'new state: (disabled|uninstalled)|Success') {
            $ok++; Write-Log "OK   $($p.pkg)   <- $($p.label)"
        }
        else {
            $fail++; Write-Log "FAIL $($p.pkg)   rc=$($r.rc)  $($r.out.Trim())"
        }
    }
    Write-Log "apply done: ok=$ok skip=$skip fail=$fail (tier=$($Tier -join ','))"
    if ($fail -gt 0) { Write-Log 'Failures are usually protected packages (systemui/settings) and can be ignored.' }
}

function Invoke-Rollback($db) {
    $dis = Get-DisabledPackages
    $inst = Get-InstalledForUser
    $ok = 0; $skip = 0; $fail = 0
    foreach ($p in ($db.packages | Sort-Object tier)) {
        if ($Tier -notcontains $p.tier -and $Tier -notcontains 'all') { continue }
        $isDis = $dis.ContainsKey($p.pkg)
        $isInst = $inst.ContainsKey($p.pkg)
        if (-not $isDis -and $isInst) { $skip++; continue }

        if ($isDis) { $cmd = "pm enable $($p.pkg)" }
        else { $cmd = "pm install-existing --user 0 $($p.pkg)" }

        if (-not $PSCmdlet.ShouldProcess($p.pkg, $cmd)) { continue }
        $r = Get-SuShell $cmd -AllowFail
        if ($r.out -match 'new state: enabled|installed for user|Success') {
            $ok++; Write-Log "OK   $($p.pkg)"
        }
        else {
            $fail++; Write-Log "FAIL $($p.pkg) rc=$($r.rc) $($r.out.Trim())"
        }
    }
    Write-Log "rollback done: ok=$ok skip=$skip fail=$fail"
}

# ------------------------------------------------------------------ main
$script:Adb = Resolve-Adb
# adb on Windows sometimes fails on %TEMP%\adb.log permissions; point it somewhere writable
$env:TMP = (Join-Path $RepoRoot '.adbtmp')
$env:TEMP = $env:TMP
New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null
& $script:Adb start-server 2>&1 | Out-Null

$db = Load-List
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Force -Path $LogDir | Out-Null }
$script:LogFile = Join-Path $LogDir ('debloat-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
Write-Log "action=$Action tier=$($Tier -join ',') uninstall=$Uninstall log=$script:LogFile"

switch ($Action) {
    'list' { Show-List $db }
    'status' { Assert-Ready; Show-Status $db }
    'apply' { Assert-Ready; Invoke-Apply $db; Show-Status $db }
    'rollback' { Assert-Ready; Invoke-Rollback $db; Show-Status $db }
}
