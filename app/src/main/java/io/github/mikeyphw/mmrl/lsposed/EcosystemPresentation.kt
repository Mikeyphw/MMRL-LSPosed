package io.github.mikeyphw.mmrl.lsposed

import java.util.Locale

/** Pure, local-only UI helpers. Never authorizes a download, install, or Vector write. */
internal object EcosystemPresentation {
    const val DISCOVER_PAGE = 80

    /** AND across whitespace terms, OR across the visible metadata fields. */
    fun matches(query: String, vararg fields: String): Boolean {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (terms.isEmpty()) return true
        val searchable = fields.joinToString(" ").lowercase(Locale.ROOT)
        return terms.all { it in searchable }
    }

    /** Display all entries eventually, without unbounded initial Compose rows. */
    fun nextLimit(current: Int, total: Int): Int {
        if (total <= 0) return 0
        return ((current.coerceAtLeast(0).toLong() + DISCOVER_PAGE).coerceAtMost(total.toLong())).toInt()
    }
}
