package io.github.mikeyphw.mmrl.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/** Offline presentation regression; no Vector connection and no catalog network request. */
class R1ModuleAndCatalogSortTest {
    private fun module(pkg: String, label: String, api: Int = 0,
        format: ModuleDiscovery.Format = ModuleDiscovery.Format.MODERN,
        description: String = "") = ModuleDiscovery.Candidate(
        packageName = pkg, name = label, format = format, minApiVersion = api,
        description = description,
    )

    @Test fun savedStatusIsGroupedWithoutClaimingHookExecution() {
        val installed = listOf(module("com.example.alpha", "Alpha"),
            module("com.example.beta", "Beta"), module("com.example.gamma", "Gamma"))
        val result = ModuleListModel.groups(installed, mapOf(
            "com.example.alpha" to true, "com.example.beta" to false))
        assertEquals(listOf(ModuleListModel.GroupKind.ENABLED,
            ModuleListModel.GroupKind.DISABLED,
            ModuleListModel.GroupKind.NOT_INDEXED), result.map { it.kind })
        assertEquals(listOf("com.example.alpha", "com.example.beta", "com.example.gamma"),
            result.flatMap { it.modules }.map { it.packageName })
        assertEquals(listOf(ModuleListModel.GroupKind.UNKNOWN),
            ModuleListModel.groups(installed, null).map { it.kind })
    }

    @Test fun moduleSearchAndSortAreDeterministicAndReadOnly() {
        val installed = listOf(module("com.example.c", "Zulu", 92),
            module("com.example.a", "Alpha", 102, description = "Screenshots"),
            module("com.example.b", "Beta", 101, format = ModuleDiscovery.Format.LEGACY))
        assertEquals(listOf("Zulu", "Beta", "Alpha"), ModuleListModel.groups(
            installed, null, sort = ModuleListModel.Sort.NAME_ZA).flatMap { it.modules }.map { it.name })
        assertEquals(listOf(102, 101, 92), ModuleListModel.groups(installed, null,
            sort = ModuleListModel.Sort.API_DESC).flatMap { it.modules }.map { it.minApiVersion })
        assertEquals("Alpha", ModuleListModel.groups(installed, null,
            query = "screenShots", format = ModuleListModel.FormatFilter.MODERN)
            .single().modules.single().name)
        assertTrue(ModuleListModel.groups(installed, null,
            state = ModuleListModel.StateFilter.ENABLED).isEmpty())
    }

    private fun repo(pkg: String, title: String, stars: Int, updated: String) =
        VectorModuleCatalog.Module(pkg, title, "", stars, updated, "v1", "https://modules.lsposed.org/module/$pkg")

    @Test fun repositorySortSupportsPopularityAndNameWithStableTieBreakers() {
        val input = listOf(repo("a.example.z", "Zulu", 12, "2026-01"),
            repo("a.example.b", "Alpha", 3, "2026-03"),
            repo("a.example.a", "Alpha", 12, "2026-02"))
        fun packages(sort: VectorModuleCatalog.Sort) = VectorModuleCatalog.sort(input, sort)
            .map { it.packageName }
        assertEquals(listOf("a.example.b", "a.example.a", "a.example.z"),
            packages(VectorModuleCatalog.Sort.UPDATED_NEWEST))
        assertEquals(listOf("a.example.a", "a.example.z", "a.example.b"),
            packages(VectorModuleCatalog.Sort.MOST_STARS))
        assertEquals(listOf("a.example.a", "a.example.b", "a.example.z"),
            packages(VectorModuleCatalog.Sort.NAME_AZ))
        assertEquals(listOf("a.example.z", "a.example.a", "a.example.b"),
            packages(VectorModuleCatalog.Sort.NAME_ZA))
    }

    @Test fun hiddenOrUnreleasedDuplicateCannotSuppressValidCatalogEntry() {
        fun row(hidden: Boolean, hasRelease: Boolean) = JSONObject()
            .put("name", "com.example.duplicate")
            .put("description", "Visible module")
            .put("hide", hidden)
            .put("latestRelease", "1.0")
            .put("latestReleaseTime", "2026-11-04T12:00:00Z")
            .put("releases", if (hasRelease) JSONArray().put(JSONObject()) else JSONArray())
        val data = JSONArray().put(row(hidden = true, hasRelease = true))
            .put(row(hidden = false, hasRelease = false))
            .put(row(hidden = false, hasRelease = true))
        val modules = VectorModuleCatalog.parse(data.toString())
        assertEquals(1, modules.size)
        assertEquals("com.example.duplicate", modules.single().packageName)
    }

    @Test fun repositorySortUsesInstantChronologyAndKeepsUnknownDatesLast() {
        val input = listOf(
            repo("com.example.offset", "Offset", 1, "2026-11-04T09:00:00+03:00"),
            repo("com.example.utc", "UTC", 1, "2026-11-04T07:00:00Z"),
            repo("com.example.malformed", "Malformed", 1, "not-a-date"),
            repo("com.example.unknown", "Unknown", 1, ""))
        fun packages(by: VectorModuleCatalog.Sort) = VectorModuleCatalog.sort(input, by)
            .map { it.packageName }
        assertEquals(listOf("com.example.utc", "com.example.offset",
            "com.example.malformed", "com.example.unknown"),
            packages(VectorModuleCatalog.Sort.UPDATED_NEWEST))
        assertEquals(listOf("com.example.offset", "com.example.utc",
            "com.example.malformed", "com.example.unknown"),
            packages(VectorModuleCatalog.Sort.UPDATED_OLDEST))
    }

    @Test fun repositorySortDoesNotMutateTheCacheList() {
        val original = listOf(repo("com.example.z", "Z", 0, ""),
            repo("com.example.a", "A", 9, "2026-01"))
        VectorModuleCatalog.sort(original, VectorModuleCatalog.Sort.MOST_STARS)
        assertEquals("Z", original.first().title)
        assertEquals("A", VectorModuleCatalog.sort(original,
            VectorModuleCatalog.Sort.UPDATED_NEWEST).first().title)
    }
}
