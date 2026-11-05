package io.github.mikeyphw.mmrl.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure read-only regression coverage. No downloads or privileged device operations. */
class R2BModuleVersionIndexTest {
    private fun v(local: String, remote: String?) = ModuleVersionIndex.assess(local, remote).verdict

    @Test fun strictNumericVersionsOrderWithoutLexicographicMistakes() {
        assertEquals(ModuleVersionIndex.Verdict.NEWER_IN_CATALOG, v("1.9.9", "v1.10.0"))
        assertEquals(ModuleVersionIndex.Verdict.SAME_VERSION, v("v2.4", "2.4.0"))
        assertEquals(ModuleVersionIndex.Verdict.INSTALLED_NEWER, v("3.2.0", "3.1.99"))
        assertEquals(ModuleVersionIndex.Verdict.NEWER_IN_CATALOG, v("1.2.3", "1.2.3.1"))
        assertEquals(ModuleVersionIndex.Verdict.SAME_VERSION, v("1.2.0.0", "1.2"))
    }

    @Test fun prereleasesAndNonNumericTagsNeverBecomeConfirmedUpdates() {
        for (remote in listOf("2.0-beta", "2.0+build17", "2026-11-05", "main-abc123", "2.01.0", "1.2.3.4.5", "10000000000.0", "2.0.0 (4)", "2.0.0\\nInjected")) {
            assertEquals(ModuleVersionIndex.Verdict.NEEDS_MANUAL_REVIEW,
                v("1.0.0", remote))
        }
        assertEquals(ModuleVersionIndex.Verdict.NEEDS_MANUAL_REVIEW, v("1.0.0-rc1", "2.0.0"))
        assertEquals(ModuleVersionIndex.Verdict.VERSION_UNKNOWN, v("", "2.0.0"))
        assertEquals(ModuleVersionIndex.Verdict.VERSION_UNKNOWN, v("1.0.0", ""))
    }

    @Test fun cacheAbsenceOrUnlistedModuleNeverInventsAnUpdate() {
        val local = listOf(ModuleDiscovery.Candidate("com.example.module", "Example", ModuleDiscovery.Format.MODERN, versionName="1.0.0"))
        val unavailable = ModuleVersionIndex.forInstalled(local, null)
        assertEquals(ModuleVersionIndex.Verdict.CATALOG_UNAVAILABLE, unavailable["com.example.module"]!!.verdict)
        assertEquals(0, ModuleVersionIndex.newerCount(unavailable))
        val missing = ModuleVersionIndex.forInstalled(local, emptyList())
        assertEquals(ModuleVersionIndex.Verdict.NOT_LISTED, missing["com.example.module"]!!.verdict)
        assertEquals(0, ModuleVersionIndex.comparableCount(missing))
    }

    @Test fun exactPackageIdentityAndVerifiedComparisonCountOnly() {
        val installed = listOf(
            ModuleDiscovery.Candidate("com.example.one", "One", ModuleDiscovery.Format.MODERN, versionName="1.0.0"),
            ModuleDiscovery.Candidate("com.example.two", "Two", ModuleDiscovery.Format.LEGACY, versionName="1.0.0-beta"),
        )
        val catalog = listOf(
            VectorModuleCatalog.Module("com.example.one", "One", "", 1, "", "2.0.0", ""),
            VectorModuleCatalog.Module("com.example.two", "Two", "", 1, "", "2.0.0", ""),
            VectorModuleCatalog.Module("com.example.one.extra", "Other", "", 1, "", "9.9.9", ""),
        )
        val assessments = ModuleVersionIndex.forInstalled(installed, catalog)
        assertEquals(1, ModuleVersionIndex.newerCount(assessments))
        assertEquals(1, ModuleVersionIndex.comparableCount(assessments))
        assertTrue(assessments["com.example.two"]!!.verdict == ModuleVersionIndex.Verdict.NEEDS_MANUAL_REVIEW)
    }
}
