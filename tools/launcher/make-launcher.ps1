# make-launcher.ps1 -- copy the dynamic-recipe launcher into a dist folder under
# its user-facing Chinese name.
#
# WHY A SCRIPT: build-native.cmd cannot create a file whose NAME contains non-ASCII
# characters. cmd.exe hands the raw bytes of the command line to the ANSI Win32 API,
# and build-native.cmd is stored as UTF-8, so a literal 启动.cmd inside that .cmd
# would produce a mojibake filename. PowerShell is Unicode-aware, so the literal
# lives here instead (this file carries a UTF-8 BOM on purpose -- see CLAUDE.md).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\launcher\make-launcher.ps1 -Dest <dist>
param(
    [Parameter(Mandatory = $true)][string]$Dest
)
$ErrorActionPreference = 'Stop'

$src = Join-Path $PSScriptRoot 'launch-dynamic.cmd'
if (-not (Test-Path -LiteralPath $src)) { throw "missing launcher template: $src" }
if (-not (Test-Path -LiteralPath $Dest)) { throw "missing destination folder: $Dest" }

$dst = Join-Path $Dest '启动.cmd'
$bytes = [System.IO.File]::ReadAllBytes($src)
[System.IO.File]::WriteAllBytes($dst, $bytes)

# ASCII-named twin, for users who unzip with a tool that mangles non-ASCII names.
$dstAscii = Join-Path $Dest 'Launch.cmd'
[System.IO.File]::WriteAllBytes($dstAscii, $bytes)

Write-Output ("launcher written: {0} ({1} bytes)" -f $dst, $bytes.Length)
