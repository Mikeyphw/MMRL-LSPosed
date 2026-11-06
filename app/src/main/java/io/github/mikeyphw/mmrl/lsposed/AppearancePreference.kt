package io.github.mikeyphw.mmrl.lsposed

/** Persisted names are stable; corrupt or obsolete values fall back safely to System. */
internal object AppearancePreference {
    private val supported = setOf("SYSTEM", "LIGHT", "DARK", "AMOLED")

    fun canonical(stored: String?): String = stored?.takeIf { it in supported } ?: "SYSTEM"
}
