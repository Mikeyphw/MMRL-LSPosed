# S11 — physical functional acceptance record

Recorded: 2026-11-04. Reporter: project maintainer. Device: Android 16 Redmi
M2101K7BG / mt6785, via `adb -s localhost:5555`. **No new device writes were
performed by the assistant**; these are maintainer-reported manual results.

## Immutable implementation identity

- Repository: `Mikeyphw/MMRL-LSPosed`, branch `main`.
- Source SHA: `6c89740f86fb750ad9dc108f02065719314a7595`.
- GitHub Actions: run `41832873086`, attempt 1, hosted build / JVM / lint /
  Android API 36 non-root emulator / provenance all passed.
- Signed debug artifact ID: `13324440938`.
- Stable debug signing certificate SHA-256:
  `d887b78cc920b49112e2373f2389f51d7bc2931f894ff89509a0aae14e617b27`.
- Downloaded artifact APK SHA-256:
  `028973ba6cff2aed68b553d3a60b88777aa1992f4d34f18f8ec645e41387517c`.
- Physical installed package `io.github.mikeyphw.mmrl.lsposed.debug` APK
  SHA-256 matched this same hash (read-only `adb shell sha256sum`).

## Status (manual functional checks, no new automation)

- S11.0 source/run/device preflight: `PRE_FLIGHT_READ_ONLY_PASS`.
- S11.1 GitHub signed artifact receipt: `SIGNED_DEBUG_HASH_AND_RECEIPT_PASS`.
- S11.2 Vector auto-connect / opt-out / manual connect: maintainer **PASS**.
- S11.3 direct scope checkbox UX: maintainer **PASS**.
- S11.4 Vector Store / catalog / offline behavior: maintainer **PASS**.
- S11.5 optional GitHub settings / releases / artifacts: maintainer **PASS**.
- S11.6 Apps / Downloads / accessibility smoke: maintainer **PASS**.
- S11.7 functional disposition: **MAINTAINER_MANUAL_ACCEPTED**.

These statements do **not** establish independent new S11 write-journal/restore
telemetry, runtime hook injection, verified compatibility across other Android
users, complete 200% typography/TalkBack qualification, or a release-signed build.
S08 separately has physical disposable-lab `S08_LAB_WRITE_AND_RESTORE_PASS`
(run `41743515595`, session `0007`). Debug signature must never be represented
as a public release signature.

## Next: R1A/R1B — profile-aware index and Modules/Discover UX

Previous `AppsReverseIndex.reverse` rejected all nonzero Vector scope user IDs.
R1A projects each `(packageName, userId)` as a distinct read-only row and
prevents an app visible to only one Android profile from being labeled as
installed in another. Editing still uses the guarded S08 user-0-only path;
R1A does not change root writes, module state or Vector connection. Run hosted
JVM tests / lint / API 36 UI tests, then check Apps with/without a secondary
profile on physical hardware before labeling R1A device-qualified.

R1B adds no new device acceptance claims. The screenshot-inspired Modules page and offline
repository sort are source candidates until hosted CI and physical UI retest.
