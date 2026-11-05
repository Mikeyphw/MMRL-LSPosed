# R4C — Source-bound release-candidate device trial and attempted restoration

**Status:** implementation candidate. **No release certificate is enrolled**, and neither a
production candidate nor an R4C physical acceptance has been demonstrated. The prior R4B
signing policy remains `enrollment: pending`. This code must not be used with the debug-signed
APK; it requires an independently enrolled, distinct release certificate at the exact candidate
source commit.

This phase is intentionally **not** a production release, automated deployment, root or Vector
operation, or a guarantee of working downgrade/data restoration. The R4C script never invokes
`adb uninstall`, `pm clear`, `reboot`, `su`, KernelSU, Vector or LSPosed actions. No device
command runs from GitHub Actions. The app itself and its existing signing workflows are unchanged.

## Preconditions

1. Merge and qualify R4B on `main`. Verify the `R4B Isolated Release Signing Candidate` workflow
   with `sign_candidate=false` and no secrets accessed.
2. Independently create a *distinct* release PKCS#12 certificate, back it up off-repository,
   record its fingerprint in `config/release-signing.json` in a separate reviewed commit,
   configure GitHub Environment protections/required reviewers, and enroll the three environment
   secrets. Never store signing secrets in the repo or this acceptance kit.
3. On `main`, explicitly dispatch the **R4B** workflow with `sign_candidate=true` only after
   these protections are in place. It produces **unqualified** candidate APK + receipt.
   Confirm the exact GitHub run ID, attempt and source SHA from the successful signed candidate;
   verify the provenance independently through GitHub before importing anything.
4. Obtain the `mmrl-lsposed-r4b-unqualified-release-candidate-<run>-attempt<attempt>` archive,
   independently check its GitHub artifact SHA-256, and safely extract the two files into a
   private directory: `MMRL-LSPosed-release-candidate.apk`, `release-candidate.json`.
   Do not execute ZIP members. The R4C CLI does **not** claim the GitHub artifact digest
   establishes producer identity.
5. Have an **already installed production-signed baseline** with the same *release* certificate,
   and a byte-for-byte backup of its **base APK** on the host. Back up app data separately.
   **The existing debug app (`.debug` package) is not a valid baseline.** If no baseline is
   installed, R4C intentionally refuses the trial rather than auto-installing or uninstalling.
6. Use a disposable/consenting physical device; Android API >=26, one connected ADB serial,
   `aapt` and `apksigner` from an independently verified Android SDK. The CLI refuses split APK
   installations because a single base APK cannot represent a complete rollback set.

## Commands (examples only; change placeholders)

All commands run locally (Termux host with ADB, or a trusted workstation), **not** in GitHub
Actions. Run in the existing checkout (`~/Code/MMRL-LSPosed`), after fetching `origin/main`:

```zsh
cd "$HOME/Code/MMRL-LSPosed"
git fetch origin main
candidate="$HOME/r4c-private/candidate"
baseline="$HOME/r4c-private/installed-baseline.apk"
serial='localhost:5555'  # example, choose an explicitly authorized device
source='<exact-40-hex-R4B-candidate-GITHUB_SHA>'
run='<candidate-GITHUB_RUN_ID>'
attempt='<candidate-GITHUB_RUN_ATTEMPT>'
aapt='/path/to/reviewed/android-sdk/build-tools/36.0.0/aapt'
signer='/path/to/reviewed/android-sdk/build-tools/36.0.0/apksigner'
opts=(--root "$PWD" --candidate-dir "$candidate" --source "$source" \
      --run "$run" --attempt "$attempt" --aapt "$aapt" --apksigner "$signer")

# Strict local verification; no ADB command.
python3 scripts/ci_device_acceptance.py verify "${opts[@]}"

# Read-only device inspection; adb pull copies installed base APK to temporary *host* storage.
python3 scripts/ci_device_acceptance.py plan "${opts[@]}" \
  --serial "$serial" --baseline-apk "$baseline" \
  --plan-file "$HOME/r4c-private/plan.json"
```

**Do not run `trial` casually.** It is a real `adb install -r` of a *higher-version*
non-debuggable candidate. An Android downgrade back to baseline can be refused. On-device
application data can change irreversibly after an upgrade. A baseline APK is not a data
backup. R4C does not modify device settings or capture data backups. Only proceed if you
personally have a verified data backup and accept the potential inability to restore.

```zsh
# Explicit operator mutation, only after independent physical-device approval.
python3 scripts/ci_device_acceptance.py trial "${opts[@]}" \
  --serial "$serial" --baseline-apk "$baseline" \
  --plan-file "$HOME/r4c-private/plan.json" \
  --journal-file "$HOME/r4c-private/trial-journal.json" \
  --ack-device-mutation --ack-backup-confirmed --ack-downgrade-may-fail

# Optional separate attempt to restore baseline; Android may refuse this downgrade.
python3 scripts/ci_device_acceptance.py restore "${opts[@]}" \
  --serial "$serial" --baseline-apk "$baseline" \
  --plan-file "$HOME/r4c-private/plan.json" \
  --journal-file "$HOME/r4c-private/trial-journal.json" \
  --ack-backup-confirmed --ack-downgrade-may-fail
```

## Acceptance semantics and failure handling

- `verify` checks Git-tracked release policy **at the exact candidate source SHA** (reachable
  from the expected `origin/main`), distinct non-debug signing identity, R4B receipt, source/run/
  attempt, APK hash, version, non-debuggable package and actual `apksigner` verification.
  It does not query GitHub, so separately review hosted run provenance and the artifact ZIP.
- `plan` is read-only on the device. It checks the installed APK path, API and model, pulls
  the installed base APK, verifies its **bytes** and release certificate against a supplied
  baseline, and requires a strictly higher candidate version. A changed device or stale
  plan is refused before mutation.
- `trial` is **opt-in**. It creates an exclusive journal before `adb install -r`, then
  independently reads back the installed APK. It records hash-level smoke evidence only.
  It never claims UI, hook, data, device or release qualification.
- `restore` is a **separate opt-in** attempted `adb install -r -d` and then an installed-base
  rehash. Non-debuggable production APK downgrades are commonly disallowed; the journal
  records failure if Android refuses, and the operator must arrange manual recovery.
  It never uninstalls, clears data, uses root or silently reports success.
- `BASELINE_APK_RESTORED_NOT_DATA_VERIFIED` means that the APK bytes returned to the
  known baseline. It **does not prove app data rollback**, compatibility or functional safety.
- `R4C` acceptance remains **pending** until an operator independently completes signed
  candidate provenance verification, data-backup confirmation, an authorized physical trial,
  UI and compatibility checks, any desired restoration, and the separate R4D release gate.

The local source-contract suite uses fake APKs and mocked ADB; **passing it never
substitutes for hosted Android CI or real-device signing/upgrade tests**.
