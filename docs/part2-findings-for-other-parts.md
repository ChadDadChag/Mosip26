# Part 2: findings and changes for the other parts

These come from Part 2's live sessions, recorded traces and PAD evaluation with the real analyzer. They are
**single subject s01, provisional**: one person, one laptop webcam, one phone. With the user's approval, the Part 3
issues were fixed directly, one commit per fix (messages start "Part 3:"). Each change can be reverted through a
tuning flag. Everything else is a recommendation; no other part's code was changed.

## Part 3: liveness engine

### Changes made (2026-10-24)

| # | Change | Evidence | How to revert |
|---|---|---|---|
| P3-1 | **TURN and LOOK no longer need a return.** They are detected when the hold completes (≥ 20° / 13° for ≥ 200 ms over ≥ 3 frames). `ChallengeRunner` then emits `GUIDANCE(LOOK_AT_CAMERA)`, after HOLD_STILL if it was the last challenge. The wrong-way failure and arming are unchanged. | With only the product prompt, **0 of 9** turns passed. Users reached 20° in 1.5–2.1 s, then held 52–63° and never came back, because nothing asked them to. Coached ("turn about 30° and come back") they passed 2 of 4. Traces with a full return were detected 6 of 6 at 20°. | `EngineTuning.axisRequireReturn = true` |
| P3-2 | **`blinkMaxClosedMs` 400 → 600 ms** | Deliberate blinks on request took 400–600 ms to reopen to 0.85 × baseline EAR. A trace replay detected 3 of 3 at 600 ms and 0 of 3 at 400 or 500 ms. | set it back to 400 |
| P3-3 | **Tilt-spoof guard for BLINK and SMILE.** Frames whose yaw or pitch drift more than `actionMaxPoseDriftDeg` (12°) from the baseline do not count. A closure or smile in progress is cancelled. After a drift, open eyes at a stable pose must be seen before a new closure counts. | In Phase 5 a close-up phone photo, tilted by hand, passed BLINK and SMILE. On real traces, 8° and 12° keep every real blink and smile (TPR 1.00, FPR 0); 16° adds false firings. | `actionMaxPoseDriftDeg = NaN` |
| P3-4 | **`DetectorTuningReport` no longer recommends on FPR alone.** With fewer than 2 positives, or TPR 0 at every value, it prints "insufficient positive evidence: keep current". It now also sweeps `blinkReopenRatio`, `blinkMaxClosedMs` and `actionMaxPoseDriftDeg`. | On real traces it had "recommended" blinkClosedRatio 0.45 although no value detected anything. | revert the commit |

The Part 3 tests that changed, and why, are listed in [PART3_STATUS.md](../PART3_STATUS.md) under "Changes from the
Part 2 review". All Part 3 gates stay green: 197 core tests, the 85% coverage gate, animal-sniffer (Java 8 and
API 21), and 12 tool tests.

### Live verification after the changes

Mirrored preview, product prompts only, no coaching. The analyzer is the shipped one, with the session moiré cap.

| Run | Setup | Challenges: outcome, time, max pose drift, max \|yaw\| | Result |
|---|---|---|---|
| b1 | real face, forced challenges | SMILE ✓ 0.90 s (drift 3°); BLINK ✓ 1.01 s (drift 4°) | **PASSED**, 1 attempt, 5.8 s |
| b2 | real face, forced challenges | BLINK ✓ 0.90 s (drift 1°); **TURN_LEFT ✓ 2.13 s**, \|yaw\| 29°, no return needed | **PASSED**, 1 attempt, 6.6 s |
| b3 | real face, forced challenges | SMILE ✓ 1.36 s (drift 4°); **TURN_LEFT ✓ 2.02 s**, \|yaw\| 30° | **PASSED**, 1 attempt, 6.2 s |
| a4 | real face, default settings | none (passive) | **PASSED** passively, 0.9994, 2.0 s |
| q | phone photo about 10 cm away, default settings, own face out of view | attempts 1–3: PAD_FAILURE in the passive phase; attempt 4: passive median **capped at 0.600**, so a challenge (TURN_RIGHT); PadMonitor then failed it in the challenge | **FAILED** (MAX_RETRIES_EXCEEDED), as required |
| r6 | phone photo at normal distance, forced challenges | POOR_QUALITY, then PAD_FAILURE twice in the passive phase | **FAILED**, as required |

Notes on this table:
- **Turns.** Both turns passed in about 2 s without coming back. Before the change, 0 of 9 passed.
- **First attempt at these runs: turns misread as tilts.** The tester tilted their head sideways (roll) instead of rotating on 3 turn prompts. Yaw moved only 3–7°, so those prompts timed out. This is the second time "turn your head" was read as "tilt"; see the prompt-wording recommendation below.
- **Two close-up phone runs were discarded.** The tester's own face entered the view, which the tester confirmed ("there was an issue on my side"). The data agree: raw scores about 1.00, low moiré, and a real eye closure (EAR 0.03 × baseline) with under 1° of pose drift.
- **The tilt guard was not reached live.** The phone attacks never got to a BLINK or SMILE prompt, because passive PAD or PadMonitor stopped them first. The guard is covered by unit tests.
- **No false cancels on real faces.** Real blinks and smiles drifted only 1–5°, well below the 12° limit.

### Confirmed facts and remaining recommendations

| # | Topic | Finding or recommendation |
|---|---|---|
| a | Pitch sign | **Confirmed: +pitch = looking up.** The analyzer removes the mesh's neutral bias (−9.5°, a signed bundle parameter) and reports pose in the subject's frame, never mirrored. Proposal P2 is marked confirmed; the contract javadoc is unchanged. |
| b | Turn UX | Implemented as P3-1 above (detection at the hold, then a LOOK_AT_CAMERA cue). Remaining: the prompt text itself is in the UIs (Parts 4 and 5); see below. `turnMinDeg` 20 is confirmed: retest traces gave TPR 1.00 from 12° to 25°. |
| c | Blink | 9 of 9 detected live before the change. `blinkReopenRatio` stays 0.85. The report's old blink "recommendation" was the FPR-only artefact fixed in P3-4. |
| d | Tilt spoof | Implemented as P3-3. PadMonitor (3-frame rolling median < 0.30) still watches every challenge frame. |
| e | Analyzer speed | 18–22 ms per frame (p50 / p95) on the development laptop's CPU, comfortably above the 15 fps challenge rate. |
| f | Passive thresholds | Keep 0.85 / 0.30 (decision D-PAD-4). The analyzer now caps the score at 0.60 when the session's rolling median screen moiré is 1.40 or more. The cap can force a challenge but can never trigger PadMonitor (0.60 > 0.30). Round 1: close-up phone photos passing passively went from 4/12 to 0/12, with 0 bona fide rejections. See [part2-iso30107-evaluation.md](part2-iso30107-evaluation.md). |
| g | `padDetails` keys | At most 8 audit keys: `minifas_v2_live`, `minifas_v1se_live`, `minifas_v2_c0`, `moire`, `moire_med`, `moire_cap`, `volatility_cap`, `pad_latch`. Pose-gated frames: `pose_gated`, `moire`, `moire_med`. `PassiveEvaluator.padDetailMedians()` aggregates them as before. F18 replaced `minifas_v2_c2` and `minifas_v1se_spoof`, which the softmax makes redundant (V2 c2 = 1 − v2 live − v2 c0; V1SE spoof = 1 − v1se live). |
| h | Trace capture | People start moving before the cue when they know the action, and a cold webcam open (0.8–2.5 s) can swallow the pre-prompt baseline. Keep the camera open across takes, as Part 2's `KeptOpenSource` does, and keep `DetectorTuningReport`'s 2-positive minimum. |
| i | Print guards (F18) | A printed cut-out passed passively on a retry, twice live. The analyzer now caps the score at 0.60 for the rest of the session once a 3-frame median drops below 0.30 (PAD latch), and while the second-lowest of the last 10 model scores is below 0.85 (volatility cap). Part 3 is unchanged. **Parts 4 and 5:** retry with `session.retry()`, never with a new session, or the latch starts over. Live: 29 attack sessions, none passed; 10 real-face sessions, 0 rejections, 1 of 7 passive passes became a challenge. See [part2-pad-architecture.md](part2-pad-architecture.md), section 3.2. |

## Parts 4 and 5: Desktop and Android integration

**Wiring the real engine:**

```java
OnnxFaceAnalyzerFactory analyzers = new OnnxFaceAnalyzerFactory(          // verifies the signed model bundle
        new ClasspathBundleSource(), modelStoreOrNull, TrustedKeys.builtIn(),
        AnalyzerConfig.fromConfig(configProvider));                         // optional ...face.analyzer.* keys
analyzers.preload();                                    // at start-up: about 0.2 s warm (1 s in a fresh JVM), not in the first session
LivenessService liveness = DefaultLivenessService.builder()
        .config(configProvider).analyzerFactory(analyzers).auditSink(auditSink).build();
```

- **Dependencies.** On desktop, add `com.microsoft.onnxruntime:onnxruntime:1.31.0` to the application (the analyzer
  declares it `provided`). On Android, use `onnxruntime-android:1.31.0` instead (minSdk 24, AGP 8 or later); see
  [part2-pad-architecture.md](part2-pad-architecture.md) §11.
- **Turn prompt wording.** Show "Please turn your head to the left, then look back at the camera" (and the same for
  right). The user asked us during testing whether "turn" means tilting or rotating, so make it clear that it means
  rotating, as when shaking the head "no", not tilting towards a shoulder. A small arrow animation helps.
- **Show `GUIDANCE(LOOK_AT_CAMERA)`.** It now arrives right after a turn or look is detected (P3-1), and the next
  challenge waits for a frontal face.
- **Mirroring.** Mirror the preview like a selfie (display only). Never mirror the frames given to the engine. With an
  unmirrored preview, our tester turned the wrong way twice in Phase 5.
- **Model updates.** Call `ModelUpdater` from the client's existing sync job (fix round 4):
  `ModelUpdater.builder().build().check(new URL(indexUrl), modelStore, new ClasspathBundleSource(), TrustedKeys.builtIn())`.
  - It downloads the index and the zip, checks them, and installs with
    `modelStore.install(staged, bundled, keys)`. That call verifies the bundle, refuses anything not newer, and
    switches atomically.
  - On `INSTALLED`, create a new factory, or reload it, as Android does; the next factory uses the newer of the
    bundled and installed versions.
  - Audit the outcome and its reason code.
  - Read the URL and the switch from the same keys as Android: `mosip.registration.liveness.model.update.url` and
    `.enabled`, off by default ([part2-pad-architecture.md](part2-pad-architecture.md) §7).
  - Expose `rollback()` as an operator action only.
- **Translations.** [ui-liveness-spec.md](ui-liveness-spec.md) Appendix A has the liveness texts in Arabic, French,
  Hindi, Kannada and Tamil, as `messages_<code>.properties` blocks. They are machine-translated and need review. The
  French block is `\u`-escaped for v1.2.0.2's ISO-8859-1 `messages_fr.properties`.
- **Errors.** A model integrity failure is `MODEL_INTEGRITY_FAILED` and is final: there is no fallback to unverified
  models. Show the contract's `userMessage()`.
- **Face binding (F1).**
  - **Why.** Liveness runs on camera frames, but the stored face comes from the SBI capture. A live person could
    therefore pass liveness while a photo is captured.
  - **What to do (Person 4, Desktop).** After a PASSED gate and the device `/capture`, compare the two before
    accepting the capture. Anything but MATCH rejects the capture: do not store it, show the generic
    `liveness.error.verification_not_completed`, and run liveness again on the next Scan. Errors fail closed.

    ```java
    analyzers.preload();                                   // verifies all five files
    background.execute(() -> {                             // optional: so the first binding does not build the session
        try { analyzers.warmUpMatcher(); } catch (LivenessEngineException e) { /* built on first use */ }
    });

    boolean bound;
    try (FaceMatcher matcher = analyzers.createMatcher()) {        // cheap; shares the factory's verified sessions
        MatchResult m = matcher.compare(result.bestFrame, capturedFaceFrame);   // RGB888 frames
        bound = m.matched();
        // audit LIVENESS_FACE_BINDING: m.outcome, m.similarity (2 decimals), m.threshold, result.sessionId
    } catch (LivenessEngineException | RuntimeException e) {
        bound = false;
    }
    if (!bound) { /* reject the capture; generic text; the next Scan runs liveness again */ }
    ```

  - **Config keys.** `mosip.registration.liveness.face.binding.enabled` (default true; the host reads it: false
    stores captures as before, audited SKIPPED) and `mosip.registration.liveness.face.binding.cosineThreshold`
    (default 0.45, read by `AnalyzerConfig.fromConfig`). The full flow, as built on Android, is in
    [ui-liveness-spec.md](ui-liveness-spec.md) §6 "Face binding (F1)".

  - **Outcomes.** Accept only `MATCH`. The other outcomes:
    - `NO_MATCH`: a different person;
    - `NO_FACE_A` / `NO_FACE_B`, `MULTIPLE_FACES_A` / `MULTIPLE_FACES_B`: image A or B does not hold exactly one face.

    Decode the SBI image to RGB first; ISO 19794-5 faces are often JPEG 2000.
  - **Threshold.** `mosip.registration.liveness.face.binding.cosineThreshold`; the default is 0.45, decided from
    the binding evaluation (OpenCV's documented value is 0.363; see
    [part2-pad-architecture.md](part2-pad-architecture.md) §12).
  - **Binding is not presentation attack detection.** A photo of the same person matches. Use it after liveness,
    not instead of it.
  - **Android.** Built in fix round 2 (the capture handler stores an armed face capture only after a MATCH; see
    the spec section above).

## Part 1: frame pipeline

| Topic | Finding |
|---|---|
| Webcam backend crash | During a long trace-recording session (sarxos webcam-capture 0.3.12 with BridJ, JDK 11, Windows 11), the JVM exited once with code 116 after 16 of 18 takes. There was no `hs_err` file. It was not reproduced in about 100 later takes. If it recurs, try the openpnp-capture backend, or keep the camera open between sessions to reduce open/close cycles. |
| Note N1 (preview tee) | The engine owns the single `FrameSource.Listener`, so the UI needs another way to get preview frames. Part 2's dev source `WebcamFrameSource` solves it with a separate `setPreviewListener` that gets every grabbed frame, independent of the engine's listener (dev code in `liveness-eval`). A production `FrameSource` could use the same tee. |
| Cold camera open | Opening the webcam took 0.8–2.5 s. `KeptOpenSource` (keep the device open; `start` and `stop` only attach and detach the listener) removed it for back-to-back takes. A similar keep-warm option would shorten retries. |

## All parts: second-screen replay on a tablet (F5, 2026-10-27 and 2026-10-28)

| Topic | Finding |
|---|---|
| Live result | The subject's own selfie video on a tablet (Galaxy Tab, about 11 in), 40–60 cm from the webcam: 0 of 6 live sessions passed (3 with the default policy, 3 with forced challenges). Every attempt that reported a code ended in PAD_FAILURE, and no challenge was completed. The PAD latch fired in every session within 1.7–4.6 s. |
| Recorded takes | First recording: the 4 recorded takes were non-responses: the face covered about 3.4% of the picture, below the 4% minimum, so the passive window never filled. In production that is a "move closer" timeout, never an acceptance. |
| Retake at real-face size (2026-10-28) | The tablet held so that the face covered 20–35% of the picture: 0 of 2 live sessions passed (PAD_FAILURE, and the PAD latch fired in both), and 0 of 7 responding recorded takes passed passively (highest first window 0.59 against 0.85). The moiré cap decided nothing; the models, the PAD latch and the PAD monitor did. |
| Nothing to change | Parts 3, 4 and 5 need no change for this case: the engine's existing PAD path and the F18 guards decided it. |
| Not covered | One subject, one tablet, one video. No desktop monitor, and no tablet held closer than real-face size (face over 35% of the picture). Details: `docs/part2-iso30107-evaluation.md` section 4.6. |
