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
| `patches/0001-*.patch` … `0027-*.patch` | one patch per commit of the integration branch, in order, for `git am` |
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
- **The series:** it keeps the 27 commits and their messages. That is what reviewers read, and it
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

# 2a. The integration as 27 commits ...
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
- The arm64 profile APK was 256 MiB, of which 134 MiB is ONNX Runtime. Distribute per ABI (an App
  Bundle, or `splits { abi }`); see the benchmark document.
- The SFace face matcher (fix round 1, F1) adds 38.7 MB to every APK. The debug APK is now
  488,250,639 bytes.

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
has a **Liveness test** button (top right), which opens the debug page. From there you can:
- run sessions with the real or the fake engine;
- run every hook through `LivenessGate` (the "Hook gates" buttons);
- run the analyzer benchmark;
- read and reset the persisted metrics;
- install or roll back a signed model bundle staged with `adb push` in
  `/sdcard/Android/data/io.mosip.registration_client/files/bundle-staging/`.

The debug page never shows camera images.

**Upgrading a phone that installed a model bundle.** The store keeps installed bundles across app
updates. A four-model bundle installed before F1 (for example the P5-5 test's `pad-2026.10.1`) no
longer verifies against the five roles. The engine ignores it, because it is older than the
bundled `pad-2026.10.4`. The next model install keeps it as the rollback target; a rollback to it
fails with `MODEL_INTEGRITY_FAILED` and changes nothing. A second install prunes it.

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
2. **Tests:**
   - `cd android; .\gradlew.bat :liveness:testDebugUnitTest`: 73 tests, all passing.
   - `flutter test test/liveness`: 56 tests, all passing.
3. **On a phone:** open the debug page and run:
   - one "Resident face" hook gate with the fake engine, which needs no camera;
   - one "Resident face" hook gate with the real engine, looking at the camera.

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
