<#
.SYNOPSIS
    Flash out/recovery_adb.img onto the inactive/current recovery slot with dd (root), with backup + readback check.

.DESCRIPTION
    The Poke6 is already bootloader-unlocked (ro.boot.verifiedbootstate=orange), so AVB will not
    reject an unsigned recovery. This script follows the same dd path the project already used for
    patched boot images.

    Steps: 0) sanity 1) push image 2) dd backup recovery<slot> -> /sdcard 3) verify backup md5
           4) dd write 5) readback md5 compare 6) print how to reboot.

    Rollback: dd the backup back (command printed at the end).

.EXAMPLE
    .\tools\flash-recovery-adb.ps1 -WhatIf
    .\tools\flash-recovery-adb.ps1 -Yes
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Image = '',
    [string]$Serial = '',
    [int]$Bs = 4194304
)

$ErrorActionPreference = 'Stop'
$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$RepoRoot = Split-Path -Parent $ScriptDir
if (-not $Image) { $Image = Join-Path $RepoRoot 'out\recovery_adb.img' }
if (-not (Test-Path $Image)) { throw "image not found: $Image (run tools/mk-recovery-adb.py first)" }

$Adb = Join-Path $RepoRoot 'adb.exe'
if (-not (Test-Path $Adb)) {
    $cmd = Get-Command 'adb.exe' -ErrorAction SilentlyContinue
    if (-not $cmd) { throw 'adb.exe not found (repo root or PATH)' }
    $Adb = $cmd.Source
}
$env:TMP = (Join-Path $RepoRoot '.adbtmp')
$env:TEMP = $env:TMP
New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null
$LogDir = Join-Path $RepoRoot 'out'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$LogFile = Join-Path $LogDir ('flash-recovery-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

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

function Su([string]$cmd) {
    # single-quoted remote command, root via Magisk
    $base = @(); if ($Serial) { $base += @('-s', $Serial) }
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Adb @base shell "su -c '$cmd'" 2>&1
        $rc = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $prevEap }
    [pscustomobject]@{ rc = $rc; out = ($out -join "`n").Trim() }
}

& $Adb start-server 2>&1 | Out-Null
$t = A @('devices')
$serial0 = $null
foreach ($l in ($t.out -split "`n")) { if ($l -match '^(\S+)\s+device') { $serial0 = $Matches[1]; break } }
if (-not $serial0) { throw 'no adb device' }
if (-not $Serial) { $Serial = $serial0 }

$id = Su 'id'
if ($id.out -notmatch 'uid=0') { throw "su unavailable: $($id.out)" }
$slot = (A @('shell', 'getprop ro.boot.slot_suffix')).out.Trim()
if (-not $slot) { $slot = '_a' }
$part = "recovery$slot"
$size = (Get-Item $Image).Length
$md5 = (Get-FileHash -LiteralPath $Image -Algorithm MD5).Hash.ToLower()
$blocks = [math]::Ceiling($size / $Bs)

Say "serial=$Serial slot=$slot part=$part"
Say "image=$Image size=$size md5=$md5 blocks=$blocks bs=$Bs"

# 1) push
if (-not $PSCmdlet.ShouldProcess("$part", "push $size bytes and dd write")) { return }
$push = A @('push', $Image, '/sdcard/recovery_adb.img')
if ($push.rc -ne 0) { throw "adb push failed: $($push.out)" }
Say 'push OK'

# 2) backup current partition
$backup = "/sdcard/recovery${slot}_backup.img"
Say "backing up $part -> $backup"
$r = Su "dd if=/dev/block/by-name/$part of=$backup bs=$Bs"
Say "  $($r.out -replace "`n", ' | ')"
$rmd5 = Su "md5sum $backup"
Say "  backup md5: $($rmd5.out)"
$srcmd5 = Su "dd if=/dev/block/by-name/$part bs=$Bs | md5sum"
Say "  partition md5: $($srcmd5.out)"
$b1 = ($rmd5.out -split '\s+')[0]
$b2 = ($srcmd5.out -split '\s+')[0]
if ($b1 -ne $b2) { throw "backup md5 mismatch ($b1 vs $b2) - aborting before write" }
Say 'backup verified'

# 3) write
Say "writing $part"
$w = Su "dd if=/sdcard/recovery_adb.img of=/dev/block/by-name/$part bs=$Bs"
Say "  $($w.out -replace "`n", ' | ')"

# 4) readback first $size bytes
$rb = Su "dd if=/dev/block/by-name/$part bs=$Bs count=$blocks | md5sum"
Say "  readback md5: $($rb.out)"
$rbm = ($rb.out -split '\s+')[0]
if ($rbm -ne $md5) {
    Say "WARNING: readback md5 differs ($rbm vs $md5)."
    Say "         This can happen if dd pads the tail; verify with:"
    Say "         adb shell su -c 'dd if=/dev/block/by-name/$part bs=4096 count=$([math]::Ceiling($size/4096)) | md5sum'"
}
else { Say 'readback verified' }

Say ''
Say 'Next:'
Say "  adb reboot recovery          # should now give a root adb shell"
Say "  adb shell getprop init.svc.adbd"
Say "  adb shell 'setprop sys.usb.config fastboot'   # switch to fastbootd"
Say ''
Say 'Rollback:'
Say "  adb shell su -c 'dd if=$backup of=/dev/block/by-name/$part bs=$Bs'"
Say "  adb reboot"
Say "log: $LogFile"
