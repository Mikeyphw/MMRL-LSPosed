package io.github.mikeyphw.mmrl.lsposed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Retains explicitly acquired read-only configuration across ordinary Activity recreation.
 * A fresh process starts disconnected and never performs an implicit root query.
 */
internal class VectorUiSession : ViewModel() {
    var selectedModule by mutableStateOf<ModuleDiscovery.Candidate?>(null)
    var vector by mutableStateOf<VectorState>(VectorState.Disconnected)
    var scopeOpen by mutableStateOf(false)
    var scope by mutableStateOf<ScopeState>(ScopeState.Idle)
    var appsIndex by mutableStateOf<AppsIndexState>(AppsIndexState.Idle)
    var scopeCounts by mutableStateOf<Map<String, Int>>(emptyMap())
    var writeBusy by mutableStateOf(false)
}
