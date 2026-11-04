package io.github.mikeyphw.mmrl.lsposed

import android.content.pm.ApplicationInfo
import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A configured target may remain visible after its APK is uninstalled. */
internal data class AppTarget(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val userId: Int = 0,
    val savedOnly: Boolean = false,
)

private enum class TargetTab { ALL, SELECTED, SUGGESTED }
private val managerPackages = setOf(
    "org.lsposed.manager", "org.matrix.vector.manager", "io.github.libxposed.manager",
)

/**
 * Every user tap is one explicit, journaled Vector mutation, never a local draft.
 * S08's precondition check, CLI acknowledgement and independent saved-state readback
 * remain authoritative. A failure may leave a pending reconciliation lock: no retries.
 * Enabling a disabled module is an independent user action: never a hidden side effect.
 */
@Composable
internal fun ScopeDraftPage(
    module: ModuleDiscovery.Candidate,
    state: ScopeState,
    padding: PaddingValues,
    enabled: Boolean?,
    onCommitted: (VectorMutation.Outcome) -> Unit,
    onWriteBusy: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    var applying by remember(module.packageName) { mutableStateOf(false) }
    var writeError by remember(module.packageName) { mutableStateOf("") }
    var verified by remember(module.packageName) { mutableStateOf("") }
    var installed by remember(module.packageName) { mutableStateOf<List<AppTarget>?>(null) }
    var scanError by remember(module.packageName) { mutableStateOf(false) }

    LaunchedEffect(module.packageName) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                val apps = context.packageManager.getInstalledApplications(0)
                apps.map { app ->
                    val label = runCatching { context.packageManager.getApplicationLabel(app).toString().trim() }
                        .getOrDefault(app.packageName)
                    AppTarget(app.packageName, label.ifBlank { app.packageName },
                        (app.flags and (ApplicationInfo.FLAG_SYSTEM or
                            ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
                }.sortedBy { it.label.lowercase() }
            }
        }
        installed = result.getOrNull()
        scanError = result.isFailure
    }

    when (state) {
        ScopeState.Idle, ScopeState.Loading -> ScopeInfo(padding, "Loading saved Vector scopes…")
        is ScopeState.Unavailable -> ScopeInfo(padding, "Scope unavailable: ${state.reason}")
        is ScopeState.Ready -> {
            val baseline = remember(module.packageName, state.entries) { state.entries.map { it.key }.toSet() }
            val recommended = remember(module.packageName, module.declaredScopes) { module.declaredScopes.toSet() }
            var query by rememberSaveable(module.packageName) { mutableStateOf("") }
            var filter by rememberSaveable(module.packageName) { mutableStateOf(TargetTab.ALL) }

            fun change(action: VectorMutation.Action, target: String = "") {
                if (applying || enabled == null) return
                applying = true
                writeError = ""
                verified = ""
                onWriteBusy(true)
                coroutine.launch {
                    val request = VectorMutation.Request(
                        module.packageName, action, enabled, state.entries, target)
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching { VectorMutation.change(context.applicationContext, request) }
                    }
                    outcome.onSuccess {
                        onCommitted(it)
                        verified = "Saved change verified · ${it.receiptId.take(8)}"
                    }.onFailure {
                        writeError = (it.message ?: "Write outcome uncertain") +
                            ". If a write was attempted, reconcile on Home before retrying."
                    }
                    applying = false
                    onWriteBusy(false)
                }
            }

            val indexed = installed.orEmpty().associateBy { it.packageName }
            val configured = state.entries.mapNotNull { scope ->
                if (scope.userId == 0 && scope.packageName in indexed) null
                else AppTarget(scope.packageName,
                    if (scope.userId == 0) scope.packageName else "${scope.packageName} (user ${scope.userId})",
                    system = true, userId = scope.userId, savedOnly = true)
            }
            val suggestions = recommended.filterNot { it in indexed || "$it/0" in baseline }
                .map { AppTarget(it, it, system = true, savedOnly = true) }
            val targets = (installed.orEmpty() + configured + suggestions)
                .distinctBy { "${it.packageName}/${it.userId}" }
            val visible = targets.filter { target ->
                val selected = "${target.packageName}/${target.userId}" in baseline
                val matches = when (filter) {
                    TargetTab.ALL -> true
                    TargetTab.SELECTED -> selected
                    TargetTab.SUGGESTED -> target.packageName in recommended
                }
                matches && (target.label.contains(query, ignoreCase = true) ||
                    target.packageName.contains(query, ignoreCase = true))
            }
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Saved scope · ${state.entries.size} targets",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Tap an app to save its scope immediately. Vector's saved configuration is " +
                        "verified before and after each change; live hooks are not verified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (module.staticScope) Text("Only module-declared static targets are editable.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary)
                    if (enabled == false) {
                        Text("This module is disabled. Enable it first to add scope targets. " +
                            "Vector can otherwise enable it implicitly.",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { change(VectorMutation.Action.ENABLE) },
                            enabled = !applying, modifier = Modifier.testTag("scope_enable_module")) {
                            Text(if (applying) "Applying…" else "Enable module")
                        }
                    } else if (enabled == null) {
                        Text("Connect to Vector to change saved scopes.",
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (applying) Text("Verifying change with Vector…",
                        style = MaterialTheme.typography.labelMedium)
                    if (verified.isNotBlank()) Text(verified,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                    if (writeError.isNotBlank()) Text(writeError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(value = query, onValueChange = { query = it.take(120) },
                        label = { Text("Search apps") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("scope_search"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = filter == TargetTab.ALL,
                            onClick = { filter = TargetTab.ALL }, label = { Text("All") })
                        FilterChip(selected = filter == TargetTab.SELECTED,
                            onClick = { filter = TargetTab.SELECTED }, label = { Text("Selected") })
                        FilterChip(selected = filter == TargetTab.SUGGESTED,
                            onClick = { filter = TargetTab.SUGGESTED }, label = { Text("Suggested") })
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scanError) item { Text("App inventory unavailable; showing saved targets only") }
                    if (installed == null && !scanError) item { Text("Loading apps…") }
                    if (filter == TargetTab.SUGGESTED && recommended.isEmpty()) item {
                        Text("No declared scope suggestions in this module APK.")
                    } else if (visible.isEmpty() && installed != null) item {
                        Text("No apps match this view.")
                    }
                    items(visible, key = { "${it.packageName}/${it.userId}" }) { target ->
                        val key = "${target.packageName}/${target.userId}"
                        val selected = key in baseline
                        val manager = target.packageName in managerPackages ||
                            target.packageName.startsWith("com.dergoogler.mmrl") ||
                            target.packageName.startsWith("io.github.mikeyphw.mmrl")
                        val permittedByStatic = !module.staticScope || target.packageName in recommended
                        val supported = target.userId == 0 && !target.system && !target.savedOnly &&
                            !manager && target.packageName != context.packageName &&
                            target.packageName != module.packageName && permittedByStatic
                        val editable = !applying && enabled != null && supported && (selected || enabled == true)
                        val reason = when {
                            target.userId != 0 -> "Other Android user · read-only"
                            target.system -> "System app · read-only"
                            manager || target.packageName == context.packageName -> "Manager app · read-only"
                            target.packageName == module.packageName -> "Module APK · read-only"
                            target.savedOnly -> "Uninstalled or unavailable · read-only"
                            !permittedByStatic -> "Outside declared static scope"
                            enabled == false && !selected -> "Enable module to add this target"
                            else -> if (selected) "Saved · tap to remove" else "Tap to add to saved scope"
                        }
                        val toggle = { change(if (selected) VectorMutation.Action.REMOVE_SCOPE
                            else VectorMutation.Action.ADD_SCOPE, target.packageName) }
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.fillMaxWidth().clickable(enabled = editable) { toggle() },
                            shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                AndroidView(factory = { ctx -> ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                } }, update = { view ->
                                    val icon = runCatching {
                                        context.packageManager.getApplicationIcon(target.packageName)
                                    }.getOrNull()
                                    if (icon != null) view.setImageDrawable(icon)
                                    else view.setImageResource(R.drawable.ic_launcher)
                                }, modifier = Modifier.size(40.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(target.label, fontWeight = FontWeight.Medium, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(target.packageName, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(reason, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Checkbox(checked = selected, enabled = editable,
                                    onCheckedChange = { toggle() }, modifier = Modifier.testTag("scope_$key"))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeInfo(padding: PaddingValues, message: String) {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Text("Saved scopes are unavailable until Vector is connected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
