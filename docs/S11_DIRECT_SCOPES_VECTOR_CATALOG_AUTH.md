# S11 — Direct scope UX, Vector catalogue, automatic connection, GitHub token

Base: `Mikeyphw/MMRL-LSPosed` main `15ed223b6f1e9bd3d07ce74a2ecedcef1eef99e9`.

## Motivation and behavior

The physical-device screenshots from November 4 showed a review dialog whose apply button was disabled while the module was disabled, plus an unusable generic GitHub topic search because `api.github.com` did not resolve on that device.

- **Scope editor**: selecting a supported app checkbox sends exactly one S08 `ADD_SCOPE` / `REMOVE_SCOPE` mutation, with a durable write journal, a fresh snapshot check, CLI acknowledgment, complete read-back and app-local success/error status. No local draft, review sheet, bulk transactions, or automatic retry. A disabled module must first be explicitly enabled using the editor's dedicated button; scope changes never enable it implicitly. Unsupported system, manager, other-user and static-outside-declaration targets remain read-only.
- **Automatic Vector connection**: defaults on for a physical device and is configurable under Settings. The app performs a single read-only connection attempt per newly created ViewModel (not background polling). The emulator guard prevents CI UI tests from requesting root. Auto-connection never writes Vector, auto-retries, acknowledges an uncertain write, or interferes with the S08 recovery journal. Manual refresh remains available.
- **Discover**: uses the full LSPosed module catalog that the Vector 2.2 `RepoRepository` actually uses, `https://backup.modules.lsposed.org/modules.json`. `modules.lsposed.org` is suitable for module detail links, but Vector's upstream code notes that it returns HTTP 403 for the complete `modules.json`. Search is local, without root. A bounded, public metadata cache is saved to the app cache directory. If offline, the last parseable catalog is shown with an explicit stale notice; otherwise the user gets a DNS/network diagnostic and Retry. No GitHub PAT is sent to this catalog.
- **GitHub token**: Settings accepts an optional GitHub personal access token, AES-256-GCM encrypted with a non-exportable Android Keystore key, stored under non-backed-up private app preferences. A saved token is never shown in Settings and can be removed. An explicit API connectivity test uses GitHub's `/rate_limit`. Only exact-host `https://api.github.com` requests receive `Authorization: Bearer ...`; redirects are disabled. This applies to GitHub releases and workflow metadata only. A token may improve GitHub API rate limits, **but cannot repair DNS failures**.

## Tests and acceptance

The overlay adds source regressions and offline JVM catalog parser tests, and preserves the hosted API 36 emulator build/tests, Devtool, signing and provenance jobs. CI device tests never run the physical S08 write acceptance automatically.

The following still require a new GitHub Actions run and physical acceptance:

1. Hosted Android/AIDL/Kotlin compile, JVM parser tests, Compose emulator tests and stable signing/provenance success.
2. On real `localhost:5555` device: auto connection after a cold app launch, and manual disable/enable of auto-connect.
3. On real device: enable disposable hook-lab module when disabled, then scope add and remove by tapping checkbox; verify saved Vector state after every tap. Do not attempt with an ordinary module for acceptance.
4. DNS issues: test catalog fetch; if DNS is unavailable, verify stale cache behavior after one successful fetch. GitHub token tests should be performed only through the app, without logging or sharing token value.

**Do not call this feature accepted until the hosted and physical checks pass.**

## R2 audit improvements

- Catalog first renders a previously valid private cache, then performs network refresh. A DNS outage leaves usable cached results visible.
- GitHub connection reports DNS failures, timeouts, HTTP 401 and 403/429 distinctly, without reflecting the secret.
- Physical Generic System Image (GSI) builds are not mistaken for emulators solely due to `generic` in the build fingerprint.
- Four non-root API 36 emulator tests continue; Settings test now checks auto-connect and token controls.
