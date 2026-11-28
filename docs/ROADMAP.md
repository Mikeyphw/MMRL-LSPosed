> **2026-11-04 S11 UX policy update:** The historical S07/S08 draft/confirmation steps
> below were intentional at the earlier alpha stage, but the current S11 candidate
> replaces them with immediate, individually verified scope checkbox actions.
> A disabled module requires its own explicit Enable action. The user also requested
> one-shot read-only auto-connect by default on physical devices (opt-out in Settings).
> This does not grant automatic writes. Details and acceptance limits:
> `docs/S11_DIRECT_SCOPES_VECTOR_CATALOG_AUTH.md`.

> **2026-11-03 progress update:** S08 device write-and-restore accepted; API 36 emulator CI passed in run 41759587780. S09+S10 functional features are an implementation candidate awaiting a fresh hosted run and physical UX acceptance. Historical baseline tables below describe the project's creation, not current state. See `docs/PROJECT_STATUS.md` and `docs/S09_S10_CONSOLIDATED_FEATURES.md`.

# MMRL-LSPosed — Implementation Roadmap

**Version:** 1.1 · **Baseline:** 2026-11-03 · **Current state:** S04–S07 accepted; S08 implementation candidate pending hosted/device-write acceptance

> **Product objective:** Build a standalone, polished Android LSPosed/Vector manager with its own application identity, Material 3 UX, module and scope management, app-target reverse lookup, discovery/downloads, and Devtool-powered cross-repository engineering. Do not ship the existing debug-like MMRL LSPosed screen.

## Current progress (2026-11-03)

- S01–S02 foundation and hosted Devtool signing pipeline: successful, stable-signed APK.
- S04–S07 Vector 2.2 read-only features: CI run
  [41720288365](https://github.com/Mikeyphw/MMRL-LSPosed/actions/runs/41720288365)
  passed; maintainer reported successful physical-device verification and approved closure.
  See `docs/S04_S07_ACCEPTANCE.md` for evidence limitations.
- S08 guarded writes: implementation prepared, **not device-write qualified**. See
  `docs/S08_GUARDED_WRITES.md`. No personal modules should be used for acceptance tests.
- S09–S11 and federation work: pending further review.

**Historical note:** Section 2 preserves the original planning-time snapshots; statements
about a repository not yet existing refer to the old baseline, not current GitHub state.

## 1. Non-negotiable decisions

1. **Standalone first.** Proposed GitHub repository: `Mikeyphw/MMRL-LSPosed`; proposed Termux path: `~/Code/MMRL-LSPosed`. The repository does **not** exist at the verified baseline. Existing MMRL checkout remains `~/Code/MMRL-lsposed-vector`; Devtool remains `~/Code/devtool`.
2. **Preserve MMRL.** Do not merge the rejected Phase07 UI solely to enable the new app, move the existing checkout, or remove LSPosed code from MMRL before standalone acceptance. PR #7 stays independent until migration is qualified.
3. **Proven adapter first.** Support **Vector 2.2 / API 102** using reviewed Phase06/07 backend contracts. Other LSPosed variants require separately verified adapters; installation alone does not mean runtime hooks are active.
4. **Polished product UX.** Home / Modules / Apps / Discover / Downloads bottom navigation, Settings in the top bar, dedicated module detail and full-screen scope picker. Compact technical diagnostics live under Advanced.
5. **No silent privileged behavior.** No root prompt during composition/onboarding, no automatic writes, no broad module targeting, no guesses about hook activity, and no assumption that a failed write was rolled back.
6. **Hosted Android builds only.** Run Gradle through GitHub Actions with Devtool DAG integration. Termux handles Git, source tests, Devtool preflight and ADB acceptance—not Gradle.
7. **Source and artifact integrity.** Pin source SHA/branch, preserve GPL-3.0 attribution and applicable history, verify APK identity/signature/hash, workflow run/attempt and receipts.
8. **Federation is orchestration, not code sync.** Use Devtool for boundary/ownership, frozen source locks, graph planning, affected targets, Git lineage and verified artifacts. Share Kotlin Android functionality only via reviewed source changes or versioned Gradle libraries.

## 2. Baseline and known evidence

| Item | Baseline verified on 2026-11-03 | Treatment |
|---|---|---|
| MMRL | `Mikeyphw/MMRL`, branch `lsposed-vector`, SHA `b8410ce024b921d96c384e7b45d26929625418ab` | Preserve |
| Phase07 | PR [#7](https://github.com/Mikeyphw/MMRL/pull/7), head `7312cc03587cf60ef0c7010810b012d677ee5f1f`, open | Extract backend, reject old UX |
| Devtool | `Mikeyphw/devtool`, `main` SHA `b164463db51e2cffd12f753043b9e55a08cc43ae` | Reverify before pin update |
| Hosted CI | [Run 41672905479](https://github.com/Mikeyphw/MMRL/actions/runs/41672905479): all three jobs successful | Build/provenance reference only |
| Device | `M2101K7BG` / `mt6785`, Vector 2.2 / API102, 22 modules and 12 saved-enabled in prior read-only acceptance | Not evidence of actual hook injection |
| New repository | `Mikeyphw/MMRL-LSPosed` absent | Create only after explicit implementation authorization |

**Revalidation rule:** The SHAs above are historical checkpoints, not permanent source truth. Re-fetch branch heads and verify exact preimages before creating a new implementation overlay or integrating a change. Existing run artifacts expire; never depend on indefinite GitHub retention.

## 3. Release milestones

| Milestone | Goal | Entry requirements | Exit gate |
|---|---|---|---|
| **M0 — Repository foundation** | Independent app and CI exist | Source and licensing freeze | Reproducible hosted debug APK, independent package and GitHub artifact receipts |
| **M1 — Read-only UX alpha** | Beautiful useful app without configuration writes | M0 | Home, Modules, details and full-screen scope picker; device read-only acceptance and screenshot tests |
| **M2 — Controlled configuration beta** | Verified enable/disable and scope editing | M1 | One-by-one writes, confirmation, reconciliation, disposable lab restoration |
| **M3 — Functional manager beta** | Reverse Apps view, metadata-complete module UX and ready framework settings | M2 | Correct app-to-module mapping, strict capability gating, accessibility and error-state checks |
| **M4 — Connected ecosystem** | Discover/Downloads, GitHub artifacts and mature Devtool federation | M3 | Source integrity, safe downloads, signed/provenance-bound artifacts and cross-repo gates |
| **M5 — Release candidate** | Qualified standalone product | M4 | Release signing, full device/UI/regression acceptance, licenses, no high-risk open issues |

**Recommended first publicly testable APK:** M1 read-only alpha. This lets the visual UX be judged on a real phone before root writes are added.

## 4. Sequential implementation backlog

### S00 — Source audit, boundaries and freeze · P0

**Tasks**
- Check latest MMRL `lsposed-vector` and Phase07 head, Devtool main, CI and artifact availability.
- Inventory Phase06/07 backend, tests, manifest services, AIDL, dependencies and licensing; separate accepted root logic from rejected Compose UI.
- Record preimage SHA256 for each file to be imported and create a migration ownership map: **port**, **rewrite**, **exclude** or **shared later**.
- Fix the new application's provisional identity and source-seeding strategy in the decision register.

**Done when:** A pinned source manifest, licensing notes, backend dependency map and reviewed repository bootstrap plan exist. No app or repo mutations required for S00.

### S01 — Independent repository bootstrap · P0 · depends on S00

**Tasks**
- Create independent `Mikeyphw/MMRL-LSPosed` (if approved), preserving suitable Git ancestry or explicit attribution/commit provenance. Do not create another MMRL checkout.
- Bootstrap `app/`, `core/`, `feature/`, `testing/`, `.devtool.toml`, `.github/workflows/`, `LICENSE`, attribution and migration documents.
- Set unique package/namespace (`io.github.mikeyphw.mmrl.lsposed` **provisional**), separate debug ID, independent version code, data directory, launcher and update/signing policy.
- Remove MMRL root-module installer, repo manager, Magisk/KernelSU/APatch functionality, unnecessary components, broad permissions and MMRL-specific deep links.
- Add deny-by-default exported component policy, minimal package visibility and scoped root-service registration.

**Done when:** The standalone repository builds a minimal shell without installing over or sharing Android data with MMRL; all reused GPL source is attributed.

### S02 — Hosted CI + Devtool native target · P0 · depends on S01

**Tasks**
- Create Android target `lsposed_app` and `ci` workflow in standalone `.devtool.toml`.
- Use GitHub Actions for Gradle assemble, lint and tests; preserve an immutable source/runner/Devtool pin per build.
- Publish debug APK plus artifact manifest with SHA256, application ID, version, signature/certificate fingerprint, source SHA, workflow run ID/attempt and runner/toolchain identity.
- Make lint baseline source-controlled; split UI/unit tests, Android build and device-only acceptance so hosted CI never claims physical-device success.
- Verify fresh standalone CI with a real APK artifact download and receipt inspection.

**Done when:** The project has successful hosted CI with a demonstrably standalone, installable debug APK and explicit provenance.

### S03 — Design system, navigation, onboarding · P0 · depends on S02

**Tasks**
- Implement Material 3 with Material You dynamic color, Light/Dark/AMOLED, typography, accessible contrast, coherent 8dp spacing and real system insets.
- Phone bottom navigation: **Home · Modules · Apps · Discover · Downloads**; tablet rail and two-pane module detail; Settings from app bar.
- Dedicated nested detail/scope pages; preserve search/filter/scroll state across navigation.
- First-run flow: Welcome → detect installed framework → explain permissions → **user-pressed Connect** → readiness summary. APK browsing remains possible without root.
- Localize `en` and `pt-BR`; include empty/loading/permission-denied/offline/error states and nonintrusive advanced diagnostics.

**Done when:** Screenshots and Compose UI tests cover day/night/AMOLED, large text, phone/tablet, keyboard, back navigation and empty/error states without privileged work on screen render.

### S04 — Framework API, Vector adapter and root boundary · P0 · depends on S02

**Tasks**
- Define `FrameworkAdapter` and explicit capabilities (`readModules`, `readScopes`, `setEnabled`, `addScope`, `removeScope`, supported target/user classes, runtime evidence).
- Port and isolate the reviewed `VectorManager`, `VectorRootTransport`, `VectorCliRootService` and Binder/AIDL contracts; update all package, manifest and libsu identity assumptions deliberately.
- Keep allowlisted fixed commands, bounded JSON/IPC size and timeouts, work off the Compose main thread; never expose arbitrary shell input.
- Report exact states: **no root**, **Vector missing**, **connected**, **unsupported API**, **temporarily disconnected**, **saved config verified**, **hook state unverified**.
- No privileged state access until explicit Connect; do not trigger root just by opening Home or Modules.

**Done when:** Offline contract tests, root denial/timeout tests and a **read-only** device test pass; integration never writes scope/module state during this phase.

### S05 — Installed module discovery and reliable metadata · P0 · depends on S03, S04

**Tasks**
- Merge non-root installed APK discovery with Vector saved state using stable package IDs; maintain explicit provenance when data sources disagree.
- Show actual APK icon, human label, package ID, version, legacy/modern marker, saved-enabled state and verified scope count (or unknown state).
- Parse supported modern metadata, including declared module scope and restrictions where available; handle legacy metadata and malformed/missing declarations conservatively.
- Search/filter locally and sort by name, enabled, type, last update where known; no forced root IO per keystroke.
- Handle Android package visibility, split APKs, uninstalled-but-configured modules, missing icons and large inventories.

**Done when:** Inventory tests and physical phone comparison match installed modules/saved config, with no false hook-active labels or privileged writes.

### S06 — Home, Modules and Module Detail · P0 · depends on S05

**Tasks**
- Home: framework status, saved-enabled/installed counts, quick actions, recent refresh evidence and discreet warnings.
- Modules: performant LazyColumn, real icons, filter chips/search/sort, state, scope count and intuitive module rows.
- Module Detail: visual header, version/compatibility, scope summary, entry to the module's own settings when available, and expandable Technical Details.
- Explicit refresh action; retain last-known data with timestamp, never present stale cached state as current.

**Done when:** User can visually navigate and understand all 22+ installed modules without inspecting package IDs or raw logs; screenshot and device acceptance pass.

### S07 — Full-screen scope picker, **draft-only** · P0 · depends on S06

**Tasks**
- Show icon/name/package for installed app targets with Selected / All / Suggested segments, fast search, optional user/installed filters and current selected count.
- Preserve an immutable baseline snapshot plus draft additions/removals; checkbox selections never trigger a root write.
- Suggest targets from trustworthy, declared metadata only, and label suggestions as suggestions, not confirmed hooks.
- Classify system/framework, manager/self, special `android`/`system`, other user/profile and static-scope targets as read-only or unsupported until verified.
- Review sheet displays before/after, adds/removes, static-scope restrictions, disabled-module implications and an explicit Cancel path.
- In M1 alpha, **Apply is unavailable** and clearly described as a preview only.

**Done when:** Full-screen UX and draft diff tests pass, including navigation/rotation/restoration, restricted target handling, and zero writes in device acceptance. **M1 exit gate.**

### S08 — Guarded module changes and scope writes · P0 · depends on S07

**Tasks**
- Enable/disable one module only after explicit confirmation, fresh pre-state read and post-write verification.
- For scope add/remove, verify user-0 non-system target, no forbidden manager/self target and backend capability. **Require explicit enable first** for disabled modules, because Vector 2.2 may implicitly enable on scope addition.
- Refuse stale baselines and unexpected side effects. Unknown post-write outcome transitions to **Refresh and reconcile**, not auto-retry or presumed rollback.
- Introduce durable operation ID/journal and per-action receipts. Do not advertise multi-select as an atomic transaction; initially apply staged changes one at a time with verification.
- Test writes **only against isolated disposable hook-lab packages**, snapshot prior state, compare after changes and verify full restoration. Never use the user's ordinary modules for automated write tests.

**Done when:** Hosted logic tests, guarded device acceptance and 100% disposable-target restoration pass, including timeout, stale state, partial failure and root revocation. **M2 exit gate.**

### S09 — Apps reverse lookup and manager polish · P1 · depends on S08

**Tasks**
- Apps screen lists installed apps and configured module targets from verified saved scope mappings; allow search and per-app detail.
- Distinguish **configured targeting** from actually running hooks. Treat unsupported multi-user or static-scope entries as informational.
- Complete framework status/details, configurable refresh preferences, advanced diagnostics, action audit, language/appearance settings and first-run recovery.

**Done when:** Reverse mapping agrees with module scope inventories and remains correct across changes, revoked root and stale snapshots. **M3 exit gate.**

### S10 — Discover, Downloads, GitHub artifacts · P1 · depends on S09

**Tasks**
- Implement explicitly trusted/opted-in module sources and catalog metadata, compatibility filters, release identity, installed-version comparison and source attribution.
- Implement safe APK download handoff with progress/cancellation where supported, integrity verification, filename/path validation, duplicate detection and normal Android installer confirmation.
- Add an optional GitHub Actions artifacts browser with repository/workflow/run/attempt/commit/artifact ID/expiry, SHA256 and ZIP safety checks. **Never equate a successful CI job or checksum with publisher trust/signing qualification.**
- Share stable Android GitHub/download functionality through reviewed Kotlin code or a version-pinned Gradle library; Devtool's Python Actions client is a host control-plane reference, not Android runtime code.

**Done when:** Failed/expired/ambiguous sources are rejected, artifacts are provenance-bound, downloads do not silently install, and end-to-end test files pass.

### S11 — Release hardening and MMRL cleanup · P1 · depends on S10 and F04

**Tasks**
- Verify release keystore custody, stable release application ID, update signature continuity, versioning/changelog, SBOM/dependency review, GPL notices and source links.
- Exercise Android 16, lower supported API level, 200% font scale, screen readers, tablet/landscape, light/dark/AMOLED, root grant/deny, binder death/reconnect and offline fallback.
- Add performance/accessibility checks, no-crash smoke tests, long-lived state consistency and exact signed APK receipt validation.
- After qualified standalone release, decide whether to close/supersede MMRL Phase07 PR #7 and, **in a separate reviewed MMRL PR**, remove or retire LSPosed-only UI code; keep shared features intentionally synchronized.

**Done when:** All release gates pass with zero unresolved high-severity regressions and an independently installable, source-bound signed release. **M5 exit gate.**

## 5. Parallel Devtool federation workstream

> Coordinate independent repositories; **never** use federation `sync` as a mechanism for copying app features.

### F00 — Inspect and qualify Devtool source · starts alongside S00
- Recheck Devtool `main` SHA, federation schema, CLI commands, remote providers, Git ownership, source-lock and artifact-edge tests.
- Compare existing MMRL CI's older pinned Devtool revision against new `main`; upgrade only after adapter/CI compatibility tests.
- **Gate:** Pin policy and relevant tests documented; no silent bump of workflow source pins.

### F01 — Coordinator and read-only enrollment · depends on S01
- Create `~/Code/mmrl-suite` as an independent small config-owning Git repository; enroll `../MMRL-lsposed-vector` and `../MMRL-LSPosed` by explicit paths.
- Run `devtool federation list`, `inspect`, `preflight`, `capabilities`, `affected` and initial read-only source/ownership checks.
- **Gate:** Two exact roots and clean ownership boundaries; zero unexpected repository mutation.

### F02 — CI graph, locks and impact selection · depends on S02, F01
- Expose the standalone `lsposed_app::ci` target and the existing MMRL `mmrl_lsposed_ci::ci` target as distinct workflows.
- Define `validate-both` only after both workflows really exist; check DAG, exact qualified refs, dependency edges and impact selection.
- Freeze the source-lock baseline; use bootstrap/sync **previews**, and require explicit approval before mutating local checkouts.
- **Gate:** Deterministic graph, reviewed source lock, no spurious affected-target execution.

### F03 — Git provenance and artifact exchange · depends on F02
- Record parent/child Devtool lineage, source/branch/commit, runner mapping and per-node evidence.
- For local Devtool producer/consumer edges, require artifact registry ID, SHA256/type/schema, verified materialization and no path escape.
- For hosted Actions downloads, independently bind repository, workflow run, attempt, SHA, artifact ID, digest, package/signature receipt; distinguish hosted artifacts from Devtool's local federation edges.
- **Gate:** Tampered and mismatched artifacts fail closed; provenance trace links every build to its source and consumer.

### F04 — Shared Kotlin components and maintenance workflow · depends on S10, F03
- Extract a small versioned reusable library only when a genuine shared requirement exists: GitHub API/artifact viewing, download progress, or design tokens.
- Establish reviewed release/versioning, compatibility matrix, dependency impact graph and PR-based upgrades to both app repos.
- Share generic CI logic via reviewed reusable GitHub Actions workflows with immutable action references; no automatic broad cherry-pick between apps.
- **Gate:** One version change can be traced through both apps, with affected CI selection and no product-specific coupling.

## 6. Risk register and blocking rules

| Risk | Required mitigation | Hard block |
|---|---|---|
| Vector implicit enable on scope add | Read module state; force separately confirmed enable | No writes for disabled module |
| Unsupported `staticScope` / system targets | Capability/mutation policy separated from display | Read-only restricted rows |
| Binder timeout or unknown outcome | Journal, refresh, reconcile before retry | No assumed rollback |
| APK scan not equal daemon state | Dual-source inventory and confidence labels | No “hook active” without proof |
| Framework API differences | Adapter negotiation; explicit version checks | No guessed commands to legacy LSPosed |
| Android package visibility | Audited manifests/API behavior and fallback | No misleading “zero modules” |
| Reused MMRL package or services | Unique ID/AIDL/manifest mapping and tests | No colliding package/data/IPC |
| GitHub artifacts untrusted/expired | Source/run/attempt/hash, ZIP validation, signer checks | No unverified install claim |
| Devtool federation mistaken for code sync | Versioned Kotlin libraries and reviewed PRs | No unattended cherry-picks |
| Old MMRL prematurely cleaned | Standalone gate before cleanup PR | Keep MMRL unchanged through M5 |

## 7. Acceptance evidence required per implementation slice

Every slice/overlay must include: (1) exact source preimage and target SHA, (2) complete changed-file manifest and intended owner repository, (3) unit/contract tests plus negative tests, (4) hosted CI run and actual artifact IDs if applicable, (5) Android screenshot/Compose test or physical-device visual evidence for UX changes, (6) protected/disposable-device operation receipts for privileged changes, (7) explicit known limitations and rollback/recovery instructions, and (8) a machine-readable acceptance summary. **Do not call a feature delivered merely because source files were added.**

At milestone boundaries, verify **product behavior**, not only infrastructure: independent APK install, natural navigation, realistic icons, enabled/scope state correctness, physical-device rendering, read-only preservation, and later fully restored disposable writes.

## 8. Execution order and priority

```text
S00 ─┬─> S01 ─> S02 ─> S03 ─┬─> S05 ─> S06 ─> S07 ─> [M1]
     │                      │                             │
     └─> F00               S04 ────────────────────────────┘
             F01 <─ S01; F02 <─ S02+F01; F03 <─ F02
S07 ─> S08 ─> [M2] ─> S09 ─> [M3] ─> S10 ─> [M4] ─> S11 ─> [M5]
                                             └─> F04 <─ F03+S10
```

**Next actionable item:** Complete S00 source-freeze manifest and S01 bootstrap design; the new GitHub repository is created only on explicit authorization. Target **M1 read-only alpha** before enabling any privileged writes in the new app.

## 9. Decision register (unresolved, not blockers for S00)

- Final app display name and icon (candidate `MMRL Xposed`).
- Final package namespace (provisional `io.github.mikeyphw.mmrl.lsposed`).
- Independent seed from filtered history vs selected source imports with preservation of commit provenance and GPL terms.
- Signing authority, release channel and update trust policy.
- Initial module catalog providers and download source approval rules.
- Future adapter qualification matrix for other LSPosed flavors.

## 10. Primary reference set

- Handoff archive: `MMRL_LSPosed_Standalone_Devtool_Federation_Handoff_2026-11-03.zip` (especially `START_NEW_CHAT.md`, `COMPLETE_UX_SPEC.md`, `AUDIT.md`, `FEDERATION_PLAN.md`).
- Current MMRL Phase07 PR: https://github.com/Mikeyphw/MMRL/pull/7
- Verified hosted build: https://github.com/Mikeyphw/MMRL/actions/runs/41672905479
- Devtool federation source documentation: https://github.com/Mikeyphw/devtool/blob/b164463db51e2cffd12f753043b9e55a08cc43ae/docs/architecture/federated-repositories.md

---

**Status at document creation:** Roadmap created. No GitHub repository, branch, CI workflow, APK or overlay was created or modified as part of producing this roadmap.

## R4D-01 — supply-chain evidence groundwork (implementation candidate; not qualified)

Adds `scripts/ci_r4d_supply_chain.py`, its read-only GitHub Actions workflow, CycloneDX partial resolved-runtime inventory, unverified third-party licensing register, exact source/run/attempt evidence digest manifest and deliberately `BLOCKED` report. No release admission or device acceptance is implied by passing CI. Remaining R4D tasks: independently attest the actual release candidate APK, verify protected Environment settings and authenticated release signer; add complete dependency/plugin and third-party license verification; API26 smoke, physical S11 and R4C restore/data evidence; independent gate with protected manual publication. See `docs/R4D_SUPPLY_CHAIN_AND_QUALIFICATION.md`.

## R5 — application-first refinement (after merged R4D-01)

Defer R4D-02 and later multi-scope SBOM/provenance infrastructure. Maintain existing security boundaries without inventing new release gates. R5A (this source candidate) improves Modules list/detail presentation and preserves guarded writes. After hosted R5A checks and real-phone visual review: R5B scope-editor usability, R5C Discover/Downloads, and R5D device polish. Keep Vector, Kotlin app, CI and security foundations separate from nonessential enterprise supply-chain work.

## R5B — Scope Editor UX refinement (source candidate, 2026-11-05)

- The saved-scope editor now shows counts for all/selected/suggested targets, tokenized local search across labels and packages, a clear/reset affordance, and better empty states. Read-only targets remain visually distinguished; a failed saved-scope read offers an explicit user-tap retry.
- Existing immediate, one-at-a-time journaled Vector writes, fresh pre-state, read-back verification and disabled-module enable protection remain unchanged. No root call on Compose rendering, no bulk scope write, and no automatic retry after unknown mutation outcome.
- Python/JVM tests and GitHub Actions API36 emulator build are required; real-device scope browsing and visual acceptance remain separate. No changes to production signing.

## R5C — Discover and Downloads everyday UX (candidate; hosted/device acceptance pending)

After R5A Modules and R5B Scope Editor, R5C adds progressive local catalog browsing, resettable searches and metadata-aware Downloads/CI artifact filtering, without new networking or release qualification layers. See `docs/R5C_DISCOVER_DOWNLOADS_UX.md`. Next: R5D real-device visual acceptance and targeted stability fixes. R4D-02 multi-scope provenance is deferred, not required for this UI work.

## R5D — physical UX acceptance and focused cold-start fixes

After merged R5C, retain the R5A/B/C product refinements and fix two concrete stability gaps: persist appearance after process restart; show a real Home module-scan failure with manual retry. Provide only an optional, non-root ADB status/screenshot capture helper for the physical review. Do not add release framework or implicit device writes. Hosted Android tests and trusted-main signing must succeed; physical screenshots and cold-start UX need separate manual acceptance. See `docs/R5D_DEVICE_STABILITY.md`.
