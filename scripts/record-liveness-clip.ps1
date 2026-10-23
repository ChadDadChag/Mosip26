# Records one labelled liveness test clip from the webcam with Part 1's MjpegRecorder (Windows).
#
#   scripts\record-liveness-clip.ps1 -Label print_attack -Person p03 -Take 1 [-Seconds 8] [-Fps 15]
#
# Label: live | print_attack | screen_attack | replay_attack | no_face
# The clip goes to recordings\ (git-ignored: real faces are biometric data and must never be committed), named with
# the expected outcome, e.g. recordings\p03-print_attack-1__expect-FAILED-PAD_FAILURE.mjpeg.
# Run the end-to-end tests on them with:  "-Dliveness.videos.dir=$PWD\recordings"
# Runs Maven in the folder above scripts\ (registration-client\registration once this repo is copied over it), or in
# $env:REGISTRATION_DIR. Needs JDK 11, Maven, and one "mvn install" of the patched build.
param(
    [Parameter(Mandatory = $true)][ValidateSet("live", "print_attack", "screen_attack", "replay_attack", "no_face")][string]$Label,
    [Parameter(Mandatory = $true)][string]$Person,
    [Parameter(Mandatory = $true)][int]$Take,
    [int]$Seconds = 8,
    [double]$Fps = 15
)
$ErrorActionPreference = "Stop"
$expect = switch ($Label) {
    "live" { "PASSED" }
    "no_face" { "FAILED-FACE_NOT_DETECTED" }
    default { "FAILED-PAD_FAILURE" }
}
$root = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force -Path (Join-Path $root "recordings") | Out-Null
$out = Join-Path $root ("recordings\{0}-{1}-{2}__expect-{3}.mjpeg" -f $Person, $Label, $Take, $expect)
$frames = [int]($Seconds * $Fps)
Write-Host "Recording $Label for $Person (take $Take): $Seconds s at $Fps fps -> $out"
switch ($Label) {
    "live" { Write-Host "Look at the camera naturally. Blink normally. Keep still." }
    "print_attack" { Write-Host "Hold a PRINTED photo of the person in front of the camera, filling the frame." }
    "screen_attack" { Write-Host "Hold a PHONE/TABLET showing a photo of the person in front of the camera." }
    "replay_attack" { Write-Host "Play a VIDEO of the person on a phone/monitor in front of the camera." }
    "no_face" { Write-Host "Point the camera at an empty background." }
}
3..1 | ForEach-Object { Write-Host "  starting in $_..."; Start-Sleep -Seconds 1 }
$registration = if ($env:REGISTRATION_DIR) { $env:REGISTRATION_DIR } else { $root }
Push-Location $registration
try {
    & mvn -q exec:java -pl registration-services `
        "-Dexec.mainClass=io.mosip.registration.liveness.device.mock.MjpegRecorder" `
        "-Dexec.args=webcam $frames $Fps $out"
    if ($LASTEXITCODE -ne 0) { throw "MjpegRecorder failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}
Write-Host "Saved $out"
