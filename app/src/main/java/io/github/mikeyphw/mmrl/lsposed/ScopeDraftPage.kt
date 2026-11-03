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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A fixed scope entry can be displayed even if its APK has been uninstalled. */
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
 * Review-only editor. All checkbox changes are local Compose state.
 * There is no service, mutation, Apply, or persistence call in this screen.
 */
@Composable
internal fun ScopeDraftPage(
    module: ModuleDiscovery.Candidate,
    state: ScopeState,
    padding: PaddingValues,
) {
    val context = LocalContext.current
    var installed by remember(module.packageName) { mutableStateOf<List<AppTarget>?>(null) }
    var scanError by remember(module.packageName) { mutableStateOf(false) }
    LaunchedEffect(module.packageName) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                val apps = context.packageManager.getInstalledApplications(0)
                apps.map { app ->
                    val label = runCatching {
                        context.packageManager.getApplicationLabel(app).toString().trim()
                    }.getOrDefault(app.packageName)
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
            val baseline = remember(module.packageName, state.entries) {
                state.entries.map { it.key }.toSet()
            }
            val recommended = remember(module.packageName, module.declaredScopes) {
                module.declaredScopes.toSet()
            }
            // Saveable local draft; no root modification is possible.
            var draft by rememberSaveable(module.packageName, baseline) {
                mutableStateOf(ArrayList(baseline.sorted()))
            }
            var query by rememberSaveable(module.packageName) { mutableStateOf("") }
            var filter by rememberSaveable(module.packageName) { mutableStateOf(TargetTab.ALL) }
            var showReview by rememberSaveable(module.packageName) { mutableStateOf(false) }
            val draftSet = draft.toSet()
            val added = (draftSet - baseline).sorted()
            val removed = (baseline - draftSet).sorted()
            val indexed = installed.orEmpty().associateBy { it.packageName }
            val configured = state.entries.mapNotNull { scope ->
                if (scope.userId == 0 && scope.packageName in indexed) null
                else AppTarget(scope.packageName,
                    if (scope.userId == 0) scope.packageName else "${scope.packageName} (user ${scope.userId})",
                    system = true, userId = scope.userId, savedOnly = true)
            }
            val suggestions = recommended.filterNot { it in indexed || "${it}/0" in baseline }
                .map { AppTarget(it, it, system = true, savedOnly = true) }
            val targets = (installed.orEmpty() + configured + suggestions)
                .distinctBy { "${it.packageName}/${it.userId}" }
            val visible = targets.filter { target ->
                val checked = "${target.packageName}/${target.userId}" in draftSet
                val matches = when (filter) {
                    TargetTab.ALL -> true
                    TargetTab.SELECTED -> checked
                    TargetTab.SUGGESTED -> target.packageName in recommended
                }
                matches && (target.label.contains(query, ignoreCase = true) ||
                    target.packageName.contains(query, ignoreCase = true))
            }
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saved scope · ${state.entries.size} targets",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Draft only · Live hooks unverified · Nothing will be saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (module.staticScope) Text("Module declares a static scope. Other targets are not selectable.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary)
                    if (added.isNotEmpty() || removed.isNotEmpty()) Text(
                        "${added.size} proposed additions · ${removed.size} proposed removals — not saved",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { showReview = true }) { Text("Review draft") }
                        OutlinedButton(onClick = { draft = ArrayList(baseline.sorted()) },
                            enabled = added.isNotEmpty() || removed.isNotEmpty()) {
                            Text("Discard changes")
                        }
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it.take(120) },
                        label = { Text("Search apps") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = filter == TargetTab.ALL, onClick = { filter = TargetTab.ALL },
                            label = { Text("All") })
                        FilterChip(selected = filter == TargetTab.SELECTED,
                            onClick = { filter = TargetTab.SELECTED }, label = { Text("Selected") })
                        FilterChip(selected = filter == TargetTab.SUGGESTED,
                            onClick = { filter = TargetTab.SUGGESTED }, label = { Text("Suggested") })
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scanError) item { Text("App inventory unavailable; showing saved targets only") }
                    if (installed == null && !scanError) item { Text("Loading app icons and targets…") }
                    if (filter == TargetTab.SUGGESTED && recommended.isEmpty()) item {
                        Text("No declared scope suggestions in the installed module APK.")
                    } else if (visible.isEmpty() && installed != null) item {
                        Text("No apps match this view.")
                    }
                    items(visible, key = { "${it.packageName}/${it.userId}" }) { target ->
                        val key = "${target.packageName}/${target.userId}"
                        val selected = key in draftSet
                        val manager = target.packageName in managerPackages ||
                            target.packageName.startsWith("com.dergoogler.mmrl") ||
                            target.packageName.startsWith("io.github.mikeyphw.mmrl")
                        val permittedByStaticScope = !module.staticScope || target.packageName in recommended
                        val editable = target.userId == 0 && !target.system && !target.savedOnly &&
                            !manager && target.packageName != context.packageName &&
                            target.packageName != module.packageName && permittedByStaticScope
                        val reason = when {
                            target.userId != 0 -> "Other Android user · read-only"
                            target.packageName in setOf("android", "system") -> "Framework target · read-only"
                            target.system -> "System app · read-only"
                            manager || target.packageName == context.packageName -> "Manager app · read-only"
                            target.packageName == module.packageName -> "Module APK · read-only"
                            target.savedOnly -> "Uninstalled/unknown APK · read-only"
                            !permittedByStaticScope -> "Outside static scope · read-only"
                            else -> "Local preview only"
                        }
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.fillMaxWidth().clickable(enabled = editable) {
                                draft = ArrayList((if (selected) draftSet - key else draftSet + key).sorted())
                            }, shape = RoundedCornerShape(16.dp)) {
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
                                Checkbox(checked = selected, enabled = editable, onCheckedChange = { checked ->
                                    draft = ArrayList((if (checked) draftSet + key else draftSet - key).sorted())
                                })
                            }
                        }
                    }
                }
            }
            if (showReview) AlertDialog(
                onDismissRequest = { showReview = false },
                title = { Text("Review local scope draft") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saved: ${baseline.size} · Draft: ${draftSet.size}")
                        Text("Add (${added.size}): " + added.take(10).joinToString().ifBlank { "None" })
                        Text("Remove (${removed.size}): " + removed.take(10).joinToString().ifBlank { "None" })
                        if (added.size > 10 || removed.size > 10)
                            Text("Only the first 10 changed targets per group are shown.")
                        Text("No Apply action is available. The Vector database is unchanged.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { showReview = false }) { Text("Close") } },
                dismissButton = { TextButton(onClick = {
                    draft = ArrayList(baseline.sorted()); showReview = false
                }) { Text("Discard draft") } },
            )
        }
    }
}

@Composable
private fun ScopeInfo(padding: PaddingValues, message: String) {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Text("Scope is read-only. Nothing is written to the Vector database.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
