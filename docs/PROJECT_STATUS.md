> **2026-11-04:** S09+S10 hosted build, JVM, API 36 emulator, signing and provenance
> passed in [run 41770774354](https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/41770774354)
> at source `15ed223b6f1e9bd3d07ce74a2ecedcef1eef99e9`.
> Physical UI screenshots exposed scope-review and generic GitHub search usability issues.
> An S11 candidate replaces drafts with direct per-app verified writes, adds one-shot
> read-only Vector auto-connect, uses the actual Vector LSPosed catalog and provides
> encrypted optional GitHub API token settings. See
> `docs/S11_DIRECT_SCOPES_VECTOR_CATALOG_AUTH.md`. Hosted/physical S11 qualification pending.

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

## S08: guarded configuration changes — device accepted

Hosted build, lint, JVM tests, persistent debug signing, instrumentation/hook lab artifacts,
and attestation passed. The maintainer's physical device reported both
`S08_DEVICE_READ_ONLY_PASS` and `S08_LAB_WRITE_AND_RESTORE_PASS` for
run `41743515595`, `session-0007`. Host recovery journal recorded exact
restoration of disposable module/scope state. No general hook-execution claim.

## S09 emulator foundation — hosted accepted

[GitHub run 41759587780](https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/41759587780)
on source `2a60250e5293d864b82446afde0346a70274b96a` executed four real non-root
Compose instrumentation tests on an Android API 36 emulator, and all signing/provenance jobs passed.

## S09+S10 functional UI — implementation candidate

Apps reverse lookup, module sorting and filters, framework details,
user-driven GitHub Discover, release download verification, private saved-file history
and CI artifact metadata are now implemented as a
source-pinned candidate. See `docs/S09_S10_CONSOLIDATED_FEATURES.md`. Hosted Android
compilation and physical feature acceptance **not yet performed**; do not mark these
functions accepted before their new CI and device checks.

## Next milestones

S09: feature UI implemented as candidate; physical scope-index comparisons pending.
S10: GitHub metadata, verified first-party release download and artifact viewer candidate;
no authenticated in-app CI archive downloading or APK signer qualification yet.
S11: release hardening and independent release signing qualification.

Devtool federation coordination continues separately without rewriting MMRL's main fork.
