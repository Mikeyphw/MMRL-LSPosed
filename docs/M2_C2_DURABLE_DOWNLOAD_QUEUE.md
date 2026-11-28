# M2-C2 — Durable first-party download queue and hosted KSP recovery

Base: MMRL-LSPosed PR 20, commit `6c72a2a80e120884edb8283cd9de49e45fcb0c24`, tree `a8cdbaf5c1ebbc70bc3b137e9d85c60623fac913`, Shared pin `610ea806b53f9236b7368afd1263193acc6a2c20`.

## Downloads

Queue eligible first-party release files or CI ZIPs from Downloads. Two application-owned workers, a 64-row durable private queue, three bounded transient attempts, per-row progress and cancellation, explicit retry or removal, and the existing verified-file custody gates. Navigation does not cancel a transfer. Process death reconciles active rows to INTERRUPTED; opening Downloads or Activity does not start network work. Explicit retry begins a new bounded budget and fresh identity lookup. Activity remains read-only and shows the linked transfer attempt, not an installer or Vector action. Queue files contain public source identity only; credentials and signed redirects remain out of persistence. Private partial bytes are resumed only with the same source, storage host, strong ETag, exact Content-Range, encoding and size. Otherwise safely restart; full size, digest and format checks and non-replacing publication still apply. Cancellation removes owned partial bytes but never removes a published file. Verified data is not APK signing trust, release acceptance or installation authorization. There is no background service guarantee when Android suspends or kills the app, nor third-party repository integration in this slice.

## Hosted KSP resilience

KSP stays at 2.3.6. Before the original S01 and R4D tasks, the hosted Linux configuration preflight checks Gradle cache objects and their content addresses, independently observed marker and implementation POM hashes, plugin descriptor, and canonical HTTPS SHA-256 for other KSP objects or the trusted-main exact verification receipt. POM pins come from S01 46105518678 attempt 2 diagnostic artifact 15247711755 (archive SHA-256 `0c374d24786cb5501f83a4ee72f85d23c239d983386643f0bed2ad13349844c4`). Both canonical endpoints returned HTTP 200 and identical POM hashes. These are byte-integrity observations, not publisher signatures. Cache writes are confined to successful trusted main S01; PR and R4D are read-only. Configuration attempts are bounded; only classified transient dependency failures retry. Missing cache may resolve online; corrupt or conflicting cache fails closed. Configuration success does not replace any native build, lint, JVM, emulator, R4D inventory or device gate. No local Gradle execution. CI rate limits can still exhaust retries; no unconditional success promise.

## Qualification

Standalone JVM behavior cases exercise the queue and publication helpers (including foreign-host range refusal); offline Python fixtures exercise KSP cache and retry policy. Existing tests remain mandatory. Android builds and real device storage/network/process recovery require the corresponding hosted and physical acceptance separately.
