package io.github.mikeyphw.mmrl.lsposed

import org.junit.Assert.assertEquals
import org.junit.Test

class R5DAppearancePreferenceTest {
    @Test fun validSelectionsSurviveSerialization() {
        for (name in listOf("SYSTEM", "LIGHT", "DARK", "AMOLED"))
            assertEquals(name, AppearancePreference.canonical(name))
    }

    @Test fun corruptAndUnknownPreferencesFallBackToSystem() {
        for (name in listOf(null, "", "amoled", "DEBUG", "SYSTEM\n", "\u0000"))
            assertEquals("SYSTEM", AppearancePreference.canonical(name))
    }
}
