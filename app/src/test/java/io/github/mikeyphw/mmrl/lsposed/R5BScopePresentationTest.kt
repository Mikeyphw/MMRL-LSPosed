package io.github.mikeyphw.mmrl.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation-only tests, no Vector, Android root, or network access. */
class R5BScopePresentationTest {
    private val rows = listOf(
        ScopeEditorPresentation.Row("app.one/0", "app.one", "Maps One", true, false),
        ScopeEditorPresentation.Row("app.two/0", "app.two", "Browser Two", false, true),
        ScopeEditorPresentation.Row("missing/10", "missing", "Unavailable", true, true),
    )

    @Test fun countsSavedAndSuggestedWithoutConflatingThem() {
        assertEquals(ScopeEditorPresentation.Counts(3, 2, 2),
            ScopeEditorPresentation.counts(rows))
        assertEquals(0, ScopeEditorPresentation.counts(emptyList()).available)
    }

    @Test fun queryMatchesAllWordsAcrossLabelAndPackage() {
        assertTrue(ScopeEditorPresentation.matches(rows[0], "maps APP.ONE"))
        assertTrue(ScopeEditorPresentation.matches(rows[1], "browser two"))
        assertTrue(ScopeEditorPresentation.matches(rows[1], "  "))
        assertFalse(ScopeEditorPresentation.matches(rows[1], "browser one"))
        assertFalse(ScopeEditorPresentation.matches(rows[2], "maps"))
    }

    @Test fun emptyStatesRespectSearchAndSelectedFilters() {
        assertTrue(ScopeEditorPresentation.emptyMessage("abc", false, false).contains("search"))
        assertTrue(ScopeEditorPresentation.emptyMessage("", true, false).contains("saved"))
        assertTrue(ScopeEditorPresentation.emptyMessage("", false, true).contains("suggestions"))
    }
}
