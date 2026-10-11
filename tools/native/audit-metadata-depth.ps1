<#
  Compare the union of reflection registrations across GUI JSON files and headless JSON.
  Bare means no effective member registration (type/name and conditions do not add depth).
  Duplicate entries are merged per side, including legacy reflect-config.json name entries.
  A candidate is evidence for investigation, never permission to add all registrations.
  Exit: 0 = no candidates, 1 = candidates, 2 = invalid inputs.
#>
[CmdletBinding()]
param([string]$Repo = '', [string]$GuiMetadataDir = '', [string]$HeadlessMetadataDir = '')
$ErrorActionPreference = 'Stop'
if (-not $Repo) { $Repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
if (-not $GuiMetadataDir) { $GuiMetadataDir = Join-Path $Repo 'src\main\resources\META-INF\native-image\com.mhtextractor' }
if (-not $HeadlessMetadataDir) { $HeadlessMetadataDir = Join-Path $Repo 'tools\native\headless' }
function Read-Registrations([string]$Directory) {
    $types = @{}
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter '*.json' -File -Recurse | Sort-Object FullName)
    if (-not $files.Count) { throw "No metadata JSON in $Directory" }
    foreach ($file in $files) {
        $source = [IO.File]::ReadAllText($file.FullName)
        $null = ConvertFrom-Json -InputObject $source
        # Track JSON object spans without confusing braces/quotes inside string values.
        $stack = New-Object 'System.Collections.Generic.Stack[int]'
        $inString = $false
        $escaped = $false
        for ($i = 0; $i -lt $source.Length; $i++) {
            $ch = $source[$i]
            if ($inString) {
                if ($escaped) { $escaped = $false }
                elseif ($ch -eq '\') { $escaped = $true }
                elseif ($ch -eq '"') { $inString = $false }
                continue
            }
            if ($ch -eq '"') { $inString = $true; continue }
            if ($ch -eq '{') { $stack.Push($i); continue }
            if ($ch -ne '}') { continue }
            $start = $stack.Pop()
            $object = ConvertFrom-Json -InputObject $source.Substring($start, $i - $start + 1)
            $type = $null
            if ($object.type -is [string]) { $type = $object.type }
            elseif ($file.Name -eq 'reflect-config.json' -and $object.name -is [string]) { $type = $object.name }
            if (-not $type) { continue }
            if (-not $types.ContainsKey($type)) { $types[$type] = New-Object System.Collections.ArrayList }
            $members = @()
            foreach ($property in $object.PSObject.Properties) {
                if ($property.Name -notmatch '(?i)(method|field|constructor|class|recordcomponent|permittedsubclass|nestmember)') { continue }
                if ($property.Value -is [bool] -and -not $property.Value) { continue }
                if ($null -eq $property.Value -or @($property.Value).Count -eq 0) { continue }
                $members += ($property.Name + '=' + (ConvertTo-Json -InputObject $property.Value -Depth 50 -Compress))
            }
            $line = 1 + ([regex]::Matches($source.Substring(0, $start), "`n")).Count
            [void]$types[$type].Add([pscustomobject]@{ Evidence = ($file.FullName + ':' + $line); Members = $members })
        }
    }
    return $types
}
try {
    $gui = Read-Registrations $GuiMetadataDir
    $headless = Read-Registrations $HeadlessMetadataDir
    $candidates = 0
    $shared = 0
    foreach ($type in ($gui.Keys | Sort-Object)) {
        if (-not $headless.ContainsKey($type)) { continue }
        $shared++
        $g = @($gui[$type] | ForEach-Object { $_.Members })
        $h = @($headless[$type] | ForEach-Object { $_.Members })
        if (($g.Count -eq 0) -eq ($h.Count -eq 0)) { continue }
        $candidates++
        $bareSide = if ($h.Count -eq 0) { 'HEADLESS' } else { 'GUI' }
        Write-Output ("CANDIDATE $type | $bareSide lacks member registrations")
        foreach ($entry in $gui[$type]) {
            Write-Output ('  GUI ' + $entry.Evidence + ' | ' + $(if ($entry.Members.Count) { $entry.Members -join '; ' } else { 'BARE' }))
        }
        foreach ($entry in $headless[$type]) {
            Write-Output ('  HEADLESS ' + $entry.Evidence + ' | ' + $(if ($entry.Members.Count) { $entry.Members -join '; ' } else { 'BARE' }))
        }
    }
    Write-Output ("[depth] GUI_TYPES=$($gui.Count) HEADLESS_TYPES=$($headless.Count) SHARED=$shared CANDIDATES=$candidates")
    Write-Output '[depth] Candidates require runtime/path evidence; do not bulk-register to clear this list.'
    if ($candidates) { exit 1 }
    exit 0
} catch {
    [Console]::Error.WriteLine('[depth] ERROR: ' + $_.Exception.Message)
    exit 2
}
