package io.github.mikeyphw.mmrl.lsposed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No action, retry, scope mutation or installer authority is exposed by this screen. */
@Composable
internal fun ActivityPage(padding: PaddingValues) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<ActivityEntry>>(emptyList()) }
    var error by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(refresh, page) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching { ActivityHistoryStore.refresh(context.applicationContext, page = page, pageSize = 50) }
        }
        result.onSuccess { newItems ->
            items = if (page == 0) newItems else (items + newItems).distinctBy { it.id }
            hasMore = newItems.size == 50
            error = false
        }.onFailure {
            if (page == 0) items = emptyList()
            hasMore = false
            error = true
        }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Activity", style = MaterialTheme.typography.headlineSmall)
            Text("Read-only local history. A verified hash is not APK signer trust. " +
                "Pending Vector writes still require explicit reconciliation.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { page = 0; refresh++ }, enabled = !loading,
                modifier = Modifier.testTag("activity_refresh")) { Text("Refresh activity") }
        }
        if (error) item { Text("Activity could not be verified. No record was assumed successful.",
            color = MaterialTheme.colorScheme.error) }
        if (loading) item { Text("Reading local receipts…") }
        if (!loading && items.isEmpty() && !error) item { Text("No recorded activity") }
        items(items, key = { it.id }) { record ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${record.kind} · ${record.status}", style = MaterialTheme.typography.titleSmall)
                    Text(record.source, style = MaterialTheme.typography.bodySmall)
                    record.progressPercent?.let { Text("$it%", style = MaterialTheme.typography.labelSmall) }
                    record.failureCode?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (!loading && hasMore && !error) item {
            OutlinedButton(onClick = { page += 1 },
                modifier = Modifier.testTag("activity_load_more")) {
                Text("Load more history")
            }
        }
    }
}
