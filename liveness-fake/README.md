# liveness-fake: scripted `LivenessService` for UI work

`FakeLivenessService` implements the real contract (`LivenessService` / `LivenessSession`). It uses the real per-workflow policy, real threading, and real retry, cancel and cooldown behaviour. Only the decisions are scripted. To switch to the real engine later, change the line that constructs the service; nothing else changes.

## Usage

**Desktop (JavaFX):**
```java
LivenessService liveness = new FakeLivenessService(key -> Objects.toString(ApplicationContext.map().get(key), null));
LivenessSession s = liveness.newSession(WorkflowType.RESIDENT);      // null → liveness disabled, use legacy flow
if (s != null) s.start(frameSource, new LivenessListener() {          // frameSource may be null with the fake
    public void onEvent(LivenessEvent e)     { Platform.runLater(() -> screen.render(e)); }
    public void onComplete(LivenessResult r) { Platform.runLater(() -> screen.finish(r)); } });
```

**Android (native layer, forwarding to Flutter):**
```java
LivenessService liveness = FakeLivenessService.builder(syncedConfig::get).auditSink(auditSink).build();
LivenessSession s = liveness.newSession(WorkflowType.OPERATOR);
s.start(frameSource, new LivenessListener() {
    public void onEvent(LivenessEvent e)     { mainHandler.post(() -> eventSink.success(toMap(e))); }
    public void onComplete(LivenessResult r) { mainHandler.post(() -> eventSink.success(toMap(r))); } });
```

The Retry button calls `s.retry()`. Leaving the screen calls `s.cancel()`, which you must always do.

## Choosing a scenario

The first match wins:
1. `builder.scenario(...)` or `service.setScenario(...)` (can be changed at runtime)
2. the system property `-Dliveness.fake.scenario=PAD_ATTACK`
3. the config key `mosip.registration.liveness.fake.scenario=PAD_ATTACK`
4. the default, `PASS_ACTIVE`

| Scenario | What the UI sees |
|---|---|
| `PASS_PASSIVE` | Checking… then PASSED (no challenge) |
| `PASS_ACTIVE` | Checking… then the policy's challenge count (resident 2, operator/supervisor 1), then PASSED |
| `PASS_AFTER_1_CHALLENGE` / `PASS_AFTER_2_CHALLENGES` | a fixed number of challenges, then PASSED |
| `FAIL_CHALLENGE_THEN_PASS_ON_RETRY` | ATTEMPT_FAILED(CHALLENGE_FAILED), then after Retry a new sequence and PASSED |
| `PAD_ATTACK` | ATTEMPT_FAILED(PAD_FAILURE) each attempt (show only the generic message), then FAILED_FINAL(MAX_RETRIES_EXCEEDED) |
| `CHALLENGE_TIMEOUT` | a prompt that runs for the full timeout, then ATTEMPT_FAILED(CHALLENGE_TIMEOUT), then PASSED after Retry |
| `NO_FACE_THEN_PASS` | GUIDANCE(NO_FACE)… then Checking… again (hint cleared), then PASSED |
| `MULTIPLE_FACES` | GUIDANCE(MULTIPLE_FACES), then ATTEMPT_FAILED(MULTIPLE_FACES), then PASSED after Retry |
| `POOR_LIGHTING_HINTS` | GUIDANCE(IMPROVE_LIGHTING), then GUIDANCE(MOVE_CLOSER), cleared, then challenges and PASSED |
| `DEVICE_DISCONNECT` | DEVICE_ERROR(recoverable) mid-challenge; Retry does not use up an attempt; then PASSED |
| `MAX_RETRIES_EXCEEDED` | four failed attempts, then FAILED_FINAL. For OPERATOR this starts the 60 s cooldown: the next session fails immediately. |
| `ENGINE_ERROR` | ATTEMPT_FAILED(ENGINE_ERROR) without using a retry, then FAILED_FINAL(ENGINE_ERROR) |
| `RANDOM` | one of the above, picked for each session |

## Timing

`-Dliveness.fake.timeScale=1.0` gives realistic pacing and is the default: about 1.5 s of checking, about 2 s per challenge, then hold still. Use `0.25` for 4× speed, or `0` for instant (automated tests).

## Behaviour your UI can rely on (the same as the real engine)

- Callbacks arrive on a background thread named `liveness-events-*`, in order. Marshal them to the UI thread yourself.
- `onComplete` is called **exactly once**. Nothing is delivered after it.
- `cancel()` can be called from any thread, even inside a callback, and is idempotent. It stops the `FrameSource`.
- `retry()` is only honoured after ATTEMPT_FAILED or a recoverable DEVICE_ERROR. At any other time it is ignored.
- `ATTEMPT_FAILED.retriesLeft` is how many more Retry presses are allowed.
- Show messages through `event.error.userMessage()`. `PAD_FAILURE` always maps to the generic "could not be completed" message.
- If you pass a real `FrameSource`, it is started and stopped like the real engine would, its errors show up as DEVICE_ERROR, and its latest frame becomes `LivenessResult.bestFrame`. Without a source, `bestFrame` is a grey 320×240 placeholder.
