package app.filo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.filo.data.ClipMode
import app.filo.data.FileEntry
import app.filo.data.FileKind
import app.filo.data.FileOps
import app.filo.data.MimeTypes
import app.filo.data.Opener
import app.filo.data.SortField
import app.filo.data.SortOrder
import app.filo.data.ViewLevel
import app.filo.data.ViewPrefs
import app.filo.data.sortedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The shared file list used by Storage, folders, categories, favorites and search:
 * tap to open, long-press to select, ⋮ for actions, copy/move with a paste bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileCollectionScreen(
    title: String,
    entries: List<FileEntry>?,
    nav: NavController,
    onBack: (() -> Unit)?,
    onOpenFolder: (File) -> Unit,
    viewKey: String,
    viewDefaults: ViewPrefs,
    modifier: Modifier = Modifier,
    currentDir: File? = null,
    selectionKey: Any = Unit,
    pickForVault: Boolean = false,
    emptyTitle: String = "Nothing here",
    emptyBody: String = "This folder is empty.",
    topContent: (@Composable () -> Unit)? = null,
    showSearchAction: Boolean = true,
    onMenu: (() -> Unit)? = null,
    active: Boolean = true,
    highlight: String? = null,
) {
    val graph = LocalGraph.current
    val home = LocalHome.current
    val context = LocalContext.current
    val actions = graph.actions
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val favorites by graph.favorites.paths.collectAsStateWithLifecycle()
    val clip by actions.clip.collectAsStateWithLifecycle()
    val views by graph.views.all.collectAsStateWithLifecycle()
    val view = views[viewKey] ?: viewDefaults
    fun setView(next: ViewPrefs) = graph.views.set(viewKey, next)
    val sorted = remember(entries, view.sort) { entries?.sortedBy(view.sort) }

    var selection by remember(selectionKey) { mutableStateOf(emptySet<String>()) }
    var sheetFor by remember { mutableStateOf<FileEntry?>(null) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var info by remember { mutableStateOf<FileEntry?>(null) }
    var extracting by remember { mutableStateOf<FileEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }

    // Drop selected paths that no longer exist after a change.
    LaunchedEffect(entries) {
        if (entries != null && selection.isNotEmpty()) {
            val present = entries.map { it.path }.toSet()
            selection = selection.filter { it in present }.toSet()
        }
    }
    BackHandler(enabled = active && selection.isNotEmpty()) { selection = emptySet() }

    fun toggle(entry: FileEntry) {
        if (pickForVault && entry.isDirectory) return
        selection = if (entry.path in selection) selection - entry.path else selection + entry.path
    }

    fun open(entry: FileEntry) {
        when {
            entry.isDirectory -> onOpenFolder(entry.file)
            entry.kind == FileKind.IMAGE -> {
                graph.viewerPaths = sorted.orEmpty().filter { it.kind == FileKind.IMAGE }.map { it.path }
                nav.navigate(Routes.image(entry.path))
            }
            entry.extension == "zip" -> extracting = entry
            !Opener.open(context, entry.file) -> actions.notify("No app on your phone can open .${entry.extension} files")
        }
    }

    fun hide(paths: Collection<String>) {
        if (graph.vault.unlocked.value) {
            actions.moveToVault(paths)
        } else {
            actions.pendingVault.value = paths.toList()
            nav.navigate(Routes.VAULT)
        }
    }

    fun clip(mode: ClipMode, paths: Collection<String>) {
        actions.setClip(mode, paths)
        selection = emptySet()
        if (currentDir == null) home.showTab(nav, Tabs.STORAGE)
    }

    val selectedEntries = entries.orEmpty().filter { it.path in selection }
    val inSelection = selection.isNotEmpty() && !pickForVault

    Scaffold(
        modifier = modifier,
        topBar = {
            if (inSelection) {
                SelectionBar(
                    count = selection.size,
                    allFiles = selectedEntries.isNotEmpty() && selectedEntries.none { it.isDirectory },
                    allFavorite = selectedEntries.isNotEmpty() && selectedEntries.all { it.path in favorites },
                    onClose = { selection = emptySet() },
                    onSelectAll = { selection = entries.orEmpty().map { it.path }.toSet() },
                    onShare = { Opener.share(context, selectedEntries.map { it.file }) },
                    onTrash = {
                        actions.moveToTrash(selection)
                        selection = emptySet()
                    },
                    onCopy = { clip(ClipMode.COPY, selection) },
                    onMove = { clip(ClipMode.MOVE, selection) },
                    onCompress = {
                        actions.compress(selection, currentDir ?: selectedEntries.first().file.parentFile!!)
                        selection = emptySet()
                    },
                    onFavorite = { graph.favorites.toggle(selection) },
                    onHide = {
                        hide(selection)
                        selection = emptySet()
                    },
                )
            } else {
                var sortMenu by remember { mutableStateOf(false) }
                var moreMenu by remember { mutableStateOf(false) }
                TopAppBar(
                    title = { Text(if (pickForVault) "Choose files to hide" else title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        } else if (onMenu != null) {
                            IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Open menu") }
                        }
                    },
                    actions = {
                        if (!pickForVault && showSearchAction) {
                            IconButton(onClick = { nav.navigate(Routes.SEARCH) }) { Icon(Icons.Outlined.Search, "Search") }
                        }
                        IconButton(onClick = {
                            setView(view.copy(level = if (view.level.isGrid) ViewLevel.LIST else ViewLevel.GRID_3))
                        }) {
                            if (!view.level.isGrid) {
                                Icon(Icons.Outlined.GridView, "Show as grid")
                            } else {
                                Icon(Icons.AutoMirrored.Outlined.List, "Show as list")
                            }
                        }
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                        SortMenu(sortMenu, view.sort, onDismiss = { sortMenu = false }, onChange = { setView(view.copy(sort = it)) })
                        if (currentDir != null && !pickForVault) {
                            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
                            DropdownMenu(moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("New folder") },
                                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                    onClick = {
                                        moreMenu = false
                                        newFolder = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (settings.showHidden) "Hide hidden files" else "Show hidden files") },
                                    onClick = {
                                        moreMenu = false
                                        graph.settings.update { it.copy(showHidden = !it.showHidden) }
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        },
        bottomBar = {
            val c = clip
            when {
                pickForVault -> ActionBar(
                    text = if (selection.isEmpty()) "Tap files to select them" else "${itemCount(selection.size).replace("item", "file")} selected",
                    confirm = "Hide",
                    enabled = selection.isNotEmpty(),
                    onCancel = { nav.popBackStack() },
                    onConfirm = {
                        if (graph.vault.unlocked.value) {
                            actions.moveToVault(selection) { nav.popBackStack(Routes.VAULT, inclusive = false) }
                        } else {
                            // Filo was left meanwhile and the folder locked itself: unlock first, then hide.
                            actions.pendingVault.value = selection.toList()
                            nav.popBackStack(Routes.VAULT, inclusive = false)
                        }
                        selection = emptySet()
                    },
                )
                c != null && currentDir != null -> ActionBar(
                    text = "${itemCount(c.paths.size)} to ${if (c.mode == ClipMode.COPY) "copy" else "move"}",
                    confirm = if (c.mode == ClipMode.COPY) "Paste" else "Move here",
                    enabled = true,
                    onCancel = { actions.clearClip() },
                    onConfirm = { actions.paste(currentDir) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            topContent?.invoke()
            val list = sorted
            when {
                list == null -> Loading()
                list.isEmpty() -> EmptyState(Icons.Outlined.FolderOpen, emptyTitle, emptyBody)
                else -> key(selectionKey) {
                    FileListPane(
                        entries = list,
                        level = view.level,
                        selection = selection,
                        favorites = favorites,
                        onOpen = ::open,
                        onSelectionChange = { selection = it },
                        onMore = if (pickForVault) null else { entry -> sheetFor = entry },
                        onLevelChange = { setView(view.copy(level = it)) },
                        selectable = { !(pickForVault && it.isDirectory) },
                        tapSelects = pickForVault,
                        highlight = highlight,
                    )
                }
            }
        }
    }

    sheetFor?.let { entry ->
        FileActionSheet(
            entry = entry,
            favorite = entry.path in favorites,
            onDismiss = { sheetFor = null },
            onOpenWith = { if (!Opener.open(context, entry.file, chooser = true)) actions.notify("No app can open this file") },
            onShare = { Opener.share(context, listOf(entry.file)) },
            onCopy = { clip(ClipMode.COPY, listOf(entry.path)) },
            onMove = { clip(ClipMode.MOVE, listOf(entry.path)) },
            onRename = { renaming = entry },
            onFavorite = { graph.favorites.toggle(listOf(entry.path)) },
            onCompress = { actions.compress(listOf(entry.path), entry.file.parentFile!!) },
            onExtract = { actions.extract(entry.path) },
            onHide = { hide(listOf(entry.path)) },
            onInfo = { info = entry },
            onTrash = { actions.moveToTrash(listOf(entry.path)) },
        )
    }

    renaming?.let { entry ->
        NameDialog(
            title = "Rename",
            initial = entry.name,
            selectBaseName = !entry.isDirectory,
            confirm = "Rename",
            exists = { name -> !name.equals(entry.name, ignoreCase = true) && File(entry.file.parentFile, name).exists() },
            onDismiss = { renaming = null },
            onConfirm = { actions.rename(entry.path, it) },
        )
    }

    if (newFolder && currentDir != null) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirm = "Create",
            exists = { File(currentDir, it).exists() },
            onDismiss = { newFolder = false },
            onConfirm = { actions.createFolder(currentDir, it) },
        )
    }

    info?.let { FileInfoDialog(it) { info = null } }

    extracting?.let { entry ->
        AlertDialog(
            onDismissRequest = { extracting = null },
            title = { Text(entry.name) },
            text = { Text("Extract this archive into a new folder next to it?") },
            confirmButton = {
                Button(onClick = {
                    extracting = null
                    actions.extract(entry.path)
                }) { Text("Extract") }
            },
            dismissButton = {
                TextButton(onClick = {
                    extracting = null
                    Opener.open(context, entry.file, chooser = true)
                }) { Text("Open with") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    allFiles: Boolean,
    allFavorite: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onCompress: () -> Unit,
    onFavorite: () -> Unit,
    onHide: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Clear selection") } },
        title = { Text("$count selected") },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, "Select all") }
            if (allFiles) IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Share") }
            IconButton(onClick = onTrash) { Icon(Icons.Outlined.Delete, "Move to trash") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More actions") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                fun item(text: String, icon: ImageVector, action: () -> Unit) = Triple(text, icon, action)
                listOfNotNull(
                    item("Copy to…", Icons.Outlined.ContentCopy, onCopy),
                    @Suppress("DEPRECATION") item("Move to…", Icons.Outlined.DriveFileMove, onMove),
                    item("Compress to zip", Icons.Outlined.FolderZip, onCompress),
                    item(if (allFavorite) "Remove from favorites" else "Add to favorites", Icons.Outlined.StarBorder, onFavorite),
                    if (allFiles) item("Move to hidden folder", Icons.Outlined.Lock, onHide) else null,
                ).forEach { (text, icon, action) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        leadingIcon = { Icon(icon, null) },
                        onClick = {
                            menu = false
                            action()
                        },
                    )
                }
            }
        },
    )
}

@Composable
fun SortMenu(expanded: Boolean, sort: SortOrder, onDismiss: () -> Unit, onChange: (SortOrder) -> Unit) {
    DropdownMenu(expanded, onDismissRequest = onDismiss) {
        SortField.entries.forEach { field ->
            DropdownMenuItem(
                text = { Text(field.label) },
                leadingIcon = { if (sort.field == field) Icon(Icons.Outlined.Check, "Current sort") else Spacer(Modifier.width(24.dp)) },
                onClick = {
                    onDismiss()
                    // Tapping the active field flips the direction.
                    onChange(if (sort.field == field) sort.copy(ascending = !sort.ascending) else SortOrder(field, field == SortField.NAME || field == SortField.TYPE))
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (sort.ascending) "Ascending" else "Descending") },
            onClick = {
                onDismiss()
                onChange(sort.copy(ascending = !sort.ascending))
            },
        )
    }
}

@Composable
private fun ActionBar(text: String, confirm: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onConfirm, enabled = enabled) { Text(confirm) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionSheet(
    entry: FileEntry,
    favorite: Boolean,
    onDismiss: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onFavorite: () -> Unit,
    onCompress: () -> Unit,
    onExtract: () -> Unit,
    onHide: () -> Unit,
    onInfo: () -> Unit,
    onTrash: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            ListItem(
                leadingContent = { FileThumb(entry, 44.dp) },
                headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(entry.subtitle()) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            @Composable
            fun action(text: String, icon: ImageVector, run: () -> Unit) {
                ListItem(
                    leadingContent = { Icon(icon, null) },
                    headlineContent = { Text(text) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickableRow {
                        // Let the sheet slide away first, then act (avoids two windows fighting over focus).
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onDismiss()
                            run()
                        }
                    },
                )
            }
            if (!entry.isDirectory) {
                action("Open with", Icons.AutoMirrored.Outlined.OpenInNew, onOpenWith)
                action("Share", Icons.Outlined.Share, onShare)
            }
            action("Copy to…", Icons.Outlined.ContentCopy, onCopy)
            @Suppress("DEPRECATION")
            action("Move to…", Icons.Outlined.DriveFileMove, onMove)
            action("Rename", Icons.Outlined.Edit, onRename)
            action(if (favorite) "Remove from favorites" else "Add to favorites", if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, onFavorite)
            if (entry.extension == "zip") action("Extract here", Icons.Outlined.Unarchive, onExtract) else action("Compress to zip", Icons.Outlined.FolderZip, onCompress)
            if (!entry.isDirectory) action("Move to hidden folder", Icons.Outlined.Lock, onHide)
            action("File info", Icons.Outlined.Info, onInfo)
            action("Move to trash", Icons.Outlined.Delete, onTrash)
        }
    }
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    exists: (String) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    selectBaseName: Boolean = false,
) {
    val end = if (selectBaseName) initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    val name = value.text
    val error = when {
        name.isEmpty() -> null
        !FileOps.isValidName(name) -> "This name isn't allowed"
        name != initial && exists(name) -> "That name is already taken"
        else -> null
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                label = { Text("Name") },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            Button(
                enabled = name.isNotEmpty() && error == null && name != initial,
                onClick = {
                    onDismiss()
                    onConfirm(name)
                },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FileInfoDialog(entry: FileEntry, onDismiss: () -> Unit) {
    val size by produceState<Long?>(if (entry.isDirectory) null else entry.size, entry.path) {
        if (entry.isDirectory) value = withContext(Dispatchers.IO) { FileOps.totalSize(entry.file) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File info") },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoLine("Name", entry.name)
                    InfoLine("Location", entry.file.parent ?: "")
                    InfoLine("Size", size?.let { "${formatSize(it)} (${"%,d".format(it)} bytes)" } ?: "Calculating…")
                    InfoLine("Modified", formatDate(entry.lastModified))
                    InfoLine("Type", if (entry.isDirectory) "Folder" else MimeTypes.of(entry.name))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ColumnScope.InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
