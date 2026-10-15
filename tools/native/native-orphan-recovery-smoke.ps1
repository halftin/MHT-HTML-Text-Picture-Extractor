<#
  Native orphan-recovery smoke: run the REAL native backend against a temp root that contains
  (a) an unlocked mht-ipc-* orphan session dir -> must be reclaimed, and
  (b) a live session dir whose .session.lock is held by this process -> must SURVIVE.
  Green: -OutDir fresh, both assertions hold -> ORPHAN_RECOVERY_OK, exit 0.
  Teeth check: -SkipLiveLock releases the live dir (no lock) so the live assertion MUST fail.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BackendExe,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$TempRoot = "",
    [switch]$SkipLiveLock,
    [int]$TimeoutSec = 90
)
$ErrorActionPreference = "Stop"
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class NativeOrphanProcess {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool GetExitCodeProcess(IntPtr process, out uint code);
}
"@
if (-not (Test-Path -LiteralPath $BackendExe)) { throw "Backend executable missing" }
$OutDir = [IO.Path]::GetFullPath($OutDir)
if (Test-Path -LiteralPath $OutDir) { throw "Use a fresh OutDir" }
New-Item -ItemType Directory -Path $OutDir | Out-Null
if (-not $TempRoot) { $TempRoot = Join-Path $OutDir "tmp" }
if (-not (Test-Path -LiteralPath $TempRoot)) { New-Item -ItemType Directory -Path $TempRoot | Out-Null }
$TempRoot = [IO.Path]::GetFullPath($TempRoot)
$fixture = Join-Path $OutDir "input"
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $fixture "input.html"), "<html><body><p>orphan recovery smoke</p></body></html>", (New-Object Text.UTF8Encoding($false)))
$output = Join-Path $OutDir "output"
$eof = Join-Path $OutDir "eof.txt"
[IO.File]::WriteAllText($eof, "")
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$orphan = Join-Path $TempRoot ("mht-ipc-orphan-" + $suffix)
$live = Join-Path $TempRoot ("mht-ipc-live-" + $suffix)
New-Item -ItemType Directory -Path $orphan -Force | Out-Null
New-Item -ItemType Directory -Path $live -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $orphan ".session.lock"), "")
[IO.File]::WriteAllText((Join-Path $live ".session.lock"), "")
$stream = $null
if (-not $SkipLiveLock) {
    $stream = [System.IO.File]::Open((Join-Path $live ".session.lock"), [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    $stream.Lock(0, 1)
    Write-Output "LIVE_LOCK held=$($stream.SafeFileHandle.DangerousGetHandle())"
} else {
    Write-Output "LIVE_LOCK skipped (teeth check)"
}
Write-Output ("ORPHAN_DIR=" + $orphan)
Write-Output ("LIVE_DIR=" + $live)
$args = @(("-Djava.io.tmpdir=" + $TempRoot), "--once", "--input", $fixture, "--output", $output)
$quoted = ($args | ForEach-Object { '"' + $_ + '"' }) -join " "
Write-Output ("COMMAND: '" + $BackendExe + "' " + $quoted)
$out = Join-Path $OutDir "backend.stdout.log"
$err = Join-Path $OutDir "backend.stderr.log"
$proc = Start-Process -FilePath $BackendExe -ArgumentList $quoted -WorkingDirectory $OutDir -WindowStyle Hidden -PassThru -RedirectStandardInput $eof -RedirectStandardOutput $out -RedirectStandardError $err
$handle = $proc.Handle   # capture BEFORE the wait: the handle is invalid after exit
try {
    if (-not $proc.WaitForExit($TimeoutSec * 1000)) { & taskkill.exe /PID $proc.Id /T /F; throw "Backend timeout" }
    [uint32]$code = 0
    if (-not [NativeOrphanProcess]::GetExitCodeProcess($handle, [ref]$code)) { throw "Cannot read Win32 exit status" }
} finally { $proc.Dispose() }
Get-Content -LiteralPath $out -Encoding UTF8 | Write-Output
Get-Content -LiteralPath $err -Encoding UTF8 | Write-Output
Write-Output ("BACKEND_EXIT=" + $code)
$orphanGone = -not (Test-Path -LiteralPath $orphan)
$liveAlive = Test-Path -LiteralPath $live
$liveLockAlive = Test-Path -LiteralPath (Join-Path $live ".session.lock")
if ($stream) { try { $stream.Unlock(0, 1) } catch { }; $stream.Dispose() }
Write-Output ("ORPHAN_RECLAIMED=" + $orphanGone)
Write-Output ("LIVE_SURVIVED=" + $liveAlive)
if ($code -ne 0) { Write-Output "ORPHAN_RECOVERY_FAILED: backend exit code"; exit 1 }
if (-not $orphanGone) { Write-Output "ORPHAN_RECOVERY_FAILED: unlocked orphan session dir was NOT reclaimed"; exit 1 }
if (-not $liveAlive -or -not $liveLockAlive) { Write-Output "ORPHAN_RECOVERY_FAILED: live session dir was reclaimed (LOCK SEMANTICS BROKEN)"; exit 1 }
Write-Output "ORPHAN_RECOVERY_OK"
exit 0