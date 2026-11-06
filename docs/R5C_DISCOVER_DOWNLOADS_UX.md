# R5C — Discover and Downloads UX

A focused everyday-usage improvement on merged R5B `main` `d5084c54ba3a260b873397d7d50876d24f421177`.

## Changes

- **Discover** shows valid cached Vector catalog data immediately, retains its bounded online refresh and offline notice, and now renders search/sort results in pages of 80, with **Show more modules** until all filtered results can be browsed. The earlier fixed 250-row display limit is removed. Search has a clear control, explicit zero-match state, and narrow-screen horizontal sorting controls. Search and sort are purely local and never trigger root or network requests.
- **Module cards** show summary (up to three lines), catalog stars, advertised version and the date string if available; version metadata is **not** a verification of compatibility or update signing.
- **Downloads** provides separate, persistent local text filters for GitHub releases (including asset filenames) and CI runs (ID, commit prefix, status and event). Selecting a run exposes its artifacts before the longer run list, with a separate artifact-name/ID filter and clear controls. Filter empty states are explicit. No search requests are sent to GitHub.
- A new pure `EcosystemPresentation` helper provides case-insensitive multi-term matching and bounded incremental display. JVM tests exercise the helper; non-root Compose emulator assertions exercise search clear controls and tab navigation.

## Unchanged safety and scope

- No Vector root access, scopes, permissions, module enable/disable, APK installation, automatic update, download trust expansion, or GitHub token changes.
- Existing `VerifiedDownload` and `CiArtifactDownload` SHA-256/size, source/run/attempt checks, archive path validation, cancellation and FileProvider boundaries remain untouched.
- The repository remains manually user-driven for GitHub fetches and downloads. A hash or successful CI run **never** qualifies APK signing trust.
- R4D-02 is **not** part of this work.

## Acceptance

1. Source contracts and pure Kotlin model tests pass locally, followed by real hosted Devtool Android compilation, lint, JVM and API 36 non-root Compose instrumentation.
2. On the physical device, test a cached-offline Discover session, search/reset and pagination with a sufficiently large catalog. In Downloads, test release and CI-run filters, selected artifact filtering and tab switching.
3. Download tests must use disposable files; do not install unknown APKs or run privileged scope changes for this UI slice.

Passing hosted tests does not establish physical-device visual acceptance.
