# Face Liveness — Part 1: Device & Frame Pipeline

Part 1 gets face frames from a biometric device (or a stand-in) into one common
shape, so the liveness code (Parts 2/3) never depends on SBI, MDS or camera APIs.

    device / camera / file  ->  FrameSource  ->  Frame  ->  FrameSource.Listener.onFrame()

`Frame` (from `liveness-api`) carries pixels plus their format (RGB888, or GRAY8 for
mono/NIR cameras), width, height, timestamp, sequence number and device ID.
`FrameSource` has `start(listener)`, `stop()`, `isRunning()` and `info()`. `info()`
includes `DeviceCapabilities`: the formats, resolutions and frame rates the device supports.

The package also contains a Part 2 reference analyzer (`liveness/engine`). It is
not part of Part 1 and is documented separately in its own delivery notes.

## Part 1 checklist

| Item | Where |
|---|---|
| Study of the existing integration (MDS discovery, `/capture`, `/stream`) | `docs/part1-frame-streaming-architecture.md` §1 |
| `Frame`: pixels, timestamp, format, device ID, sequence | `liveness-api/.../Frame.java` |
| `FrameSource` common device abstraction | `liveness-api/.../FrameSource.java`, interface table in docs §2 |
| SBI adapter: MJPEG `/stream`, decode, continuous or every Nth frame | `SbiStreamFrameSource`, `SbiStreamFrameSourceFactory` |
| Mock L0/L1 device: webcam relay, recorded video, static images | `MockFrameSource` (+ `asCertificationLevel`), `SarxosWebcamFrameGrabber`, `MjpegRecorder` |
| Device errors: unavailable, connection failure, disconnect/reconnect, corrupt/incomplete frames | `SbiStreamFrameSource`, `MjpegFrameReader`; table in docs §6 |
| Capability discovery (formats, resolutions, frame rates) | `DeviceCapabilities`, `CapabilityProbe`, per-source table in docs §7 |
| Android frame source (CameraX; SBI-intent decision explained) | `android-liveness-frame-source/`, docs §5 |
| Deliverables: interface, architecture diagram, mock device | docs §2–§5, `MockFrameSource` |
| MockMDS streams real frames, and its `serialNo` bug is fixed | `mock-mds-patch/` |
| Liveness debug window and headless runner (preview, face box, score, prompts, Retry) | `LivenessHarness`, `PreviewFrameSource` |
| Interoperability and end-to-end tests (device path to engine, unplug and replug, recorded clips) | `DevicePathInteropTest`, `UnplugReplugIntegrationTest`, `RecordedVideoE2ETest`, `SbiStreamProbe` |
| Test data protocol and QA checklist | `docs/part1-test-data-protocol.md`, `docs/part1-qa-checklist.md`, `scripts/record-liveness-clip.*` |

## What is in here

| Path | What it is |
|---|---|
| `registration/liveness-api/` | The shared contracts (`Frame`, `FrameSource`, `DeviceCapabilities`, `ErrorCode`, ...). New Maven module. |
| `registration/registration-services/.../liveness/device/sbi/` | `SbiStreamFrameSource` + `SbiStreamFrameSourceFactory`: reads the existing SBI `/stream` MJPEG through `BioService.getStream()`. |
| `.../liveness/device/util/` | `MjpegFrameReader` (MJPEG parsing), `JpegToRgb888Converter` (JPEG to RGB888), `CapabilityProbe`, `PreviewFrameSource` (sends every frame to a preview as well as to the engine). |
| `.../liveness/device/mock/` | `MockFrameSource` (3 modes), `SarxosWebcamFrameGrabber`, `MjpegRecorder`. |
| `.../liveness/device/demo/` | `FramePipelineDemo` (frames only), `LivenessHarness` (the whole pipeline with the real engine), `SbiStreamProbe` (checks any MDS `/stream` the way the client calls it). |
| `android-liveness-frame-source/` | `CameraXFrameSource` for Android. Not part of the Maven build. |
| `mock-mds-patch/` | The patch for MOSIP's MockMDS: the `serialNo` fix plus real face frames (images, video, webcam). See its README. |
| `scripts/record-liveness-clip.sh`, `.ps1` | Record a labelled test clip from the webcam. |
| `docs/part1-frame-streaming-architecture.md` | Existing-integration analysis, interface, architecture and sequence diagrams, error and capability tables. |
| `docs/part1-test-data-protocol.md`, `docs/part1-qa-checklist.md` | What to film and how to keep it private; the problem-statement checklist for Part 1. |
| `registration/pom.xml`, `registration/registration-services/pom.xml` | Small edits: the liveness modules, the `liveness-api` and `liveness-core` dependencies (the harness and end-to-end tests run the real engine), and `webcam-capture` 0.3.12. |

No existing Registration Client class (Streamer, controllers, SBI provider, BioServiceImpl,
`/capture`) is modified.

## How to install

Requirements: **JDK 11** (the upstream build uses Lombok 1.18.16, which does not run on newer
JDKs), **Maven 3.6+**, **Git**, and internet access for the first build.

1. Get the Registration Client at the version the patch is built against:

       git clone --depth 1 --branch v1.2.0.2 https://github.com/mosip/registration-client.git

2. Copy the contents of this repository over `registration-client/registration/`.
   The two `pom.xml` files are the v1.2.0.2 originals plus only the liveness additions, and
   `.gitignore` keeps all of MOSIP's entries.

## Build and test

PowerShell, from `registration-client\registration` (adjust the JDK/Maven paths to yours):

    $env:JAVA_HOME = "C:\Users\penna\tools\jdk-11.0.32.1+1"
    $mvn = "C:\Users\penna\tools\apache-maven-3.9.9\bin\mvn.cmd"

    # build + Part 1 tests only (fast)
    & $mvn -pl liveness-api,liveness-core,registration-services -am test "-Dtest=**/liveness/**/*Test,FrameTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dgpg.skip"

    # build without tests
    & $mvn -pl liveness-api,liveness-core,registration-services -am install -DskipTests "-Dgpg.skip"

The first build downloads all MOSIP dependencies and takes a while; later builds are offline-fast.

## Run the demo (see the pipeline working)

`FramePipelineDemo` opens a source and shows a live preview with sequence number,
resolution, delivered FPS and any device errors. After the `install` above:

    cd registration-services
    & $mvn -q exec:java "-Dexec.mainClass=io.mosip.registration.liveness.device.demo.FramePipelineDemo" "-Dexec.args=webcam 15"
    & $mvn -q exec:java "-Dexec.mainClass=io.mosip.registration.liveness.device.demo.FramePipelineDemo" "-Dexec.args=images C:\path\to\photos 10"
    & $mvn -q exec:java "-Dexec.mainClass=io.mosip.registration.liveness.device.demo.FramePipelineDemo" "-Dexec.args=video C:\path\to\clip.mjpeg 15 2"

Arguments: `webcam [fps] [stride]`, `images <fileOrDir> [fps] [stride]`, `video <file.mjpeg> [fps] [stride]`.
Close the window to stop. To make a `.mjpeg` recording from your webcam or a folder of images:

    & $mvn -q exec:java "-Dexec.mainClass=io.mosip.registration.liveness.device.mock.MjpegRecorder" "-Dexec.args=webcam 150 15 C:\path\to\clip.mjpeg"
    & $mvn -q exec:java "-Dexec.mainClass=io.mosip.registration.liveness.device.mock.MjpegRecorder" "-Dexec.args=images C:\path\to\photos C:\path\to\clip.mjpeg"

## Run the liveness harness (the whole pipeline)

`LivenessHarness` connects a frame source, the Part 2 stand-in analyzer (`HeuristicFaceAnalyzer`) and the real
Part 3 engine. The window shows:
- the live preview, with the detected face box and the liveness score
- the current status, the challenge prompt and its progress, and positioning guidance, using the problem-statement texts
- the result, with Start, Retry and Cancel buttons

    & $mvn -q exec:java "-Dexec.mainClass=io.mosip.registration.liveness.device.demo.LivenessHarness" "-Dexec.args=webcam"
    & $mvn -q exec:java "-Dexec.mainClass=io.mosip.registration.liveness.device.demo.LivenessHarness" "-Dexec.args=video C:\path\to\clip.mjpeg 15 --workflow operator --diagnostic"

Arguments: `webcam [fps]`, `video <file.mjpeg> [fps]`, `images <fileOrDir> [fps]`, plus `--workflow resident|operator|supervisor`,
`--diagnostic` (prints the audit, `diag.*` per-attempt score statistics and the metrics) and `--headless` (no window).
Headless mode presses Retry automatically and exits with 0 (PASSED), 1 (FAILED) or 2 (cancelled or timed out).

The stand-in analyzer is not a real PAD model: it can pass a photo. Real PAD results need Part 2's analyzer.

The SBI path (`SbiStreamFrameSource`) needs a running MDS (a real device or the patched MockMDS) and is used
from inside the Registration Client (see "How to use it" below). To check an MDS stream on its own, use `SbiStreamProbe`
(see `mock-mds-patch/README.md`).

## How to use it

Real SBI device (uses the existing MDS discovery and `/stream`):

    SbiStreamFrameSourceFactory factory = new SbiStreamFrameSourceFactory(
            deviceSpecificationFactory, bioService,
            15_000L,  // frameTimeoutMs: no bytes for this long = stalled
            2,        // frameStride: process every 2nd frame (1 = every frame)
            3,        // maxReconnectAttempts after a mid-stream drop (0 = off)
            500L);    // reconnectBackoffMs
    FrameSource source = factory.create("FACE_FULLFACE");   // fails fast if the device is unavailable
    source.start(new FrameSource.Listener() {
        public void onFrame(Frame f) { /* RGB888 in f.rgb() */ }
        public void onError(ErrorCode code, String detail, boolean recoverable) { }
    });
    ...
    source.stop();

With `frameStride = 3` you receive sequence numbers 0, 3, 6, ...: the sequence is the
raw frame index, so skipped frames show up as gaps. `stop()` followed by `start()` (a
retry) is safe even while the previous connection is still unwinding.

Capability discovery:

    source.info().capabilities;                                     // MJPEG declared; resolution/fps fill in while streaming
    factory.probeCapabilities("FACE_FULLFACE", 20, 5_000L);         // open briefly and measure
    CapabilityProbe.probe(anyFrameSource, 20, 5_000L);              // same, for any source

Mock device, no hardware or MDS needed:

    MockFrameSource.ofVideoFile(Path.of("clip.mjpeg"), 15.0, true);   // replay a recorded MJPEG file
    new MockFrameSource(Path.of("images/"), 10.0);                    // cycle static images (attack tests)
    MockFrameSource.ofWebcam(new SarxosWebcamFrameGrabber(), 15.0);   // real webcam
    new MockFrameSource(Path.of("images/"), 10.0).asCertificationLevel("L1");   // pose as an L1 device

Making recordings for `ofVideoFile` (same framing as SBI `/stream`):

    MjpegRecorder.record(sbiOrWebcamSource, Path.of("clip.mjpeg"), 150, 20_000L);
    MjpegRecorder.fromImages(Path.of("frames/"), Path.of("clip.mjpeg"));

    java -cp <classpath> io.mosip.registration.liveness.device.mock.MjpegRecorder images frames/ clip.mjpeg
    java -cp <classpath> io.mosip.registration.liveness.device.mock.MjpegRecorder webcam 150 15 clip.mjpeg

## Trying it against the MockMDS service

Unpatched MockMDS does not stream to the Registration Client. The client's `StreamSbiRequestDTO`
sends `serialNo`, but MockMDS looks the device up by `deviceId`, finds nothing, and answers with
error 605. This also breaks the client's existing face preview.

An alias from `serialNo` to `deviceId` is not enough. The face device's serial number (`2345678901`)
is not its `deviceId` (`3`).

`mock-mds-patch/` fixes the lookup: MockMDS accepts either `deviceId` or the DigitalId serial number. The
patch also makes the face stream send images, a recorded video or the webcam. Apply it, then start MockMDS
with the run command in `mock-mds-patch/README.md` and run the Registration Client's device discovery as usual.

Checked live: unpatched MockMDS returned 605. Patched, it streamed 45 of 45 frames of a recording, in
order and byte-identical through `MjpegFrameReader`.

## Behaviours worth knowing

- The existing SBI provider reads one frame as a sanity check before returning the stream,
  so the first frame you receive is the device's second.
- A frame whose framing is fine but whose JPEG is corrupt is reported as `INVALID_FRAME`
  (recoverable) and skipped. A dropped connection or unparseable framing triggers reconnect.
  A `Content-Length` over 16 MiB, or 64 KiB of header without one, counts as unparseable framing,
  so a corrupt stream cannot exhaust memory or spin. Garbage containing no `0xFF` byte can desync
  the framing; this was tested and is a property of the existing wire format.
- MDS device info has no resolution/FPS, so for SBI devices those capabilities are OBSERVED
  from the stream (`DeviceInfo.maxWidth` etc. are 0 until the first frame).
- Android frames are rotated upright using CameraX's rotation and are not mirrored.
- How the engine handles errors from a frame source:
  - A device error (unavailable, connection failure, disconnect, camera unavailable) shows "Device error" with
    Retry, and is never counted as a liveness attempt.
  - Retry is offered for the first 3 device errors in a session. A 4th device error ends the session with that
    device code.
  - `INVALID_FRAME` from the source counts as a corrupt frame: 3 of them fail the attempt as `INVALID_FRAME`.

## Test status (be aware)

- **Real build.** The suite runs inside the real Registration Client v1.2.0.2 build (JDK 11, the upstream MOSIP
  classes, no stubs), with the "Build and test" command above. The `registration-services` liveness tests total 96,
  all passing: the original 83, plus 13 new.
- **New tests:**
  - preview tee
  - harness model and headless run
  - `SbiStreamProbe`
  - device-path interoperability
  - unplug and replug through the real engine
  - recorded-clip end-to-end
- **Bugs found and fixed.** The unplug and replug test found an engine bug, now fixed in Part 3: a frame left over
  from before the unplug could resume the attempt and swallow the user's Retry.
- **MockMDS.** The patched MockMDS was run as a process. `SbiStreamProbe` and the client's own request got 45 of
  45 frames from a recording. The patch's own `FaceStreamProviderTest` passes (7).
- **Not run with hardware:**
  - `SarxosWebcamFrameGrabber` and the MockMDS webcam mode compile against the real `webcam-capture` 0.3.12 jar,
    but have not been run with a camera here.
  - `CameraXFrameSource` type-checks against stubs of the CameraX/Android types it uses. Its YUV→RGB conversion,
    rotation (0/90/180/270) and stride were run on synthetic frames. It has not been built with the Android SDK or
    run on a device.
  - Nothing has been run against real SBI hardware.
- **Manual checks.** The checks that need hardware or people are listed in `docs/part1-qa-checklist.md`.

---

# Part 3: Active challenges, orchestrator and config

Part 3 is the liveness engine. It takes frames from any `FrameSource` (Part 1) and per-frame results from any `FaceAnalyzer` (Part 2), and decides PASSED or FAILED for the resident, operator and supervisor workflows. It is pure Java with no runtime dependencies beyond `liveness-api`, so the same jars run in the Desktop client and in the Android native layer.

| Module | What | Ships in the apps |
|---|---|---|
| `liveness-core/` | `DefaultLivenessService`: the session state machine, passive decision, random challenges, blink/smile/turn/look detectors, retries, timeouts, cooldown, policy and audit | yes |
| `liveness-fake/` | `FakeLivenessService`: scripted scenarios with real timing, retry and cancel, so the UIs can be built before the real engine runs (see `liveness-fake/README.md`) | dev builds |
| `liveness-tools/` | Trace recorder, replayer and threshold-tuning report (see `liveness-tools/README.md`) | no |

## Build and test (Part 3)

Use the same setup as above (JDK 11, Registration Client v1.2.0.2 checkout, patch copied over `registration/`), and also copy `liveness-core`, `liveness-fake` and `liveness-tools`. Then, from `registration-client/registration`:

    mvn -pl liveness-api,liveness-core,liveness-fake,liveness-tools -am verify -Dgpg.skip

This runs about 260 tests, enforces 85% line coverage on `liveness-core`, and checks API use against the Java 8 and Android API 21 signatures.

## Use (Parts 4 and 5)

```java
LivenessService liveness = DefaultLivenessService.builder()     // or: new FakeLivenessService(configProvider)
        .config(configProvider)          // host config; missing keys use liveness-default.properties
        .analyzerFactory(analyzerFactory) // Part 2: one FaceAnalyzer per session
        .auditSink(auditSink)
        .build();
LivenessSession session = liveness.newSession(WorkflowType.RESIDENT); // null = liveness disabled
session.start(frameSource, listener);  // callbacks arrive on a background thread: marshal to the UI thread
// Retry button -> session.retry();  leaving the screen -> session.cancel();
// accept the face only if result.passed(); use result.bestFrame
```

**Documentation:**
- [docs/person3-design.md](docs/person3-design.md): architecture, decision flow, challenges, config, errors, security, ISO/IEC 30107
- [PART3_STATUS.md](PART3_STATUS.md): requirement-by-requirement status
- [docs/contract-change-proposals.md](docs/contract-change-proposals.md): contract changes, both accepted and proposed
