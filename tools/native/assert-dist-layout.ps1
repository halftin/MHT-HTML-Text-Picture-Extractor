<#
  assert-dist-layout.ps1 - assert the P4-21 distribution split:
    * frontend MhtExtractor.exe (+ JavaFX DLLs for the dynamic recipe) stays in the dist root
    * the backend executable lives in <root>\Backend\ and NOT in the root
  Exit 0 = correct, 1 = wrong. ASCII-only; CRLF.
  Control group: run it on a fake dir that mimics the old flat layout -> must exit 1.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Dist,
    [ValidateSet('dynamic','static')][string]$Recipe = 'dynamic'
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($Dist)
$errors = @()
if (-not (Test-Path -LiteralPath (Join-Path $root 'MhtExtractor.exe'))) { $errors += 'missing frontend MhtExtractor.exe in the dist root' }
if (Test-Path -LiteralPath (Join-Path $root 'MhtExtractorBackend.exe')) { $errors += 'backend exe must NOT be in the dist root (it belongs in Backend\)' }
if (-not (Test-Path -LiteralPath (Join-Path $root 'Backend\MhtExtractorBackend.exe'))) { $errors += 'missing Backend\MhtExtractorBackend.exe' }
if ($Recipe -eq 'dynamic') {
    foreach ($dll in @('glass.dll','prism_common.dll','decora_sse.dll','javafx_font.dll','javafx_iio.dll')) {
        if (-not (Test-Path -LiteralPath (Join-Path $root $dll))) { $errors += ('missing dynamic frontend DLL in the root: ' + $dll) }
    }
}
if ($errors.Count -gt 0) {
    Write-Output '[dist-layout] FAIL'
    $errors | ForEach-Object { Write-Output ('  - ' + $_) }
    exit 1
}
Write-Output ('[dist-layout] OK recipe=' + $Recipe + ' : frontend in root, backend in Backend\')
exit 0
