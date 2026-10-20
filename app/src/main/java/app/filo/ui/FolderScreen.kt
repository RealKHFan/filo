package app.filo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.filo.Graph
import app.filo.data.FileEntry
import app.filo.data.FileOps
import app.filo.data.ViewPrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FolderViewModel(private val graph: Graph, start: File) : ViewModel() {
    private val history = ArrayDeque<File>()

    private val _dir = MutableStateFlow(start)
    val dir: StateFlow<File> = _dir.asStateFlow()

    private val _entries = MutableStateFlow<List<FileEntry>?>(null)
    val entries: StateFlow<List<FileEntry>?> = _entries.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    init {
        viewModelScope.launch {
            var shown: File? = null
            combine(_dir, graph.settings.state, graph.actions.changes) { d, s, _ -> d to s }.collectLatest { (dir, s) ->
                if (dir != shown) _entries.value = null
                // If this folder was moved or deleted, step up to the nearest one that still exists.
                var target = dir
                while (!target.isDirectory && target.parentFile != null && target != graph.storageRoot) target = target.parentFile!!
                if (target != dir) {
                    _dir.value = target
                    return@collectLatest
                }
                _entries.value = withContext(Dispatchers.IO) { FileOps.list(dir, s.showHidden) }
                shown = dir
            }
        }
    }

    fun open(dir: File) {
        if (dir.absolutePath == _dir.value.absolutePath) return
        history.addLast(_dir.value)
        _dir.value = dir
        _canGoBack.value = true
    }

    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        _dir.value = previous
        _canGoBack.value = history.isNotEmpty()
        return true
    }
}

/** Storage tab and any folder opened from elsewhere. Back goes to the previous folder. */
@Composable
fun FolderScreen(
    nav: NavController,
    start: File,
    isTab: Boolean,
    pickForVault: Boolean = false,
    active: Boolean = true,
    highlight: String? = null,
) {
    val graph = LocalGraph.current
    val home = LocalHome.current
    val vm: FolderViewModel = viewModel(key = "folder:${start.absolutePath}:$pickForVault") { FolderViewModel(graph, start) }
    val dir by vm.dir.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val canGoBack by vm.canGoBack.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val volumes = androidx.compose.runtime.remember { graph.volumes() }
    val volume = volumes.firstOrNull { FileOps.isSameOrInside(dir, it.root) } ?: volumes.first()

    BackHandler(enabled = active && canGoBack) { vm.back() }

    val title = if (dir.absolutePath == volume.root.absolutePath) volume.label else dir.name
    FileCollectionScreen(
        title = title,
        entries = entries,
        nav = nav,
        onBack = when {
            canGoBack -> { { vm.back() } }
            !isTab -> { { nav.popBackStack() } }
            else -> null
        },
        onOpenFolder = vm::open,
        viewKey = ViewPrefsStore.folderKey(dir),
        viewDefaults = ViewPrefsStore.defaultsForFolder(dir, graph.storageRoot),
        currentDir = dir,
        selectionKey = dir.absolutePath,
        pickForVault = pickForVault,
        emptyTitle = "This folder is empty",
        emptyBody = if (settings.showHidden) "There are no files here." else "There are no files here. Hidden files are not shown.",
        topContent = {
            Breadcrumbs(
                volume.label,
                volume.root,
                dir,
                onOpen = vm::open,
                drop = if (pickForVault) null else CrumbDrop(STORAGE_SPACE, { it.absolutePath }) { target, paths -> graph.actions.moveInto(paths, target) },
            )
        },
        onMenu = if (isTab) home::openMenu else null,
        active = active,
        highlight = highlight?.takeIf { File(it).parentFile == dir },
    )
}

/** Where files dragged onto the path bar go. [keyOf] turns a part of the path into the same key as DragSession.origin. */
class CrumbDrop(
    val space: String,
    val keyOf: (File) -> String,
    val onDropInto: (dir: File, paths: Set<String>) -> Unit,
)

/** "Internal storage › Download › Music": tap any part to jump there. */
@Composable
fun Breadcrumbs(
    rootLabel: String,
    root: File,
    dir: File,
    onOpen: (File) -> Unit,
    /** Files dragged onto a part of the path move into that folder; holding there opens it. */
    drop: CrumbDrop? = null,
) {
    val fileDrag = LocalFileDrag.current
    val parts = buildList {
        add(rootLabel to root)
        val relative = dir.absolutePath.removePrefix(root.absolutePath).trim('/')
        var current = root
        if (relative.isNotEmpty()) {
            relative.split('/').forEach {
                current = File(current, it)
                add(it to current)
            }
        }
    }
    val state = rememberLazyListState()
    LaunchedEffect(parts.size, dir) { state.scrollToItem((parts.size - 1).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Folder path: " + parts.joinToString(", ") { it.first } },
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(parts) { index, (label, file) ->
            val last = index == parts.lastIndex
            val key = "crumb:" + file.absolutePath
            val hovered = fileDrag.hovered?.key == key
            val target = if (drop == null) {
                Modifier
            } else {
                Modifier.dropZone(drop.space) { _, session ->
                    // The folder they're already in: holding there does nothing, letting go cancels.
                    if (session.origin == drop.keyOf(file)) {
                        null
                    } else {
                        DropSpot(key, label, onDrop = { paths -> drop.onDropInto(file, paths) }, onOpen = { onOpen(file) })
                    }
                }
            }
            AssistChip(
                onClick = { onOpen(file) },
                label = { Text(label, maxLines = 1) },
                border = null,
                modifier = target,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = when {
                        hovered -> MaterialTheme.colorScheme.tertiaryContainer
                        last -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    },
                    labelColor = when {
                        hovered -> MaterialTheme.colorScheme.onTertiaryContainer
                        last -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            )
            if (!last) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp).size(18.dp),
                )
            }
        }
    }
}
