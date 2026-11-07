# R5D — Cold-start UX stability and physical-device acceptance

**Source base:** merged R5C PR #14, `bd93bccdcf7533d9f1b730d75d1cadd35317f8b1`.

R5D is a small product fix and a **read-only device acceptance helper**, not a security framework.

## Changes

- Appearance (System/Light/Dark/AMOLED) persists in private Android preferences after an actual cold app launch. Invalid/obsolete values fall back to System; only known enum names may be stored. This does not touch token encryption, root, Vector, or signing.
- Home no longer says **Loading** indefinitely when installed-module discovery has actually failed. It shows **Module scan unavailable** and offers a manual **Retry module scan** action. That action calls the existing local installed-app scan only; it never connects to Vector or changes module state.
- The optional `scripts/r5d_device_readonly.py` observes model, SDK, installation state and can capture a screenshot **only on an explicit separate command**. It neither starts the app nor modifies packages, writes Vector, requests root, or uploads evidence.

## Device acceptance (Termux; developer-initiated)

Use only on a previously authorized ADB connection; `localhost:5555` is the known device serial in this workflow. No installation is performed by the helper.

```zsh
cd "$HOME/Code/MMRL-LSPosed"
python3 scripts/r5d_device_readonly.py status --serial localhost:5555
```

For optional local screenshots, manually display the desired screen first and run:

```zsh
python3 scripts/r5d_device_readonly.py capture --serial localhost:5555
```

Screenshots live only under `~/.local/state/mmrl-lsposed/r5d-screenshots/` with restrictive permissions; review personally before sharing. Never capture GitHub-token entry or private application data.

### Physical manual checklist

1. Use an independently verified, **stable-signed trusted-main debug APK** from the *same new merged* `main` S01 run; confirm its package and source SHA using the run evidence. Installing it is separate and **requires your explicit decision**; the R5D helper does not install or select artifacts.
2. Open Settings, choose AMOLED, force-close/relaunch the application manually, and confirm AMOLED remains selected. Repeat for System.
3. Browse Home/Modules. Search, filter, rotate, reopen, and check both module counts and saved-state labels agree with read-only Vector metadata. Verify no claim that saved configuration proves active hooks.
4. Manually test a temporary loss of local app scanning only if practical; confirm retry appears on failure and disappears after a successful scan. Never clear app data to manufacture an error.
5. Browse Scope Editor with a disposable test module, test search and selected/suggested views, and verify saved state. **Do not write** to ordinary modules for acceptance; optional disposable write/restore requires separate authorization and the existing S08 journal.
6. Browse Discover online and offline, search/sort/show more; inspect Downloads filtering and selected CI artifacts, without installing untrusted APKs.
7. Review dark/light/AMOLED, large text, back navigation, and any screenshot/layout issues. Keep screenshots local until intentionally shared.

## Evidence status

Local Python contracts, Kotlin preference unit tests and non-root API36 emulator tests must pass; hosted Android compilation, lint, and stable-signed trusted-main build must pass before *new* physical-device qualification. Physical testing is **not performed by this overlay**. A green PR run cannot establish actual on-phone acceptance, and an unsigned/ephemeral PR APK is not the trusted debug release.

R4D-02 remains intentionally deferred. R4B production enrollment and release-readiness gates remain separate; this overlay does not publish.
