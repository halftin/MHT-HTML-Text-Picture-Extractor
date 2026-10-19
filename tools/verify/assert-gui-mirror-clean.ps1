<#
  Scan raw image bytes for business package markers (no decompilation).
  Exit 0 = expectation met, 1 = expectation violated, 2 = unreadable/empty input.
  -ExpectBusiness reverses the assertion for the companion backend (teeth).
  Latin-1 preserves every byte; PowerShell's -Encoding Byte / Get-Content pipeline
  must not be used: coercing an object[] to a string can hide every marker.
#>
[CmdletBinding()]
param(
    [string]$DistDir = 'dist',
    [string]$Exe = 'MhtExtractor.exe',
    [switch]$ExpectBusiness,
    [ValidateRange(1, 100)][int]$MaxSnippets = 20
)
$ErrorActionPreference = 'Stop'
try {
    $image = if ([IO.Path]::IsPathRooted($Exe)) { $Exe } else { Join-Path $DistDir $Exe }
    $image = [IO.Path]::GetFullPath($image)
    $bytes = [IO.File]::ReadAllBytes($image)
    if ($bytes.Length -eq 0) { throw 'Empty image' }
    $text = [Text.Encoding]::GetEncoding(28591).GetString($bytes)
} catch {
    Write-Output ('RESULT=FAIL (cannot read image: {0})' -f $_.Exception.Message)
    exit 2
}
$markers = @(
    'com/mhtextractor/core/', 'com/mhtextractor/extractor/',
    'com/mhtextractor/processor/', 'com/mhtextractor/output/',
    'com/mhtextractor/cache/', 'com/mhtextractor/util/',
    'com/mhtextractor/config/', 'com/mhtextractor/app/',
    'com/mhtextractor/engine/', 'org/jsoup/', 'com/mhtextractor/backend/'
)
$hits = 0
$snippets = [Collections.Generic.SortedSet[string]]::new([StringComparer]::Ordinal)
Write-Output ('IMAGE=' + $image)
Write-Output ('BYTES=' + $bytes.Length)
foreach ($marker in $markers) {
    $matches = [regex]::Matches($text, [regex]::Escape($marker))
    if ($matches.Count -gt 0) {
        $hits++
        Write-Output ('  {0} x{1}' -f $marker, $matches.Count)
        foreach ($match in $matches) {
            # Bound diagnostic storage as well as output; counts above remain complete.
            if ($snippets.Count -lt $MaxSnippets) {
                $length = [Math]::Min($text.Length - $match.Index, $marker.Length + 120)
                $snippet = [regex]::Match($text.Substring($match.Index, $length), '^[A-Za-z0-9_$/]+').Value
                [void]$snippets.Add($snippet)
            }
        }
    }
}
Write-Output ('HITS=' + $hits)
foreach ($snippet in $snippets) { Write-Output ('  SNIPPET=' + $snippet) }
if ($ExpectBusiness) {
    if ($hits -gt 0) { Write-Output 'RESULT=PASS (business markers present, as expected)'; exit 0 }
    Write-Output 'RESULT=FAIL (expected business markers, found none)'
    exit 1
}
if ($hits -eq 0) { Write-Output 'RESULT=PASS (no business markers)'; exit 0 }
Write-Output 'RESULT=FAIL (business markers in GUI image)'
exit 1
