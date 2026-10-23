# MockMDS patch: real face frames on `/stream`

`mockmds-face-stream.patch` changes MOSIP's MockMDS (`mosip-mock-services/MockMDS`) in two ways:

1. **It fixes the stream request.** Without this fix, the Registration Client gets no face stream from MockMDS at all.
2. **It can stream real frames.** The face `/stream` can send images, a recorded video or the webcam instead of one fixed picture.

With both, the full device path (MockMDS → SBI `/stream` → `SbiStreamFrameSource` → liveness engine) can be run on a laptop without a biometric device.

The patch was made against [mosip/mosip-mock-services](https://github.com/mosip/mosip-mock-services) commit `3d2174d` (the 2026-09-23 `master`).

## 1. The stream request fix

The Registration Client (1.2.0.x, `StreamSbiRequestDTO`) sends this to `/stream`:

    {"serialNo":"2345678901","deviceSubId":"0","timeout":"..."}

MockMDS reads `deviceId` from that request and finds the device by its `deviceId`. The client never sends `deviceId`, so the lookup gets `null`. MockMDS then answers with an error JSON (`errorCode 605`) instead of a stream. This also breaks the client's existing face preview.

Renaming the field with `@JsonAlias("serialNo")`, as suggested earlier in the Part 1 README, is **not enough**. For the face device the two values differ:

| | Value |
|---|---|
| `serialNo` (DigitalId) | `2345678901` |
| `deviceId` | `3` |

The patch makes three changes:

- adds `serialNo` to `StreamingRequestDetail`
- uses `serialNo` when `deviceId` is missing
- matches a device by its `deviceId` **or** its DigitalId serial number (`isDevice` in `SBIServiceResponse`)

Requests that send `deviceId` behave as before.

Checked live:

- **Unpatched MockMDS:** returns error `605` to the client's request.
- **Patched MockMDS:** streamed 45 of 45 frames of a recording, in order and byte-identical. They were read with Part 1's `MjpegFrameReader` and decoded with `JpegToRgb888Converter`.

## 2. Face stream modes

These are configured with system properties (checked first) or with `application.properties`:

| Key | Values | Default |
|---|---|---|
| `mosip.mock.sbi.face.stream.mode` | `static`, `images`, `video`, `webcam` | `static`: the original fixed image |
| `mosip.mock.sbi.face.stream.source` | a folder or file for `images` and `video` | none |
| `mosip.mock.sbi.face.stream.fps` | frames per second for `images` and `video` | `15` |

How each mode behaves:

- **`images`**: shows JPEG/PNG/BMP files in name order, one per frame period, looping. A single image simulates a printed-photo attack.
- **`video`**: replays a recording in a loop. It accepts:
  - the files written by Part 1's `MjpegRecorder`, which use exactly the `/stream` wire format (`--BoundaryString` and `Content-Length`)
  - files saved by `SbiStreamProbe --save`
  - plain concatenated JPEGs, for example `ffmpeg -i in.mp4 -f mjpeg out.mjpeg`
- **`webcam`**: the machine's default camera, live, via `webcam-capture` 0.3.12 (the same library as Part 1's `SarxosWebcamFrameGrabber`).

Frames advance by elapsed time, so playback speed does not depend on how fast the stream loop asks for frames.

A bad setting never stops MockMDS. If the mode is unknown, the source is missing, or the source has no frames, MockMDS logs an error and falls back to `static`.

Only the face full-face stream (`deviceSubId` 0) changes. Fingerprint, iris and `/capture` behave as before.

## Apply and build

Requires JDK 21 (MockMDS's own requirement) and Maven.

    git clone https://github.com/mosip/mosip-mock-services.git
    cd mosip-mock-services
    git checkout 3d2174d          # or a later master, if the patch still applies
    git apply --check /path/to/mock-mds-patch/mockmds-face-stream.patch
    git apply /path/to/mock-mds-patch/mockmds-face-stream.patch
    cd MockMDS
    mvn package -Dgpg.skip -Dmaven.test.failure.ignore=true

The patch keeps MockMDS's CRLF line endings. If `git apply` reports whitespace errors on Windows, run it with `--ignore-whitespace`.

**About `-Dmaven.test.failure.ignore`:** on macOS on Apple silicon, three `BioUtilHelperTest` tests fail on unpatched MockMDS too (OpenCV/JP2000 native library `NoClassDefFoundError`). The patch does not cause them. The patch's own tests (`FaceStreamProviderTest`, 7 tests) pass:

    mvn test -Dtest=FaceStreamProviderTest -Dgpg.skip -Djacoco.skip=true

## Run

From `MockMDS/`, after `mvn package`. Replace `<recording.mjpeg>` with your file:

    java -Dmosip.mock.sbi.face.stream.mode=video \
         -Dmosip.mock.sbi.face.stream.source=<recording.mjpeg> \
         -Dmosip.mock.sbi.face.stream.fps=15 \
         -cp "target/mock-mds-1.3.1.jar:target/lib/*" \
         io.mosip.mock.sbi.test.TestMockSBI \
         "mosip.mock.sbi.device.purpose=Registration" "mosip.mock.sbi.biometric.type=Biometric Device"

Notes:

- **Windows:** use `;` instead of `:` in `-cp` and `^` instead of `\` for line breaks.
- **Webcam:** use `-Dmosip.mock.sbi.face.stream.mode=webcam` and drop the source setting.
- **Port:** MockMDS listens on the first free port from 4501. The Registration Client finds it with its usual device discovery.

## Check the stream without the Registration Client

Part 1's `SbiStreamProbe` sends exactly the request the Registration Client sends and reads the stream through the liveness frame reader. Run it from `registration-client/registration` after `mvn install`:

    mvn -q exec:java -pl registration-services \
      -Dexec.mainClass=io.mosip.registration.liveness.device.demo.SbiStreamProbe \
      "-Dexec.args=4501 2345678901 60 --save probe.mjpeg"

Expected output: `<status line> | multipart stream | frames=60 | <w>x<h> | <fps> fps`. The exit code is 0 when frames arrived.

On unpatched MockMDS it reports `NOT a multipart stream` and the error.

The saved `probe.mjpeg` can be replayed by:

- `MockFrameSource.ofVideoFile`
- the harness (`LivenessHarness video probe.mjpeg`)
- this patch's `video` mode
- the recorded-video end-to-end tests

## Why a patch, not a fork

MockMDS belongs to MOSIP, and the team repo is an overlay for the Registration Client. Keeping the change as one reviewable patch makes it easy to see and to offer upstream as a PR. The `serialNo` fix stands on its own, so it could go upstream separately.
