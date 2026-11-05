# R3C — Portable Devtool evidence contract (consumer-side, read-only)

**Pinned source:** `Mikeyphw/MMRL-LSPosed`, R3B PR #6 commit `be3acf41a05177abb901f10658210acca8d05480`, to be merged before applying R3C. No Devtool pin, Gradle, signing, root, package installation or Android UI changes.

## Delivered contract

R3A already writes `plan.json`, `verification.json`, `federation-index.json` after Devtool's reviewed native Android DAG; R3C introduces a fourth file, `portable-federation-contract.json`, in the **same** GitHub Actions S01 evidence archive. It contains:

- Producer repository, source commit, GitHub run ID, rerun attempt and event.
- The **currently reviewed/pinned** Devtool commit, target/workflow, immutable graph SHA-256 and native DAG node IDs.
- Debug APK SHA-256, size, signer certificate fingerprint, and the exact attempt-named evidence artifact.
- Byte-for-byte SHA-256 for each original evidence JSON; an independent internal canonical contract digest.
- Explicit `release_qualified=false`, `device_qualified=false`, `hooks_verified=false` and `independently_attested=false`.

`ci_federation_portable.py` has two stdlib-only modes:

1. `generate --directory PATH`: during GitHub CI **only**, rechecks all three R3A files using the original fail-closed GitHub-hosted producer validator, then creates the portable contract once without overwriting any existing file.
2. `verify --archive EVIDENCE.zip [independent identity...]`: from any **consumer** repo or machine (no Devtool Python package, GitHub credential, Android device or root needed), require exactly four safe root-level JSON evidence members and verify all cross-file identities, negative qualification flags, size/digest receipts and an independent GitHub tuple supplied by the caller. Also accepts `--directory PATH` for hosted self-verification.

## Consumer invocation

Fetch the archive **and independently query** GitHub for the run's repository, source commit, run ID, run attempt, event and the reviewed/pinned Devtool SHA. Use only those independently established values as arguments:

```sh
python3 ci_federation_portable.py verify \
  --archive path/to/s01-evidence.zip \
  --repository Mikeyphw/MMRL-LSPosed \
  --source-sha "$INDEPENDENT_GITHUB_RUN_SHA" \
  --run-id "$INDEPENDENT_GITHUB_RUN_ID" \
  --attempt "$INDEPENDENT_GITHUB_RUN_ATTEMPT" \
  --event pull_request \
  --devtool-sha b164463db51e2cffd12f753043b9e55a08cc43ae
```

Successful output: `R3C_PORTABLE_VERIFY_PASS <source> <run-id> <attempt>`.
This script is suitable for copying unmodified into a separate MMRL consumer's CI. The *producer schema* in v1 is deliberately specific to MMRL-LSPosed's S01 Android target; it does not pretend to accept arbitrary differently shaped consumers' receipts. Upstream Devtool may later host a generic producer schema, but R3C does not silently change Devtool or its pin.

## Trust boundaries

- Internal SHA-256 checks and GitHub's own archive digest establish **consistency/integrity**, not publisher authenticity, independently signed provenance, APK cert continuity, trusted release signing or installed/runtime compatibility.
- `verify` must receive an **independent** GitHub identity; feeding its arguments from the untrusted ZIP itself defeats this gate. For a pull request, the source SHA is the GitHub-generated test-merge commit, not necessarily the head branch commit.
- ZIP files are never extracted to disk or executed by the verifier. Reject extra, duplicate, nested, traversal, non-regular, symlink, encrypted, oversized or malformed members and repeated JSON object keys. No credentials appear in the contract.
- No producer automation makes API calls or changes GitHub permissions; it only writes a fourth source-bound JSON beside the existing verified evidence, before the existing upload step.
- The Devtool pin remains **`b164463db51e2cffd12f753043b9e55a08cc43ae`** despite newer upstream Devtool `main`. Changing the pin requires a separate, independently qualified native graph/provider compatibility audit.

## Qualification

- Python source suite, malicious metadata and ZIP fixtures, single-write and source-refusal regressions.
- Parse GitHub Actions YAML; ensure the new step runs after `ci_federation.py` but before evidence upload, and signing/provenance jobs remain unchanged.
- Dry-run against the *actual* published R3A evidence archive from GitHub Actions run `42085026186` (successful in local validation).
- Required hosted gate: R3C source contracts, APK, lint, JVM, federation index+portable contract generation, API 36 emulator, and published four-file evidence artifact.
- Post-merge: independently review trusted-main signing/provenance. No R3C physical device step is required because its logic is Python-only and read-only; this does **not** substitute for R3B's separate physical authenticated-download acceptance.
