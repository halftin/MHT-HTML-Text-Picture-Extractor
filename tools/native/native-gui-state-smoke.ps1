<#
  Build NativeGuiStateProbe as a real native image and exercise public load/save.
  Run after Maven package, with vcvars64.bat loaded. OutDir must be fresh.
  Red: -MetadataDir <pre-fix snapshot> -ExpectMissingRegistration (exit 1).
  Green: default GUI metadata, exact saved UTF-8 bytes and reload (exit 0).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$Repo = '',
    [string]$MetadataDir = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$TempDir = $env:TEMP,
    [switch]$ExpectMissingRegistration
)
$ErrorActionPreference = 'Stop'
$toolPath = $env:PATH
[Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
[Environment]::SetEnvironmentVariable('Path', $null, 'Process')
[Environment]::SetEnvironmentVariable('Path', $toolPath, 'Process')
if (-not $Repo) { $Repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
if (-not $MetadataDir) { $MetadataDir = Join-Path $Repo 'src\main\resources\META-INF\native-image\com.mhtextractor' }
if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) { throw 'Call vcvars64.bat first' }
if (-not $TempDir -or -not (Test-Path -LiteralPath $TempDir)) { throw 'Pass an existing TempDir' }
$OutDir = [IO.Path]::GetFullPath($OutDir)
if (Test-Path -LiteralPath $OutDir) { throw 'Use a fresh OutDir' }
New-Item -ItemType Directory -Path $OutDir | Out-Null
$env:TEMP = $TempDir
$env:TMP = $TempDir
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $TempDir
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class NativeGuiStateProcess {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool GetExitCodeProcess(IntPtr process, out uint code);
}
'@
function Invoke-Recorded([string]$Label, [string]$Program, [string[]]$Arguments, [int]$TimeoutSec) {
    $quoted = ($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    Write-Output ('COMMAND: "' + $Program + '" ' + $quoted)
    $stdout = Join-Path $OutDir ($Label + '.stdout.log')
    $stderr = Join-Path $OutDir ($Label + '.stderr.log')
    $proc = Start-Process -FilePath $Program -ArgumentList $quoted -WorkingDirectory $OutDir `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    try {
        $handle = $proc.Handle
        if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
            & taskkill.exe /PID $proc.Id /T /F
            throw "$Label timed out"
        }
        [uint32]$code = 0
        if (-not [NativeGuiStateProcess]::GetExitCodeProcess($handle, [ref]$code)) { throw 'Cannot read exit status' }
    } finally { $proc.Dispose() }
    Get-Content -LiteralPath $stdout -Encoding UTF8 | Write-Host
    Get-Content -LiteralPath $stderr -Encoding UTF8 | Write-Host
    [IO.File]::WriteAllText((Join-Path $OutDir ($Label + '.exit.txt')), [string]$code)
    Write-Host "${Label}_EXIT=$code"
    $script:lastCode = $code
}
$classes = Join-Path $OutDir 'classes'
$sourceClasses = Join-Path $Repo 'target\classes'
New-Item -ItemType Directory -Path $classes | Out-Null
foreach ($file in Get-ChildItem -LiteralPath $sourceClasses -Recurse -File) {
    $relative = $file.FullName.Substring($sourceClasses.Length + 1)
    if ($relative -like 'META-INF\native-image\*') { continue }
    $dest = Join-Path $classes $relative
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($dest)) -Force | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $dest
}
$metadataCopy = Join-Path $OutDir 'metadata'
Copy-Item -LiteralPath $MetadataDir -Destination $metadataCopy -Recurse
foreach ($file in Get-ChildItem -LiteralPath $MetadataDir -File) {
    $copy = Join-Path $metadataCopy $file.Name
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    if ($hash -ne (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash) { throw 'Metadata copy differs' }
    Write-Output ("METADATA_SHA256 $($file.Name) $hash")
    # Verify bytes before touching the isolated snapshot; production files are never injected.
    (Get-Item -LiteralPath $copy).LastWriteTime = Get-Date
}
$deps = (Get-ChildItem -LiteralPath (Join-Path $Repo 'target\lib') -Filter '*.jar' -File |
    Sort-Object Name | ForEach-Object FullName) -join ';'
if (-not $deps) { throw 'Run Maven package first' }
$cp = $classes + ';' + $deps
Invoke-Recorded 'JAVAC' (Join-Path $JavaHome 'bin\javac.exe') @('-cp', $cp, '-d', $classes,
    (Join-Path $Repo 'tools\native\NativeGuiStateProbe.java')) 60
if ($lastCode -ne 0) { throw 'Probe compilation failed' }
$nativeArguments = @('-cp', $cp, 'NativeGuiStateProbe', '--no-fallback', '-Ob', '-J-Xmx4G',
    '-H:+ReportExceptionStackTraces', ('-H:ConfigurationFileDirectories=' + $metadataCopy),
    '--initialize-at-run-time=com.sun.glass,com.sun.prism,com.sun.scenario,com.sun.javafx.font,com.sun.javafx.iio,com.sun.media,com.sun.webkit,com.sun.javafx.tk.quantum,com.sun.marlin',
    '-H:+JNI', '-H:NativeLinkerOption=prism_common.lib', ('-H:Path=' + $OutDir), '-H:Name=NativeGuiStateProbe')
# A command file keeps the native-image .cmd launcher and its stderr out of PS 5.1's error stream.
$commandFile = Join-Path $OutDir 'build.cmd'
$command = '@call "' + (Join-Path $JavaHome 'bin\native-image.cmd') + '" ' +
    (($nativeArguments | ForEach-Object { '"' + $_ + '"' }) -join ' ') + "`r`n@exit /b %errorlevel%`r`n"
[IO.File]::WriteAllText($commandFile, $command, [Text.Encoding]::ASCII)
Invoke-Recorded 'NATIVE_BUILD' $env:ComSpec @('/d', '/c', $commandFile) 900
if ($lastCode -ne 0) { throw 'Native build failed; this is NOT a red control' }
$exe = Join-Path $OutDir 'NativeGuiStateProbe.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw 'Native executable missing' }
$logs = Join-Path $OutDir 'logs'
New-Item -ItemType Directory -Path $logs | Out-Null
$fixture = '{"imageModeEnabled":true,"inputFolder":"input-\u4e2d\u6587","outputFolder":"output-\u4e2d\u6587","openFolderAfterComplete":false,"extractCheckedOnly":true,"locale":"en_US"}'
[IO.File]::WriteAllText((Join-Path $logs 'gui-state.json'), $fixture, (New-Object Text.UTF8Encoding($false)))
Invoke-Recorded 'PROBE' $exe @('-Djava.io.tmpdir=' + $TempDir) 60
$output = [IO.File]::ReadAllText((Join-Path $OutDir 'PROBE.stdout.log'))
$errorOutput = [IO.File]::ReadAllText((Join-Path $OutDir 'PROBE.stderr.log'))
if ($ExpectMissingRegistration) {
    # Jackson can wrap the missing constructor registration in IOException, which
    # the production manager catches and logs before returning null.
    $productLog = [IO.File]::ReadAllText((Join-Path $logs 'mht-extractor.log'))
    Write-Output $productLog
    if ($lastCode -ne 1 -or -not $errorOutput.Contains('GUI_STATE_ASSERTION_FAILED') -or
        -not $errorOutput.Contains('java.lang.AssertionError: Loaded fields differ') -or
        -not $productLog.Contains('com.mhtextractor.uicore.GuiState') -or
        -not $productLog.Contains('configure reflection')) { throw 'Expected runtime red assertion missing' }
    Write-Output '[gui-state-smoke] RED_CONTROL_MATCH: native build succeeded; runtime assertion failed'
} elseif ($lastCode -ne 0 -or -not $output.Contains('GUI_STATE_OK')) {
    throw 'Native GUI state load/save assertions failed'
}
exit $lastCode
