<#
  Compile NativePreviewCacheProbe into a REAL native image, then load JVM-written caches.
  Like native-card-smoke.ps1, JVM success alone is not acceptance evidence.
  Use -MetadataDir to select a pre-fix snapshot for the red control without editing sources.
  -OutDir must be a fresh directory; target/classes metadata is excluded from the classpath.
  Requires a packaged target/lib, vcvars64.bat environment, and agent-generated fixtures.
  Exit: 0 = both list shapes pass, 1 = probe/build failure, 20 = MSVC unavailable.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [Parameter(Mandatory = $true)][string]$FixtureRoot,
    [string]$Repo = '',
    [string]$MetadataDir = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NativeImage = '',
    [string]$TempDir = $env:TEMP,
    [int]$TimeoutSec = 60
)
$ErrorActionPreference = 'Stop'
if (-not $Repo) { $Repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
if (-not (Get-Command link.exe -ErrorAction SilentlyContinue)) {
    Write-Output '[preview-smoke] MSVC unavailable; call vcvars64.bat first'
    exit 20
}
if (-not $MetadataDir) { $MetadataDir = Join-Path $Repo 'src\main\resources\META-INF\native-image\com.mhtextractor' }
if (-not $NativeImage) { $NativeImage = Join-Path $JavaHome 'bin\native-image.cmd' }
if (-not $TempDir -or -not (Test-Path -LiteralPath $TempDir)) { throw 'Pass an existing -TempDir' }
if ($TimeoutSec -le 0) { throw 'TimeoutSec must be positive' }
$OutDir = [IO.Path]::GetFullPath($OutDir)
if (Test-Path -LiteralPath $OutDir) { throw "Use a fresh OutDir: $OutDir" }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$env:TEMP = $TempDir
$env:TMP = $TempDir
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $TempDir
$classes = Join-Path $OutDir 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
# Copy classes/resources, never the automatic production native-image configuration.
$sourceClasses = Join-Path $Repo 'target\classes'
foreach ($file in Get-ChildItem -LiteralPath $sourceClasses -Recurse -File) {
    $relative = $file.FullName.Substring($sourceClasses.Length + 1)
    if ($relative -like 'META-INF\native-image\*') { continue }
    $dest = Join-Path $classes $relative
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($dest)) -Force | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $dest
}
$metadataCopy = Join-Path $OutDir 'metadata'
Copy-Item -LiteralPath $MetadataDir -Destination $metadataCopy -Recurse
$deps = (Get-ChildItem -LiteralPath (Join-Path $Repo 'target\lib') -Filter '*.jar' -File |
    Sort-Object Name | ForEach-Object FullName) -join ';'
if (-not $deps) { throw 'Missing target/lib; run Maven package first' }
$cp = $classes + ';' + $deps
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    Write-Output ('COMMAND: "' + $Program + '" ' + (($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '))
    & $Program @Arguments
    $code = $LASTEXITCODE
    Write-Output ("COMMAND_EXIT=$code")
    if ($code -ne 0) { throw "Command failed: $Program (exit=$code)" }
}
Invoke-Checked (Join-Path $JavaHome 'bin\javac.exe') @('-cp', $cp, '-d', $classes,
    (Join-Path $Repo 'tools\native\NativePreviewCacheProbe.java'))
$exe = Join-Path $OutDir 'NativePreviewCacheProbe.exe'
# Mirror build-native.cmd: the NIK static JavaFX libs need prism_common.lib added explicitly at link
# time (its _overrideNativeWindowHandle implementation lives there) and JNI must stay enabled.
$prismCommonLib = Join-Path $JavaHome 'lib\static\windows-amd64\prism_common.lib'
$linkOpt = if (Test-Path -LiteralPath $prismCommonLib) { @('-H:+JNI', '-H:NativeLinkerOption=prism_common.lib') } else { @('-H:+JNI') }
Invoke-Checked $NativeImage (@('-cp', $cp, 'NativePreviewCacheProbe', '--no-fallback', '-Ob',
    '-J-Xmx4G', '-H:+ReportExceptionStackTraces', ('-H:ConfigurationFileDirectories=' + $metadataCopy),
    '--initialize-at-run-time=com.sun.glass,com.sun.prism,com.sun.scenario,com.sun.javafx.font,com.sun.javafx.iio,com.sun.media,com.sun.webkit,com.sun.javafx.tk.quantum,com.sun.marlin') + $linkOpt + @(('-H:Path=' + $OutDir), '-H:Name=NativePreviewCacheProbe'))
if (-not (Test-Path -LiteralPath $exe)) { throw 'native-image produced no executable' }
# Process.ExitCode is unreliable on this host; retain the handle and read Win32 status.
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class NativePreviewCacheProcess {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool GetExitCodeProcess(IntPtr process, out uint code);
}
'@
$logging = Join-Path $OutDir 'probe-logback.xml'
[IO.File]::WriteAllText($logging, '<configuration><root level="OFF" /></configuration>')
$failed = 0
foreach ($shape in @('nonempty','empty')) {
    $runArgs = @(("-Djava.io.tmpdir=$TempDir"), ("-Dlogback.configurationFile=$logging"),
        (Join-Path $FixtureRoot ('fixture-' + $shape)), $shape)
    $quoted = ($runArgs | ForEach-Object { '"' + $_ + '"' }) -join ' '
    Write-Output ('COMMAND: "' + $exe + '" ' + $quoted)
    $stdout = Join-Path $OutDir ($shape + '.stdout.log')
    $stderr = Join-Path $OutDir ($shape + '.stderr.log')
    $proc = Start-Process -FilePath $exe -ArgumentList $quoted -WorkingDirectory $OutDir `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    try {
        $handle = $proc.Handle
        if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
            & taskkill.exe /PID $proc.Id /T /F
            [void]$proc.WaitForExit(5000)
            throw "Probe timed out: $shape"
        }
        [uint32]$code = 0
        if (-not [NativePreviewCacheProcess]::GetExitCodeProcess($handle, [ref]$code)) {
            throw 'Cannot read native process exit status'
        }
    } finally { $proc.Dispose() }
    Get-Content -LiteralPath $stdout -Encoding UTF8
    Get-Content -LiteralPath $stderr -Encoding UTF8
    Write-Output ("PROBE_${shape}_EXIT=$code")
    if ($code -ne 0) { $failed++ }
}
Write-Output ("[preview-smoke] RESULTS: " + (2 - $failed) + " passed, $failed failed")
if ($failed) { exit 1 }
exit 0
