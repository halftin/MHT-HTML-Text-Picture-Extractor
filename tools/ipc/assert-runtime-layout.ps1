# Runtime data layout. Exit 0 = pass, 1 = fail. ASCII-only; CRLF.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Dist,
    [switch]$ExpectBackendLog
)
$ErrorActionPreference = 'Stop'
$errors = @()
try {
    $root = [IO.Path]::GetFullPath($Dist)
    if (-not (Test-Path -LiteralPath (Join-Path $root 'logs\mht-extractor.log') -PathType Leaf)) {
        $errors += 'missing logs\mht-extractor.log in the distribution root'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $root 'Backend\temp') -PathType Container)) {
        $errors += 'missing Backend\temp in the distribution root'
    }
    if ($ExpectBackendLog -and -not (Test-Path -LiteralPath (Join-Path $root 'logs\backend.log') -PathType Leaf)) {
        $errors += 'missing logs\backend.log in the distribution root'
    }
    $outputName = -join ([char[]]@(0x8F93, 0x51FA, 0x6587, 0x4EF6))
    foreach ($name in @('logs', 'config', $outputName)) {
        if (Test-Path -LiteralPath (Join-Path $root ('Backend\' + $name))) {
            $errors += ('unexpected Backend\' + $name + '; data belongs in the distribution root')
        }
    }
} catch {
    $errors += $_.Exception.Message
}
if ($errors.Count -gt 0) {
    Write-Output '[runtime-layout] FAIL'
    $errors | ForEach-Object { Write-Output ('  - ' + $_) }
    exit 1
}
Write-Output '[runtime-layout] OK: logs in root; temporary data in Backend\temp'
exit 0
