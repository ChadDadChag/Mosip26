# F9 re-evaluation of the multi-subject data in one command (Windows; development only). Run it from any folder after
# dot-sourcing the toolchain (liveness-env.ps1: JDK and Maven):
#
#   scripts\f9-reeval.ps1 -Subjects s01,s02,s03 [-SubjectMap "s02=p05,s03=p11"] [-Out eval-data\reports\f9-...]
#                         [-Labelled "test images\labels.csv"] [-LabelsConfirmed] [-NoLabelled]
#                         [-Python <python>] [-PythonPath <site-packages>]
#
# Steps, in order; the run stops at the first one that fails:
#   a. inventory: bona fide takes per subject and condition, and the consent marker of every take (R0);
#   b. PadEvaluation over all subjects with the current defaults (R1);
#   e. BindingEvaluation --identity with the labelled photos and the subject map (R4);
#   c, d, f. the guard replays at 0.85 and 0.80 (R2), the moire cap per subject (R3), and summary.md
#      (models/tools/f9_reeval.py analyze).
# -Out must be under eval-data (gitignored) and empty; the default is eval-data\reports\f9-<yyyyMMdd-HHmmss>.
# Reports hold aggregates and codes only. -Python is an interpreter with models/tools/requirements.txt (default:
# LIVENESS_PYTHON, else python); -PythonPath adds a site-packages folder (default: LIVENESS_PYTHONPATH), for a venv
# whose own launcher is blocked.
#
# RULES BEGIN
# Pre-registered decision rules, fixed on 2026-10-28 before the F9 teammate data (models/tools/f9_reeval.py).
# R0 Consent. Every bona fide take of a listed subject has "consent": true in its meta.json, or the subject is
#    refused and the run stops. Subjects are codes (sNN), never names. A bona fide subject in the data that is not
#    listed also stops the run: PadEvaluation reads every subject.
# R1 PAD thresholds. PadEvaluation with the current defaults: APCER per species, BPCER pooled and per subject at
#    passive 0.85 and reject 0.30, and its recommended thresholds. Reported only: this run adopts no threshold.
# R2 F18 volatility cap. Share = the listed subjects' recorded real-face takes whose first passive window passes
#    without the guards but not with them (latch, volatilityMinScore 0.85), pooled. Above 25%: evaluate 0.80, by
#    replaying every recorded attack take and every attack attempt of the live logs (all SessionDemo cases except
#    the real-face a, b, c, h, i, j, k, l and the mixed m) at 0.85 and at 0.80. Adopt 0.80 only if no attack
#    verdict changes; otherwise keep 0.85 and record the cost (the share). If there is no attack attempt to
#    replay, R2 is not decidable and 0.85 stays. At 25% or below: keep 0.85. Bona fide PAD rejections (ATTACK)
#    are reported per subject.
# R3 F2 moire cap on new faces. Per subject, the share of scored bona fide frames (one face, model outputs) that the
#    moire cap capped. Above 10%: the subject is flagged. A flag is report only: the subject's code goes into the
#    limitations in docs/part2-iso30107-evaluation.md, and the moire cap does not change this week.
# R4 F15 identity threshold. BindingEvaluation's rule (at least 3 people and 50 onboarding-like pairs), printed
#    verbatim, decidable or not. Labels count as confirmed only with -LabelsConfirmed. At about 11 onboarding-like
#    pairs per person, s01 and 3 teammates give about 44 pairs, under 50: "not decidable, 0.40 stays" is the
#    expected outcome, and the 50-pair bar is not lowered after the fact.
# R5 Size. A result that changes production (R2: adopt 0.80) needs at least 3 subjects. With fewer, it is
#    provisional and nothing changes.
# R3 action and readings 1-7 confirmed by the planner on 2026-10-28, before the F9 teammate data.
# R2's sentence on no attack attempt to replay: a clarification made on 2026-10-28, before the F9 teammate data.
# RULES END
param(
    [Parameter(Mandatory = $true)][string[]]$Subjects,
    [string]$SubjectMap = "",
    [string]$Out = "",
    [string]$Data = "eval-data",
    [string]$Labelled = "test images\labels.csv",
    [switch]$LabelsConfirmed,
    [switch]$NoLabelled,
    [string]$Python = $(if ($env:LIVENESS_PYTHON) { $env:LIVENESS_PYTHON } else { "python" }),
    [string]$PythonPath = $env:LIVENESS_PYTHONPATH
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$subjectList = ($Subjects | ForEach-Object { $_ -split "," } | ForEach-Object { $_.Trim() } | Where-Object { $_ }) -join ","
# A command that does not run (java not on PATH) leaves $LASTEXITCODE as it was, so a step would look passed: the
# tools are checked first, and each native call starts from -1.
foreach ($exe in "mvn", "java", $Python) {
    if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) {
        throw "REFUSED: '$exe' not found: dot-source liveness-env.ps1 first (JDK, Maven), or pass -Python"
    }
}
if (-not $Out) { $Out = Join-Path $Data ("reports\f9-" + (Get-Date -Format "yyyyMMdd-HHmmss")) }
$outFull = [IO.Path]::GetFullPath((Join-Path $repo $Out))
$evalFull = [IO.Path]::GetFullPath((Join-Path $repo "eval-data")) + [IO.Path]::DirectorySeparatorChar
if (-not $outFull.StartsWith($evalFull, [StringComparison]::OrdinalIgnoreCase)) {
    throw "REFUSED: -Out must be under eval-data (gitignored): $outFull"
}
# Never over an earlier run: its pad and binding reports would stand in for a step that did not write new ones.
if ((Test-Path -LiteralPath $outFull) -and (Get-ChildItem -LiteralPath $outFull -Force | Select-Object -First 1)) {
    throw "REFUSED: -Out is not empty: $outFull"
}
New-Item -ItemType Directory -Force $outFull | Out-Null
if (-not $NoLabelled -and -not (Test-Path -LiteralPath $Labelled)) {
    throw "REFUSED: no labelled photos at '$Labelled' for the F15 rule (R4); pass -Labelled <labels.csv> or -NoLabelled"
}
if ($PythonPath) { $env:PYTHONPATH = $PythonPath }
$started = Get-Date
$times = [ordered]@{}

function Step([string]$name, [scriptblock]$body) {
    "== $name"
    $t0 = Get-Date
    & $body
    $times[$name] = [int]((Get-Date) - $t0).TotalSeconds
}

function Fail([string]$name, [string]$log) {
    if ($log -and (Test-Path $log)) { Get-Content $log -Tail 15 }
    throw "STOPPED at '$name' (see $log)"
}

# The liveness-eval classes and classpath, as scripts\dataset-session.ps1 builds them.
$eval = Join-Path $repo "liveness-eval"
$cpFile = Join-Path $eval "target\classpath.txt"
Step "build liveness-eval" {
    $global:LASTEXITCODE = -1
    & mvn -q -B -f (Join-Path $eval "pom.xml") compile dependency:build-classpath "-Dmdep.outputFile=$cpFile"
    if ($LASTEXITCODE -ne 0) { throw "STOPPED at 'build liveness-eval' (mvn exit $LASTEXITCODE)" }
}
$cp = (Join-Path $eval "target\classes") + ";" + (Get-Content -Raw $cpFile).Trim()
$tool = Join-Path $repo "models\tools\f9_reeval.py"

Step "a. inventory (R0)" {
    $global:LASTEXITCODE = -1
    & $Python $tool inventory --data $Data --subjects $subjectList --out $outFull
    if ($LASTEXITCODE -ne 0) { throw "STOPPED at 'a. inventory': see the REFUSED line above" }
}

Step "b. PadEvaluation (R1)" {
    $log = Join-Path $outFull "pad.log"
    $ErrorActionPreference = "Continue" # Java logs warnings on stderr
    $global:LASTEXITCODE = -1
    & java -cp $cp io.mosip.registration.liveness.eval.PadEvaluation --data $Data --out (Join-Path $outFull "pad") *> $log
    if ($LASTEXITCODE -ne 0) { Fail "b. PadEvaluation" $log }
}

Step "e. BindingEvaluation --identity (R4)" {
    $log = Join-Path $outFull "binding.log"
    $bargs = @("--data", $Data, "--out", (Join-Path $outFull "binding"), "--identity")
    if ($SubjectMap) { $bargs += @("--subject-map", $SubjectMap) }
    if (-not $NoLabelled) {
        $bargs += @("--labelled", $Labelled)
        if ($LabelsConfirmed) { $bargs += "--labels-confirmed" }
    }
    $ErrorActionPreference = "Continue"
    $global:LASTEXITCODE = -1
    & java -cp $cp io.mosip.registration.liveness.eval.BindingEvaluation @bargs *> $log
    if ($LASTEXITCODE -ne 0) { Fail "e. BindingEvaluation" $log }
}

Step "c, d, f. guard replays, moire cap, summary (R2, R3)" {
    $global:LASTEXITCODE = -1
    & $Python $tool analyze --data $Data --subjects $subjectList --pad (Join-Path $outFull "pad") `
        --binding (Join-Path $outFull "binding") --sessions (Join-Path $Data "sessions") --out $outFull
    if ($LASTEXITCODE -ne 0) { throw "STOPPED at 'c, d, f': see the REFUSED line above" }
}

""
"Steps (s): " + (($times.GetEnumerator() | ForEach-Object { "$($_.Key) $($_.Value)" }) -join "; ")
"Total: $([int]((Get-Date) - $started).TotalSeconds) s. Summary: $(Join-Path $outFull 'summary.md')"
