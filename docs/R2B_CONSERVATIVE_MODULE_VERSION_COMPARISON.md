# R2B — Conservative installed APK ↔ public catalog version comparison

**Depends on R2A PR #2:** `7f0f260b661a2fd6d276ba60701d27c778e34488`.

## Scope

- Compare *installed* module APK `versionName` with `latestRelease` in an already cached copy of the Vector/LSPosed public module catalog, matched by **exact package name**.
- Show a count of **newer numeric versions in the saved public catalog**, not an unqualified "updates available" count. A matching name/version does **not** verify publisher provenance, APK signing or compatibility.
- Support simple numeric versions with 2–4 parts and optional `v` prefix, bounded input lengths, reject ambiguous prerelease, nonnumeric, zero-padded or build-tag variants. Compare numeric components, never lexical version strings.
- Treat unknown installed versions, unavailable cache, missing catalog entries and incomparable tags conservatively. Do not invent update counts for them.
- Read the existing cached catalog only when opening or explicitly refreshing the Modules section. A successful Discover refresh can be reflected on the next visit. Sorting/searching Modules does not download metadata or invoke root.
- Show contextual module detail information and visually concise indicators, without adding install actions or unverified claims.
- Preserve R1B's original grouped screen, filters, sorting, module details, and Vector write and journal behavior.

## Explicit limitations

The saved catalog may be stale, and a numeric catalog version alone does **not** mean an APK is a safe update. No APK signature continuity, author identity, download integrity, release attachment matching, or target compatibility is established by R2B. Manual review remains required. No background update polling, automatic installation or notifications.

## Validation

- 4 pure Kotlin JVM version-index regressions, plus new Python source-contract tests and unchanged repository suite.
- Full GitHub Actions native Devtool debug build, lint, JVM tests, PR APK staging and API 36 Compose emulator instrumentation **must pass after the PR is pushed**.
- Trusted-main debug signing and GitHub provenance occur only after merge.
- Physical device: load Discover once, return to Modules, verify no claim is shown for ambiguous/unmatched versions; inspect module details. Test offline cache and six Discover sorts. Do not change or clear Vector modules/scopes.
