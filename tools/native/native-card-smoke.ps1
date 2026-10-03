<#
  native-card-smoke.ps1 - assert that the native image can actually PAINT a centered reminder card.

  Why this exists (2026-10-03, real user-visible incident):
    The native image was missing the reflection registrations for the JavaFX effect peers
    (com.sun.scenario.effect.impl.prism.PrMergePeer / PPSLinearConvolveShadowPeer). Reminder
    cards carry a drop shadow, so every centered card failed to paint while its ModalPane kept
    blocking input: "no card + whole window black + cannot interact + cannot exit". The JVM was
    completely unaffected, so the normal regression (unit tests, GUI automation, probes) could
    NOT see it. Only a native run of the real assembly catches it.

  What it does:
    1. compiles tools/probes/AppCardProbe.java (javac, JavaFX on --module-path)
    2. builds that probe into a native image with the same options as build-native.cmd
    3. runs it; the probe boots the REAL assembly, auto-shows a confirm card and measures it
    4. exits 0 only if every probe check passed

  Usage (standalone):
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\native\native-card-smoke.ps1
  Inside build-native.cmd the already-resolved env is passed in via -JavaHome / -NativeImage.

  Exit codes: 0 = card renders, 1 = smoke failed, 20 = MSVC (link.exe) not available.
#>
[CmdletBinding()]
param(
    [string]$Repo = "",
    [string]$OutDir = "",
    [string]$JavaHome = $env:BUILD_JAVA_HOME,
    [string]$NativeImage = $env:NATIVE_IMAGE,
    [string]$JavaFxLib = "",
    [string]$JfxDlls = "",
    [string]$PrismCommonLib = "",
    [switch]$SkipNativeBuild,
    [int]$TimeoutSec = 180
)

$ErrorActionPreference = 'Stop'

function Say([string]$m) { Write-Host $m }

if ([string]::IsNullOrWhiteSpace($Repo)) {
    $Repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    if (-not (Test-Path (Join-Path $Repo 'build-native.cmd'))) { $Repo = (Get-Location).Path }
}
if (-not (Test-Path (Join-Path $Repo 'build-native.cmd'))) { Say "[smoke] [ERROR] cannot locate repo root (no build-native.cmd); pass -Repo"; exit 1 }
if ([string]::IsNullOrWhiteSpace($OutDir))    { $OutDir    = Join-Path $Repo '.claude-work\native-card-smoke' }
if ([string]::IsNullOrWhiteSpace($JavaFxLib)) { $JavaFxLib = Join-Path $Repo 'target\lib' }
if ([string]::IsNullOrWhiteSpace($JfxDlls))   { $JfxDlls   = Join-Path $Repo 'target\jfx-dlls' }

Say "[smoke] repo       = $Repo"
Say "[smoke] outDir     = $OutDir"

if (-not $SkipNativeBuild -and -not (Get-Command link.exe -ErrorAction SilentlyContinue)) {
    Say "[smoke] [ERROR] link.exe not on PATH - run inside build-native.cmd, or call vcvars64.bat first."
    exit 20
}

if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    foreach ($c in @($env:BUILD_JAVA_HOME, $env:JAVA_HOME, $env:GRAALVM_HOME)) {
        if ($c -and (Test-Path (Join-Path $c 'bin\javac.exe'))) { $JavaHome = $c; break }
    }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) { Say "[smoke] [ERROR] no JDK with javac found (set JAVA_HOME / BUILD_JAVA_HOME)"; exit 1 }
Say "[smoke] buildJdk   = $JavaHome"

if ([string]::IsNullOrWhiteSpace($NativeImage) -or -not (Test-Path $NativeImage)) {
    $cands = @()
    if ($env:GRAALVM_HOME) { $cands += (Join-Path $env:GRAALVM_HOME 'bin\native-image.cmd') }
    if ($env:JAVA_HOME)    { $cands += (Join-Path $env:JAVA_HOME 'bin\native-image.cmd') }
    $cands += (Join-Path $JavaHome 'bin\native-image.cmd')
    foreach ($c in $cands) { if (Test-Path $c) { $NativeImage = $c; break } }
}
if (-not $SkipNativeBuild -and (-not $NativeImage -or -not (Test-Path $NativeImage))) { Say "[smoke] [ERROR] native-image not found (set GRAALVM_HOME or pass -NativeImage)"; exit 1 }
if ($NativeImage) { Say "[smoke] nativeImage= $NativeImage" }

$probeSrc = Join-Path $Repo 'tools\probes\AppCardProbe.java'
$depsFile = Join-Path $Repo 'target\cp.txt'
foreach ($p in @($probeSrc, (Join-Path $Repo 'target\classes'), $JavaFxLib)) {
    if (-not (Test-Path $p)) { Say "[smoke] [ERROR] missing input: $p  (run a full package first)"; exit 1 }
}
# dependency classpath: prefer target/lib (build-native always extracts it there); cp.txt is only a fallback
$libJars = (Get-ChildItem (Join-Path $Repo 'target\lib') -Filter '*.jar' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }) -join ';'
if ([string]::IsNullOrWhiteSpace($libJars)) {
    if (-not (Test-Path $depsFile)) { Say "[smoke] [ERROR] neither target/lib jars nor target/cp.txt available"; exit 1 }
    $libJars = (Get-Content -LiteralPath $depsFile -Raw).Trim()
}

$probeOut = Join-Path $Repo 'tools\probes\out'
if (-not (Test-Path $probeOut)) { New-Item -ItemType Directory -Path $probeOut -Force | Out-Null }
$mp = (Get-ChildItem $JavaFxLib -Filter 'javafx-*-win.jar' | ForEach-Object { $_.FullName }) -join ';'
if ([string]::IsNullOrWhiteSpace($mp)) { Say "[smoke] [ERROR] no javafx-*-win.jar in $JavaFxLib"; exit 1 }
$cp = $probeOut + ';' + (Join-Path $Repo 'target\classes') + ';' + $libJars

Say "[smoke] compiling AppCardProbe ..."
& (Join-Path $JavaHome 'bin\javac.exe') -nowarn --module-path $mp --add-modules javafx.controls,javafx.fxml -cp $cp -d $probeOut $probeSrc
if ($LASTEXITCODE -ne 0) { Say "[smoke] [ERROR] javac failed (exit=$LASTEXITCODE)"; exit 1 }

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$exe = Join-Path $OutDir 'AppCardProbeSmoke.exe'
if (-not $SkipNativeBuild) {
    if (Test-Path $exe) { Remove-Item -LiteralPath $exe -Force }
    $prismOpt = @()
    if ($PrismCommonLib -and (Test-Path $PrismCommonLib)) { $prismOpt = @(('-H:NativeLinkerOption=' + $PrismCommonLib)) }
    $niArgs = @(
        '-cp', $cp,
        'AppCardProbe',
        '--no-fallback',
        ('-H:Path=' + $OutDir),
        '-H:Name=AppCardProbeSmoke',
        '-H:+JNI',
        '-H:+ReportExceptionStackTraces',
        '--enable-all-security-services',
        '--enable-http', '--enable-https',
        '--enable-url-protocols=file,http,https',
        '--initialize-at-run-time=com.sun.glass,com.sun.prism,com.sun.scenario,com.sun.javafx.font,com.sun.javafx.iio,com.sun.media,com.sun.webkit,com.sun.javafx.tk.quantum,com.sun.marlin',
        ('-Djava.library.path=' + $JfxDlls),
        '-J-Xmx4G',
        '-Ob'
    ) + $prismOpt
    Say "[smoke] native-image build (1-3 min) ..."
    & $NativeImage @niArgs
    if ($LASTEXITCODE -ne 0) { Say "[smoke] [ERROR] native-image failed (exit=$LASTEXITCODE)"; exit 1 }
    if (-not (Test-Path $exe)) { Say "[smoke] [ERROR] native-image reported success but no $exe"; exit 1 }
}

if (Test-Path $JfxDlls) { Copy-Item (Join-Path $JfxDlls '*.dll') -Destination $OutDir -Force }

$outLog = Join-Path $OutDir 'smoke-output.log'
Say "[smoke] running probe ..."
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $exe
$psi.WorkingDirectory = $OutDir
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$proc = [System.Diagnostics.Process]::Start($psi)
$stdout = $proc.StandardOutput.ReadToEnd()
$stderr = $proc.StandardError.ReadToEnd()
$timedOut = -not $proc.WaitForExit($TimeoutSec * 1000)
if ($timedOut) { try { $proc.Kill() } catch { } }
$code = if ($timedOut) { 1 } else { $proc.ExitCode }
if ($null -eq $code) { $code = 1 }
Set-Content -LiteralPath $outLog -Value $stdout -Encoding UTF8
Set-Content -LiteralPath ($outLog + '.err') -Value $stderr -Encoding UTF8
if ($timedOut) { Say "[smoke] [ERROR] probe timed out after $TimeoutSec s - the native image hung (card render deadlock?)"; exit 1 }
if (Test-Path $outLog) {
    Get-Content -LiteralPath $outLog -Encoding UTF8 |
        Where-Object { $_ -match '^\[(PASS|FAIL)\]' -or $_ -match '^RESULTS:' -or $_ -match '^\[probe\]' } |
        ForEach-Object { Say "    $_" }
}
if (Test-Path ($outLog + '.err')) {
    $errLines = Get-Content -LiteralPath ($outLog + '.err') -Encoding UTF8 |
        Where-Object { $_ -match 'Exception|Register not found|Could not create peer' }
    if ($errLines) { $errLines | Select-Object -First 8 | ForEach-Object { Say "    [stderr] $_" } }
}

if ($code -ne 0) {
    Say "[smoke] FAILED (exit=$code) - the native image cannot paint a centered reminder card."
    Say "[smoke]         typical cause: missing JavaFX effect-peer reflection metadata"
    Say "[smoke]         (PrMergePeer / PPSLinearConvolveShadowPeer). Collect with the tracing agent"
    Say "[smoke]         and merge append-only into reachability-metadata.json."
    Say "[smoke]         full log: $outLog"
    exit 1
}
Say "[smoke] PASSED - the native image paints a centered confirm card."
exit 0
