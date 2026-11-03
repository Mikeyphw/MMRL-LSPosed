# MMRL-LSPosed

Independent Android app for Xposed module management, initially targeting verified Vector 2.2/API 102 integration. This repository is distinct from [Mikeyphw/MMRL](https://github.com/Mikeyphw/MMRL).

## Status: S01 (foundation)

- Material 3 native Compose shell with Home, Modules, Apps, Discover, Downloads and Settings
- Read-only, unprivileged installed Xposed APK discovery (modern/legacy metadata)
- Real installed app labels and icons; search, filters and module detail
- System/light/dark/AMOLED theme selection (session-level in S01)
- English and Brazilian Portuguese
- Isolated APK package: `io.github.mikeyphw.mmrl.lsposed.debug` for hosted debug builds
- Pinned Gradle wrapper from reviewed MMRL source, required by hosted Devtool
- GitHub Actions runs the Android Gradle build/lint exclusively through reviewed native Devtool DAG nodes

**No root access, Vector CLI invocation, scope writes, module toggles, app installation, download execution or hook verification is implemented yet.** Installed APK discovery is *not* proof of enabled/active hooks.

## Build policy

Android builds run on GitHub Actions only. Do not run Gradle from Termux. The [hosted workflow](.github/workflows/s01-hosted-devtool.yml) builds and verifies a debug-only candidate; it is not a production signing or device-acceptance authority.

## Next

S02: qualifying the first hosted APK and visual acceptance on device; S03–S07: reusable Vector adapter, scoped read-only manager, polished scope picker; S08: guarded writes after explicit confirmation and disposable-target testing. See `docs/PROJECT_STATUS.md`.

## Attribution

This new implementation is GPL-3.0. Its Gradle wrapper is retrieved byte-for-byte from [Mikeyphw/MMRL](https://github.com/Mikeyphw/MMRL), itself derived from [MMRLApp/MMRL](https://github.com/MMRLApp/MMRL), at a pinned source commit. No original MMRL Kotlin application code is copied in S01; future code ports must keep source references and required notices. See `docs/UPSTREAM.md`.
