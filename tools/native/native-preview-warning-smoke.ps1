<#
  Build the real headless BackendCli with isolated metadata, then preview a warning over IPC.
  -MetadataDir selects a pre-fix snapshot; production metadata is never rewritten.
  Reuses prepare-headless.ps1 from build-native.cmd NATIVE_TARGET=headless for isolation.
  Requires packaged target/lib, build JDK 25, a JavaFX-free GraalVM, MSVC and Node.
  All paths are supplied by the caller; OutDir and runtime temp must be fresh.
  -SkipNativeBuild reruns an existing successful image only after metadata hash comparison.
  Exit: 0 = preview/warnings/cleanup pass, 1 = build or smoke failure, 20 = no MSVC.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [Parameter(Mandatory = $true)][string]$TempDir,
    [Parameter(Mandatory = $true)][string]$GraalHome,
    [string]$Repo = '',
    [string]$MetadataDir = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Node = 'node.exe',
    [switch]$SkipNativeBuild
)
$ErrorActionPreference = 'Stop'
if (-not $Repo) { $Repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
if (-not $MetadataDir) { $MetadataDir = Join-Path $Repo 'tools\native\headless' }
$OutDir = [IO.Path]::GetFullPath($OutDir)
$TempDir = [IO.Path]::GetFullPath($TempDir)
if ((Test-Path -LiteralPath $OutDir) -and -not $SkipNativeBuild) { throw "Use a fresh OutDir: $OutDir" }
if (-not (Test-Path -LiteralPath $TempDir)) { throw 'Pass an existing TempDir' }
if (-not (Test-Path -LiteralPath (Join-Path $MetadataDir 'reachability-metadata.json'))) { throw 'Missing selected metadata' }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
function Invoke-Checked([string]$Program, [string[]]$Arguments, [string]$Label) {
    Write-Output ('COMMAND: "' + $Program + '" ' + (($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '))
    $ErrorActionPreference = 'Continue'
    & $Program @Arguments 2>&1 | ForEach-Object {
        $line = [string]$_
        Add-Content -LiteralPath (Join-Path $OutDir ($Label + '.log')) -Value $line -Encoding UTF8
        Write-Output $line
    }
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    [IO.File]::WriteAllText((Join-Path $OutDir ($Label + '.exit.txt')), [string]$code)
    Write-Output ("${Label}_EXIT=$code")
    if ($code -ne 0) { throw "Command failed: $Label (exit=$code)" }
}
try {
    $env:JAVA_HOME = $JavaHome
    $env:BUILD_JAVA_HOME = $JavaHome
    $env:GRAALVM_HOME = $GraalHome
    $env:NATIVE_TARGET = 'headless'
    $env:HEADLESS_WORK_DIR = Join-Path $OutDir 'work'
    $env:OUTPUT_DIR = Join-Path $OutDir 'dist'
    $env:LOG_DIR = Join-Path $OutDir 'build-logs'
    $env:TEMP = $TempDir
    $env:TMP = $TempDir
    $env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $TempDir
    $inputDir = Join-Path $env:HEADLESS_WORK_DIR 'input'
    $selected = Join-Path $OutDir 'selected-metadata'
    $name = Join-Path $env:OUTPUT_DIR 'MhtExtractorBackend'
    if (-not $SkipNativeBuild) {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    $vs = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    $vcvars = if ($vs) { Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat' } else { '' }
    if (-not $vcvars -or -not (Test-Path -LiteralPath $vcvars)) { Write-Output '[warning-smoke] MSVC unavailable'; exit 20 }
    Invoke-Checked 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        (Join-Path $Repo 'tools\native\prepare-headless.ps1'), '-Repo', $Repo,
        '-WorkDir', $env:HEADLESS_WORK_DIR, '-OutputDir', $env:OUTPUT_DIR, '-JavaHome', $JavaHome) 'prepare'
    New-Item -ItemType Directory -Path $env:OUTPUT_DIR -Force | Out-Null
    New-Item -ItemType Directory -Path $selected | Out-Null
    foreach ($file in Get-ChildItem -LiteralPath $MetadataDir -File | Where-Object { $_.Extension -in @('.json','.properties') }) {
        Copy-Item -LiteralPath $file.FullName -Destination $selected
    }
    Invoke-Checked 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        (Join-Path $Repo 'tools\native\assert-headless-metadata.ps1'), '-MetadataDir', $selected) 'metadata'
    $cp = [IO.File]::ReadAllText((Join-Path $inputDir 'classpath.txt'))
    $charset = [IO.File]::ReadAllText((Join-Path $inputDir 'default-charset.txt'))
    # Same native-image flags/entry point as build-native.cmd headless, with selected snapshot.
    $native = Join-Path $GraalHome 'bin\native-image.cmd'
    $buildCommand = @('@echo off', ('call "' + $vcvars + '"'), 'if errorlevel 1 exit /b 20',
        ('cd /d "' + $inputDir + '"'),
        ('call "' + $native + '" --limit-modules=java.se,jdk.unsupported -cp "' + $cp +
        '" -H:+UnlockExperimentalVMOptions -H:DefaultCharset="' + $charset +
        '" -H:ConfigurationFileDirectories="' + $selected +
        '" -H:+PrintClassInitialization -H:-UnlockExperimentalVMOptions com.mhtextractor.backend.BackendCli --no-fallback -o "' +
        $name + '" --enable-url-protocols=http,https -J-Xmx4G -Ob'), 'exit /b %errorlevel%')
    $commandFile = Join-Path $OutDir 'native-build.cmd'
    [IO.File]::WriteAllText($commandFile, ($buildCommand -join "`r`n") + "`r`n", [Text.Encoding]::ASCII)
    Invoke-Checked $commandFile @() 'native-build'
    } else {
        if (-not (Test-Path -LiteralPath ($name + '.exe')) -or
            [IO.File]::ReadAllText((Join-Path $OutDir 'native-build.exit.txt')).Trim() -ne '0') {
            throw 'SkipNativeBuild requires a successfully built image in this OutDir'
        }
        $expected = @(Get-ChildItem -LiteralPath $MetadataDir -File | Where-Object { $_.Extension -in @('.json','.properties') })
        $actual = @(Get-ChildItem -LiteralPath $selected -File)
        if ($expected.Count -ne $actual.Count) { throw 'Selected metadata file count differs from image snapshot' }
        foreach ($file in $expected) {
            if ((Get-FileHash -LiteralPath $file.FullName).Hash -ne
                (Get-FileHash -LiteralPath (Join-Path $selected $file.Name)).Hash) {
                throw ('Selected metadata differs from image snapshot: ' + $file.Name)
            }
        }
        Write-Output '[warning-smoke] Reusing successfully built image; selected metadata SHA-256 matches snapshot'
    }
    Invoke-Checked 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        (Join-Path $Repo 'tools\native\assert-headless-metadata.ps1'), '-MetadataDir', $selected,
        '-AnalysisDir', (Join-Path $env:OUTPUT_DIR 'reports')) 'analysis'
    $runDir = Join-Path $OutDir 'run'
    if ($SkipNativeBuild) { $runDir = Join-Path $OutDir ('run-' + [Guid]::NewGuid().ToString('N')) }
    Invoke-Checked $Node @((Join-Path $Repo 'tools\native\native-preview-warning-client.mjs'),
        ($name + '.exe'), $runDir, $TempDir,
        (Join-Path $inputDir 'classes\backend-build.properties')) 'smoke'
    Write-Output '[warning-smoke] PASS: real native BackendCli preview/warnings/session cleanup'
    exit 0
} catch {
    [Console]::Error.WriteLine('[warning-smoke] FAILED: ' + $_.Exception.Message)
    exit 1
}
