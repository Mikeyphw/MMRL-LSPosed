package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Looper
import java.io.IOException

/**
 * Vector 2.2/API 102 write gate, one user-confirmed action per invocation.
 * No batching, optimistic UI, automatic retries, or rollback assumptions.
 * Saved daemon configuration is NOT proof of live hooks.
 */
internal object VectorMutation {
    enum class Action { ENABLE, DISABLE, ADD_SCOPE, REMOVE_SCOPE }
    data class Request(
        val module: String,
        val action: Action,
        val expectedEnabled: Boolean,
        val expectedScopes: List<VectorProtocol.Scope>,
        val target: String = "",
    )
    data class Outcome(
        val snapshot: VectorReadOnly.Snapshot,
        val scopes: List<VectorProtocol.Scope>,
        val receiptId: String,
    )
    private val gate = Any()

    fun change(context: Context, request: Request): Outcome = synchronized(gate) {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw IOException("Configuration writes must run on Dispatchers.IO")
        if (!VectorProtocol.validPackage(request.module)) throw IOException("Invalid module identifier")
        val app = context.applicationContext
        if (VectorWriteJournal.pending(app) != null)
            throw IOException("Previous write outcome unknown. Reconcile before another change.")
        val candidate = ModuleDiscovery.scan(app).singleOrNull { it.packageName == request.module }
            ?: throw IOException("Module APK must be installed and recognized before editing")
        val pre = VectorReadOnly.inspect(app)
        val module = pre.modules[request.module] ?: throw IOException("Module not present in Vector")
        val oldScopes = VectorReadOnly.scopes(app, request.module)
        if (module.enabled != request.expectedEnabled || oldScopes != request.expectedScopes)
            throw IOException("Saved configuration changed since review; refresh before writing")
        val operation: String
        val arg: String
        val wantedEnabled: Boolean
        val wantedScopes: List<VectorProtocol.Scope>
        when (request.action) {
            Action.ENABLE, Action.DISABLE -> {
                if (request.target.isNotEmpty()) throw IOException("Unexpected scope target")
                wantedEnabled = request.action == Action.ENABLE
                if (wantedEnabled == module.enabled) throw IOException("Module already in requested state")
                operation = if (wantedEnabled) "modules enable" else "modules disable"
                arg = ""
                wantedScopes = oldScopes
            }
            Action.ADD_SCOPE, Action.REMOVE_SCOPE -> {
                safeTarget(app, candidate, request.target)
                if (request.action == Action.ADD_SCOPE && !module.enabled)
                    throw IOException("Vector implicitly enables on scope add. Enable this module separately first.")
                val item = VectorProtocol.Scope(request.target, 0)
                val contains = item in oldScopes
                if (contains == (request.action == Action.ADD_SCOPE))
                    throw IOException("Scope target is already in requested state")
                wantedEnabled = module.enabled
                operation = if (request.action == Action.ADD_SCOPE) "scope add" else "scope rm"
                arg = "${request.target}/0"
                wantedScopes = (if (request.action == Action.ADD_SCOPE) oldScopes + item
                    else oldScopes.filterNot { it == item })
                    .sortedWith(compareBy({ it.packageName }, { it.userId }))
            }
        }
        val description = "enabled=${module.enabled};scope=" + oldScopes.joinToString(",") { it.key }
        val entry = VectorWriteJournal.begin(app, request.module, request.action.name, request.target)
        // From this line, every error or interruption retains a durable pending journal.
        // Never retry, even if the CLI returned an error after reaching Vector.
        val ack = VectorRootTransport.mutate(app, operation, request.module, arg)
        if (operation.startsWith("modules "))
            VectorWritePolicy.verifyModuleAcknowledgement(ack, operation, request.module)
        else VectorWritePolicy.verifyScopeAcknowledgement(ack)
        val post = VectorReadOnly.inspect(app)
        val postScopes = VectorReadOnly.scopes(app, request.module)
        VectorWritePolicy.verifyReadBack(pre.modules, post.modules, request.module,
            wantedEnabled, oldScopes, postScopes, wantedScopes)
        val after = "enabled=$wantedEnabled;scope=" + postScopes.joinToString(",") { it.key }
        VectorWriteJournal.verified(app, entry, description, after)
        Outcome(post, postScopes, entry.id)
    }

    /** No system, manager, static-outside-declaration, uninstalled or other-user edits. */
    @Suppress("DEPRECATION")
    private fun safeTarget(context: Context, module: ModuleDiscovery.Candidate, target: String) {
        if (!VectorProtocol.validPackage(target) || target == module.packageName ||
            target == context.packageName || target.startsWith("io.github.mikeyphw.mmrl") ||
            target.startsWith("com.dergoogler.mmrl") ||
            target in setOf("org.matrix.vector.manager", "org.lsposed.manager", "io.github.libxposed.manager"))
            throw IOException("Unsafe or unsupported scope target")
        if (module.staticScope && target !in module.declaredScopes)
            throw IOException("Target is outside module-declared static scope")
        val application = try { context.packageManager.getApplicationInfo(target, 0) }
        catch (e: PackageManager.NameNotFoundException) {
            throw IOException("Scope target must be installed for primary user", e)
        }
        if ((application.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
            throw IOException("System app scope changes require a separate review")
    }

    data class Review(val entry: VectorWriteJournal.Pending,
                      val snapshot: VectorReadOnly.Snapshot,
                      val scopes: List<VectorProtocol.Scope>)

    /** Explicit read-only step 1: present observed state before unlocking. */
    fun reviewUncertain(context: Context): Review = synchronized(gate) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("IO worker required")
        val entry = VectorWriteJournal.pending(context) ?: throw IOException("No pending operation")
        val observed = VectorReadOnly.inspect(context)
        val scopes = VectorReadOnly.scopes(context, entry.module)
        Review(entry, observed, scopes)
    }

    /** Explicit step 2: re-read and confirm the observation has not changed. Never writes Vector. */
    fun acknowledgeReview(context: Context, review: Review): VectorReadOnly.Snapshot = synchronized(gate) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("IO worker required")
        val active = VectorWriteJournal.pending(context) ?: throw IOException("No pending operation")
        if (active.id != review.entry.id) throw IOException("Pending operation changed")
        val latest = VectorReadOnly.inspect(context)
        val scopes = VectorReadOnly.scopes(context, active.module)
        if (latest.modules != review.snapshot.modules || scopes != review.scopes)
            throw IOException("Vector state changed again; refresh reconciliation first")
        val state = latest.modules[active.module]?.enabled?.toString() ?: "missing"
        VectorWriteJournal.reconciled(context, active,
            "enabled=$state;scope=" + scopes.joinToString(",") { it.key })
        latest
    }
}
