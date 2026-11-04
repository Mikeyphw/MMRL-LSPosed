package io.github.mikeyphw.mmrl.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure offline catalog tests; no root, APK installation, network or credentials. */
class S11CatalogPolicyTest {
    private val sample = """[
        {"name":"com.example.valid","description":"Example module","summary":"hooks test app",
         "stargazerCount":12,"latestRelease":"1.2.3","latestReleaseTime":"2026-10-20T00:00:00Z",
         "releases":[{"name":"1.2.3"}]},
        {"name":"com.example.hidden","hide":true,"releases":[{}]},
        {"name":"invalid!","releases":[{}]},
        {"name":"com.example.empty","releases":[]},
        {"name":"com.example.valid","releases":[{}]}
    ]"""

    @Test fun catalogFiltersHiddenEmptyDuplicateAndInvalidModules() {
        val list = VectorModuleCatalog.parse(sample)
        assertEquals(1, list.size)
        assertEquals("com.example.valid", list.single().packageName)
        assertEquals("Example module", list.single().title)
        assertEquals(12, list.single().stars)
        assertEquals("https://modules.lsposed.org/module/com.example.valid", list.single().pageUrl)
    }

    @Test fun catalogSearchIsLocalAndCaseInsensitive() {
        val list = VectorModuleCatalog.parse(sample)
        assertEquals(list, VectorModuleCatalog.filter(list, "HOOKS"))
        assertEquals(list, VectorModuleCatalog.filter(list, "example"))
        assertEquals(list, VectorModuleCatalog.filter(list, "hooks example"))
        assertEquals(list, VectorModuleCatalog.filter(list, "MODULE   hooks"))
        assertTrue(VectorModuleCatalog.filter(list, "not-present").isEmpty())
    }

    @Test fun emptyAndBrokenCatalogsFailClosed() {
        assertThrows(Exception::class.java) { VectorModuleCatalog.parse("[]") }
        assertThrows(Exception::class.java) { VectorModuleCatalog.parse("not json") }
        assertFalse(VectorModuleCatalog.parse(sample).single().pageUrl.contains("api.github.com"))
    }
}
