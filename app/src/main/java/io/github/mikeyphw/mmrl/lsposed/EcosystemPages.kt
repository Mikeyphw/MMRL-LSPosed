package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private fun openGitHub(context: Context, link: String) {
    val uri = Uri.parse(link)
    require(uri.scheme == "https" && uri.host == "github.com" &&
        uri.port == -1 && uri.userInfo == null)
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
internal fun DiscoverPage(padding: PaddingValues) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    var term by rememberSaveable { mutableStateOf("") }
    var ordering by rememberSaveable { mutableStateOf(VectorModuleCatalog.Sort.UPDATED_NEWEST) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<VectorModuleCatalog.Result?>(null) }
    var message by remember { mutableStateOf("") }

    fun load() {
        if (busy) return
        busy = true
        message = ""
        coroutine.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { VectorModuleCatalog.load(context.applicationContext) }
            }
            result.onSuccess { catalog = it }.onFailure {
                message = "LSPosed catalog unavailable. Check your connection or Private DNS and retry."
            }
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        // Render previously verified local metadata without waiting for DNS timeout.
        val savedCatalog = withContext(Dispatchers.IO) {
            VectorModuleCatalog.cached(context.applicationContext)
        }
        if (savedCatalog != null) catalog = savedCatalog
        load()
    }
    val results = remember(catalog, term, ordering) {
        VectorModuleCatalog.sort(
            VectorModuleCatalog.filter(catalog?.modules.orEmpty(), term), ordering)
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Discover modules", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            Text("Official LSPosed module repository used by Vector. " +
                "Catalog entries are community-published; review publisher and compatibility before installing.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(value = term, onValueChange = { term = it.take(80) },
                label = { Text("Search LSPosed modules") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("discover_query"))
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { load() }, enabled = !busy,
                    modifier = Modifier.testTag("discover_search")) {
                    Text(if (busy) "Loading…" else "Refresh catalog")
                }
                Box {
                    OutlinedButton(onClick = { sortMenuOpen = true },
                        modifier = Modifier.testTag("discover_sort")) {
                        Text("Sort: " + when (ordering) {
                            VectorModuleCatalog.Sort.UPDATED_NEWEST -> "Newest"
                            VectorModuleCatalog.Sort.UPDATED_OLDEST -> "Oldest"
                            VectorModuleCatalog.Sort.MOST_STARS -> "Most stars"
                            VectorModuleCatalog.Sort.FEWEST_STARS -> "Fewest stars"
                            VectorModuleCatalog.Sort.NAME_AZ -> "Name A–Z"
                            VectorModuleCatalog.Sort.NAME_ZA -> "Name Z–A"
                        })
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        VectorModuleCatalog.Sort.entries.forEach { sort ->
                            DropdownMenuItem(text = { Text(when (sort) {
                                VectorModuleCatalog.Sort.UPDATED_NEWEST -> "Recently updated"
                                VectorModuleCatalog.Sort.UPDATED_OLDEST -> "Oldest updates"
                                VectorModuleCatalog.Sort.MOST_STARS -> "Most stars"
                                VectorModuleCatalog.Sort.FEWEST_STARS -> "Fewest stars"
                                VectorModuleCatalog.Sort.NAME_AZ -> "Name A–Z"
                                VectorModuleCatalog.Sort.NAME_ZA -> "Name Z–A"
                            }) }, onClick = { ordering = sort; sortMenuOpen = false },
                                modifier = Modifier.testTag("discover_sort_${sort.name.lowercase()}"))
                        }
                    }
                }
            }
            if (catalog != null) Text("${results.size} of ${catalog!!.modules.size} modules · sorted locally",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
        val active = catalog
        if (active != null && active.cached) item {
            Text(active.warning, color = MaterialTheme.colorScheme.tertiary)
        }
        if (message.isNotBlank()) item { Text(message, color = MaterialTheme.colorScheme.error) }
        if (busy && active == null) item { Text("Loading LSPosed catalog…") }
        if (!busy && active != null && results.isEmpty()) item { Text("No modules match your search") }
        items(results.take(250), key = { it.packageName }) { repo ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(repo.title, style = MaterialTheme.typography.titleMedium)
                    Text(repo.packageName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (repo.summary.isNotBlank()) Text(repo.summary,
                        style = MaterialTheme.typography.bodySmall)
                    Text("${repo.stars} stars" + if (repo.version.isNotBlank()) " · ${repo.version}" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = {
                        val uri = Uri.parse(repo.pageUrl)
                        if (uri.scheme == "https" && uri.host == "modules.lsposed.org" &&
                            uri.userInfo == null && uri.port == -1) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure { message = "No browser available to open module details" }
                        }
                    }) { Text("View module details") }
                }
            }
        }
        if (results.size > 250) item {
            Text("Showing the first 250 matches. Refine your search to see more.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DownloadsPage(padding: PaddingValues) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var releases by androidx.compose.runtime.remember { mutableStateOf<List<GitHubEcosystem.Release>?>(null) }
    var runs by androidx.compose.runtime.remember { mutableStateOf<List<GitHubEcosystem.Run>?>(null) }
    var selectedRun by remember { mutableStateOf<GitHubEcosystem.Run?>(null) }
    var artifacts by remember { mutableStateOf<List<GitHubEcosystem.Artifact>?>(null) }
    var message by rememberSaveable { mutableStateOf("") }
    var saved by androidx.compose.runtime.remember { mutableStateOf<File?>(null) }
    var history by remember { mutableStateOf<List<VerifiedDownload.Saved>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var cancelRequested by remember { mutableStateOf(false) }
    val cancelSignal = remember { AtomicBoolean(false) }
    var progress by remember { mutableStateOf(0) }
    // Leaving this screen must cancel an in-flight transfer; never finish it silently.
    DisposableEffect(Unit) { onDispose { cancelSignal.set(true) } }

    LazyColumn(Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Verified downloads", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            Text("First-party MMRL-LSPosed releases and GitHub Actions artifacts. " +
                "SHA-256 proves file integrity, not APK signer trust or release qualification.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = {
                    if (tab != 0) { tab = 0; selectedRun = null; artifacts = null; message = "" }
                }, label = { Text("Releases") })
                FilterChip(selected = tab == 1, onClick = {
                    if (tab != 1) { tab = 1; selectedRun = null; artifacts = null; message = "" }
                }, label = { Text("CI artifacts") })
            }
        }
        item {
            Button(onClick = {
                val requestedTab = tab // Immutable request ownership across asynchronous tab changes.
                busy = true; message = ""; saved = null
                if (requestedTab == 1) { selectedRun = null; artifacts = null }
                coroutine.launch {
                    if (requestedTab == 0) {
                        val result = withContext(Dispatchers.IO) { runCatching { GitHubEcosystem.releases(context.applicationContext) } }
                        result.onSuccess { releases = it }.onFailure {
                            if (tab == requestedTab) message = it.message?.take(180) ?: "GitHub unavailable"
                        }
                    } else {
                        val result = withContext(Dispatchers.IO) { runCatching { GitHubEcosystem.recentRuns(context.applicationContext) } }
                        result.onSuccess { runs = it }.onFailure {
                            if (tab == requestedTab) message = it.message?.take(180) ?: "GitHub unavailable"
                        }
                    }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.testTag("downloads_load")) {
                Text(if (busy) "Loading…" else "Load from GitHub")
            }
        }
        if (message.isNotBlank()) item { Text(message, color = MaterialTheme.colorScheme.error) }
        if (saving) item {
            Text("Verified download: $progress%")
            OutlinedButton(onClick = { cancelSignal.set(true); cancelRequested = true },
                enabled = !cancelRequested, modifier = Modifier.testTag("downloads_cancel")) {
                Text(if (cancelRequested) "Cancelling…" else "Cancel download")
            }
        }
        item {
            OutlinedButton(onClick = {
                busy = true; message = ""
                coroutine.launch {
                    withContext(Dispatchers.IO) { runCatching {
                        VerifiedDownload.listSaved(context.applicationContext)
                    } }.onSuccess { history = it }.onFailure {
                        message = it.message?.take(180) ?: "Cannot read saved files"
                    }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.testTag("downloads_saved")) {
                Text("My verified files")
            }
        }
        if (history != null) {
            if (history!!.isEmpty()) item { Text("No previously verified downloads") }
            items(history!!, key = { it.file.name }) { entry ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.file.name, style = MaterialTheme.typography.titleSmall)
                        Text("SHA-256 ${entry.sha256.take(18)}…", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { saved = entry.file }) { Text("Select verified file") }
                    }
                }
            }
        }
        if (saved != null) item {
            Text("Verified file saved in private app storage: ${saved!!.name}")
            OutlinedButton(onClick = {
                runCatching {
                    val file = saved ?: return@runCatching
                    val uri = FileProvider.getUriForFile(context,
                        "${context.packageName}.verified-files", file)
                    val type = if (file.name.endsWith(".apk", true))
                        "application/vnd.android.package-archive" else "application/zip"
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, type)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.onFailure { message = "No compatible file viewer installed" }
            }) { Text("Open verified file") }
        }
        if (tab == 0) {
            val entries = releases
            if (entries == null && !busy) item { Text("Load the official releases when you're ready.") }
            if (entries != null && entries.isEmpty()) item { Text("No public non-prerelease GitHub releases found") }
            items(entries.orEmpty(), key = { it.tag }) { release ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(release.name.ifBlank { release.tag }, style = MaterialTheme.typography.titleMedium)
                        Text("${release.assets.size} release assets · Check publisher and signer before installation",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { runCatching { openGitHub(context, release.htmlUrl) }
                            .onFailure { message = "No browser available" } }) {
                            Text("View release")
                        }
                        release.assets.forEach { asset ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${asset.name} · ${asset.size / 1024} KiB", style = MaterialTheme.typography.bodyMedium)
                                Text(if (asset.verifiedDownloadable)
                                    "Published SHA-256: ${asset.digest?.removePrefix("sha256:")?.take(16)}…"
                                    else "No verifiable SHA-256 or unsupported file; browser only",
                                    style = MaterialTheme.typography.labelSmall)
                                if (asset.verifiedDownloadable) {
                                    OutlinedButton(onClick = {
                                        busy = true; saving = true; cancelRequested = false
                                        cancelSignal.set(false)
                                        progress = 0; message = ""
                                        coroutine.launch {
                                            var lastPercent = -1 // Throttle UI events to at most 101 updates.
                                            val outcome = withContext(Dispatchers.IO) { runCatching {
                                                VerifiedDownload.save(context.applicationContext, asset,
                                                    cancelled = { cancelSignal.get() },
                                                    onProgress = { received, total ->
                                                        val percent = ((received * 100) / total).toInt().coerceIn(0, 100)
                                                        if (percent != lastPercent) {
                                                            lastPercent = percent
                                                            coroutine.launch { progress = percent }
                                                        }
                                                    })
                                            } }
                                            outcome.onSuccess { saved = it; message = "Verified SHA-256" }
                                                .onFailure { message = it.message?.take(180) ?: "Download failed" }
                                            saving = false; busy = false
                                        }
                                    }, enabled = !busy, modifier = Modifier.testTag("verified_download")) {
                                        Text("Save SHA-256 verified")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val entries = runs
            if (entries == null && !busy) item {
                Text("Load recent GitHub Actions runs. Artifacts open on GitHub and may require sign-in.")
            }
            if (entries != null && entries.isEmpty()) item { Text("No public CI runs found") }
            items(entries.orEmpty(), key = { it.id }) { run ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Run ${run.id} · ${run.status}", style = MaterialTheme.typography.titleMedium)
                        Text("Source ${run.commit} · attempt ${run.attempt} · CI status is not a signing proof",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                selectedRun = run; artifacts = null; busy = true; message = ""
                                coroutine.launch {
                                    withContext(Dispatchers.IO) { runCatching {
                                        GitHubEcosystem.runArtifacts(run, context.applicationContext)
                                    } }.onSuccess { artifacts = it }.onFailure {
                                        message = it.message?.take(180) ?: "Artifact lookup unavailable"
                                    }
                                    busy = false
                                }
                            }, enabled = !busy) { Text("Inspect artifacts") }
                            OutlinedButton(onClick = {
                                runCatching { openGitHub(context, run.htmlUrl) }
                                    .onFailure { message = "No browser available" }
                            }) { Text("GitHub") }
                        }
                    }
                }
            }
            val active = selectedRun
            if (active != null) {
                item {
                    Text("Artifacts · run ${active.id} (current attempt ${active.attempt}; archives can predate reruns)",
                        style = MaterialTheme.typography.titleMedium)
                }
                if (artifacts != null && artifacts!!.isEmpty()) item { Text("No accessible artifacts in this run") }
                items(artifacts.orEmpty(), key = { it.id }) { artifact ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(artifact.name, style = MaterialTheme.typography.titleSmall)
                            Text("Artifact ${artifact.id} · ${artifact.bytes / 1024} KiB · " +
                                if (artifact.expired) "Expired" else "GitHub-hosted",
                                style = MaterialTheme.typography.bodySmall)
                            Text(artifact.digest?.let { "Archive digest ${it.take(23)}… (not an APK signature)" }
                                ?: "No published archive digest", style = MaterialTheme.typography.labelSmall)
                            OutlinedButton(onClick = {
                                runCatching { openGitHub(context, artifact.url) }
                                    .onFailure { message = "No browser available" }
                            }, enabled = !artifact.expired) { Text("View on GitHub") }
                        }
                    }
                }
            }
        }
    }
}
