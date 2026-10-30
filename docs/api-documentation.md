# API Documentation: Face Liveness and Presentation Attack Detection for the MOSIP Registration Clients

This is the API (Application Programming Interface) documentation for the MOSIP Decode 2026 entry on problem 04. MOSIP
is the Modular Open Source Identity Platform. The project adds a face liveness check to both MOSIP Registration
Clients: the Desktop client (JavaFX, v1.2.0.2) and the Android client (Flutter, v1.1.1).

## Read this first

**This project adds no REST (Representational State Transfer) API and no other network service of its own.** It opens
no port and runs no server. It adds two things:

1. **An in-process Java library, the liveness engine.** Both clients load it and call it as ordinary Java methods.
2. **A Flutter-to-native bridge inside the Android app.** Dart screens use it to reach the Java engine in the same
   process.

The project also calls some APIs that it does not own:

- **SBI (Secure Biometric Interface).** This is MOSIP's standard for talking to biometric devices. On Desktop, the
  client's existing code calls it over HTTP (Hypertext Transfer Protocol) on the local machine.
- **The Android SBI intents and CameraX.** CameraX is the Android camera library.
- **The clients' own audit and configuration modules.**
- **An optional model update index over HTTPS (HTTP Secure).** This is a file format the client expects. This project
  does not host it. It is off by default.

**What was and was not run.** Read every section with these limits in mind.

| Item | Status |
|---|---|
| Real L0/L1 biometric hardware (L0 and L1 are the two SBI device certification levels) | **Never used.** The Desktop device path ran against automated tests, recorded clips and a patched copy of MOSIP's device simulator, MockMDS. |
| A live MOSIP backend | **Never used.** Neither client was run end to end. Desktop hooks were checked by compilation, code review, unit tests and rendered screens. Android hooks were checked through the same gate call from a debug page, by widget tests and by code review. |
| SBI capture on Android | **Never made.** Liveness itself ran on a phone. |
| Online model update | Tested only against local test servers. No public update server exists. |
| Signing key | The built-in key is a **development key**. Replace it before any release. |
| Code examples in section 11 | Compiled against the real classes with `javac` from JDK 11 (Java Development Kit). Examples 1, 3, 4, 5 and 6 were also run, with stub vendor engines, a recorded clip or a packed bundle. The Dart snippets in section 6 are copied from the client's hook code and were not compiled for this document. |

Paths that start with `lib/`, `pigeon/` or `android/` are in the Android client after the patches in
`android-integration/patches/` are applied. All other paths are in this repository. A few classes named in section 5
belong to the upstream Desktop client v1.2.0.2, and this repository does not hold their source.

### Terms

| Term | Meaning |
|---|---|
| Liveness check | A short test that a live person is in front of the camera. |
| PAD | Presentation Attack Detection: spotting a photo, a screen or a mask held up in place of a live face. |
| MDS | MOSIP Device Service: the software on a machine that exposes a biometric device through SBI. |
| Session | One liveness run for one person, including its retries. |
| Attempt | One try inside a session. The default is 4 attempts (1 + `maxRetries`). |
| Workflow | Who is being checked: `RESIDENT`, `OPERATOR` or `SUPERVISOR`. Each has its own policy. |
| Passive check | The first step. The engine scores frames while the person simply looks at the camera. |
| Active challenge | A prompted action, such as a blink or a head turn, used when the passive score is inconclusive. |
| Gate | The one call a client screen makes before it captures or authorises a face. |
| Best frame | A sharp, frontal frame kept from a passing session. |
| Face binding | A check that the face captured through SBI is the person who just passed liveness. |
| Identity binding | A check that the live face is the logged-in user's onboarded face. It is used where no face capture follows, such as a password login. |
| Fail closed | An error refuses the face or the step. It never lets it through. |
| Model bundle | The five signed model files the analyzer loads. |
| EAR | Eye Aspect Ratio: eye height divided by eye width, computed from six landmarks. It falls when the eye closes. |
| ONNX | Open Neural Network Exchange, a file format for machine learning models. The engine runs models on ONNX Runtime. |
| ISO/IEC 19794-5 | The International Organization for Standardization and International Electrotechnical Commission standard for face image records. SBI face captures use it. |
| NaN | Not a Number: the floating-point value the API uses for "unknown". |
| CPU | Central Processing Unit, the main processor. |
| YUV | A camera colour format that stores brightness and colour separately. |
| PNG | Portable Network Graphics, an image format. |
| JSON | JavaScript Object Notation, the text data format. |
| JWT | JSON Web Token: signed data in the form `header.payload.signature`. |
| URL | Uniform Resource Locator: a web address. |
| UUID | Universally Unique Identifier, a random 128-bit id. |
| UI | User Interface. |
| JVM | Java Virtual Machine. |
| SDK | Software Development Kit. |
| ECDSA, P-256 | Elliptic Curve Digital Signature Algorithm, and the curve used with it. |
| SHA-256 | Secure Hash Algorithm with a 256-bit result. |
| MJPEG | Motion JPEG: a stream of separate JPEG images. JPEG is the Joint Photographic Experts Group image format. |
| RGB888 | Red, green and blue pixels, one byte each. |
| CBEFF, BIR, BDB | Common Biometric Exchange Formats Framework, Biometric Information Record and Biometric Data Block: how MOSIP wraps stored biometrics. |

### Contents

1. [Overview](#1-overview)
2. [Java engine contract](#2-java-engine-contract)
3. [Configuration keys](#3-configuration-keys)
4. [Analyzer and model bundle API](#4-analyzer-and-model-bundle-api)
5. [Device APIs used](#5-device-apis-used)
6. [Android bridge](#6-android-bridge)
7. [Desktop integration entry points](#7-desktop-integration-entry-points)
8. [Online model update](#8-online-model-update)
9. [Authentication and security](#9-authentication-and-security)
10. [Audit events and metrics](#10-audit-events-and-metrics)
11. [Example use cases](#11-example-use-cases)
12. [Further reading](#12-further-reading)

---

## 1. Overview

![System overview: both clients, the shared engine, the device path and the optional online parts](images/architecture/a-system-overview.svg)

| Interface | Kind | Caller | Provider | Desktop | Android | Section |
|---|---|---|---|---|---|---|
| `LivenessService`, `LivenessSession`, `LivenessListener` | In-process Java | Client code | `liveness-core` | Yes | Yes | 2 |
| `FrameSource` | In-process Java | Engine | `SbiStreamFrameSource`, `MockFrameSource` (Desktop), `CameraXFrameSource` (Android) | Yes | Yes | 2.2 |
| `FaceAnalyzer` | In-process Java | Engine | `OnnxFaceAnalyzer`, or your own | Yes | Yes | 2.5, 4 |
| `ConfigProvider`, `AuditSink` | In-process Java | Engine | Each client's adapter | Yes | Yes | 2.17, 2.18 |
| Model bundle files | Signed files | Analyzer | Whoever signs a release | Yes | Yes | 4 |
| SBI `/info`, `/device`, `/stream`, `/capture` | Local HTTP | The client's existing device code | MDS | Yes | No | 5 |
| SBI intents | Android intents | The client's existing device code | SBI app | No | Yes | 5.10 |
| Pigeon `LivenessApi`, event channel, preview view | In-app messages | Dart | Java in the same app | No | Yes | 6 |
| `LivenessGate` | In-process call | Client screens | Each client | Yes (Java) | Yes (Dart) | 6.5, 7.1 |
| Update index and zip | HTTPS `GET`, off by default | `ModelUpdater` | A host the deployment provides | Yes | Yes | 8 |

**One check, in order:**

1. A client screen calls the gate for a workflow.
2. The device or camera delivers frames to the engine through `FrameSource`.
3. The analyzer scores each frame. The engine takes the median score of 10 usable frames.
4. A median of at least 0.85 passes. A median below 0.30 is treated as an attack. Anything between starts active
   challenges.
5. The engine reports exactly one result, with a best frame on a pass.
6. The screen continues. On a face capture, face binding compares the captured face with the best frame. On a
   password-only step, identity binding compares the best frame with the user's onboarded face.

---

## 2. Java engine contract

Package `io.mosip.registration.liveness.api`, module `liveness-api`. Module `liveness-core` holds the real engine and
`liveness-fake` holds a scripted one for UI work.

### 2.1 Lifecycle and threads

```text
service.newSession(workflow)            null means liveness is disabled: keep the legacy flow
session.start(source, listener)         the engine starts the source and creates one analyzer
  onEvent(...)                          STARTED, CHECKING_PASSIVE, GUIDANCE, CHALLENGE_PROMPT, ...
  onEvent(ATTEMPT_FAILED)               the caller may call session.retry()
  onEvent(DEVICE_ERROR, recoverable)    the caller may call session.retry()
  onComplete(result)                    exactly once, and nothing after it
session.cancel()                        the user left: delivers CANCELLED
```

The engine stops the source exactly once, before it delivers the final result. An app may therefore reuse the same
source, or start another session, from inside `onComplete`.

| Thread | Name | Runs | Rule for your code |
|---|---|---|---|
| Source | Set by the source, for example `LIVENESS_SBI_STREAM_THREAD` | `FrameSource.Listener.onFrame` and `onError` | The engine only stores the newest frame here. An older frame not yet analysed is dropped. |
| Worker | `liveness-<first 8 characters of the session id>` | Creates, calls and closes the analyzer, and makes every decision. It wakes at least every 50 ms, so timeouts fire without frames. | Your `FaceAnalyzer` runs here. |
| Dispatcher | `liveness-events-<first 8 characters of the session id>` | Every `LivenessListener` callback, in order | Never the UI thread. Marshal to your UI thread yourself. |

Rules for every caller:

- Accept a face only in `onComplete`, when `result.passed()` is true. Events only drive the screen.
- Call `cancel()` when the user leaves the screen. A session left without a final result keeps an idle thread.
- `retry()` and `cancel()` are safe from any thread, including inside a callback.
- An exception thrown by your listener is swallowed. It cannot break the session, and you will not see it.

### 2.2 `FrameSource`

**Purpose.** One adapter for any face device, so that the engine never touches SBI, MDS or camera APIs. Decoding
(JPEG, MJPEG, YUV camera formats) happens inside the adapter.

| Method | Rules |
|---|---|
| `DeviceInfo info()` | Valid before `start()`. Sources that watch the stream refine it while running. |
| `void start(Listener listener)` | Returns at once and never throws. Errors go to `Listener.onError`. Each start is a fresh run, and `sequence` restarts at 0. If `start` throws anyway, the engine treats it as a recoverable `DEVICE_UNAVAILABLE`. |
| `void stop()` | Idempotent. Releases the device. A stopped run never delivers to its listener again, even if its thread is still inside a blocking device call. |
| `boolean isRunning()` | True while a run is active. |

`FrameSource.Listener` (the engine implements this):

| Method | Rules |
|---|---|
| `void onFrame(Frame frame)` | Runs on the source's thread. Must return quickly. |
| `void onError(ErrorCode code, String detailForAuditOnly, boolean recoverable)` | `recoverable = true`: the source is retrying by itself, or skipped one frame. `false`: the source gave up and delivers nothing more until it is started again. The detail goes to the audit trail, never to the screen. |

`FrameSource.DeviceInfo` has public final fields: `deviceId`, `vendor`, `model`, `sbiVersion`, `certificationLevel`
(`"L0"`, `"L1"` or `"NONE"`), `maxWidth`, `maxHeight`, `maxFps` (0 means unknown) and `capabilities`. Its methods
`withCapabilities(DeviceCapabilities)` and `withCertificationLevel(String)` return changed copies. The engine records
the device id and certification level in the audit trail. It does not refuse a device because of its level.

| Implementation | Client | Source of frames |
|---|---|---|
| `SbiStreamFrameSource` | Desktop | SBI `/stream` (section 5). `SbiStreamFrameSourceFactory.create(modality)` builds it. |
| `DeferredFrameSource` | Desktop | Wraps the SBI source. It opens the device only when started, so an unplugged device becomes a recoverable error with Retry. |
| `MockFrameSource` | Desktop, development and tests | `new MockFrameSource(Path imageOrDir, double fps)`, `ofVideoFile(Path mjpeg, double fps, boolean loop)` or `ofWebcam(WebcamFrameGrabber, double fps)`. `asCertificationLevel("L1")` makes it report that level. |
| `CameraXFrameSource` | Android | CameraX image analysis, 640 x 480 requested, at most 15 frames per second. |
| `PreviewFrameSource` | Desktop | A wrapper that also sends every frame to `PreviewListener`s, because `start` takes one listener and the engine owns it. |

`CapabilityProbe.probe(FrameSource stopped, int sampleFrames, long timeoutMs)` runs a source briefly and returns what
it really delivers.

### 2.3 `Frame`

**Purpose.** One decoded video frame. Pixels are packed, row by row, with no padding. They are upright where the
source knows the rotation, and never mirrored.

| Member | Meaning |
|---|---|
| `Frame(byte[] rgb, int width, int height, long timestampMs, long sequence, String deviceId)` | An RGB888 frame. |
| `Frame(byte[] pixels, PixelFormat format, int width, int height, long timestampMs, long sequence, String deviceId)` | Any format. The buffer must be exactly `width * height * format.bytesPerPixel` bytes, or the constructor throws `IllegalArgumentException`. |
| `byte[] pixels()`, `PixelFormat format()` | The raw buffer. `PixelFormat` is `RGB888` (3 bytes) or `GRAY8` (1 byte, for mono or near-infrared face cameras). |
| `byte[] rgb()` | Always RGB888. For `GRAY8` it builds an expanded copy once. |
| `int width()`, `int height()` | Size in pixels. |
| `long timestampMs()` | Monotonic capture time, not wall-clock time. |
| `long sequence()` | Strictly increasing within a run. Gaps mean skipped or dropped frames. |
| `String deviceId()` | SBI device id or serial number, `mock:*` or `android-camera:*`. |

The engine counts a frame as a violation if its sequence or timestamp does not increase, or if it arrives less than
5 ms after the last one. Three violations in an attempt fail it with `INVALID_FRAME`. Give each frame its own buffer:
the engine keeps a frame beyond the `onFrame` call.

### 2.4 `DeviceCapabilities`

**Purpose.** What a device can produce. An empty list means "not known", never "not supported".

| Member | Meaning |
|---|---|
| `DeviceCapabilities(List<String> streamFormats, List<Resolution> resolutions, List<Integer> frameRates, Origin origin)` | Copies the lists. Formats look like `"MJPEG"`, `"JPEG"`, `"PNG"`, `"YUV_420_888"`. |
| `static DeviceCapabilities unknown()` | Empty lists, origin `UNKNOWN`. |
| `Resolution largestResolution()`, `int maxFrameRate()` | Largest known values. Null and 0 mean unknown. |
| `Origin` | `DECLARED` (the source states it), `OBSERVED` (measured from the stream), `UNKNOWN`. |
| `Resolution(int width, int height)` | Both must be positive. |

SBI device information has no resolution or frame rate fields. For SBI devices these values are therefore `OBSERVED`
from the live stream.

### 2.5 `FaceAnalyzer`

**Purpose.** Runs all per-frame machine learning once: face detection, landmarks, head pose, quality and PAD. To plug
in another engine, implement this interface (section 11, example 4).

| Method | Rules |
|---|---|
| `FrameAnalysis analyze(Frame frame) throws LivenessEngineException` | Must return a non-null analysis with a non-null `frame`. |
| `String modelVersion()` | Goes into the result and the audit trail. |
| `void close()` | Called once at the end of the session, on the worker thread. |

**Threading.** Not thread-safe. The engine creates one analyzer per session and calls it from that session's worker
thread only. Retries in a session keep the same analyzer.

**Creation.** `liveness-core` defines the factory the engine takes:

```java
package io.mosip.registration.liveness.core.session;

public interface FaceAnalyzerFactory {
    FaceAnalyzer create() throws LivenessEngineException;
}
```

**How the engine treats analyzer failures:**

| Thrown or returned | Effect |
|---|---|
| `LivenessEngineException` with `MODEL_INTEGRITY_FAILED` | The session ends at once. |
| `LivenessEngineException` with `INVALID_FRAME` | Counts as one frame violation. |
| Any other exception, or a null analysis | Skips the frame. Three failures in a row fail the attempt with `ENGINE_ERROR`. |
| `create()` throws `MODEL_INTEGRITY_FAILED` | The session ends with that code. |
| `create()` throws anything else | The session ends with `ENGINE_ERROR`. |

### 2.6 `FrameAnalysis`

**Purpose.** The analyzer's output for one frame.

```java
new FrameAnalysis(Frame frame, int faceCount, float[] faceBox, FaceLandmarks landmarks,
                  float yawDeg, float pitchDeg, float rollDeg, Set<QualityIssue> qualityIssues,
                  float livenessScore, Map<String, Float> padDetails, long processingMs)
```

| Field | Meaning |
|---|---|
| `frame` | The analysed frame. Required. |
| `faceCount` | 0 means no face. More than 1 means several faces. |
| `faceBox` | Primary face as `[x, y, w, h]` in pixels. Null when `faceCount` is 0. |
| `landmarks` | Primary face. May be null. |
| `yawDeg`, `pitchDeg`, `rollDeg` | Pose in the subject's frame, never mirrored. Positive yaw: turned to the subject's left. Positive pitch: looking up. Positive roll: the subject's left eye is higher. Use `Float.NaN` when unknown. |
| `qualityIssues` | Set of `TOO_DARK`, `TOO_BRIGHT`, `BLURRY`, `FACE_TOO_SMALL`, `FACE_TOO_LARGE`, `FACE_OFF_CENTER`, `FACE_CUT_OFF`. Null becomes an empty set. |
| `livenessScore` | Probability from 0 to 1 that the primary face is live. `Float.NaN` when not computed. |
| `padDetails` | Raw model outputs for the audit trail only. Never shown. The engine records at most 8 keys. Null becomes an empty map. |
| `processingMs` | Time spent on this frame. |
| `boolean usable()` | True when `faceCount == 1` and there is no quality issue. |

A frame enters the passive window only if it is `usable()`, has a score, and its reported yaw and pitch are within 25
degrees. Unknown pose (NaN) does not count against a frame.

### 2.7 `FaceLandmarks`

**Purpose.** Named landmark points in pixels, independent of the model that produced them. The engine computes blink
and smile from these.

| Field | Meaning |
|---|---|
| `float[][] leftEye`, `rightEye` | 6 points each, subject's left and right, in EAR order: outer corner, upper-outer, upper-inner, inner corner, lower-inner, lower-outer. |
| `float[] mouthLeft`, `mouthRight` | Outer mouth corners. |
| `float[] mouthUpperInner`, `mouthLowerInner` | Inner lip mid-points. |
| `float[] noseTip`, `chin` | Single points. |
| `float[] leftIris`, `rightIris` | Iris centres. May be null. |

The constructor takes the fields in this order. Landmarks are used only inside the detectors. They are never audited
or logged.

### 2.8 `LivenessService`

**Purpose.** The entry point the apps use.

| Method | Rules |
|---|---|
| `LivenessSession newSession(WorkflowType workflow)` | Returns null when liveness is disabled for that workflow. The session takes a copy of the policy. During a cooldown it returns a session that fails as soon as it starts (section 2.9). |
| `LivenessPolicy policy(WorkflowType workflow)` | A copy of the policy in force. |
| `void reloadConfig(ConfigProvider config)` | Call after a config sync. Sessions already running keep their old policy. |

Thread-safe. Each client holds one instance.

**Real engine.** `DefaultLivenessService` in `liveness-core`:

```java
DefaultLivenessService engine = DefaultLivenessService.builder()
        .config(configProvider)        // missing keys use the bundled defaults; null is allowed
        .analyzerFactory(factory)      // required: build() throws IllegalStateException without it
        .auditSink(auditSink)          // optional: no audit if absent
        .build();
```

`Builder.tuning(EngineTuning)` changes engine constants for tuning tools. `clock` and `random` are for tests only.
`engine.metrics()` returns the session metrics (section 10.4). The engine makes no network call.

**Scripted engine.** `FakeLivenessService` in `liveness-fake` implements the same contract with real policy, retry,
cancel and cooldown behaviour, but scripted decisions (`FakeScenario`, for example `PASS_ACTIVE` or `PAD_ATTACK`). It
is for UI work. Its model version is `fake-1.0`. On Desktop only a JVM property selects it. Android release builds
refuse it.

### 2.9 `LivenessSession`

**Purpose.** One liveness run for one person, including its retries.

| Method | Rules |
|---|---|
| `void start(FrameSource source, LivenessListener listener)` | Call once. A second call throws `IllegalStateException`. On the real engine, a null source or listener throws `IllegalArgumentException`. |
| `void retry()` | Valid after `ATTEMPT_FAILED` or a `DEVICE_ERROR` with `recoverable = true`. Ignored at any other time. After `ATTEMPT_FAILED` it starts the next attempt with a new challenge sequence. After a device error it repeats the same attempt and restarts the source. |
| `void cancel()` | Idempotent, from any thread. Stops the source, then delivers `onComplete` with decision `CANCELLED`, even while the analyzer is running. If called before `start()`, the later `start()` delivers `CANCELLED`. No event is emitted for a cancel. |
| `String sessionId()` | A random UUID. It links the screen, the audit trail and the log. |

**Cooldown.** When a session ends in `MAX_RETRIES_EXCEEDED` and the policy says `COOLDOWN`, new sessions for that
workflow are refused for `cooldownSec`. A refused session never touches the source. `start()` immediately gives
`FAILED_FINAL` and a `FAILED` result with `MAX_RETRIES_EXCEEDED`. The cooldown is held in memory, so it resets when the
app restarts.

### 2.10 `LivenessListener`

| Method | Rules |
|---|---|
| `void onEvent(LivenessEvent event)` | Drives the screen. Runs on the dispatcher thread, in order. |
| `void onComplete(LivenessResult result)` | Called exactly once per session. No event follows it. |

### 2.11 `LivenessEvent`

**Purpose.** Everything a screen needs. The UI only maps events to texts. All fields are public and final.

| `type` | When | Fields set |
|---|---|---|
| `STARTED` | Each attempt begins | `attemptNumber` |
| `CHECKING_PASSIVE` | The first frame of an attempt was accepted. Sent again when a guidance hint no longer applies. | `attemptNumber` |
| `GUIDANCE` | A positioning hint persisted for 300 ms. Hints change at most every 500 ms, except `NO_FACE` and `MULTIPLE_FACES`, which may change sooner. `LOOK_AT_CAMERA` also follows a turn or look challenge. | `guidance`, `attemptNumber` |
| `CHALLENGE_PROMPT` | Ask for an action | `challenge`, `challengeIndex` (from 1), `challengeCount`, `timeoutMs`, `attemptNumber` |
| `CHALLENGE_PROGRESS` | Progress passed a quarter step, or every 1.5 s as a "please continue" heartbeat. Also sent when a hint clears. | `challenge`, `challengeIndex`, `challengeCount`, `progress` (0 to 1), `attemptNumber` |
| `ACTION_DETECTED` | The current challenge was met | `attemptNumber` |
| `HOLD_STILL` | The engine is picking the best frame after the last challenge. Not sent on a passive pass. | `attemptNumber` |
| `ATTEMPT_FAILED` | An attempt failed and Retry is possible | `error`, `retriesLeft`, `attemptNumber`, `recoverable = true` |
| `PASSED` | Just before `onComplete(PASSED)` | `attemptNumber` |
| `FAILED_FINAL` | The session failed | `error`, `retriesLeft = 0`, `recoverable = false` |
| `DEVICE_ERROR` | A device problem | `error`, `retriesLeft`, `recoverable`. If `recoverable` is true, show Retry. |

`retriesLeft` is the number of Retry presses still allowed. With the default `maxRetries` of 3, the first failure
reports 3.

`Guidance` values: `NO_FACE`, `MULTIPLE_FACES`, `MOVE_CLOSER`, `MOVE_BACK`, `CENTER_FACE`, `IMPROVE_LIGHTING`,
`HOLD_STEADY`, `LOOK_AT_CAMERA`. The text key is `liveness.guidance.<lower-case name>`.

The static factories `of`, `guidance`, `prompt`, `progress` and `failure` are for code that implements a session. The
constructor is private.

### 2.12 `LivenessResult`

**Purpose.** The final outcome of a session, and the only thing a flow may use to accept or reject a face.

| Field | Meaning |
|---|---|
| `decision` | `PASSED`, `FAILED` or `CANCELLED`. |
| `passPath` | `PASSIVE_ONLY`, `ACTIVE_CHALLENGE`, or `NONE` when not passed. |
| `failureCode` | Null when passed. `CANCELLED` when cancelled. When retries ran out it is `MAX_RETRIES_EXCEEDED`, and the underlying cause is in the audit trail as `lastCause`. |
| `bestFrame` | The frame to hand to the capture or authentication flow. Null unless passed. Treat a null on a pass as "no frame": face binding then rejects with `NO_LIVE_FRAME`. |
| `passiveScore` | Median score of the passive window. `NaN` when no window was decided, and on a cancel. |
| `challengesIssued` | The challenges of the last attempt. Empty on a passive pass. |
| `attempts` | Attempts used. 0 for a cooldown refusal. |
| `durationMs` | Session duration. |
| `sessionId` | The session's UUID. |
| `modelVersion` | The bundle version, or `unavailable` when no analyzer was loaded. |

`boolean passed()` is true when `decision` is `PASSED`.

### 2.13 `LivenessPolicy`

**Purpose.** The settings for one workflow. Section 3.2 lists every field.

| Member | Meaning |
|---|---|
| `static LivenessPolicy load(ConfigProvider cfg, WorkflowType wf)` | Reads the keys and calls `validate()`. |
| `void validate()` | Throws `IllegalArgumentException` for bad thresholds, `minChallenges`, `maxRetries`, `challengeTimeoutMs` or `analyzeFps`. |
| `PREFIX` | `mosip.registration.liveness.face.` |
| `OnRepeatedFailure` | `BLOCK` or `COOLDOWN`. |

Fields are public and mutable, so `LivenessService.policy()` returns a copy. Hosts normally read it and never build
one.

### 2.14 `WorkflowType`

| Value | Used for |
|---|---|
| `RESIDENT` | Resident face capture. |
| `OPERATOR` | Operator login, onboarding and packet authentication. |
| `SUPERVISOR` | Supervisor approval and exception review. |

### 2.15 `ChallengeType`

Values: `BLINK`, `SMILE`, `TURN_LEFT`, `TURN_RIGHT`, `LOOK_UP`, `LOOK_DOWN`. The text key is
`liveness.challenge.<lower-case name>`. The default pool has the first four.

The engine picks the sequence with a secure random source, and the user never chooses it. No challenge repeats in a
sequence. Opposite motions are never adjacent. On Retry the sequence differs from the previous one when an alternative
exists. A sequence of one challenge is never `BLINK` alone if another type is usable, because natural blinking could
satisfy it. An analyzer without pose gets no head challenges, and one without eye and mouth landmarks gets no `BLINK`
or `SMILE`.

### 2.16 `ErrorCode`

`ErrorCode.userMessage()` maps each code to a message category. Screens show the category's text, never the code.

| Category (`ErrorCode.UserMessage`) | English text |
|---|---|
| `NONE` | No text. |
| `LIVENESS_NOT_VERIFIED` | "We could not verify face liveness. Please try again." |
| `VERIFICATION_NOT_COMPLETED` | "Face verification could not be completed. Please try again." |
| `MAX_ATTEMPTS_REACHED` | "Face verification could not be completed." plus the workflow's recovery text. |
| `DEVICE_PROBLEM` | "Camera/device not available. Check the connection and try again." |

`PAD_FAILURE` deliberately maps to the same generic text as an engine error, so that an attacker learns nothing. On a
final failure both clients show the `MAX_ATTEMPTS_REACHED` text with the recovery text, unless the category is
`DEVICE_PROBLEM`, so a final screen never says "try again". The recovery texts are:

- Resident: "Please contact the supervisor. The resident can be registered using the biometric exception process."
- Operator: "Please contact your administrator or try again later."
- Supervisor: "Please contact your administrator."

**Retry column.** "Free" means Retry is offered and uses no attempt. "Uses one" means Retry is offered and uses one
attempt. "Final" means the session ends. For any code marked "Uses one", a failure with no retries left ends the
session as `MAX_RETRIES_EXCEEDED`.

| Code | What triggers it | Retry | Message category |
|---|---|---|---|
| `DEVICE_UNAVAILABLE` | No frame within 5 s of the start; or the device cannot be found or opened; or `FrameSource.start` threw. | Free | `DEVICE_PROBLEM` |
| `DEVICE_CONNECTION_FAILED` | The stream could not be opened; or no bytes arrived for `frameTimeoutMs` (15 s). | Free | `DEVICE_PROBLEM` |
| `DEVICE_DISCONNECTED` | No frame for 2 s during an attempt; or the stream ended, failed or had invalid framing. | Free | `DEVICE_PROBLEM` |
| `CAMERA_UNAVAILABLE` | A camera could not be opened. On Android this includes a refused camera permission, which gives a `FAILED` result without a session. | Free | `DEVICE_PROBLEM` |
| `FACE_NOT_DETECTED` | The passive timeout passed and most rejected frames had no face; or the face was missing for 1 s during a challenge. | Uses one | `LIVENESS_NOT_VERIFIED` |
| `MULTIPLE_FACES` | Several faces for 1.5 s in the passive check; or in 2 frames in a row during a challenge; or the passive timeout passed and this was the main reason. | Uses one | `LIVENESS_NOT_VERIFIED` |
| `POOR_QUALITY` | The passive timeout passed with a face present, but frames were not usable. | Uses one | `LIVENESS_NOT_VERIFIED` |
| `LIVENESS_BELOW_THRESHOLD` | The passive median was between the two thresholds and `activeEnabled` is false. | Uses one | `LIVENESS_NOT_VERIFIED` |
| `PAD_FAILURE` | The passive median was below `padRejectThreshold`; or a rolling 3-frame median fell below it during a challenge or the final hold. No challenge is offered after a passive attack verdict. | Uses one | `VERIFICATION_NOT_COMPLETED` |
| `CHALLENGE_FAILED` | The wrong action was detected; or the face changed during the challenges (a face swap or photo switch). | Uses one | `LIVENESS_NOT_VERIFIED` |
| `CHALLENGE_TIMEOUT` | The action was not detected in `challengeTimeoutMs`; or no stable neutral face appeared in 4 s before a prompt. | Uses one | `LIVENESS_NOT_VERIFIED` |
| `MAX_RETRIES_EXCEEDED` | An attempt failed with no retries left; or a cooldown refused the session. | Final | `MAX_ATTEMPTS_REACHED` |
| `INVALID_FRAME` | 3 violations in one attempt: bad sequence or timing, corrupt frames reported by the source, or frames the analyzer rejected. | Uses one | `VERIFICATION_NOT_COMPLETED` |
| `ENGINE_ERROR` | The analyzer failed on 3 frames in a row; or no challenge could be selected. A second attempt ending this way ends the session. An analyzer that cannot be created, or an internal engine failure, ends the session at once. | Free, then Final | `VERIFICATION_NOT_COMPLETED` |
| `MODEL_INTEGRITY_FAILED` | The model bundle failed verification. There is no fallback. | Final | `VERIFICATION_NOT_COMPLETED` |
| `CANCELLED` | `cancel()` was called. Appears only in the result, never in an event. | Final | `NONE` |

**Device errors.** The first 3 device errors in a session each give `DEVICE_ERROR` with `recoverable = true`. The
fourth gives `DEVICE_ERROR` with `recoverable = false`, then `FAILED_FINAL` with that device code. If a source reports
a recoverable error and frames resume within 3 s, the engine restarts the same attempt by itself and sends `STARTED`
with the same attempt number. If the source gave up (`recoverable = false` from the source), the engine still offers
Retry, and Retry restarts the source. This is how an unplugged camera recovers.

### 2.17 `ConfigProvider`

**Purpose.** An adapter over the host app's configuration.

```java
public interface ConfigProvider {
    String get(String key);   // null when the key is absent
}
```

It has one method, so a lambda works. The engine may call it from any thread that resolves a policy. Desktop provides
`DesktopLivenessConfig` and Android provides `AndroidConfigProvider`. Section 3 lists the keys.

### 2.18 `AuditSink`

**Purpose.** Where the engine writes its audit records.

```java
public interface AuditSink {
    enum Event { LIVENESS_STARTED, LIVENESS_PASSED, LIVENESS_ATTEMPT_FAILED, LIVENESS_FAILED_FINAL,
                 PAD_ATTACK_SUSPECTED, DEVICE_ERROR, MODEL_LOADED, MODEL_INTEGRITY_FAILED, CONFIG_LOADED }

    void record(Event event, WorkflowType workflow, String sessionId, Map<String, String> details);
}
```

- `details` holds strings only: codes, scores, timings, model version, device id. The engine has no way to pass an
  image: the audit code accepts no frame, analysis, landmark or pixel buffer.
- `sessionId` is null for `CONFIG_LOADED`, which belongs to the service and not to a session.
- Most calls come from the session's worker thread. Others come from the app thread that called `newSession`, `policy`
  or, for a cooldown refusal, `start`. Return quickly.
- Auditing is best effort. The engine swallows anything your sink throws, and the liveness outcome never changes.

Section 10 lists every event and field.

### 2.19 `LivenessEngineException`

A checked exception that the analyzer and model code throw.

| Member | Meaning |
|---|---|
| `LivenessEngineException(ErrorCode code, String message)` | No cause. |
| `LivenessEngineException(ErrorCode code, String message, Throwable cause)` | With a cause. |
| `ErrorCode code()` | The error code. |

The engine catches it and never passes it to your listener. You see only the `ErrorCode` in events and the result.
Code that calls `OnnxFaceAnalyzerFactory`, `ModelStore` or `FaceMatcher` directly does need to catch it.

---

## 3. Configuration keys

### 3.1 How a value is found

Both clients read the same keys. The host configuration comes from each client's existing config sync, so the last
synced values also apply offline. An empty value counts as absent.

| Client | Order of lookup |
|---|---|
| Desktop | `ApplicationContext.map()` (synced global parameters and local preferences), then the bundled `liveness-desktop-default.properties`. |
| Android | Debug overrides (debug and profile builds only, held in memory), then the synced global parameters. |

For a **policy** field the engine then adds its own bundled file, `liveness-default.properties`. The full order is:

1. host key for the workflow
2. bundled key for the workflow
3. host `default` key
4. bundled `default` key
5. hard-coded value in `LivenessPolicy`

A bundled workflow value therefore beats a synced `default` value. To change `minChallenges` for operators, set the
`operator.` key itself.

**Invalid policy values.** If any value for a workflow is invalid or cannot be parsed, the whole host config for that
workflow is ignored and the bundled defaults apply. Liveness stays on. The error appears in the audit record
`LIVENESS_CONFIG_LOADED` as `configError`.

### 3.2 Policy keys

Key: `mosip.registration.liveness.face.<scope>.<field>`, where `<scope>` is `default`, `resident`, `operator` or
`supervisor`. The table shows the shipped effective value for each scope.

| Field | `default` | `resident` | `operator` | `supervisor` | Valid range | Meaning |
|---|---|---|---|---|---|---|
| `enabled` | true | true | true | true | boolean | `false`: `newSession` returns null and the legacy flow runs. |
| `passiveThreshold` | 0.85 | 0.85 | 0.85 | 0.85 | above `padRejectThreshold`, up to 1 | A median at or above this passes with no challenge. |
| `padRejectThreshold` | 0.30 | 0.30 | 0.30 | 0.30 | 0 or more, below `passiveThreshold` | A median below this is an attack. Also used for the rolling check during challenges. |
| `passiveWindowFrames` | 10 | 10 | 10 | 10 | integer, at least 1 | Usable frames in the passive decision. |
| `passiveTimeoutMs` | 8000 | 8000 | 8000 | 8000 | integer, above 0 | With no full window in this time, the attempt fails. |
| `activeEnabled` | true | true | true | true | boolean | `false`: a score between the thresholds fails with `LIVENESS_BELOW_THRESHOLD`. |
| `challengeTypes` | `BLINK,SMILE,TURN_LEFT,TURN_RIGHT` | same | same | same | comma list of `ChallengeType` names, not empty | The pool the engine picks from. |
| `minChallenges` | 2 | 2 | **1** | **1** | 1 to the number of distinct pool entries | Challenges in one attempt. |
| `challengeTimeoutMs` | 6000 | 6000 | 6000 | 6000 | integer, at least 1000 | Time for one challenge, from the prompt. |
| `maxRetries` | 3 | 3 | 3 | 3 | integer, 0 or more | Attempts = 1 + `maxRetries`. |
| `onRepeatedFailure` | `BLOCK` | `BLOCK` | **`COOLDOWN`** | `BLOCK` | `BLOCK` or `COOLDOWN` | `COOLDOWN` also refuses new sessions for `cooldownSec`. |
| `cooldownSec` | 60 | 60 | 60 | 60 | integer, 0 or more | Length of the cooldown, held in memory. |
| `diagnosticMode` | false | false | false | false | boolean | Adds `diag.*` statistics to audit records. Never changes the screen. |
| `analyzeFps` | 8 | 8 | 8 | 8 | integer, at least 1 | Frames analysed per second in the passive check. Challenges use at least 15. |

### 3.3 How switches are read

The clients do not read every on/off key the same way. A value outside these rules can change behaviour silently.

| Key | Desktop | Android |
|---|---|---|
| `enabled`, `activeEnabled`, `diagnosticMode` | Only `true`, in any case, is on. Any other value is off, so `enabled=yes` turns liveness off. | Same. |
| `binding.enabled` | Only `true` is on. `binding.enabled=yes` turns binding off. | Only `false` is off. Any other value is on. |
| `binding.identity.enabled`, `binding.identity.requireTemplate` | `true`, `yes`, `1`, `on` and `false`, `no`, `0`, `off`, in any case. Any other value keeps the default and logs a warning. | Same. |
| `model.update.enabled` | Only `true` is on. | Same. |
| `model.offlineInstall.enabled` | Only `true` is on. A missing key means on. | Only `false` is off. |
| `desktop.operator.gatePasswordLogin`, `desktop.animations` | Only `true` is on. A missing key means on. | Not used. |
| `android.exposureAssist` | Not used. | Only `false` is off. |

### 3.4 Binding keys

Prefix `mosip.registration.liveness.face.binding.`

| Key | Default | Valid range | Meaning |
|---|---|---|---|
| `enabled` | true | see 3.3 | Face binding on or off. Off: captures are stored as before, audited `SKIPPED`. |
| `cosineThreshold` | 0.45 | -1.0 to 1.0 | Two faces match at or above this cosine similarity. |
| `identity.enabled` | true | see 3.3 | Identity binding after password-only gates. |
| `identity.threshold` | 0.40 | 0.30 to 1.0 | The live face is the onboarded user at or above this. The value is provisional. |
| `identity.requireTemplate` | false | see 3.3 | `true` refuses a user with no readable onboarded face. `false` accepts them on presence alone, and the audit trail records it. |

### 3.5 Analyzer keys

Prefix `mosip.registration.liveness.face.analyzer.` All are optional. `off` in the last column means the value may
also be `off`, `none` or `nan`, which switches that rule off.

| Key | Default | Valid range | Meaning |
|---|---|---|---|
| `intraOpThreads` | 2 | 1 to 64 | ONNX Runtime threads per model. |
| `accelerator` | `CPU` | `CPU`, `XNNPACK`, `NNAPI` | Execution provider. XNNPACK is a CPU acceleration library and NNAPI is the Android Neural Networks API. An unavailable provider falls back to CPU. |
| `detectorMaxWidth` | 320 | at least 32 | Face detection runs on a copy at most this wide. |
| `detectorScoreThreshold` | 0.7 | above 0, below 1 | Minimum detection score. |
| `detectorNmsIou` | 0.3 | above 0, below 1 | Overlap above which a weaker duplicate detection is dropped. NMS is non-maximum suppression and IoU is intersection over union. |
| `detectorSecondFaceMinAreaRatio` | 0.2 | 0 to 1 | A second face counts only if its area is at least this share of the largest face. |
| `meshPresenceThreshold` | 0.5 | above 0, below 1 | Below this the landmark result is dropped, so there is no pose and no score. |
| `meshRoiScale` | 1.5 | 1 to 3 | Size of the crop given to the landmark model, relative to the face. ROI is region of interest. |
| `meshTracking` | true | `true`, `false` | The crop follows the previous frame's landmarks while the face box overlaps. |
| `pitchBiasDeg` | -9.5 | -30 to 30 | Removed from the measured pitch. When the key is absent, the signed value in the bundle wins. |
| `padMaxYawDeg` | 30 | above 0, up to 90 | Outside this yaw the score is NaN. |
| `padMaxPitchDeg` | 25 | above 0, up to 90 | Outside this pitch the score is NaN. |
| `tooDarkLuma` | 40 | 0 to 255, below `tooBrightLuma` | Mean face brightness below this is `TOO_DARK`. |
| `tooBrightLuma` | 220 | 0 to 255 | Mean face brightness above this is `TOO_BRIGHT`. |
| `qualityMaxClippedFraction` | 0.30 | above 0, up to 1 | A larger share of blown-out face pixels is also `TOO_BRIGHT`. |
| `blurLapVar` | 15 | 0 to 1000000 | Sharpness (Laplacian variance) below this is `BLURRY`. |
| `qualityRelativeBlurRatio` | 0.45 | above 0, up to 1, or off | Also `BLURRY` below this share of the session's rolling median sharpness. |
| `minAreaFrac` | 0.04 | 0 to 1, below `maxAreaFrac` | Face area below this share of the frame is `FACE_TOO_SMALL`. |
| `minFaceWidthPx` | 80 | 0 to 10000 | Face width below this is `FACE_TOO_SMALL`. |
| `maxAreaFrac` | 0.60 | above 0, up to 1 | Face area above this share of the frame is `FACE_TOO_LARGE`. |
| `maxCentreOffset` | 0.25 | above 0, up to 0.5 | Face centre further than this from the frame centre is `FACE_OFF_CENTER`. |
| `moireCapThreshold` | 1.40 | above 0, up to 10, or off | Moire is the interference pattern a camera sees on a screen. A rolling median at or above this caps the score. |
| `moireCapScore` | 0.60 | above 0, below 1 | The capped score. Keep it between the two policy thresholds, so that the session goes to a challenge. The code does not enforce this. |
| `moireWindow` | 10 | `moireMinFrames` to 100 | Frames in the rolling moire median. |
| `moireMinFrames` | 3 | 1 to 100 | Values needed before the cap can apply. |
| `moireVetoThreshold` | off | above 0, up to 10, or off | A number sets the score to 0 at or above that moire value. |
| `padLatchThreshold` | 0.30 | above 0, below 1, or off | Once a 3-frame median falls below this, later scores in the session are capped. |
| `padLatchCapScore` | 0.60 | above 0, below 1, above `padLatchThreshold` | The cap while latched. |
| `volatilityMinScore` | 0.85 | above 0, up to 1, or off | When the second-lowest of recent model scores is below this, the score is capped. |
| `volatilityCapScore` | 0.60 | above 0, below 1 | The volatility cap. |

**Invalid analyzer values differ by client.** The two binding thresholds in section 3.4 count as analyzer values here.

- **Android** logs a warning naming the key, then uses the Android defaults for every analyzer key, not only the bad
  one.
- **Desktop** cannot build the engine, so every gate fails closed with the generic failure screen until the value is
  fixed. Liveness is not skipped. This comes from reading the code. No test covers it.

### 3.6 Model update and install keys

Prefix `mosip.registration.liveness.model.`

| Key | Default | Valid range | Client | Meaning |
|---|---|---|---|---|
| `update.enabled` | false | see 3.3 | Both | Turns the online model update on. It stays off until a deployment hosts bundles. |
| `update.url` | none | an HTTPS URL | Both | The index, `latest.json`. |
| `update.intervalHours` | 24 | integer, at least 1 | Android | Hours between checks. Desktop checks after each successful config sync instead. |
| `offlineInstall.enabled` | true | see 3.3 | Both | Shows the install and rollback actions to supervisors and admins. |

### 3.7 Desktop keys

Prefix `mosip.registration.liveness.face.desktop.`

| Key | Default | Valid range | Meaning |
|---|---|---|---|
| `sbi.frameTimeoutMs` | 15000 | above 0 | No bytes from the device for this long means a stalled stream. |
| `sbi.frameStride` | 1 | at least 1 | Use every Nth device frame. |
| `sbi.maxReconnectAttempts` | 3 | 0 or more | Automatic reconnects after a stream drops. |
| `sbi.reconnectBackoffMs` | 500 | 0 or more | Wait between reconnects. |
| `autoContinueMs` | 1000 | 0 or more | How long the success state stays on screen. |
| `operator.gatePasswordLogin` | true | see 3.3 | Run an operator check after a successful password login. Skipped when a face login step follows. |
| `animations` | true | see 3.3 | The 200 ms status cross-fade. `false` means reduced motion. |
| `metricsAuditEvery` | 20 | integer | Audit a metrics snapshot every N sessions. 0 or less means never. |
| `modelStoreDir` | `<working directory>/.liveness-models` | a folder path | The model store. |

### 3.8 Android keys

| Key | Default | Valid range | Meaning |
|---|---|---|---|
| `mosip.registration.liveness.face.android.<resident, operator or supervisor>.camera` | `front` | `front`, `back` | The camera for that workflow. |
| `mosip.registration.liveness.face.android.exposureAssist` | true | see 3.3 | Raises camera exposure while the face is dim. |

### 3.9 Development keys

These never apply in a normal deployment. The Desktop ones are read only from JVM system properties, never from
synced config, so a config push cannot replace the real engine or the SBI device.

| Key | Where | Values | Meaning |
|---|---|---|---|
| `mosip.registration.liveness.face.desktop.engine` | Desktop, JVM property | `fake`, otherwise the real engine | Selects the scripted engine. |
| `mosip.registration.liveness.face.desktop.fake.scenario` | Desktop, JVM property | a `FakeScenario` name | Scenario for the scripted engine. Default `RANDOM`. |
| `mosip.registration.liveness.face.desktop.source` | Desktop, JVM property | `sbi`, `webcam`, `video:<file.mjpeg>`, `images:<folder>` | Frame source. Default `sbi`. |
| `mosip.registration.liveness.model.update.allowInsecureLocalhost` | Desktop, JVM property | boolean | Allows plain HTTP to 127.0.0.1 or localhost for a test server. |
| `liveness.capture` | JVM property | `sarxos` | Webcam library for `MockFrameSource`. Default OpenCV. |
| `mosip.registration.liveness.fake.scenario` | Config, read by the scripted engine | a `FakeScenario` name | Default `PASS_ACTIVE` when the scripted engine is built without a scenario. |
| `liveness.fake.scenario`, `liveness.fake.timeScale` | JVM property; on Android, a debug override for the time scale | a `FakeScenario` name; a number | Scenario and speed of the scripted engine. `0` is instant. |

### 3.10 Engine constants that are not configuration

These `EngineTuning` values are fixed in code. A caller sees their effect, so they are listed here.

| Constant | Value | Effect |
|---|---|---|
| `startTimeoutMs` | 5000 | No frame this long after start gives `DEVICE_UNAVAILABLE`. |
| `frameStallMs` | 2000 | No frame this long during an attempt gives `DEVICE_DISCONNECTED`. |
| `deviceGraceMs` | 3000 | Frames resuming within this time restart the attempt by themselves. |
| `maxDeviceErrors` | 3 | Device errors that still offer Retry. |
| `invalidFrameLimit` | 3 | Frame violations per attempt. |
| `minFrameIntervalMs` | 5 | Closer frames count as a violation. |
| `engineErrorFrames` | 3 | Analyzer failures in a row that fail an attempt. |
| `activeMaxFps` | 15 | Analysis rate during challenges. |
| `maxPassivePoseDeg` | 25 | Pose limit for the passive window. |
| `multiFaceFailMs` | 1500 | Several faces for this long fail a passive check. |
| `faceLostMs` | 1000 | Face missing for this long fails a challenge. |
| `baselineTimeoutMs` | 4000 | No stable neutral face for this long before a prompt fails the attempt. |

### 3.11 When a change takes effect

| Keys | Desktop | Android |
|---|---|---|
| Policy keys | The next session. The service compares a fingerprint of the liveness keys before each session. | New sessions after a sync. A sync triggers a reload. |
| `analyzer.*`, `binding.cosineThreshold`, `binding.identity.threshold` | **The next start, or the next model install, update or rollback.** A sync alone does not rebuild the models. | The next session after a sync that changes them. |
| `binding.enabled`, `binding.identity.enabled`, `binding.identity.requireTemplate` | The next check. | The next check. |
| `desktop.sbi.*`, `autoContinueMs` | The next session. | Not used. |
| `desktop.modelStoreDir` | The next start. | Not used. |
| `model.update.enabled`, `model.update.url` | The next successful sync. | Rescheduled after each sync. |

---

## 4. Analyzer and model bundle API

Package `io.mosip.registration.liveness.analyzer` and sub-packages, module `liveness-analyzer`.

### 4.1 `OnnxFaceAnalyzerFactory`

**Purpose.** Creates `OnnxFaceAnalyzer`s that share one set of verified ONNX sessions. It implements
`FaceAnalyzerFactory` and `AutoCloseable`. Its methods are thread-safe.

| Constructor | Bundle | Keys | Config |
|---|---|---|---|
| `OnnxFaceAnalyzerFactory()` | Packaged in the jar | Built-in | Defaults |
| `OnnxFaceAnalyzerFactory(BundleSource source, AnalyzerConfig config)` | `source` | Built-in | Given |
| `OnnxFaceAnalyzerFactory(ModelStore store, AnalyzerConfig config)` | Packaged, or newer in `store` | Built-in | Given |
| `OnnxFaceAnalyzerFactory(BundleSource bundled, ModelStore storeOrNull, TrustedKeys keys, AnalyzerConfig config)` | `bundled`, or newer in the store | Given | Given |

| Method | Meaning |
|---|---|
| `FaceAnalyzer create() throws LivenessEngineException` | A new analyzer for one session. The first call loads and verifies the bundle. |
| `void preload()` | Verifies the bundle and builds the per-frame sessions now. Call it at start-up. |
| `void warmUpMatcher()` | Builds the face matcher's session now, on a background thread, so that the first binding does not wait. |
| `boolean matcherReady()` | Whether that session exists. Loads nothing. |
| `FaceMatcher createMatcher()` | A matcher on the same verified sessions. |
| `String modelVersion()` | The version in use. |
| `Accelerator activeAccelerator()` | The execution provider really in use. |
| `AnalyzerConfig effectiveConfig()` | The config after the bundle's signed pitch bias is applied. |
| `void close()` | Closes the shared sessions. Later `create()` calls fail with `ENGINE_ERROR`. |

**Loading rules.** Only a successful load is cached. A failure is tried again and thrown again on every `create()`.
There is no fallback to unverified models. With a store, the packaged bundle must verify, and the store's current
bundle is used only if its version is higher, and then it must verify too. An older or equal store bundle is not read.

**Settings.** `AnalyzerConfig.fromConfig(ConfigProvider)` reads the keys in section 3.5 and the two binding thresholds.
`AnalyzerConfig.builder()` and `AnalyzerConfig.defaults()` build one in code.

### 4.2 `BundleSource`

**Purpose.** Where a bundle's files come from. Nothing read from a source is trusted until `VerifiedBundle` has
checked it.

```java
public interface BundleSource {
    int MAX_FILE_BYTES = 64 * 1024 * 1024;
    byte[] read(String name) throws IOException;   // the whole file; missing or too large throws
    String describe();                             // for messages; no secrets
}
```

| Implementation | Reads from |
|---|---|
| `ClasspathBundleSource` | `liveness/models/` in the jar. |
| `DirectoryBundleSource(File dir)` | A plain folder, staged or installed. |
| `AssetBundleSource(Context)` | `assets/liveness/models/` in the Android package. |

### 4.3 `TrustedKeys`

**Purpose.** The public keys allowed to sign bundles, by key id. The keys are compiled into the class on purpose: a
key file kept next to the models could be swapped together with them.

| Member | Meaning |
|---|---|
| `static TrustedKeys builtIn()` | The compiled keys. Today this is one **development key**, `mosip-liveness-dev-2026-10`. |
| `TrustedKeys(Map<String, PublicKey> keys)` | For a host that ships its own release key. |
| `static PublicKey parsePublicKey(String hexX509)` | An elliptic curve public key from the lower-case hex of its X.509 encoding, the standard public key format. |
| `PublicKey get(String keyId)` | The key, or null. |
| `Set<String> keyIds()` | The ids. |

### 4.4 `VerifiedBundle`

**Purpose.** A bundle whose signature, manifest and files have all been verified. It is the only way the analyzer
obtains model bytes. Sessions are built from the same arrays that were hashed, so nothing is read again between the
check and the use.

| Member | Meaning |
|---|---|
| `static VerifiedBundle load(BundleSource src, TrustedKeys keys) throws LivenessEngineException` | Verifies in the order in section 4.8. Every failure is `MODEL_INTEGRITY_FAILED`. |
| `version()`, `keyId()`, `params()`, `modelBytes()`, `fileName(ModelRole)`, `sha256()` | The verified content. |
| `static int compareVersions(String a, String b)` | Compares by year, month and number. |
| `static boolean isValidVersion(String v)` | Checks the `pad-YYYY.MM.N` form. |

### 4.5 `ModelStore`

**Purpose.** Installed model bundles on disk, with anti-rollback and an atomic switch. Anti-rollback means a bundle
older than the current one is refused. The store's methods are synchronized.

```text
<root>/bundles/<version>/    manifest.json, manifest.sig and one model file per role
<root>/current               text file holding the active version
```

| Method | Meaning |
|---|---|
| `ModelStore(File root)` | Opens a store. Nothing needs to exist yet. |
| `VerifiedBundle loadCurrent(TrustedKeys keys)` | The active bundle, verified. Null if none is installed. |
| `VerifiedBundle loadCurrentIfNewerThan(String version, TrustedKeys keys)` | The same, but only if strictly newer. Otherwise null, and the bundle is not read. |
| `String currentVersion()` | The version in `current`, or null. Does not verify. |
| `List<String> installedVersions()` | Oldest first. |
| `String install(BundleSource staged, BundleSource bundled, TrustedKeys keys)` | The call hosts should use. It verifies both bundles first. If the store is empty or older than the app's own bundle, it installs that bundle first as the rollback base and removes older ones. Then it installs `staged`. |
| `String install(BundleSource staged, TrustedKeys keys)` | The same without a base. `ModelBundleTool install` uses it. |
| `String rollback(TrustedKeys keys)` | Makes the previous bundle current again. This is an explicit operator action that bypasses anti-rollback. The previous bundle is verified first. |

| Exception | Meaning |
|---|---|
| `LivenessEngineException` (`MODEL_INTEGRITY_FAILED`) | A bundle did not verify, or the pointer, the folder name and the manifest version disagree. A tampered disk is treated as an attack. |
| `IllegalArgumentException` ("not newer than ...") | `install`: the version is not strictly newer. |
| `IllegalStateException` | `rollback`: nothing is installed, or there is no older bundle. |
| `IOException` | A disk error. The current bundle stays active. |

An install writes verified bytes to a temporary folder with a disk sync, renames it, verifies it again from disk, then
switches `current`. It keeps only the current and the previous bundle. A switch interrupted by a crash finishes at the
next start if the bundle verifies.

### 4.6 `FaceMatcher`

**Purpose.** Compares faces with the SFace model. `OnnxFaceAnalyzerFactory.createMatcher()` creates it. It is
thread-safe (calls run one at a time), and it keeps nothing between calls. It is not PAD: a photo of the same person
matches. Use it after liveness passed.

| Method | Meaning |
|---|---|
| `MatchResult compare(Frame a, Frame b)` | Face binding. Exactly one face must be in each image. |
| `MatchResult compare(byte[] rgbA, int wA, int hA, byte[] rgbB, int wB, int hB)` | The same on raw RGB888 buffers. |
| `IdentityResult identify(Frame live, List<Frame> templates)` | Identity binding. The best similarity over the readable templates decides. A null entry is an unreadable template. |
| `double threshold()`, `double identityThreshold()` | The thresholds in use. |
| `void close()` | Marks the matcher closed. The factory's sessions stay open. |
| `featureOfSingleFace`, `featureOfPrimaryFace`, `featuresOfAllFaces` | For evaluation tools. A feature is a biometric template, so keep it in memory only. |

`compare`, `identify` and the feature methods throw `LivenessEngineException`: `INVALID_FRAME` for an empty image, and
`ENGINE_ERROR` for a closed matcher or a runtime failure.

| `MatchResult.Outcome` | Meaning |
|---|---|
| `MATCH` | One face in each image, similarity at or above the threshold. |
| `NO_MATCH` | One face in each image, similarity below the threshold. |
| `NO_FACE_A`, `NO_FACE_B` | No face in that image. |
| `MULTIPLE_FACES_A`, `MULTIPLE_FACES_B` | Several faces in that image. |

| `IdentityResult.Outcome` | Meaning |
|---|---|
| `MATCH` | The best similarity is at or above the threshold. |
| `NO_MATCH` | At least one template was readable, and none reached the threshold. |
| `NOT_BOUND_NO_TEMPLATE` | No template was given. |
| `NOT_BOUND_UNREADABLE` | Templates were given, but none decoded to exactly one face. |
| `NO_LIVE_FACE` | The live image does not hold exactly one face. |

`MatchResult` has `outcome`, `similarity` (NaN unless both faces were found), `threshold` and `matched()`.
`IdentityResult` also has `templates`, `unreadable`, `matched()` and `notBound()`.

### 4.7 Bundle contents

| Role (`ModelRole`) | File | Bytes | Job |
|---|---|---|---|
| `DETECTOR` | `yunet_2026may.onnx` | 229,738 | YuNet: finds faces. |
| `LANDMARKS` | `face_mesh_v2.onnx` | 4,921,061 | MediaPipe FaceMesh V2: 478 points, used for landmarks and pose. |
| `PAD_V2` | `minifasnet_v2_s2.7.onnx` | 1,743,495 | MiniFASNet V2: passive PAD. |
| `PAD_V1SE` | `minifasnet_v1se_s4.0.onnx` | 1,742,663 | MiniFASNet V1SE: passive PAD. |
| `MATCHER` | `face_recognition_sface_2021dec_int8.onnx` | 9,896,933 | SFace: face comparison for binding. |

The current bundle is `pad-2026.10.5`. A bundle must hold exactly these five roles.

### 4.8 Manifest format

`models/manifest.json`:

```json
{
  "schema": 1,
  "bundleVersion": "pad-2026.10.5",
  "keyId": "mosip-liveness-dev-2026-10",
  "createdUtc": "2026-10-25T07:28:33Z",
  "params": { "pitchBiasDeg": -9.5 },
  "files": [
    {"role": "DETECTOR", "name": "yunet_2026may.onnx", "size": 229738, "sha256": "ebafce4e...f0f0"}
  ]
}
```

The real file lists all five roles. The hash here is shortened for reading.

| Field | Rule |
|---|---|
| `schema` | Must be 1. |
| `bundleVersion` | `pad-YYYY.MM.N`, compared numerically by year, month and N. |
| `keyId` | Must equal the key id in `manifest.sig`. |
| `createdUtc` | Required string. |
| `params` | Required object of numbers. `pitchBiasDeg` replaces the config default unless the host set that key. |
| `files` | Exactly one entry per role. |
| `files[].role` | A `ModelRole` name. |
| `files[].name` | Letters, digits, `.`, `_`, `-`. No `..`. No repeats. |
| `files[].size` | 1 byte to 64 MiB. |
| `files[].sha256` | 64 lower-case hex digits. |

The parser is strict. It refuses unknown fields, duplicate keys, trailing content, NaN and infinity. The manifest may
be at most 64 KiB.

`models/manifest.sig` is one line, at most 1 KiB:

```text
<keyId>:<lower-case hex of the DER-encoded ECDSA signature>
```

DER is Distinguished Encoding Rules, the standard binary form of the signature. The signature is `SHA256withECDSA`
over the exact bytes of `manifest.json`. Do not reformat a signed manifest.

**Verification order.** Any failure is `MODEL_INTEGRITY_FAILED`.

1. Read `manifest.json` and `manifest.sig`.
2. Find the key id in `TrustedKeys`, and verify the signature over the exact manifest bytes, before any parsing.
3. Parse the manifest and apply the rules above.
4. For each file: read it, check its size, then check its SHA-256.

### 4.9 `ModelBundleTool`

A command-line tool in `liveness-eval`, for development and release machines. It never ships in a client.

```text
keygen   --key-id <id> [--out <p8>] [--force]
manifest --dir <models> --version <pad-YYYY.MM.N> --key-id <id> [--param k=v ...] [--created <ISO-UTC>]
sign     --dir <models> --key <p8> --key-id <id>
verify   --dir <dir> [--trust <keyId>=<public hex>]
install  --store <dir> --from <dir> [--trust <keyId>=<public hex>]
rollback --store <dir> [--trust <keyId>=<public hex>]
pack     --dir <signed bundle> --out <dir> --base-url <url> [--trust <keyId>=<public hex>]
```

Exit codes: 0 success, 1 failed, 2 usage error. `keygen` refuses a path inside a git working tree, so that the private
key cannot enter a repository. `--trust` replaces the built-in keys, for tests and continuous integration only.

---

## 5. Device APIs used

This section describes existing MOSIP interfaces, as the client code calls them. **Nothing here was run against a real
L0/L1 device.** The checks used automated tests, recorded clips and the patched MockMDS.

### 5.1 How the project reaches a device

The project does not implement SBI. On Desktop it reuses the client's own device classes:

```text
DesktopLivenessService.openFaceSource()
  SbiStreamFrameSourceFactory.create("FACE_FULL FACE")
    MosipDeviceSpecificationFactory.getDeviceInfoByModality(...)   existing discovery
    MosipDeviceSpecificationFactory.isDeviceAvailable(device)      existing check
  SbiStreamFrameSource
    BioService.getStream(device, modality)                         existing call, returns an InputStream
    MjpegFrameReader.readNextFrame(stream)                         new: one JPEG
    JpegToRgb888Converter.convert(jpeg)                            new: RGB888 Frame
```

Face capture for storage or matching stays on the client's existing `/capture` path. The project only adds a check of
the result (face binding, section 7.3).

### 5.2 Common rules

- Base URL: `http://127.0.0.1:<port>/`. The host is hard-coded in `MosipDeviceSpecificationHelper`.
- Each operation has its own HTTP method name, as SBI defines: `MOSIPDINFO`, `SBIDISC`, `STREAM`, `RCAPTURE`.
- The requests below are those of the SBI 1.0 provider, `MosipDeviceSpecification_SBI_1_0_ProviderImpl`. The client
  picks the provider by the device's spec version. It also has 0.9.5 and 0.9.2 providers, which differ: for example,
  the 0.9.5 `/stream` request sends `deviceId`, not `serialNo`. This project was not checked against those.

### 5.3 Device discovery and `/info`

The client scans ports from `mosip.registration.mdm.portRangeFrom` to `mosip.registration.mdm.portRangeTo` (default
4501 to 4600). The scan runs only if the range stays within 4500 to 4600.

**Request.** No body.

```text
MOSIPDINFO http://127.0.0.1:4501/info
```

**Response.** A JSON array, one entry per device:

```json
[ { "deviceInfo": "<signed JWT>", "error": { "errorCode": "...", "errorInfo": "..." } } ]
```

The client verifies the `deviceInfo` signature, then decodes its payload:

```json
{
  "deviceInfo": {
    "deviceStatus": "...", "certification": "...", "specVersion": ["1.0"], "purpose": "...",
    "serviceVersion": "...", "firmware": "...", "env": "...", "callbackId": "...",
    "deviceSubId": ["..."], "serialNo": "...", "digitalId": "<signed JWT>"
  },
  "error": { "errorCode": "...", "errorInfo": "..." }
}
```

`digitalId` is itself signed. Its payload holds `serialNo`, `make`, `model`, `type`, `deviceSubType`, `deviceProvider`,
`deviceProviderId` and `dateTime`.

**What this project uses.** `SbiStreamFrameSource.info()` takes the device id (the serial number on the SBI 1.0 path),
make, model, spec version and certification. `L1` and `L0` are matched inside the certification text. Anything else
becomes `NONE`.

### 5.4 `/device` availability check

The client calls this before it opens a stream.

```text
SBIDISC http://127.0.0.1:4501/device

{"type":"<device type>"}
```

The response is a JSON array of objects with `serialNo`, `deviceStatus`, `certification`, `serviceVersion`,
`deviceSubId`, `callbackId`, `digitalId`, `specVersion`, `purpose` and `error`. In v1.2.0.2 the device counts as
available when, in an entry with `specVersion`, `deviceStatus` and `certification` all present, at least one of these
is true: the spec version list contains `1.0`, the status is `Ready`, or the certification equals the device's.

### 5.5 `/stream`

**Request:**

```text
STREAM http://127.0.0.1:4501/stream

{"serialNo":"2345678901","deviceSubId":"0","timeout":"60000","dimensions":null}
```

| Field | Value |
|---|---|
| `serialNo` | The serial number from the device's digital ID. |
| `deviceSubId` | `"0"` for the face modality. |
| `timeout` | `mosip.registration.capture_time_out`, else `"60000"`. |
| `dimensions` | Never set, so it is sent as null. |

**Response.** An MJPEG body, framed as in section 5.6. A device that cannot stream may answer with error JSON instead,
such as `{"errorCode":"605","errorInfo":"..."}`. The client does not check the status line or the content type. It
reads the body.

**First frame.** The client's provider reads one frame as a check before it returns the stream, so the frame reader
first sees the device's second frame.

**The stream is not signed.** See section 9.

### 5.6 The MJPEG framing `MjpegFrameReader` expects

Each frame on the wire:

```text
--BoundaryString\r\n
Content-type: image/jpeg\r\n
Content-Length: 18342\r\n
\r\n
<18342 bytes of JPEG, starting with FF D8>
\r\n\r\n
```

`MjpegFrameReader.readNextFrame(InputStream)` does not parse the multipart structure. It does this:

1. Reads header lines, each ending at a carriage return or line feed, until a line contains `Content-Length:` in any
   case.
2. Parses the number. It must be from 1 to 16 MiB (`MAX_FRAME_BYTES`). There must be such a header within 64 KiB.
3. Skips to the first `0xFF` byte, the start of the JPEG.
4. Reads exactly `Content-Length` bytes, counting that `0xFF`.

| Condition | Result |
|---|---|
| The stream ends before or inside a frame | `EOFException` |
| No header within 64 KiB, or an invalid or oversized length | `IOException` |

Send the header as written here, not at the very first byte of the stream. The reader accepts any case, but the
client's own first-frame check, `MosipDeviceSpecificationHelper.getJPEGByteArray`, looks for exactly `Content-Length:`
and does not match it at position 0.

`MjpegRecorder` writes this format. `MockFrameSource.ofVideoFile` replays it. `SbiStreamProbe`, a development tool,
sends the client's exact `/stream` request and reports what comes back:

```text
SbiStreamProbe <port> <serialNo> [frames=60] [--subId 0] [--host 127.0.0.1] [--save out.mjpeg]
```

### 5.7 `/capture`

**Request:**

```text
RCAPTURE http://127.0.0.1:4501/capture
```

```json
{
  "env": "...", "purpose": null, "specVersion": "1.0", "timeout": "...",
  "captureTime": "2026-10-30T12:00:00.000", "transactionId": null, "domainUri": null,
  "bio": [ {
    "type": "...", "count": "1", "exception": null, "requestedScore": "...",
    "serialNo": "...", "bioSubType": "...", "deviceSubId": "0", "previousHash": null
  } ],
  "customOpts": null
}
```

In v1.2.0.2 the request object's constructor does not store `purpose` or the generated transaction id, so both are
sent as null. That is existing client behaviour, which this project does not change.

**Response:**

```json
{ "biometrics": [ { "specVersion": "...", "data": "<signed JWT>", "hash": "...",
                    "error": { "errorCode": "...", "errorInfo": "..." }, "additionalInfo": { } } ] }
```

The client verifies each `data` signature. The payload holds `digitalId`, `bioType`, `deviceCode`,
`deviceServiceVersion`, `bioSubType`, `purpose`, `env`, `bioValue`, `bioExtract`, `transactionId`, `timestamp`,
`requestedScore` and `qualityScore`. `bioValue`, once decoded from URL-safe Base64, is the ISO/IEC 19794-5 face record.
Face binding decodes that record, which usually holds a JPEG 2000 image.

### 5.8 Stream failures and what the engine sees

| Situation | `SbiStreamFrameSource` reports | Recoverable | What follows |
|---|---|---|---|
| A frame's JPEG does not decode, but the framing is intact | `INVALID_FRAME` | true | The frame is skipped. The stream continues. |
| The stream ends, a read fails, or the framing is invalid, after at least one delivered frame | `DEVICE_DISCONNECTED` | true while reconnects remain | Up to `maxReconnectAttempts`, with `reconnectBackoffMs` between them. Sequence numbers continue. |
| The same, before any frame or with no reconnects left | `DEVICE_DISCONNECTED` | false | The source stops. |
| No byte for `frameTimeoutMs` | `DEVICE_CONNECTION_FAILED` | false | A watchdog closes the stream. |
| `getStream` throws "Bio Device not found" (`REG-MDS-001`) | `DEVICE_UNAVAILABLE` | false | The source stops. |
| `getStream` throws a stream error or timeout (`REG-MDS-002`, `REG-MDS-007`) | `DEVICE_CONNECTION_FAILED` | true | Not retried by the source. |
| `getStream` returns no stream, or throws anything else | `DEVICE_CONNECTION_FAILED` | false | The source stops. |

`DeferredFrameSource` reports a failed device open as `DEVICE_UNAVAILABLE`, recoverable. The engine turns any of these
device errors into `DEVICE_ERROR` with Retry, as section 2.16 describes.

### 5.9 What the MockMDS patch changes

MockMDS is MOSIP's device simulator, in `mosip-mock-services`. `mock-mds-patch/mockmds-face-stream.patch` changes it so
that the real SBI path can run on a laptop. It was made against commit `3d2174d`. Its first change fixes `/stream`. Its
second makes the face stream send real frames.

**1. The `serialNo` lookup.** The client sends `serialNo`, but unpatched MockMDS reads `deviceId` and finds the device
by `deviceId`. The lookup gets null, and MockMDS answers with error JSON, `errorCode` 605, instead of a stream. For the
face device the two values also differ: `serialNo` is `2345678901` and `deviceId` is `3`. The patch:

- adds `serialNo` to `StreamingRequestDetail`;
- uses `serialNo` when `deviceId` is missing;
- matches a device by its `deviceId` or its digital ID serial number.

Requests that send `deviceId` behave as before.

The upstream MockMDS source is not in this repository. The 605 result comes from a live run recorded in
`mock-mds-patch/README.md`. The same run streamed 45 of 45 recorded frames through the patched MockMDS, in order and
byte-identical.

**2. Face stream modes.** Only the full-face stream changes. Fingerprint, iris and `/capture` behave as before.

| Key (system property first, then `application.properties`) | Values | Default |
|---|---|---|
| `mosip.mock.sbi.face.stream.mode` | `static`, `images`, `video`, `webcam` | `static`, the original fixed image |
| `mosip.mock.sbi.face.stream.source` | A file or folder for `images` and `video` | None |
| `mosip.mock.sbi.face.stream.fps` | Frames per second for `images` and `video` | 15 |

A bad setting never stops MockMDS. It logs an error and falls back to `static`.

### 5.10 Android: SBI intents and CameraX

SBI on Android works through intents, and the client's existing `BiometricsDetailsApi` uses them: discovery with the
action `io.sbi.device`, then `<callbackId>.Info` and `<callbackId>.rCapture`. In the SBI version targeted here these
intents offer no frame stream, so liveness frames come from the phone camera through `CameraXFrameSource`, and the SBI
capture stays the stored biometric. Face binding links the two.

`CameraXFrameSource` requests 640 x 480, converts YUV camera images to RGB888, rotates frames upright and does not
mirror them. It paces delivery to at most 15 frames per second. Its device id is `android-camera:<facing>` and its
certification level is `NONE`.

**No SBI capture was made on Android.** The binding code was exercised by unit tests and by a debug page that uses a
camera still in place of the capture.

---

## 6. Android bridge

[Pigeon](https://pub.dev/packages/pigeon) generates type-safe Dart and Java code from one definition file, so that
Dart can call Java in the same app. No network is involved.

| Item | Location |
|---|---|
| Definition | `pigeon/liveness.dart` |
| Generated Dart | `lib/pigeon/liveness_pigeon.dart` (Pigeon 10.1.6) |
| Generated Java | `android/app/src/main/java/io/mosip/registration_client/model/LivenessPigeon.java` |
| Java implementation | `android/app/src/main/java/io/mosip/registration_client/api_services/LivenessApi.java` |
| Message channel names | `dev.flutter.pigeon.registration_client.LivenessApi.<method>` |
| Event channel | `io.mosip.registration_client/liveness/events` |
| Camera preview view type | `io.mosip.registration_client/liveness_preview` |

**One session at a time.** Starting a session cancels the running one and drops its remaining callbacks. The Activity
pausing also cancels it. Session state is touched on the main thread only.

**Build types.** "Diagnostics builds" are debug and profile builds (`BuildConfig.LIVENESS_DIAGNOSTICS` is true). In
release builds, diagnostics methods are refused or ignored, as the table says.

### 6.1 Types

| Type | Fields |
|---|---|
| `LivenessPolicyInfo` | `bool enabled`, `int maxRetries`, `int minChallenges`, `int challengeTimeoutMs` |
| `BindingInfo` | `String outcome`, `double similarity`, `double threshold` |
| `ModelBundleActionResult` | `String outcome`, `String? fromVersion`, `String? toVersion` |
| `IdentityCheckInfo` | `String outcome`, `bool accepted`, `bool retryable`, `double similarity`, `double threshold` |

| Field | Values |
|---|---|
| `BindingInfo.outcome` | `MATCH`, `NO_MATCH`, `NO_FACE_A`, `NO_FACE_B`, `MULTIPLE_FACES_A`, `MULTIPLE_FACES_B`, `ERROR`, `SKIPPED` |
| `ModelBundleActionResult.outcome` | `INSTALLED`, `ROLLED_BACK`, `NOT_NEWER`, `INVALID`, `NOTHING_TO_ROLL_BACK`, `NOT_ALLOWED`, `FAILED`, `CANCELLED` (the file picker was closed) |
| `IdentityCheckInfo.outcome` | `MATCH`, `NO_MATCH`, `NO_LIVE_FACE`, `NO_LIVE_FRAME`, `NOT_BOUND_NO_TEMPLATE`, `NOT_BOUND_UNREADABLE`, `SKIPPED`, `ERROR` |
| `IdentityCheckInfo.similarity` | `NaN` in release builds. Production screens never show a score. |

### 6.2 Methods

`workflow` is `RESIDENT`, `OPERATOR` or `SUPERVISOR`. `camera` is `front` or `back`. "Async" methods complete later.
Dart sees a `Future` for every method.

**Session and policy**

| Method | Returns | Builds | Meaning |
|---|---|---|---|
| `isEnabled(String workflow)` | `bool` | All | Whether liveness is enabled for the workflow. |
| `policy(String workflow)` | `LivenessPolicyInfo` | All | The parts of the policy the UI needs. |
| `startSession(String workflow, String camera, bool useFake)` | `String`, async | All | Cancels any running session, starts one and returns its id. Returns `""` when liveness is disabled. `useFake = true` fails in release builds. Without the camera permission it returns an id and sends a `complete` message with `FAILED` and `CAMERA_UNAVAILABLE`. |
| `retry()` | none | All | Same as `LivenessSession.retry()`. |
| `cancel()` | none | All | Same as `LivenessSession.cancel()`. |
| `cameraFor(String workflow)` | `String` | All | `front` or `back`, from config. |
| `modelVersion()` | `String` | All | The loaded version, or `""` before the models load. |
| `reloadConfig()` | none | All | Re-reads the config for new sessions. The native side also does this after each sync. |
| `metrics()` | `String` | All | The persisted metrics, one `key=value` per line. |

**Binding**

| Method | Returns | Builds | Meaning |
|---|---|---|---|
| `armBinding(String sessionId)` | `String` | All | Before an SBI face capture. `ARMED`, `DISABLED` (binding off: the capture is stored as before, audited `SKIPPED`) or `REJECTED` (unknown or expired session: the hook blocks). |
| `verifyIdentity(String sessionId, String userId, String gate, int attempt)` | `IdentityCheckInfo`, async | All | Identity binding after a passed gate. `gate` is `password_login`, `packet_auth` or `supervisor_approval`. `attempt` is 1, or 2 for the one retry. Never fails: an exception gives `ERROR`, rejected and retryable. A Java `Error`, such as running out of memory, gives `ERROR`, rejected and not retryable. |
| `bindCapturedFace(String sessionId, Uint8List capturedImage)` | `BindingInfo`, async | All | Compares a JPEG, PNG or JPEG 2000 image with the held best frame, then drops the frame. Only the debug page calls it. |

**Model bundles**

| Method | Returns | Builds | Meaning |
|---|---|---|---|
| `activeModelVersion()` | `String` | All | The version new sessions use, or `""`. |
| `modelStoreStatus()` | `String` | All | Installed versions and the current one. |
| `modelBundleActionsAllowed()` | `bool` | All | True when the offline install is enabled and a supervisor or admin is logged in. |
| `installModelBundleFromFile()` | `ModelBundleActionResult`, async | All | Opens the system file picker for a signed zip, then installs it. Checks the role and the key again. |
| `rollbackModelBundleFromSettings()` | `ModelBundleActionResult`, async | All | The previous bundle becomes current. Checks the role and the key again. |
| `installModelBundle()` | `String`, async | Diagnostics, error otherwise | Installs the bundle staged in `files/bundle-staging/`. |
| `rollbackModelBundle()` | `String`, async | Diagnostics, error otherwise | Rolls back and returns the version. |
| `checkModelUpdate(String indexUrl)` | `String`, async | Diagnostics, error otherwise | Checks for an update now. A non-empty URL first becomes the update URL and enabled overrides. |
| `setModelBundleDiagnosticOverride(bool on)` | none | Diagnostics, ignored otherwise | Lets the debug page open the model page without a logged-in supervisor. Each action it allows is audited with `roleCheck=diagnostics-override`. |

**Diagnostics**

| Method | Returns | Builds | Meaning |
|---|---|---|---|
| `setConfigOverride(String key, String value)` | none | Diagnostics, ignored otherwise | Sets a config override. An empty value removes it. |
| `resetMetrics()` | none | Diagnostics, ignored otherwise | Clears the persisted metrics. |
| `runBenchmark(String camera)` | `String`, async | Diagnostics, error otherwise | Holds 60 frames in memory, benchmarks the analyzer, drops the frames and returns a text report. |
| `runMatcherBenchmark(String camera)` | `String`, async | Diagnostics, error otherwise | The same for face binding, on 20 frames. |
| `captureStill(String camera, bool jp2)` | `Uint8List`, async | Diagnostics, error otherwise | One camera still as JPEG or JPEG 2000, for the binding test. Keep it in memory and never display it. |
| `enrollIdentityTestTemplate(String sessionId)` | `String`, async | Diagnostics, `NOT_ALLOWED` otherwise | Makes the held best frame a test template in app-private storage. `ENROLLED`, `NO_LIVE_FRAME` or `FAILED`. |
| `deleteIdentityTestTemplate()` | `bool` | Diagnostics, false otherwise | Deletes the test template. |
| `hasIdentityTestTemplate()` | `bool` | Diagnostics, false otherwise | Whether it exists. |
| `identityTestTemplateTime()` | `int?` | Diagnostics, null otherwise | When it was last written. |
| `setIdentityDiagnosticOverride(bool on)` | none | Diagnostics, ignored otherwise | Makes `verifyIdentity` use the test template. Audited as `templateSource=diagnostics-test`. |

### 6.3 Event channel

Each message is a map. Enums travel as their names, and absent values as null.

```text
event:    {kind:"event", type, guidance, challenge, challengeIndex, challengeCount, timeoutMs, progress,
           error, userMessage, attemptNumber, retriesLeft, recoverable}

complete: {kind:"complete", decision, passPath, failureCode, userMessage, passiveScore, challengesIssued:[...],
           attempts, durationMs, sessionId, modelVersion, bestFrameJpeg}
```

The fields match `LivenessEvent` and `LivenessResult`. `userMessage` is the `ErrorCode.UserMessage` name.
`bestFrameJpeg` is set only on `PASSED`, and never for the fake engine. Keep it in memory, and never log or display
it. On a pass, the native side also holds the best frame in memory for binding, for at most 5 minutes.

### 6.4 Dart service

`lib/platform_spi/liveness_service.dart` defines `LivenessService`. `LivenessServiceImpl` wraps the Pigeon API and
asks for the camera permission. `LivenessMessage` gives typed getters over the maps above, such as `isComplete`,
`type`, `decision` and `sessionId`. `lib/platform_spi/model_bundle_service.dart` defines `ModelBundleService`, with
`actionsAllowed()`, `activeVersion()`, `installFromFile()` and `rollback()`.

A refused camera permission completes on the Dart side with a `complete` message: `FAILED`, `CAMERA_UNAVAILABLE`, and
a session id of the form `camera-denied-<time>`.

### 6.5 `LivenessGate` and how a screen calls it

`lib/ui/liveness/liveness_gate.dart`. This is the one call a screen makes.

```dart
static Future<GateResult> run(BuildContext context, {
  required String workflow,                  // LivenessGate.resident, .operator or .supervisor
  bool useFake = false,                      // debug page and tests only
  LivenessService? service,                  // tests
  Widget Function()? previewBuilder,         // tests
});
```

It returns proceed at once when liveness is disabled. Otherwise it opens `LivenessPage` full screen on the root
navigator, so it also works from a dialog. It never throws: any error gives `blocked`.

| `GateResult` member | Meaning |
|---|---|
| `proceed`, `cancelled`, `blocked` | The status. Exactly one is true. |
| `disabled` | Proceed because liveness is off for this workflow. |
| `sessionId` | The passed session's id. |
| `bestFrameJpeg` | The passed session's best frame. Never log it. |
| `failureMessageKey` | The text key for a blocked result. |

**A screen with a face capture after it** uses `FaceBinding.captureFace`. It runs the gate, arms binding, then runs the
capture. Stop on anything but `captured`.

```dart
final step = await FaceBinding.captureFace(context,
    workflow: LivenessGate.resident,
    capture: () => BiometricsApi().invokeDiscoverSbi(widget.field.id!, widget.parameterTitle));
if (step != FaceCaptureStep.captured || !mounted) return;
```

| `FaceCaptureStep` | Meaning |
|---|---|
| `captured` | Captured and stored as before. |
| `cancelled` | The person backed out. Nothing happened. |
| `blocked` | Liveness failed, or binding could not be armed. Nothing was captured. |
| `rejected` | The SBI face is not the person who passed. `invokeDiscoverSbi` returned `LIVENESS_BINDING_FAILED`. Nothing was stored. |

**A password-only step** uses `IdentityBinding.run`. It runs the gate, then `verifyIdentity`. A rejection a new session
can fix shows Retry, which runs one more session.

```dart
final liveness = await IdentityBinding.run(context,
    workflow: LivenessGate.operator, userId: username, gate: IdentityBinding.packetAuth);
if (!liveness.proceed || !mounted) {
  final String? message = liveness.messageKey;
  if (message != null && mounted) _showInSnackBar(livenessText(appLocalizations, message));
  return;
}
```

`IdentityGateStep` is `proceed`, `cancelled`, `blocked` or `notVerified`. A rejection on the first attempt of the
password login counts as one failed login, so the login lockout sees it.

### 6.6 Android hook list

| Flow | Workflow | File | Call |
|---|---|---|---|
| Operator login, after the password | `OPERATOR` | `lib/ui/login_page.dart` | `IdentityBinding.run`, gate `password_login`. Not proceeding logs the operator out. |
| Packet authentication | `OPERATOR` | `lib/ui/process_ui/generic_process.dart` | `IdentityBinding.run`, gate `packet_auth`. Not proceeding submits nothing. |
| Supervisor approval | `SUPERVISOR` | `lib/ui/approve_packet/widget/authenticate_dialogbox.dart` | `IdentityBinding.run`, gate `supervisor_approval`. Not proceeding approves nothing. |
| Resident face capture, landscape, scan and rescan | `RESIDENT` | `lib/ui/process_ui/widgets/biometric_scan_middle_block.dart` | `FaceBinding.captureFace` |
| Resident face capture, portrait | `RESIDENT` | `lib/ui/process_ui/widgets_mobile/biometric_capture_scan_block_portrait.dart` | `FaceBinding.captureFace` |
| Operator onboarding, face | `OPERATOR` | `lib/ui/onboard/widgets/operator_biometric_capture_scan_block_view.dart` | `FaceBinding.captureFace` |

The native capture handler is `BiometricsDetailsApi`. An armed face capture is stored only on `MATCH`. A comparison
that takes more than 20 s fails closed. These hooks were not run end to end, because there was no MOSIP backend.

---

## 7. Desktop integration entry points

Classes in `registration-client` and `registration-services`. All of them need the Registration Client's Spring
context.

### 7.1 `LivenessGate`

`io.mosip.registration.controller.liveness.LivenessGate`. The one call each Desktop face flow makes. It opens a modal
window, runs one session and reports one outcome.

| Method | Meaning |
|---|---|
| `void run(WorkflowType workflow, Window owner, Consumer<Outcome> done)` | Runs the check. Call it on the JavaFX thread. `done` runs once, on the JavaFX thread. |
| `void runWithIdentity(WorkflowType workflow, Window owner, String userId, Consumer<Outcome> done)` | `run`, then identity binding for `userId`. |
| `boolean gatesPasswordLogin(Collection<String> remainingLoginModes)` | Whether a password login must pass a check: the key is on and no face login step follows. |

| `Outcome` | Meaning |
|---|---|
| `PROCEED` | The session passed and binding is armed; or liveness is disabled, no window opens and the legacy flow continues. `proceed()` is true only for this value. |
| `CANCELLED` | The user pressed Cancel or closed the window. |
| `BLOCKED` | Liveness failed. The window already showed the failure and the recovery text. |
| `IDENTITY_NOT_VERIFIED` | Liveness passed, but the face was not verified as the user's, after the one retry. The caller counts this as a failed login. |

It never throws. Any error ends as `BLOCKED` with the generic text. Auto-logout is suspended while the window is open.

```java
livenessGate.run(WorkflowType.RESIDENT, window, outcome -> {
    if (outcome.proceed()) {
        searchDeviceAndCapture();              // the existing flow, unchanged
    } else {
        disableEnableBiometricPane(false);     // nothing captured, nothing counted
    }
});
```

### 7.2 `DesktopLivenessService`

`io.mosip.registration.liveness.desktop.DesktopLivenessService`, a Spring `@Service`. The client's single entry point
to liveness.

| Method | Meaning |
|---|---|
| `LivenessSession newSession(WorkflowType)` | A new session, or null when disabled. Picks up a changed config first. |
| `LivenessPolicy policy(WorkflowType)` | The policy in force. |
| `boolean isEnabled(WorkflowType)` | An unreadable policy gives true, so an error never switches liveness off. |
| `FrameSource openFaceSource()` | The face device as a deferred frame source. Never null. |
| `FaceBinding binding()`, `IdentityBinding identity()` | The binding objects. |
| `DesktopLivenessConfig config()` | The config adapter. |
| `boolean isScriptedEngine()`, `boolean policyDiagnostic(WorkflowType)` | State queries. |
| `void preloadAsync()` | Verifies the bundle and builds the sessions in the background. `ClientApplication` calls it at start-up. |
| `void onConfigSynced(ConfigSyncedEvent)`, `void checkModelUpdateAsync(String trigger)` | The online model update. Never blocks or fails the sync. |
| `boolean isOfflineModelInstallAllowed()` | The key is on and the user has `REGISTRATION_SUPERVISOR` or `REGISTRATION_ADMIN`. |
| `String activeModelVersion()` | The version new sessions use, or null. |
| `ModelBundleResult installOfflineBundle(File zip)` | Installs a signed zip. Blocking: call it off the UI thread. |
| `ModelBundleResult rollbackModelBundle()` | Rolls back. Blocking. |
| `void sessionCompleted(WorkflowType, LivenessResult)` | The gate calls it after each session, for metrics. |
| `Map<String, String> metricsSnapshot()` | Counts and averages since start-up. |

`ModelBundleResult` has `outcome()`, `fromVersion()` and `toVersion()`. `ModelBundleOutcome` is `INSTALLED`,
`ROLLED_BACK`, `NOT_NEWER`, `INVALID`, `NOTHING_TO_ROLL_BACK`, `NOT_ALLOWED` or `FAILED`.

### 7.3 `FaceBinding`

`io.mosip.registration.liveness.desktop.FaceBinding`. Liveness runs on the stream, but the face that counts comes from
a separate `/capture`. Without a check, a live person could pass and a photo could then be captured. A passed gate
arms the binding with its best frame. The next face capture of that workflow consumes it.

| Method | Meaning |
|---|---|
| `void arm(WorkflowType workflow, LivenessResult passed)` | The gate calls this on a pass. Replaces any earlier arming. Valid for 5 minutes. |
| `void disarm()` | Drops any arming. |
| `Verdict verifyCapture(WorkflowType workflow, List<BiometricsDto> captured)` | Decides whether a capture may be used. Consumes the arming in every case. Call it on a background thread: decoding and matching take about 100 ms. |

`Verdict` has `kind`, `reason` and `accepted()`.

| `Verdict.Kind` | `accepted()` | `reason` values |
|---|---|---|
| `MATCH` | true | `MATCH` |
| `REJECTED` | false | `NO_MATCH`, `NO_FACE_A`, `NO_FACE_B`, `MULTIPLE_FACES_A`, `MULTIPLE_FACES_B`, `NOT_ARMED`, `EXPIRED`, `NO_LIVE_FRAME`, `UNDECODABLE`, `ERROR` |
| `BINDING_DISABLED` | true | `SKIPPED`, `SKIPPED_SCRIPTED_ENGINE` |
| `LIVENESS_DISABLED` | true | `LIVENESS_DISABLED` |

Binding is not PAD: a photo of the same person matches. It never replaces liveness. Neither image is logged or kept.

### 7.4 `IdentityBinding`

`io.mosip.registration.liveness.desktop.IdentityBinding`. A password login has no face capture, so `FaceBinding` has
nothing to compare. This class compares the best frame with the faces the user onboarded on this machine.

| Method | Meaning |
|---|---|
| `Verdict verify(WorkflowType workflow, String userId, String gate, int attempt)` | The check after a passed gate. Consumes the arming. Call it off the JavaFX thread. |
| `Verdict verifyLive(WorkflowType, String userId, String gate, int attempt, String sessionId, Frame live)` | The same on a frame the caller holds, for the development demo. |
| `static TemplateSource fromUserBiometrics(UserDetailDAO users)` | Reads the user's active Face rows of `reg.user_biometric`. |

| Outcome | Result |
|---|---|
| `MATCH` | Accepted. |
| `NO_MATCH`, `NO_LIVE_FACE`, `NO_LIVE_FRAME`, `NOT_ARMED`, `EXPIRED`, `ERROR` | Rejected. `retryable()` is true: one new session is offered. |
| `NOT_BOUND_NO_TEMPLATE`, `NOT_BOUND_UNREADABLE` | Accepted on presence alone and audited, unless `requireTemplate` is on. Then rejected, not retryable. |
| `SKIPPED` | Accepted: identity binding is off. |

### 7.5 Desktop hook list

| Flow | Workflow | Where the gate runs | What checks the result |
|---|---|---|---|
| Resident face capture | `RESIDENT` | `GenericBiometricsController.scan(ActionEvent)` | `verifyCapture` in the capture task. A rejected capture is not stored or counted. |
| Operator onboarding, face | `OPERATOR` | `BiometricsController.scan(ActionEvent)` | `verifyCapture` in the capture task. |
| Operator login with face | `OPERATOR` | `LoginController.captureFace(ActionEvent)` | `SessionContext.validateFace`, before the template match. |
| Operator login with password | `OPERATOR` | `LoginController.loadNextScreen` | `runWithIdentity`. Not proceeding destroys the session. `IDENTITY_NOT_VERIFIED` is one failed login. |
| Packet authentication with face | `OPERATOR` | `AuthenticationController.validateFace` | `BaseController.captureAndValidateFace`, before the template match. |
| Reviewer step of packet authentication | `SUPERVISOR` | `AuthenticationController.validateFace` | The same. |
| End-of-day approval with face | `SUPERVISOR` | `EODAuthenticationController.validateFace` | The same. |

Not gated, on purpose: the exception photo, which is the recovery path, and fingerprint, iris, OTP (one-time password)
and password authentication of a packet.

Other integration points:

| File | Change |
|---|---|
| `ClientApplication` | Calls `preloadAsync()` at start-up. |
| `GlobalParamServiceImpl.synchConfigData` | Publishes `ConfigSyncedEvent` after a successful sync. |
| `PacketHandlerController` | Two home page tiles for the offline install and rollback, shown only to supervisors and admins. |
| `AuditEvent`, `Components`, `AuditReferenceIdTypes`, `AuditManagerService` | The `REG-LIV-*` events, component `REG-MOD-153`, reference type `LIVENESS_SESSION_ID`, and an `audit(...)` overload that takes a description. |

**Not run end to end.** The Desktop client was compiled and its liveness tests were run, but it was not launched
against a backend. The login hook, the retry dialog and the home page tiles were checked by compilation and code
review. The services behind them have tests.

---

## 8. Online model update

**This is a format the client expects, not a service this project hosts.** No public server exists. The feature is
off by default. A deployment that wants it puts two files on any static HTTPS host: an index, `latest.json`, and a
bundle zip. The client only ever reads them.

| Item | Value |
|---|---|
| Enable | `mosip.registration.liveness.model.update.enabled=true` |
| Index URL | `mosip.registration.liveness.model.update.url` |
| Called by | `ModelUpdater.check(URL indexUrl, ModelStore store, BundleSource bundled, TrustedKeys keys)` |
| When, Desktop | After each successful config sync, on a background thread. It never delays or fails the sync. Without a sync, nothing is checked. |
| When, Android | A periodic WorkManager job named `liveness-model-update`, every `update.intervalHours` hours, only while a network is connected. |
| When disabled, or no URL | No network call. |

### 8.1 `GET` the index

**Request.** The client sends a plain `GET`, with no body:

```http
GET /liveness/latest.json HTTP/1.1
Host: models.example.org
Accept-Encoding: identity
```

`Accept-Encoding: identity` is the only header the code sets itself, because sizes and hashes apply to the bytes as
served. The platform's HTTP library adds its usual headers, such as `User-Agent`.

**What is sent over the network.** Only that request. The code adds no user id, machine id, session id, API key, cookie
or biometric data. As with any web request, the server sees the client's network address.

**Response.** Status 200, with this JSON. The example is real output of `ModelBundleTool pack`, for the current bundle.
A client already on `pad-2026.10.5` would answer `UP_TO_DATE` to it. A real update carries a higher version.

```json
{
  "schema": 1,
  "bundleVersion": "pad-2026.10.5",
  "zipUrl": "https://models.example.org/liveness/pad-2026.10.5.zip",
  "zipSha256": "e493137b0da1fbe24c7ba068b5721b4948f458349e6be75bf3ac98463e97fca7",
  "zipSize": 13427198
}
```

| Field | Type | Rule |
|---|---|---|
| `schema` | number | Must be 1. |
| `bundleVersion` | string | `pad-YYYY.MM.N`. It must equal the version in the zip's manifest. |
| `zipUrl` | string | 1 to 2048 characters. Absolute, or relative to the URL the index finally came from. |
| `zipSha256` | string | 64 lower-case hex digits. The hash of the zip. |
| `zipSize` | number | 1 byte to 64 MiB. The exact size of the zip. |

The index may be at most 16 KiB. It must hold exactly these five fields: an extra field is refused. The content type
is not checked. The JSON parser is the strict one from section 4.8.

**Transport.** HTTPS only. Plain HTTP works only to 127.0.0.1 or localhost, and only with `allowInsecureLocalhost`.
On Android only debug and profile builds set it. On Desktop only a JVM property sets it. The client follows redirects
itself: status 301, 302, 303, 307 or 308, at most 3 hops, each under the same rule. Any other status than 200 fails.
The connect timeout is 15 s and the read timeout is 30 s.

### 8.2 `GET` the zip

The client downloads the zip only if `bundleVersion` is newer than both the store's current bundle and the app's own.
If it is not, the result is `UP_TO_DATE` and nothing more is fetched.

The zip is a flat archive: `manifest.json` first, then exactly `manifest.sig` and the files the manifest names, each
once, and each at most its declared size. Directories, paths, extra entries and duplicates are refused. If the server
sends `Content-Length`, it must equal `zipSize`.

### 8.3 How it is verified

1. The size must equal `zipSize`.
2. The SHA-256 of the zip must equal `zipSha256`.
3. The zip is unpacked by the rules above into a fresh `<store root>/update.tmp-<random>` folder.
4. `ModelStore.install(staged, bundled, keys)` verifies the ECDSA signature, every file size and every SHA-256, then
   checks that the version is strictly newer, then switches atomically. The app's own bundle becomes the rollback
   base.
5. The temporary folder is deleted, whatever happened.

**The index itself is not signed.** It only says what to fetch. The bundle signature is what makes a download usable,
and HTTPS is a second defence. A host or network that serves other bytes gets `FAILED`, never an installed bundle.

### 8.4 Result and failure

`check` never throws for network, server or bundle problems. It returns an `UpdateResult` with `status()`, `version()`,
`reason()` and `detail()`.

| `status()` | Meaning |
|---|---|
| `INSTALLED` | A newer bundle is installed. New sessions use it, and running sessions keep their models. |
| `UP_TO_DATE` | Nothing to do. `version()` is the newest local version. |
| `FAILED` | See `reason()`. The store is unchanged and the current models stay in use. |

| `reason()` | Meaning |
|---|---|
| `INSECURE_TRANSPORT` | Not HTTPS, and not an allowed HTTP to localhost. Also applies to a redirect. |
| `NETWORK` | Connection refused, a timeout or another read or write error. |
| `HTTP_STATUS` | An answer other than 200, a redirect with no target, or too many redirects. |
| `INDEX_INVALID` | The index is too large, malformed, has unknown fields or has invalid values. |
| `ZIP_SIZE` | `zipSize` is over 64 MiB, or the server sent a different number of bytes. |
| `ZIP_HASH_MISMATCH` | The SHA-256 differs from the index. |
| `ZIP_INVALID` | Not a readable zip, no manifest first, a different version than the index, or missing entries. |
| `ZIP_ENTRY_REFUSED` | A directory, a path, an unnamed entry, a duplicate, or an entry over its size. |
| `BUNDLE_INVALID` | The signature, manifest or file hashes failed (`MODEL_INTEGRITY_FAILED`). |
| `STORAGE` | A local problem: the store's pointer, the app's own manifest, or the disk. |

**After a failure.** The outcome is audited as `REG-LIV-012` on Desktop and `REG-EVT-019` on Android, with the reason
code and no URL. Desktop tries again at the next successful sync. Android tries again at the next period, and the job
always ends as a success. If WorkManager started the process before the engine existed, Android keeps up to 20
outcomes and audits them at the next start, with `trigger=scheduled-offline`. Desktop can also audit `reason=INVALID_URL`
or `reason=ERROR`, for a bad configured URL or an unexpected failure.

**Rollback and the update index.** A rollback does not block a version. If the index still offers the newer bundle, the
next check installs it again. Fix the index, or turn updates off, before you roll back a bad bundle. A run with a
throwaway key and a local server confirmed this: check, `INSTALLED`; rollback; check, `INSTALLED` again for the same
version.

**What was run.** `ModelUpdaterTest` and `DesktopModelUpdateTest` use local HTTP servers. On a phone, a debug build
installed a local test bundle from a laptop server, refused a zip with one flipped byte, and rolled back. Nothing was
run against a production host.

### 8.5 Publishing a bundle

```text
ModelBundleTool pack --dir <signed bundle> --out <dir> --base-url https://models.example.org/liveness
```

`pack` verifies the bundle, then writes `<version>.zip` and `latest.json`. Upload the zip first and the index second,
so that the index never points at a missing zip. Clients install only bundles signed by a key in their `TrustedKeys`.

---

## 9. Authentication and security

| Interface | Who can call it | Authentication | Integrity | Notes |
|---|---|---|---|---|
| Java engine API | Any code in the same process | None. These are in-process calls. | None needed. | The security boundary is the app itself. |
| Android bridge (Pigeon, event channel) | Dart code in the same app | None. These are in-process messages. | None needed. | Diagnostics methods are refused or ignored in release builds. Model actions check the role natively. |
| SBI `/info` | Any local process | None on the request. Local HTTP, no encryption. | The device info and digital ID are signed. The client checks them with its signature service and a trust check against the configured domains. | Existing client behaviour. |
| SBI `/device` | Any local process | None. | **Not signed.** | It only reports availability. |
| SBI `/stream` | Any local process | None. | **Not signed.** Frames carry no signature, so a local process posing as the MDS could feed frames. | Face binding compares the best frame with the signed `/capture` result. The engine checks that sequence and timing are plausible. A photo of the same person would still match binding. |
| SBI `/capture` | Any local process | None on the request. | Each biometric is a signed JWT, verified by the client with a trust check. | The result the packet stores. |
| Android SBI intents | The client and the SBI app | Existing client behaviour. | Not changed by this project. | **Not exercised:** no SBI capture was made on Android. |
| Model bundle files | Whoever can write the store folder or ship the app | ECDSA P-256 signature over the exact manifest bytes, against a key compiled into the app. | SHA-256 and size of every file. | Any failure is `MODEL_INTEGRITY_FAILED`, with no fallback. **The built-in key is a development key.** |
| Update index and zip | Any client that knows the URL | None. The client sends no credentials. | The index is not signed. The zip has a size and SHA-256 from the index, and the bundle inside has the ECDSA signature. | HTTPS only. A hostile host can withhold updates. It cannot install models. |
| Offline install and rollback | A logged-in supervisor or admin | The client's existing login. The role is checked in the UI and again before acting. | The same signature check as the online update. | Audited with the user id. Can be switched off. |
| Config keys | The MOSIP config sync | Existing client sync. | Existing client sync. | Desktop development switches are JVM properties only, so a config push cannot select the scripted engine or a mock device. |

**The signing key.** The built-in key, `mosip-liveness-dev-2026-10`, is a development key. Its private half is held
outside the repository on one developer's machine. Before any release, create a production key in a hardware security
module or on an offline signing machine, add its public key to `TrustedKeys`, re-sign every bundle and release the
analyzer. Public keys are compiled in, so that a swapped key file cannot pass.

**Other protections:**

- **Fail closed.** Engine failure, screen failure, a missing or expired binding, an undecodable capture and a matcher
  error all refuse the face. The one deliberate exception is a user with no readable onboarded face, who passes
  identity binding on presence alone unless `requireTemplate` is on. That case is audited.
- **No detail on screen.** PAD failures use the generic text. No score, threshold, model name or challenge timing is
  shown.
- **Unpredictable challenges.** The engine picks them with a secure random source, with a random delay of up to 400 ms
  before each prompt.
- **No telemetry from ONNX Runtime.** The analyzer turns it off, and Android removes its start-up provider.
- **Images stay in memory.** See section 10.5.

---

## 10. Audit events and metrics

### 10.1 How records are written

| Item | Desktop | Android |
|---|---|---|
| Adapter | `DesktopAuditSink` | `AndroidAuditSink` |
| Destination | `AuditManagerService`, table `AUDIT.APP_AUDIT_LOG` | `AuditManagerService`, the client's audit table |
| Event ids | `REG-LIV-001` to `REG-LIV-013` | `REG-EVT-009` to `REG-EVT-020` |
| Module | `REG-MOD-153`, "Face liveness" | By workflow: `REGISTRATION`, `LOGIN`, `REG_PACKET_LIST`. `HOME` with no workflow. |
| Session link | The reference id is the session id. The reference type is `LIVENESS_SESSION_ID`. | `sessionId=` at the start of the details. |
| Description | `workflow=RESIDENT; session=<id>; errorCode=...; ...`. A record over 2000 characters is split into numbered parts. | `<event description>: sessionId=<id>;key=value;...`, cut to 2048 characters. |
| Thread | One background thread | One background thread |
| On failure | Logged. Never reaches a capture or a login. | Logged. Never reaches the engine. |

To read one Desktop session, filter `AUDIT.APP_AUDIT_LOG` on `MODULE_ID = 'REG-MOD-153'` and `REF_ID = '<session id>'`.
The column names come from the client's `initial.sql`. This query was not run for this document.

### 10.2 Field groups

| Group | Fields |
|---|---|
| Base | `engine` (`core` or `fake`), `attempt`, `modelVersion`, `deviceId`, `deviceCertification` |
| Attempt | Base, plus `passiveOutcome` (`PASS`, `ATTACK`, `NEED_ACTIVE`, `BELOW_THRESHOLD`, `TIMEOUT`), `passiveScore`, `passiveFrames`, `passiveMs`, `challenges`, `attemptMs`, and `diag.*` in diagnostic mode |
| Final | Attempt, plus `durationMs`, `framesAnalyzed`, `framesDropped`, `avgProcessingMs` |

`challenges` is a comma list of `TYPE:OUTCOME:ms`. The outcomes are `DETECTED`, `WRONG_ACTION`, `TIMEOUT`,
`BASELINE_UNSTABLE`, `CONTINUITY_BREAK`, `FACE_LOST`, `MULTIPLE_FACES`, `PAD_FAILURE`, `INVALID_FRAME` and `ABORTED`.

`diag.*` holds `diag.scoredFrames`, `diag.scoreMin`, `diag.scoreMedian`, `diag.scoreMax`, `diag.noFaceFrames`,
`diag.multiFaceFrames` and `diag.issue.<QualityIssue>`.

### 10.3 Events

| Desktop | Android | Name | Type | When | Fields |
|---|---|---|---|---|---|
| `REG-LIV-001` | `REG-EVT-009` | `LIVENESS_STARTED` | User | Each attempt starts | Base |
| `REG-LIV-002` | `REG-EVT-010` | `LIVENESS_PASSED` | User | The session passed | Final, plus `passPath`, `passiveScore`, `bestFrame.iso19794_5` (`COMPLIANT`, `NOT_COMPLIANT`, `UNKNOWN`), `bestFrame.fallback`, `bestFrame.isoIssues`, `bestFrame.yawDeg`, `bestFrame.pitchDeg`, `bestFrame.rollDeg`, `bestFrame.eyeDistancePx` |
| `REG-LIV-003` | `REG-EVT-011` | `LIVENESS_ATTEMPT_FAILED` | User | An attempt failed and Retry is possible | Attempt, plus `errorCode`, `retriesLeft`, `reason` |
| `REG-LIV-004` | `REG-EVT-012` | `LIVENESS_FAILED_FINAL` | User | The session failed, was cancelled, or was refused by a cooldown | Final, plus `errorCode`, `lastCause`, `cooldownSec`. A cancel: `errorCode=CANCELLED`, `cancelled=true`. A refusal: Base, plus `errorCode`, `reason=cooldown`, `cooldownRemainingMs` |
| `REG-LIV-005` | `REG-EVT-013` | `LIVENESS_PAD_ATTACK_SUSPECTED` | System | A presentation attack is suspected | Attempt, plus `errorCode=PAD_FAILURE`, `passiveScore`, `reason`, and up to 8 `pad.<model>` medians on a passive verdict |
| `REG-LIV-006` | `REG-EVT-014` | `LIVENESS_DEVICE_ERROR` | System | A device error | Attempt, plus `errorCode`, `recoverable`, `deviceDetail` (at most 120 characters) |
| `REG-LIV-007` | `REG-EVT-015` | `LIVENESS_MODEL_LOADED` | System | Models loaded, at start-up and per session | Base. Start-up adds `modelVersion`, `accelerator`, `loadMs`, and on Desktop `phase=preload` |
| `REG-LIV-008` | `REG-EVT-016` | `LIVENESS_MODEL_INTEGRITY_FAILED` | System | The bundle did not verify, or the analyzer could not be built | Base, plus `errorCode`, `reason`. Start-up: `errorCode`, `phase=preload`, and on Desktop `reason` |
| `REG-LIV-009` | `REG-EVT-017` | `LIVENESS_CONFIG_LOADED` | System | A workflow's policy was resolved after a start or a reload | `engine`, `policy` (one line of all fields), `configError` |
| `REG-LIV-010` | `REG-EVT-018` | `LIVENESS_FACE_BINDING` | System | A capture was compared with the live face, or binding is off | `outcome`, `similarity` (2 decimals), `threshold`. Desktop adds `accepted` |
| `REG-LIV-011` | None | `LIVENESS_METRICS` | System | Desktop: every `metricsAuditEvery` sessions | The metrics in section 10.4 |
| `REG-LIV-012` | `REG-EVT-019` | `LIVENESS_MODEL_UPDATE` | System | An online check finished, or an offline install or rollback ran | Online: `outcome`, `version`, `reason`, `durationMs`, `trigger`. Offline or rollback: `outcome`, `errorCode`, `reason`, `fromVersion`, `toVersion`, `durationMs`, `trigger` (`offline` or `rollback`), `userId` |
| `REG-LIV-013` | `REG-EVT-020` | `LIVENESS_IDENTITY_BINDING` | System | The live face was compared with the onboarded face, or the check is off | `outcome`, `similarity`, `threshold`, `gate`, `attempt`, `userId`, `templates`, `unreadable`, `requireTemplate` (when not bound), `accepted`. Desktop adds `templateAgeDays`. Android adds `templateSource=diagnostics-test` for the test face |

`trigger` for an online check is `System` or `User` on Desktop, and `scheduled`, `manual` or `scheduled-offline` on
Android. Android's offline and rollback records can add `roleCheck=diagnostics-override` in diagnostics builds.

### 10.4 Metrics

`LivenessMetrics` counts sessions in memory for one service. Call `snapshot()`, then `toMap()` for a flat form. It
holds counts and averages only.

| Metric | Meaning |
|---|---|
| `sessions` | Sessions started. |
| `passed`, `passedPassive`, `passedActive` | Passes, in total, without challenges and after challenges. |
| `failedFinal` | Sessions that ended `FAILED`. |
| `cancelled` | Sessions cancelled. |
| `cooldownRefusals` | Sessions refused by a cooldown. |
| `retries` | Attempts started by Retry. |
| `deviceErrors` | Device errors. |
| `avgProcessingMs` | Average analysis time per frame. |
| `avgChallengeCompletionMs` | Average time from prompt to detection, for challenges that succeeded. |
| `retryRate` | `retries / sessions`. |
| `failureRate` | `failedFinal / sessions`. |
| `passiveShare` | `passedPassive / passed`. |
| `finalFailure.<code>` | Final failures by error code. |

`Snapshot` also has public fields that `toMap()` leaves out: `attemptFailures`, `framesAnalyzed`, `processingMsTotal`,
`challengesDetected`, `challengeMsTotal`, `challengesFailed` and `attemptFailuresByCode`.

| Item | Desktop | Android |
|---|---|---|
| Kept | In memory since start-up. | `LivenessMetricsStore`: the same counts in SharedPreferences (`liveness_metrics`), across restarts. Fake engine sessions are not counted. |
| Read through | `DesktopLivenessService.metricsSnapshot()` | Pigeon `metrics()`. Nearly the same list, with `attemptFailure.<code>` added, and without `passed` and `cooldownRefusals`. |
| Reported | Audited as `REG-LIV-011`. Logged in diagnostic mode. | Local only. Nothing uploads this store. |

### 10.5 What is never logged

Across the audit trail, the metrics and the logs, the clients' liveness code never records:

- images, pixels, camera frames, the best frame or the captured face, in any encoding;
- landmarks, coordinates or face boxes;
- face features or face templates.

Update and install audit records also hold no index URL, zip URL or file path. They hold codes and versions.

**How the code enforces it.** The audit recorder has no method that accepts a frame, analysis, landmark or pixel buffer,
and a test checks this. `DesktopAuditSink` also limits keys to short identifiers, caps values at 400 characters and
drops any value that looks like encoded binary data. Free-text fields are cut to 120 characters.

**Images in the clients.** Frames, the best frame and the captured face stay in memory. The best frame is dropped when
consumed or replaced, or after 5 minutes. On Android it is also dropped when the app pauses, unless an armed SBI capture
is in flight. Only the client's existing capture flow stores an accepted face, as it did before.

**Two exceptions, both outside production use:**

- The Android debug test face, in debug and profile builds only, is stored in app-private files until deleted.
- Development tools that do not ship in a client, such as `MjpegRecorder`, write frames to a file when someone runs them
  for that purpose.

**What the audit trail does hold.** Be accurate about this in any privacy review:

- the session id, workflow, device id and certification level;
- scores of a session: the passive median, per-model PAD medians and similarity values;
- the user id, in identity binding records and in offline install and rollback records;
- free text from a device (`deviceDetail`), cut to 120 characters.

Metrics hold no session id, no individual's score, no image and no device identity.

---

## 11. Example use cases

Each example was compiled against the real classes with `javac` from JDK 11. Every example needs `liveness-api` on the
classpath. Examples 4 and 5 also need `liveness-core`. Examples 1 and 6 also need `liveness-core`, `liveness-analyzer`
and ONNX Runtime, and example 1 needs `registration-services` for `MockFrameSource`.

### Example 1: run a resident check with a mock device

This needs no hardware and no MDS. Point it at a recorded clip, for example one of the files in
`registration-services/src/test/resources/liveness/videos/`. The file names state the result the end-to-end test
expects for each clip.

```java
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessService;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.device.mock.MockFrameSource;

public final class ResidentCheck {

    public static void main(String[] args) throws Exception {
        // A recorded MJPEG clip stands in for the face device: 15 frames per second, looping.
        FrameSource device = MockFrameSource.ofVideoFile(Paths.get(args[0]), 15.0, true);

        // The signed models in the jar, the built-in key and the default analyzer settings.
        try (OnnxFaceAnalyzerFactory analyzers = new OnnxFaceAnalyzerFactory()) {
            LivenessService liveness = DefaultLivenessService.builder()
                    .config(key -> null)          // no host config: the bundled defaults apply
                    .analyzerFactory(analyzers)
                    .build();

            LivenessSession session = liveness.newSession(WorkflowType.RESIDENT);
            if (session == null) {
                System.out.println("Liveness is disabled for RESIDENT: keep the legacy flow.");
                return;
            }

            CountDownLatch finished = new CountDownLatch(1);
            session.start(device, new LivenessListener() {
                @Override
                public void onEvent(LivenessEvent e) {
                    System.out.println(e.type + (e.challenge == null ? "" : " " + e.challenge));
                    // This example has no Retry button, so a failed attempt ends the session.
                    if (e.type == LivenessEvent.Type.ATTEMPT_FAILED
                            || (e.type == LivenessEvent.Type.DEVICE_ERROR && e.recoverable)) {
                        session.cancel();
                    }
                }

                @Override
                public void onComplete(LivenessResult r) {
                    System.out.println(r.decision + ", failure " + r.failureCode + ", session " + r.sessionId);
                    finished.countDown();
                }
            });

            if (!finished.await(2, TimeUnit.MINUTES)) {
                session.cancel();
            }
        }
    }
}
```

Run on `nasa-print-normal__expect-FAILED-PAD_FAILURE.mjpeg`, it printed `STARTED`, `CHECKING_PASSIVE`, `ATTEMPT_FAILED`
and then `CANCELLED`.

### Example 2: run an operator check and handle retry

Your screen implements `Screen`. Its Retry and Cancel buttons call `retryPressed()` and `cancelPressed()`.

```java
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessListener;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessService;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;

public final class OperatorCheck implements LivenessListener {

    /** Marshal these calls to your UI thread (Platform.runLater on JavaFX). */
    public interface Screen {
        void showProgress(LivenessEvent event);
        void showRetry(ErrorCode.UserMessage message, int retriesLeft);
        void showFinalFailure(ErrorCode.UserMessage message);
        void accepted(LivenessResult result);
        void closed();
    }

    private final Screen screen;
    private LivenessSession session;

    public OperatorCheck(Screen screen) {
        this.screen = screen;
    }

    /** Returns false when liveness is disabled for OPERATOR: the caller keeps its legacy flow. */
    public boolean begin(LivenessService liveness, FrameSource device) {
        session = liveness.newSession(WorkflowType.OPERATOR);
        if (session == null) {
            return false;
        }
        session.start(device, this);
        return true;
    }

    public void retryPressed() {
        session.retry();
    }

    public void cancelPressed() {
        session.cancel();
    }

    @Override
    public void onEvent(LivenessEvent e) {
        switch (e.type) {
            case ATTEMPT_FAILED:
                screen.showRetry(e.error.userMessage(), e.retriesLeft); // Retry here uses one attempt
                break;
            case DEVICE_ERROR:
                if (e.recoverable) {
                    screen.showRetry(e.error.userMessage(), e.retriesLeft); // Retry here uses no attempt
                }
                break;
            case FAILED_FINAL:
                screen.showFinalFailure(e.error.userMessage());
                break;
            default:
                screen.showProgress(e);
                break;
        }
    }

    @Override
    public void onComplete(LivenessResult r) {
        // The only place that accepts or rejects the face.
        if (r.passed()) {
            screen.accepted(r);
        } else if (r.decision == LivenessResult.Decision.CANCELLED) {
            screen.closed();
        }
        // FAILED: FAILED_FINAL already put the failure on the screen. Its Close button ends the flow.
    }
}
```

The operator policy defaults to one challenge and `COOLDOWN`. After `MAX_RETRIES_EXCEEDED`, the next sessions fail at
once for 60 s, and they arrive through this same code as `FAILED_FINAL`.

### Example 3: add a new device vendor by implementing `FrameSource`

A compliant SBI device needs no code: the client's discovery finds it. Use this only for a camera that is not reachable
through SBI. Replace the two vendor interfaces with the real SDK calls. If the device gives JPEG images,
`JpegToRgb888Converter.convert(jpeg)` turns each into RGB888.

```java
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

public final class VendorFrameSource implements FrameSource {

    /** What the vendor SDK offers. Replace these two interfaces with the real calls. */
    public interface VendorSdk {
        VendorCamera open(String serial) throws IOException;
    }

    public interface VendorCamera extends Closeable {
        byte[] readRgb() throws IOException;   // blocks; packed RGB888, width * height * 3 bytes
        int width();
        int height();
    }

    private final VendorSdk sdk;
    private final String serial;
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    public VendorFrameSource(VendorSdk sdk, String serial) {
        this.sdk = sdk;
        this.serial = serial;
    }

    @Override
    public DeviceInfo info() {
        return new DeviceInfo(serial, "Vendor", "Model 1", "none", "NONE", 0, 0, 0);
    }

    @Override
    public void start(Listener listener) {
        Thread thread = new Thread(() -> run(listener), "vendor-camera");
        thread.setDaemon(true);
        if (worker.compareAndSet(null, thread)) {
            thread.start();                    // returns at once; errors go to the listener
        }
    }

    @Override
    public void stop() {
        Thread thread = worker.getAndSet(null);
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return worker.get() != null;
    }

    private void run(Listener listener) {
        Thread me = Thread.currentThread();
        long sequence = 0;                     // each start() begins again at 0
        try (VendorCamera camera = sdk.open(serial)) {
            while (worker.get() == me) {
                byte[] rgb = camera.readRgb();
                if (worker.get() != me) {
                    return;                    // stopped during the read: never deliver
                }
                long nowMs = System.nanoTime() / 1_000_000L; // monotonic, not wall-clock time
                try {
                    listener.onFrame(new Frame(rgb, camera.width(), camera.height(), nowMs, sequence, serial));
                } catch (IllegalArgumentException badBuffer) {
                    listener.onError(ErrorCode.INVALID_FRAME, "wrong buffer size", true); // skip this frame
                }
                sequence++;
            }
        } catch (IOException e) {
            if (worker.compareAndSet(me, null)) {
                listener.onError(ErrorCode.DEVICE_DISCONNECTED, e.getClass().getSimpleName(), false);
            }
        }
    }
}
```

Pass it to `session.start(...)` as in example 1. In the Desktop client, `DesktopLivenessService.openFaceSource()` is
where the source is chosen, so that is the method to change.

### Example 4: plug in a different PAD engine by implementing `FaceAnalyzer`

```java
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.ConfigProvider;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.api.LivenessService;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;

public final class VendorPadAnalyzer implements FaceAnalyzer {

    /** What the vendor engine offers. Replace this interface with the real calls. */
    public interface VendorPad {
        float[][] findFaces(byte[] rgb, int width, int height) throws Exception; // each {x, y, w, h}
        float liveProbability(byte[] rgb, int width, int height, float[] box) throws Exception; // 0..1
        String version();
    }

    private final VendorPad pad;

    public VendorPadAnalyzer(VendorPad pad) {
        this.pad = pad;
    }

    @Override
    public FrameAnalysis analyze(Frame frame) throws LivenessEngineException {
        long start = System.nanoTime();
        try {
            byte[] rgb = frame.rgb();
            float[][] faces = pad.findFaces(rgb, frame.width(), frame.height());
            float[] box = faces.length == 0 ? null : faces[0];
            float score = faces.length == 1
                    ? pad.liveProbability(rgb, frame.width(), frame.height(), box) : Float.NaN;
            Map<String, Float> audit = Float.isNaN(score)
                    ? Collections.<String, Float>emptyMap() : Collections.singletonMap("vendor_live", score);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            return new FrameAnalysis(frame, faces.length, box,
                    null,                                     // no landmarks from this engine
                    Float.NaN, Float.NaN, Float.NaN,          // no head pose from this engine
                    EnumSet.noneOf(QualityIssue.class), score, audit, ms);
        } catch (Exception e) {
            throw new LivenessEngineException(ErrorCode.ENGINE_ERROR, "vendor PAD failed", e);
        }
    }

    @Override
    public String modelVersion() {
        return pad.version();
    }

    @Override
    public void close() {
        // Release per-session resources here.
    }

    /** Builds the real engine on this analyzer. */
    public static LivenessService service(VendorPad pad, ConfigProvider config, AuditSink audit) {
        FaceAnalyzerFactory factory = () -> new VendorPadAnalyzer(pad);
        return DefaultLivenessService.builder().config(config).analyzerFactory(factory).auditSink(audit).build();
    }
}
```

**Three things to get right:**

- **Calibrate the score.** The engine applies 0.85 and 0.30 to your score. These values were chosen for the bundled
  models. Measure your engine and set `passiveThreshold` and `padRejectThreshold`.
- **Know what no landmarks means.** With no landmarks and no pose, no challenge can run. In a run of this example with a
  stub engine, a score of 0.95 passed, 0.10 gave `PAD_FAILURE`, and 0.60 gave `ENGINE_ERROR`, because no challenge
  could be selected. Set `activeEnabled=false` to get `LIVENESS_BELOW_THRESHOLD` there instead, or supply landmarks
  and pose.
- **Report quality.** A frame with any quality issue does not count. This example reports none, so the engine never
  gets a chance to refuse a poor frame.

### Example 5: read the audit stream

```java
import java.util.Map;

import io.mosip.registration.liveness.api.AuditSink;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;

public final class AuditReader implements AuditSink {

    @Override
    public void record(Event event, WorkflowType workflow, String sessionId, Map<String, String> details) {
        // Runs on an engine thread: return quickly. A real sink hands the record to a queue.
        // sessionId is null for CONFIG_LOADED. The engine swallows anything thrown here.
        System.out.println(event + " workflow=" + workflow + " session=" + sessionId + " " + details);
    }

    public static void printMetrics(DefaultLivenessService engine) {
        System.out.println(engine.metrics().snapshot().toMap());
    }
}
```

Attach it with `.auditSink(new AuditReader())` on the builder. A session that fails the passive check as an attack
writes these records, in this order: `CONFIG_LOADED` (once per workflow), `MODEL_LOADED`, `LIVENESS_STARTED`,
`PAD_ATTACK_SUSPECTED`, `LIVENESS_ATTEMPT_FAILED`.

### Example 6: install a signed model bundle offline

Use the same zip that `ModelBundleTool pack` writes for the online update.

```java
import java.io.File;

import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.integrity.BundleSource;
import io.mosip.registration.liveness.analyzer.integrity.ClasspathBundleSource;
import io.mosip.registration.liveness.analyzer.integrity.ModelStore;
import io.mosip.registration.liveness.analyzer.integrity.ModelUpdater;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;

public final class OfflineInstall {

    public static void main(String[] args) throws Exception {
        File zip = new File(args[0]);                         // the zip from removable media
        ModelStore store = new ModelStore(new File(args[1])); // for example .liveness-models
        BundleSource bundled = new ClasspathBundleSource();   // the bundle packaged with the app
        TrustedKeys keys = TrustedKeys.builtIn();

        ModelUpdater.UpdateResult r = ModelUpdater.builder().build().installZip(zip, store, bundled, keys);
        switch (r.status()) {
            case INSTALLED:
                System.out.println("Installed " + r.version());
                break;
            case UP_TO_DATE:
                System.out.println("Not newer, nothing changed. In use: " + r.version());
                break;
            default:
                System.out.println("Refused: " + r.reason() + ", " + r.detail());
                return;
        }

        // New sessions use the newer of the packaged bundle and the store's current bundle.
        try (OnnxFaceAnalyzerFactory analyzers =
                new OnnxFaceAnalyzerFactory(bundled, store, keys, AnalyzerConfig.defaults())) {
            analyzers.preload();
            System.out.println("Loaded " + analyzers.modelVersion());
        }
    }
}
```

`installZip` takes the same limits and rules as an online update. It checks the signature, sizes and hashes before the
version, so a well-formed zip with changed contents is `BUNDLE_INVALID` whatever version it claims. A zip damaged at the
archive level fails earlier, as `ZIP_INVALID`. A verified zip that is not newer gives `UP_TO_DATE`, and the store does
not change.

This example was run with a zip of the current bundle: it printed "Not newer, nothing changed". A file that was not a
zip was refused with `ZIP_INVALID`. This exact program cannot reach its `INSTALLED` branch without a bundle signed with
the development private key. The same `installZip` call did return `INSTALLED` in a run with a throwaway key passed
through `new TrustedKeys(Map)`, and `ModelUpdaterTest` and `DesktopOfflineModelInstallTest` cover that path too.

**In the clients:**

- **Desktop:** a supervisor or admin uses the "Install model bundle" tile on the home page. It calls
  `DesktopLivenessService.installOfflineBundle(File)`. This tile was not tried in a logged-in client.
- **Android:** a supervisor or admin uses the "Face liveness models" page, which calls
  `installModelBundleFromFile()`. A debug build reached the same page and ran an install and a rollback on a phone.
- **Command line:** `ModelBundleTool install --store <dir> --from <dir>` takes an unpacked folder, not a zip.

---

## 12. Further reading

Optional. This document stands alone, and these files hold more background. Some of them were written earlier, so where
one differs from the code, trust the code and this document.

| Topic | File |
|---|---|
| Device and frame path | `docs/part1-frame-streaming-architecture.md` |
| PAD design, thresholds and model bundle | `docs/part2-pad-architecture.md`, `models/README.md` |
| PAD evaluation | `docs/part2-iso30107-evaluation.md` |
| Desktop integration | `docs/part4-desktop-integration.md` |
| Android integration | `docs/part5-android-integration.md` |
| Screens and texts | `docs/ui-liveness-spec.md` |
| Requirement by requirement status and known gaps | `docs/requirements-traceability.md` |
| MockMDS patch | `mock-mds-patch/README.md` |
| Scripted engine | `liveness-fake/README.md` |
