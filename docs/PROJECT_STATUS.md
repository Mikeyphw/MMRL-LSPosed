# Current implementation and gates

## S01 — standalone foundation

**Implemented in the source overlay:**
- New Android namespace and independent debug appId
- Compose navigation and UI shell; module APK inventory via unprivileged Android APIs
- English and pt-BR strings, readable icons and system/light/dark/AMOLED theme options
- Hosted native Devtool APK and lint DAG, script verification of receipts/package/debug signer
- GitHub wrapper bytes pinned to exact source via preflight/bootstrap script
- Source-contract and overlay-security offline tests

**Not yet verified:** successful hosted Android build, installation, package visibility on Android 16, screenshots, device tests, signed release, root adapter integration or actual hook activation. No APK acceptance is claimed before hosted CI and physical-device evidence.

## S02 gates

1. Apply only to exact initial repository commit after clean-status check; commit and push generated files.
2. GitHub Actions must complete Devtool APK/lint DAG and publish valid evidence and a debug APK.
3. Verify source SHA, run/attempt, debug signature, exact applicationId and output checksum.
4. Install on separate test profile or in parallel with MMRL, check light/dark/AMOLED, 22+ installed Xposed APK inventory, app icons, filters, module detail and bottom navigation.
5. Zero root prompts, zero module/scope writes. No claims of hook activation.

## Upcoming phases

- S03/04: immutable framework capabilities, Vector 2.2 adapter and Binder root-service migration with new app-owned namespace
- S05/06: complete module details and polished lists with verified saved-enabled state
- S07: full-screen draft-only scope editor, real installed-app picker, disabled-apply review
- S08: explicit verified writes, staged change journaling, disposable-target restoration
- S09+: Apps reverse lookup, Discover, Downloads, GitHub artifacts and federation sharing

MMRL fork and PR #7 remain untouched until standalone qualification.
