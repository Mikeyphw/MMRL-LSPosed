# MMRL-LSPosed — implementation and acceptance status

Updated November 3, 2026. Source of truth for milestone progress; the separate roadmap includes historical baseline notes.

## S01–S02: standalone app, Devtool hosted pipeline and stable signing

Implemented and verified with GitHub Actions. The app is independent from MMRL and uses
`io.github.mikeyphw.mmrl.lsposed` (`.debug` variant). Hosted builds are run through
pinned Devtool source. GitHub Actions uses persistent debug signing and publishes exact
APK checksums, source receipts, JVM tests, lint evidence and GitHub provenance attestations.

## S04–S07: read-only Vector adapter and draft scope UX — accepted

**Hosted evidence:** [successful run 41720288365](https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/41720288365)
for source SHA `5e8672ebf728490fd008b53bc2ac46919dc30c2f`. Android APK/AIDL,
Lint, Kotlin JVM protocol tests, stable signing and GitHub attestation all passed.

**Physical-device acceptance:** The maintainer explicitly reported on-device verification of
S04–S07 as working on November 3, 2026. Record this as **maintainer-verified** rather than
falsely claiming an independently captured automated device-test receipt. This closes the
read-only milestone for roadmap progression. No active hook-injection verification was claimed.

## S08: guarded configuration changes — implementation candidate

Introduces one-by-one saved module enable/disable and scope add/remove operations with:
- explicit confirmation, fresh pre-state and scope comparison, fixed root commands;
- user-0 installed non-system target rules, static-scope boundaries and refusal to implicitly
  enable a disabled module while adding scope;
- synchronous write-ahead journal, immutable-ish bounded receipts, read-back validation,
  and an explicit two-step reconciliation screen for unknown outcomes;
- no automatic retry, bulk scope replacement, rollbacks, or silent background mutation.

**Pending:** fresh hosted `main` build/test/sign/attest, followed by positive/negative tests
using *only disposable hook-lab module and target packages*. Verify exact restoration of
saved module state and scopes. A successful hosted build alone does not qualify S08 device writes.

## Next milestones

S09: Apps reverse lookup, module/app-target relationship maps and manager polish.
S10: verified catalogs, downloads and GitHub artifact viewer.
S11: release hardening and independent release signing qualification.

Devtool federation coordination continues separately without rewriting MMRL's main fork.
