# Runs scripted real-engine liveness sessions on the webcam (LiveAnalyzerDemo --mode series; Windows; development only).
#
#   scripts\session-series.ps1 -Plan m,n,o [-Camera 0] [-LogDir eval-data\sessions] [-Capture opencv|sarxos]
#
# Each case is a real Part 3 session with the Part 2 analyzer, preceded by a prompt (SPACE = start, S = skip);
# failed attempts are retried automatically until the session ends. Cases: see SessionDemo (for example m, n, o =
# multiple faces; s3, t3, u, v3 = the single-print retest with cut-out 3). No frame is stored: only the event log,
# <LogDir>\sessions-<time>.jsonl (default liveness-eval\target; events with their time, the result, codes and each
# analysed frame's numbers), which stays on this laptop. Attack-session evidence goes to eval-data\sessions (gitignored).
param(
    [Parameter(Mandatory = $true)][string[]]$Plan,
    [int]$Camera = 0,
    [string]$LogDir = "liveness-eval\target",
    [ValidateSet("opencv", "sarxos")][string]$Capture = "opencv"
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$eval = Join-Path $repo "liveness-eval"
$classes = Join-Path $eval "target\classes"
$cpFile = Join-Path $eval "target\classpath.txt"
$pom = Join-Path $eval "pom.xml"
if (-not (Test-Path $classes) -or -not (Test-Path $cpFile) -or (Get-Item $pom).LastWriteTime -gt (Get-Item $cpFile).LastWriteTime) {
    # -Dmaven.gitcommitid.skip: git-commit-id-plugin 3.0.1 cannot read a git worktree ("Could not get HEAD Ref"), and
    # liveness-eval does not use git.properties.
    & mvn -q -B -f (Join-Path $eval "pom.xml") compile dependency:build-classpath "-Dmdep.outputFile=$cpFile" "-Dmaven.gitcommitid.skip=true"
    if ($LASTEXITCODE -ne 0) { throw "liveness-eval build failed ($LASTEXITCODE)" }
}
$cp = $classes + ";" + (Get-Content -Raw $cpFile).Trim()
Push-Location $repo
try {
    # Java logs harmless warnings on stderr; under "Stop", PowerShell 5.1 would treat them as fatal.
    $ErrorActionPreference = "Continue"
    & java -cp $cp io.mosip.registration.liveness.eval.LiveAnalyzerDemo --mode series --plan ($Plan -join ",") --camera "$Camera" --log-dir "$LogDir" --capture $Capture
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
