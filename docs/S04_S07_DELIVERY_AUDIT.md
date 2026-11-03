# S04–S07 delivery audit — R1 → R2 corrective overlay

Source pin: `Mikeyphw/MMRL-LSPosed@84d7ff9401bcc5971a32be8ed17eaec5059388d6`
Vector reference: `v2.2`, build `3116`, API `102`, `JingMatrix/Vector@569048119f0c1071b3d5069b645cfd4526b7ee8e`
Original implementation: S04–S07 R1 overlay, **not** green-compiled.
Corrective package: S04–S07 R2, **hosted and physical-device acceptance pending**.

| Roadmap promise | R1 finding | R2 corrective implementation | Evidence/status |
|---|---|---|---|
| Standalone app + existing Material3 navigation | Existing S01 UI preserved | Retained; no name/package change | Source review; on-device baseline previously accepted |
| S04 exact read-only root boundary | Fixed three CLI reads existed; path assumption narrow | Explicit user Connect only; fixed allowlist; additional approved module paths; size/timeout checks | Source assertions; device pending |
| S04 Vector API/JSON response checks | Parser inline and too permissive for version matching | Independent `VectorProtocol` with strict version/API checks; malformed/duplicate/unsupported data refusal | JVM JUnit tests added; hosted execution pending |
| S05 installed+Vector inventory merge | Basic merge retained uninstalled modules | Merge preserved; no fabricated enabled flags or APK info | Offline source contract, device comparison pending |
| S05 module metadata and declared scope | Missing declared scope/static scope, split APK support, description, API metadata | Bounded modern/legacy metadata, split APKs, staticScope and suggested targets | Static contracts; device/APK acceptance pending |
| S06 Home/Modules/detail | Existing UI connected, **but R1 Modules filter has a Kotlin parenthesis error** | Filter syntax corrected, metadata, saved state, last-read counts/timestamp; keeps icons | Kotlin syntax check; hosted compilation pending |
| S06 navigation/rotation | Selected module and Vector snapshot used `remember` only | Activity-owned ViewModel retains explicit session across standard recreation; fresh process remains disconnected | Source checks; physical rotation test pending |
| S07 full-screen scope picker | Search, icons, All/Selected, local draft; no Suggested or review | Added Suggested, immutable baseline, review/discard UI, static/manager/user restrictions | Offline source contract; UI/device test pending |
| S07 safety and zero writes | No UI Apply; root service read-only | Kept exact 3 root reads, no enable/disable/scope writes | Source audit; live before/after device comparison pending |
| Devtool native build/lint | Two jobs; no protocol unit tests | Added independent `testDebugUnitTest` as third Devtool node, before signing | Host tests declared; GitHub run pending |
| Stable GitHub signing and attestations | Existing S01-SIGN passed previous hosted run | Workflow signing and attestation stages unchanged except insertion of test stage | Source diff; must requalify new run |
| English-only | English-only | Preserved | Offline check |
| Exact-source conflict protection | R1 preimages only; couldn't upgrade itself | Supports either pristine base or exact uncommitted R1 preimages; SHA pinned postimages; refuses other edits | Synthetic repo installer tests |

## Explicitly not claimed

- This package is **not** proof of Gradle/Kotlin/AIDL compilation, JUnit execution, RootService Binder success, Android screenshot/accessibility conformance or Vector daemon compatibility on your real phone.
- It does **not** verify live process injection, active hooks, release qualification, system/multi-user mutations or change safety.
- Actual module enable/disable and scope mutation remain deferred to S08 under separately reviewed one-operation confirmation and reconciliation gates.
- There are no tests requiring a root-capable Android emulator in GitHub Actions; real root acceptance is deliberately performed manually against your existing Vector installation.

## R2 acceptance gates

1. Termux: deterministic overlay audit + source contract suite + exact preimage conflict check.
2. GitHub Actions: three native Devtool nodes (`assembleDebug`, `lintDebug`, `testDebugUnitTest`) succeed, stable signer identity/fingerprint and APK receipt pass, GitHub provenance attestation succeeds.
3. Real device: deny/regrant root, inspect saved 22+ module entries and scopes against Vector, rotate with an unsaved draft and verify zero Vector writes, test disconnected/retry behavior, compare system/user/manager restrictions.
4. Only after 2–3 are complete is this milestone candidate qualified as M1 alpha.
