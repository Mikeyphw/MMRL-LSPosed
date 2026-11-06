# R5B — Everyday scope management UX

This slice is a visual/presentation refinement, not a change in Vector authority or mutation semantics.

## Behavior

- The scope header clearly says **saved**, not active hooks. The All/Selected/Suggested chips display exact current snapshot counts; they are scrollable on narrow screens.
- Search matches multiple terms against app label and package name. A clear control appears only for non-empty searches. Empty states offer a one-tap reset to All.
- Saved-but-unavailable targets remain visible and read-only. Existing system, manager, different-user, module-self and static-scope restrictions remain enforced by the original toggle gate.
- If the initial read fails, **Retry scope read** performs a new explicit read. It does not issue a write or reconcile an uncertain write. The UI never automatically retries mutations.
- Only a user tap on an eligible target issues one Vector request through the existing journaled `VectorMutation.change` path. Source changes do not introduce batch editing, hidden enablement, or review-first flows.

## Tests and acceptance

- Run local Python contracts in the existing checkout; hosted Devtool CI owns real Kotlin/JVM compilation, API36 non-root UI, signing and provenance checks.
- Presentation JVM model tests cover saved/suggested counting, multi-token query matching and empty-state messages.
- Physical device: browse a previously verified disposable module, check selected/suggested counts, search/reset, try supported toggles only with explicit acceptance, and verify saved scopes and restoration. **Do not test writes against ordinary modules.**
- Green hosted CI does not constitute physical-device acceptance.
