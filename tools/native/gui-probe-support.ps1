# Shared recorded process/build/acquisition functions. Dot-source from the entry scripts.
# ASCII, CRLF; no machine-specific JDK paths. All generated inputs use caller directories.
$ErrorActionPreference = 'Stop'
$script:ProbeRepo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script:Utf8 = New-Object Text.UTF8Encoding($false)

function Initialize-ProbeEnvironment([string]$JavaHome, [string]$TempRoot) {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) { throw 'Pass -JavaHome (JDK 25 with JavaFX)' }
    New-Item -ItemType Directory -Path $TempRoot -Force | Out-Null
    $env:JAVA_HOME = $JavaHome
    $env:BUILD_JAVA_HOME = $JavaHome
    $env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $TempRoot
    $env:TEMP = $TempRoot
    $env:TMP = $TempRoot
    # This host can expose both PATH and Path, which breaks Start-Process environment construction.
    $toolPath = $env:PATH
    [Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $null, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $toolPath, 'Process')
    if (-not ('GuiProbeProcess' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class GuiProbeProcess {
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool GetExitCodeProcess(IntPtr handle, out uint code);
}
'@
    }
}

function New-ProbeDirectory([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (Test-Path -LiteralPath $full) { throw "Use a fresh directory: $full" }
    New-Item -ItemType Directory -Path $full | Out-Null
    return $full
}

function Invoke-ProbeProcess([string]$Label, [string]$Program, [string[]]$Arguments,
                             [string]$WorkingDirectory, [string]$Evidence, [int]$TimeoutSec = 60) {
    New-Item -ItemType Directory -Path $Evidence -Force | Out-Null
    foreach ($value in @($Program, $WorkingDirectory) + $Arguments) {
        if ($value -match '["\r\n]') { throw 'Embedded quotes/newlines in process arguments are unsupported' }
    }
    $quoted = ($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $command = 'COMMAND [' + $Label + ']: "' + $Program + '" ' + $quoted + ' cwd="' + $WorkingDirectory + '"'
    Write-Host $command
    [IO.File]::WriteAllText((Join-Path $Evidence ($Label + '.command.txt')), $command, $script:Utf8)
    $stdout = Join-Path $Evidence ($Label + '.stdout.log')
    $stderr = Join-Path $Evidence ($Label + '.stderr.log')
    $proc = Start-Process -FilePath $Program -ArgumentList $quoted -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $handle = $proc.Handle
    try {
        if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
            & taskkill.exe /PID $proc.Id /T /F
            if (-not $proc.WaitForExit(5000)) { throw "Cannot terminate $Label pid=$($proc.Id)" }
            throw "$Label timed out after $TimeoutSec seconds (not a successful red control)"
        }
        [uint32]$code = 0
        if (-not [GuiProbeProcess]::GetExitCodeProcess($handle, [ref]$code)) { throw 'GetExitCodeProcess failed' }
    } finally { $proc.Dispose() }
    Get-Content -LiteralPath $stdout -Encoding UTF8 | ForEach-Object { Write-Host $_ }
    Get-Content -LiteralPath $stderr -Encoding UTF8 | ForEach-Object { Write-Host $_ }
    [IO.File]::WriteAllText((Join-Path $Evidence ($Label + '.exit.txt')), [string]$code, $script:Utf8)
    Write-Host "${Label}_EXIT=$code"
    return $code
}

function Get-ProbeInputs {
    @(Get-Item (Join-Path $script:ProbeRepo 'tools\probes\GuiE2eProbe.java'),
        (Join-Path $script:ProbeRepo 'tools\probes\support\ProbePng.java')) +
    @(Get-ChildItem -LiteralPath (Join-Path $script:ProbeRepo 'target\classes') -Recurse -File) +
    @(Get-ChildItem -LiteralPath (Join-Path $script:ProbeRepo 'target\lib') -Filter '*.jar' -File)
}
function Get-ProbeFingerprint {
    $lines = @(Get-ProbeInputs | Sort-Object FullName | ForEach-Object {
        $_.FullName + ' ' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    })
    return $lines -join "`n"
}

function Build-GuiProbe([string]$BuildDir, [string]$JavaHome, [string]$GraalHome, [switch]$JvmOnly) {
    $BuildDir = New-ProbeDirectory $BuildDir
    $classes = Join-Path $BuildDir 'classes'
    New-Item -ItemType Directory -Path $classes | Out-Null
    $deps = (Get-ChildItem -LiteralPath (Join-Path $script:ProbeRepo 'target\lib') -Filter '*.jar' -File |
        Sort-Object Name | ForEach-Object FullName) -join ';'
    if (-not $deps) { throw 'Run mvnw.cmd -DskipTests package first' }
    $cp = $classes + ';' + (Join-Path $script:ProbeRepo 'target\classes') + ';' + $deps
    $code = Invoke-ProbeProcess 'javac' (Join-Path $JavaHome 'bin\javac.exe') @('-J-Duser.language=en', '-encoding', 'UTF-8', '-cp', $cp,
        '-d', $classes, (Join-Path $script:ProbeRepo 'tools\probes\GuiE2eProbe.java'),
        (Join-Path $script:ProbeRepo 'tools\probes\support\ProbePng.java')) $script:ProbeRepo $BuildDir
    if ($code -ne 0) { throw 'Probe compilation failed' }
    [IO.File]::WriteAllText((Join-Path $BuildDir 'classpath.txt'), $cp, $script:Utf8)
    if (-not $JvmOnly) {
        if (-not (Test-Path -LiteralPath (Join-Path $GraalHome 'bin\native-image.cmd'))) { throw 'Pass -GraalHome with native-image' }
        if (Test-Path -LiteralPath (Join-Path $GraalHome 'lib\static\windows-amd64\prism_common.lib')) {
            throw 'These parity/e2e scripts require the dynamic recipe (GraalVM without JavaFX static libraries)'
        }
        # Same discovery and vcvars64 invocation as native-preview-warning-smoke.ps1.
        $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
        $vs = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        $vcvars = if ($vs) { Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat' } else { '' }
        if (-not $vcvars -or -not (Test-Path -LiteralPath $vcvars)) { throw 'MSVC unavailable' }
        $ni = @('-cp', $cp, 'GuiE2eProbe', '--no-fallback', '-Ob', '-J-Xmx4G', '-H:+JNI',
            '-H:+ReportExceptionStackTraces', '--enable-all-security-services', '--enable-http', '--enable-https',
            '--enable-url-protocols=file,http,https',
            '--initialize-at-run-time=com.sun.glass,com.sun.prism,com.sun.scenario,com.sun.javafx.font,com.sun.javafx.iio,com.sun.media,com.sun.webkit,com.sun.javafx.tk.quantum,com.sun.marlin',
            ('-Djava.library.path=' + (Join-Path $script:ProbeRepo 'target\jfx-dlls')),
            ('-H:Path=' + $BuildDir), '-H:Name=GuiE2eProbe')
        $command = @('@echo off', ('call "' + $vcvars + '"'), 'if errorlevel 1 exit /b 20',
            ('set "JAVA_HOME=' + $GraalHome + '"'),
            ('call "' + (Join-Path $GraalHome 'bin\native-image.cmd') + '" ' + (($ni | ForEach-Object { '"' + $_ + '"' }) -join ' ')),
            'exit /b %errorlevel%') -join "`r`n"
        $cmdFile = Join-Path $BuildDir 'native-build.cmd'
        [IO.File]::WriteAllText($cmdFile, $command + "`r`n", [Text.Encoding]::Default)
        $code = Invoke-ProbeProcess 'native-build' $env:ComSpec @('/d', '/c', $cmdFile) $script:ProbeRepo $BuildDir 900
        if ($code -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $BuildDir 'GuiE2eProbe.exe'))) { throw 'Native build failed' }
        Copy-Item (Join-Path $script:ProbeRepo 'target\jfx-dlls\*.dll') -Destination $BuildDir
        [IO.File]::WriteAllText((Join-Path $BuildDir 'image.sha256'),
            (Get-FileHash -LiteralPath (Join-Path $BuildDir 'GuiE2eProbe.exe')).Hash, $script:Utf8)
    }
    [IO.File]::WriteAllText((Join-Path $BuildDir 'inputs.sha256.txt'), (Get-ProbeFingerprint), $script:Utf8)
    return $BuildDir
}

function Assert-ProbeBuild([string]$BuildDir) {
    if ([IO.File]::ReadAllText((Join-Path $BuildDir 'inputs.sha256.txt')) -cne (Get-ProbeFingerprint)) {
        throw 'Probe/build inputs changed; build a fresh image'
    }
    if ([IO.File]::ReadAllText((Join-Path $BuildDir 'native-build.exit.txt')).Trim() -ne '0' -or
        [IO.File]::ReadAllText((Join-Path $BuildDir 'image.sha256')).Trim() -ne
        (Get-FileHash -LiteralPath (Join-Path $BuildDir 'GuiE2eProbe.exe')).Hash) { throw 'Native image evidence/hash mismatch' }
    Write-Host '[PASS] reused native image and all source/dependency SHA256 fingerprints match'
}

function New-ProbeFixture([string]$Directory) {
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aK1sAAAAASUVORK5CYII='
    $mht = @('MIME-Version: 1.0','Content-Type: multipart/related; boundary="probe-boundary"','','--probe-boundary',
        'Content-Type: text/html; charset=UTF-8','Content-Transfer-Encoding: 8bit','Content-Location: index.html','',
        '<html><head><title>Probe fixture</title></head><body><p>Probe fixture</p><img src="cid:fixture.png"></body></html>',
        '--probe-boundary','Content-Type: image/png','Content-Transfer-Encoding: base64','Content-ID: <fixture.png>',
        'Content-Location: fixture.png','',$png,'--probe-boundary--','') -join "`r`n"
    [IO.File]::WriteAllText((Join-Path $Directory 'fixture.mht'), $mht, $script:Utf8)
}

function Invoke-GuiAcquisition([string]$Kind, [string]$Scenario, [string]$BuildDir, [string]$JavaHome,
        [string]$Backend, [string]$Work, [string]$Evidence, [string]$TempRoot, [string]$Fixture,
        [string]$Locale = 'en_US', [switch]$WrongSource) {
    $Work = New-ProbeDirectory $Work
    New-Item -ItemType Directory -Path (Join-Path $Work 'logs') | Out-Null
    [IO.File]::WriteAllText((Join-Path $Work 'logs\gui-state.json'),
        ('{"locale":"' + $Locale + '","openFolderAfterComplete":false}'), $script:Utf8)
    $runtimeTemp = New-ProbeDirectory (Join-Path $TempRoot ('p5d-' + [Guid]::NewGuid().ToString('N')))
    $parts = $Locale.Split('_')
    $properties = @("-Djava.io.tmpdir=$runtimeTemp", "-Duser.language=$($parts[0])", "-Duser.country=$($parts[1])",
        '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        "-Dprobe.scenario=$Scenario", "-Dprobe.backend=$Backend", "-Dprobe.fixture=$Fixture",
        "-Dprobe.png=$Work\probe.png", "-Dprobe.wrongSource=$($WrongSource.IsPresent.ToString().ToLowerInvariant())")
    $program = Join-Path $BuildDir 'GuiE2eProbe.exe'
    if ($Kind -eq 'jvm') {
        $program = Join-Path $JavaHome 'bin\java.exe'
        $properties += @('-cp', [IO.File]::ReadAllText((Join-Path $BuildDir 'classpath.txt')), 'GuiE2eProbe')
    }
    $beforePids = @(Get-Process -Name GuiE2eProbe,MhtExtractorBackend -ErrorAction SilentlyContinue | ForEach-Object Id)
    $code = Invoke-ProbeProcess 'run' $program $properties $Work $Evidence 65
    $residual = @(Get-Process -Name GuiE2eProbe,MhtExtractorBackend -ErrorAction SilentlyContinue |
        Where-Object { $beforePids -notcontains $_.Id } | Select-Object Id,ProcessName,Path)
    $listing = if ($residual.Count) { $residual | Format-Table -AutoSize | Out-String -Width 300 } else { '(none)' }
    [IO.File]::WriteAllText((Join-Path $Evidence 'remaining-processes.txt'), $listing, $script:Utf8)
    Write-Host "PROCESS_REMAINING=$($residual.Count) $listing"
    if ($residual.Count) { throw 'Child process remains after probe exit' }
    # IpcFiles deliberately retains its temp-root startup coordination lock. After
    # all owned processes exit, remove only this unlocked sentinel in our fresh root.
    $startupLock = Join-Path $runtimeTemp '.mht-ipc-startup.lock'
    if (Test-Path -LiteralPath $startupLock) {
        $lock = [IO.File]::Open($startupLock, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $lock.Dispose()
        Remove-Item -LiteralPath $startupLock
        Write-Host 'CLEANUP removed unlocked persistent .mht-ipc-startup.lock from isolated temp root'
    }
    $remaining = @(Get-ChildItem -LiteralPath $runtimeTemp -Force)
    Write-Host "TEMP_REMAINING=$($remaining.Count) root=$runtimeTemp"
    if ($remaining.Count) { throw 'Runtime temp cleanup incomplete (evidence retained)' }
    Remove-Item -LiteralPath $runtimeTemp
    $out = [IO.File]::ReadAllText((Join-Path $Evidence 'run.stdout.log'))
    if ($code -eq 0 -and -not $out.Contains('PROBE_COMPLETE')) { throw 'Missing natural-close completion marker' }
    return $code
}
