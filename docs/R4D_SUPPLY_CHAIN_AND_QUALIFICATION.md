# R4D-01 — evidence-backed inventory and release-qualification denial

**Status:** source implementation candidate, not a release. Base GitHub `main` pinned at `9513da4d45175f1fb1510d9f27ab12ab7f479872` (2026-11-05). This phase has **no release signer, APK, device mutation, GitHub Release upload, or production admission**. Even a successful CI run **must** print `RELEASE_BLOCKED`.

## Purpose and trust boundary

R4A proves trusted-main **debug** signer/provenance. R4B establishes manual, isolated **production candidate** signing, but the certificate remains `pending`. R4C provides opt-in physical upgrade/rollback plumbing with no qualifying device evidence. R4D-01 establishes an explicitly incomplete, machine-readable **supply-chain inventory** and a fail-closed **BLOCKED** report; it does **not** convert any existing debug/R4B/R4C evidence into a release.

New files:

- `scripts/ci_r4d_supply_chain.py`: validates same-run GitHub repository/source/attempt/ref, exact policy preimages, hosted Gradle dependency report, reproducibly generates/verifies JSON/SHA-256 manifest. Rejects malformed/duplicate JSON keys, edited source, changed attempts and outputs; no mutation beyond writing into a new exclusive evidence directory.
- `.github/workflows/r4d-supply-chain-qualification.yml`: read-only PR/main/manual CI. Python contracts and `:app:dependencies --configuration releaseRuntimeClasspath` **run on GitHub Actions only**. No Gradle in Termux. Uploads only inventory/qualification documents with 14-day retention. On trusted-main **push**, independently rechecks same-run evidence and attests the **SBOM JSON only**, not the APK and not production-readiness. All third-party actions pinned to already reviewed SHAs.
- `scripts/tests/test_r4d_supply_chain.py`: tests bad run/attempt/source, forged hashes, policy changes, duplicate JSON keys, invalid Gradle output, release-denial invariants and no workflow signing.
- `docs/R4D_SUPPLY_CHAIN_AND_QUALIFICATION.md`: evidence semantics and follow-on admission checklist.

## Evidence produced

| File | Meaning |
|---|---|
| `sbom.cdx.json` | CycloneDX 1.6 **partial** inventory of components visible in hosted `releaseRuntimeClasspath` dependency report; composition `incomplete`; third-party licenses **unverified** |
| `dependency-inventory.json` | Source/run/attempt, source input hashes, resolved Gradle coordinates, declared direct dependencies, unparsed-line count and completeness limitations |
| `license-audit.json` | Explicit list of **unverified** third-party coordinates; never invents license metadata from names |
| `release-qualification.json` | `status=BLOCKED`, `release_qualified=false`, `device_qualified=false`, `publish_allowed=false`, mandatory missing gates |
| `gradle-release-dependencies.txt` | Raw hosted Gradle report for independent reparse; no private signing materials |
| `manifest.json` | SHA-256 for all emitted files and pinned source inputs, source SHA, run ID and attempt |

The BOM is **not** a transitive-edge-accurate SBOM, not a published SPDX notice set, not a complete plugin/build-tool inventory and not an independently verified reproducible dependency resolution. `releaseRuntimeClasspath` does not cover tests, `hook-lab-module`, JDK/Gradle tooling, or download provenance. The Gradle text parser preserves all found GAV coordinates but does not claim dependency graph completeness. The `LICENSE` is GPL v3; third-party libsu, Vector and AndroidX notices remain a separate mandatory legal audit. Missing copyright/notices and JitPack provenance are blockers, not guessed away.

## Operator commands (read-only)

On Termux, in the existing checkout after the new branch has been applied and pushed, use only Python for local tests:

```zsh
zsh <<'ZSH'
setopt ERR_EXIT PIPE_FAIL
cd "$HOME/Code/MMRL-LSPosed"
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -q
gh run list -R Mikeyphw/MMRL-LSPosed --workflow r4d-supply-chain-qualification.yml --limit 6
ZSH
```

The workflow generates and verifies its evidence at `GITHUB_SHA`, with `GITHUB_RUN_ID`, `GITHUB_RUN_ATTEMPT`, `GITHUB_EVENT_NAME` and `GITHUB_REF` all bound. PR evidence does not have a trusted-main SBOM attestation, by design. After merge to `main`, the attestation is generated for the BOM, **not** for a production APK.

For an actual trusted-main BOM artifact subsequently downloaded with verified run/attempt, independently check GitHub's attestation (this is a **manual check**, not something the script pretends happened):

```zsh
# Replace with the exact main push run and its real source commit.
sha='<verified-40-char-main-commit>'
run='<verified-R4D-run-id>'
attempt='<verified-run-attempt>'
# Download mmrl-lsposed-r4d-inventory-$run-attempt$attempt
# using gh run download to a fresh local evidence directory.
gh attestation verify ./r4d-inventory/sbom.cdx.json \
  -R Mikeyphw/MMRL-LSPosed \
  --signer-workflow Mikeyphw/MMRL-LSPosed/.github/workflows/r4d-supply-chain-qualification.yml \
  --source-ref refs/heads/main --source-digest "$sha"
```

The attested BOM is an inventory input only. It is **not** the APK's SLSA provenance and cannot serve as a production-signer proof. Any future R4B APK attestation must be performed independently from the protected signer workflow and verified using the APK's actual bytes, source/run/attempt and release-certificate fingerprint; never accept a JSON claim of `independently_attested=true` without cryptographic verification.

## Mandatory before any `READY` release

1. Enroll a separately backed-up **release** certificate and verify protected Environment branch restrictions, required reviewers and secret scoping in GitHub settings; test manual `sign_candidate=false` again. Do not reuse the debug identity. Set up and use `sign_candidate=true` only with explicit operator approval.
2. Obtain an actual release-candidate artifact; independently recheck APK signature against the enrolled fingerprint, APK bytes, GitHub source/run/attempt, workflow identity and genuine GitHub attestation. R4B's `independently_attested=false` remains authoritative until cryptographic evidence exists.
3. Produce a complete, authenticated dependency/lock/checksum, plugin/toolchain and license/notice audit; convert the partial BOM to a complete, reviewed BOM with provenance. Investigate JitPack/libsu, GPL obligations and all redistributed third-party dependencies, without treating a source scan as conclusive legal advice.
4. Complete hosted API 36 Compose, lint, JVM, **API 26 release smoke** and physically authorized Vector 2.2/KernelSU S11 UX acceptance (auto-readonly connect, direct scope readback/restore on disposable lab, offline catalog, token privacy/download flows), plus R4C real signed upgrade, verified data preservation and restoration. Record all evidence and exact version/device identity.
5. Have an independent, source/run/attempt/signer/device/evidence-bound **admission validator** on a trusted machine that can produce `READY` only when every mandatory gate is independently verified and current; current R4D-01 code **intentionally has no READY state**. A signing job or artifact retention is not approval to publish.
6. Implement a separate manual distribution/sealing operation after verified READY, with separate authorization; no automatic Releases, in-app promotion, installation or signing path is added here.

**Current decision:** `BLOCKED` by design. New R4D hosted CI can show the evidence pipeline works, not that the product is released or safe to install.
