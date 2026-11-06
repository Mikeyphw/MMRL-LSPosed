package io.github.mikeyphw.mmrl.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R5CEcosystemPresentationTest {
    @Test fun emptySearchMatchesEverything() {
        assertTrue(EcosystemPresentation.matches("", "LSPosed module"))
        assertTrue(EcosystemPresentation.matches("   ", ""))
    }

    @Test fun searchIsCaseInsensitiveAndAllTermsAreRequired() {
        assertTrue(EcosystemPresentation.matches("LSPOSED  MOD", "LSPosed", "Advanced mod"))
        assertTrue(EcosystemPresentation.matches("421428", "Run 42142815531"))
        assertFalse(EcosystemPresentation.matches("lsposed missing", "LSPosed module"))
    }

    @Test fun searchMatchesAcrossMetadataFields() {
        assertTrue(EcosystemPresentation.matches("release apk", "Release 1.0", "Manager.apk"))
        assertTrue(EcosystemPresentation.matches("main success", "main", "success"))
        assertFalse(EcosystemPresentation.matches("runner", "release", "APK"))
    }

    @Test fun paginationIsBoundedAndNeverDropsRows() {
        assertEquals(80, EcosystemPresentation.DISCOVER_PAGE)
        assertEquals(160, EcosystemPresentation.nextLimit(80, 500))
        assertEquals(81, EcosystemPresentation.nextLimit(80, 81))
        assertEquals(0, EcosystemPresentation.nextLimit(80, 0))
        assertEquals(2500, EcosystemPresentation.nextLimit(Int.MAX_VALUE, 2500))
    }
}
