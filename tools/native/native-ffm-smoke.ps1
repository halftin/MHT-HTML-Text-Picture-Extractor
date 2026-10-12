<#
  Build and run the actual Windows FFM sequence with product headless metadata.
  Call vcvars64.bat before running this script. OutDir must be fresh.
  Red: -MetadataDir <pre-fix snapshot> -ExpectMissingRegistration (exit 1).
  Green: default product metadata, FFM_OK for stdin and stdout (exit 0).
  All paths are supplied by the caller; no drive is assumed.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$Repo = '',
    [string]$MetadataDir = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NativeImage = '',
    [string]$TempDir = $env:TEMP,
    [int]$TimeoutSec = 30,
    [switch]$ExpectMissingRegistration
)
$ErrorActionPreference = 'Stop'
# Some hosts supply both PATH and Path; PS 5.1 Start-Process rejects that block.
$toolPath = $env:PATH
[Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
[Environment]::SetEnvironmentVariable('Path', $null, 'Process')
[Environment]::SetEnvironmentVariable('Path', $toolPath, 'Process')
if (-not $Repo) { $Repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
if (-not $MetadataDir) { $MetadataDir = Join-Path $Repo 'tools\native\headless' }
if (-not $NativeImage) { $NativeImage = Join-Path $JavaHome 'bin\native-image.cmd' }
if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
    Write-Output '[ffm-smoke] MSVC unavailable; call vcvars64.bat first'
    exit 20
}
$compiler = (Get-Command cl.exe).Source
if (-not $TempDir -or -not (Test-Path -LiteralPath $TempDir)) { throw 'Pass an existing -TempDir' }
if ($TimeoutSec -le 0) { throw 'TimeoutSec must be positive' }
$OutDir = [IO.Path]::GetFullPath($OutDir)
if (Test-Path -LiteralPath $OutDir) { throw "Use a fresh OutDir: $OutDir" }
New-Item -ItemType Directory -Path $OutDir | Out-Null
$env:TEMP = $TempDir
$env:TMP = $TempDir
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $TempDir
$classes = Join-Path $OutDir 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
$metadataCopy = Join-Path $OutDir 'metadata'
Copy-Item -LiteralPath $MetadataDir -Destination $metadataCopy -Recurse
Get-FileHash -LiteralPath (Join-Path $metadataCopy 'reachability-metadata.json') -Algorithm SHA256 |
    Format-List | Out-String | Write-Output
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    Write-Output ('COMMAND: "' + $Program + '" ' + (($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '))
    & $Program @Arguments
    $code = $LASTEXITCODE
    Write-Output ("COMMAND_EXIT=$code")
    if ($code -ne 0) { throw "Command failed: $Program (exit=$code)" }
}
Invoke-Checked (Join-Path $JavaHome 'bin\javac.exe') @('-d', $classes,
    (Join-Path $Repo 'tools\native\NativeFfmProbe.java'))
# Isolated probe classes: no automatic GUI metadata from target/classes or the app jar.
Invoke-Checked $NativeImage @('-cp', $classes, '--no-fallback', '-Ob', '-J-Xmx4G',
    ('--native-compiler-path=' + $compiler),
    '--enable-native-access=ALL-UNNAMED', '-H:+ReportExceptionStackTraces',
    ('-H:ConfigurationFileDirectories=' + $metadataCopy), ('-H:Path=' + $OutDir),
    '-H:Name=NativeFfmProbe', 'NativeFfmProbe')
$exe = Join-Path $OutDir 'NativeFfmProbe.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw 'native-image produced no executable' }
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class NativeFfmProcess {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool GetExitCodeProcess(IntPtr process, out uint code);
}
'@
$stdout = Join-Path $OutDir 'probe.stdout.log'
$stderr = Join-Path $OutDir 'probe.stderr.log'
$runArguments = '"-Djava.io.tmpdir=' + $TempDir + '"'
Write-Output ('COMMAND: "' + $exe + '" ' + $runArguments)
$proc = Start-Process -FilePath $exe -ArgumentList $runArguments -WorkingDirectory $OutDir `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
try {
    $handle = $proc.Handle
    if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
        & taskkill.exe /PID $proc.Id /T /F
        [void]$proc.WaitForExit(5000)
        throw 'FFM probe timed out'
    }
    [uint32]$code = 0
    if (-not [NativeFfmProcess]::GetExitCodeProcess($handle, [ref]$code)) {
        throw 'Cannot read native process exit status'
    }
} finally { $proc.Dispose() }
$output = [IO.File]::ReadAllText($stdout)
Write-Output $output
Get-Content -LiteralPath $stderr -Encoding UTF8
Write-Output "PROBE_EXIT=$code"
if ($ExpectMissingRegistration) {
    $expected = 'FFM_FAIL org.graalvm.nativeimage.MissingForeignRegistrationError: Cannot perform downcall with leaf type (long,int)long.'
    if ($code -ne 1 -or -not $output.Contains($expected)) { throw 'Red control did not reproduce the expected FFM error' }
    Write-Output '[ffm-smoke] RED_CONTROL_MATCH'
} elseif ($code -ne 0 -or -not $output.Contains('FFM_OK standardHandle=-11 ') -or
    -not $output.Contains('FFM_OK standardHandle=-10 ')) {
    throw 'Green control did not execute both stdio downcalls'
}
exit $code
