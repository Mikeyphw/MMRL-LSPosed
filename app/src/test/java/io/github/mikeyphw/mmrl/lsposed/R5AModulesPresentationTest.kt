package io.github.mikeyphw.mmrl.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline saved-configuration and filtering UX. No root, network, or write calls. */
class R5AModulesPresentationTest {
    private fun module(pkg: String, name: String) = ModuleDiscovery.Candidate(
        packageName = pkg, name = name, format = ModuleDiscovery.Format.MODERN)
    private val installed = listOf(module("a.one", "One"), module("b.two", "Two"),
        module("c.three", "Three"))

    @Test fun summaryCountsOnlyInstalledAndNeverCountsOtherVectorEntries() {
        val s = ModuleListModel.summary(installed, mapOf("a.one" to true,
            "b.two" to false, "other.package" to true))
        assertEquals(3, s.installed)
        assertEquals(1, s.enabled)
        assertEquals(1, s.disabled)
        assertEquals(1, s.notIndexed)
        assertTrue(s.savedAvailable)
    }

    @Test fun unknownVectorStateIsNotMisrepresentedAsDisabled() {
        val s = ModuleListModel.summary(installed, null)
        assertFalse(s.savedAvailable)
        assertEquals(0, s.enabled)
        assertEquals(0, s.disabled)
        assertEquals(0, s.notIndexed)
        assertEquals(3, s.installed)
        assertTrue(ModuleListModel.groups(installed, null,
            state = ModuleListModel.StateFilter.ENABLED).isEmpty())
    }

    @Test fun quickSavedStatusFilteringAndResetAreConsistent() {
        val flags = mapOf("a.one" to true, "b.two" to false)
        assertEquals(listOf("a.one"), ModuleListModel.groups(installed, flags,
            state = ModuleListModel.StateFilter.ENABLED).flatMap { it.modules }
            .map { it.packageName })
        assertEquals(listOf("b.two"), ModuleListModel.groups(installed, flags,
            state = ModuleListModel.StateFilter.DISABLED).flatMap { it.modules }
            .map { it.packageName })
        assertEquals(listOf("c.three"), ModuleListModel.groups(installed, flags,
            state = ModuleListModel.StateFilter.NOT_INDEXED).flatMap { it.modules }
            .map { it.packageName })
        assertTrue(ModuleListModel.hasActiveFilters("Two", ModuleListModel.FormatFilter.ALL,
            ModuleListModel.StateFilter.ALL, ModuleListModel.Sort.NAME_AZ))
        assertTrue(ModuleListModel.hasActiveFilters("", ModuleListModel.FormatFilter.ALL,
            ModuleListModel.StateFilter.ENABLED, ModuleListModel.Sort.NAME_AZ))
        assertFalse(ModuleListModel.hasActiveFilters("", ModuleListModel.FormatFilter.ALL,
            ModuleListModel.StateFilter.ALL, ModuleListModel.Sort.NAME_AZ))
    }
}
