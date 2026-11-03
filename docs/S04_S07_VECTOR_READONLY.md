# S04–S07 R2 — consolidated Vector read-only implementation

## Sources and version gate

- User-supplied `Vector-v2.2-3116-Release.zip` was verified against 27 embedded SHA-256 entries; no mismatches. Release root module ID: `zygisk_vector`.
- Reference source: `JingMatrix/Vector@569048119f0c1071b3d5069b645cfd4526b7ee8e`, particularly `daemon/.../Cli.kt` and `manager-ui/.../ModuleDetection.kt`.
- MMRL backend design provenance: `Mikeyphw/MMRL@7312cc03587cf60ef0c7010810b012d677ee5f1f`, GPL-3.0. The original manager UI was not ported.
- The standalone application supports saved-configuration reads for Vector framework version **2.2**, API version **102**, only. It refuses other runtime versions; future versions require review. The remote `status` JSON can supply numeric or decimal-string API 102.

## Boundary and behavior

- No privileged call occurs during app startup or APK discovery. The user initiates root connectivity through Connect.
- Libsu's RootService is a separate process/Binder surface (not a normal Android exported `<service>`). Exactly `status`, `modules ls` and `scope ls <package>` are permitted. No module or scope write commands; no arbitrary command execution interface.
- The root executor uses fixed approved module paths. It refuses the developer override `/data/local/tmp/daemon.apk`, bounds command time and response bytes and never trusts a malformed or unsuccessful JSON reply.
- Installed APK metadata uses both base and split APKs and bounded reads for modern and legacy Xposed module manifests. These are **suggestions and metadata**, never hook evidence.
- Vector saved enabled states and saved scopes are not proof of active hooks. Configured-but-uninstalled packages remain visible without invented APK metadata. No state is fabricated on denied/failed root access.
- Local draft scope editor has All / Selected / Suggested filters, app icons/search, preview-only changes and diff-review/discard. Static/system/manager/multiuser/special entries are informational or restricted; no Apply exists.
- Read-only UI session survives activity recreation without repeating root access. Starting a fresh process has no privileged state and requires Connect.
- English only. Signing certificate pin, APK namespace, original Devtool source SHA and GitHub provenance attestation workflow preserved.

## Newly implemented verification

- Source/manifest safety checks run offline in Termux without Gradle.
- Hosted native Devtool workflow includes a **third job** `:app:testDebugUnitTest`, with JUnit cases for version/API admission, enabled/disabled records, malformed/duplicate modules, special/system/multiuser scopes, duplicate scopes and invalid users.
- Hosted Kotlin/AIDL compilation, Android Lint and the signing/attestation chain still require a new successful GitHub Actions run, **not claimed here**.
- On-device acceptance on your phone remains pending. Confirm root granted/denied paths, Vector 2.2/3116 actual output, scope read symmetry, rotation, and that a scope draft never writes.

## Not yet delivered in this milestone

- Live hook/injection proof; background daemon health monitoring; enabled-state writes; scope writes; complete multiuser editing; release-qualified signing; polished Discover/Downloads and federation.
- Composable screenshot tests and 200%-font/tablet accessibility acceptance await the physical-device/UI gate. No claim of finished M1 until those tests are completed.

## Security risk note

The app uses `QUERY_ALL_PACKAGES` for installed Xposed module discovery, in line with the existing S01 documentation. It should be reassessed before any Google Play distribution.
