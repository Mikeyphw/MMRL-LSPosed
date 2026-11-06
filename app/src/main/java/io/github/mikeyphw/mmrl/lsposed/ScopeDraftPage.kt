package io.github.mikeyphw.mmrl.lsposed

import android.content.pm.ApplicationInfo
import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.TextButton
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
import java.util.Locale
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
    onRetryRead: () -> Unit,
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
                }.sortedBy { it.label.lowercase(Locale.ROOT) }
            }
        }
        installed = result.getOrNull()
        scanError = result.isFailure
    }

    when (state) {
        ScopeState.Idle, ScopeState.Loading -> ScopeInfo(padding, "Loading saved Vector scopes…")
        is ScopeState.Unavailable -> ScopeInfo(padding, "Scope unavailable: ${state.reason}", onRetryRead)
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
            // A UI-only projection. The Vector mutation preconditions below remain authoritative.
            val display = targets.map { target ->
                val key = "${target.packageName}/${target.userId}"
                ScopeEditorPresentation.Row(key, target.packageName, target.label,
                    selected = key in baseline, suggested = target.packageName in recommended)
            }
            val counts = ScopeEditorPresentation.counts(display)
            val shownKeys = display.filter { row ->
                val inTab = when (filter) {
                    TargetTab.ALL -> true
                    TargetTab.SELECTED -> row.selected
                    TargetTab.SUGGESTED -> row.suggested
                }
                inTab && ScopeEditorPresentation.matches(row, query)
            }.mapTo(HashSet()) { it.key }
            val visible = targets.filter { "${it.packageName}/${it.userId}" in shownKeys }
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("App scopes", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text("${counts.selected} saved · ${counts.available} visible targets",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("scope_summary"))
                    Text("Tap a supported app to save one change. Saved scopes do not prove active hooks.",
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
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = query, onValueChange = { query = it.take(120) },
                            label = { Text("Search apps or packages") }, singleLine = true,
                            modifier = Modifier.weight(1f).testTag("scope_search"))
                        if (query.isNotBlank()) TextButton(onClick = { query = "" },
                            enabled = !applying, modifier = Modifier.testTag("scope_clear_search")) {
                            Text("Clear")
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = filter == TargetTab.ALL,
                            onClick = { filter = TargetTab.ALL },
                            modifier = Modifier.testTag("scope_filter_all"),
                            label = { Text("All (${counts.available})") })
                        FilterChip(selected = filter == TargetTab.SELECTED,
                            onClick = { filter = TargetTab.SELECTED },
                            modifier = Modifier.testTag("scope_filter_selected"),
                            label = { Text("Selected (${counts.selected})") })
                        FilterChip(selected = filter == TargetTab.SUGGESTED,
                            onClick = { filter = TargetTab.SUGGESTED },
                            modifier = Modifier.testTag("scope_filter_suggested"),
                            label = { Text("Suggested (${counts.suggested})") })
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scanError) item { Text("App inventory unavailable; showing saved targets only") }
                    if (installed == null && !scanError) item { Text("Loading apps…") }
                    if (visible.isEmpty() && (installed != null || scanError)) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(ScopeEditorPresentation.emptyMessage(query,
                                filter == TargetTab.SELECTED, filter == TargetTab.SUGGESTED),
                                style = MaterialTheme.typography.bodyMedium)
                            if (query.isNotBlank() || filter != TargetTab.ALL) {
                                TextButton(onClick = { query = ""; filter = TargetTab.ALL },
                                    modifier = Modifier.testTag("scope_reset_filters")) {
                                    Text("Show all targets")
                                }
                            }
                        }
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
                            target.savedOnly -> "Unavailable app · read-only saved target"
                            target.system -> "System app · read-only"
                            manager || target.packageName == context.packageName -> "Manager app · read-only"
                            target.packageName == module.packageName -> "Module APK · read-only"
                            !permittedByStatic -> "Outside declared static scope"
                            enabled == false && !selected -> "Enable module to add this target"
                            else -> if (selected) "Saved in Vector · tap to remove" else "Tap to add to Vector"
                        }
                        val toggle = { change(if (selected) VectorMutation.Action.REMOVE_SCOPE
                            else VectorMutation.Action.ADD_SCOPE, target.packageName) }
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.fillMaxWidth()
                                .testTag("scope_row_$key")
                                .clickable(enabled = editable) { toggle() },
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
private fun ScopeInfo(padding: PaddingValues, message: String,
    onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Text("Saved scopes are unavailable until Vector is connected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) Button(onClick = onRetry,
            modifier = Modifier.testTag("scope_retry_read")) { Text("Retry scope read") }
    }
}
