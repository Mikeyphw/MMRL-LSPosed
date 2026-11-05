# R1B — Modules screen and sortable Vector repository

Source-locked base: `Mikeyphw/MMRL-LSPosed/main` at `6c89740f86fb750ad9dc108f02065719314a7595`. This is **one consolidated overlay** including R1A's profile-aware Apps index; it replaces the *unapplied* R1A candidate and must not be layered on old overlays.

## Modules

The Modules page is inspired by the maintainer's supplied Vector screenshot: large Modules header, saved enabled/total count, refresh and repository shortcuts, rounded search with filter toggle, update-browsing banner, grouping, real installed APK icons, description, version, format/API labels and last-read saved scope counts. Filter by installed module type, saved Vector status, search terms (name, package, description, version), and sort per group (name A–Z/Z–A, API high first, package).

**No fictional live hook or update claims:** Vector enabled/disabled status always means **saved configuration**. The repository shortcut never claims a particular update is available without version verification. An unconnected Vector groups discovered APKs as *Installed modules* rather than claiming disabled status. No new root queries or mutations occur from rendering this page.

The app's existing debug identity, module detail page, guarded scope writes, journal, recovery and exact read-back remain unchanged. `Browse updates` routes to Discover. Search, sort and filter selections remain UI-only and are preserved across ordinary Compose recomposition and configuration recreation.

## Discover

The actual Vector LSPosed repository is now sortable by newest/oldest release timestamp, most/fewest stars, and module name A–Z/Z–A. Sorting is deterministic (case-insensitive with package-name tie-breakers), performed locally on filtered results, and also works offline with the verified cached repository data. No API calls are fired by changing sort. Refresh remains an explicit action.

## R1A retained

Separate `(packageName,userId)` reverse-index rows, noncurrent user visibility precautions, `user:10` search, stable keys, and standalone JVM regression tests. No changes to Vector mutation policy.

## Qualification gate

- Source-pin, payload manifests and Python static checks: local preflight only.
- JVM sorting/grouping regressions and API36 emulator UI tests: **require new GitHub Actions run**.
- Physical screenshot review and accessibility: **pending**; previous S11 manual acceptance applies only to original S11 binary.
- This is a debug UX candidate, not release signing or device-hook verification.

## R1B R2 audit corrections (2026-11-04)

- Verified sorting/filtering UI and screenshot-inspired module grouping remain present.
- **Fixed repository duplicate handling:** hidden/unreleased rows no longer claim a module package identity before a later usable row is considered.
- **Fixed chronological sort:** parse ISO timestamps and supported date-only legacy metadata instead of comparing raw strings; unknown or invalid dates appear last in both newest/oldest modes. Sorting is still local/offline and never touches GitHub credentials.
- Module version text is width-bounded so long version strings cannot consume the full module-name row; refresh's accessibility label now reflects whether Vector is connected.
- Discover sort button exposes a textual sorting label. Adds JVM parser/timezone tests plus read-only source contracts.
- This correction replaces the earlier R1B ZIP **before applying**. Full hosted Android/Gradle qualification remains pending.
