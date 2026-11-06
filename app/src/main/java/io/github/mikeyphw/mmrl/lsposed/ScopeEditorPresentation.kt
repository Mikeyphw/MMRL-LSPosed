package io.github.mikeyphw.mmrl.lsposed

import java.util.Locale

/** Presentation-only search and summary for saved Vector targets. No permission decisions or writes. */
internal object ScopeEditorPresentation {
    data class Row(
        val key: String,
        val packageName: String,
        val label: String,
        val selected: Boolean,
        val suggested: Boolean,
    )
    data class Counts(val available: Int, val selected: Int, val suggested: Int)

    fun counts(rows: List<Row>): Counts = Counts(
        available = rows.size,
        selected = rows.count { it.selected },
        suggested = rows.count { it.suggested },
    )

    /** Each nonblank search word must match either the label or package name. */
    fun matches(row: Row, query: String): Boolean {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val searchable = (row.label + " " + row.packageName).lowercase(Locale.ROOT)
        return words.all { searchable.contains(it) }
    }

    fun emptyMessage(query: String, selectedTab: Boolean, suggestedTab: Boolean): String = when {
        query.isNotBlank() -> "No apps match your search. Clear the search or switch filters."
        selectedTab -> "No saved targets yet. Open All to choose an app."
        suggestedTab -> "No module-declared suggestions available. Open All to browse installed apps."
        else -> "No targets available. Check app visibility or refresh the saved scope."
    }
}
