package io.github.mikeyphw.mmrl.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM coverage of conditional-cache admission; no network or Android root. */
class R2CatalogCacheTest {
    private val body = """[{"name":"com.example.module","releases":[{}]}]"""

    @Test fun strongAndWeakValidatorsAreBoundToExactCacheBytes() {
        val strong = "\"catalog-v12\""
        val weak = "W/\"catalog-v13\""
        for (etag in listOf(strong, weak)) {
            val receipt = VectorModuleCatalog.cacheReceipt(body, etag)
            assertTrue(receipt != null)
            assertEquals(etag, VectorModuleCatalog.validatedCacheEtag(receipt, body))
            assertNull(VectorModuleCatalog.validatedCacheEtag(receipt, body + " "))
            assertNull(VectorModuleCatalog.validatedCacheEtag(receipt, null))
        }
    }

    @Test fun responseValidatorsCannotInjectHeadersOrInventMetadata() {
        val invalid = listOf(
            "catalog-v12", "\"x\"\r\nX-Token: secret", "\"unterminated", "W/xxx",
            "\"x\"extra", "\"x\"\n", "\"a b\"", "\"x\"\"y\"",
            "\"" + "a".repeat(170) + "\""
        )
        for (etag in invalid) assertNull(VectorModuleCatalog.cacheReceipt(body, etag))
        assertNull(VectorModuleCatalog.cacheReceipt(body, null))
        assertNull(VectorModuleCatalog.validatedCacheEtag("{}", body))
        assertNull(VectorModuleCatalog.validatedCacheEtag("not-json", body))
    }

    @Test fun alteredSchemaDigestAndOversizedMetadataCannotEnableConditionalGet() {
        val receipt = VectorModuleCatalog.cacheReceipt(body, "\"v3\"")!!
        assertNull(VectorModuleCatalog.validatedCacheEtag(
            receipt.replace("vector-catalog-etag.v1", "other-schema"), body))
        assertNull(VectorModuleCatalog.validatedCacheEtag(
            receipt.replace(Regex("[a-f0-9]{64}"), "0".repeat(64)), body))
        assertNull(VectorModuleCatalog.validatedCacheEtag(receipt + " ".repeat(1025), body))
        assertNull(VectorModuleCatalog.validatedCacheEtag(receipt, "[]"))
        assertFalse(receipt.contains("github_token"))
    }
}
