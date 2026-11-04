package io.github.mikeyphw.mmrl.lsposed

import android.os.Build
import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelProvider
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Independent manager: opt-out read-only Vector auto-connect on physical Android. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StandaloneApp() }
    }
}

private fun activityFrom(context: Context): ComponentActivity {
    var cursor: Context = context
    repeat(12) {
        val activity = cursor as? ComponentActivity
        if (activity != null) return activity
        cursor = (cursor as? ContextWrapper)?.baseContext
            ?: throw IllegalStateException("Expected an Activity context")
    }
    throw IllegalStateException("Unable to find Activity context")
}

private enum class Section(val label: Int, val icon: Int) {
    HOME(R.string.home, R.drawable.ic_home),
    MODULES(R.string.modules, R.drawable.ic_modules),
    APPS(R.string.apps, R.drawable.ic_apps),
    DISCOVER(R.string.discover, R.drawable.ic_discover),
    DOWNLOADS(R.string.downloads, R.drawable.ic_downloads),
}

private enum class Appearance { SYSTEM, LIGHT, DARK, AMOLED }
private enum class ModuleFilter { ALL, MODERN, LEGACY }
private enum class ModuleStateFilter { ALL, ENABLED, DISABLED }
private val Accent = Color(0xFF587FC7)

@Composable
private fun StandaloneApp() {
    val context = LocalContext.current
    var appearance by rememberSaveable { mutableStateOf(Appearance.SYSTEM) }
    val isDark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK, Appearance.AMOLED -> true
    }
    val colors = when {
        appearance == Appearance.AMOLED -> darkColorScheme(
            primary = Color(0xFFB6CCFF), background = Color.Black,
            surface = Color.Black, surfaceContainer = Color(0xFF121418),
        )
        Build.VERSION.SDK_INT >= 31 && isDark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        isDark -> darkColorScheme(primary = Color(0xFFB6CCFF))
        else -> lightColorScheme(primary = Accent)
    }
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        var section by rememberSaveable { mutableStateOf(Section.HOME) }
        var settings by rememberSaveable { mutableStateOf(false) }
        val activity = remember(context) { activityFrom(context) }
        val session = remember(activity) {
            ViewModelProvider(activity)[VectorUiSession::class.java].also { stored ->
                if (stored.vector is VectorState.Connecting)
                    stored.vector = VectorState.Unavailable("Connect was interrupted; retry manually")
                if (stored.scope is ScopeState.Loading)
                    stored.scope = ScopeState.Unavailable("Scope read interrupted; reopen preview")
                // Activity recreation can cancel a UI coroutine; journal recovery stays authoritative.
                stored.writeBusy = false
            }
        }
        var selectedModule by session::selectedModule
        var candidates by remember { mutableStateOf<List<ModuleDiscovery.Candidate>?>(null) }
        var discoveryError by remember { mutableStateOf(false) }
        var refreshKey by remember { mutableIntStateOf(0) }
        var vectorRefresh by remember { mutableIntStateOf(0) }
        var vector by session::vector
        var autoConnect by remember { mutableStateOf(LocalSettings.autoConnect(context.applicationContext)) }
        LaunchedEffect(session) {
            if (!session.autoConnectAttempted) {
                session.autoConnectAttempted = true
                if (autoConnect && !LocalSettings.isEmulator() &&
                    vector is VectorState.Disconnected) vectorRefresh++
            }
        }
        var appsIndex by session::appsIndex
        var appsRequest by remember { mutableIntStateOf(0) }
        var scopeOpen by session::scopeOpen
        var scopeRequest by remember { mutableIntStateOf(0) }
        var scope by session::scope
        val scopeCounts = session.scopeCounts
        BackHandler(enabled = session.writeBusy || settings || scopeOpen || selectedModule != null) {
            if (session.writeBusy) return@BackHandler
            when {
                scopeOpen -> { scopeOpen = false; scope = ScopeState.Idle }
                selectedModule != null -> selectedModule = null
                else -> settings = false
            }
        }
        LaunchedEffect(refreshKey) {
            discoveryError = false
            val result = withContext(Dispatchers.IO) {
                runCatching { ModuleDiscovery.scan(context.applicationContext) }
            }
            result.onSuccess { candidates = it }.onFailure { discoveryError = true; candidates = null }
        }
        LaunchedEffect(appsRequest) {
            if (appsRequest == 0) return@LaunchedEffect
            val snapshot = (vector as? VectorState.Ready)?.snapshot ?: return@LaunchedEffect
            appsIndex = AppsIndexState.Loading
            val result = withContext(Dispatchers.IO) {
                runCatching { AppsReverseIndex.inspect(context.applicationContext, snapshot) }
            }
            // A refresh or mutation invalidates the older snapshot while the IO read is running.
            if ((vector as? VectorState.Ready)?.snapshot === snapshot) {
                appsIndex = result.fold(
                    onSuccess = { AppsIndexState.Ready(it) },
                    onFailure = { AppsIndexState.Unavailable(it.message?.take(180) ?: "Scope index unavailable") },
                )
            }
        }
        LaunchedEffect(vectorRefresh) {
            if (vectorRefresh == 0) return@LaunchedEffect
            vector = VectorState.Connecting
            appsIndex = AppsIndexState.Idle
            session.scopeCounts = emptyMap()
            val result = withContext(Dispatchers.IO) {
                runCatching { VectorReadOnly.inspect(context.applicationContext) }
            }
            vector = result.fold(onSuccess = { VectorState.Ready(it) },
                onFailure = { VectorState.Unavailable(it.message?.take(120) ?: "Root access unavailable") })
        }
        LaunchedEffect(scopeRequest) {
            if (scopeRequest == 0) return@LaunchedEffect
            val pkg = selectedModule?.packageName ?: return@LaunchedEffect
            scope = ScopeState.Loading
            val result = withContext(Dispatchers.IO) {
                runCatching { VectorReadOnly.scopes(context.applicationContext, pkg) }
            }
            if (scopeOpen && selectedModule?.packageName == pkg) {
                result.onSuccess { session.scopeCounts = session.scopeCounts + (pkg to it.size) }
                scope = result.fold(onSuccess = { ScopeState.Ready(it) },
                    onFailure = { ScopeState.Unavailable(it.message?.take(120) ?: "Scope read failed") })
            }
        }
        val combined = candidates?.let { installed ->
            VectorReadOnly.mergeInstalled(installed, (vector as? VectorState.Ready)?.snapshot)
        }
        AppScaffold(
            section = section,
            onSectionChange = { section = it; selectedModule = null; scopeOpen = false; settings = false },
            settings = settings,
            onSettings = { settings = true },
            onBack = {
                if (!session.writeBusy) {
                    when {
                        scopeOpen -> { scopeOpen = false; scope = ScopeState.Idle }
                        selectedModule != null -> selectedModule = null
                        else -> settings = false
                    }
                }
            },
            selectedModule = selectedModule,
            scopePreview = scopeOpen,
        ) { innerPadding ->
            when {
                settings -> SettingsPage(appearance, onAppearance = { appearance = it },
                    vector = vector, onRefreshVector = { vectorRefresh++ },
                    autoConnect = autoConnect, onAutoConnect = { selected ->
                        LocalSettings.setAutoConnect(context.applicationContext, selected)
                        autoConnect = selected
                        if (selected && !LocalSettings.isEmulator() &&
                            vector !is VectorState.Ready && vector !is VectorState.Connecting) vectorRefresh++
                    }, padding = innerPadding)
                scopeOpen && selectedModule != null -> {
                    val currentModule = selectedModule!!
                    ScopeDraftPage(currentModule, scope, innerPadding,
                        enabled = (vector as? VectorState.Ready)?.snapshot?.modules
                            ?.get(currentModule.packageName)?.enabled,
                        onWriteBusy = { session.writeBusy = it },
                        onCommitted = { result ->
                            vector = VectorState.Ready(result.snapshot)
                            appsIndex = AppsIndexState.Idle
                            scope = ScopeState.Ready(result.scopes)
                            session.scopeCounts = session.scopeCounts +
                                (currentModule.packageName to result.scopes.size)
                        })
                }
                selectedModule != null -> {
                    val currentModule = selectedModule!!
                    ModuleDetailPage(currentModule, vector, scopeCounts, innerPadding,
                        onScope = { scope = ScopeState.Idle; scopeOpen = true; scopeRequest++ },
                        onWriteBusy = { session.writeBusy = it },
                        onCommitted = { result ->
                            vector = VectorState.Ready(result.snapshot)
                            appsIndex = AppsIndexState.Idle
                            session.scopeCounts = session.scopeCounts +
                                (currentModule.packageName to result.scopes.size)
                        })
                }
                section == Section.HOME -> HomePage(combined, vector, innerPadding, onBrowse = {
                    section = Section.MODULES
                }, onConnect = { vectorRefresh++ }, onReconciled = { snapshot ->
                    vector = VectorState.Ready(snapshot)
                    appsIndex = AppsIndexState.Idle
                    session.scopeCounts = emptyMap()
                })
                section == Section.MODULES -> ModuleListPage(
                    combined, discoveryError, vector, scopeCounts, innerPadding,
                    onRefresh = { candidates = null; refreshKey++;
                        if (vector is VectorState.Ready) vectorRefresh++ },
                    onOpen = { selectedModule = it; scope = ScopeState.Idle; scopeOpen = false },
                )
                section == Section.APPS -> AppsReversePage(
                    appsIndex, vector is VectorState.Ready, innerPadding,
                    onBuild = { if (vector is VectorState.Ready) appsRequest++ },
                    onOpenModule = { pkg ->
                        combined?.firstOrNull { it.packageName == pkg }?.let {
                            selectedModule = it; scope = ScopeState.Idle; scopeOpen = false
                        }
                    },
                )
                section == Section.DISCOVER -> DiscoverPage(innerPadding)
                else -> DownloadsPage(innerPadding)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    section: Section,
    onSectionChange: (Section) -> Unit,
    settings: Boolean,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    selectedModule: ModuleDiscovery.Candidate?,
    scopePreview: Boolean,
    content: @Composable (PaddingValues) -> Unit,
) {
    val nested = settings || selectedModule != null || scopePreview
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when {
                            settings -> stringResource(R.string.settings)
                            scopePreview -> stringResource(R.string.scope_preview)
                            selectedModule != null -> selectedModule.name
                            else -> stringResource(section.label)
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    if (nested) IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (!nested) IconButton(onClick = onSettings,
                        modifier = Modifier.testTag("open_settings")) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = {
            if (!nested) NavigationBar {
                Section.entries.forEach { destination ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("nav_${destination.name.lowercase()}"),
                        selected = section == destination,
                        onClick = { onSectionChange(destination) },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(stringResource(destination.label), maxLines = 1) },
                        alwaysShowLabel = false,
                    )
                }
            }
        },
        content = content,
    )
}

@Composable
private fun HomePage(
    candidates: List<ModuleDiscovery.Candidate>?,
    vector: VectorState,
    padding: PaddingValues,
    onBrowse: () -> Unit,
    onConnect: () -> Unit,
    onReconciled: (VectorReadOnly.Snapshot) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.hello_title), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(stringResource(R.string.hello_description), color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    Button(onClick = onBrowse) { Text(stringResource(R.string.browse_modules)) }
                }
            }
        }
        item {
            SectionHeading(stringResource(R.string.framework))
            Spacer(Modifier.height(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(50.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_modules), null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(when (vector) {
                            VectorState.Disconnected -> "Not connected"
                            VectorState.Connecting -> "Connecting to Vector…"
                            is VectorState.Ready -> "Vector v2.2 · API ${vector.snapshot.apiVersion}"
                            is VectorState.Unavailable -> "Connection unavailable"
                        }, style = MaterialTheme.typography.titleMedium)
                        Text(when (vector) {
                            VectorState.Disconnected -> "Connect or enable automatic connection in Settings."
                            VectorState.Connecting -> "Requesting read-only framework access…"
                            is VectorState.Ready -> "${vector.snapshot.enabledCount} enabled / " +
                                "${vector.snapshot.modules.size} configured modules. Hooks not verified."
                            is VectorState.Unavailable -> vector.reason
                        }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onConnect, enabled = vector !is VectorState.Connecting,
                            modifier = Modifier.testTag("vector_connect")) {
                            Text(if (vector is VectorState.Ready) "Refresh Vector" else "Connect to Vector")
                        }
                        if (vector is VectorState.Ready) Text(
                            "Saved configuration checked at " +
                                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                                    .format(java.util.Date(vector.snapshot.observedAtMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { ReconcilePanel(onReconciled) }
        item {
            SectionHeading(stringResource(R.string.discovered_apks))
            Spacer(Modifier.height(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                onClick = onBrowse) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (candidates == null) stringResource(R.string.loading)
                            else stringResource(R.string.count_label, candidates.size),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.read_only_label),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(painterResource(R.drawable.ic_modules), null, modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ModuleListPage(
    candidates: List<ModuleDiscovery.Candidate>?,
    hasError: Boolean,
    vector: VectorState,
    scopeCounts: Map<String, Int>,
    padding: PaddingValues,
    onRefresh: () -> Unit,
    onOpen: (ModuleDiscovery.Candidate) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ModuleFilter.ALL) }
    var savedFilter by rememberSaveable { mutableStateOf(ModuleStateFilter.ALL) }
    var enabledFirst by rememberSaveable { mutableStateOf(false) }
    val saved = (vector as? VectorState.Ready)?.snapshot?.modules
    val visible = remember(candidates, query, filter, savedFilter, enabledFirst, saved) {
        val filtered = candidates.orEmpty().filter { item ->
            (filter == ModuleFilter.ALL || (filter == ModuleFilter.MODERN &&
                item.format in setOf(ModuleDiscovery.Format.MODERN, ModuleDiscovery.Format.BOTH)) ||
                (filter == ModuleFilter.LEGACY &&
                item.format in setOf(ModuleDiscovery.Format.LEGACY, ModuleDiscovery.Format.BOTH))) &&
                (item.name.contains(query, ignoreCase = true) ||
                    item.packageName.contains(query, ignoreCase = true)) &&
                (savedFilter == ModuleStateFilter.ALL ||
                    (saved != null && saved[item.packageName]?.enabled ==
                        (savedFilter == ModuleStateFilter.ENABLED)))
        }
        if (enabledFirst && saved != null) filtered.sortedWith(
            compareByDescending<ModuleDiscovery.Candidate> { saved[it.packageName]?.enabled == true }
                .thenBy { it.name.lowercase() }) else filtered
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.modules_description), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(120) },
                    label = { Text(stringResource(R.string.search_modules)) },
                    modifier = Modifier.weight(1f).testTag("module_search"), singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == ModuleFilter.ALL, onClick = { filter = ModuleFilter.ALL },
                    label = { Text(stringResource(R.string.all)) })
                FilterChip(selected = filter == ModuleFilter.MODERN, onClick = { filter = ModuleFilter.MODERN },
                    label = { Text(stringResource(R.string.modern)) })
                FilterChip(selected = filter == ModuleFilter.LEGACY, onClick = { filter = ModuleFilter.LEGACY },
                    label = { Text(stringResource(R.string.legacy)) })
            }
            if (saved != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = savedFilter == ModuleStateFilter.ALL,
                    onClick = { savedFilter = ModuleStateFilter.ALL }, label = { Text("Any status") })
                FilterChip(selected = savedFilter == ModuleStateFilter.ENABLED,
                    onClick = { savedFilter = ModuleStateFilter.ENABLED }, label = { Text("Enabled") })
                FilterChip(selected = savedFilter == ModuleStateFilter.DISABLED,
                    onClick = { savedFilter = ModuleStateFilter.DISABLED }, label = { Text("Disabled") })
            }
            FilterChip(selected = enabledFirst, onClick = { enabledFirst = !enabledFirst },
                enabled = saved != null, label = { Text("Sort: enabled first") })
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (hasError) item { SoftInfo(R.string.module_scan_error) }
            else if (candidates == null) item { SoftInfo(R.string.loading) }
            else if (candidates.isEmpty()) item {
                SoftInfo(R.string.empty_modules)
                Text(stringResource(R.string.empty_modules_detail),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else if (visible.isEmpty()) item { SoftInfo(R.string.search_empty) }
            else items(visible, key = { it.packageName }) { module ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(module) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        InstalledAppIcon(module.packageName)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(module.name, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(module.packageName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatLabel(module.format), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                            if (module.versionName.isNotBlank()) Text(
                                "Version ${module.versionName}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val stored = (vector as? VectorState.Ready)?.snapshot?.modules?.get(module.packageName)
                            if (stored != null) Text(
                                if (stored.enabled) "Enabled in Vector · saved state"
                                else "Disabled in Vector · saved state",
                                color = if (stored.enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium)
                            if (stored != null) Text(
                                scopeCounts[module.packageName]?.let { "$it saved scope targets (last read)" }
                                    ?: "Saved scope count not loaded",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else if (vector is VectorState.Ready) Text("Not in Vector inventory",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleDetailPage(candidate: ModuleDiscovery.Candidate, vector: VectorState,
    scopeCounts: Map<String, Int>, padding: PaddingValues, onScope: () -> Unit,
    onCommitted: (VectorMutation.Outcome) -> Unit,
    onWriteBusy: (Boolean) -> Unit) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    var preparing by remember(candidate.packageName) { mutableStateOf(false) }
    var committing by remember(candidate.packageName) { mutableStateOf(false) }
    var request by remember(candidate.packageName) { mutableStateOf<VectorMutation.Request?>(null) }
    var writeMessage by remember(candidate.packageName) { mutableStateOf("") }
    val stored = (vector as? VectorState.Ready)?.snapshot?.modules?.get(candidate.packageName)
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    InstalledAppIcon(candidate.packageName, size = 68)
                    Text(candidate.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(when {
                        stored == null -> "Saved enabled state unknown"
                        stored.enabled -> "Enabled in Vector saved configuration"
                        else -> "Disabled in Vector saved configuration"
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Live hooks are not verified by this manager.",
                        style = MaterialTheme.typography.bodySmall)
                    if (stored != null) Text(
                        scopeCounts[candidate.packageName]?.let { "$it saved scope targets (last read)" }
                            ?: "Saved scope count unknown until preview is opened",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (stored != null) {
                        OutlinedButton(onClick = {
                            preparing = true
                            writeMessage = ""
                            coroutine.launch {
                                val result = withContext(Dispatchers.IO) { runCatching {
                                    val current = VectorReadOnly.inspect(context.applicationContext)
                                    val now = current.modules[candidate.packageName]
                                    if (now?.enabled != stored.enabled)
                                        throw java.io.IOException("Saved state changed. Refresh Vector before editing.")
                                    val baseline = VectorReadOnly.scopes(context.applicationContext, candidate.packageName)
                                    VectorMutation.Request(candidate.packageName,
                                        if (stored.enabled) VectorMutation.Action.DISABLE else VectorMutation.Action.ENABLE,
                                        stored.enabled, baseline)
                                } }
                                result.onSuccess { request = it }.onFailure {
                                    writeMessage = it.message ?: "Could not prepare module change"
                                }
                                preparing = false
                            }
                        }, enabled = !preparing && !committing) {
                            Text(if (stored.enabled) "Disable module…" else "Enable module…")
                        }
                    }
                    if (preparing || committing) Text("Verifying saved Vector state…")
                    if (writeMessage.isNotBlank()) Text(writeMessage,
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onScope, enabled = stored != null) {
                        Text(stringResource(R.string.scope_preview))
                    }
                }
            }
        }
        item {
            SectionHeading(stringResource(R.string.technical_details))
            Spacer(Modifier.height(8.dp))
            DetailLine(stringResource(R.string.package_name), candidate.packageName)
            DetailLine(stringResource(R.string.module_format), formatLabel(candidate.format))
            DetailLine("APK version", candidate.versionName.ifBlank { "Unknown" })
            if (candidate.minApiVersion > 0) DetailLine("Minimum Xposed API",
                candidate.minApiVersion.toString())
            if (candidate.targetApiVersion > 0) DetailLine("Target Xposed API",
                candidate.targetApiVersion.toString())
            if (candidate.description.isNotBlank()) Text(candidate.description,
                style = MaterialTheme.typography.bodyMedium)
            if (candidate.declaredScopes.isNotEmpty()) DetailLine("Declared scope",
                "${candidate.declaredScopes.size} suggested targets" +
                if (candidate.staticScope) " (static)" else "")
            Text(stringResource(R.string.scope_future), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val confirmation = request
    if (confirmation != null) AlertDialog(
        onDismissRequest = { if (!committing) request = null },
        title = { Text(if (confirmation.action == VectorMutation.Action.ENABLE)
            "Enable saved module?" else "Disable saved module?") },
        text = { Text("${candidate.name} (${candidate.packageName})\n" +
            "Vector will update its saved configuration. Actual hooks may require a process restart. " +
            "The operation will be re-read and verified; an uncertain outcome blocks further writes.") },
        confirmButton = { TextButton(onClick = {
            request = null
            committing = true
            onWriteBusy(true)
            coroutine.launch {
                val outcome = withContext(Dispatchers.IO) { runCatching {
                    VectorMutation.change(context.applicationContext, confirmation)
                } }
                outcome.onSuccess {
                    onCommitted(it)
                    writeMessage = "Saved change verified · receipt ${it.receiptId.take(8)}"
                }.onFailure {
                    writeMessage = (it.message ?: "Uncertain write outcome") +
                        ". If a write started, review and reconcile from Home before retrying."
                }
                committing = false
                onWriteBusy(false)
            }
        }, enabled = !committing) { Text("Confirm one change") } },
        dismissButton = { TextButton(onClick = { request = null },
            enabled = !committing) { Text("Cancel") } },
    )
}

/** Reconciliation is two deliberate reads and explicit user acknowledgement; never a retry. */
@Composable
private fun ReconcilePanel(onReconciled: (VectorReadOnly.Snapshot) -> Unit) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    var pending by remember { mutableStateOf(runCatching {
        VectorWriteJournal.pending(context)
    }.getOrNull()) }
    var review by remember { mutableStateOf<VectorMutation.Review?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val entry = pending ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Configuration needs reconciliation", style = MaterialTheme.typography.titleMedium)
            Text("${entry.action} · ${entry.module}" +
                if (entry.target.isNotBlank()) " · ${entry.target}" else "",
                style = MaterialTheme.typography.bodySmall)
            Text("A previous write was interrupted or could not be verified. " +
                "No further writes are allowed until you inspect saved Vector state.",
                style = MaterialTheme.typography.bodyMedium)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = {
                busy = true; error = ""
                coroutine.launch {
                    withContext(Dispatchers.IO) { runCatching {
                        VectorMutation.reviewUncertain(context.applicationContext)
                    } }.onSuccess { review = it }.onFailure {
                        error = it.message ?: "Vector read failed"
                    }
                    busy = false
                }
            }, enabled = !busy) { Text("Inspect saved state") }
        }
    }
    val current = review
    if (current != null) AlertDialog(
        onDismissRequest = { if (!busy) review = null },
        title = { Text("Reconcile without retrying") },
        text = { Text("Module: ${current.entry.module}\n" +
            "Observed enabled: " +
            (current.snapshot.modules[current.entry.module]?.enabled?.toString() ?: "not listed") +
            "\nObserved saved targets: ${current.scopes.size}\n" +
            "This does NOT undo or repeat the previous change. " +
            "Acknowledging records the observation and unlocks future individually confirmed writes.") },
        confirmButton = { TextButton(onClick = {
            busy = true
            coroutine.launch {
                withContext(Dispatchers.IO) { runCatching {
                    VectorMutation.acknowledgeReview(context.applicationContext, current)
                } }.onSuccess { snapshot ->
                    review = null
                    pending = null
                    onReconciled(snapshot)
                }.onFailure {
                    error = it.message ?: "Reconciliation not verified"
                    review = null
                }
                busy = false
            }
        }, enabled = !busy) { Text("Acknowledge observed state") } },
        dismissButton = { TextButton(onClick = { review = null },
            enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsPage(appearance: Appearance, onAppearance: (Appearance) -> Unit,
    vector: VectorState, onRefreshVector: () -> Unit,
    autoConnect: Boolean, onAutoConnect: (Boolean) -> Unit, padding: PaddingValues) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    var tokenDraft by remember { mutableStateOf("") }
    var tokenStored by remember { mutableStateOf(LocalSettings.hasGitHubToken(context)) }
    var tokenStatus by remember { mutableStateOf("") }
    var testingToken by remember { mutableStateOf(false) }
    var updatingToken by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionHeading(stringResource(R.string.appearance)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Appearance.entries.forEach { option ->
                    val label = when (option) {
                        Appearance.SYSTEM -> R.string.system
                        Appearance.LIGHT -> R.string.light
                        Appearance.DARK -> R.string.dark
                        Appearance.AMOLED -> R.string.amoled
                    }
                    Surface(onClick = { onAppearance(option) },
                        color = if (appearance == option) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(label), style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f))
                            if (appearance == option) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        item { SectionHeading("Vector connection") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Connect to Vector automatically", style = MaterialTheme.typography.titleMedium)
                        Text("Read-only on app launch. No background polling, scope writes, " +
                            "or automatic retries after errors.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = autoConnect, onCheckedChange = onAutoConnect,
                        modifier = Modifier.testTag("auto_vector_connect"))
                }
            }
        }
        item { SectionHeading("Framework details") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when (vector) {
                        VectorState.Disconnected -> "Not connected · no root query made"
                        VectorState.Connecting -> "Connecting…"
                        is VectorState.Unavailable -> "Unavailable · ${vector.reason}"
                        is VectorState.Ready -> "${vector.snapshot.frameworkVersion} / API ${vector.snapshot.apiVersion}"
                    }, style = MaterialTheme.typography.titleMedium)
                    if (vector is VectorState.Ready) Text(
                        "${vector.snapshot.modules.size} configured modules · " +
                        "${vector.snapshot.enabledCount} saved-enabled. Hook execution not verified.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onRefreshVector, enabled = vector !is VectorState.Connecting) {
                        Text(if (vector is VectorState.Ready) "Refresh framework" else "Connect to Vector")
                    }
                }
            }
        }
        item { SectionHeading("GitHub API authentication") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (tokenStored) "Personal access token saved" else "No GitHub token saved",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Optional. Fine-grained read-only access is sufficient for public metadata. " +
                        "The token is encrypted with Android Keystore, excluded from backups, " +
                        "and sent only to api.github.com. It is not used for the LSPosed catalog.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = tokenDraft, onValueChange = { tokenDraft = it.take(512) },
                        label = { Text("GitHub personal access token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("github_token_input"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val submitted = tokenDraft
                            tokenDraft = ""
                            updatingToken = true
                            coroutine.launch {
                                val success = withContext(Dispatchers.IO) {
                                    runCatching { LocalSettings.saveGitHubToken(context.applicationContext, submitted) }
                                        .isSuccess
                                }
                                if (success) tokenStored = true
                                tokenStatus = if (success) "Token saved securely" else
                                    "Could not save token; check its format and device Keystore"
                                updatingToken = false
                            }
                        }, enabled = tokenDraft.isNotBlank() && !updatingToken && !testingToken) {
                            Text("Save token")
                        }
                        OutlinedButton(onClick = {
                            updatingToken = true
                            coroutine.launch {
                                val success = withContext(Dispatchers.IO) {
                                    runCatching { LocalSettings.clearGitHubToken(context.applicationContext) }
                                        .isSuccess
                                }
                                if (success) { tokenStored = false; tokenDraft = "" }
                                tokenStatus = if (success) "Token removed from settings" else
                                    "Could not remove token"
                                updatingToken = false
                            }
                        }, enabled = tokenStored && !updatingToken && !testingToken) { Text("Remove") }
                    }
                    OutlinedButton(onClick = {
                        testingToken = true
                        tokenStatus = "Checking GitHub API…"
                        coroutine.launch {
                            tokenStatus = withContext(Dispatchers.IO) {
                                runCatching { GitHubEcosystem.rateLimit(context.applicationContext) }
                                    .getOrElse { GitHubEcosystem.connectionDiagnostic(it) }
                            }
                            testingToken = false
                        }
                    }, enabled = !testingToken && !updatingToken, modifier = Modifier.testTag("github_token_check")) {
                        Text(if (testingToken) "Checking…" else "Check GitHub connection")
                    }
                    if (tokenStatus.isNotBlank()) Text(tokenStatus,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SectionHeading(stringResource(R.string.about)) }
        item { Text(stringResource(R.string.about_detail), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PlaceholderPage(icon: Int, title: Int, message: Int, padding: PaddingValues) {
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier.size(76.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SectionHeading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.SemiBold)

@Composable private fun SoftInfo(message: Int) = Text(stringResource(message),
    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)

@Composable private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(108.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InstalledAppIcon(packageName: String, size: Int = 48) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx -> ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = null
        } },
        update = { view ->
            val drawable = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            if (drawable == null) view.setImageResource(R.drawable.ic_launcher)
            else view.setImageDrawable(drawable)
        },
        modifier = Modifier.size(size.dp),
    )
}

@Composable
private fun formatLabel(format: ModuleDiscovery.Format): String = when (format) {
    ModuleDiscovery.Format.MODERN -> stringResource(R.string.modern)
    ModuleDiscovery.Format.LEGACY -> stringResource(R.string.legacy)
    ModuleDiscovery.Format.BOTH -> "${stringResource(R.string.modern)} + ${stringResource(R.string.legacy)}"
    ModuleDiscovery.Format.UNKNOWN -> "No installed APK metadata"
}
