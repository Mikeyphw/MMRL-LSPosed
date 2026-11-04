# S09 + S10 — Apps reverse lookup, Discover and Downloads

**Implementation candidate — not hosted/device accepted until the fresh GitHub run and device checks pass.**

## UX delivered

### Apps

- Connect to Vector explicitly on Home, then press **Build saved-scope index** in Apps.
- For each configured module, read saved targets using the previously accepted bounded Vector read-only adapter. Merge with installed applications, show the corresponding modules, allow app/package/module search and configured-only/all-installed filtering.
- Rows display installed app icons and human labels; saved module names are clickable shortcuts to the existing guarded module details. Modules gains saved-enabled/disabled filters and an enabled-first sort; Settings shows read-only framework details with explicit refresh.
- State labels always mean *saved configuration*, not hooks executing. Static/system targets are informational; no writes, auto-Connect, or root queries on page composition.
- Bound reads to 128 saved modules and 5,000 installed apps. Refuse non-user-0 scopes, failed reads, or detected module-state drift; never label a partial failure a complete index.
- Index explicitly invalidated on Vector refresh, module mutations, scope changes, or write-journal reconciliation. A concurrent scope change in another client cannot be locked out; rebuild before decisions.

### Discover

- No default trusted third-party repository. Press **Search GitHub** for a topic-scoped `xposed-module` search with optional text.
- Show repository names/descriptions/stars as *unreviewed* GitHub metadata. **Inspect on GitHub** opens the browser; no arbitrary downloaded APK or repository code executes.
- Strict HTTPS API host, user-initiated requests, response-size limit, 7/10-second network timeouts. GitHub unauthenticated rate limits and offline errors remain visible.

### Downloads / GitHub artifacts

- **Load from GitHub** pulls the first-party `Mikeyphw/MMRL-LSPosed` non-prerelease GitHub releases or recent Actions runs only when pressed.
- Release assets are eligible for in-app download only if they have a valid GitHub API `sha256:...` digest, are APK/ZIP, have a safe filename, are first-party release URLs, and are <=100 MiB.
- Downloads stream to app-private temporary files with bounded size, full SHA-256 and exact size validation, atomic rename, and an fsynced receipt. On failure/cancel, the partial file is removed. Progress and explicit cancel are available; saved receipts are rehashed before being displayed again.
- The only file-sharing provider is a non-exported, grant-on-demand `FileProvider` restricted to `verified-downloads/`. **Open verified file** requires a user tap and Android's normal handling; no unattended installation.
- **CI artifacts** lists recent runs, commit and attempt, then fetches public artifact ID, name, size, expiry and optional archive digest for a selected run. It requires that the artifact's GitHub API workflow-run ID and source SHA match the selected run. Open the artifact's GitHub page in a browser; downloading CI archives directly in-app is deliberately unsupported without an authenticated, provenance-bound flow.
- An archive checksum does **not** prove an APK signature or trusted publisher. Release APK signature verification and authenticated workflow-artifact direct downloads remain separate future gates.

## Regression gates

- Reuse existing Android 16/API 36 emulator instrumentation; four safe smoke tests now visit the actual Apps/Discover/Downloads UI without issuing root or network commands.
- New hosted JVM unit tests exercise reverse indexing and rejection of bad Android users, repo/URL allowlists, download digest/name/size constraints.
- Python source-contract tests protect explicit root/network behavior, FileProvider confinement, cap limits, S08 safety, and the unchanged hosted signing/provenance workflow.
- The S08 physical-device write-and-restore acceptance remains unchanged and cannot be run by emulator tests.

## Pending before milestone acceptance

1. Fresh GitHub Actions `main` run: Android compilation, JVM tests, lint, four API 36 UI instrumentation tests, signed debug artifact and provenance.
2. Device-side check: Apps index vs Vector saved scope lists, refresh invalidation, offline Discover/Downloads, dark/AMOLED and scrolling; no implicit root prompt.
3. Optional release download test with a real first-party published `sha256` asset, including intentional cancellation, tampering rejection and Android install handoff. If no eligible public release exists, mark this gate **not exercised**, not successful.
4. Authenticated artifact direct download, APK signer qualification, compatibility scoring and source reputation are **not** claimed in this candidate.

Source applies only to `Mikeyphw/MMRL-LSPosed`; the separate MMRL fork remains untouched.


## R2 audited corrections (supersedes the initial S09+S10 overlay)

- **Downloads concurrency:** a request retains its originating Releases/CI tab, so switching tabs during a request cannot reinterpret a `List<Release>` as `List<Run>` or crash rendering. Removed unchecked list casts entirely. Changing tabs clears stale selected-run/artifact state.
- **Safe cancellation:** a thread-safe `AtomicBoolean` is checked by the transfer; canceling or leaving the screen requests cancellation and incomplete temporary files are discarded. Progress is throttled to 0–100% changes and posted to Compose from the UI coroutine.
- **GitHub status:** a null/in-progress `conclusion` uses `status` instead of showing literal `null`. Artifact ownership/source-SHA parsing is extracted into a pure function with a hosted JVM regression test. A run can contain artifacts uploaded by earlier attempts; the UI explicitly avoids falsely attributing every artifact to the current attempt.
- **Browser handling:** only GitHub HTTPS URIs without unexpected ports or userinfo can be opened from these pages.
- **Emulator:** retains the four class-scoped non-root tests, now checking that Apps indexing is disabled while disconnected, that Discover search input accepts text, and that Downloads tabs switch without triggering network access.

Validated offline with 72 Python contracts, targeted `kotlinc` data/Compose compilation against lightweight stubs, and exact-source overlay installer acceptance. **A real Android SDK build, API 36 emulator execution, live network/download acceptance, and physical-device scope accuracy remain separate hosted/device gates.**
