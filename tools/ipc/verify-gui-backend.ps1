# P4-17 repeatable GUI / probe evidence. ASCII-only; run after clean test and package.
param(
    [ValidateSet('Gui','Probes','Backend','Control')][string]$Mode = 'Backend',
    [string]$Locale = 'en_US',
    [string]$Evidence = 'H:\TEST\mht\p4-17',
    [string]$OnlyProbe,
    [switch]$Agent
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$javaHome = 'D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3'
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=H:\TEST\temp -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
$env:TEMP = 'H:\TEST\temp'
$env:TMP = 'H:\TEST\temp'
$evidenceRoot = [IO.Path]::GetFullPath($Evidence)
if (-not $evidenceRoot.StartsWith('H:\TEST\mht\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Evidence must be beneath H:\TEST\mht\'
}
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$mp = (Get-ChildItem "$repo\target\lib" -Filter 'javafx-*-win.jar' | ForEach-Object FullName) -join ';'
$deps = [IO.File]::ReadAllText("$repo\target\cp.txt").Trim()
$cp = "$repo\target\classes;$repo\target\test-classes;$deps"
$classes = Join-Path $evidenceRoot 'probe-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$probes = if ($Mode -eq 'Probes') {
    Get-ChildItem "$repo\tools\probes" -Filter '*.java' | Where-Object BaseName -ne 'BackendConnectionProbe' |
        Sort-Object Name | ForEach-Object BaseName
} else { @('BackendConnectionProbe') }
if ($OnlyProbe) {
    if ($Mode -ne 'Probes' -or $probes -notcontains $OnlyProbe) { throw 'Unknown probe selection' }
    $probes = @($OnlyProbe)
}
if ($Mode -ne 'Gui') {
    foreach ($probe in $probes) {
        & "$javaHome\bin\javac.exe" @('--module-path',$mp,'--add-modules','javafx.controls,javafx.fxml',
            '-cp',$cp,'-d',$classes,"$repo\tools\probes\$probe.java")
        if ($LASTEXITCODE -ne 0) { throw "javac failed: $probe" }
    }
    $cp = "$classes;$cp"
}
function Invoke-Evidence([string]$name, [string]$loc, [string]$main, [string[]]$extra) {
    $work = Join-Path $evidenceRoot "$name-$loc"
    New-Item -ItemType Directory -Force -Path "$work\logs" | Out-Null
    # Some existing probes read fixtures by a repository-relative path. Preserve it on H.
    $fixtureParent = "$work\src\test\resources"
    New-Item -ItemType Directory -Force -Path $fixtureParent | Out-Null
    Copy-Item -LiteralPath "$repo\src\test\resources\fixtures" -Destination $fixtureParent -Recurse -Force
    $initialState = @{ locale = $loc }
    if ($name -in @('HtmlViewerProbe','SidePanelProbe')) {
        # These legacy probes synchronously sleep on FX after import; preload their normal input folder.
        $initialState['inputFolder'] = "$fixtureParent\fixtures"
    }
    [IO.File]::WriteAllText("$work\logs\gui-state.json", ($initialState | ConvertTo-Json))
    $parts = $loc.Split('_')
    $arguments = @('--module-path',$mp,'--add-modules','javafx.controls,javafx.fxml',
        '-Djava.io.tmpdir=H:\TEST\temp','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',
        "-Duser.language=$($parts[0])","-Duser.country=$($parts[1])","-Dprobe.locale=$loc",
        "-Dprobe.png=$work\probe.png") + $extra + @('-cp',$cp,$main)
    [IO.File]::WriteAllLines("$work\command.txt", @("$javaHome\bin\java.exe") + $arguments)
    $quoted = (@("$javaHome\bin\java.exe") + $arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    [IO.File]::WriteAllLines("$work\run.cmd", @('@echo off', ($quoted + ' > stdout.txt 2> stderr.txt'), 'exit /b %ERRORLEVEL%'))
    $resultFile = "$work\logs\test-results.txt"
    if ($Mode -eq 'Gui' -and (Test-Path -LiteralPath $resultFile)) {
        Remove-Item -LiteralPath $resultFile
    }
    $startedAt = [DateTime]::UtcNow
    Push-Location $work
    try {
        if ($name -eq 'NonDaemonExitProbe') {
            # This diagnostic deliberately starts a ten-minute non-daemon hog. Its documented
            # outcome is an observation after 60s, not a green product exit assertion.
            $argumentText = ($arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
            # Avoid PowerShell 5.1 Start-Process rebuilding the inherited Path/PATH environment.
            $startInfo = New-Object Diagnostics.ProcessStartInfo
            $startInfo.FileName = "$javaHome\bin\java.exe"
            $startInfo.Arguments = $argumentText
            $startInfo.WorkingDirectory = $work
            $startInfo.UseShellExecute = $false
            $startInfo.CreateNoWindow = $true
            $startInfo.WindowStyle = 'Hidden'
            $startInfo.RedirectStandardOutput = $true
            $startInfo.RedirectStandardError = $true
            $diagnostic = [Diagnostics.Process]::Start($startInfo)
            $diagnosticOut = $diagnostic.StandardOutput.ReadToEndAsync()
            $diagnosticErr = $diagnostic.StandardError.ReadToEndAsync()
            $exited = $diagnostic.WaitForExit(60000)
            if (-not $exited) {
                Stop-Process -Id $diagnostic.Id -Force
                $null = $diagnostic.WaitForExit(10000)
            }
            if (-not $diagnostic.HasExited) { throw 'Diagnostic process did not terminate' }
            [IO.File]::WriteAllText("$work\stdout.txt", $diagnosticOut.GetAwaiter().GetResult())
            [IO.File]::WriteAllText("$work\stderr.txt", $diagnosticErr.GetAwaiter().GetResult())
            $observation = if ($exited) { 'NATURAL_EXIT_WITHIN_60S' } else { 'NON_DAEMON_HOG_BLOCKS_EXIT_AFTER_60S' }
            [IO.File]::WriteAllText("$work\observation.txt", "$observation; pid=$($diagnostic.Id); terminated=$($diagnostic.HasExited)")
            Write-Output "$name locale=$loc observation=$observation terminated=$($diagnostic.HasExited)"
            return
        }
        # Direct invocation gives a real native exit code; never rely on Process.ExitCode.
        & cmd.exe /d /c run.cmd
        $rc = $LASTEXITCODE
    } finally { Pop-Location }
    Write-Output "$name locale=$loc exit=$rc stdout=$work\stdout.txt"
    if ($Mode -eq 'Control') {
        if ($rc -eq 0) { throw 'Red control unexpectedly passed' }
        if (-not ([IO.File]::ReadAllText("$work\stdout.txt").Contains('BACKEND-GUI RESULTS: FAILED'))) {
            throw 'Red control failed outside the target assertion'
        }
    } elseif ($rc -ne 0) { throw "Failed: $name locale=$loc (see raw stdout / stderr)" }
    if ($Mode -eq 'Gui') {
        $file = $resultFile
        if (-not (Test-Path $file)) { throw 'Missing freshly generated GUI results' }
        if ((Get-Item -LiteralPath $file).LastWriteTimeUtc -lt $startedAt) { throw 'Stale GUI results' }
        Write-Output "Fresh results mtime=$((Get-Item -LiteralPath $file).LastWriteTimeUtc.ToString('o'))"
        Get-Content $file -Encoding UTF8 | Select-String 'RESULTS:'
        if ($loc -eq 'en_US') {
            $chinese = ([regex]::Matches([IO.File]::ReadAllText("$work\stdout.txt"), '[\u4e00-\u9fff]')).Count
            Write-Output "EN stdout Chinese characters=$chinese"
            if ($chinese -ne 0) { throw 'English GUI stdout contains Chinese characters' }
        }
    }
}
if ($Mode -eq 'Gui') {
    foreach ($loc in @('zh_CN','en_US')) {
        Invoke-Evidence 'gui' $loc 'com.mhtextractor.gui.acceptance.TestMain' @('-Dtest.mode=true')
    }
} else {
    foreach ($probe in $probes) {
        $extra = @()
        if ($Mode -eq 'Control') { $extra += '-Dprobe.cg.cancel=true' }
        if ($Agent) {
            $agentDir = Join-Path $evidenceRoot "agent-$Locale"
            New-Item -ItemType Directory -Force -Path $agentDir | Out-Null
            $extra += "-agentlib:native-image-agent=config-output-dir=$agentDir"
        }
        Invoke-Evidence $probe $Locale $probe $extra
    }
}
