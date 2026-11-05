## R3B — Authenticated GitHub Actions ZIP custody (source candidate)

- Adds user-initiated ZIP download with fresh run/attempt and artifact metadata checks,
  Android Keystore GitHub token confined to `api.github.com`, bounded storage-host
  redirect, published ZIP SHA-256 and size check, and private receipt persistence.
- Refuses stale attempts, missing digest, unsupported event, expired artifacts and
  unsafe ZIP paths, duplicates, excessive entry sizes or invalid archives.
- Saved ZIP is rehashed before use; optional extraction writes **only small text
  evidence** into app-private storage. No APK extraction, installer, Vector root
  operation or signing change. API-reported hash is NOT independent attestation.
- Hosted Android/JVM/emulator and physical-network acceptance still pending.
  See `docs/R3B_AUTHENTICATED_CI_ARCHIVES.md`.

## R3A Devtool federation and CI artifact evidence (candidate, 2026-11-05)

- R2C remains PR-CI accepted; R3A must be applied only after R2C merges to `main`.
- Adds source/attempt-aware CI artifact inventory with a current-only default and all-attempts override. Metadata categorization never claims signer or provenance validation.
- Adds an unsigned, source-checked `federation-index.json` to existing hosted S01 Devtool evidence artifacts, preserving GitHub run/attempt, Devtool SHA, graph SHA, node identities, and debug-only qualification.
- **R3A is unqualified until hosted CI, API36, trusted-main post-merge, and physical UX checks pass.** See `docs/R3A_DEVTOOL_ARTIFACT_FEDERATION.md`.

## R2C — source-bound release downloads (candidate, November 5, 2026)

- Based on merged R2B `main` `5e7e14526e768b9d7e481ff8c7cb73191aa5fb98`.
- Exact first-party GitHub release/tag/asset URL binding and v2 downloaded-file receipts; existing two-field receipts stay accessible but marked legacy hash-only.
- File hash/receipt revalidation before external viewer handoff; no APK signing/provenance or automatic install claims. No root/Vector, signing, Devtool or CI workflow changes.
- **Not accepted until hosted Android/Devtool/JVM/lint/API36 CI and physical UX checks pass.** See `docs/R2C_RELEASE_DOWNLOAD_IDENTITY.md`.

## R2A candidate — conditional Vector catalog caching (2026-11-05)

- R1B screenshot-inspired Modules and local repository sorting merged via [PR #1](https://github.com/Mikeyphw/MMRL-LSPosed/pull/1), merge commit `9caef214d4f6770e1cc64d367cc3092633c5c0ae`. The PR workflow [42053252042](https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/42053252042) passed including the API 36 four-test non-root UI gate. Trusted merge-to-main signing/provenance still requires separate verification.
- R2A adds an ETag/`If-None-Match` validator to the public Vector catalog, paired with an exact cache-body SHA-256 receipt. Untrusted or invalid metadata cannot authorize a conditional request; `304` requires a validated, already-parsed saved body. `200` must still parse and meet existing size/host limits before cache replacement.
- Offline, DNS or HTTP failure retains the last valid local catalog. Search/sorting are still entirely local; networking remains user-driven, and no GitHub token or Vector write operation is involved.
- R2A is a **source candidate**, not device/release qualified until hosted CI, new JVM cache tests and physical offline/online checks pass. See `docs/R2A_CONDITIONAL_CATALOG_CACHE.md`.

## R1B consolidated candidate — screenshot-inspired Modules and sortable Vector catalog (2026-11-04)

- Supersedes the **unapplied R1A overlay** and retains its profile-aware Apps saved-scope index and tests.
- Adds compact status-grouped module rows with real APK icons, names, descriptions, version, API and saved-scope counts, search/filter/sort, refresh, and Discover shortcut.
- Adds cached/offline Vector catalog ordering by release time, stars and name (six options), without a network request per sort.
- Saved Vector enablement is not proof of live hooks; the Modules update banner is navigation only, not an unverified update count.
- New code is **UNQUALIFIED until GitHub Actions JVM/lint/API36/provenance and physical UI checks pass**. No Vector write paths or signing changes.
- Full details: `docs/R1B_MODULES_UI_AND_CATALOG_SORTING.md`.

> **2026-11-04 S11 acceptance update:** Signed debug APK from hosted run
> [41832873086](https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/41832873086)
> (source `6c89740f86fb750ad9dc108f02065719314a7595`) matches the
> installed APK on `localhost:5555` byte-for-byte (SHA-256
> `028973ba6cff2aed68b553d3a60b88777aa1992f4d34f18f8ec645e41387517c`).
> The maintainer reports that auto/manual Vector connection, direct-scope UX,
> Discover, GitHub settings, Apps, and Downloads all worked on the phone.
> **S11 manual functional acceptance passed.** This is not an independent S11
> root-write restoration transcript, hook verification, or release qualification.
> See `docs/S11_DEVICE_ACCEPTANCE.md`.

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

## R2B candidate — conservative catalog-vs-installed version hints (2026-11-05)

- Depends on R2A PR #2 and its successful hosted PR run `42071031874`.
- Implemented source candidate: cached catalog only, strict numeric comparison of installed package versions against the corresponding public Vector catalog entry, safe newer-version hints, and contextual module-detail information.
- **No update-install, publisher-verification or runtime-hook claim.** Numeric comparison is not proof of APK signing continuity, compatibility or trust. Unclear/prerelease tags require manual review.
- Root APIs, write journal, signing and GitHub Actions workflows remain untouched.
- Physical-device R2B UX, hosted CI and trusted-main signing are **not yet qualified**. See `docs/R2B_CONSERVATIVE_MODULE_VERSION_COMPARISON.md`.
