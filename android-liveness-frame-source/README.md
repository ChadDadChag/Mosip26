# Android Liveness FrameSource (CameraX)

## Status: type-checked and conversion-tested off-device; NOT built with the Android SDK, NOT run on a device

This directory is **not part of the Desktop Maven reactor** and is never built by
`mvn compile`/`mvn test` on `registration-services` or any other existing module.

What has been checked:
- `CameraXFrameSource.java` type-checks against hand-written stubs of exactly the
  CameraX/Android types it uses. This catches typos and signature mismatches only.
- The YUV_420_888 → RGB888 conversion, rotation (0/90/180/270) and every-Nth-frame
  logic were executed against a synthetic `ImageProxy`, and the output pixels matched
  the expected rotated grids.

What has not been checked: compiling against real `androidx.camera.*`, and running on an
emulator or a phone. Treat it accordingly until an Android build does both.

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
  listener never builds a backlog).
- **Every Nth frame**: the `frameStride` constructor argument skips conversion of
  unselected frames. `Frame.sequence()` stays the raw index, so skips appear as gaps.
- **Rotation**: each frame is rotated by `ImageProxy.getImageInfo().getRotationDegrees()`
  while converting, so faces arrive upright (width and height swap at 90/270). Frames are not
  mirrored: front-camera previews often are, but the analysis frames are not. Left/right
  challenge logic must use the unmirrored image.
- YUV_420_888 → RGB888 uses BT.601 coefficients and honours row/pixel strides.
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
