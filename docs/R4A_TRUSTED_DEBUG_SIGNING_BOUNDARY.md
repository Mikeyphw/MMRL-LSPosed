# R4A — Trusted debug signer continuity (not release signing)

**Base:** `Mikeyphw/MMRL-LSPosed` `main` commit `2f8dd6f89fedb01d27eb9cc5f43faa8ed6952694`, after merging R3C PR #7.

## R4A implements

- Adds an independent **read-only trusted-main verification** inside the existing `attest-signed` job, *before* the existing `actions/attest` action. It first checks out exact source and retrieves that **same run/attempt's** R3C four-file Devtool evidence alongside the existing signed-debug archive.
- Offline verifier `scripts/ci_release_boundary.py` independently reconciles exact `push`/`main` repository, source, run, attempt, Devtool pin, graph and node identities; requires the R3C portable producer/consumer consistency contract; verifies the pre-sign and post-sign APK SHA-256 and sizes; checks the immutable Devtool native handoff; and matches the debug APK signer fingerprint to committed `config/debug-signing.json`.
- Fail closed for mismatched/modified APKs, signer identities, unsafe files, duplicated JSON keys, debug identity changes, version changes, forged release/device/hook claims and mismatched Devtool inputs. Do not print secrets or local credentials.
- Adds offline negative-case regressions and detailed bounded trust-model documentation.

## Explicitly NOT delivered

- **No production release APK**, no release keystore creation, no new signing secrets, no change to Android `release` build variant or app package ID.
- No independent verification of the APK's cryptographic signature in this Python step. The existing hosted signer (`AndroidSigningManager` and `apksigner`) does that before upload; GitHub's existing OIDC attestation action still executes separately after this new evidence check.
- No physical install, update/rollback continuity test, lower-API/Android 16 device matrix, hook verification, formal SBOM, changelog or release publication. These are subsequent R4 gates.
- The Devtool portable JSON contract is **self-reported evidence consistency**, not a standalone trust root or an attestation.

## Acceptance

1. PR source contracts, debug APK, Android lint, JVM, and API 36 emulator must pass in GitHub Actions. PR builds never obtain GitHub signing secrets or execute the main-only attestation job.
2. After merge, a **trusted-main push** must pass `Verify trusted debug signer continuity and reject release claims` before GitHub's `actions/attest` stage and upload its source-bound artifacts. An empty/skipped main signing job is **not** an acceptance pass.
3. A physical APK install/upgrade continuity test, dedicated non-debuggable release signing policy, and independent release qualification remain unresolved until separately implemented and tested.

Do not treat source+hash matching as proof that a downloaded module's publisher or APK signer is trustworthy.
