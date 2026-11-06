# R5A — Modules and Module Detail UX refinement

Base: `Mikeyphw/MMRL-LSPosed` main `72051386de2178543e1012e73a2952f73854bec6` (after merged R4D-01). Supersedes no underlying Vector or release policy.

## Product changes

- Modules header presents counts for *actually detected APKs*, never counts unrelated Vector entries as installed. When Vector is unavailable no invented disabled count appears.
- Search supports immediate clear. Quick saved-state filter chips are visible; enabled/disabled/not-indexed filters are disabled until saved Vector state exists. Advanced type and sort controls remain available, with one-tap reset and a useful no-results recovery.
- Each module uses a compact Material 3 card with app icon, name, version, description, saved-state label, format and cached scope count. Update hints remain explicitly untrusted version comparison. Repository banner is shown only for a positive cached comparison; no network fetch occurs on Modules composition.
- Module Detail emphasizes the icon, name, saved state, scope entry and existing **confirmation-backed** enable/disable action. Technical metadata is under an explicit expand control.
- English-only product strings are preserved; no debug diagnostics or verification receipts moved into the list cards.

## Safety and tests

- Existing `VectorMutation`, `VectorWriteJournal`, `VectorReadOnly`, root transport, `ScopeDraftPage`, signing policy, workflow and configuration files are unchanged.
- R5A adds pure JVM summary/filter tests, Python source contracts, and extends the existing four-test API36 non-root UI class without changing its test count.
- Any saved-state toggle still requires fresh Vector read, preview, explicit confirmation, verified write and journal handling. List chips **only filter** and never toggle modules.
- CI acceptance requires real hosted Kotlin/Compose compile, JVM, lint, API36 emulator, stable debug signing and provenance. Physical Android device inspection remains necessary for visual and state correctness. No production release is authorized.
