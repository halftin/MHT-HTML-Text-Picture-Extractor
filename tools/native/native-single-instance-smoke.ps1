<#
  Exercise two real native GUI processes in the same fresh working directory.
  Default artifacts/temp follow the repository H:\TEST convention. Explicit OutDir must be fresh.
  Exit: 0 = all assertions and cleanup pass; 1 = failure (evidence is retained).
#>
[CmdletBinding()]
param(
    [string]$DistDir = '',
    [string]$OutDir = '',
    [string]$TempRoot = 'H:\TEST\temp'
)
$ErrorActionPreference = 'Stop'
$toolPath = $env:PATH
[Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
[Environment]::SetEnvironmentVariable('Path', $null, 'Process')
[Environment]::SetEnvironmentVariable('Path', $toolPath, 'Process')
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $DistDir) { $DistDir = $repo + '-dist' }
if (-not $OutDir) { $OutDir = 'H:\TEST\mht\native-single-instance-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') }
$DistDir = [IO.Path]::GetFullPath($DistDir)
$OutDir = [IO.Path]::GetFullPath($OutDir)
$TempRoot = [IO.Path]::GetFullPath($TempRoot)
$exe = Join-Path $DistDir 'MhtExtractor.exe'
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw "Native GUI executable missing: $exe" }
if ([IO.Path]::GetPathRoot($OutDir) -ine 'H:\' -or [IO.Path]::GetPathRoot($TempRoot) -ine 'H:\') {
    throw 'Runtime artifacts and temporary files must be on H:'
}
if (Test-Path -LiteralPath $OutDir) { throw "Use a fresh OutDir: $OutDir" }
New-Item -ItemType Directory -Path $OutDir | Out-Null
$work = Join-Path $OutDir 'work'
New-Item -ItemType Directory -Path $work | Out-Null
New-Item -ItemType Directory -Path $TempRoot -Force | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
function Save-Text([string]$Name, [string]$Value) {
    [IO.File]::WriteAllText((Join-Path $OutDir $Name), $Value, $utf8)
}
function Get-GuiProcesses {
    @(Get-Process -Name MhtExtractor -ErrorAction SilentlyContinue | Sort-Object Id |
        Select-Object Id, ProcessName, Path, StartTime)
}
function Record-Processes([string]$Label) {
    $items = @(Get-GuiProcesses)
    $listing = if ($items.Count) { $items | Format-Table -AutoSize | Out-String -Width 300 } else { '(none)' }
    Save-Text ($Label + '.txt') $listing
    Write-Host "PROCESSES_${Label}:`n$listing"
    return $items
}
function Read-Log {
    if (Test-Path -LiteralPath $logFile) { return (Get-Content -LiteralPath $logFile -Encoding UTF8 | Out-String) }
    return ''
}
function Read-LogBytes {
    $stream = [IO.File]::Open($logFile, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    $memory = New-Object IO.MemoryStream
    try { $stream.CopyTo($memory); return ,$memory.ToArray() }
    finally { $stream.Dispose(); $memory.Dispose() }
}
function Get-MessageValues([string]$Key) {
    foreach ($locale in @('zh_CN', 'en_US')) {
        $bundle = Join-Path $repo ('src\main\resources\i18n\messages_' + $locale + '.properties')
        $line = @(Get-Content -LiteralPath $bundle -Encoding UTF8 | Where-Object { $_.StartsWith($Key + '=') })
        if ($line.Count -ne 1) { throw "Missing/ambiguous message: $Key ($locale)" }
        $line[0].Substring($Key.Length + 1)
    }
}
function Has-Message([string]$Log, [string[]]$Values) {
    foreach ($value in $Values) { if ($Log.Contains(' - ' + $value)) { return $true } }
    return $false
}
function Read-Exit([IntPtr]$Handle) {
    [uint32]$code = 0
    if (-not [NativeSingleInstanceProcess]::GetExitCodeProcess($Handle, [ref]$code)) {
        throw ('GetExitCodeProcess failed: ' + [Runtime.InteropServices.Marshal]::GetLastWin32Error())
    }
    return $code
}
function Start-Gui([string]$Label) {
    $argument = '"-Djava.io.tmpdir=' + $TempRoot + '"'
    Write-Host ('COMMAND: "' + $exe + '" ' + $argument + ' (cwd="' + $work + '")')
    Start-Process -FilePath $exe -ArgumentList $argument -WorkingDirectory $work -WindowStyle Hidden `
        -PassThru -RedirectStandardOutput (Join-Path $OutDir ($Label + '.stdout.log')) `
        -RedirectStandardError (Join-Path $OutDir ($Label + '.stderr.log'))
}
$first = $null
$second = $null
$firstHandle = [IntPtr]::Zero
$secondHandle = [IntPtr]::Zero
$failure = $null
$logFile = Join-Path $work 'logs\mht-extractor.log'
$oldTemp = $env:TEMP
$oldTmp = $env:TMP
$oldJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:TEMP = $TempRoot
    $env:TMP = $TempRoot
    $env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $TempRoot
    if (-not ('NativeSingleInstanceProcess' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class NativeSingleInstanceProcess {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool GetExitCodeProcess(IntPtr process, out uint code);
}
'@
    }
    $ready = @(Get-MessageValues 'log.app.ready')
    $already = @(Get-MessageValues 'log.app.alreadyRunning')
    $existing = @(Record-Processes 'before-start')
    if ($existing.Count) { throw 'Existing MhtExtractor processes: close them before running this smoke' }
    $info = Get-Item -LiteralPath $exe
    Write-Host ('EXE=' + $info.FullName + ' MTIME=' + $info.LastWriteTime.ToString('o') + ' BYTES=' + $info.Length)
    $first = Start-Gui 'instance-1'
    $firstHandle = $first.Handle
    Write-Host "INSTANCE_1_PID=$($first.Id)"
    Save-Text 'instance-1.pid.txt' ([string]$first.Id)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while (-not (Has-Message (Read-Log) $ready)) {
        if ($first.HasExited) { throw ('Instance 1 exited before ready; exit=' + (Read-Exit $firstHandle)) }
        if ($timer.Elapsed.TotalSeconds -ge 60) { throw 'Instance 1 ready timeout (60 s)' }
        Start-Sleep -Milliseconds 100
    }
    $first.Refresh()
    if ($first.HasExited) { throw 'Instance 1 must remain alive after ready' }
    Write-Host ('[PASS] instance 1 log.app.ready; alive; elapsed_ms=' + $timer.ElapsedMilliseconds)
    # Preserve the byte boundary before #2 starts: the shared log appends both processes.
    $firstBytes = Read-LogBytes
    $boundary = $firstBytes.Length
    [IO.File]::WriteAllBytes((Join-Path $OutDir 'instance-1.product.log'), $firstBytes)
    Save-Text 'instance-2.log-offset.txt' ([string]$boundary)
    Write-Host ('INSTANCE_1_RAW_LOG:' + "`n" + $utf8.GetString($firstBytes))
    $timer.Restart()
    $second = Start-Gui 'instance-2'
    $secondHandle = $second.Handle # Must precede WaitForExit on this host.
    Write-Host "INSTANCE_2_PID=$($second.Id)"
    Save-Text 'instance-2.pid.txt' ([string]$second.Id)
    $remainingMs = [Math]::Max(0, 15000 - [int]$timer.ElapsedMilliseconds)
    if (-not $second.WaitForExit($remainingMs)) { throw 'Instance 2 did not exit itself within 15 s' }
    $secondMs = $timer.ElapsedMilliseconds
    $secondCode = Read-Exit $secondHandle
    Save-Text 'instance-2.exit.txt' ([string]$secondCode)
    Write-Host "INSTANCE_2_EXIT=$secondCode ELAPSED_MS=$secondMs"
    $allBytes = Read-LogBytes
    if ($allBytes.Length -lt $boundary) { throw 'Shared product log was truncated' }
    $secondLog = $utf8.GetString($allBytes, $boundary, $allBytes.Length - $boundary)
    Save-Text 'instance-2.product.log' $secondLog
    Write-Host ('INSTANCE_2_RAW_LOG:' + "`n" + $secondLog)
    if ($secondMs -gt 15000) { throw 'Instance 2 exceeded 15 s including launch' }
    if (-not (Has-Message $secondLog $already)) { throw 'Instance 2 missing log.app.alreadyRunning' }
    if (Has-Message $secondLog $ready) { throw 'Instance 2 incorrectly displayed the ready main window' }
    if ($secondCode -ne 0) { throw "Instance 2 abnormal exit: $secondCode" }
    $first.Refresh()
    if ($first.HasExited) { throw 'Instance 1 exited while rejecting instance 2' }
    Write-Host '[PASS] instance 2 log.app.alreadyRunning; no log.app.ready; instance 1 alive'
} catch {
    $failure = $_
    Write-Host ('SINGLE_INSTANCE_FAILED: ' + $_.Exception.Message)
} finally {
    $null = Record-Processes 'before-cleanup'
    foreach ($proc in @($second, $first)) {
        if ($null -eq $proc) { continue }
        try {
            if (-not $proc.HasExited) {
                Write-Host "COMMAND: taskkill /PID $($proc.Id) /T /F"
                & taskkill.exe /PID $proc.Id /T /F | ForEach-Object { Write-Host $_ }
                $killCode = $LASTEXITCODE
                Write-Host "TASKKILL_EXIT=$killCode"
                if ($killCode -ne 0 -and -not $proc.HasExited) {
                    # Some restricted Windows sessions deny taskkill's tree enumeration.
                    # The retained Process identifies only this smoke's own child.
                    Write-Host "FALLBACK: Process.Kill PID=$($proc.Id)"
                    $proc.Kill()
                }
                if (-not $proc.WaitForExit(5000)) { throw "Cleanup timeout: $($proc.Id)" }
            }
            $handle = if ($proc -eq $first) { $firstHandle } else { $secondHandle }
            $code = Read-Exit $handle
            $label = if ($proc -eq $first) { 'instance-1' } else { 'instance-2' }
            Save-Text ($label + '.exit.txt') ([string]$code)
            Write-Host "${label}_FINAL_EXIT=$code"
        } catch { if (-not $failure) { $failure = $_ }; Write-Host "CLEANUP_FAILED: $_" }
        finally { $proc.Dispose() }
    }
    $remaining = @(Record-Processes 'after-cleanup')
    if ($remaining.Count -and -not $failure) { $failure = 'MhtExtractor processes remain after cleanup' }
    if (Test-Path -LiteralPath $logFile) { Copy-Item -LiteralPath $logFile -Destination (Join-Path $OutDir 'combined.product.log') }
    $env:TEMP = $oldTemp
    $env:TMP = $oldTmp
    $env:JAVA_TOOL_OPTIONS = $oldJavaOptions
}
if ($failure) { Save-Text 'smoke.exit.txt' '1'; Write-Host "SINGLE_INSTANCE_FAILED: $failure"; exit 1 }
Save-Text 'smoke.exit.txt' '0'
Write-Host '[PASS] no residual MhtExtractor processes'
Write-Host 'NATIVE_SINGLE_INSTANCE_OK'
exit 0
