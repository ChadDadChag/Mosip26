# MOSIP face liveness and PAD (Decode Problem 04)

Offline face liveness and presentation attack detection for the Desktop and Android Registration Clients. It uses a hybrid approach: a passive check first, then randomly chosen facial challenges when the passive result is inconclusive.

| Module | Owner | What |
|---|---|---|
| `liveness-api` | team (approved by Part 3) | Shared contract: `LivenessService`, `LivenessSession`, `FrameSource`, `FaceAnalyzer`, events, results, policy, error codes |
| `liveness-core` | Part 3 | The engine: `DefaultLivenessService` |
| `liveness-fake` | Part 3 | `FakeLivenessService`, scripted scenarios for UI work (see [liveness-fake/README.md](liveness-fake/README.md)) |
| `liveness-tools` | Part 3 | Trace recording, replay and threshold tuning, dev only (see [liveness-tools/README.md](liveness-tools/README.md)) |

**Design:** [docs/person3-design.md](docs/person3-design.md) covers the architecture, state machine, decision flow, challenges, errors, security and ISO/IEC 30107 notes. Contract change proposals are in [docs/contract-change-proposals.md](docs/contract-change-proposals.md).

## Build

You need JDK 17 or newer and Maven 3.9 or newer. The output is Java 8 bytecode, and the build checks the API against the Java 8 and Android API 21 signatures.

```bash
mvn verify          # all modules; liveness-core has an 85% line-coverage gate
```

## Use (Parts 4 and 5)

```java
LivenessService liveness = DefaultLivenessService.builder()      // or: new FakeLivenessService(config)
        .config(configProvider).analyzerFactory(analyzerFactory).auditSink(auditSink).build();
LivenessSession session = liveness.newSession(WorkflowType.RESIDENT); // null: liveness disabled
session.start(frameSource, listener);  // callbacks arrive on a background thread
// Retry button -> session.retry();  leaving the screen -> session.cancel();
// accept the face only if result.passed(); use result.bestFrame
```
