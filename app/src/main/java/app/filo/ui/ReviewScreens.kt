package app.filo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckCircle
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.filo.ReviewController
import app.filo.data.FileEntry
import app.filo.data.FileKind
import app.filo.data.Opener
import app.filo.data.ReviewKind
import app.filo.data.ReviewResult
import app.filo.data.StorageInfo
import app.filo.data.entries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun ReviewKind.icon(): ImageVector = when (this) {
    ReviewKind.LARGE -> Icons.Outlined.SdStorage
    ReviewKind.DUPLICATES -> Icons.Outlined.FileCopy
    ReviewKind.OLD_DOWNLOADS -> Icons.Outlined.Download
    ReviewKind.SCREENSHOTS -> Icons.Outlined.Screenshot
    ReviewKind.INSTALLERS -> Icons.Outlined.Android
    ReviewKind.EMPTY_FOLDERS -> Icons.Outlined.FolderOff
}

private fun ReviewKind.explain(): String = when (this) {
    ReviewKind.LARGE -> "Files of 100 MB or more"
    ReviewKind.DUPLICATES -> "Identical files stored more than once"
    ReviewKind.OLD_DOWNLOADS -> "In Download and not changed for 90 days"
    ReviewKind.SCREENSHOTS -> "Everything in your Screenshots folders"
    ReviewKind.INSTALLERS -> ".apk files; apps already installed don't need them"
    ReviewKind.EMPTY_FOLDERS -> "Folders with nothing inside"
}

private fun ReviewResult.summary(kind: ReviewKind): String {
    val list = entries(kind)
    if (list.isEmpty()) return "Nothing found"
    return when (kind) {
        ReviewKind.DUPLICATES -> {
            val reclaim = duplicates.sumOf { it.reclaimable }
            "${duplicates.size} ${if (duplicates.size == 1) "set" else "sets"} · keeping one of each frees ${formatSize(reclaim)}"
        }
        ReviewKind.EMPTY_FOLDERS -> itemCount(list.size).replace("item", "folder")
        else -> "${itemCount(list.size).replace("item", "file")} · ${formatSize(list.sumOf { it.size })}"
    }
}

/** Kind colors for the storage bar. Fixed so the legend always matches. */
private val kindColors = mapOf(
    FileKind.IMAGE to Color(0xFF1D9E75),
    FileKind.VIDEO to Color(0xFF378ADD),
    FileKind.AUDIO to Color(0xFF7F77DD),
    FileKind.DOCUMENT to Color(0xFFD85A30),
    FileKind.APK to Color(0xFF639922),
    FileKind.ARCHIVE to Color(0xFFBA7517),
    FileKind.OTHER to Color(0xFF888780),
)
private val systemColor = Color(0xFFB4B2A9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(nav: NavController) {
    val graph = LocalGraph.current
    val state by graph.review.state.collectAsStateWithLifecycle()
    val space by produceState(StorageInfo.space(graph.storageRoot), state) {
        value = withContext(Dispatchers.IO) { StorageInfo.space(graph.storageRoot) }
    }
    LaunchedEffect(Unit) { if (state is ReviewController.State.Idle) graph.review.scan() }
    val home = LocalHome.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = { IconButton(onClick = home::openMenu) { Icon(Icons.Outlined.Menu, "Open menu") } },
                actions = {
                    if (state !is ReviewController.State.Scanning) {
                        IconButton(onClick = { graph.review.scan() }) { Icon(Icons.Outlined.Refresh, "Scan again") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "What takes up space on your phone, so you can decide what to keep. Nothing is deleted unless you choose it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            when (val s = state) {
                is ReviewController.State.Scanning, ReviewController.State.Idle -> item {
                    val files = (s as? ReviewController.State.Scanning)?.files ?: 0
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("Looking through your storage…", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("${"%,d".format(files)} files so far", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                is ReviewController.State.Failed -> item {
                    EmptyState(Icons.Outlined.Refresh, "Couldn't finish", s.message)
                }
                is ReviewController.State.Done -> {
                    item { BreakdownCard(s.result, space.total, space.used) }
                    items(ReviewKind.entries) { kind ->
                        ReviewCard(kind, s.result.summary(kind), enabled = s.result.entries(kind).isNotEmpty()) {
                            nav.navigate(Routes.reviewList(kind))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownCard(result: ReviewResult, total: Long, used: Long) {
    val parts = kindColors.keys.map { it to (result.bytesByKind[it] ?: 0L) }.filter { it.second > 0 }
    val system = (used - result.totalFileBytes).coerceAtLeast(0)
    val segments = parts.map { kindColors.getValue(it.first) to it.second } + listOf(systemColor to system)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                if (total > 0) "${formatSize(used)} of ${formatSize(total)} used" else "${formatSize(result.totalFileBytes)} in files",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(14.dp))
            Canvas(
                Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
                    .semantics { contentDescription = "Storage use by type" },
            ) {
                drawRoundRect(track, cornerRadius = CornerRadius(7.dp.toPx()))
                val base = if (total > 0) total.toFloat() else segments.sumOf { it.second }.toFloat().coerceAtLeast(1f)
                var x = 0f
                segments.forEach { (color, bytes) ->
                    val w = size.width * (bytes / base)
                    if (w > 0) drawRect(color, Offset(x, 0f), Size(w, size.height))
                    x += w
                }
            }
            Spacer(Modifier.height(14.dp))
            parts.forEach { (kind, bytes) -> LegendRow(kindColors.getValue(kind), kind.label, bytes) }
            if (system > 0) LegendRow(systemColor, "Apps, app data and system", system)
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, bytes: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(formatSize(bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReviewCard(kind: ReviewKind, summary: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(kind.icon(), null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(kind.title, style = MaterialTheme.typography.titleMedium)
                Text(kind.explain(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (enabled) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewListScreen(nav: NavController, kind: ReviewKind) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val state by graph.review.state.collectAsStateWithLifecycle()
    val favorites by graph.favorites.paths.collectAsStateWithLifecycle()
    val result = (state as? ReviewController.State.Done)?.result
    var selection by remember { mutableStateOf(emptySet<String>()) }
    val entries = result?.entries(kind).orEmpty()
    val selectedSize = entries.filter { it.path in selection }.sumOf { it.size }

    var sheetFor by remember { mutableStateOf<FileEntry?>(null) }
    var info by remember { mutableStateOf<FileEntry?>(null) }
    val drag = remember { DragSelect() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun open(entry: FileEntry) {
        when {
            entry.isDirectory -> nav.navigate(Routes.folder(entry.path))
            entry.kind == FileKind.IMAGE -> {
                graph.viewerPaths = entries.filter { it.kind == FileKind.IMAGE }.map { it.path }
                nav.navigate(Routes.image(entry.path))
            }
            !Opener.open(context, entry.file) -> graph.actions.notify("No app on your phone can open this file")
        }
    }

    fun showInFolder(entry: FileEntry) {
        val parent = entry.file.parentFile ?: return
        nav.navigate(Routes.folder(parent.absolutePath, highlight = entry.path))
    }

    fun toggle(entry: FileEntry) {
        selection = if (entry.path in selection) selection - entry.path else selection + entry.path
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(kind.title) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (kind == ReviewKind.DUPLICATES && result != null) {
                        TextButton(onClick = {
                            // Keep the oldest copy of each set; select the rest.
                            selection = result.duplicates.flatMap { group -> group.files.drop(1).map { it.path } }.toSet()
                        }) { Text("Select extra copies") }
                    } else if (entries.isNotEmpty()) {
                        TextButton(onClick = {
                            selection = if (selection.size == entries.size) emptySet() else entries.map { it.path }.toSet()
                        }) { Text(if (selection.size == entries.size) "Select none" else "Select all") }
                    }
                },
            )
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${itemCount(selection.size)} · ${formatSize(selectedSize)}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        OutlinedButton(onClick = { selection = emptySet() }) { Text("Clear") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            graph.actions.moveToTrash(selection)
                            selection = emptySet()
                        }) { Text("Move to trash") }
                    }
                }
            }
        },
    ) { padding ->
        if (result == null || entries.isEmpty()) {
            Column(Modifier.padding(padding)) {
                EmptyState(Icons.Outlined.CheckCircle, "Nothing here", "There's nothing in this list right now.")
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).dragToSelect(
                state = drag,
                keys = entries.map { it.path },
                selection = selection,
                onSelectionChange = { selection = it },
                itemAt = { p -> listState.layoutInfo.visibleItemsInfo.firstOrNull { p.y >= it.offset && p.y < it.offset + it.size }?.key as? String },
                scrollBy = { listState.scrollBy(it) },
            ),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    "Tap to select, or touch and hold and drag over several. Tap a thumbnail or ⋮ to look at a file first. Deleted files stay in Trash for 30 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (kind == ReviewKind.DUPLICATES) {
                result.duplicates.forEach { group ->
                    item(key = "header:" + group.files.first().path) {
                        SectionHeader(
                            "${group.files.size} copies · ${formatSize(group.size)} each",
                            Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    items(group.files, key = { it.path }) { entry ->
                        FileRow(
                            entry,
                            selected = entry.path in selection,
                            selectionMode = false,
                            favorite = entry.path in favorites,
                            onClick = { if (!drag.consumeClick()) toggle(entry) },
                            onLongClick = { toggle(entry) },
                            onMore = { sheetFor = entry },
                            onThumbClick = { open(entry) },
                            longPressFromParent = true,
                            selectedMark = true,
                        )
                    }
                }
            } else {
                items(entries, key = { it.path }) { entry ->
                    FileRow(
                            entry,
                            selected = entry.path in selection,
                            selectionMode = false,
                            favorite = entry.path in favorites,
                            onClick = { if (!drag.consumeClick()) toggle(entry) },
                            onLongClick = { toggle(entry) },
                            onMore = { sheetFor = entry },
                            onThumbClick = { open(entry) },
                            longPressFromParent = true,
                            selectedMark = true,
                        )
                }
            }
        }
    }

    sheetFor?.let { entry ->
        ReviewItemSheet(
            entry = entry,
            selected = entry.path in selection,
            onDismiss = { sheetFor = null },
            onOpen = { open(entry) },
            onShowInFolder = { showInFolder(entry) },
            onInfo = { info = entry },
            onToggle = { toggle(entry) },
        )
    }
    info?.let { FileInfoDialog(it) { info = null } }
}

/** "Internal storage › Download › Old" instead of a raw path. */
@Composable
private fun placeName(dir: java.io.File?): String {
    val root = LocalGraph.current.storageRoot
    if (dir == null) return ""
    val relative = dir.absolutePath.removePrefix(root.absolutePath).trim('/')
    return if (dir.absolutePath.startsWith(root.absolutePath)) {
        (listOf("Internal storage") + relative.split('/').filter { it.isNotEmpty() }).joinToString(" › ")
    } else {
        dir.absolutePath
    }
}

/** Look at a file before deciding: open it, see where it lives, or check its details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewItemSheet(
    entry: FileEntry,
    selected: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShowInFolder: () -> Unit,
    onInfo: () -> Unit,
    onToggle: () -> Unit,
) {
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun after(action: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.padding(bottom = 16.dp)) {
            androidx.compose.material3.ListItem(
                leadingContent = { FileThumb(entry, 48.dp) },
                headlineContent = { Text(entry.name, maxLines = 2) },
                supportingContent = { Text("${entry.subtitle()}\n${placeName(entry.file.parentFile)}") },
            )
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
            @Composable
            fun action(text: String, icon: ImageVector, run: () -> Unit) {
                androidx.compose.material3.ListItem(
                    leadingContent = { Icon(icon, null) },
                    headlineContent = { Text(text) },
                    modifier = Modifier.clickableRow { after(run) },
                )
            }
            action("Open", Icons.AutoMirrored.Outlined.OpenInNew, onOpen)
            action("Show in folder", Icons.Outlined.FolderOpen, onShowInFolder)
            action("File info", Icons.Outlined.Info, onInfo)
            action(if (selected) "Don't select" else "Select", Icons.Outlined.CheckCircle, onToggle)
        }
    }
}
