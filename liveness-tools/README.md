# liveness-tools: trace recording, replay and detector tuning (development only)

This module never ships in the Desktop or Android apps.

| Tool | What it does |
|---|---|
| `TraceRecorder` | Runs any `FrameSource` (Part 1) and any `FaceAnalyzer`/`FaceAnalyzerFactory` (Part 2), and writes a labelled JSONL trace. |
| `TraceReplayer` | Feeds traces through the **real** detectors and passive evaluator from `liveness-core`. |
| `DetectorTuningReport` | Sweeps the detector `TUNE` thresholds over labelled traces, and prints TPR, FPR, wrong-action rate, time to detect, the passive APCER trade-off, and recommended values. |
| `SyntheticTraceGenerator` | Generates labelled synthetic traces, used as pipeline and regression fixtures. |

## Privacy

A trace holds **only scale-free features**:
- eye aspect ratio
- mouth width and corner lift, relative to inter-ocular distance
- yaw, pitch and roll
- liveness score
- face count and quality issues
- the face box, normalised to the frame

There are **no pixels and no landmark coordinates**, so a face cannot be reconstructed from a trace. The replayer rebuilds a generic face with the same ratios, which is all the detectors use.

## Recording real traces (once Parts 1 and 2 are available)

```bash
mvn -q -pl liveness-tools -am install
java -cp "liveness-tools/target/classes:liveness-core/target/classes:liveness-api/target/classes:<part1.jar>:<part2.jar>" \
  io.mosip.registration.liveness.tools.TraceRecorder \
  --source <FrameSource class> --analyzer <FaceAnalyzerFactory class> \
  --label blink --seconds 6 --prompt-ms 2000 --out liveness-tools/src/test/resources/traces/<name>-blink-1.jsonl
```

- **Labels:** `blink`, `smile`, `turn_left`, `turn_right`, `look_up`, `look_down`, `neutral`, `photo_attack`, `screen_attack` and `replay_attack`.
- **Timing:** the console prints `>>> NOW: BLINK` at `--prompt-ms`. The person performs the action then, and keeps still otherwise.
- **What to record:** aim for at least 5 people × 3 takes per label, with different lighting, glasses and distances.
- **Committing:** only commit traces from people who agreed. Name files without real names (for example `p03-blink-2.jsonl`).

## Running the report

```bash
java -cp "liveness-tools/target/classes:liveness-core/target/classes:liveness-api/target/classes" \
  io.mosip.registration.liveness.tools.DetectorTuningReport liveness-tools/src/test/resources/traces
```

**Changing defaults:**
- **`EngineTuning` values** (engine-internal thresholds) change in a reviewed `liveness-core` commit.
- **Policy values** in `liveness-default.properties` (`passiveThreshold`, `padRejectThreshold`, …) change **only through a contract PR** approved by Part 3.

## Committed traces

`src/test/resources/traces/synthetic-*.jsonl` are **synthetic** (header `"synthetic": true`). The regression tests in `TraceToolsTest` replay them:
- every action is detected with the default tuning
- no motion detector fires on another label
- no attack passes the passive check

Replace or extend them with real team recordings. The same tests then become real regression tests.
