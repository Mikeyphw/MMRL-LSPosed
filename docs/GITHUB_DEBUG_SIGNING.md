# S01-SIGN — Stable GitHub signing for debug APKs

The S01 Android application remains a **debuggable, non-release-qualified** build,
with package `io.github.mikeyphw.mmrl.lsposed.debug`. Its build runs via
Devtool's pinned native hosted Android DAG (`b164463db51e2cffd12f753043b9e55a08cc43ae`).
Devtool's reviewed native Android provider intentionally has no signing authority.
We therefore sign **after** independent Devtool APK handoff and lint verification,
then use the pinned Devtool `AndroidSigningManager` to inspect the key and
verify the final APK's certificate SHA-256.

## One-time provision on Termux

After applying the overlay, run:

```zsh
cd ~/Code/MMRL-LSPosed
zsh scripts/setup_debug_signing.zsh
python3 -m unittest discover -s scripts/tests -v
```

Preconditions: `gh auth status` succeeds, Java `keytool` is installed
(`pkg install openjdk-21` if needed), `openssl` and Python 3 are available.

The script creates a persistent **debug-only** PKCS12 keystore and a password
under `$HOME/.config/mmrl-lsposed/signing/` with private permissions and never
places them in the repository. It generates the random password locally and
uploads two encrypted Actions repository secrets via `gh secret set`:

- `MMRL_DEBUG_KEYSTORE_B64` — base64-encoded private PKCS12 keystore
- `MMRL_DEBUG_STORE_PASSWORD` — password, shared by PKCS12 store and key

The script creates `config/debug-signing.json`, containing **only the public**
signing certificate SHA-256 and a fixed debug identity. This file **must be
committed** with the overlay. CI verifies the certificate against this source
pin and refuses missing secrets, missing policy, mismatched certificates,
package/version changes, wrong run/attempt, or a failed Devtool receipt.
Re-running provisioning reuses the existing identity. It will **not** silently
rotate or overwrite an existing key/policy.

**Back up both private files in a secure location.** They cannot be retrieved
from GitHub Actions secrets after upload. Never publish or add the key/password
to Git. If you lose the key, old installations cannot be updated with a
replacement signing key under the same application ID without an authorized
key rotation path or reinstall.

## CI pipeline

On `push` to `main`: Devtool build → native APK receipt verification →
Devtool lint → stable signing with scoped secrets → Devtool signer identity
verification → publish the stable-signed debug APK and non-secret evidence →
separate GitHub OIDC provenance attestation job.

On `pull_request`: Devtool build, verification and lint still run; **no signing
secrets are exposed**. The PR artifact is explicitly labeled ephemeral.
GitHub OIDC attestation permissions are granted **only to the main-branch
attestation job**. All critical actions are commit-SHA pinned.

The main artifact contains:

- `MMRL-LSPosed-debug-signed.apk` — single-installable debug APK with the
  persistent signing certificate
- `signed-debug.json` — source SHA, GitHub run/attempt, Devtool graph/node
  provenance, pre-signing and post-signing APK SHA-256, signer fingerprint,
  package, version and explicit non-release qualification
- `devtool-node-receipt.json` — immutable original Devtool build receipt

A GitHub artifact attestation, using `actions/attest@v4`, binds the exact APK
bytes to the source/workflow. Signing and attestation are separate guarantees.
See [GitHub's attestation instructions](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations).

## Device install compatibility

**The S01 APK built before this overlay used a different, ephemeral debug
certificate.** Android normally refuses updating an installed app with a
different certificate. Back up any app data first; uninstall **only** the
`io.github.mikeyphw.mmrl.lsposed.debug` package before installing the first
stable-signed build. Do **not** uninstall or modify `com.dergoogler.mmrl` or
any existing Vector/LSPosed manager.

Subsequent stable-signed debug APKs can update one another if the keystore stays
unchanged and the Android version-code rules are satisfied. S01 currently uses
versionCode 1; future versions should increment versionCode normally.

## Boundaries

- A signed *debug* APK is **not** a release-signed, non-debuggable or
  independently release-qualified APK.
- Debug signing secrets must **not** be reused for production release signing.
- This change does not alter root permissions, hook verification, vector module
  state or Android app functionality.
- This changes the **published** debug certificate, not the baseline Gradle
  debug signing identity used inside Devtool's reviewed build job.
- Branch protection and review rules are recommended to protect source trust
  anchors and workflows from unauthorized changes. The signing workflow does
  not assume that GitHub branch protection is configured.
