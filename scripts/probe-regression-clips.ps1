# Finds the outcome of new regression clips before they get their __expect- name (F16; Windows).
#
#   scripts\probe-regression-clips.ps1 -Clips <folder with *.mjpeg> [-Runs 3]
#
# Plays every clip through RecordedVideoE2ETest itself (the real device path, Part 2's ONNX analyzer and the Part 3
# engine; one attempt, RESIDENT) -Runs times. Each clip is copied to a temporary folder as
# <name>__expect-FAILED-PROBE.mjpeg, so the test's own assertion reports the result:
#   expected:<FAILED> but was:<PASSED>  -> the clip PASSED (an F3/F2 finding to report; never commit it as a test)
#   expected:<PROBE> but was:<CODE>     -> the clip FAILED with CODE
# A clip is committable only if it FAILED in every run; with the same code each time it is named
# <name>__expect-FAILED-<CODE>.mjpeg, otherwise <name>__expect-FAILED.mjpeg. Nothing is copied into the repository:
# that is done by hand after the privacy check (models\tools\clip_privacy_check.py and a look at every frame).
#
# Runs Maven offline in the folder above scripts\ (registration-client\registration once this repo is copied over it),
# or in $env:REGISTRATION_DIR, after one "mvn install" of the patched build.
param(
    [Parameter(Mandatory = $true)][string]$Clips,
    [int]$Runs = 3
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$registration = if ($env:REGISTRATION_DIR) { $env:REGISTRATION_DIR } else { $root }
$probe = Join-Path ([IO.Path]::GetTempPath()) ("liveness-probe-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Path $probe | Out-Null
$names = @()
foreach ($f in Get-ChildItem -Path $Clips -Filter *.mjpeg) {
    $base = ($f.BaseName -split "__expect-")[0]
    Copy-Item $f.FullName (Join-Path $probe ($base + "__expect-FAILED-PROBE.mjpeg"))
    $names += $base
}
if ($names.Count -eq 0) { throw "no .mjpeg clips in $Clips" }
$report = Join-Path $registration "registration-services\target\surefire-reports\TEST-io.mosip.registration.liveness.device.mock.RecordedVideoE2ETest.xml"
$results = @{}
foreach ($n in $names) { $results[$n] = @() }
try {
    for ($run = 1; $run -le $Runs; $run++) {
        Write-Host "run $run of $Runs ($($names.Count) clips)"
        if (Test-Path $report) { Remove-Item $report }
        Push-Location $registration
        try {
            & mvn -o -q -B -pl registration-services test "-Dtest=RecordedVideoE2ETest" "-Dgpg.skip" `
                "-Dmaven.test.failure.ignore=true" "-Dliveness.videos.dir=$probe" | Out-Null
        } finally {
            Pop-Location
        }
        if (-not (Test-Path $report)) { throw "no surefire report after run $run (did the build fail?)" }
        $xml = [xml](Get-Content -Raw $report)
        foreach ($tc in $xml.testsuite.testcase) {
            if ($tc.name -notmatch '\[(.+?)__expect-FAILED-PROBE\.mjpeg\]') { continue }
            $clip = $Matches[1]
            $msg = if ($tc.failure) { $tc.failure.message } elseif ($tc.error) { "ERROR " + $tc.error.message } else { "" }
            $msg = $msg -replace '[\[\]]', ''  # JUnit marks the differing part: expected:<P[ROBE]> but was:<P[OOR_QUALITY]>
            if ($msg -match 'expected:<FAILED> but was:<(\w+)>') { $outcome = $Matches[1] }
            elseif ($msg -match 'expected:<PROBE> but was:<(\w+)>') { $outcome = "FAILED-" + $Matches[1] }
            else { $outcome = "UNKNOWN(" + $msg + ")" }
            $results[$clip] += $outcome
        }
    }
} finally {
    Remove-Item -Recurse -Force $probe
}
Write-Host ""
foreach ($n in ($names | Sort-Object)) {
    $r = $results[$n]
    $distinct = @($r | Select-Object -Unique)
    if (@($r | Where-Object { $_ -notlike "FAILED-*" }).Count -gt 0) {
        $verdict = "NOT COMMITTABLE: " + (($r | Where-Object { $_ -notlike "FAILED-*" }) -join ", ") + " (report it)"
    } elseif ($distinct.Count -eq 1 -and $r.Count -eq $Runs) {
        $verdict = "commit as " + $n + "__expect-" + $distinct[0] + ".mjpeg"
    } else {
        $verdict = "commit as " + $n + "__expect-FAILED.mjpeg (the code varies)"
    }
    Write-Host ("{0,-28} {1,-60} {2}" -f $n, ($r -join " | "), $verdict)
}
