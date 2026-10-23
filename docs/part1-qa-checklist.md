# Part 1 QA checklist (device and frame pipeline)

This list maps each problem-statement item that Part 1 owns to the tests that cover it, and to the manual check for the things code cannot test. "Automated" means the item runs in the Registration Client v1.2.0.2 build:

    mvn -pl liveness-api,liveness-core,liveness-fake,registration-services -am test \
      "-Dtest=**/liveness/**/*Test,FrameTest" -Dsurefire.failIfNoSpecifiedTests=false -Dgpg.skip

Engine-side behaviour (passive and active decisions, challenges, retries, messages) is Part 3's and is tracked in `PART3_STATUS.md`. The screens are Parts 4 and 5.

Test class names below are under `registration-services/src/test/java/io/mosip/registration/liveness/device/`.

## Mandatory task 1: L0/L1 device integration

| Requirement | Automated | Manual check |
|---|---|---|
| Integrate with L0/L1 devices | `sbi/SbiStreamFrameSourceTest`, `sbi/DevicePathInteropTest` (the SBI adapter and the mock device give identical frames and the same liveness decision) | Run a real L0/L1 device or patched MockMDS. Start `SbiStreamProbe <port> <serialNo>` and check that frames arrive |
| Receive face frame streams | `sbi/SbiStreamFrameSourceTest.convertsStreamFramesIntoLivenessFrames`, `util/MjpegFrameReaderTest.readsExactMockMdsWireFormat`, `demo/SbiStreamProbeTest` | As above |
| Continuous or periodic processing | `sbi/SbiStreamFrameSourceStrideTest`, `mock/MockFrameSourceTest.frameStrideOfTwo…`. The engine also samples at `analyzeFps` and always takes the newest frame | none |
| Device frame formats | `util/JpegToRgb888ConverterTest` (RGB and grayscale JPEG), `FrameTest` (RGB888 and GRAY8) | A real device's JPEG decodes: check the preview in `LivenessHarness` |
| Communication failures | `sbi/SbiStreamFrameSourceReconnectTest`, `sbi/UnplugReplugIntegrationTest.cameraThatNeverComesBack…` | Stop MockMDS during capture. Expect "Device error" with Retry offered for the first 3 device errors, then a final failure on the 4th |
| Connection and disconnection | `sbi/UnplugReplugIntegrationTest.unplugMidCaptureShowsDeviceErrorWithRetryAndReplugPasses` (unplug, then Retry, then replug, then PASSED, with exactly one result) | Unplug a USB camera during capture, plug it back, press Retry |
| Common device abstraction | `FrameSource` in `liveness-api`. The SBI adapter, the mock device (images, video, webcam) and the Android `CameraXFrameSource` all implement it. `DevicePathInteropTest` checks they are interchangeable | none |

## Error handling owned by Part 1

| Error (problem statement §9) | Code the engine receives | Automated |
|---|---|---|
| Device unavailable | Before capture, `SbiStreamFrameSourceFactory.create` throws `MDS_BIODEVICE_NOT_FOUND`. If the device disappears when the stream opens, the engine gets `DEVICE_UNAVAILABLE` (not recoverable) | `sbi/SbiStreamFrameSourceTest.factoryFailsFastWhenDeviceIsNoLongerAvailable`, `sbi/UnplugReplugIntegrationTest` (the device is gone at reconnect) |
| Device connection failure | `DEVICE_CONNECTION_FAILED` when there is no stream or no data for `frameTimeoutMs`. `DEVICE_DISCONNECTED` (recoverable) on a mid-stream drop, followed by a reconnect with backoff, then a final error when reconnects run out | `sbi/SbiStreamFrameSourceReconnectTest` |
| Camera unavailable | `CAMERA_UNAVAILABLE` (webcam or CameraX) | `mock/MockFrameSourceTest.webcamModeReportsErrorWhenGrabberThrows`, `UnplugReplugIntegrationTest` |
| Invalid or incomplete frame data | `INVALID_FRAME` recoverable, frame skipped. Bad framing triggers a reconnect | `sbi/SbiStreamFrameSourceCorruptFrameTest`, `util/MjpegFrameReaderTest` (truncated, oversized, garbage), `util/JpegToRgb888ConverterTest` |

How the engine treats these codes:
- A device error offers Retry and is never counted as an attempt.
- Retry is offered for the first 3 device errors. A 4th ends the session with the device code.
- 3 corrupt frames fail the attempt as `INVALID_FRAME`.

## Bonus and good-to-have items

| Item | Where | Automated |
|---|---|---|
| Mock L0/L1 device | `MockFrameSource` (images, video, webcam; `asCertificationLevel("L1")`), plus the MockMDS patch (`mock-mds-patch/`) for the real SBI path | `mock/*Test`, `FaceStreamProviderTest` (7, inside MockMDS) |
| Automated liveness and PAD scenarios from recordings | `mock/RecordedVideoE2ETest` plus `docs/part1-test-data-protocol.md` | 1 bundled clip, plus team clips via `-Dliveness.videos.dir` |
| Interoperability testing framework | `sbi/DevicePathInteropTest`, `demo/SbiStreamProbe` (checks any MDS) | yes |
| Diagnostic mode for device troubleshooting | `LivenessHarness --diagnostic` (per-frame score, face box, device errors, audit with `diag.*` stats), `FramePipelineDemo`, `SbiStreamProbe` | `demo/HarnessModelTest` |
| Multiple device vendors | Any SBI-compliant MDS goes through `SbiStreamFrameSource`. Other cameras plug in as a `FrameSource` | `DevicePathInteropTest` |
| Device capability discovery | `DeviceCapabilities`, `CapabilityProbe`, `SbiStreamFrameSourceFactory.probeCapabilities` | `mock/MjpegRecorderTest.probe…` |
| Live face preview (UI §10.1, data side) | `PreviewFrameSource` sends every frame to the preview while the engine keeps its own rate | `util/PreviewFrameSourceTest`, `demo/HarnessModelTest.headlessHarness…` |

## Manual checks that need hardware or people

Tick these when done. Record the device model, OS and date.

- [ ] **Real L0/L1 face device** (vendor MDS): `SbiStreamProbe` streams, then `LivenessHarness` passes a live person.
- [ ] **Patched MockMDS in each mode** (`webcam`, `video`, `images`): the Registration Client preview works and the liveness path passes or fails as expected.
- [ ] **USB webcam:** unplug and replug during capture, on Windows and on macOS or Linux.
- [ ] **Android phone:** `CameraXFrameSource` gives upright frames at 0/90/180/270 rotation, and is stable on a low-end device. It needs the Android SDK build.
- [ ] **Team recordings:** all labels recorded per the protocol. `RecordedVideoE2ETest` is run with them, and the results are shared with Part 2 (PAD) and Part 3 (thresholds).
- [ ] **Throughput:** with `--diagnostic`, average processing time and dropped frames are recorded for each machine.
