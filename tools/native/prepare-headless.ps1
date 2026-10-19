# Input assembly only. The native-image invocation lives in build-native.cmd.
param(
    [Parameter(Mandatory = $true)][string]$Repo,
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [Parameter(Mandatory = $true)][string]$OutputDir,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$DefaultCharset
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$utf8 = New-Object System.Text.UTF8Encoding($false)
$root = [IO.Path]::GetFullPath($WorkDir).TrimEnd('\')
if (-not $root.StartsWith('H:\TEST\mht\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Headless work directory must be under H:\TEST\mht\.'
}
$dist = [IO.Path]::GetFullPath($OutputDir).TrimEnd('\')
if (-not $dist.StartsWith('H:\TEST\mht\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Headless dist must be under H:\TEST\mht\.'
}
foreach ($dir in @($root,$dist)) {
    if ((Test-Path -LiteralPath $dir) -and
        ((Get-Item -LiteralPath $dir).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Headless work/dist must be physical H-drive directories: $dir"
    }
}
if (Test-Path -LiteralPath (Join-Path $dist 'MhtExtractor.exe')) {
    throw 'Refusing to use a GUI dist for the headless target.'
}
# Reports/metadata must belong to this build, never an older native-image run.
foreach ($name in @('reports','metadata')) {
    $old = [IO.Path]::GetFullPath((Join-Path $dist $name))
    if (-not $old.StartsWith($dist + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unsafe headless artifact subdirectory.'
    }
    if (Test-Path -LiteralPath $old) {
        if ((Get-Item -LiteralPath $old).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Refusing to delete a reparse point: $old"
        }
        Remove-Item -LiteralPath $old -Recurse -Force
    }
}
# Delete only our named input directory after verifying its absolute boundary.
$inputDir = Join-Path $root 'input'
if (-not ([IO.Path]::GetFullPath($inputDir)).StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Unsafe headless input directory.'
}
if (Test-Path -LiteralPath $inputDir) {
    if ((Get-Item -LiteralPath $inputDir).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Refusing to delete a reparse point: $inputDir"
    }
    Remove-Item -LiteralPath $inputDir -Recurse -Force
}
$classes = Join-Path $inputDir 'classes'
$libs = Join-Path $inputDir 'lib'
$metadata = Join-Path $inputDir 'metadata'
New-Item -ItemType Directory -Path $classes,$libs,$metadata -Force | Out-Null
$packages = @('app','backend','engine','cache','config','core','extractor','i18n','model',
    'output','platform','processor','uicore','uiport','util')
$classCount = 0
foreach ($package in $packages) {
    $source = Join-Path $Repo ('target\classes\com\mhtextractor\' + $package)
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing compiled package: $source" }
    foreach ($file in Get-ChildItem -LiteralPath $source -Filter '*.class' -Recurse -File) {
        $bytes = [IO.File]::ReadAllBytes($file.FullName)
        if ([Text.Encoding]::GetEncoding(28591).GetString($bytes) -match 'javafx[./]') {
            throw "JavaFX reference in headless class: $($file.FullName)"
        }
        $relative = $file.FullName.Substring((Join-Path $Repo 'target\classes').Length + 1)
        $dest = Join-Path $classes $relative
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($dest)) -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $dest
        $classCount++
    }
}
# Business locale resources, headless logging and the compile-time handshake identity.
Copy-Item -LiteralPath (Join-Path $Repo 'target\classes\i18n') -Destination $classes -Recurse
Copy-Item -LiteralPath (Join-Path $Repo 'target\classes\logback-headless.xml') -Destination $classes
Copy-Item -LiteralPath (Join-Path $Repo 'target\classes\backend-build.properties') -Destination $classes
$jar = Join-Path $inputDir 'backend.jar'
& (Join-Path $JavaHome 'bin\jar.exe') --create --file $jar --main-class com.mhtextractor.backend.BackendCli -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed: $LASTEXITCODE" }
# Copy runtime jars, excluding all UI libraries. No shaded GUI jar on this classpath.
$dependencies = @(Get-ChildItem -LiteralPath (Join-Path $Repo 'target\lib') -Filter '*.jar' -File |
    Where-Object { $_.Name -notmatch '^(javafx-|atlantafx-|ikonli-)' } | Sort-Object Name)
if ($dependencies.Count -eq 0) { throw 'Missing runtime dependencies; run Maven package.' }
foreach ($dep in $dependencies) {
    $dest = Join-Path $libs $dep.Name
    Copy-Item -LiteralPath $dep.FullName -Destination $dest
    # webp ships two optional FX adapters and a module descriptor referencing FX.
    # Keep its pure Java decoder; trim only the isolated build-input copy.
    if ($dep.Name -like 'webp-*.jar') {
        $archive = [IO.Compression.ZipFile]::Open($dest, [IO.Compression.ZipArchiveMode]::Update)
        try {
            foreach ($entry in @($archive.Entries)) {
                if ($entry.FullName -match '^org/glavo/webp/javafx/' -or $entry.FullName -eq 'module-info.class') {
                    $entry.Delete()
                }
            }
        } finally { $archive.Dispose() }
    }
    $archive = [IO.Compression.ZipFile]::OpenRead($dest)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match '(^|/)(javafx/|com/sun/javafx/)|META-INF/native-image/') {
                throw "Unexpected UI class or automatic native metadata in dependency: $($dep.Name):$($entry.FullName)"
            }
        }
    } finally { $archive.Dispose() }
}
$subset = Join-Path $Repo 'tools\native\headless'
foreach ($file in Get-ChildItem -LiteralPath $subset -File) {
    if ($file.Extension -in @('.json','.properties')) {
        Copy-Item -LiteralPath $file.FullName -Destination $metadata
    }
}
& (Join-Path $Repo 'tools\native\assert-headless-metadata.ps1') -MetadataDir $metadata
if ($LASTEXITCODE -ne 0) { throw 'Headless metadata assertion failed.' }
$cp = (@($jar) + @($dependencies | ForEach-Object { Join-Path $libs $_.Name })) -join ';'
[IO.File]::WriteAllText((Join-Path $inputDir 'classpath.txt'), $cp, $utf8)
# Windows CRT argv is encoded using the system ANSI codepage. Graal's default
# UTF-8 image charset corrupts non-ASCII CLI arguments on a CP936 host.
if (-not $DefaultCharset) {
    $encoding = [Text.Encoding]::Default
    $DefaultCharset = switch ($encoding.CodePage) {
        936 { 'GBK' }
        932 { 'windows-31j' }
        950 { 'x-windows-950' }
        949 { 'x-windows-949' }
        default { $encoding.WebName }
    }
}
[IO.File]::WriteAllText((Join-Path $inputDir 'default-charset.txt'), $DefaultCharset, $utf8)
$manifest = @(Get-ChildItem -LiteralPath $inputDir -Recurse -File | Where-Object Name -ne 'manifest.tsv' |
    Sort-Object FullName | ForEach-Object {
        "{0}`t{1}`t{2}" -f $_.FullName.Substring($inputDir.Length + 1), $_.Length,
            (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower()
    })
[IO.File]::WriteAllLines((Join-Path $inputDir 'manifest.tsv'), [string[]]$manifest, $utf8)
"[headless] input: $classCount classes / $($dependencies.Count) runtime jars / no GUI jar or automatic metadata"
"[headless] classpath: $cp"
"[headless] explicit metadata: $metadata"
"[headless] Windows argument charset: $DefaultCharset"
