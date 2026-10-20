package app.filo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filo.data.FileEntry
import app.filo.data.FileKind
import app.filo.data.ViewLevel
import kotlinx.coroutines.delay

/**
 * One file or folder as a row.
 * [longPressFromParent]: the surrounding list handles long-press (drag to select); the row only
 * offers it to screen readers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onMore: (() -> Unit)?,
    subtitle: String? = null,
    large: Boolean = false,
    onThumbClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    longPressFromParent: Boolean = false,
    /** Always show a checkbox (with ⋮ next to it when [onMore] is set). */
    selectedMark: Boolean = false,
    /** Being dragged somewhere else right now: shown faded. */
    lifted: Boolean = false,
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        highlighted -> MaterialTheme.colorScheme.tertiaryContainer
        else -> Color.Transparent
    }
    val press = if (longPressFromParent || onLongClick == null) {
        Modifier
            .clickable(onClick = onClick)
            .semantics { if (onLongClick != null) onLongClick("Select") { onLongClick(); true } }
    } else {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
    }
    val thumb = if (large) 64.dp else 44.dp
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (lifted) 0.4f else 1f)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .then(press)
            .padding(start = 8.dp, top = if (large) 12.dp else 8.dp, bottom = if (large) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbModifier = if (onThumbClick != null) {
            Modifier.clip(RoundedCornerShape(thumb * 0.28f)).clickable(onClickLabel = "Preview", onClick = onThumbClick)
        } else {
            Modifier
        }
        FileThumb(entry, thumb, thumbModifier)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (favorite) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.Star, "Favorite", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                subtitle ?: entry.subtitle(),
                style = if (large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (selectedMark) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            if (onMore != null) {
                IconButton(onClick = onMore) { Icon(Icons.Outlined.MoreVert, contentDescription = "More options for ${entry.name}") }
            }
            return@Row
        }
        when {
            selectionMode -> Checkbox(checked = selected, onCheckedChange = { onClick() })
            onMore != null -> IconButton(onClick = onMore) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More options for ${entry.name}")
            }
            else -> Spacer(Modifier.width(16.dp))
        }
    }
}

@Composable
fun FileGridCell(
    entry: FileEntry,
    selected: Boolean,
    favorite: Boolean,
    columns: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    highlighted: Boolean = false,
    lifted: Boolean = false,
) {
    val tiny = columns >= 5
    val compact = columns >= 4
    Column(
        Modifier
            .alpha(if (lifted) 0.4f else 1f)
            .padding(if (compact) 2.dp else 4.dp)
            .clip(RoundedCornerShape(if (compact) 10.dp else 16.dp))
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.secondaryContainer
                    highlighted -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${entry.name}, ${entry.subtitle()}"
                onLongClick("Select") { onLongClick(); true }
            }
            .padding(if (compact) 3.dp else 6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (entry.kind == FileKind.IMAGE || entry.kind == FileKind.VIDEO) {
                FileThumb(entry, 180.dp, Modifier.fillMaxSize(), corner = if (compact) 10.dp else 14.dp)
            } else {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(if (compact) 10.dp else 16.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) { FileThumb(entry, if (compact) 36.dp else 56.dp) }
            }
            if (selected) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(if (compact) 18.dp else 24.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
            if (favorite && !tiny) {
                Icon(
                    Icons.Outlined.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp),
                )
            }
        }
        if (!tiny) {
            Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
            Text(
                entry.name,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (!compact) {
                Text(
                    if (entry.isDirectory) itemCount(entry.childCount) else formatSize(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Lets selected files be dragged out of a list and dropped onto folders.
 * [space] keeps storage and the hidden folder apart, [here] identifies the open folder,
 * [onDropInto] gets the folder dropped on, or null for the open folder itself.
 */
class PaneDrop(
    val space: String,
    val here: String,
    val hereLabel: String,
    val onDropInto: (target: FileEntry?, paths: Set<String>) -> Unit,
)

/**
 * A list or grid of files.
 * - Tap opens (or toggles while selecting); long-press and drag selects a range.
 * - Pinch changes the size ([onLevelChange]).
 * - [highlight] scrolls to that file and lights it up for a moment ("Show in folder").
 */
@Composable
fun FileListPane(
    entries: List<FileEntry>,
    level: ViewLevel,
    selection: Set<String>,
    favorites: Set<String>,
    onOpen: (FileEntry) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onMore: ((FileEntry) -> Unit)?,
    onLevelChange: ((ViewLevel) -> Unit)?,
    modifier: Modifier = Modifier,
    selectable: (FileEntry) -> Boolean = { true },
    /** A tap selects files instead of opening them (picking files). */
    tapSelects: Boolean = false,
    highlight: String? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
    /** Selected files can be dragged onto folders (see DragDrop.kt). */
    drop: PaneDrop? = null,
) {
    val drag = remember { DragSelect() }
    val fileDrag = LocalFileDrag.current
    val thumbs = LocalThumbs.current
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var paneCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val hoveredKey = fileDrag.hovered?.key
    val dragging = fileDrag.session?.paths.orEmpty()
    val keys = remember(entries, selectable) { entries.filter(selectable).map { it.path } }
    var flash by remember(highlight) { mutableStateOf(highlight) }

    LaunchedEffect(highlight, entries.isNotEmpty(), level.isGrid) {
        val index = entries.indexOfFirst { it.path == highlight }
        if (index < 0) return@LaunchedEffect
        if (level.isGrid) gridState.scrollToItem(index) else listState.scrollToItem(index)
        delay(2500)
        flash = null
    }

    fun toggle(entry: FileEntry) {
        if (!selectable(entry)) return
        onSelectionChange(if (entry.path in selection) selection - entry.path else selection + entry.path)
    }

    fun tap(entry: FileEntry) {
        if (drag.consumeClick()) return
        when {
            selection.isNotEmpty() && selectable(entry) -> toggle(entry)
            // Folders still open with a tap; long-press selects them.
            tapSelects && selectable(entry) && !entry.isDirectory -> toggle(entry)
            else -> onOpen(entry)
        }
    }

    fun gridItemAt(p: Offset): String? = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
        p.x >= it.offset.x && p.x < it.offset.x + it.size.width && p.y >= it.offset.y && p.y < it.offset.y + it.size.height
    }?.key as? String

    fun listItemAt(p: Offset): String? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { p.y >= it.offset && p.y < it.offset + it.size }?.key as? String

    val byPath = remember(entries) { entries.associateBy { it.path } }
    val startMove: ((String, Offset) -> Boolean)? = drop?.let { d ->
        { key, position ->
            val entry = byPath[key]
            val coordinates = paneCoordinates
            if (entry == null || coordinates == null || !coordinates.isAttached) {
                false
            } else {
                fileDrag.start(DragSession(d.space, d.here, selection, entry, thumbs), coordinates.localToRoot(position))
                true
            }
        }
    }
    val dropZone = if (drop == null) {
        Modifier
    } else {
        val edge = with(LocalDensity.current) { 64.dp.toPx() }
        Modifier.dropZone(
            space = drop.space,
            scroll = { local, height ->
                val speed = when {
                    local.y < edge -> -(edge - local.y) / edge * 24f
                    local.y > height - edge -> (local.y - (height - edge)) / edge * 24f
                    else -> 0f
                }
                val state: androidx.compose.foundation.gestures.ScrollableState = if (level.isGrid) gridState else listState
                val canMove = (speed < 0 && state.canScrollBackward) || (speed > 0 && state.canScrollForward)
                if (canMove) state.scrollBy(speed)
                canMove
            },
        ) { local, session ->
            val entry = (if (level.isGrid) gridItemAt(local) else listItemAt(local))?.let { byPath[it] }
            when {
                entry != null && entry.isDirectory && entry.path !in session.paths -> DropSpot(
                    key = "folder:" + entry.path,
                    label = entry.name,
                    onDrop = { paths -> drop.onDropInto(entry, paths) },
                    onOpen = { onOpen(entry) },
                )
                // Empty space (or a file): into the folder that's open, unless that's where they came from.
                session.origin != drop.here -> DropSpot("here:" + drop.here, drop.hereLabel, { paths -> drop.onDropInto(null, paths) })
                else -> null
            }
        }
    }

    val resize = if (onLevelChange != null) Modifier.pinchToResize(level, onLevelChange) else Modifier
    Box(modifier.fillMaxSize().then(resize).onGloballyPositioned { paneCoordinates = it }.then(dropZone)) {
        if (level.isGrid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(level.columns),
                state = gridState,
                modifier = Modifier.fillMaxSize().dragToSelect(
                    state = drag,
                    keys = keys,
                    selection = selection,
                    onSelectionChange = onSelectionChange,
                    itemAt = ::gridItemAt,
                    scrollBy = { gridState.scrollBy(it) },
                    onStartMove = startMove,
                ),
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = contentPadding.calculateBottomPadding()),
            ) {
                items(entries, key = { it.path }) { entry ->
                    FileGridCell(
                        entry,
                        selected = entry.path in selection,
                        favorite = entry.path in favorites,
                        columns = level.columns,
                        onClick = { tap(entry) },
                        onLongClick = { toggle(entry) },
                        highlighted = entry.path == flash || hoveredKey == "folder:" + entry.path,
                        lifted = entry.path in dragging,
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().dragToSelect(
                    state = drag,
                    keys = keys,
                    selection = selection,
                    onSelectionChange = onSelectionChange,
                    itemAt = ::listItemAt,
                    scrollBy = { listState.scrollBy(it) },
                    onStartMove = startMove,
                ),
                contentPadding = contentPadding,
            ) {
                items(entries, key = { it.path }) { entry ->
                    FileRow(
                        entry,
                        selected = entry.path in selection,
                        selectionMode = selection.isNotEmpty(),
                        favorite = entry.path in favorites,
                        onClick = { tap(entry) },
                        onLongClick = { toggle(entry) },
                        onMore = onMore?.let { { it(entry) } },
                        large = level == ViewLevel.LIST_LARGE,
                        highlighted = entry.path == flash || hoveredKey == "folder:" + entry.path,
                        longPressFromParent = true,
                        lifted = entry.path in dragging,
                    )
                }
            }
        }
    }
}
