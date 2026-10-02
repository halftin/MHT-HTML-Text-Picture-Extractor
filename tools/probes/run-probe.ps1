# run-probe.ps1 - compile and run a UI probe from tools/probes against this repo's build.
#
# Why this exists
#   The probes need two things that are *generated per machine* and must never be
#   hardcoded: a JavaFX module path (target/lib/*-win.jar) and the project
#   classpath (target/cp.txt). This script derives both, and if the build output is
#   missing it bootstraps Maven through the repo-local wrapper - so it works on a
#   packaging machine that has nothing installed except a JDK.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI and
# would garble non-ASCII text. Keep it that way (see CLAUDE.md).
#
# Usage
#   .\run-probe.ps1 -List                      list available probes
#   .\run-probe.ps1 UiProbe                    run a probe (default locale zh_CN)
#   .\run-probe.ps1 UiProbe -Locale en_US      run with a specific locale
#   .\run-probe.ps1 IconShowcaseProbe -Rebuild force mvn package first
#
# Output
#   Snapshots / logs land in <repo>\.claude-work\uiprobe\ (git-ignored scratch).
#   Some probes accept -Dprobe.png; this script passes it for those that do.
param(
    [Parameter(Position = 0)][string]$Probe,
    [switch]$List,
    [switch]$Rebuild,
    [string]$Locale = 'zh_CN',
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # tools\probes -> repo root
Set-Location $repo
$probeDir = Join-Path $repo 'tools\probes'
$classDir = Join-Path $probeDir 'out'
$outDir = if ($OutDir) { $OutDir } else { Join-Path $repo '.claude-work\uiprobe' }

if ($List -or -not $Probe) {
    Write-Output "Available probes in $probeDir :"
    Get-ChildItem $probeDir -Filter *.java | Sort-Object Name | ForEach-Object { Write-Output ("  " + $_.BaseName) }
    if (-not $Probe) {
        Write-Output ""
        Write-Output "Usage: .\run-probe.ps1 <ProbeName> [-Locale zh_CN|en_US] [-Rebuild] [-OutDir <dir>]"
    }
    exit 0
}

$src = Join-Path $probeDir ($Probe + '.java')
if (-not (Test-Path $src)) { Write-Output "[ERROR] no such probe: $src"; exit 1 }

function Find-Jdk {
    if ($env:BUILD_JAVA_HOME -and (Test-Path "$env:BUILD_JAVA_HOME\bin\javac.exe")) { return $env:BUILD_JAVA_HOME }
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { return $env:JAVA_HOME }
    $onPath = & where.exe javac.exe 2>$null | Select-Object -First 1
    if ($onPath) { return (Split-Path -Parent (Split-Path -Parent $onPath)) }
    foreach ($root in @("$env:ProgramFiles\BellSoft", "$env:LOCALAPPDATA\Programs\BellSoft",
                        "$env:USERPROFILE\jdk", 'C:\Java', 'D:\Java', 'D:\install')) {
        if (Test-Path $root) {
            foreach ($d in Get-ChildItem $root -Directory -ErrorAction SilentlyContinue) {
                if (Test-Path "$($d.FullName)\bin\javac.exe") { return $d.FullName }
            }
        }
    }
    return $null
}

function Find-Maven {
    if ($env:MAVEN_CMD -and (Test-Path $env:MAVEN_CMD)) { return $env:MAVEN_CMD }
    $wrapper = Join-Path $repo 'mvnw.cmd'
    if (Test-Path $wrapper) { return $wrapper }
    $onPath = & where.exe mvn.cmd 2>$null | Select-Object -First 1
    if ($onPath) { return $onPath }
    $cached = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists" -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue |
              Select-Object -First 1
    if ($cached) { return $cached.FullName }
    return $null
}

$jdk = Find-Jdk
if (-not $jdk) { Write-Output "[ERROR] no JDK found - set BUILD_JAVA_HOME to a JDK 25 directory"; exit 1 }
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'

$needBuild = $Rebuild -or -not (Test-Path (Join-Path $repo 'target\classes')) -or -not (Test-Path (Join-Path $repo 'target\lib'))
if ($needBuild) {
    $mvn = Find-Maven
    if (-not $mvn) { Write-Output "[ERROR] no Maven found - install Maven, or keep the repo-local mvnw.cmd"; exit 1 }
    Write-Output "[build] `"$mvn`" -q -DskipTests package   (this may take a minute)"
    & cmd.exe /c "`"$mvn`" -q -DskipTests package"
    if ($LASTEXITCODE -ne 0) { Write-Output "[ERROR] mvn package failed, exit=$LASTEXITCODE"; exit $LASTEXITCODE }
}

$cpFile = Join-Path $repo 'target\cp.txt'
if (-not (Test-Path $cpFile)) {
    $mvn = Find-Maven
    if (-not $mvn) { Write-Output "[ERROR] no Maven found - cannot generate target/cp.txt"; exit 1 }
    Write-Output "[deps] generating target/cp.txt"
    & cmd.exe /c "`"$mvn`" -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $cpFile)) { Write-Output "[ERROR] could not generate target/cp.txt"; exit 1 }
}
$deps = (Get-Content $cpFile -Raw).Trim()

$modulePath = (Get-ChildItem (Join-Path $repo 'target\lib') -Filter 'javafx-*-win.jar' -ErrorAction SilentlyContinue |
               ForEach-Object { $_.FullName }) -join ';'
if (-not $modulePath) { Write-Output "[ERROR] target\lib has no javafx-*-win.jar - run a full package first"; exit 1 }

New-Item -ItemType Directory -Force -Path $classDir | Out-Null
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Output "[javac] $Probe"
& $javac --module-path $modulePath --add-modules javafx.controls,javafx.fxml `
    -cp "$repo\target\classes;$deps" -d $classDir $src
if ($LASTEXITCODE -ne 0) { Write-Output "[ERROR] compile failed, exit=$LASTEXITCODE"; exit $LASTEXITCODE }

$parts = $Locale.Split('_')
Write-Output "[java] $Probe  locale=$Locale  out=$outDir"
# -Dprobe.locale is the property the probes ACTUALLY read (every probe does
#   System.getProperty("probe.locale", <its own default>)
# ). Passing only -Duser.language / -Duser.country is silently ignored by them - and because
# UiProbe defaults to en_US while the others default to zh_CN, an "en" run and a "zh" run both
# come out ENGLISH for UiProbe without any error. Measured 2026-10-02: UiProbe -Locale zh_CN
# printed "locale=en_US". Keep both forms: -Duser.* still drives ResourceBundle.getBundle
# defaults for code that reads Locale.getDefault().
& $java --module-path $modulePath --add-modules javafx.controls,javafx.fxml `
    "-Dprobe.locale=$Locale" `
    "-Duser.language=$($parts[0])" "-Duser.country=$($parts[1])" `
    "-Dprobe.png=$outDir\probe.png" `
    -cp "$classDir;$repo\target\classes;$deps" $Probe
$rc = $LASTEXITCODE
Write-Output "[probe] exit=$rc   snapshots/logs -> $outDir"
exit $rc
