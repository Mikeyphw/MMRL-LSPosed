package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import android.os.Process
import java.util.Locale

/** Reverse lookup of saved Vector scope configuration, never proof of hook execution. */
internal object AppsReverseIndex {
    private const val MAX_MODULES = 128
    private const val MAX_APPS = 5000

    data class Target(
        val packageName: String,
        val label: String,
        val installed: Boolean,
        val systemTarget: Boolean,
        val modules: List<String>,
        val userId: Int = 0,
    ) {
        /** Vector scope keys include the Android user; package alone is not unique. */
        val key: String get() = "$packageName/$userId"
    }

    data class Snapshot(
        val targets: List<Target>,
        val observedAtMillis: Long,
        val inspectedModules: Int,
        /** Package visibility and labels were read in this user, not in other profiles. */
        val inventoryUserId: Int = 0,
    ) {
        val scopedApps: Int get() = targets.count { it.modules.isNotEmpty() }
    }

    /** Explicitly invoked from a user action. One bounded read per saved module. */
    @Suppress("DEPRECATION")
    fun inspect(context: Context, baseline: VectorReadOnly.Snapshot): Snapshot {
        require(baseline.apiVersion == 102) { "Only Vector API 102 is supported" }
        require(baseline.modules.size <= MAX_MODULES) { "Too many configured modules for a safe scope index" }
        val byModule = linkedMapOf<String, List<VectorProtocol.Scope>>()
        for (module in baseline.modules.keys.sorted()) {
            byModule[module] = VectorReadOnly.scopes(context, module)
        }
        // Detect enabled-module drift. Vector does not offer a transaction across
        // other clients' concurrent scope edits; timestamps and refresh stay visible.
        val after = VectorReadOnly.inspect(context)
        require(after.apiVersion == baseline.apiVersion && after.modules == baseline.modules) {
            "Vector module configuration changed; refresh and rebuild the index"
        }
        // An app only sees a package inventory for its own Android user. Do not
        // claim packages from other Vector scopes are installed or absent there.
        val inventoryUserId = Process.myUid() / 100_000
        val installed = context.packageManager.getInstalledApplications(0)
            .map { it.packageName }.distinct().take(MAX_APPS + 1)
        require(installed.size <= MAX_APPS) { "Installed app inventory exceeds safe index bound" }
        return Snapshot(
            targets = reverse(installed.toSet(), byModule, inventoryUserId).map { target ->
                if (!target.installed) target else target.copy(label = runCatching {
                    @Suppress("DEPRECATION")
                    val app = context.packageManager.getApplicationInfo(target.packageName, 0)
                    context.packageManager.getApplicationLabel(app).toString()
                }.getOrDefault(target.packageName).take(150))
            },
            observedAtMillis = System.currentTimeMillis(),
            inspectedModules = baseline.modules.size,
            inventoryUserId = inventoryUserId,
        )
    }

    /**
     * Pure read-only projection. A target is identified by (package, Android user).
     * Other-user configuration remains visible but is NOT an installed-package claim.
     * Scope editing is deliberately unchanged and still restricted to user 0.
     */
    fun reverse(
        installedPackages: Set<String>,
        scopesByModule: Map<String, List<VectorProtocol.Scope>>,
        inventoryUserId: Int = 0,
    ): List<Target> {
        require(inventoryUserId >= 0) { "Invalid local Android user" }
        val memberships = linkedMapOf<Pair<String, Int>, MutableSet<String>>()
        for ((module, scopes) in scopesByModule) {
            require(VectorProtocol.validPackage(module)) { "Invalid configured module package" }
            for (scope in scopes) {
                require(scope.userId >= 0) { "Invalid Android user ID" }
                require(VectorProtocol.validPackage(scope.packageName) ||
                    scope.packageName in setOf("android", "system")) {
                    "Invalid configured target package"
                }
                memberships.getOrPut(scope.packageName to scope.userId) { linkedSetOf() }.add(module)
            }
        }
        val visibleKeys = installedPackages.map { it to inventoryUserId }
        return (visibleKeys + memberships.keys).distinct().map { (pkg, user) ->
            Target(
                packageName = pkg,
                label = pkg,
                installed = user == inventoryUserId && pkg in installedPackages,
                systemTarget = pkg == "android" || pkg == "system",
                modules = memberships[pkg to user]?.sorted().orEmpty(),
                userId = user,
            )
        }.sortedWith(compareByDescending<Target> { it.modules.isNotEmpty() }
            .thenBy { it.packageName.lowercase(Locale.ROOT) }
            .thenBy { it.userId })
    }
}

internal sealed interface AppsIndexState {
    data object Idle : AppsIndexState
    data object Loading : AppsIndexState
    data class Ready(val snapshot: AppsReverseIndex.Snapshot) : AppsIndexState
    data class Unavailable(val reason: String) : AppsIndexState
}
