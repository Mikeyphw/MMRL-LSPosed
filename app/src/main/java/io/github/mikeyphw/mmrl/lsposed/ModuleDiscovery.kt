package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Unprivileged inventory of installed APKs that advertise Xposed metadata.
 *
 * This scanner does NOT read Vector, LSPosed daemon state, saved scope or live hooks.
 * It never loads module code and never executes root/shell commands.
 */
internal object ModuleDiscovery {
    enum class Format { MODERN, LEGACY, BOTH }

    data class Candidate(
        val packageName: String,
        val name: String,
        val format: Format,
    )

    @Suppress("DEPRECATION")
    fun scan(context: Context): List<Candidate> {
        val manager = context.packageManager
        return manager.getInstalledApplications(PackageManager.GET_META_DATA)
            .mapNotNull { application ->
                val isLegacy = hasLegacyFlag(application) || entryExists(application, "assets/xposed_init")
                val isModern = entryExists(application, "META-INF/xposed/java_init.list") ||
                    entryExists(application, "META-INF/xposed/native_init.list")
                if (!isLegacy && !isModern) return@mapNotNull null
                val label = runCatching { manager.getApplicationLabel(application).toString().trim() }
                    .getOrDefault(application.packageName)
                    .ifBlank { application.packageName }
                Candidate(
                    packageName = application.packageName,
                    name = label,
                    format = when {
                        isLegacy && isModern -> Format.BOTH
                        isModern -> Format.MODERN
                        else -> Format.LEGACY
                    },
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.lowercase(Locale.getDefault()) })
    }

    private fun hasLegacyFlag(application: ApplicationInfo): Boolean =
        when (val flag = application.metaData?.get("xposedmodule")) {
            is Boolean -> flag
            is String -> flag.equals("true", ignoreCase = true)
            else -> false
        }

    private fun entryExists(application: ApplicationInfo, name: String): Boolean {
        val source = application.sourceDir ?: return false
        return runCatching { ZipFile(source).use { it.getEntry(name) != null } }.getOrDefault(false)
    }
}
