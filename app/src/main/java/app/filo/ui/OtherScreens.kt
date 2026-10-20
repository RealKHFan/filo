package app.filo.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import kotlinx.coroutines.launch
import app.filo.data.FileEntry
import androidx.compose.material3.Surface
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.filo.BuildConfig
import app.filo.data.FileKind
import app.filo.data.Opener
import app.filo.data.ThemeMode
import app.filo.data.TrashItem
import coil3.compose.AsyncImage
import java.io.File

// ---------------------------------------------------------------- Trash

private fun TrashItem.asEntry(graph: app.filo.Graph) = FileEntry(
    path = graph.trash.fileOf(this).absolutePath,
    name = name,
    isDirectory = isDirectory,
    size = size,
    lastModified = deletedAt,
)

/** Trash tab: same look as the file lists. Tap or ⋮ for options, long-press to select several. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(active: Boolean = true) {
    val graph = LocalGraph.current
    val home = LocalHome.current
    val changes by graph.actions.changes.collectAsStateWithLifecycle()
    val items by produceState<List<TrashItem>?>(null, changes) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.trash.list() }
    }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var sheetFor by remember { mutableStateOf<TrashItem?>(null) }
    var confirm by remember { mutableStateOf<List<TrashItem>?>(null) }
    val trashDrag = remember { DragSelect() }
    val trashList = androidx.compose.foundation.lazy.rememberLazyListState()
    val list = items.orEmpty()
    val selected = list.filter { it.id in selection }
    androidx.activity.compose.BackHandler(enabled = active && selection.isNotEmpty()) { selection = emptySet() }

    fun from(item: TrashItem): String {
        val parent = File(item.originalPath).parentFile
        return if (parent == null || parent == graph.storageRoot) "Internal storage" else parent.name
    }

    fun toggle(item: TrashItem) {
        selection = if (item.id in selection) selection - item.id else selection + item.id
    }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Outlined.Close, "Clear selection") } },
                    title = { Text("${selection.size} selected") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    actions = {
                        IconButton(onClick = { selection = list.map { it.id }.toSet() }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                        IconButton(onClick = {
                            graph.actions.restoreFromTrash(selected)
                            selection = emptySet()
                        }) { Icon(Icons.Outlined.RestoreFromTrash, "Restore") }
                        IconButton(onClick = { confirm = selected }) { Icon(Icons.Outlined.DeleteForever, "Delete for good") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Trash") },
                    navigationIcon = { IconButton(onClick = home::openMenu) { Icon(Icons.Outlined.Menu, "Open menu") } },
                    actions = { if (list.isNotEmpty()) TextButton(onClick = { confirm = list }) { Text("Empty") } },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                items == null -> Loading()
                list.isEmpty() -> EmptyState(Icons.Outlined.DeleteOutline, "Trash is empty", "Files you delete stay here for 30 days, so you can bring them back.")
                else -> LazyColumn(
                    state = trashList,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.dragToSelect(
                        state = trashDrag,
                        keys = list.map { it.id },
                        selection = selection,
                        onSelectionChange = { selection = it },
                        itemAt = { p -> trashList.layoutInfo.visibleItemsInfo.firstOrNull { p.y >= it.offset && p.y < it.offset + it.size }?.key as? String },
                        scrollBy = { trashList.scrollBy(it) },
                    ),
                ) {
                    item {
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("${itemCount(list.size)} · ${formatSize(list.sumOf { it.size })}", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Deleted files are kept for 30 days, then removed for good.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(list, key = { it.id }) { item ->
                        val days = graph.trash.daysLeft(item)
                        FileRow(
                            entry = item.asEntry(graph),
                            selected = item.id in selection,
                            selectionMode = selection.isNotEmpty(),
                            favorite = false,
                            onClick = {
                                when {
                                    trashDrag.consumeClick() -> Unit
                                    selection.isNotEmpty() -> toggle(item)
                                    else -> sheetFor = item
                                }
                            },
                            longPressFromParent = true,
                            onLongClick = { toggle(item) },
                            onMore = { sheetFor = item },
                            subtitle = "${formatSize(item.size)} · ${if (days == 1) "1 day" else "$days days"} left · from ${from(item)}",
                        )
                    }
                }
            }
        }
    }

    sheetFor?.let { item ->
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        fun after(action: () -> Unit) {
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                sheetFor = null
                action()
            }
        }
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { sheetFor = null }, sheetState = sheetState) {
            Column(Modifier.padding(bottom = 16.dp)) {
                ListItem(
                    leadingContent = { FileThumb(item.asEntry(graph), 44.dp) },
                    headlineContent = { Text(item.name, maxLines = 2) },
                    supportingContent = { Text("Deleted ${formatDate(item.deletedAt)} · from ${from(item)}") },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.RestoreFromTrash, null) },
                    headlineContent = { Text("Restore") },
                    supportingContent = { Text("Put it back in ${from(item)}") },
                    modifier = Modifier.clickableRow { after { graph.actions.restoreFromTrash(listOf(item)) } },
                )
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.DeleteForever, null) },
                    headlineContent = { Text("Delete for good") },
                    modifier = Modifier.clickableRow { after { confirm = listOf(item) } },
                )
            }
        }
    }

    confirm?.let { toDelete ->
        val all = toDelete.size == list.size && list.size > 1
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (all) "Empty trash?" else "Delete ${itemCount(toDelete.size)} for good?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                Button(onClick = {
                    confirm = null
                    selection = emptySet()
                    if (all) graph.actions.emptyTrash() else graph.actions.deleteForever(toDelete)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    var themeDialog by remember { mutableStateOf(false) }
    var lockDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Display", Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text("Theme") },
                supportingContent = { Text(settings.themeMode.label) },
                modifier = Modifier.clickableRow { themeDialog = true },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow("Colors from wallpaper", "Match Filo to your wallpaper, like other Pixel apps", settings.dynamicColor) { on ->
                    graph.settings.update { it.copy(dynamicColor = on) }
                }
            }
            SwitchRow(
                "Show hidden files",
                "Files and folders whose names start with a dot, such as .thumbnails",
                settings.showHidden,
            ) { on -> graph.settings.update { it.copy(showHidden = on) } }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Apps", Modifier.padding(horizontal = 16.dp))
            SwitchRow("Show system apps", "Include apps that came with the phone", settings.showSystemApps) { on ->
                graph.settings.update { it.copy(showSystemApps = on) }
            }
            val changes by graph.actions.changes.collectAsStateWithLifecycle()
            val usageAccess = remember(changes, settings.fullAppSizes) { graph.apps.hasUsageAccess() }
            SwitchRow(
                "Show full app sizes",
                when {
                    !settings.fullAppSizes -> "Off: only the app itself is counted. Turning this on opens Android's Usage access page, where you decide. Filo never asks with a pop-up."
                    !usageAccess -> "Waiting for Usage access. Allow Filo in Android's Usage access page to count app data and cache."
                    else -> "App, data and cache are counted."
                },
                settings.fullAppSizes,
            ) { on ->
                graph.settings.update { it.copy(fullAppSizes = on) }
                if (on && !graph.apps.hasUsageAccess()) app.filo.data.AppActions.openUsageAccessSettings(context)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Privacy", Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text("No internet access") },
                supportingContent = { Text("Filo can't connect to the internet at all, so your files never leave your phone.") },
            )
            SectionHeader("Hidden folder", Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text("Lock after leaving Filo") },
                supportingContent = { Text(autoLockLabel(settings.autoLockSeconds)) },
                modifier = Modifier.clickableRow { lockDialog = true },
            )
            ListItem(
                headlineContent = { Text("PIN and fingerprint") },
                supportingContent = { Text("Change them from the ⋮ menu inside the hidden folder. It always locks when you go back out of it.") },
                modifier = Modifier.clickableRow { nav.navigate(Routes.VAULT) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (lockDialog) {
        ChoiceDialog(
            title = "Lock after leaving Filo",
            options = AUTO_LOCK_CHOICES.map { it to autoLockLabel(it) },
            selected = settings.autoLockSeconds,
            onSelect = { seconds -> graph.settings.update { it.copy(autoLockSeconds = seconds) } },
            onDismiss = { lockDialog = false },
        )
    }

    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = settings.themeMode == mode,
                                role = Role.RadioButton,
                                onClick = {
                                    graph.settings.update { it.copy(themeMode = mode) }
                                    themeDialog = false
                                },
                            ).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.themeMode == mode, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(mode.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { themeDialog = false }) { Text("Close") } },
        )
    }
}

@Composable
fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = body?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) }),
    )
}

val AUTO_LOCK_CHOICES = listOf(0, 5, 15, 30, 60)

fun autoLockLabel(seconds: Int) = when (seconds) {
    0 -> "Immediately"
    60 -> "After 1 minute"
    else -> "After $seconds seconds"
}

@Composable
fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = value == selected,
                            role = Role.RadioButton,
                            onClick = {
                                onSelect(value)
                                onDismiss()
                            },
                        ).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---------------------------------------------------------------- About

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(nav: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(88.dp).clip(CircleShape).background(androidx.compose.ui.res.colorResource(app.filo.R.color.filo_teal))) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(app.filo.R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Filo", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            val context = LocalContext.current
            ListItem(
                headlineContent = { Text("Source code") },
                supportingContent = { Text(REPO_URL.removePrefix("https://")) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Opens in your browser") },
                modifier = Modifier.clickableRow {
                    // Filo itself has no internet access; the browser opens the page.
                    try {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, REPO_URL.toUri()))
                    } catch (_: Exception) {
                    }
                },
            )
            ListItem(
                headlineContent = { Text("License") },
                supportingContent = { Text("GNU General Public License v3. You may use, study, share and change Filo. Read the full license") },
                modifier = Modifier.clickableRow { nav.navigate(Routes.license("gpl")) },
            )
            ListItem(
                headlineContent = { Text("No internet access") },
                supportingContent = { Text("Filo can't connect to anything. No ads, no tracking, no Google services.") },
            )
            ListItem(
                headlineContent = { Text("Font") },
                supportingContent = { Text("Plus Jakarta Sans by Tokotype, SIL Open Font License 1.1. Read the font license") },
                modifier = Modifier.clickableRow { nav.navigate(Routes.license("font")) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

const val REPO_URL = "https://github.com/RealKHFan/filo"

/** Shows the full license text, bundled with the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(nav: NavController, which: String) {
    val context = LocalContext.current
    val text = remember(which) {
        val res = if (which == "font") app.filo.R.raw.ofl else app.filo.R.raw.gpl3
        context.resources.openRawResource(res).bufferedReader().use { it.readText() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (which == "font") "Font license" else "License") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.padding(padding)) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Image viewer

/** The pictures to swipe through: the list the opening screen showed, or the file's folder. */
private fun viewerList(graph: app.filo.Graph, file: File): List<String> {
    val given = graph.viewerPaths
    if (file.absolutePath in given) return given
    val siblings = file.parentFile?.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && FileKind.ofName(it.name) == FileKind.IMAGE }
        ?.sortedWith(compareBy(app.filo.data.NaturalOrder) { it.name })
        ?.map { it.absolutePath }
    return siblings?.takeIf { file.absolutePath in it } ?: listOf(file.absolutePath)
}

/** Full-screen pictures: swipe left and right, pinch or double-tap to zoom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(nav: NavController, file: File) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val paths = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(viewerList(graph, file)) } }
    if (paths.isEmpty()) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    PhotoPager(
        count = paths.size,
        initialPage = paths.indexOf(file.absolutePath).coerceAtLeast(0),
        title = { File(paths[it]).name },
        onBack = { nav.popBackStack() },
        actions = { page ->
            val current = File(paths[page])
            IconButton(onClick = { Opener.share(context, listOf(current)) }) { Icon(Icons.Outlined.Share, "Share", tint = Color.White) }
            IconButton(onClick = { Opener.open(context, current, chooser = true) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open with", tint = Color.White)
            }
            IconButton(onClick = { nav.navigate(Routes.folder(current.parent ?: graph.storageRoot.absolutePath, highlight = current.absolutePath)) }) {
                Icon(Icons.Outlined.FolderOpen, "Show in folder", tint = Color.White)
            }
            IconButton(onClick = {
                graph.actions.moveToTrash(listOf(current.absolutePath))
                paths.remove(current.absolutePath)
                if (paths.isEmpty()) nav.popBackStack()
            }) { Icon(Icons.Outlined.Delete, "Move to trash", tint = Color.White) }
        },
    ) { page, modifier ->
        AsyncImage(model = File(paths[page]), contentDescription = File(paths[page]).name, contentScale = ContentScale.Fit, modifier = modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoPager(
    count: Int,
    initialPage: Int,
    title: (Int) -> String,
    onBack: () -> Unit,
    actions: @Composable (page: Int) -> Unit,
    page: @Composable (page: Int, modifier: Modifier) -> Unit,
) {
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage) { count }
    var zoomed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().testTag("photo_pager"),
        ) { index ->
            ZoomableBox(resetKey = pager.settledPage, onZoomChange = { if (index == pager.currentPage) zoomed = it }) { modifier ->
                page(index, modifier)
            }
        }
        val current = pager.currentPage.coerceIn(0, (count - 1).coerceAtLeast(0))
        TopAppBar(
            title = {
                Column {
                    Text(if (count > 0) title(current) else "", color = Color.White, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    if (count > 1) Text("${current + 1} of $count", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) } },
            actions = { if (count > 0) actions(current) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.45f)),
        )
    }
}

/**
 * Pinch or double-tap to zoom. While not zoomed, one-finger swipes go to the pager (next picture);
 * while zoomed, they move the picture around.
 */
@Composable
private fun ZoomableBox(resetKey: Any, onZoomChange: (Boolean) -> Unit, content: @Composable (Modifier) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(resetKey) {
        scale = 1f
        offset = Offset.Zero
        onZoomChange(false)
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        offset = clampOffset((center - tap) * 1.5f, scale, size.width.toFloat(), size.height.toFloat())
                    }
                    onZoomChange(scale > 1f)
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) {
                                Offset.Zero
                            } else {
                                clampOffset(offset + event.calculatePan(), scale, size.width.toFloat(), size.height.toFloat())
                            }
                            onZoomChange(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        content(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}

private fun clampOffset(offset: Offset, scale: Float, width: Float, height: Float): Offset {
    val maxX = width * (scale - 1) / 2
    val maxY = height * (scale - 1) / 2
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

// ---------------------------------------------------------------- Permission

@Composable
fun PermissionScreen() {
    val context = LocalContext.current
    val graph = LocalGraph.current
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        graph.permission.refresh()
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Filo", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "To show and manage your files, Filo needs “All files access”. " +
                "Filo has no internet access, so nothing ever leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
                try {
                    context.startActivity(direct)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
        }) { Text("Allow access") }
    }
}
