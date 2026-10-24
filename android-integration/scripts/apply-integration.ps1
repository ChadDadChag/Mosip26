<#
.SYNOPSIS
Creates an Android Registration Client checkout with the face liveness integration applied.

.DESCRIPTION
1. Clones the upstream tag (v1.1.1, depth 1) into a new folder.
2. Applies android-integration\patches\*.patch with git am (one commit per patch), or with
   -Consolidated the single person5-liveness.diff with git apply (one commit).
3. Runs sync-engine.ps1 to build and copy the engine jars, the signed model bundle and the
   camera source from this repository.
4. With -BuildApk: flutter pub get, the Pigeon code generation (pigeon.sh, through Git Bash),
   then flutter build apk --debug for arm64. Flutter must be on PATH (for example after
   dot-sourcing %USERPROFILE%\tools\android-env.ps1).

git must know your name and e-mail (git am and git commit record them as the committer).

.PARAMETER Target
A folder that does not exist yet.

.EXAMPLE
.\android-integration\scripts\apply-integration.ps1 -Target C:\dev\arc-liveness -BuildApk
#>
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [string]$Repository = 'https://github.com/mosip/android-registration-client.git',
    [string]$Tag = 'v1.1.1',
    [switch]$Consolidated,
    [switch]$SkipEngineBuild,
    [switch]$BuildApk
)

$ErrorActionPreference = 'Stop'

function Invoke-Checked([string]$what, [scriptblock]$command) {
    # Native tools write progress to stderr; only the exit code decides.
    $ErrorActionPreference = 'Continue'
    & $command
    if ($LASTEXITCODE -ne 0) { throw "$what failed ($LASTEXITCODE)" }
}

$integration = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (Test-Path $Target) { throw "$Target already exists; give a new folder" }

Invoke-Checked 'git clone' { git -c core.longpaths=true clone --depth 1 --branch $Tag $Repository $Target }
$root = (Resolve-Path $Target).Path
Invoke-Checked 'git config' { git -C $root config core.longpaths true }

if ($Consolidated) {
    $diff = Join-Path $integration 'person5-liveness.diff'
    Invoke-Checked 'git apply' { git -C $root apply --index --whitespace=nowarn $diff }
    Invoke-Checked 'git commit' { git -C $root commit -q -m "Face liveness integration (person5-liveness.diff)" }
} else {
    $patches = Get-ChildItem -LiteralPath (Join-Path $integration 'patches') -Filter '*.patch' | Sort-Object Name
    if ($patches.Count -eq 0) { throw "no patches in $integration\patches" }
    Invoke-Checked 'git am' { git -C $root am --whitespace=nowarn @($patches | ForEach-Object { $_.FullName }) }
}
Write-Host "Applied the integration to $root"

$syncArgs = @{ Clone = $root }
if ($SkipEngineBuild) { $syncArgs.SkipBuild = $true }
& (Join-Path $PSScriptRoot 'sync-engine.ps1') @syncArgs

if ($BuildApk) {
    $bash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
    if (-not (Test-Path $bash)) { throw "Git Bash not found at $bash (needed for pigeon.sh)" }
    Push-Location $root
    try {
        Invoke-Checked 'pigeon.sh' { & $bash -c 'sh pigeon.sh' }
        # pigeon.sh also rewrites the iOS bridge files; the integration does not touch iOS.
        Invoke-Checked 'git checkout ios' { git checkout -- ios/Runner/pigeon.h ios/Runner/pigeon.m }
        Invoke-Checked 'flutter build' { flutter build apk --debug --target-platform android-arm64 }
    } finally {
        Pop-Location
    }
}
