# R3B — Authenticated CI archive download, integrity and bounded evidence extraction

Base: R3A PR #5 source commit `7133d35603734eb8a08216b8dac2b9b9ffa0e1db`, **after a normal reviewed merge** into `main`.

## Goals

- Only an explicit user tap can download. Requires an existing Android Keystore GitHub token with **Actions: read** permission. Token is sent to the sole pinned `api.github.com` ZIP download endpoint. HTTP 302 URL is manually inspected and fetched without Authorization. Redirect targets are limited to GitHub Actions storage hosts; unknown/invalid hosts are refused and the browser route remains available.
- Re-fetch completed-successful workflow run identity (`head_sha`, run ID, attempt, event, main-branch constraints) and artifact-list metadata immediately before download. Reject expired/old-attempt/unclassified/missing-digest artifacts.
- Stream to bounded temporary file in app-private storage, enforce size and GitHub-published archive SHA-256, inspect all ZIP paths and expansion metadata, and write a source/run/attempt/artifact/digest receipt. No auto-install, APK unpack or root call.
- Saved CI archives are independently rehashed and ZIP-inspected before listing or extracting. Extract only **non-executable text evidence** (JSON/TXT/LOG/XML/TRX), maximum 24 entries, 1 MiB/entry and 8 MiB aggregate, into a private folder with digest-prefixed filenames; verify each entry's CRC and advertised size. Binary APK, nested ZIP and DEX contents remain within the verified ZIP.
- Keep PR artifacts distinct from trusted-main debugging output. Even a trusted-main **debug** APK, a GitHub SHA-256, or a Devtool federation JSON is **not** a release-signing qualification or independent artifact provenance.

## Expected limitations

GitHub Actions archive storage redirects can change. Unknown hosts are rejected rather than weakening the token or URL policy. Downloadable CI archives require GitHub Actions read permission and a published SHA-256 digest. Old/artifact-unknown entries remain browser-only. ZIP inspection is NOT general malware scanning or APK signature verification. Text evidence remains in app-private storage and is not automatically sent to other apps.

## Tests and rollout

- Full Python source regression suite.
- Native hosted JVM tests: current attempt, API run details, storage redirects, negative URL tests, path traversal, source-bound receipt and ZIP metadata checks.
- Native hosted Android build, lint, API 36 non-root UI instrumentation, evidence + provenance gates on PR.
- Physical device: configure existing GitHub token with Actions: read, download known safe S01 evidence ZIP from current successful run, inspect the saved receipt, and exercise text evidence extraction. Verify that old attempts/missing digests are browse-only, cancellation leaves no verified receipt, and no APK install prompt occurs.
- No Termux Gradle, no test root, no mutation of Vector settings/signing credentials.
