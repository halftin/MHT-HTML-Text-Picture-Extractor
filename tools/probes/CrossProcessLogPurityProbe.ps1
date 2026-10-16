# Real JVM frontend + native backend, default production logback files, no log filters.
# Count-only is independently reusable; InjectCjk changes isolated en evidence and MUST exit 1.
[CmdletBinding(DefaultParameterSetName = 'Run')]
param(
    [Parameter(ParameterSetName = 'Run')][string]$OutDir = ('H:\TEST\mht\cross-log-purity-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')),
    [Parameter(ParameterSetName = 'Run')][string]$JavaHome = $env:BUILD_JAVA_HOME,
    [Parameter(ParameterSetName = 'Run', Mandatory = $true)][string]$Backend,
    [Parameter(ParameterSetName = 'Run')][string]$TempRoot = 'H:\TEST\temp',
    [Parameter(ParameterSetName = 'Run')][switch]$InjectCjk,
    [Parameter(ParameterSetName = 'Count', Mandatory = $true)][string[]]$CountFiles,
    [Parameter(ParameterSetName = 'Count', Mandatory = $true)][ValidateSet('en','zh')][string]$Expected
)
$ErrorActionPreference = 'Stop'
$logUtf8 = New-Object Text.UTF8Encoding($false, $true)
function Assert-LogCounts([string[]]$Files, [string]$Language) {
    $ok = $true
    foreach ($file in $Files) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing log file: $file" }
        $bytes = [IO.File]::ReadAllBytes($file)
        if (-not $bytes.Length) { throw "Empty log file: $file" }
        $content = $logUtf8.GetString($bytes)
        # BMP Han ranges. Also count supplementary Han code points as single characters.
        $count = [regex]::Matches($content, '[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]').Count
        for ($i = 0; $i -lt $content.Length - 1; $i++) {
            if ([char]::IsSurrogatePair($content, $i)) {
                $cp = [char]::ConvertToUtf32($content, $i)
                if (($cp -ge 0x20000 -and $cp -le 0x2ffff) -or ($cp -ge 0x30000 -and $cp -le 0x323af)) { $count++ }
                $i++
            }
        }
        $pass = if ($Language -eq 'en') { $count -eq 0 } else { $count -gt 0 }
        Write-Host "COUNT language=$Language file=$file bytes=$($bytes.Length) CJK=$count pass=$pass"
        if (-not $pass) { $ok = $false }
    }
    return $ok
}
try {
    if ($PSCmdlet.ParameterSetName -eq 'Count') {
        if (Assert-LogCounts $CountFiles $Expected) { exit 0 } else { exit 1 }
    }
    . (Join-Path (Split-Path -Parent $PSScriptRoot) 'native\gui-probe-support.ps1')
    Initialize-ProbeEnvironment $JavaHome $TempRoot
    $OutDir = New-ProbeDirectory $OutDir
    $build = Build-GuiProbe (Join-Path $OutDir 'jvm-build') $JavaHome '' -JvmOnly
    $fixture = Join-Path $OutDir 'fixture'
    New-ProbeFixture $fixture
    $failed = $false
    foreach ($locale in @('en_US','zh_CN')) {
        $root = Join-Path $OutDir $locale
        $work = Join-Path $root 'work'
        $code = Invoke-GuiAcquisition 'jvm' 'purity' $build $JavaHome $Backend $work $root $TempRoot $fixture $locale
        if ($code -ne 0) { throw "$locale workflow failed; no purity conclusion" }
        $events = [IO.File]::ReadAllText((Join-Path $work 'backend-events.txt'), $logUtf8)
        if (-not $events.Contains('log.imageextractor.01') -or -not $events.Contains('WARN')) {
            throw 'Missing actual extraction warning backendLog control'
        }
        $files = @((Join-Path $work 'logs\mht-extractor.log'), (Join-Path $work 'logs\backend.log'),
            (Join-Path $work 'forwarded-backend.log'))
        if ($InjectCjk -and $locale -eq 'en_US') {
            [IO.File]::AppendAllText($files[0], ([string][char]0x4e2d + [char]0x6587 + "`n"), $logUtf8)
            Write-Host 'INJECTED two Han characters into isolated en application log'
        }
        $language = $locale.Substring(0,2)
        if (-not (Assert-LogCounts $files $language)) { $failed = $true }
        # A separate implementation in a separate process independently reads the raw bytes.
        $verifyArguments = @((Join-Path $script:ProbeRepo 'tools\verify\CjkLogCount.java'), $language) + $files
        $verify = Invoke-ProbeProcess 'independent-count' (Join-Path $JavaHome 'bin\java.exe') `
            $verifyArguments $OutDir $root 60
        if ($verify -ne 0) { $failed = $true }
    }
    if ($failed) { Write-Host '[FAIL] Cross-process log purity'; exit 1 }
    Write-Host '[PASS] en CJK=0 and zh CJK>0 in GUI/backend/forwarded backendLog text'
    exit 0
} catch { Write-Host ('[FAIL] ' + $_.Exception.Message); exit 1 }
