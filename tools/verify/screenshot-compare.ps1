# JDK-only source launcher; keep this wrapper ASCII for Windows PowerShell 5.1.
param(
    [Parameter(Mandatory = $true)][string]$Baseline,
    [Parameter(Mandatory = $true)][string]$Current,
    [string]$Threshold = '0',
    [string]$DiffDir
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
try {
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' }
            else { (Get-Command java -ErrorAction Stop).Source }
    $javaArgs = @('-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
        '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        (Join-Path $PSScriptRoot 'ScreenshotCompare.java'),
        '--baseline', $Baseline, '--current', $Current, '--threshold', $Threshold)
    if ($DiffDir) { $javaArgs += @('--diff-dir', $DiffDir) }
    & $java @javaArgs
    exit $LASTEXITCODE
} catch {
    Write-Output ("ERROR: " + $_.Exception.Message)
    exit 1
}
