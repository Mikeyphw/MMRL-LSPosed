package io.github.mikeyphw.mmrl.lsposed

/**
 * Read-only comparison of installed APK versionName with Vector's public catalog.
 * A matching package and numeric version do NOT establish publisher identity,
 * signature continuity, compatibility, or safety to install an APK.
 */
internal object ModuleVersionIndex {
    enum class Verdict {
        NEWER_IN_CATALOG, SAME_VERSION, INSTALLED_NEWER,
        NEEDS_MANUAL_REVIEW, VERSION_UNKNOWN, NOT_LISTED, CATALOG_UNAVAILABLE,
    }

    data class Assessment(
        val verdict: Verdict,
        val installedVersion: String,
        val catalogVersion: String = "",
    )

    /** Intentionally excludes prereleases, build metadata, date text and Git SHAs.
     *  This is a strict numeric ordering hint, not full SemVer implementation. */
    private val VERSION = Regex("[vV]?([0-9]+(?:\\.[0-9]+){1,3})")

    private fun numeric(value: String): List<Long>? {
        val raw = value.trim()
        if (raw.length !in 3..48) return null
        val parts = VERSION.matchEntire(raw)?.groupValues?.getOrNull(1)?.split('.') ?: return null
        if (parts.any { it.length > 9 || (it.length > 1 && it.startsWith('0')) }) return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        return numbers + List(4 - numbers.size) { 0L }
    }

    fun assess(installed: String, published: String?): Assessment {
        val local = installed.trim().take(80)
        val remote = published?.trim()?.take(80).orEmpty()
        if (local.isEmpty() || remote.isEmpty()) return Assessment(Verdict.VERSION_UNKNOWN, local, remote)
        val a = numeric(local)
        val b = numeric(remote)
        if (a == null || b == null) return Assessment(Verdict.NEEDS_MANUAL_REVIEW, local, remote)
        val comparison = (0 until 4).firstNotNullOfOrNull { i ->
            a[i].compareTo(b[i]).takeIf { it != 0 }
        } ?: 0
        val verdict = when {
            comparison < 0 -> Verdict.NEWER_IN_CATALOG
            comparison > 0 -> Verdict.INSTALLED_NEWER
            else -> Verdict.SAME_VERSION
        }
        return Assessment(verdict, local, remote)
    }

    fun forInstalled(
        installed: List<ModuleDiscovery.Candidate>,
        catalog: List<VectorModuleCatalog.Module>?,
    ): Map<String, Assessment> {
        if (catalog == null) return installed.associate { it.packageName to
            Assessment(Verdict.CATALOG_UNAVAILABLE, it.versionName) }
        val listed = catalog.associateBy { it.packageName }
        return installed.associate { candidate ->
            val entry = listed[candidate.packageName]
            candidate.packageName to if (entry == null)
                Assessment(Verdict.NOT_LISTED, candidate.versionName)
            else assess(candidate.versionName, entry.version)
        }
    }

    fun newerCount(assessments: Map<String, Assessment>): Int =
        assessments.values.count { it.verdict == Verdict.NEWER_IN_CATALOG }

    fun comparableCount(assessments: Map<String, Assessment>): Int =
        assessments.values.count { it.verdict in setOf(
            Verdict.NEWER_IN_CATALOG, Verdict.SAME_VERSION, Verdict.INSTALLED_NEWER) }
}
