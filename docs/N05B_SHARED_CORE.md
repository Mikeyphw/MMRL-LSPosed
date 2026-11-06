# N05B — source-locked MMRL-Shared consumer in MMRL-LSPosed

## Boundaries and provenance

- Main base: `Mikeyphw/MMRL-LSPosed:main` `bd93bccdcf7533d9f1b730d75d1cadd35317f8b1`.
- Core source: `Mikeyphw/MMRL-Shared` pinned Git gitlink `9e09e1267099ef6a015f0c4bb1dcd31e3471d0ee`.
- New separate feature PR (based on `main`); **PR #15** remains untouched.
- No root, Vector, installer, scope-editor, device-write or automatic APK install changes.
- No Maven publication is required. The app consumes a new Gradle Android library project, whose sole Kotlin source directory is inside the **exact Git submodule commit**.

## Integration

`integration/shared-contracts` is a minimal AGP 9 Android-library adapter. It is not a fork or copy of the core. The ordinary app has a real `implementation(project(":shared-contracts"))` dependency. Both `releasePage` and `asset` decisions delegate to `ReleaseAssetIdentity` in the shared core, while keeping the existing `ReleaseIdentityPolicy` API intact. Run identity shared APIs get independent JVM regression tests without changing CI release-signing or installer behavior.

All four workflows that checkout sources now initialize the submodule recursively. A source contract rejects a wrong commit, missing checkout, unexpected URL or stale metadata. Release source inventory records `.gitmodules` and the adapter build script. The original source-bound APK, Devtool, supply-chain and signing workflows remain in place.

## Acceptance

1. Apply the source-pinned bundle from a clean or dirty **existing** checkout: only a temporary Git worktree is changed; the active branch and its uncommitted files remain untouched.
2. Installer verifies exact `main` source, GitHub shared source pin, compiler test and Gitlink, commits and pushes a separate `feat/n05b-source-pinned-shared-core-20261106` PR against `main`.
3. Hosted S01 native Devtool APK/lint/JVM and R4D supply-chain workflow must both pass; inspect the two new shared JVM regression tests in published XML.
4. Do not merge unless both are green. If PR #15 merges first, GitHub will retest the merge base; update N05B if GitHub detects conflicts. No Gradle command runs in Termux.
5. After merge, N05C will bring the same pinned core into MMRL `next`, then N05D defines explicit dependency-aware Devtool federation edges.

The shared library is **source pinned**, not binary signed or published to Maven. Existing APK-signing claims remain exactly as before.
