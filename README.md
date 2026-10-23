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

## What is in here

| Path | What it is |
|---|---|
| `registration/liveness-api/` | The shared contracts (`Frame`, `FrameSource`, `DeviceCapabilities`, `ErrorCode`, ...). New Maven module. |
| `registration/registration-services/.../liveness/device/sbi/` | `SbiStreamFrameSource` + `SbiStreamFrameSourceFactory`: reads the existing SBI `/stream` MJPEG through `BioService.getStream()`. |
| `.../liveness/device/util/` | `MjpegFrameReader` (MJPEG parsing), `JpegToRgb888Converter` (JPEG to RGB888), `CapabilityProbe`. |
| `.../liveness/device/mock/` | `MockFrameSource` (3 modes), `SarxosWebcamFrameGrabber`, `MjpegRecorder`. |
| `android-liveness-frame-source/` | `CameraXFrameSource` for Android. Not part of the Maven build. |
| `docs/part1-frame-streaming-architecture.md` | Existing-integration analysis, interface, architecture and sequence diagrams, error and capability tables. |
| `registration/pom.xml`, `registration/registration-services/pom.xml` | Two small edits: the `liveness-api` module/dependency, plus `webcam-capture` 0.3.12. |

No existing Registration Client class (Streamer, controllers, SBI provider, BioServiceImpl,
`/capture`) is modified.

## How to install

Requirements: **JDK 11** (the upstream build uses Lombok 1.18.16, which does not run on newer
JDKs), **Maven 3.6+**, **Git**, and internet access for the first build.

1. Get the Registration Client at the version the patch is built against:

       git clone --depth 1 --branch v1.2.0.2 https://github.com/mosip/registration-client.git

2. Copy the contents of `part1_patch/registration/` over `registration-client/registration/`.
   The two `pom.xml` files are the v1.2.0.2 originals plus only the liveness additions.

## Build and test

PowerShell, from `registration-client\registration` (adjust the JDK/Maven paths to yours):

    $env:JAVA_HOME = "C:\Users\penna\tools\jdk-11.0.32.1+1"
    $mvn = "C:\Users\penna\tools\apache-maven-3.9.9\bin\mvn.cmd"

    # build + Part 1 tests only (fast)
    & $mvn -pl liveness-api,registration-services -am test "-Dtest=**/liveness/**/*Test" "-Dsurefire.failIfNoSpecifiedTests=false"

    # build without tests
    & $mvn -pl liveness-api,registration-services -am install -DskipTests

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

The SBI path (`SbiStreamFrameSource`) needs a running MDS (a real device or MockMDS) and is used
from inside the Registration Client (see "How to use it" below); it has no standalone demo.

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

Start `mosip-mock-services-master/MockMDS` with its own scripts (`run_reg.bat` / `run.sh`)
or `mvnw spring-boot:run`, then run the Registration Client's device discovery as usual.

Known blocker: the Registration Client's `StreamSbiRequestDTO` sends `serialNo`, but MockMDS's
`StreamingRequestDetail` expects `deviceId`. MockMDS therefore looks for a non-existent
`streamnull<subId>.jpg` and the stream fails, for the existing Streamer preview too. Neither
side was changed. The smallest fix is `@JsonAlias("serialNo")` on `deviceId` in the MockMDS
class. The mock modes above do not use this path.

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

## Test status (be aware)

- Executed: 88 tests (API, converter, MJPEG parser, mock modes, recorder, capability probe, stride,
  reconnect, corrupt frames, restart-while-blocked, Part 2 engine) with JUnit 4.12 and Mockito 5.
  They were compiled against small stubs of the five upstream MOSIP types (`BioService`,
  `MdmBioDevice`, `RegBaseCheckedException`, `RegistrationExceptionConstants`,
  `MosipDeviceSpecificationFactory`), and the suite passed three runs in a row. A full `mvn test`
  inside the real Registration Client has not been run. Test code uses only Mockito calls that
  exist in both 1.x and 2.x, which are on the upstream test classpath.
- `SarxosWebcamFrameGrabber` compiles against the real `webcam-capture` 0.3.12 jar but has not
  been run with a camera.
- `CameraXFrameSource` type-checks against stubs of the CameraX/Android types it uses. Its
  YUV→RGB conversion, rotation (0/90/180/270) and stride were executed on synthetic frames.
  It has not been built with the Android SDK or run on a device.
- Not run against real SBI hardware or the actual MockMDS process.
