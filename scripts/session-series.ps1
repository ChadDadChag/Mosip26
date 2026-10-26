# Runs scripted real-engine liveness sessions on the webcam (LiveAnalyzerDemo --mode series; Windows; development only).
#
#   scripts\session-series.ps1 -Plan m,n,o [-Camera 0]
#
# Each case is a real Part 3 session with the Part 2 analyzer, preceded by a prompt (SPACE = start, S = skip);
# failed attempts are retried automatically until the session ends. Cases: see SessionDemo (for example m, n, o =
# multiple faces). No frame is stored: only the event log, liveness-eval\target\sessions-<time>.jsonl (events with
# their time, the result, codes and numbers), which stays on this laptop.
param(
    [Parameter(Mandatory = $true)][string[]]$Plan,
    [int]$Camera = 0
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$eval = Join-Path $repo "liveness-eval"
$classes = Join-Path $eval "target\classes"
$cpFile = Join-Path $eval "target\classpath.txt"
if (-not (Test-Path $classes) -or -not (Test-Path $cpFile)) {
    & mvn -q -B -f (Join-Path $eval "pom.xml") compile dependency:build-classpath "-Dmdep.outputFile=$cpFile"
    if ($LASTEXITCODE -ne 0) { throw "liveness-eval build failed ($LASTEXITCODE)" }
}
$cp = $classes + ";" + (Get-Content -Raw $cpFile).Trim()
Push-Location $repo
try {
    # Java logs harmless warnings on stderr; under "Stop", PowerShell 5.1 would treat them as fatal.
    $ErrorActionPreference = "Continue"
    & java -cp $cp io.mosip.registration.liveness.eval.LiveAnalyzerDemo --mode series --plan ($Plan -join ",") --camera "$Camera"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
