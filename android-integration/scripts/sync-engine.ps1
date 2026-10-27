<#
.SYNOPSIS
Builds the liveness engine and copies it into an Android Registration Client checkout.

.DESCRIPTION
Installs the engine modules (liveness-api, -core, -fake, -analyzer) with JDK 11 and Maven from
%USERPROFILE%\tools\liveness-env.ps1, then copies into <Clone>\android\liveness:

  libs\                                  the four engine jars
  src\main\assets\liveness\models\       the signed model bundle: the ONNX files listed in manifest.json (5 since F1), manifest.json, manifest.sig
  src\main\java\...\CameraXFrameSource.java   Part 1's camera source (android-liveness-frame-source)

The model files are copied byte for byte. Each ONNX file is checked against manifest.json
(size and SHA-256) and every copy against its source, so a stale or modified bundle fails
the sync instead of failing on the phone. engine-sync.txt records the engine commit (the last
commit that changed the engine modules, the models (not models/tools), the camera source or the parent pom) and
the SHA-256 of every copied file. The jars embed build time and git info, so a rebuilt jar has
a new SHA-256 even from the same commit; -SkipBuild copies the jars already built.

Edit these files in this repository and sync again; never edit the copies in the client.
The environment changes made for Maven are undone when the script ends.

.PARAMETER Clone
Root of the Android Registration Client checkout (the folder that holds android\settings.gradle).

.PARAMETER SkipBuild
Reuse the jars already in liveness-*\target instead of running Maven.

.PARAMETER RunTests
Run the engine tests while installing (slower; the default skips them).

.EXAMPLE
.\android-integration\scripts\sync-engine.ps1 -Clone $env:USERPROFILE\dev\android-registration-client
#>
param(
    [Parameter(Mandatory = $true)][string]$Clone,
    [switch]$SkipBuild,
    [switch]$RunTests
)

$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$cloneRoot = (Resolve-Path $Clone).Path
if (-not (Test-Path (Join-Path $cloneRoot 'android\settings.gradle'))) {
    throw "$cloneRoot is not an Android Registration Client checkout (android\settings.gradle is missing)"
}
$module = Join-Path $cloneRoot 'android\liveness'
$engineModules = @('liveness-api', 'liveness-core', 'liveness-fake', 'liveness-analyzer')

function Get-Sha256([string]$path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
}

function Copy-Verified([string]$source, [string]$target) {
    $dir = Split-Path -Parent $target
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    Copy-Item -LiteralPath $source -Destination $target -Force
    $expected = Get-Sha256 $source
    $actual = Get-Sha256 $target
    if ($expected -ne $actual) { throw "copy of $source differs from the source (SHA-256 $actual, expected $expected)" }
    return $actual
}

# 1. Build and install the engine.
if (-not $SkipBuild) {
    $saved = @{ JAVA_HOME = $env:JAVA_HOME; MAVEN_HOME = $env:MAVEN_HOME; PATH = $env:PATH }
    try {
        . (Join-Path $env:USERPROFILE 'tools\liveness-env.ps1')
        $mvnArgs = @('-q', '-Dgpg.skip')
        if (-not $RunTests) { $mvnArgs += '-DskipTests' }
        Push-Location $repo
        try {
            Write-Host "Installing the parent pom"
            & mvn @mvnArgs -N install
            if ($LASTEXITCODE -ne 0) { throw "mvn -N install failed ($LASTEXITCODE)" }
            foreach ($m in $engineModules) {
                Write-Host "Installing $m"
                & mvn @mvnArgs -f "$m\pom.xml" install
                if ($LASTEXITCODE -ne 0) { throw "mvn install of $m failed ($LASTEXITCODE)" }
            }
        } finally {
            Pop-Location
        }
    } finally {
        $env:JAVA_HOME = $saved.JAVA_HOME
        $env:MAVEN_HOME = $saved.MAVEN_HOME
        $env:PATH = $saved.PATH
    }
}

$record = New-Object System.Collections.Generic.List[string]
# The last commit that changed what is synced (not HEAD), so documentation-only commits leave the record unchanged.
# models/tools holds development tooling (Python), not the bundle: a change there is not an engine change.
$enginePaths = $engineModules + @('models', ':(exclude)models/tools', 'android-liveness-frame-source', 'pom.xml')
$commit = (& git -C $repo log -1 --format=%h -- $enginePaths).Trim()
$dirty = (& git -C $repo status --porcelain -- $enginePaths)
$record.Add("engine commit $commit" + $(if ($dirty) { ' (with uncommitted changes)' } else { '' }))

# 2. Jars.
$libs = Join-Path $module 'libs'
if (-not (Test-Path $libs)) { New-Item -ItemType Directory -Force $libs | Out-Null }
Get-ChildItem -LiteralPath $libs -Filter 'liveness-*.jar' | Remove-Item -Force
foreach ($m in $engineModules) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $repo "$m\target") -Filter "$m-*.jar" |
            Where-Object { $_.Name -match "^$m-[0-9][0-9.]*\.jar$" } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $jar) { throw "no $m jar in $m\target (build it, or drop -SkipBuild)" }
    $hash = Copy-Verified $jar.FullName (Join-Path $libs $jar.Name)
    $record.Add("libs/$($jar.Name) $hash")
    Write-Host ("  {0,-34} {1,10:N0} bytes" -f $jar.Name, $jar.Length)
}

# 3. Models: byte-exact, checked against the manifest.
$modelsSource = Join-Path $repo 'models'
$modelsTarget = Join-Path $module 'src\main\assets\liveness\models'
$manifest = Get-Content -Raw -LiteralPath (Join-Path $modelsSource 'manifest.json') | ConvertFrom-Json
$bundleFiles = @('manifest.json', 'manifest.sig')
foreach ($f in $manifest.files) {
    $path = Join-Path $modelsSource $f.name
    $size = (Get-Item -LiteralPath $path).Length
    if ($size -ne [long]$f.size) { throw "$($f.name): $size bytes, manifest.json says $($f.size)" }
    if ((Get-Sha256 $path) -ne $f.sha256.ToLowerInvariant()) { throw "$($f.name): SHA-256 does not match manifest.json" }
    $bundleFiles += $f.name
}
if (Test-Path $modelsTarget) {
    Get-ChildItem -LiteralPath $modelsTarget -File | Where-Object { $bundleFiles -notcontains $_.Name } | Remove-Item -Force
}
foreach ($name in $bundleFiles) {
    $hash = Copy-Verified (Join-Path $modelsSource $name) (Join-Path $modelsTarget $name)
    $record.Add("assets/liveness/models/$name $hash")
}
Write-Host "  bundle $($manifest.bundleVersion): $($bundleFiles.Count) files verified"

# 4. Part 1's camera source.
$cameraRel = 'io\mosip\registration\liveness\device\android\CameraXFrameSource.java'
$hash = Copy-Verified (Join-Path $repo "android-liveness-frame-source\src\main\java\$cameraRel") (Join-Path $module "src\main\java\$cameraRel")
$record.Add("src/main/java/$($cameraRel -replace '\\', '/') $hash")

# 5. Provenance (ASCII, LF, no BOM so git sees one stable file).
$text = "# Written by Mosip26 android-integration/scripts/sync-engine.ps1. Do not edit; sync again.`n" + (($record -join "`n") + "`n")
[System.IO.File]::WriteAllText((Join-Path $module 'engine-sync.txt'), $text, (New-Object System.Text.ASCIIEncoding))

Write-Host "Synced the engine ($commit) into $module"
