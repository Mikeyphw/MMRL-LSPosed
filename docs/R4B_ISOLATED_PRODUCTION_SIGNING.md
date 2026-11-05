# R4B — Separate production signer enrollment and candidate-only signing

Base: R4A feature commit `c59217b6d02f169604f18b4df35f9483d513acf7`, merge required before this source-locked change. This phase **does not qualify or publish a production release**.

## Trust and enrollment

The existing trusted-main debug signer (`config/debug-signing.json`, `mmrl_lsposed_debug`) is **never** reused. R4B adds a separate, tracked `config/release-signing.json` policy. It deliberately begins with `enrollment=pending` and `certificate_sha256=null`: no production certificate has been supplied or invented. The first GitHub workflow run should be *policy audit only* and must report `R4B_RELEASE_ENROLLMENT_REQUIRED` while succeeding without touching credentials.

Before signing any candidate, the operator must:

1. Create a separate release PKCS#12 keystore **outside the repository** and keep an offline, recoverable backup. Never publish its bytes, passwords or alias secrets.
2. Obtain and independently verify the release leaf-certificate SHA-256 fingerprint. It must be lowercase, 64 hex digits, and **not equal** to debug fingerprint `d887b78cc920b49112e2373f2389f51d7bc2931f894ff89509a0aae14e617b27`.
3. Change only `enrollment` to `enrolled` and `certificate_sha256` to that independently verified digest in a separately reviewed commit on `main`. Code and tests require this committed certificate identity.
4. In GitHub repository settings create the Environment `mmrl-lsposed-release-signing`, restrict its deployment branches to `main`, and configure **required reviewers**. These environment protections are configured in GitHub and cannot be represented or verified by a repository YAML alone.
5. Add environment-scoped secrets `MMRL_RELEASE_KEYSTORE_B64` (base64 of PKCS#12), `MMRL_RELEASE_STORE_PASSWORD` and `MMRL_RELEASE_KEY_PASSWORD`. Do **not** reuse debug secrets or set these as general repository variables.
6. Manually run `R4B Isolated Release Signing Candidate` on `main`, first with `sign_candidate=false`. Only after reviewing the policy/enrollment gates, optionally run with `sign_candidate=true`, approving the protected Environment action explicitly.

The signing runner builds `:app:assembleRelease` using GitHub Actions (not Termux). It refuses an already signed input, checks package `io.github.mikeyphw.mmrl.lsposed` without `.debug`, checks that it isn't debuggable, aligns, signs using files readable only by the runner, independently verifies exactly one expected certificate and saves an immutable source/run/attempt-bound receipt. Tool subprocess environments exclude signing secrets; neither passwords nor key bytes appear in logs, argv, the committed policy or public receipt. The temporary keystore/password files are deleted at step exit.

The artifact is explicitly named `unqualified-release-candidate`, retained seven days, and never uploaded to GitHub Releases, distributed through the app, auto-installed or counted as a release. Its receipt says `SIGNED_RELEASE_CANDIDATE_NOT_QUALIFIED`, `release_qualified=false`, `device_qualified=false` and `independently_attested=false`. R4C must separately verify signer continuity during update/rollback and obtain device-acceptance evidence; R4D will address publication/attestation and final release qualification.

## Important limits

- GitHub Actions job success or a SHA-256 match **alone** is not sufficient to qualify the APK's production release, provenance, device behavior or compatibility.
- A public-repository Actions artifact may be downloadable by other GitHub users. Do not dispatch the signing job until this distribution model is acceptable; the workflow never automatically publishes to Releases.
- The workflow's Environment reviewers and branch restrictions are **required operator setup**, not inferred from YAML. Without them the separation is weaker; configure them before enrollment.
- Debug workflows, Devtool source pin, existing GitHub attestations and privileged Vector paths remain unchanged. R4A remains a separate acceptance gate. The first trusted-main run following R4A merge must pass before R4B's signing path should be enabled.

## Acceptance

Run the native hosted Android CI for R4B PR, including source tests, APK/lint/JVM and API 36 emulator. After R4B merges, manually dispatch only `sign_candidate=false`, confirm pending enrollment is reported and no secret accessed. For an enrolled certificate, review a new source-pinned policy commit and configure Environment, then run a manual candidate signing job. Verify published receipt, certificate and APK bytes independently; no production release acceptance is claimed in R4B.
