# S09 — GitHub Actions non-root Android instrumentation gate

## Scope and evidence boundary

GitHub-hosted Ubuntu launches an Android 16/API 36 x86_64 AVD and runs the
`S09EmulatorUiTest` Compose UI test class. The emulator is a **real Android
runtime**, but is not a physical device running Vector. It verifies the app
starts disconnected, search/navigation/settings do not cause privileged
connections, and critical UI paths exist.

This job intentionally **does not** execute `S08ReadOnlyDeviceTest` or
`S08LabWriteDeviceTest`. It does not request root, change scopes/modules,
assert live hook execution, or invalidate the accepted physical S08 test.

The same GitHub run must produce the APKs; the CI job verifies immutable
source SHA, run ID, attempt, APK SHA-256, and the signed pair receipt before
installation. On trusted main, it uses the dedicated stable-signed APK and
AndroidTest pair; on pull requests, an ephemeral same-run Gradle debug pair is
used without access to stable signing secrets.

## Pipeline

1. Native Devtool builds debug APK, lint, Vector JVM unit tests.
2. On main, stable signer signs the app; S08 lab signing produces a same-run
   instrumentation test APK plus immutable receipt. On PR, a separately
   staged ephemeral APK/test APK pair receives a source-bound receipt.
3. `emulator-instrumentation` (depends on `hosted-debug`) downloads the exact
   pair, verifies hashes and source, enables KVM, runs
   `ReactiveCircus/android-emulator-runner` pinned to commit
   `a421e43855164a8197daf9d8d40fe71c6996bb0d` and API 36.
4. Only class `io.github.mikeyphw.mmrl.lsposed.S09EmulatorUiTest` is invoked
   through AndroidJUnitRunner. All four tests must pass, and failures or
   contradictory JUnit exit codes are rejected.
5. Upload `mmrl-lsposed-emulator-api36-<run>-attempt<N>` with a preflight
   receipt, AndroidTest log, and emulator test JSON.

**A successful APK build is not an emulator test pass.** The entire workflow
is considered successful only if the emulator job passes. The main-branch
GitHub attestation job now depends on successful emulator instrumentation,
while the APK artifacts remain explicitly non-release and not device-qualified.
The physical S08 acceptance gate retains its separate meaning.

## Local Termux usage

No Gradle or emulator commands run in Termux. Apply the source-pinned overlay,
run Python contract tests and push. Follow GitHub Actions. Continue using the
existing S08 physical-device acceptance runner for real root IPC and guarded
writes. Never attempt S08 mutation on GitHub Actions emulators.

## Future expansion

When S09/S10 Apps, Discover and Downloads are implemented, expand this
non-root emulator suite to test loaded/retry/offline states and artifact
verification dialogs. Do not replace root/Vector device tests with fakes.
