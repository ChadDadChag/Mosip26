<#
.SYNOPSIS
Regenerates the Android integration patches from an Android Registration Client checkout.

.DESCRIPTION
Writes, from <Clone>, the changes between the upstream tag (v1.1.1) and the integration branch
(person5-liveness):

  android-integration\patches\NNNN-*.patch     one patch per commit, in order (git am)
  android-integration\person5-liveness.diff    the same changes as one diff (git apply)

Files that sync-engine.ps1 copies from this repository are left out of both: the engine jars
(android/liveness/libs), the signed model bundle (android/liveness/src/main/assets/liveness/models)
and Part 1's CameraXFrameSource.java. android/liveness/engine-sync.txt stays in: it records the
engine commit and the SHA-256 of every synced file.

The patches keep the original commit ids in their "From" lines. They carry no signature line,
so regenerating from the same commits gives the same files.

.PARAMETER Clone
Root of the Android Registration Client checkout that has the branch and the tag.

.EXAMPLE
.\android-integration\scripts\export-patches.ps1 -Clone $env:USERPROFILE\dev\android-registration-client
#>
param(
    [Parameter(Mandatory = $true)][string]$Clone,
    [string]$Base = 'v1.1.1',
    [string]$Branch = 'person5-liveness'
)

$ErrorActionPreference = 'Stop'

$integration = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$cloneRoot = (Resolve-Path $Clone).Path

# Paths that sync-engine.ps1 regenerates; keep in step with that script.
$synced = @(
    'android/liveness/libs',
    'android/liveness/src/main/assets/liveness/models',
    'android/liveness/src/main/java/io/mosip/registration/liveness/device/android/CameraXFrameSource.java'
)
$pathspec = @('.') + ($synced | ForEach-Object { ":(exclude)$_" })

foreach ($ref in @($Base, $Branch)) {
    & git -C $cloneRoot rev-parse --verify --quiet "$ref^{commit}" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "$ref is not a commit in $cloneRoot" }
}

# 1. One patch per commit.
$patches = Join-Path $integration 'patches'
if (Test-Path $patches) {
    Get-ChildItem -LiteralPath $patches -Filter '*.patch' | Remove-Item -Force
} else {
    New-Item -ItemType Directory $patches | Out-Null
}
& git -C $cloneRoot format-patch --quiet --no-signature -o $patches "$Base..$Branch" -- @pathspec
if ($LASTEXITCODE -ne 0) { throw "git format-patch failed ($LASTEXITCODE)" }

# 2. The same changes as one diff (git writes the file itself, so the bytes are exact).
$diff = Join-Path $integration 'person5-liveness.diff'
& git -C $cloneRoot diff --binary "--output=$diff" $Base $Branch -- @pathspec
if ($LASTEXITCODE -ne 0) { throw "git diff failed ($LASTEXITCODE)" }

# 3. Check that no synced file slipped in.
$all = @(Get-ChildItem -LiteralPath $patches -Filter '*.patch') + @(Get-Item -LiteralPath $diff)
foreach ($f in $all) {
    foreach ($p in $synced) {
        if (Select-String -LiteralPath $f.FullName -SimpleMatch -Pattern "diff --git a/$p" -Quiet) {
            throw "$($f.Name) contains $p, which sync-engine.ps1 provides"
        }
    }
}

$count = (Get-ChildItem -LiteralPath $patches -Filter '*.patch').Count
$baseId = (& git -C $cloneRoot rev-parse --short "$Base^{commit}").Trim()
$headId = (& git -C $cloneRoot rev-parse --short "$Branch^{commit}").Trim()
Write-Host "Wrote $count patches and person5-liveness.diff for $Base ($baseId)..$Branch ($headId)"
