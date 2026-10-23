# liveness-api contract change proposals

Owner and approver: Part 3. **None of these have been applied.** `liveness-api` sources are unchanged. Each item already has a workaround in `liveness-core`, so nobody is blocked. Apply them in one contract PR once the affected parts agree.

| # | Change | Why | Affects | Workaround today |
|---|---|---|---|---|
| P1 | Add `ErrorCode.COOLDOWN_ACTIVE` (`userMessage()` → `MAX_ATTEMPTS_REACHED`), `AuditSink.Event.COOLDOWN_STARTED`, and a way to show the remaining time (e.g. put the seconds in `LivenessEvent.timeoutMs` for that FAILED_FINAL). Optional: a `CooldownStore` hook so the lockout survives an app restart. | `onRepeatedFailure=COOLDOWN` exists, but there is no code that tells "refused because of cooldown" apart from "just ran out of retries". The in-memory lockout resets on restart. | P4 (operator login screen), P5 | `start()` immediately gives FAILED_FINAL(MAX_RETRIES_EXCEEDED, recoverable=false). Audit carries `reason=cooldown` and `cooldownRemainingMs`. |
| P2 | `FrameAnalysis` javadoc: "+pitch = subject looks UP", and yaw/pitch are always in the subject's frame (un-mirrored). | Only the yaw sign is documented. LOOK_UP/LOOK_DOWN depend on the pitch sign. | P2 | Core assumes +pitch = up in one place (`FaceGeometry.userPitch`), with a test. **Confirmed by Part 2 (2026-10-24): +pitch = looking up.** The analyzer removes the mesh's neutral-face bias and reports pose in the subject's frame, never mirrored. |
| P3 | Add `LivenessEvent.Guidance.NONE` (hint cleared). | The UI can't tell when a positioning hint no longer applies. | P4, P5 | Core re-emits CHECKING_PASSIVE (or CHALLENGE_PROGRESS during a challenge) to mean "cleared". |
| P4 | Extend `LivenessPolicy.validate()`: reject `passiveWindowFrames < 1`, `passiveTimeoutMs <= 0`, `cooldownSec < 0`, empty or `null` pool entries, and `minChallenges` greater than the number of **distinct** pool entries. | `BLINK,BLINK` with `minChallenges=2` passes today but cannot be satisfied. | All (config) | `PolicyResolver.validateExtra` runs the same checks. An invalid config falls back to the bundled defaults and audits `CONFIG_LOADED{error}`. |
| P5 | Add `activeAnalyzeFps` (default 15) next to `analyzeFps`. | At 8 fps a 100–150 ms blink is often never seen closed. | P2 (latency budget), P5 (low-end phones) | Core analyses every delivered frame, up to 15 fps (TUNE), during a challenge. |
| P6 | `LivenessSession.retry()` javadoc: "valid after ATTEMPT_FAILED or a recoverable DEVICE_ERROR". | `DEVICE_ERROR.recoverable` already tells the UI to show Retry. | P4, P5 | Core already accepts it (no retry consumed). |
| P7 | Add `LivenessEvent.Guidance.STRAIGHTEN_HEAD` ("Keep your head straight"). | ISO/IEC 19794-5 limits roll to ±8°. A tilted head now gets LOOK_AT_CAMERA, which does not tell the user what to fix. | P4, P5 (hint text) | Core shows LOOK_AT_CAMERA when the head is tilted more than 20° sideways (`EngineTuning.maxPassiveRollDeg`), and the best-frame ranking prefers frames within ±8°. |

## Accepted contract changes

| Date | From | Change | Status |
|---|---|---|---|
| 2026-10-23 | Part 1 | `DeviceCapabilities` (formats, resolutions, frame rates); `FrameSource.DeviceInfo.capabilities` plus `withCapabilities`/`withCertificationLevel`; `Frame.PixelFormat` (RGB888, GRAY8) with `pixels()`/`format()`. The old `Frame` and `DeviceInfo` constructors and `Frame.rgb()` are kept. | Accepted by Part 3 (backward compatible; all Part 3 code and tests pass unchanged) |
| 2026-10-23 | Part 3 | `liveness-default.properties` restored (it was missing from Part 1's upload). The engine layers it under the host config for the per-workflow defaults (operator/supervisor 1 challenge, operator cooldown). | Restored |

## Notes for other parts (not contract changes)

- **N1 → Part 1: preview.** `FrameSource.start(Listener)` takes a single listener, and the engine owns it. The live preview (§10.1) therefore needs a way to receive frames too: either the source supports several listeners, or a tee. This belongs to Part 1. Core needs nothing beyond `start`, `stop` and `onError`.
  **Resolved on the Desktop (2026-10-23)** by Part 1's `PreviewFrameSource`. This is a tee that wraps any source and sends every frame to the preview and to the engine, with errors going to the engine only. No contract change was needed. Android (CameraX) can use the same wrapper, or CameraX's own `Preview` use case.
- **N2 → Parts 1/2: frame rate.** The detectors assume ≥ 12 fps reaches the analyzer while a challenge runs (see P5).
- **N3 → Parts 4/5: config key names.** The team split says `timeoutSec`. The contract key is `mosip.registration.liveness.face.<workflow>.challengeTimeoutMs`. Use the contract names.
