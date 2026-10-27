# Starts the guided PAD dataset session (LiveAnalyzerDemo --mode dataset) from this repository's liveness-eval build
# (Windows; development only).
#
#   scripts\dataset-session.ps1 -Consent [-Subject s01] [-Only print_flat,print_curved] [-Root eval-data] [-Camera 0]
#                               [-Capture opencv|sarxos]
#
# Pass -Consent only after the participant has agreed to the consent text (docs/part2-dataset-protocol.md section 2);
# without it the session refuses before the camera opens. -Only selects a block: print_flat,print_curved (prints),
# f16 (regression clips, no person in view), f2_banding (lossless 30 fps), bona_fide (a new subject),
# screen_replay_monitor (a second screen). Frames stay under eval-data\ (gitignored).
param(
    [switch]$Consent,
    [string]$Subject = "s01",
    [string[]]$Only = @(),
    [string]$Root = "eval-data",
    [int]$Camera = 0,
    [ValidateSet("opencv", "sarxos")][string]$Capture = "opencv"
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$eval = Join-Path $repo "liveness-eval"
$classes = Join-Path $eval "target\classes"
$cpFile = Join-Path $eval "target\classpath.txt"
$pom = Join-Path $eval "pom.xml"
if (-not (Test-Path $classes) -or -not (Test-Path $cpFile) -or (Get-Item $pom).LastWriteTime -gt (Get-Item $cpFile).LastWriteTime) {
    & mvn -q -B -f (Join-Path $eval "pom.xml") compile dependency:build-classpath "-Dmdep.outputFile=$cpFile"
    if ($LASTEXITCODE -ne 0) { throw "liveness-eval build failed ($LASTEXITCODE)" }
}
$cp = $classes + ";" + (Get-Content -Raw $cpFile).Trim()
$demoArgs = @("--mode", "dataset", "--subject", $Subject, "--root", $Root, "--camera", "$Camera", "--capture", $Capture)
if ($Consent) { $demoArgs += "--consent" }
if ($Only) { $demoArgs += @("--only", ($Only -join ",")) }
Push-Location $repo
try {
    # Java logs harmless warnings on stderr; under "Stop", PowerShell 5.1 would treat them as fatal.
    $ErrorActionPreference = "Continue"
    & java -cp $cp io.mosip.registration.liveness.eval.LiveAnalyzerDemo @demoArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
