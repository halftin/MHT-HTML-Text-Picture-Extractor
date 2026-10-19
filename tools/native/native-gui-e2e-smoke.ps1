# Real native FXML -> import -> source column -> preview -> production close.
param(
    [string]$OutDir = ('H:\TEST\mht\native-gui-e2e-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')),
    [string]$JavaHome = $env:BUILD_JAVA_HOME,
    [string]$GraalHome = $env:GRAALVM_HOME,
    [Parameter(Mandatory = $true)][string]$Backend,
    [string]$TempRoot = 'H:\TEST\temp',
    [string]$BuildDir = '',
    [switch]$InjectWrongSource,
    [switch]$Extract
)
. (Join-Path $PSScriptRoot 'gui-probe-support.ps1')
try {
    Initialize-ProbeEnvironment $JavaHome $TempRoot
    $OutDir = New-ProbeDirectory $OutDir
    if (-not $BuildDir) { $BuildDir = Build-GuiProbe (Join-Path $OutDir 'build') $JavaHome $GraalHome }
    else { $BuildDir = [IO.Path]::GetFullPath($BuildDir); Assert-ProbeBuild $BuildDir }
    $fixture = Join-Path $OutDir 'fixture'
    New-ProbeFixture $fixture
    $code = Invoke-GuiAcquisition 'native' 'e2e' $BuildDir $JavaHome $Backend (Join-Path $OutDir 'work') `
        $OutDir $TempRoot $fixture 'en_US' -WrongSource:$InjectWrongSource -Extract:$Extract
    if ($InjectWrongSource) {
        $output = [IO.File]::ReadAllText((Join-Path $OutDir 'run.stdout.log'))
        if ($code -ne 1 -or -not $output.Contains('[FAIL] source column text equals fixture directory')) {
            throw 'Wrong-source injection failed to reach its intended assertion'
        }
        Write-Host 'RED_CONTROL_MATCH wrong source text assertion failed; exit=1'
        exit 1
    }
    if ($code -ne 0) { throw 'Native GUI e2e assertions failed' }
    Write-Host '[PASS] native GUI e2e completed with natural process exit and no residual process/temp'
    exit 0
} catch { Write-Host ('[FAIL] ' + $_.Exception.Message); exit 1 }
