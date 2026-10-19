# Partition shared agent registrations for the GUI; keep excluded business entries as evidence.
param(
    [Parameter(Mandatory = $true)][string]$Repo,
    [Parameter(Mandatory = $true)][string]$OutputDir,
    [string]$InputJar,
    [string]$OutputJar,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
$utf8 = New-Object Text.UTF8Encoding($false)
$source = Join-Path $Repo 'src/main/resources/META-INF/native-image/com.mhtextractor'
$business = '^(com\.mhtextractor\.(app|engine|backend|core|extractor|processor|output|cache|util|config)\.|org\.jsoup\.)'
$names = @('reflect-config.json', 'proxy-config.json', 'reachability-metadata.json')
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

function Entries($data, [string]$name) {
    if ($name -eq 'reachability-metadata.json') { return $data.reflection }
    return $data
}
function Business-Type($entry, [string]$name) {
    if ($name -eq 'proxy-config.json') { return @($entry.interfaces | Where-Object { $_ -match $business } | Select-Object -First 1) }
    $type = if ($name -eq 'reflect-config.json') { $entry.name } else { $entry.type }
    if ($type -is [string] -and $type -match $business) { return $type -replace '(\[\])+$', '' }
    return $null
}
function Json($data) {
    # PS 5.1 may attach Count to a parsed array's PSObject wrapper. Serialize a fresh array.
    if ($data -is [array]) { return ConvertTo-Json -InputObject @($data) -Depth 100 -Compress }
    return ConvertTo-Json -InputObject $data -Depth 100 -Compress
}
$manifest = @()
foreach ($name in $names) {
    $originalFile = Join-Path $source $name
    $outputFile = Join-Path $OutputDir $name
    $businessFile = Join-Path $OutputDir ('business-' + $name)
    if ([IO.Path]::GetFullPath($originalFile) -eq [IO.Path]::GetFullPath($outputFile)) { throw 'Output must not overwrite source metadata' }
    $original = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($originalFile))
    $originalJson = Json $original
    $originalEntries = @(Entries $original $name)
    if (-not $VerifyOnly) {
        $frontend = @()
        $excluded = @()
        for ($i = 0; $i -lt $originalEntries.Count; $i++) {
            $entry = $originalEntries[$i]
            if (Business-Type $entry $name) { $excluded += [pscustomobject]@{ index = $i; registration = $entry } }
            else { $frontend += $entry }
        }
        if ($name -eq 'reachability-metadata.json') { $original.reflection = @($frontend) }
        else { $original = @($frontend) }
        [IO.File]::WriteAllText($outputFile, (Json $original), $utf8)
        [IO.File]::WriteAllText($businessFile, (Json @($excluded)), $utf8)
    }

    # Reconstruct the complete original from both partitions, checking each registration/member.
    $original = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($originalFile))
    $originalEntries = @(Entries $original $name)
    $actual = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($outputFile))
    $actualEntries = @(Entries $actual $name)
    $excludedData = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($businessFile))
    $excluded = @(Entries $excludedData 'business')
    if ($originalEntries.Count -ne ($actualEntries.Count + $excluded.Count)) { throw "Registration count changed: $name" }
    $frontIndex = 0
    $businessIndex = 0
    for ($i = 0; $i -lt $originalEntries.Count; $i++) {
        if (Business-Type $originalEntries[$i] $name) {
            if ($businessIndex -ge $excluded.Count -or $excluded[$businessIndex].index -ne $i -or
                (Json $excluded[$businessIndex].registration) -cne (Json $originalEntries[$i])) {
                throw "Business registration/member loss or wrong partition: $name entry $i"
            }
            $businessIndex++
        } else {
            if ($frontIndex -ge $actualEntries.Count -or (Json $actualEntries[$frontIndex]) -cne (Json $originalEntries[$i])) {
                throw "Frontend registration/member loss or wrong partition: $name entry $i"
            }
            $frontIndex++
        }
    }
    if ($frontIndex -ne $actualEntries.Count -or $businessIndex -ne $excluded.Count) { throw "Wrong partition: $name" }
    if ($name -eq 'reachability-metadata.json') { $actual.reflection = @($originalEntries) }
    else { $actual = @($originalEntries) }
    if ((Json $actual) -cne $originalJson) { throw "Unrelated metadata change: $name" }
    $manifest += [pscustomobject][ordered]@{
        file = $name
        entries = $originalEntries.Count
        frontendEntries = $frontIndex
        excludedBusinessEntries = $businessIndex
        unionRegistrationLoss = 0
        sourceSha256 = (Get-FileHash -LiteralPath $originalFile -Algorithm SHA256).Hash
        generatedSha256 = (Get-FileHash -LiteralPath $outputFile -Algorithm SHA256).Hash
        businessSha256 = (Get-FileHash -LiteralPath $businessFile -Algorithm SHA256).Hash
    }
    Write-Output "[frontend-metadata] $name source=$($originalEntries.Count) frontend=$frontIndex excludedBusiness=$businessIndex unionRegistrationLoss=0"
}
if (-not $VerifyOnly) {
    [IO.File]::WriteAllText((Join-Path $OutputDir 'preservation.json'), (ConvertTo-Json -InputObject $manifest -Depth 10), $utf8)
}

# Unified and legacy metadata must come from the same input. A GUI-only jar copy also avoids
# automatic discovery of the original unified file alongside an external configuration directory.
if ($InputJar -or $OutputJar) {
    if (-not $InputJar -or -not $OutputJar) { throw 'Pass both InputJar and OutputJar' }
    $InputJar = [IO.Path]::GetFullPath($InputJar)
    $OutputJar = [IO.Path]::GetFullPath($OutputJar)
    if ($InputJar -eq $OutputJar) { throw 'GUI input must not overwrite the shared jar' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.IO.Compression
    $prefix = 'META-INF/native-image/com.mhtextractor/'
    $replacements = @($names | ForEach-Object { $prefix + $_ })
    $inputArchive = [IO.Compression.ZipFile]::OpenRead($InputJar)
    try {
        if (-not $VerifyOnly) {
            Copy-Item -LiteralPath $InputJar -Destination $OutputJar -Force
            $outputArchive = [IO.Compression.ZipFile]::Open($OutputJar, [IO.Compression.ZipArchiveMode]::Update)
            try {
                foreach ($name in $names) {
                    $reader = New-Object IO.StreamReader($inputArchive.GetEntry($prefix + $name).Open())
                    try { $embedded = $reader.ReadToEnd() } finally { $reader.Dispose() }
                    if ($embedded -cne [IO.File]::ReadAllText((Join-Path $source $name))) { throw "Shared jar has stale source metadata: $name" }
                    $outputArchive.GetEntry($prefix + $name).Delete()
                    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($outputArchive, (Join-Path $OutputDir $name), $prefix + $name)
                }
            } finally { $outputArchive.Dispose() }
        }
        $outputArchive = [IO.Compression.ZipFile]::OpenRead($OutputJar)
        try {
            if ($inputArchive.Entries.Count -ne $outputArchive.Entries.Count) { throw 'GUI jar entry loss' }
            $sha = [Security.Cryptography.SHA256]::Create()
            try {
                foreach ($entry in $inputArchive.Entries) {
                    $copy = $outputArchive.GetEntry($entry.FullName)
                    if (-not $copy) { throw "GUI jar entry missing: $($entry.FullName)" }
                    if ($entry.Length -eq 0 -and $copy.Length -eq 0) { continue }
                    $expected = if ($replacements -contains $entry.FullName) {
                        [IO.File]::OpenRead((Join-Path $OutputDir $entry.Name))
                    } else { $entry.Open() }
                    $actual = $copy.Open()
                    try {
                        $a = [Convert]::ToBase64String($sha.ComputeHash($expected))
                        $b = [Convert]::ToBase64String($sha.ComputeHash($actual))
                        if ($a -cne $b) { throw "GUI jar entry changed unexpectedly: $($entry.FullName) inputLength=$($entry.Length) outputLength=$($copy.Length) expected=$a actual=$b" }
                    } finally { $expected.Dispose(); $actual.Dispose() }
                }
            } finally { $sha.Dispose() }
            Write-Output "[frontend-metadata] jarEntries=$($inputArchive.Entries.Count) replaced=3 otherEntryChanges=0"
        } finally { $outputArchive.Dispose() }
    } finally { $inputArchive.Dispose() }
    if (-not $VerifyOnly) {
        $jarManifest = [ordered]@{
            sourceJar = $InputJar
            sourceSha256 = (Get-FileHash -LiteralPath $InputJar -Algorithm SHA256).Hash
            preparedJar = $OutputJar
            preparedSha256 = (Get-FileHash -LiteralPath $OutputJar -Algorithm SHA256).Hash
        }
        [IO.File]::WriteAllText((Join-Path $OutputDir 'input-jar.json'), (ConvertTo-Json $jarManifest), $utf8)
    }
}
