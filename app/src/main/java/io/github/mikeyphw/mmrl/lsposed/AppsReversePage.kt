package io.github.mikeyphw.mmrl.lsposed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** No root operations on composition. The caller performs explicit scope indexing. */
@Composable
internal fun AppsReversePage(
    state: AppsIndexState,
    connected: Boolean,
    padding: PaddingValues,
    onBuild: () -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var scopedOnly by rememberSaveable { mutableStateOf(true) }
    val snapshot = (state as? AppsIndexState.Ready)?.snapshot
    val visible = remember(snapshot, query, scopedOnly) {
        snapshot?.targets.orEmpty().filter { item ->
            (!scopedOnly || item.modules.isNotEmpty()) &&
                (query.isBlank() || item.packageName.contains(query.trim(), ignoreCase = true) ||
                    item.label.contains(query.trim(), ignoreCase = true) ||
                    item.modules.any { it.contains(query.trim(), ignoreCase = true) })
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("App targets", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            Text("Reverse lookup of saved Vector scopes. These are configured targets, " +
                "not proof that hooks are running.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Button(onClick = onBuild, enabled = connected && state !is AppsIndexState.Loading,
                modifier = Modifier.testTag("apps_build_index")) {
                Text(if (snapshot == null) "Build saved-scope index" else "Rebuild index")
            }
        }
        item {
            when (state) {
                AppsIndexState.Idle -> Text(if (connected)
                    "Choose Build index to read each configured module's saved scopes."
                    else "Connect to Vector on Home first. No root read happens here automatically.")
                AppsIndexState.Loading -> Text("Reading saved scopes. This may take a while…")
                is AppsIndexState.Unavailable -> Text(state.reason, color = MaterialTheme.colorScheme.error)
                is AppsIndexState.Ready -> Text(
                    "${state.snapshot.scopedApps} configured targets · " +
                        "${state.snapshot.inspectedModules} modules inspected",
                    style = MaterialTheme.typography.titleMedium)
            }
        }
        if (snapshot != null) {
            item {
                OutlinedTextField(value = query, onValueChange = { query = it.take(100) },
                    singleLine = true, label = { Text("Search apps or module packages") },
                    modifier = Modifier.fillMaxWidth().testTag("apps_search"))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = scopedOnly, onClick = { scopedOnly = true },
                        label = { Text("Configured targets") })
                    FilterChip(selected = !scopedOnly, onClick = { scopedOnly = false },
                        label = { Text("All installed apps") })
                }
            }
            if (visible.isEmpty()) item { Text("No matching app targets") }
            items(visible, key = { it.packageName }) { target ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (target.installed) AndroidView(factory = { ctx -> ImageView(ctx) },
                            update = { icon -> runCatching {
                                icon.setImageDrawable(context.packageManager
                                    .getApplicationIcon(target.packageName))
                            } }, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(target.label, style = MaterialTheme.typography.titleMedium)
                        Text(target.packageName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!target.installed) Text("Target not installed on this device",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (target.systemTarget) Text("System target · read-only display",
                            color = MaterialTheme.colorScheme.error)
                        Text(if (target.modules.isEmpty()) "No saved module scopes found"
                            else "Saved scope membership · ${target.modules.size} module(s)",
                            style = MaterialTheme.typography.bodyMedium)
                        target.modules.forEach { module ->
                            TextButton(onClick = { onOpenModule(module) }) { Text(module) }
                        }
                        }
                    }
                }
            }
        }
    }
}
