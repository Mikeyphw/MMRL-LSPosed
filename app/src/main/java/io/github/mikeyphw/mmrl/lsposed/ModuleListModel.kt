package io.github.mikeyphw.mmrl.lsposed

import java.util.Locale

/** Read-only module search, sorting and grouping for the Compose overview. */
internal object ModuleListModel {
    enum class FormatFilter { ALL, MODERN, LEGACY }
    enum class StateFilter { ALL, ENABLED, DISABLED, NOT_INDEXED }
    enum class Sort { NAME_AZ, NAME_ZA, API_DESC, PACKAGE }
    enum class GroupKind { ENABLED, DISABLED, NOT_INDEXED, UNKNOWN }

    data class Group(val kind: GroupKind, val modules: List<ModuleDiscovery.Candidate>)

    fun groups(
        candidates: List<ModuleDiscovery.Candidate>,
        enabledByPackage: Map<String, Boolean>?,
        query: String = "",
        format: FormatFilter = FormatFilter.ALL,
        state: StateFilter = StateFilter.ALL,
        sort: Sort = Sort.NAME_AZ,
    ): List<Group> {
        val terms = query.trim().take(120).lowercase(Locale.ROOT)
            .split(Regex("\\s+")).filter(String::isNotBlank)
        val visible = candidates.filter { module ->
            (format == FormatFilter.ALL ||
                (format == FormatFilter.MODERN && module.format in setOf(
                    ModuleDiscovery.Format.MODERN, ModuleDiscovery.Format.BOTH)) ||
                (format == FormatFilter.LEGACY && module.format in setOf(
                    ModuleDiscovery.Format.LEGACY, ModuleDiscovery.Format.BOTH))) &&
                terms.all { token ->
                    token in "${module.name} ${module.packageName} ${module.description} ${module.versionName}"
                        .lowercase(Locale.ROOT)
                } && (state == StateFilter.ALL || (enabledByPackage != null && when (state) {
                    StateFilter.ALL -> true
                    StateFilter.ENABLED -> enabledByPackage[module.packageName] == true
                    StateFilter.DISABLED -> enabledByPackage[module.packageName] == false
                    StateFilter.NOT_INDEXED -> module.packageName !in enabledByPackage
                }))
        }
        val order = when (sort) {
            Sort.NAME_AZ -> compareBy<ModuleDiscovery.Candidate> { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.packageName }
            Sort.NAME_ZA -> compareByDescending<ModuleDiscovery.Candidate> { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.packageName }
            Sort.API_DESC -> compareByDescending<ModuleDiscovery.Candidate> { it.minApiVersion }
                .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.packageName }
            Sort.PACKAGE -> compareBy<ModuleDiscovery.Candidate> { it.packageName }
        }
        val sorted = visible.sortedWith(order)
        if (enabledByPackage == null) return if (sorted.isEmpty()) emptyList()
            else listOf(Group(GroupKind.UNKNOWN, sorted))
        return listOf(
            Group(GroupKind.ENABLED, sorted.filter { enabledByPackage[it.packageName] == true }),
            Group(GroupKind.DISABLED, sorted.filter { enabledByPackage[it.packageName] == false }),
            Group(GroupKind.NOT_INDEXED, sorted.filter { it.packageName !in enabledByPackage }),
        ).filter { it.modules.isNotEmpty() }
    }
}
