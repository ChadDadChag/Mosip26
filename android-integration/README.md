# Android integration (Part 5): face liveness in the MOSIP Android Registration Client

This folder holds the changes that add face liveness to the MOSIP Android Registration Client.
They are kept as patches against the upstream release instead of a fork, plus scripts that copy
the engine from this repository into the client.

What the integration does, how it is built and how it was tested:
[`docs/part5-android-integration.md`](../docs/part5-android-integration.md). Measurements on a
phone: [`docs/part5-android-benchmark.md`](../docs/part5-android-benchmark.md).

## Base version

| | |
|---|---|
| Upstream | https://github.com/mosip/android-registration-client |
| Tag | `v1.1.1` (commit `8f4643a694f3b5e66e0491f077a8630ab5667658`) |
| Integration branch the patches were made from | `person5-liveness` (local branch of the Person 5 checkout) |

## Layout

| path | what it is |
|---|---|
| `patches/0001-*.patch` … `0056-*.patch` | one patch per commit of the integration branch, in order, for `git am` |
| `person5-liveness.diff` | the same changes as one diff, for `git apply` |
| `scripts/sync-engine.ps1` | builds the engine here and copies it into the client: the four engine jars, the signed model bundle and Part 1's `CameraXFrameSource.java`; writes `android/liveness/engine-sync.txt` |
| `scripts/apply-integration.ps1` | clones the tag, applies the patches, runs the sync and, with `-BuildApk`, builds the app |
| `scripts/export-patches.ps1` | regenerates `patches/` and `person5-liveness.diff` from a checkout of the branch |

**What the patches leave out.** They do not contain the files that `sync-engine.ps1` copies from
this repository:
- the jars in `android/liveness/libs`;
- the model bundle in `android/liveness/src/main/assets/liveness/models`;
- `CameraXFrameSource.java`.

There are three reasons:
1. These files have a single source of truth here: the engine modules, `models/` (signed) and
   `android-liveness-frame-source/`.
2. Jars are not tracked in this repository (`.gitignore`: `*.jar`).
3. A copy in a patch would go stale.

The patches do contain `android/liveness/engine-sync.txt`. It records the engine commit and the
SHA-256 of every synced file at the time the branch was synced.

**Why two forms.**
- **The series:** it keeps the 35 commits and their messages. That is what reviewers read, and it
  lets someone rebase or cherry-pick onto a newer upstream release commit by commit.
- **The consolidated diff:** it shows the whole change in one file, and applies in one step with
  plain `git apply`, with no commit metadata.
- Both give the same tree. This was checked from a fresh clone: see "Verification".
- `.gitattributes` keeps both byte-exact (`-text`), so Windows line-ending conversion cannot
  break them.

## Prerequisites

The scripts and these steps were tested on Windows 11. On Linux or macOS, the git and Flutter
steps are the same; run the sync steps of `sync-engine.ps1` with `pwsh` or by hand.

| tool | version used |
|---|---|
| git | 2.4x (Git for Windows, which provides Git Bash) |
| Engine build | JDK 11 and Maven 3.9.9 (`sync-engine.ps1` dot-sources `%USERPROFILE%\tools\liveness-env.ps1`, which puts them on PATH for the script only) |
| Android build | JDK 21, Android SDK platform 34 and build-tools 34.0.0, Flutter 3.10.4 (Dart 3.0) |
| Phone | Android 9 or later (minSdk 28), arm64; tested on a Samsung Galaxy S23 FE with Android 16 |

`git am` and `git commit` record you as the committer, so set `user.name` and `user.email` in git.

## Apply

### One command (PowerShell)

```powershell
# Flutter, the Android SDK and JDK 21 on PATH (for example: . $env:USERPROFILE\tools\android-env.ps1)
.\android-integration\scripts\apply-integration.ps1 -Target C:\dev\arc-liveness -BuildApk
```

Options:
- `-Consolidated` uses `person5-liveness.diff` instead of the series.
- `-SkipEngineBuild` reuses the engine jars already built in `liveness-*\target`.

### Step by step

```powershell
# 1. The upstream release
git -c core.longpaths=true clone --depth 1 --branch v1.1.1 https://github.com/mosip/android-registration-client.git C:\dev\arc-liveness
cd C:\dev\arc-liveness
git config core.longpaths true

# 2a. The integration as 35 commits ...
git am --whitespace=nowarn (Get-ChildItem <Mosip26>\android-integration\patches\*.patch | Sort-Object Name).FullName
# 2b. ... or as one change
git apply --index --whitespace=nowarn <Mosip26>\android-integration\person5-liveness.diff
git commit -m "Face liveness integration"

# 3. The engine, the signed models and the camera source from Mosip26
<Mosip26>\android-integration\scripts\sync-engine.ps1 -Clone C:\dev\arc-liveness

# 4. Generated code: Pigeon bridges (Git Bash), then put the iOS files pigeon.sh rewrites back
& "$env:ProgramFiles\Git\bin\bash.exe" -c "sh pigeon.sh"
git checkout -- ios/Runner/pigeon.h ios/Runner/pigeon.m

# 5. Build
flutter build apk --debug                                    # all ABIs, debuggable
flutter build apk --profile --target-platform android-arm64  # realistic timings, not debuggable
flutter build apk --release                                  # signing from android/key.properties, else the debug key
```

In bash, step 2a is `git am --whitespace=nowarn <Mosip26>/android-integration/patches/*.patch`.

**Builds and sizes.**
- The first Gradle build takes a long time (about 20 minutes on a cold cache).
- If a build fails with `OutOfMemoryError: Java heap space` (in dex merging or Java resource
  merging), stop the Gradle daemons (`cd android; .\gradlew.bat --stop`) and build again. A daemon
  reused across many builds can run out of its 1.5 GB heap; a fresh one built the full APK in
  about 2 minutes.
- `--target-platform` removes only Flutter's own libraries. ONNX Runtime still ships for all four
  ABIs, and the release build type lists all four in `abiFilters`.
- The arm64-targeted profile APK of P5-5 was 256 MiB, of which 134 MiB is ONNX Runtime.
  **Distribute per ABI** with `flutter build apk --split-per-abi` (or an App Bundle,
  `flutter build appbundle`). Profile build, fix round 2: arm64-v8a 151.3 MiB, armeabi-v7a
  138.6 MiB, x86_64 159.3 MiB; see the benchmark document, section G.
- The face matcher (F1) is OpenCV Zoo's int8 SFace since `pad-2026.10.5`: 9.9 MB in every APK
  (the fp32 file of fix round 1 was 38.7 MB).
- Debug APK sizes are not comparable between builds: incremental packaging leaves free space in
  the APK when entries are replaced.

**Release builds.**
- `BuildConfig.LIVENESS_DIAGNOSTICS` is false in release builds.
- So the release build has none of the diagnostics: no debug page, no config overrides, no
  benchmark and no model install.

## Install and first run

```powershell
adb install -r --user 0 build\app\outputs\flutter-apk\app-debug.apk
```

On a Samsung phone:
1. Turn off Auto Blocker (Settings → Security and privacy).
2. If Google Play Protect warns that the app was "built for an older version of Android", tap
   More details → Install anyway.
3. Changing security settings revokes USB debugging: allow it again on the phone.
4. If the phone has a Secure Folder, keep `--user 0` in the install command.

Without a MOSIP backend the app stops at the Login page. In debug and profile builds that page
has a **Face liveness demo** button (top right). It opens the demo page: the three real checks, the F15
identity check, the models, the diagnostics and the simulated screens (`docs/part5-android-integration.md`,
section 9). Its last row, **Developer tools**, opens the debug page. From the debug page you can:
- run sessions with the real or the fake engine;
- run every hook through `LivenessGate` (the "Hook gates" buttons);
- run the analyzer benchmark;
- read and reset the persisted metrics;
- install or roll back a signed model bundle staged with `adb push` in
  `/sdcard/Android/data/io.mosip.registration_client/files/bundle-staging/`;
- run the face binding test (F1): a real gate, then one camera still (JPEG, or JPEG 2000 as an SBI
  sends it) compared with the gate's best frame; and the matcher benchmark (timings of the
  bundled models and of each signed test bundle in `files/bench-bundles/`);
- check for an online model update now, from an index URL. Debug and profile builds may use plain
  HTTP to 127.0.0.1, so a laptop can serve a packed test bundle through
  `adb reverse tcp:8000 tcp:8000`. "Stop model updates" clears the URL again;
- switch the app's language ("UI:" menu), for checking the liveness texts in Arabic, French, Hindi,
  Kannada and Tamil.

The debug page never shows camera images.

**Upgrading a phone that installed a model bundle.** The store keeps installed bundles across app
updates. A four-model bundle installed before F1 (for example the P5-5 test's `pad-2026.10.1`) no
longer verifies against the five roles. The engine ignores it, because it is older than the
bundled bundle (`pad-2026.10.5`). Since fix round 2 the next model install first installs the
bundled bundle as the rollback base and removes every older bundle, so a rollback lands on the
bundled version.

## Verification

1. **Same tree as the branch.** This applies when the integration branch is available. Fetch it
   into the new checkout, stage everything and compare:
   ```powershell
   git fetch <path or URL of the checkout with person5-liveness> person5-liveness
   git add -A
   git diff --cached --stat FETCH_HEAD
   ```
   In the P5-6 check from a fresh clone, there was no difference at all: the index tree hash
   equalled the branch's, jars included, and the consolidated diff gave the same tree as the
   series.

   If the engine jars are rebuilt, they get new bytes, even from unchanged sources. Each jar holds
   a `git.properties` with the Mosip26 commit checked out at build time and the build time, so a
   build from a later commit recreates it. The jars and their SHA-256 lines in `engine-sync.txt`
   then differ, and nothing else does; the `engine commit` line names the last engine change and
   stays the same. A second run of `apply-integration.ps1`, made after a rebuild at a later
   Mosip26 commit, showed exactly this.

   For a byte-identical result, use `sync-engine.ps1 -SkipBuild` with the jars that were synced
   into the branch.
2. **Tests** (counts as of 2026-10-28):
   - `cd android; .\gradlew.bat :liveness:testDebugUnitTest`: 115 tests, all passing. The face
     binding and identity binding tests run the real matcher: the module's unit-test classpath swaps in the desktop
     ONNX Runtime (`android/liveness/build.gradle`).
   - `flutter test test/liveness`: 302 tests, all passing (the 20 screenshot tests are skipped unless
     `LIVENESS_SCREENSHOTS` names a folder).
3. **On a phone:** open the debug page (**Face liveness demo**, then **Developer tools**) and run:
   - one "Resident face" hook gate with the fake engine, which needs no camera;
   - one "Resident face" hook gate with the real engine, looking at the camera;
   - the face binding test: "1. Binding gate", then "2. Capture still and bind" with the same
     face in view, which gives MATCH;
   - the offline model install (F17): "Face liveness models (production page)", then Install
     model bundle with a signed test bundle zip in Downloads, then Roll back model bundle;
   - identity binding (F15), in the demo page's "Identity check" card (or the debug page's
     "Identity binding test (F15)" card): the operator enrolls once ("1. Enroll the operator's face
     (once)"), checks themself ("Same person"), a teammate checks with "2. Check identity
     (anyone)" ("Different person – refused"), then delete the test face. The verdict is the
     panel in the card, not the face check's "face captured" screen before it.

## Fonts (task 21)

The app bundles one typeface for its six languages (patch 0054): Noto Sans, with Noto Sans Arabic, Devanagari,
Kannada and Tamil as fallbacks. Static unhinted TTFs from
[notofonts/notofonts.github.io](https://github.com/notofonts/notofonts.github.io) at commit
`c1d3718e2a0eb25c2567a694ee017061fdaa5f51` (`fonts/<family>/unhinted/ttf/`), in the client's `assets/fonts/`.
Licence: SIL Open Font License 1.1, in `assets/fonts/OFL.txt` (the five families' copyright lines and the OFL text,
the same for all five, from google/fonts `ddf386ba1b9fbc6b2c818f1ddcd9148f54ced94b`); the app lists it on its
licence page.

| File | Bytes | SHA-256 |
|---|---|---|
| `NotoSans-Bold.ttf` | 432,376 | `87cb2d84472a7d66da659ee47b6cdb9552326e8c128245231f191b6ac72529d9` |
| `NotoSans-Medium.ttf` | 431,176 | `220e74546aee1b9a17cd50025dd917a064d0224e653f562287446c2328e4160b` |
| `NotoSans-Regular.ttf` | 431,364 | `f3961a9cde016d41a4879aecda1474d3a36d6bf54fa0e4643de029cc2248b0e8` |
| `NotoSans-SemiBold.ttf` | 431,500 | `a7af8d4a11e8e49fea9a19786a6469210b512cd4061413e8036bdd2425722795` |
| `NotoSansArabic-Regular.ttf` | 142,140 | `bd86ca02f087d7f3c3788ba458fb6b73744c7639ed276b8d870dba6def6c40d0` |
| `NotoSansArabic-SemiBold.ttf` | 141,856 | `36482b7e28267a3ea73eb6b86cf697747b5fbe272a9692df739ded695773642a` |
| `NotoSansDevanagari-Regular.ttf` | 184,228 | `9c7d935139ea6a1e6ad9dbac4f6d27ece1e04bca8123c8888d00a0f9df4724cd` |
| `NotoSansDevanagari-SemiBold.ttf` | 183,996 | `efaa7d3f82ef6898e3fa79101f0d2b5ab9a25554296044123b402b7b571311b9` |
| `NotoSansKannada-Regular.ttf` | 135,488 | `2870274619c2a4abe5d6b11fcb7c4a372eba765b6b7881d459b0eb0a609a68eb` |
| `NotoSansKannada-SemiBold.ttf` | 133,860 | `0fbd50bbab33fb6329a840019f1fc8b60e288a8a61c199e08b9d29b8c05ae98b` |
| `NotoSansTamil-Regular.ttf` | 46,600 | `6634d9cc97a726e670df41281dd32b167a6b9b71f2036e19671ff08fdde0c292` |
| `NotoSansTamil-SemiBold.ttf` | 46,400 | `834cfefd9865d9a74e650b21455c172b7078fcbe32215ac0f7b869111fcd7384` |

## Regenerate the patches

After committing to the integration branch in the client checkout, run this from the root of
this repository:

```powershell
.\android-integration\scripts\export-patches.ps1 -Clone $env:USERPROFILE\dev\android-registration-client
```

It rewrites `patches/` and `person5-liveness.diff`, and checks that no synced file is included.
Commit the result together with the client change it reflects. When the engine changes:
1. Run `sync-engine.ps1` first.
2. Commit the new jars and `engine-sync.txt` in the client.
3. Export.
