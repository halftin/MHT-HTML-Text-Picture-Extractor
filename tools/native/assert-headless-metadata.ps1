# Assert the exact metadata snapshot passed to native-image, including after build.
param(
    [Parameter(Mandatory = $true)][string]$MetadataDir,
    [string]$AnalysisDir
)
$ErrorActionPreference = 'Stop'
try {
    if (-not (Test-Path -LiteralPath (Join-Path $MetadataDir 'reachability-metadata.json'))) {
        throw 'Missing headless reachability-metadata.json.'
    }
    $files = @(Get-ChildItem -LiteralPath $MetadataDir -Recurse -File |
        Where-Object { $_.Extension -in @('.json','.properties') })
    foreach ($file in $files) {
        $text = [IO.File]::ReadAllText($file.FullName)
        if ($file.Extension -eq '.json') {
            # Validate JSON, then decode Unicode escapes before the text assertion.
            $null = ConvertFrom-Json -InputObject $text
            $text = [regex]::Replace($text, '\\u([0-9a-fA-F]{4})', {
                param($match)
                [string][char][Convert]::ToInt32($match.Groups[1].Value, 16)
            })
        }
        if ($text -match 'javafx[./\\]') { throw "JavaFX entry in $($file.FullName)" }
    }
    "[headless] metadata assertion: $($files.Count) files / 0 JavaFX entries (decoded JSON + properties text)"
    if ($AnalysisDir) {
        $reports = @(Get-ChildItem -LiteralPath $AnalysisDir -Recurse -File |
            Where-Object { $_.Name -like 'class_initialization*.csv' })
        if ($reports.Count -eq 0) { throw 'Missing native-image class initialization analysis.' }
        foreach ($report in $reports) {
            if ([IO.File]::ReadAllText($report.FullName) -match 'javafx[./\\]') {
                throw "JavaFX type in native-image analysis: $($report.FullName)"
            }
        }
        "[headless] image analysis assertion: $($reports.Count) reports / 0 JavaFX types"
    }
    exit 0
} catch {
    [Console]::Error.WriteLine('[headless] metadata assertion FAILED: ' + $_.Exception.Message)
    exit 1
}
