#!/usr/bin/env bash
# Records one labelled liveness test clip from the webcam with Part 1's MjpegRecorder.
#
#   scripts/record-liveness-clip.sh <label> <person-id> <take> [seconds=8] [fps=15]
#
# label: live | print_attack | screen_attack | replay_attack | no_face
# The clip is written to recordings/ (git-ignored: real faces are biometric data and must never be committed)
# with the expected outcome in its name, e.g. recordings/p03-print_attack-1__expect-FAILED-PAD_FAILURE.mjpeg.
# Run the end-to-end tests on them with:  -Dliveness.videos.dir=$(pwd)/recordings
#
# Runs Maven in the folder above scripts/ (registration-client/registration once this repo is copied over it, see
# README "How to install"), or in $REGISTRATION_DIR. Needs JDK 11, Maven, and one "mvn install" of the patched build.
set -euo pipefail

label="${1:?label: live|print_attack|screen_attack|replay_attack|no_face}"
person="${2:?person id, e.g. p03 (never a real name)}"
take="${3:?take number}"
seconds="${4:-8}"
fps="${5:-15}"

case "$label" in
  live)          expect="PASSED" ;;
  print_attack|screen_attack|replay_attack) expect="FAILED-PAD_FAILURE" ;;
  no_face)       expect="FAILED-FACE_NOT_DETECTED" ;;
  *) echo "unknown label: $label" >&2; exit 64 ;;
esac

root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$root/recordings"
out="$root/recordings/${person}-${label}-${take}__expect-${expect}.mjpeg"
frames=$(awk "BEGIN { printf \"%d\", $seconds * $fps }")

echo "Recording $label for $person (take $take): $seconds s at $fps fps -> $out"
case "$label" in
  live)          echo "Look at the camera naturally. Blink normally. Keep still." ;;
  print_attack)  echo "Hold a PRINTED photo of the person in front of the camera, filling the frame." ;;
  screen_attack) echo "Hold a PHONE/TABLET showing a photo of the person in front of the camera." ;;
  replay_attack) echo "Play a VIDEO of the person (blinking, turning) on a phone/monitor in front of the camera." ;;
  no_face)       echo "Point the camera at an empty background." ;;
esac
for i in 3 2 1; do echo "  starting in $i..."; sleep 1; done

cd "${REGISTRATION_DIR:-$root}"
mvn -q exec:java -pl registration-services \
  -Dexec.mainClass=io.mosip.registration.liveness.device.mock.MjpegRecorder \
  "-Dexec.args=webcam $frames $fps $out"
echo "Saved $out"
