package io.github.mikeyphw.mmrl.lsposed

import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** S01: independent, deliberately unprivileged Android shell and discovery. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StandaloneApp() }
    }
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
        var selectedModule by remember { mutableStateOf<ModuleDiscovery.Candidate?>(null) }
        var candidates by remember { mutableStateOf<List<ModuleDiscovery.Candidate>?>(null) }
        var discoveryError by remember { mutableStateOf(false) }
        var refreshKey by remember { mutableIntStateOf(0) }
        BackHandler(enabled = settings || selectedModule != null) {
            if (selectedModule != null) selectedModule = null else settings = false
        }
        LaunchedEffect(refreshKey) {
            discoveryError = false
            val result = withContext(Dispatchers.IO) {
                runCatching { ModuleDiscovery.scan(context.applicationContext) }
            }
            result.onSuccess { candidates = it }.onFailure { discoveryError = true; candidates = null }
        }
        AppScaffold(
            section = section,
            onSectionChange = { section = it; selectedModule = null; settings = false },
            settings = settings,
            onSettings = { settings = true },
            onBack = { if (selectedModule != null) selectedModule = null else settings = false },
            selectedModule = selectedModule,
        ) { innerPadding ->
            when {
                settings -> SettingsPage(appearance, onAppearance = { appearance = it }, innerPadding)
                selectedModule != null -> ModuleDetailPage(selectedModule!!, innerPadding)
                section == Section.HOME -> HomePage(candidates, innerPadding, onBrowse = {
                    section = Section.MODULES
                })
                section == Section.MODULES -> ModuleListPage(
                    candidates, discoveryError, innerPadding,
                    onRefresh = { candidates = null; refreshKey++ }, onOpen = { selectedModule = it },
                )
                section == Section.APPS -> PlaceholderPage(
                    R.drawable.ic_apps, R.string.device_apps_title, R.string.device_apps_description, innerPadding)
                section == Section.DISCOVER -> PlaceholderPage(
                    R.drawable.ic_discover, R.string.discover_title, R.string.discover_description, innerPadding)
                else -> PlaceholderPage(
                    R.drawable.ic_downloads, R.string.downloads_title, R.string.downloads_description, innerPadding)
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
    content: @Composable (PaddingValues) -> Unit,
) {
    val nested = settings || selectedModule != null
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when {
                            settings -> stringResource(R.string.settings)
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
                    if (!nested) IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = {
            if (!nested) NavigationBar {
                Section.entries.forEach { destination ->
                    NavigationBarItem(
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
    padding: PaddingValues,
    onBrowse: () -> Unit,
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
                        Text(stringResource(R.string.not_connected), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.framework_details), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
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
    padding: PaddingValues,
    onRefresh: () -> Unit,
    onOpen: (ModuleDiscovery.Candidate) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ModuleFilter.ALL) }
    val visible = remember(candidates, query, filter) {
        candidates.orEmpty().filter { item ->
            (filter == ModuleFilter.ALL || (filter == ModuleFilter.MODERN &&
                item.format != ModuleDiscovery.Format.LEGACY) || (filter == ModuleFilter.LEGACY &&
                item.format != ModuleDiscovery.Format.MODERN)) &&
                (item.name.contains(query, ignoreCase = true) ||
                    item.packageName.contains(query, ignoreCase = true))
        }
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
                    modifier = Modifier.weight(1f), singleLine = true,
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
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleDetailPage(candidate: ModuleDiscovery.Candidate, padding: PaddingValues) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    InstalledAppIcon(candidate.packageName, size = 68)
                    Text(candidate.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.installed_not_enabled),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.config_not_verified), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {}, enabled = false) {
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
            Text(stringResource(R.string.scope_future), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsPage(appearance: Appearance, onAppearance: (Appearance) -> Unit, padding: PaddingValues) {
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
}
