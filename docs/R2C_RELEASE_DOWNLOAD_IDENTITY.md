# R2C — Release-source identity and safe saved-file handoff

**Source base:** `Mikeyphw/MMRL-LSPosed` `main` `5e7e14526e768b9d7e481ff8c7cb73191aa5fb98` (R2B PR #3 merged).

## What R2C implements

- **Exact GitHub release source binding:** Only public, non-draft, non-prerelease releases with canonical first-party `https://github.com/Mikeyphw/MMRL-LSPosed/releases/tag/<tag>` paths qualify for listing. For app-managed asset downloads, tag, asset name and `browser_download_url` must form **the same exact canonical `/releases/download/<tag>/<asset>` path**; no URL fragments, query strings, userinfo, alternative hosts, encoded/path-ambiguous segments or arbitrary GitHub release links. Conservative ASCII tag and filename restrictions are intentional; nonconforming releases can still be opened externally using GitHub.
- **Source-bound v2 receipts:** Record fixed repository, tag, original asset name/URL, published byte count and SHA-256 of downloaded bytes, plus local stored filename. The digest is still taken from public GitHub release metadata. No GitHub token is saved in the receipt.
- **Legacy compatibility:** Existing two-field `file`/`sha256` receipts can still be listed and opened, but are explicitly labeled **legacy hash-only**; no source provenance is invented or upgraded. A partial, malformed or mixed v2 receipt is refused.
- **Storage/format defense:** Reject unexpected ZIP/APK headers, hash/size mismatch, symlinked stored files/receipts, malformed local receipt names, unsupported extension and file replacement. Preserve private app storage, atomic temp write/rename, cancellation, bounded redirects and no auto-install.
- **Revalidation at handoff:** The selected file and receipt are revalidated and rehashed **on a background dispatcher** immediately before opening via the existing private `FileProvider`. A changed file/receipt is refused.
- **Clarity:** Saved-file history shows the release tag (when source-bound) or `Legacy hash-only receipt`. Both the screen and the open-file action explicitly say **APK package and signing certificates have not been qualified**.

## Trust boundaries

1. The digest is metadata **published by GitHub**. File SHA-256 matching it proves integrity relative to that metadata, not independent release provenance, author authenticity or trustworthiness.
2. GitHub source URL/tag/filename consistency is not APK signer verification; it does not prove a module update is compatible or signed by the installed publisher.
3. ZIP header checks do not validate internal archive contents, Android package ID, APK signature or known signing root. Release signing qualification, trusted certificate policy and device installation acceptance remain in a later gated milestone.
4. User-initiated browser/file-viewer handoff is not a silent installation. No root/Vector paths, signing workflow or Devtool pin are modified.

## Tests and acceptance

- Python contracts: URL/tag/name binding, strict v2 source data and legacy downgrade, UI async rehash before handoff and no new privileged paths.
- Host JVM: canonical URL allowlist and negative cases; `Asset.verifiedDownloadable`; partial/mismatched v2 receipts; legacy-preservation negative cases.
- GitHub Actions: source tests, native Devtool APK DAG, lint, JVM tests, API 36 non-root Compose UI, PR artifact pair. This is **PR qualification only**, not trusted main signing or physical release qualification.
- Device acceptance after a trusted main build: load official releases, confirm unsupported assets remain browser-only, save an actual digest-bearing ZIP/APK, inspect v2 source label, re-open a saved file, verify legacy files remain labeled. Do not clear app data, auto-install, or change Vector modules.

## Rollback/recovery

All changes are source-only and merge-reviewable. Existing private downloaded files/receipts are never deleted/migrated in bulk. Failures preserve previously verified saved files; unsupported URL/metadata combinations are browser-only. The overlay installer refuses a changed preimage, unexpected branch/source ancestry and unrelated uncommitted changes.
