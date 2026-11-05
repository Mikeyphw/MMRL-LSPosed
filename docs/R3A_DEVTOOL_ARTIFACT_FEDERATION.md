# R3A — CI artifact evidence and Devtool federation index

**Source:** R2C PR #4 SHA `f4ebce6384042801589520a2cdc6fe2c3fe59e3e`, merge required before applying. `main` must contain R2C by ancestry, with GitHub blob preimages unchanged.

## Scope

- **CI artifacts browser:** Classifies existing first-party GitHub Actions artifact names into Devtool source/graph evidence, JVM reports, lint reports, API36 UI, PR-only ephemeral APKs, trusted-main debug receipts and S08 lab packages. Only exact current-run/current-attempt name bindings appear by default. "All artifacts" reveals older rerun and unknown entries with appropriately cautious labels. The native GitHub API run-id/source verification is retained; this feature never downloads or opens APKs in-process.
- **Event separation:** PR ephemeral APKs are not described as trusted-main signed artifacts. `Run.event` is taken only from recognized GitHub events. Artifact SHA-256 **published as metadata** is labeled as not downloaded or independently checked; a matching name or digest is not a signer, source attestation, or release qualification.
- **Devtool federation index:** GitHub Actions writes `federation-index.json` beside existing `plan.json` and `verification.json` after the native Devtool APK DAG, lint and JVM tests. A fail-closed Python validator checks the exact GitHub run, attempt, event/ref, source, pinned Devtool SHA, graph digest, planned native node identities, APK digest and debug signer from the existing S01 verification record. Its output is an **unsigned self-described index** for other consumers to inspect; it does not claim independent artifact attestation, live hook execution, device or release qualification.
- The workflow changes only one step before its existing source-evidence upload. No signing keys, permissions, Devtool pin, Gradle tasks, root/Vector writes, automatic CI archive fetch, GitHub credential storage or APK install behavior is changed.

## Limitations and next R3 stages

- The GitHub API artifact object's `workflow_run` binds run ID and source SHA, but it does not prove a particular rerun attempt. The default attempt filter relies on the **name convention** and must never be advertised as cryptographic proof. GitHub may return older artifacts even after rerun.
- GitHub metadata does not prove that a ZIP contains a particular build, that the APK is signed by an expected publisher, or that the artifact digest is independently checked.
- **R3B** may add an authenticated archive fetch with SHA-256 verification, archive entry allowlists, bounded extraction and explicit source/run attempt receipt checks. **R3C** may extend Devtool's cross-consumer federation contracts after confirming current Devtool provider compatibility. These are intentionally not claimed as R3A deliverables.

## Tests

- Python: synthetic valid index and forged run/graph/source/event/signature refusal, source contracts and overwrite refusal. Existing Python test suite unchanged in scope.
- JVM: four pure policy tests for current vs stale attempts, PR/main role partitioning, malicious names and non-qualified digest metadata.
- Hosted: GitHub Actions Devtool source contracts, debug APK DAG, lint, JVM, index stage, API36 non-root emulator, artifact publication; **trusted-main** signing only after merge.
- Physical: inspect CI artifacts in Downloads with current-only and all toggles; verify no automatic download, root connection, privileged Vector action, or unexpected auth requests.
