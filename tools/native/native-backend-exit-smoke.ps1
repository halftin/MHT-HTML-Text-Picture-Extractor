<# Run a built headless backend through --once and --serve with EOF; Win32 exit codes. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BackendExe,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$TempDir = $env:TEMP,
    [int]$TimeoutSec = 30
)
$ErrorActionPreference = 'Stop'
$toolPath = $env:PATH
[Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
[Environment]::SetEnvironmentVariable('Path', $null, 'Process')
[Environment]::SetEnvironmentVariable('Path', $toolPath, 'Process')
$BackendExe = [IO.Path]::GetFullPath($BackendExe)
$OutDir = [IO.Path]::GetFullPath($OutDir)
if (-not (Test-Path -LiteralPath $BackendExe)) { throw 'Backend executable missing' }
if (Test-Path -LiteralPath $OutDir) { throw 'Use a fresh OutDir' }
if (-not $TempDir -or -not (Test-Path -LiteralPath $TempDir)) { throw 'Pass an existing -TempDir' }
if ($TimeoutSec -le 0) { throw 'TimeoutSec must be positive' }
$env:TEMP = $TempDir
$env:TMP = $TempDir
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $TempDir
New-Item -ItemType Directory -Path $OutDir | Out-Null
$fixture = Join-Path $OutDir 'input.html'
$eof = Join-Path $OutDir 'eof.txt'
[IO.File]::WriteAllText($fixture, '<html><body><h1>FFM repair</h1><p>Native once smoke.</p></body></html>')
[IO.File]::WriteAllText($eof, '')
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class NativeBackendExitProcess {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool GetExitCodeProcess(IntPtr handle, out uint code);
}
'@
foreach ($mode in @('once', 'serve')) {
    $runArguments = @(('-Djava.io.tmpdir=' + $TempDir), ('--' + $mode))
    if ($mode -eq 'once') {
        $runArguments += @('--input', $fixture, '--output', (Join-Path $OutDir 'output'), '--locale', 'en')
    }
    $quoted = ($runArguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    Write-Output ('COMMAND: "' + $BackendExe + '" ' + $quoted + ' < "' + $eof + '"')
    $stdout = Join-Path $OutDir ($mode + '.stdout.log')
    $stderr = Join-Path $OutDir ($mode + '.stderr.log')
    $proc = Start-Process -FilePath $BackendExe -ArgumentList $quoted -WorkingDirectory $OutDir `
        -WindowStyle Hidden -PassThru -RedirectStandardInput $eof `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    try {
        $handle = $proc.Handle
        if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
            & taskkill.exe /PID $proc.Id /T /F
            [void]$proc.WaitForExit(5000)
            throw ('Backend timeout: ' + $mode)
        }
        [uint32]$code = 0
        if (-not [NativeBackendExitProcess]::GetExitCodeProcess($handle, [ref]$code)) {
            throw 'Cannot read Win32 exit status'
        }
    } finally { $proc.Dispose() }
    $output = [IO.File]::ReadAllText($stdout)
    Write-Output $output
    Get-Content -LiteralPath $stderr -Encoding UTF8
    Write-Output ($mode.ToUpperInvariant() + '_EXIT=' + $code)
    if ($code -ne 0) { throw ('Backend failed: ' + $mode) }
    if ($mode -eq 'once' -and -not $output.Contains('done: total=1 success=1 fail=0')) {
        throw 'Once mode did not extract the fixture'
    }
    if ($mode -eq 'serve') {
        $ready = $false
        foreach ($line in $output.Split([char]10)) {
            if ($line.Trim()) {
                $frame = $line | ConvertFrom-Json
                if ($frame.type -eq 'event' -and $frame.event -eq 'ready') { $ready = $true }
            }
        }
        if (-not $ready) { throw 'Serve mode did not emit a ready frame before EOF' }
    }
}
exit 0
