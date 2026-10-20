package app.filo.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.filo.data.AppActions
import app.filo.data.AppInfo
import app.filo.data.Category
import app.filo.data.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppSort(val label: String) { NAME("Name"), SIZE("Size"), UPDATED("Last updated") }

/** Apps: what's installed, plus the .apk installer files lying around in storage. */
@Composable
fun AppsScreen(nav: NavController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs: @Composable () -> Unit = {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Installer files") })
        }
    }
    if (tab == 0) InstalledApps(nav, tabs) else CategoryScreen(nav, Category.APPS, title = "Apps", topContent = tabs)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledApps(nav: NavController, tabs: @Composable () -> Unit) {
    val graph = LocalGraph.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val changes by graph.actions.changes.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(AppSort.NAME) }
    var sortMenu by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<AppInfo?>(null) }
    val apps by produceState<List<AppInfo>?>(null, changes, settings.showSystemApps, settings.fullAppSizes) {
        value = graph.apps.installed(settings.showSystemApps, settings.fullAppSizes)
    }
    val sorted = apps?.let { list ->
        when (sort) {
            AppSort.NAME -> list.sortedWith(compareBy(app.filo.data.NaturalOrder) { it.label })
            AppSort.SIZE -> list.sortedByDescending { it.shownSize }
            AppSort.UPDATED -> list.sortedByDescending { it.updatedAt }
        }
    }
    val fullSizes = settings.fullAppSizes && graph.apps.hasUsageAccess()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        AppSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = { if (sort == option) Icon(Icons.Outlined.Check, "Current sort") else Spacer(Modifier.width(24.dp)) },
                                onClick = {
                                    sort = option
                                    sortMenu = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            tabs()
            val list = sorted
            when {
                list == null -> Loading()
                list.isEmpty() -> EmptyState(Icons.Outlined.Android, "No apps found", "Apps you install show up here.")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "${list.size} ${if (list.size == 1) "app" else "apps"} · ${formatSize(list.sumOf { it.shownSize })}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    if (fullSizes) "Sizes include app data and cache." else "Sizes show the app itself. Full sizes with data can be turned on in Settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(list, key = { it.packageName }) { app ->
                        ListItem(
                            leadingContent = { AppIcon(app.packageName, 44.dp) },
                            headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(formatSize(app.shownSize), app.versionName.takeIf { it.isNotBlank() }?.let { "Version $it" }, if (app.isSystem) "System" else null)
                                        .joinToString(" · "),
                                    maxLines = 1,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { sheetFor = app }) { Icon(Icons.Outlined.MoreVert, "Options for ${app.label}") }
                            },
                            modifier = Modifier.clickableRow { sheetFor = app },
                        )
                    }
                }
            }
        }
    }

    sheetFor?.let { app -> AppSheet(app) { sheetFor = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(app: AppInfo, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun after(action: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.padding(bottom = 16.dp)) {
            ListItem(
                leadingContent = { AppIcon(app.packageName, 48.dp) },
                headlineContent = { Text(app.label, style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text("${app.packageName}\n${formatSize(app.shownSize)} · updated ${formatDate(app.updatedAt)}") },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            @Composable
            fun action(text: String, icon: ImageVector, run: () -> Unit) {
                ListItem(
                    leadingContent = { Icon(icon, null) },
                    headlineContent = { Text(text) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickableRow { after(run) },
                )
            }
            action("Open", Icons.AutoMirrored.Outlined.OpenInNew) {
                if (!AppActions.open(context, app.packageName)) graph.actions.notify("${app.label} can't be opened directly")
            }
            action("App info", Icons.Outlined.Info) { AppActions.info(context, app.packageName) }
            action("Save APK to Download", Icons.Outlined.Download) { graph.actions.saveApk(app) }
            if (app.packageName != context.packageName) {
                action("Uninstall", Icons.Outlined.Delete) { AppActions.uninstall(context, app.packageName) }
            }
        }
    }
}

/** The app's real icon, loaded in the background; a generic one if it can't be read. */
@Composable
fun AppIcon(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(48)
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(px, px).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    val bitmap = icon
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(size)) else KindIcon(FileKind.APK, size)
    }
}
