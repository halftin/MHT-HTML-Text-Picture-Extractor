<#
  audit-native-metadata.ps1 - find STALE entries in the GraalVM reachability metadata.

  A stale entry is a registered type whose class does not exist in ANY artifact the native
  build actually sees: the classpath (target/lib/*.jar), the shaded app jar, target/classes,
  and the JDK module images (both the build JDK and the native-image JDK).

  Why: the metadata is a union of many tracing-agent runs accumulated over time. Classes that
  were removed by a JavaFX upgrade or by a project refactor keep their registrations forever
  (e.g. JavaFX-8-only com.sun.javafx.scene.control.skin.*Skin, or the app's own deleted
  JavaFxUtil$SmoothProgressProperty). They do not break the build, but they make it impossible
  to tell which registrations are real.

  Usage:
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\native\audit-native-metadata.ps1
    ... -ExtraJdk "D:\path\to\graalvm" -WritePlan .claude-work\stale-plan.json

  Exit codes: 0 = audit ran (stale count may be > 0), 1 = inputs missing.
#>
[CmdletBinding()]
param(
    [string]$Repo = "",
    [string]$MetaDir = "",
    [string]$JdkHome = $env:JAVA_HOME,
    [string[]]$ExtraJdk = @(),
    [string]$WritePlan = "",
    [string]$AgentDir = ""
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Repo)) {
    $Repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    if (-not (Test-Path (Join-Path $Repo 'build-native.cmd'))) { $Repo = (Get-Location).Path }
}
if ([string]::IsNullOrWhiteSpace($MetaDir)) { $MetaDir = Join-Path $Repo 'src\main\resources\META-INF\native-image\com.mhtextractor' }
if (-not (Test-Path $MetaDir)) { Write-Host "[audit] [ERROR] metadata dir not found: $MetaDir"; exit 1 }
if (-not (Test-Path (Join-Path $Repo 'target\classes'))) { Write-Host "[audit] [ERROR] target/classes missing - run a package first"; exit 1 }

Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---- per-file registered types ----
$perFile = @{}
$all = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($f in (Get-ChildItem $MetaDir -Filter '*.json' | Sort-Object Name)) {
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    try {
        $obj = ([System.IO.File]::ReadAllText($f.FullName).TrimStart([char]0xFEFF)) | ConvertFrom-Json
        $stack = New-Object System.Collections.Stack
        $stack.Push($obj)
        while ($stack.Count -gt 0) {
            $cur = $stack.Pop()
            if ($null -eq $cur) { continue }
            if ($cur -is [System.Management.Automation.PSCustomObject]) {
                foreach ($p in $cur.PSObject.Properties) {
                    if (($p.Name -eq 'type' -or $p.Name -eq 'name') -and $p.Value -is [string] -and $p.Value -match '\.' -and $p.Value -notmatch '/') { [void]$set.Add($p.Value); [void]$all.Add($p.Value) }
                    elseif ($p.Value -is [System.Management.Automation.PSCustomObject] -or $p.Value -is [System.Array]) { $stack.Push($p.Value) }
                }
            } elseif ($cur -is [System.Array]) { foreach ($x in $cur) { $stack.Push($x) } }
        }
    } catch { Write-Host ("[audit] [warn] cannot parse " + $f.Name + ": " + $_.Exception.Message) }
    $perFile[$f.Name] = $set
}
Write-Host ("[audit] registered types = " + $all.Count)

# ---- available classes ----
$avail = New-Object 'System.Collections.Generic.HashSet[string]'
function Add-Jar([string]$p) {
    if (-not (Test-Path $p)) { return }
    try {
        $z = [System.IO.Compression.ZipFile]::OpenRead($p)
        foreach ($e in $z.Entries) { if ($e.FullName.EndsWith('.class')) { [void]$script:avail.Add((($e.FullName -replace '/', '.') -replace '\.class$', '')) } }
        $z.Dispose()
    } catch { }
}
foreach ($j in (Get-ChildItem (Join-Path $Repo 'target\lib') -Filter '*.jar' -ErrorAction SilentlyContinue).FullName) { Add-Jar $j }
$shaded = Get-ChildItem (Join-Path $Repo 'target') -Filter 'mhtExtractor-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($shaded) { Add-Jar $shaded.FullName }
$classesDir = Join-Path $Repo 'target\classes'
Get-ChildItem $classesDir -Recurse -Filter '*.class' | ForEach-Object {
    $rel = $_.FullName.Substring($classesDir.Length + 1)
    [void]$avail.Add((($rel -replace '\\', '.') -replace '\.class$', ''))
}
Write-Host ("[audit] classpath + JavaFX + app classes = " + $avail.Count)

$jdkList = @()
if ($JdkHome) { $jdkList += $JdkHome }
$jdkList += $ExtraJdk
foreach ($jdk in ($jdkList | Where-Object { $_ } | Sort-Object -Unique)) {
    $ji = Join-Path $jdk 'bin\jimage.exe'
    $mods = Join-Path $jdk 'lib\modules'
    if (-not (Test-Path $ji) -or -not (Test-Path $mods)) { Write-Host ("[audit] [warn] skip JDK (no jimage): " + $jdk); continue }
    $n = 0
    foreach ($l in (& $ji list $mods 2>$null)) {
        $line = ([string]$l).Trim()
        if ($line -eq '' -or $line.StartsWith('Module:') -or $line.StartsWith('jimage:')) { continue }
        if ($line.EndsWith('.class')) {
            $p = $line
            if ($p.StartsWith('/')) { $p = $p.Substring(1); $i = $p.IndexOf('/'); if ($i -ge 0) { $p = $p.Substring($i + 1) } }
            [void]$avail.Add((($p -replace '/', '.') -replace '\.class$', ''))
            $n++
        }
    }
    Write-Host ("[audit] JDK " + (Split-Path $jdk -Leaf) + " classes = " + $n)
}
Write-Host ("[audit] total available = " + $avail.Count)

# ---- classify ----
$plan = New-Object System.Collections.ArrayList
foreach ($t in ($all | Sort-Object)) {
    $base = $t
    while ($base.EndsWith('[]')) { $base = $base.Substring(0, $base.Length - 2) }
    if (-not $avail.Contains($base)) {
        $where = @()
        foreach ($k in ($perFile.Keys | Sort-Object)) { if ($perFile[$k].Contains($t)) { $where += $k } }
        [void]$plan.Add([pscustomobject]@{ type = $t; files = $where })
    }
}
Write-Host ""
Write-Host ("[audit] STALE = " + $plan.Count + " / " + $all.Count)
foreach ($p in $plan) { Write-Host ("STALE   " + $p.type + "   [" + ($p.files -join ', ') + "]") }
# ---- optional: diff a tracing-agent capture against the metadata ----
# Only a captured type that ACTUALLY EXISTS and is NOT registered is a real gap. The agent also
# records failed class lookups (FXMLLoader probes javafx.scene.control.<Tag> before
# javafx.scene.layout.<Tag>), so an unfiltered diff reports phantom "missing" entries.
if ($AgentDir -ne '') {
    $af = Join-Path $AgentDir 'reachability-metadata.json'
    if (-not (Test-Path $af)) { Write-Host ("[audit] [ERROR] no agent output at " + $af); exit 1 }
    $obj = ([System.IO.File]::ReadAllText($af).TrimStart([char]0xFEFF)) | ConvertFrom-Json
    $capTypes = New-Object 'System.Collections.Generic.HashSet[string]'
    $capRes = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($r in @($obj.reflection)) { if ($r -and $r.type) { [void]$capTypes.Add($r.type) } }
    foreach ($r in @($obj.resources)) { if ($r) { $v = if ($r.glob) { $r.glob } elseif ($r.pattern) { $r.pattern } elseif ($r.bundle) { $r.bundle } else { $null }; if ($v) { [void]$capRes.Add($v) } } }
    $regRes = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($f in (Get-ChildItem $MetaDir -Filter '*.json')) {
        $o2 = ([System.IO.File]::ReadAllText($f.FullName).TrimStart([char]0xFEFF)) | ConvertFrom-Json
        $st = New-Object System.Collections.Stack; $st.Push($o2)
        while ($st.Count -gt 0) {
            $cur = $st.Pop(); if ($null -eq $cur) { continue }
            if ($cur -is [System.Management.Automation.PSCustomObject]) {
                foreach ($p in $cur.PSObject.Properties) {
                    if (($p.Name -eq 'glob' -or $p.Name -eq 'pattern' -or $p.Name -eq 'bundle') -and $p.Value -is [string]) { [void]$regRes.Add($p.Value) }
                    elseif ($p.Value -is [System.Management.Automation.PSCustomObject] -or $p.Value -is [System.Array]) { $st.Push($p.Value) }
                }
            } elseif ($cur -is [System.Array]) { foreach ($x in $cur) { $st.Push($x) } }
        }
    }
    $phantom = New-Object System.Collections.ArrayList
    $real = New-Object System.Collections.ArrayList
    foreach ($t in $capTypes) {
        if ($t.StartsWith('java.lang.invoke.LambdaForm')) { continue }
        if ($all.Contains($t)) { continue }
        $base = $t; while ($base.EndsWith('[]')) { $base = $base.Substring(0, $base.Length - 2) }
        if ($avail.Contains($base)) { [void]$real.Add($t) } else { [void]$phantom.Add($t) }
    }
    $missRes = @($capRes | Where-Object { -not $regRes.Contains($_) -and $_ -notmatch '\.class$' -and $_ -notmatch '^META-INF/native-image' })
    Write-Host ""
    Write-Host ("[audit] agent capture = " + $af)
    Write-Host ("[audit] captured types = " + $capTypes.Count + ", REAL unregistered = " + $real.Count + ", phantom (class absent) = " + $phantom.Count)
    foreach ($t in ($real | Sort-Object)) { Write-Host ("MISSING-TYPE   " + $t) }
    Write-Host ("[audit] captured resources not registered (notable) = " + $missRes.Count)
    foreach ($r in ($missRes | Select-Object -First 20)) { Write-Host ("MISSING-RES    " + $r) }
    if ($real.Count -gt 0 -or $missRes.Count -gt 0) { exit 1 }
}
if ($WritePlan -ne '') {
    $json = $plan | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($WritePlan, $json, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ("[audit] plan written to " + $WritePlan + " (no BOM)")
}
exit 0
