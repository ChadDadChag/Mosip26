# Android Liveness FrameSource (CameraX)

## Status: compiled in the Android Registration Client; runtime results are in the Android integration

This directory is **not part of the Desktop Maven reactor** and is never built by
`mvn compile`/`mvn test` on `registration-services` or any other existing module.

`android-integration/scripts/sync-engine.ps1` copies `CameraXFrameSource.java` into the
Android Registration Client's `android/liveness` module, which compiles it against real
CameraX 1.3.4 (AGP 8.3.2, compileSdk 34) and unit-tests the YUV conversion and rotation
against a reference conversion on a synthetic `ImageProxy`. Edit the file here, then sync;
do not edit the copy in the client.

Earlier checks (before an Android SDK was available): a type check against hand-written
stubs of the CameraX/Android types, and the conversion, rotation and every-Nth-frame
logic run against a synthetic `ImageProxy`.

## Why CameraX instead of an SBI app over intents

The SBI spec defines Android intents for discovery (`io.sbi.device`), device info and capture.
As far as the spec version targeted here goes, it has no streaming equivalent of the Desktop
`/stream`. Each intent capture is a one-shot activity round trip that returns a signed
ISO 19794-5 face, which is often JPEG 2000 and not decodable by Android's built-in codecs.
That cannot feed active challenges ("blink", "turn left") at frame rate.

So on Android:
- **liveness frames** come from CameraX (this module);
- **the stored biometric** still comes from the SBI app's capture intent, as today.

Part 3 must bind the two (for example, match the SBI-captured face against the liveness
frames) so that one person cannot pass liveness while a different face is captured. If a
deployment's SBI app does offer streaming, add it as another `FrameSource`; nothing
downstream changes. Check this against the SBI version your devices implement.

## What it implements

`CameraXFrameSource implements FrameSource` from `liveness-api`, the same pure-Java
contract the Desktop `SbiStreamFrameSource` and `MockFrameSource` implement.

- `start(Listener)`: obtains a `ProcessCameraProvider` asynchronously and binds an
  `ImageAnalysis` use case (YUV_420_888, keep-only-latest backpressure, so a slow
  listener never builds a backlog). The resolution comes from a `ResolutionSelector`:
  the requested size (640×480 in the client) or the closest higher, then lower, at the same aspect ratio.
- **Preview**: given a `Preview.SurfaceProvider` (for example `PreviewView.getSurfaceProvider()`),
  a `Preview` with the same aspect ratio is bound together with the analysis in one
  `bindToLifecycle` call (after `unbindAll`). The preview is display-only.
- **Every Nth frame**: the `frameStride` constructor argument skips conversion of
  unselected frames. `Frame.sequence()` stays the raw index, so skips appear as gaps.
- **Rotation**: each frame is rotated by `ImageProxy.getImageInfo().getRotationDegrees()`
  while converting, so faces arrive upright (width and height swap at 90/270). Frames are not
  mirrored: front-camera previews often are, but the analysis frames are not. Left/right
  challenge logic must use the unmirrored image.
- YUV_420_888 → RGB888 uses BT.601 coefficients in 16.16 fixed point and honours row/pixel
  strides. Each plane is bulk-copied once into a reused array; the RGB output is a new array
  per frame, because `Frame` keeps the reference. `setDebugTiming(true)` logs, once a second,
  the camera and converted frame rates and the conversion time (never pixels).
- **Capabilities**: `info().capabilities` declares `YUV_420_888` and the requested
  resolution, then switches to the resolution CameraX actually chose (OBSERVED).
- `stop()` clears the analyzer and unbinds on the main thread. It can be called from any thread.
- Listener exceptions are isolated, as in the Desktop implementations.

## Not implemented here

- Camera runtime permission (`android.permission.CAMERA`): request it in the
  Activity/Fragment that owns `lifecycleOwner`, before `start()`.
- Full lists of supported sizes and FPS ranges: these need Camera2 interop
  (`Camera2CameraInfo` → `StreamConfigurationMap`), which is marked experimental in CameraX.
  `CapabilityProbe`-style observation (start, sample frames, stop) works today.
- Focus and exposure tuning beyond CameraX defaults.

## Gradle/manifest configuration

`build.gradle` and `src/main/AndroidManifest.xml` hold the minimal library-module
configuration: CameraX core/camera2/lifecycle, Guava `ListenableFuture`, and the CAMERA permission.
The versions have not been resolved against Google's Maven repository. Check them against
the app's compile and target SDK.
