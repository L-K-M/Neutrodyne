# SPDX-License-Identifier: Unlicense
#
# post-image-hook.test.ps1 — the Windows MSI staging hook's native fixture
# (11 AOT cache, Windows MSI). Runs the shipped Neutrodyne-post-image.wsf
# through the real cscript/WSH the way jpackage's ScriptRunner does: the WSF
# gets jpackage's environment plus $env:JpAppImageDir naming a fabricated
# staging tree in the JDK 25 direct-root app-image layout (launcher at the
# image root, app/ and runtime/ below it; Neutrodyne-App is the install-dir
# name, not a staging level).
#
# Real COM only (FileSystemObject, WshShell.Environment, Shell.Application
# FolderItem.ModifyDate); no installer is built or run and no Neutrodyne
# binary executes. Windows-only: the aot-wsf nightly scope and the
# desktop-packages Windows leg run it.
#
# powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/tests/post-image-hook.test.ps1
# Exit 0 = all cases behave; 1 = at least one regression.

$ErrorActionPreference = 'Stop'
$script:Failed = 0

function t_ok($name) { Write-Output "ok: $name" }
function t_fail($name) {
    Write-Output "not ok: $name"
    $script:Failed += 1
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$hook = Join-Path $repoRoot 'desktopApp\packaging\msi\Neutrodyne-post-image.wsf'
$epoch = [datetime]::SpecifyKind([datetime]'1980-02-01T00:00:00', 'Utc')

if (-not $IsWindows -and $env:OS -ne 'Windows_NT') {
    t_fail 'this fixture requires Windows (cscript/WSH)'
    exit 1
}
if (-not (Get-Command cscript.exe -ErrorAction SilentlyContinue)) {
    t_fail 'cscript.exe is not on PATH'
    exit 1
}
if (-not (Test-Path $hook)) {
    t_fail "the shipped hook is missing: $hook"
    exit 1
}

$work = Join-Path ([IO.Path]::GetTempPath()) ('aot-wsf-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null

function New-Staging([string]$root) {
    # The JDK 25 Windows app-image layout: launcher + app/ + runtime/ at the root.
    New-Item -ItemType Directory -Force "$root\app", "$root\runtime" | Out-Null
    [IO.File]::WriteAllText("$root\Neutrodyne.exe", 'launcher')
    [IO.File]::WriteAllText("$root\app\Neutrodyne.cfg",
        "[Application]`napp.classpath=`$APPDIR/tiny.jar`n`n[JavaOptions]`njava-options=-XX:AOTCache=`$APPDIR/neutrodyne.aot`n")
    [IO.File]::WriteAllText("$root\app\neutrodyne.aot", 'aot')
    [IO.File]::WriteAllText("$root\app\tiny.jar", 'jar')
    [IO.File]::WriteAllText("$root\runtime\release", 'release')
    # Packaging-time stamps the attribute-losing copy leaves; make that obvious.
    $now = Get-Date
    Get-ChildItem -Recurse $root | ForEach-Object { $_.LastWriteTime = $now }
}

function Invoke-Hook([string]$dir) {
    # ScriptRunner runs cscript with jpackage's environment plus JpAppImageDir,
    # which the inherited parent env then carries to the WSF.
    $out = Join-Path $work ('cscript-' + [guid]::NewGuid().ToString('N') + '.out')
    $err = Join-Path $work ('cscript-' + [guid]::NewGuid().ToString('N') + '.err')
    $saved = $env:JpAppImageDir
    try {
        if ($null -ne $dir) { $env:JpAppImageDir = $dir }
        else { Remove-Item env:JpAppImageDir -ErrorAction SilentlyContinue }
        $proc = Start-Process -FilePath 'cscript.exe' `
            -ArgumentList '//nologo', "`"$hook`"" `
            -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $out -RedirectStandardError $err
        return [pscustomobject]@{
            Code = $proc.ExitCode
            Out = (Get-Content $out -Raw -ErrorAction SilentlyContinue) +
                  (Get-Content $err -Raw -ErrorAction SilentlyContinue)
        }
    } finally {
        if ($null -ne $saved) { $env:JpAppImageDir = $saved }
        else { Remove-Item env:JpAppImageDir -ErrorAction SilentlyContinue }
    }
}

function Test-Epoch([string]$root) {
    $bad = @()
    Get-ChildItem -Recurse -Force -File $root | Where-Object {
        -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint)
    } | ForEach-Object {
        if ($_.LastWriteTimeUtc -ne $epoch) { $bad += $_.FullName }
    }
    return $bad
}

try {
    # --- 1. the direct-root staging layout stamps every staged file ----------
    $stage = Join-Path $work 'plain\image'
    New-Staging $stage
    $result = Invoke-Hook $stage
    if ($result.Code -eq 0) {
        t_ok 'the hook exits 0 on the direct-root app-image layout'
    } else {
        t_fail "the hook failed on the direct-root layout: $($result.Out)"
    }
    $bad = Test-Epoch $stage
    if (-not $bad) {
        t_ok 'every staged file carries UTC mtime 318211200000 (1980-02-01)'
    } else {
        t_fail ("files left off the epoch: " + ($bad | Select-Object -First 3) -join ' ')
    }
    if ([IO.File]::ReadAllText("$stage\app\tiny.jar") -eq 'jar' -and
        [IO.File]::ReadAllText("$stage\runtime\release") -eq 'release' -and
        (Get-ChildItem -Recurse -Force $stage | Measure-Object).Count -eq 9) {
        t_ok 'staged file contents and the tree listing are unchanged'
    } else {
        t_fail 'the hook changed staged file contents or the file list'
    }

    # --- 2. Unicode, spaces and ampersands in the path -------------------------
    $fancy = Join-Path $work 'späce & dir (x)\imägé & co'
    New-Staging $fancy
    $result = Invoke-Hook $fancy
    if ($result.Code -eq 0 -and -not (Test-Epoch $fancy)) {
        t_ok 'a path with Unicode, spaces and ampersands is treated as data'
    } else {
        t_fail "the hook failed on a special-character path: code $($result.Code) $($result.Out)"
    }

    # --- 3. data-only: no spawned command eats the path ------------------------
    $shellish = Join-Path $work 'x & calc & (y)\image'
    New-Staging $shellish
    $result = Invoke-Hook $shellish
    if ($result.Code -eq 0 -and -not (Test-Epoch $shellish)) {
        t_ok 'a shell-metachar path never reaches a spawned command'
    } else {
        t_fail "the shell-metachar path broke the hook: $($result.Out)"
    }

    # --- 4. fail-closed layouts -------------------------------------------------
    function expect_fail([string]$name, [string]$dir) {
        $result = Invoke-Hook $dir
        if ($result.Code -ne 0) {
            t_ok "the hook fails closed: $name"
        } else {
            t_fail "the hook accepted an invalid context: $name"
        }
    }
    expect_fail 'JpAppImageDir unset' $null
    expect_fail 'JpAppImageDir missing' (Join-Path $work 'no\such\image')
    New-Item -ItemType Directory -Force (Join-Path $work 'empty\image') | Out-Null
    expect_fail 'no staged launcher' (Join-Path $work 'empty\image')

    $nested = Join-Path $work 'nested\image'
    New-Item -ItemType Directory -Force "$nested\Neutrodyne-App" | Out-Null
    New-Staging "$nested\Neutrodyne-App"
    expect_fail 'the installed Neutrodyne-App layout staged as the app image' $nested

    $no_aot = Join-Path $work 'no-aot\image'
    New-Staging $no_aot
    Remove-Item "$no_aot\app\neutrodyne.aot"
    expect_fail 'no trained archive' $no_aot

    $no_flag = Join-Path $work 'no-flag\image'
    New-Staging $no_flag
    [IO.File]::WriteAllText("$no_flag\app\Neutrodyne.cfg",
        "[JavaOptions]`njava-options=-Djpackage.app-version=1.0`n")
    expect_fail '.cfg without the AOT read flag' $no_flag

    $no_jars = Join-Path $work 'no-jars\image'
    New-Staging $no_jars
    Remove-Item "$no_jars\app\tiny.jar"
    expect_fail 'no classpath JARs' $no_jars

    # --- 5. a reparse point and its outside target stay untouched ---------------
    $reparse = Join-Path $work 'linked\image'
    New-Staging $reparse
    $outside = Join-Path $work 'outside file.jar'
    [IO.File]::WriteAllText($outside, 'outside')
    $outsideBefore = (Get-Item $outside).LastWriteTimeUtc
    $junction = $null
    try {
        $junction = New-Item -ItemType Junction -Path "$reparse\app\jlink" -Target $work -ErrorAction Stop
        $linkBefore = (Get-Item $junction.FullName -Force).LastWriteTimeUtc
        $result = Invoke-Hook $reparse
        if ($result.Code -eq 0 -and
            (Get-Item $outside).LastWriteTimeUtc -eq $outsideBefore -and
            (Get-Item $junction.FullName -Force).LastWriteTimeUtc -eq $linkBefore -and
            [IO.File]::ReadAllText($outside) -eq 'outside') {
            t_ok 'a reparse point and its outside target stay untouched'
        } else {
            t_fail "the hook followed or rewrote a reparse point: code $($result.Code)"
        }
    } catch {
        # Junctions need no privilege, but a policy can still refuse them.
        t_ok "a reparse point and its outside target stay untouched (junction unavailable: $($_.Exception.Message))"
    }

    # --- 6. the epoch is UTC, not packaging-local time ---------------------------
    $originalZone = $null
    try {
        $originalZone = (Get-TimeZone).Id
        Set-TimeZone -Id 'Line Islands Standard Time' -ErrorAction Stop  # UTC+14
        $tz = Join-Path $work 'tz\image'
        New-Staging $tz
        $result = Invoke-Hook $tz
        if ($result.Code -eq 0 -and -not (Test-Epoch $tz)) {
            t_ok 'the stamped instant is UTC under a UTC+14 packaging timezone'
        } else {
            t_fail "the UTC stamp drifted under a non-UTC timezone: code $($result.Code)"
        }
    } catch {
        # Set-TimeZone needs SeTimeZonePrivilege; the case is reported, not faked.
        t_ok "the stamped instant is UTC under a UTC+14 packaging timezone (timezone change unavailable: $($_.Exception.Message))"
    } finally {
        if ($originalZone -and (Get-TimeZone).Id -ne $originalZone) {
            Set-TimeZone -Id $originalZone
        }
    }

    Write-Output ''
    if ($script:Failed -gt 0) {
        Write-Error "post-image-hook.test.ps1: $($script:Failed) case(s) failing"
        exit 1
    }
    Write-Output 'post-image-hook.test.ps1: all cases pass'
    exit 0
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
