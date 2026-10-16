# JVM/native PNG acquisition, two independent processes per kind, exact controls and red mutation.
param(
    [string]$OutDir = ('H:\TEST\mht\native-screenshot-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')),
    [string]$Threshold = '0',
    [string]$JavaHome = $env:BUILD_JAVA_HOME,
    [string]$GraalHome = $env:GRAALVM_HOME,
    [Parameter(Mandatory = $true)][string]$Backend,
    [string]$TempRoot = 'H:\TEST\temp',
    [string]$BuildDir = ''
)
. (Join-Path $PSScriptRoot 'gui-probe-support.ps1')
try {
    $value = [decimal]::Parse($Threshold, [Globalization.CultureInfo]::InvariantCulture)
    if ($value -lt 0 -or $value -ge 1) { throw 'Threshold must be >=0 and <1 so the red control can fail' }
    Initialize-ProbeEnvironment $JavaHome $TempRoot
    $OutDir = New-ProbeDirectory $OutDir
    if (-not $BuildDir) { $BuildDir = Build-GuiProbe (Join-Path $OutDir 'build') $JavaHome $GraalHome }
    else { $BuildDir = [IO.Path]::GetFullPath($BuildDir); Assert-ProbeBuild $BuildDir }
    $fixture = Join-Path $OutDir 'fixture'
    New-ProbeFixture $fixture
    foreach ($name in @('jvm1', 'jvm2', 'native1', 'native2')) {
        $kind = if ($name.StartsWith('jvm')) { 'jvm' } else { 'native' }
        $evidence = Join-Path $OutDir $name
        $work = Join-Path $OutDir 'acquisition'
        $code = Invoke-GuiAcquisition $kind 'screenshot' $BuildDir $JavaHome $Backend $work $evidence $TempRoot $fixture
        if ($code -ne 0) { throw "$name acquisition failed (not a pixel comparison failure)" }
        # Keep the visible output-directory field identical in every acquisition.
        $destination = Join-Path $evidence 'work'
        foreach ($path in @($work, $destination)) {
            if (-not [IO.Path]::GetFullPath($path).StartsWith($OutDir + '\', [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Acquisition archive must stay within OutDir'
            }
        }
        Move-Item -LiteralPath $work -Destination $destination
        $images = Join-Path $evidence 'images'
        New-Item -ItemType Directory -Path $images | Out-Null
        Copy-Item -LiteralPath (Join-Path $destination 'probe.png') -Destination (Join-Path $images 'probe.png')
    }
    $failed = $false
    foreach ($pair in @(@('jvm1','jvm2'), @('native1','native2'), @('jvm1','native1'),
                        @('jvm1','native2'), @('jvm2','native1'), @('jvm2','native2'))) {
        $label = $pair -join '-vs-'
        $a = Join-Path $OutDir ($pair[0] + '\images')
        $b = Join-Path $OutDir ($pair[1] + '\images')
        $code = Invoke-ProbeProcess $label 'powershell.exe' @('-NoProfile','-ExecutionPolicy','Bypass','-File',
            (Join-Path $script:ProbeRepo 'tools\verify\screenshot-compare.ps1'), '-Baseline',$a,'-Current',$b,
            '-Threshold',$Threshold,'-DiffDir',(Join-Path $OutDir ('diff-' + $label))) $OutDir $OutDir 60
        if ($code -ne 0) { $failed = $true }
        $bbox = Invoke-ProbeProcess ($label + '-bbox') (Join-Path $JavaHome 'bin\java.exe') @(
            (Join-Path $script:ProbeRepo 'tools\verify\ScreenshotMatrixTool.java'), 'bbox',
            (Join-Path $a 'probe.png'), (Join-Path $b 'probe.png')) $OutDir $OutDir 60
        if ($bbox -ne 0) { throw 'Bounding box measurement failed' }
    }
    $red = Join-Path $OutDir 'red'
    New-Item -ItemType Directory -Path $red | Out-Null
    $code = Invoke-ProbeProcess 'mutate' (Join-Path $JavaHome 'bin\java.exe') @(
        (Join-Path $script:ProbeRepo 'tools\verify\ScreenshotMatrixTool.java'), 'mutate',
        (Join-Path $OutDir 'jvm1\images\probe.png'), (Join-Path $red 'probe.png')) $OutDir $OutDir
    if ($code -ne 0) { throw 'Known mutation generation failed' }
    $code = Invoke-ProbeProcess 'red-compare' 'powershell.exe' @('-NoProfile','-ExecutionPolicy','Bypass','-File',
        (Join-Path $script:ProbeRepo 'tools\verify\screenshot-compare.ps1'), '-Baseline',
        (Join-Path $OutDir 'jvm1\images'),'-Current',$red,'-Threshold',$Threshold) $OutDir $OutDir
    if ($code -ne 1) { throw 'Known RGB mutation did not fail with exit 1' }
    Write-Host '[PASS] screenshot red control failed with exit 1 at the same threshold'
    Write-Host "MATRIX_COMPLETE comparisonFailure=$failed threshold=$Threshold"
    if ($failed) { exit 1 }
    exit 0
} catch { Write-Host ('[FAIL] ' + $_.Exception.Message); exit 1 }
