# S08 — Physical instrumentation and reusable disposable Xposed lab

## Origin and scope

Ported from `Mikeyphw/MMRL` Phase05/Phase06 R8 test assets at source
`7312cc03587cf60ef0c7010810b012d677ee5f1f` (GPL-3.0) into the independent
MMRL-LSPosed app. Lab namespace is deliberately different:

- `com.mikeyphw.lsposed.hooklab.module`: API 102 modern Xposed module; static scope contains **only** the lab target
- `com.mikeyphw.lsposed.hooklab.target`: probe ContentProvider with `ORIGINAL_LSPOSED_S08` baseline and optional `HOOKED_LSPOSED_S08` in a re-started scoped process
- `io.github.mikeyphw.mmrl.lsposed.debug.test`: AndroidJUnitRunner test APK; it calls the standalone app's real `VectorMutation` and `VectorReadOnly` APIs

This milestone qualifies **saved module configuration writes**, not proof of hook
execution. A separate, restart-aware probe test may later check ORIGINAL → HOOKED → ORIGINAL.

## GitHub build

The existing Devtool APK, lint, JVM test, stable signing and provenance steps
remain in place. On a trusted `main` push, the same workflow additionally builds
`:app:assembleDebugAndroidTest`, `:hook-lab-module:assembleDebug`, and
`:hook-lab-target:assembleDebug` **on GitHub only**. It signs all three test APKs
with the same stable debug certificate as the app, checks package IDs and the
modern Xposed static scope, and publishes
`mmrl-lsposed-s08-lab-RUN_ID-attemptN` plus a receipt with exact APK hashes.
GitHub attests the three test APK bytes separately. No CI runner claims root or
device acceptance, and no secret is exposed to pull-request builds.

## Device process (Termux zsh)

First get the successful GitHub run ID. Install the *matching* stable-signed
`MMRL-LSPosed-debug-signed.apk` manually. The host will **not** overwrite or
uninstall it. A previous run's AndroidTest APK may need manual removal or
explicit review if its bytes differ; the host refuses surprises.

```zsh
cd ~/Code/MMRL-LSPosed
python3 scripts/s08_device_lab.py RUN_ID localhost:5555
```

This runs the **read-only** instrumentation first and downloads/checks the
matching lab pair. It only installs missing, uniquely named lab and AndroidTest
APKs, after verifying every already installed package. It never alters saved
Vector state in default mode.

For the reversible write test, on a physical Android device with working root
and Vector 2.2/API 102, after reviewing that the lab module is disabled and has
no scope or only its one approved target:

```zsh
python3 scripts/s08_device_lab.py RUN_ID localhost:5555 --authorize-lab-changes
```

Each invocation creates a new numbered evidence session. The host fsyncs an independent journal **before** it places a one-time ticket
in app-private storage, then runs exactly the named test class. The instrumentation
API performs one ENABLE, one target scope toggle, restores the scope and then
DISABLES; every step uses S08's saved-state preflight, mutation journal, daemon
acknowledgement and read-back. The host independently checks restored lab scope,
module disabled state, and all unrelated modules' saved-enabled state. The test
never executes the write class when no authorization is present.

### Recovery

If a run aborts with `S08_RECOVERY_REQUIRED`, **do not rerun writes**. Keep the
printed `host-recovery-journal.json` and use:

```zsh
python3 scripts/s08_device_lab.py RUN_ID localhost:5555 \
  --authorize-lab-changes \
  --recover-journal /EXACT/PATH/TO/host-recovery-journal.json
```

This host recovery is limited to the known lab module and its one known target,
verifies that unrelated module states are unchanged, restores scope before the
final DISABLE (Vector 2.2 may implicitly ENABLE during scope ADD), and refuses
unknown scope entries. If the standalone app retains an uncertain S08 journal,
use its in-app **read-only reconciliation** after host restoration and confirm
before any further app writes. No mass restoration, `scope set`, `pm clear`,
uninstall, reboot, or other module alteration is authorized.

> **Important:** A successful hosted build alone does not qualify S08 physical
> writes; only an independent `PASS_LAB_WRITE_AND_RESTORE` receipt on the device
> and a matching host `RESTORED` journal do that. The original MMRL lab and MMRL
> repository are untouched.
