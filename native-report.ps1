# native-report.ps1 - emit a machine-readable build report for the native packaging run.
#
# The agent (or a human) should be able to answer "what exactly happened and what came
# out" from this one file, without parsing prose. Context is passed through environment
# variables (NB_*) so nothing has to survive a cmd -> PowerShell quoting round trip.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI.
#
# Inputs (environment):
#   NB_STEPS    TSV written by build-native.cmd: step<TAB>name<TAB>begin<TAB>end<TAB>exit
#   NB_OUT      path of the JSON report to write
#   NB_LOG      path of the master log
#   NB_STATUS   OK | PREPARE_ONLY | FAILED
#   NB_FAILSTEP failing step label (may be empty)
#   NB_REPO, NB_DIST, NB_APKVERSION, NB_JFXVERSION, NB_JDK, NB_NATIVEIMAGE,
#   NB_MAVEN, NB_VSPATH, NB_XMX

$ErrorActionPreference = 'Stop'

function Get-StepList([string]$path) {
    $list = New-Object System.Collections.ArrayList
    if (-not (Test-Path -LiteralPath $path)) { return $list }
    foreach ($line in [System.IO.File]::ReadAllLines($path)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $f = $line -split "`t"
        if ($f.Count -lt 5) { continue }
        $item = [ordered]@{
            step  = $f[0]
            name  = $f[1]
            begin = $f[2]
            end   = $f[3]
            exit  = [int]$f[4]
        }
        try {
            $b = [datetime]::Parse($f[2], [Globalization.CultureInfo]::InvariantCulture)
            $e = [datetime]::Parse($f[3], [Globalization.CultureInfo]::InvariantCulture)
            $item['durationSec'] = [math]::Round(($e - $b).TotalSeconds, 1)
        } catch {
            $item['durationSec'] = $null
        }
        [void]$list.Add([pscustomobject]$item)
    }
    return $list
}

function Get-ArtifactList([string]$dir) {
    $list = New-Object System.Collections.ArrayList
    if (-not (Test-Path -LiteralPath $dir)) { return $list }
    foreach ($f in Get-ChildItem -LiteralPath $dir -File -Recurse | Sort-Object FullName) {
        $hash = ''
        try { $hash = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLower() } catch { }
        [void]$list.Add([pscustomobject][ordered]@{
            name   = $f.Name
            path   = $f.FullName.Substring($dir.Length).TrimStart('\')
            bytes  = $f.Length
            sha256 = $hash
        })
    }
    return $list
}

$steps = Get-StepList $env:NB_STEPS
$artifacts = Get-ArtifactList $env:NB_DIST

# The jar is the *input* of the data flow; record it so the report traces input -> output.
$jarInfo = $null
$jarPath = Join-Path $env:NB_REPO ("target\mhtExtractor-{0}.jar" -f $env:NB_APKVERSION)
if (Test-Path -LiteralPath $jarPath) {
    $ji = Get-Item -LiteralPath $jarPath
    $jarInfo = [pscustomobject][ordered]@{
        path   = 'target/mhtExtractor-' + $env:NB_APKVERSION + '.jar'
        bytes  = $ji.Length
        sha256 = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLower()
    }
}

$gitCommit = ''
try { $gitCommit = (& git rev-parse --short HEAD 2>$null) } catch { }

$report = [pscustomobject][ordered]@{
    schema      = 'mhtextractor/native-build-report/1'
    generatedAt = (Get-Date).ToString('o')
    status      = $env:NB_STATUS
    failedStep  = $env:NB_FAILSTEP
    repo        = $env:NB_REPO
    gitCommit   = $gitCommit
    log         = $env:NB_LOG
    context     = [pscustomobject][ordered]@{
        appVersion    = $env:NB_APKVERSION
        javafxVersion = $env:NB_JFXVERSION
        jdk           = $env:NB_JDK
        nativeImage   = $env:NB_NATIVEIMAGE
        maven         = $env:NB_MAVEN
        vsPath        = $env:NB_VSPATH
        nativeXmx     = $env:NB_XMX
        outputDir     = $env:NB_DIST
    }
    input       = $jarInfo
    # 必须显式包成数组：PS 5.1 的 ConvertTo-Json 会把**空集合**渲染成 {}（对象），
    # 而不是 []（数组）—— 会让下游解析器读到与 schema 不符的类型。
    steps       = @($steps)
    artifacts   = @($artifacts)
}

$json = $report | ConvertTo-Json -Depth 6
$outDir = Split-Path -Parent $env:NB_OUT
if ($outDir -and -not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}
[System.IO.File]::WriteAllText($env:NB_OUT, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Output ("[report] " + $env:NB_OUT)
