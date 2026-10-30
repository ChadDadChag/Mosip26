# Usage and installation

This guide shows how to install, run and evaluate the face liveness solution for the MOSIP Registration Clients. It is
written for someone who has not seen the project before. Each route stands on its own, so you only need to read the one
you pick.

**What the solution does.** Before a Registration Client accepts a face, it checks that the face belongs to a live person
in front of the camera. It first watches the face without asking for anything (the *passive* check). If the score is not
high enough, it asks for random actions such as a blink or a head turn (the *active* check). It also detects *presentation
attacks*: printed photos, photos on screens and replayed videos. All of this runs on the device, with no network.

**You do not need a MOSIP server or a biometric device for any route here.** A phone camera or webcam stands in for the
device. Neither client can get past its own login without a MOSIP server, so each route uses a demo page or window that
calls the same liveness code the real screens call.

## Pick a route

| Route | What you get | What you need | Time |
|---|---|---|---|
| [A. Android app](#route-a-android-app-ready-to-install) | The real engine on a phone: all three workflows, attacks refused, offline, identity check, every error screen | The APK from the submission pack, an arm64 Android 9+ phone | 5 minutes |
| [B. Desktop demo](#route-b-desktop-demo-ready-to-run) | The real engine on a webcam, in a small test window | The demo folder from the submission pack, Windows, Java 11 or later, a webcam | 3 minutes |
| [C. Engine from source](#route-c-engine-from-source) | Builds the engine and runs its tests, the webcam demo and the benchmark | JDK 11, Maven, Git | About 10 minutes on a first build |
| [D. Desktop client from source](#route-d-desktop-client-from-source) | Builds the real Desktop client with liveness, runs its tests, opens the real liveness screen, plays a recorded attack through the engine | As route C, plus disk space for MOSIP's dependencies | About 20 minutes on a first build |
| [E. Android client from source](#route-e-android-client-from-source) | Builds the Android client with liveness | Flutter 3.10.4, JDK 21, Android SDK 34, Git Bash | About 20 minutes on a first build |
| [F. Simulated biometric device](#route-f-simulated-biometric-device) | The full device path on a laptop, through MOSIP's MockMDS | JDK 21, Maven | 15 minutes |

The submission pack is the cloud folder listed in the submission email. Routes A and B use it. Routes C to F need only
this repository.

## Route A: Android app (ready to install)

The APK is a developer build of MOSIP's Android Registration Client v1.1.1 with the liveness patches. It is a developer
build because only those builds have the demo page. It is signed with a debug key, so Android will warn you when you
install it.

**Needs:** a 64-bit (arm64) phone with Android 9 or later and a front camera. We tested on a Samsung Galaxy S23 FE with
Android 16. A profile build of the same code also ran on a Redmi Note 7 with Android 10.

### Install

1. Copy `app-arm64-v8a-debug.apk` to the phone and tap it, or with USB debugging on, run
   `adb install -r app-arm64-v8a-debug.apk`.
2. If Android asks, allow your file manager to install unknown apps.
3. On a Samsung phone, first turn off **Auto Blocker** (Settings, Security and privacy).
4. If Google Play Protect says the app was "built for an older version of Android", tap **More details**, then
   **Install anyway**.
5. Open the app and allow the camera when it asks.

### Evaluate it

The app opens at MOSIP's login page. Do not try to sign in: that needs a server. Tap **Face liveness demo** at the top
right. Hold the phone about 40 to 50 cm from your face, in even light.

| # | Do this | Expected result |
|---|---|---|
| 1 | Under **Run a check**, tap **Resident face capture** and look at the camera | "Checking face liveness…", then **Passed – no action needed** |
| 2 | Turn on **Always ask for actions**, then tap **Resident face capture** | Two random actions, one at a time, then **Passed after 2 actions**. "Turn" means rotate your head as if shaking it "no", without tilting |
| 3 | Tap **Operator sign-in**, then **Supervisor approval** | One action each, because these workflows have their own policy |
| 4 | Turn **Always ask for actions** off and set Camera to **Back**. Show the back camera a printed photo or a photo on a screen, with the face at about a quarter of the screen's width. Keep your own face out of view | Refused, with only a generic message. Tap **Retry** until no attempts remain, then read the final screen |
| 5 | Set Camera back to **Front**. Turn on airplane mode and repeat step 1 | The same result: the check needs no network |
| 6 | Under **Identity check**, tap **1. Enroll the operator's face (once)**, then **2. Check identity (anyone)** | **Same person**. If a second person does the check, **Different person – refused**. Tap **3. Delete test face** when you finish |
| 7 | Under **Simulated screens**, tap any row | That screen, on demand, from a simulated engine: for example a disconnected camera, or too many attempts |
| 8 | Scroll to **Models** and **Diagnostics** | The signed model version, processing time, retry rate and failure rate. No images and no names |

A face that is too far away gives a "move closer" hint and not a decision. That is normal.

Sample images, attack set-ups and a signed model bundle for the offline install test are in
[Test data](test-data.md).

## Route B: Desktop demo (ready to run)

This is the real engine and signed models on your webcam, in a small test window. It is a developer window, not the
client's screen. Route D shows the client's real screen.

**Needs:** Windows 10 or 11, Java 11 or later (`java -version` to check) and a webcam.

1. Unzip `desktop-demo.zip` from the submission pack.
2. Double-click `run-liveness-check.bat`. The window opens within about 15 seconds.
3. Press **Space** to start a check, and look at the camera.
4. To see a challenge, run `run-liveness-check.bat --force-active` from a command prompt.
5. To try another workflow, add `--workflow OPERATOR` or `--workflow SUPERVISOR`.

The window shows the prompts and the result, with **Retry**, **Cancel** and **New session** buttons. Nothing is recorded:
frames stay in memory. A log of numbers, with no images, goes to the `logs` folder.

On Linux or macOS, use route C.

## Route C: Engine from source

This route builds the engine alone. It needs no MOSIP code, and the same commands work in PowerShell and in bash.

**Needs:** JDK 11, Maven 3.6 or later (we used 3.9.9) and Git. Use JDK 11 for the build: MOSIP's build uses Lombok
1.18.16, which does not run on newer JDKs.

```powershell
git clone https://github.com/ChadDadChag/Mosip26.git
cd Mosip26
mvn -N install "-Dgpg.skip"
mvn -f liveness-api/pom.xml install "-Dgpg.skip"
mvn -f liveness-core/pom.xml install "-Dgpg.skip"
mvn -f liveness-fake/pom.xml install "-Dgpg.skip"
mvn -f liveness-tools/pom.xml install "-Dgpg.skip"
mvn -f liveness-analyzer/pom.xml install "-Dgpg.skip"
mvn -f liveness-eval/pom.xml verify "-Dgpg.skip"
```

Each command also runs that module's tests. `-Dgpg.skip` turns off release signing, and the quotes stop PowerShell from
splitting the argument. On a machine with an empty Maven cache, this took about 9 minutes on a laptop.

Then run the tools. First make the class path, then pick a command:

```powershell
mvn -f liveness-eval/pom.xml dependency:build-classpath "-Dmdep.outputFile=target/eval-cp.txt"
$cp = "liveness-eval\target\classes;" + (Get-Content liveness-eval\target\eval-cp.txt -Raw).Trim()

# Check the signature and hashes of the bundled models
java -cp $cp io.mosip.registration.liveness.eval.ModelBundleTool verify --dir models

# A full check on the webcam: press Space to start (add --force-active to get challenges)
java -cp $cp io.mosip.registration.liveness.eval.LiveAnalyzerDemo --mode session --workflow RESIDENT

# Live view of the analyzer: face box, landmarks, head pose and scores
java -cp $cp io.mosip.registration.liveness.eval.LiveAnalyzerDemo --mode analyze

# Speed of each model on this machine; writes a report to liveness-eval/reports/
java -cp $cp io.mosip.registration.liveness.eval.AnalyzerBenchmark
```

In bash, use `:` in place of `;` and `cp="liveness-eval/target/classes:$(cat liveness-eval/target/eval-cp.txt)"`.

## Route D: Desktop client from source

MOSIP's Desktop client needs a server to start, so this route builds the real client, runs its tests, and then opens the
liveness screen on its own.

**Needs:** the same tools as route C, plus about 2 GB of free disk space on a first build.

```powershell
git clone --depth 1 --branch v1.2.0.2 https://github.com/mosip/registration-client.git
git clone https://github.com/ChadDadChag/Mosip26.git
robocopy Mosip26 registration-client\registration /E /XD .git target    # on Linux or macOS: rsync -a --exclude .git Mosip26/ registration-client/registration/
cd registration-client\registration
mvn -pl "liveness-api,liveness-core,liveness-fake,liveness-analyzer,registration-services,registration-client" -am install -DskipTests "-Dgpg.skip"
```

This repository is an overlay: its files go on top of MOSIP's `registration/` folder. The two `pom.xml` files are MOSIP's
originals with only the liveness additions. On Windows, `core.longpaths` may need to be on for the clone.

**Run the tests:**

```powershell
mvn -pl "registration-services,registration-client" test "-Dtest=**/liveness/**/*Test,AuditFactoryTest,LoginServiceTest,GlobalParamServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DfailIfNoTests=false" "-Dgpg.skip"
```

**Play a recorded attack through the real engine, with no camera.** From `registration-services`:

```powershell
mvn -q exec:java "-Dexec.mainClass=io.mosip.registration.liveness.device.demo.LivenessHarness" "-Dexec.args=video src/test/resources/liveness/videos/nasa-print-normal__expect-FAILED-PAD_FAILURE.mjpeg 15 --workflow resident --diagnostic --headless" "-Dgpg.skip"
```

It prints the audit trail and exits with 1, which means the check failed: the correct result for an attack. The exit codes
are 0 for passed, 1 for failed, and 2 for cancelled or timed out. Leave out `--headless` to watch it in a window.

**Open the client's real liveness screen.** From `registration/`. Replace the last argument with the one you want:

```powershell
# Real engine on the webcam
mvn -pl registration-client test-compile exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=io.mosip.registration.controller.liveness.LivenessScreenDemo" "-Dexec.args=--live webcam --workflow operator"

# Simulated engine, no camera: a wrong action, then a retry that passes
mvn -pl registration-client test-compile exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=io.mosip.registration.controller.liveness.LivenessScreenDemo" "-Dexec.args=--scenario FAIL_CHALLENGE_THEN_PASS_ON_RETRY --workflow operator"

# Save every screen state as PNG files, with no window
mvn -pl registration-client test-compile exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=io.mosip.registration.controller.liveness.LivenessScreenDemo" "-Dexec.args=--snapshots screens --face liveness-analyzer/src/test/resources/golden/portrait_frontal.png"
```

The scenario names are in [Test data](test-data.md), section 4.

## Route E: Android client from source

The changes are 68 patches against MOSIP's `v1.1.1` tag, in `android-integration/`. The script clones the tag, applies the
patches, copies in the engine and signed models, and builds.

**Needs:** Git for Windows (for Git Bash), JDK 21 and Flutter 3.10.4 with Android SDK platform 34 and build tools 34.0.0, all
on `PATH`, and Maven on `PATH`. The engine still builds on JDK 11, so tell the script where that JDK is. Set `user.name`
and `user.email` in git first, because `git am` records you as the committer.

```powershell
$env:LIVENESS_JDK11_HOME = "C:\path\to\jdk-11"
.\android-integration\scripts\apply-integration.ps1 -Target C:\dev\arc-liveness -BuildApk
```

The first Gradle build takes about 20 minutes. If it stops with `OutOfMemoryError`, run `cd android; .\gradlew.bat --stop`
and build again. To build by hand, and for other build types, see
[android-integration/README.md](../android-integration/README.md). A `--release` build has no demo page, so use a debug or
profile build to evaluate.

## Route F: Simulated biometric device

MockMDS is MOSIP's simulated biometric device service. Unpatched, it cannot stream faces to the Registration Client: the
client sends `serialNo`, MockMDS expects `deviceId`, and it answers with error 605. The patch fixes that and adds real frames
from images, a recording or a webcam.

**Needs:** JDK 21 and Maven.

```bash
git clone https://github.com/mosip/mosip-mock-services.git
cd mosip-mock-services
git checkout 3d2174d
git apply /path/to/Mosip26/mock-mds-patch/mockmds-face-stream.patch
cd MockMDS
mvn package -Dgpg.skip -Dmaven.test.failure.ignore=true
java -Dmosip.mock.sbi.face.stream.mode=video -Dmosip.mock.sbi.face.stream.source=<clip.mjpeg> -Dmosip.mock.sbi.face.stream.fps=15 -cp "target/mock-mds-1.3.1.jar:target/lib/*" io.mosip.mock.sbi.test.TestMockSBI "mosip.mock.sbi.device.purpose=Registration" "mosip.mock.sbi.biometric.type=Biometric Device"
```

On Windows use `;` in the class path. MockMDS listens on the first free port from 4501. To check the stream, follow
[mock-mds-patch/README.md](../mock-mds-patch/README.md).

## Configuration

Liveness is set through MOSIP's normal configuration, so a change on the server reaches each client at its next sync. If a
key is missing, the defaults in
[`liveness-default.properties`](../liveness-api/src/main/resources/liveness-default.properties) apply. Keys have the form
`mosip.registration.liveness.face.<scope>.<setting>`, where `<scope>` is `default`, `resident`, `operator` or `supervisor`.
A workflow key overrides the `default` key.

Example: turn liveness off for residents, and allow operators two actions:

```properties
mosip.registration.liveness.face.resident.enabled=false
mosip.registration.liveness.face.operator.minChallenges=2
```

To add liveness to a Desktop deployment, put the block in
[`part4-registration-default.properties`](part4-registration-default.properties) into the server's
`registration-default.properties`. The full list of keys is in [API documentation](api-documentation.md).

**Before any real deployment,** replace the development signing key. The steps are in
[part2-pad-architecture.md](part2-pad-architecture.md), section 8.

## Troubleshooting

| Problem | Cause and fix |
|---|---|
| `Unknown lifecycle phase ".skip"` in PowerShell | PowerShell split the argument at the dot. Keep the quotes: `"-Dgpg.skip"` |
| A Lombok error during compilation of MOSIP's modules | You are not on JDK 11. Set `JAVA_HOME` to a JDK 11 and run `mvn -version` to check |
| `git-commit-id` fails in a `git worktree` or a folder that is not a git checkout | Add `"-Dmaven.gitcommitid.skip=true"` |
| A file name is too long on Windows during a clone | Run `git config --global core.longpaths true`, or pass `-c core.longpaths=true` to `git clone` |
| Android: `OutOfMemoryError: Java heap space` in a build | Stop the Gradle daemons with `cd android; .\gradlew.bat --stop`, then build again |
| Android: the install is blocked | Follow the Samsung and Play Protect steps in route A |
| `adb install` fails with `INSTALL_FAILED_USER_RESTRICTED` on a Xiaomi phone | MIUI's Install via USB setting is off, and it needs a Mi account. Copy the APK to the phone and tap it in a file app instead |
| Android: the app stops at the login page | This is expected with no MOSIP server. Use **Face liveness demo** at the top right |
| The check keeps saying "move closer" | The face is too small. Move closer. The face must be at least 80 pixels wide and 4% of the picture |
| The webcam will not open | Close any other program that uses it, and start the tool again |
| A phone held upside down gets "no face" | Android does not rotate the screen to upside-down portrait, so the camera image arrives the wrong way up. Hold the phone upright or in landscape |
| A photo passes when you hold it up | Check that your own face is not in view. If only the photo is in view, please tell us: it is a real gap, and we want to record it |

## How this guide was checked

On 2026-10-30 we ran routes C, D and E from fresh clones, on a Windows 11 laptop.

| Route | What was run | Result |
|---|---|---|
| A | This APK was installed on the test phone on 2026-10-29. It was not installed again | The phone ran a rotation check on it. One photo attack ran on a Redmi Note 7 on 2026-10-30, on a profile build of the same code. Before that, attacks on a phone were last recorded on an earlier build, and most attack results come from the laptop webcam |
| B | The packed folder: the Java version check on Java 8 and Java 11, the window and the signed models. The webcam was not opened | Java 8 gets a clear message. On Java 11 the window opens with the models loaded |
| C | Every command, from a fresh clone with an empty Maven cache | All builds and tests passed, in about 9 minutes. The tools ran |
| D | The build, the tests, the recorded attack and the screen images. The two interactive windows were not opened in this check | All passed. The recorded attack was refused |
| E | The one-command build from a fresh clone, with Maven's own cache already filled | The APK built in 11 minutes. All 68 patches applied |
| F | Not repeated. The results are from when the patch was made | See [mock-mds-patch/README.md](../mock-mds-patch/README.md) |

## Limits of this evaluation

- Nothing has been run on real L0/L1 biometric hardware. The SBI (Secure Biometric Interface) path was tested with the
  patched MockMDS and with automated tests.
- Neither client has been run from end to end against a MOSIP server. Each hook into the client was checked by tests,
  compilation and code review. Android's hooks were also run through a debug route on a phone.
- The attack results come from one webcam, one phone, one tablet and four bona fide people, so they are provisional. See
  [Test cases and results](test-cases-and-results.md).
