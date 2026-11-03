package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import java.io.IOException
import java.util.Locale

/** Vector saved configuration is inspected only after a user-initiated Connect. */
internal object VectorReadOnly {
    data class Snapshot(
        val frameworkVersion: String,
        val apiVersion: Int,
        val modules: Map<String, VectorProtocol.Module>,
        val observedAtMillis: Long,
    ) {
        val enabledCount: Int get() = modules.values.count { it.enabled }
    }

    fun inspect(context: Context): Snapshot {
        val status = VectorProtocol.status(VectorRootTransport.query(context, "status"))
        val modules = VectorProtocol.modules(VectorRootTransport.query(context, "modules ls"))
        return Snapshot(status.version, status.api, modules, System.currentTimeMillis())
    }

    fun scopes(context: Context, modulePackage: String): List<VectorProtocol.Scope> {
        if (!VectorProtocol.validPackage(modulePackage)) throw IOException("Invalid module package")
        VectorProtocol.status(VectorRootTransport.query(context, "status"))
        return VectorProtocol.scopes(VectorRootTransport.query(context, "scope ls", modulePackage))
    }

    /** Retain configured-but-uninstalled packages, without fabricating APK metadata. */
    fun mergeInstalled(installed: List<ModuleDiscovery.Candidate>, snapshot: Snapshot?):
        List<ModuleDiscovery.Candidate> {
        if (snapshot == null) return installed
        val rows = installed.associateBy { it.packageName }.toMutableMap()
        snapshot.modules.keys.forEach { pkg ->
            if (pkg !in rows) rows[pkg] = ModuleDiscovery.Candidate(
                packageName = pkg, name = pkg, format = ModuleDiscovery.Format.UNKNOWN,
            )
        }
        return rows.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }
}

/** Never label saved module flags or scope membership as live hook success. */
internal sealed interface VectorState {
    data object Disconnected : VectorState
    data object Connecting : VectorState
    data class Ready(val snapshot: VectorReadOnly.Snapshot) : VectorState
    data class Unavailable(val reason: String) : VectorState
}

internal sealed interface ScopeState {
    data object Idle : ScopeState
    data object Loading : ScopeState
    data class Ready(val entries: List<VectorProtocol.Scope>, val observedAtMillis: Long = System.currentTimeMillis()) : ScopeState
    data class Unavailable(val reason: String) : ScopeState
}
