# S08 — Guarded Vector saved-configuration writes

**State:** implementation candidate. S04–S07 accepted by maintainer; S08 device-write
qualification NOT YET PERFORMED. Do not treat GitHub signing or Kotlin tests as hook verification.

## Behavior

- Explicit Connect remains mandatory; app discovery never prompts for root.
- Module detail has an Enable/Disable action with explicit confirmation. It first reads the
  latest module flag and saved scope, then requires the result to match the reviewed state.
- Scope picker remains a **local draft** until the user chooses Review, then confirms a **single
  target**. No Apply All: remaining draft selections are discarded after success.
- Only installed, non-system, non-manager Android user-0 packages can be edited. Special
  `android`/`system`, manager/self, other users, unknown/uninstalled and static-scope-excluded
  targets remain read-only. Adding scope while the module is disabled is refused (Vector 2.2
  could implicitly enable the module).
- The root IPC exposes only `status`, `modules ls`, `scope ls`, `modules enable`, `modules disable`,
  `scope add` and `scope rm`; one module and, where relevant, one `/0` target per operation.
  No shell strings, `scope set`, config/db/log operations, or device-root installation.
- Every write has a synchronous write-ahead record in private SharedPreferences. The app
  verifies full module inventory and the complete target module scope afterwards and only
  clears the pending record after durable receipt persistence.
- Any thrown/timeout/cancelled command **after journal creation** leaves the pending lock.
  The Home screen then provides Inspect saved state → Acknowledge observed state. It performs
  two separate reads and never retries or rolls back. If those readings disagree, the lock stays.
- A fully verified scope write or module flag is **saved configuration**, not evidence
  that hooks are running in a target process.

## Device acceptance checklist (disposable lab only)

1. Install the stable-signed hosted debug APK and confirm the package and signer match prior
   builds. Connect manually. Record enabled flag and complete saved scopes for disposable
   hook-lab module and target packages in Vector's original manager.
2. Confirm enabling a **disabled disposable module** shows an explicit confirmation and
   saves exactly one enable; read-back and receipt must agree. Disable and confirm restoration.
3. Test a **single** scope add/remove for an installed, ordinary user-0 disposable target.
   Verify one change at a time, preserve unrelated entries, and restore the original exact scope.
4. Attempt forbidden targets: `android`, `system`, another Android user, own manager, another
   manager, uninstalled, system apps, and outside a module's nonempty `staticScope`.
   They must never be written. Scope addition on a disabled module must be refused.
5. Test stale baselines and a simulated/observed timeout or root denial. There must be no
   automatic retry; an uncertain outcome requires manual reconciliation before another write.
6. Verify the private journal survives app process death and that the explicit reconciliation
   re-reads twice before unlocking. Canceling reconciliation leaves writes locked.
7. Compare every before/after record against Vector's original manager. Confirm exact complete
   restoration. Do not use ordinary personal modules for automated mutation tests.

## Limitations / separate work

There is no multi-action transaction: concurrent external Vector manager edits can make a
post-write comparison fail. A read-back failure does not prove rollback. Android process
termination while a write is active may leave an uncertain journal, intentionally blocking
later writes. The finite receipt ledger stores the latest 64 records locally. S09 reverse
app lookup and S10 catalogs/downloads are not part of this overlay.
