package app.filo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.filo.data.FileEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Drag and drop for selected files:
 * long-press a selected file and drag. Let go over a folder or a part of the path bar to move the
 * files there. Hold still over one for [HOVER_OPEN_MILLIS] and it opens, so you can keep going.
 * Letting go over empty space moves them into the folder that is open.
 */
const val HOVER_OPEN_MILLIS = 2_000L

const val STORAGE_SPACE = "storage"
const val VAULT_SPACE = "vault"

/** What is being dragged. [space] keeps storage files and hidden files apart; [origin] is where they came from. */
class DragSession(
    val space: String,
    val origin: String,
    val paths: Set<String>,
    val preview: FileEntry,
    val thumbs: ThumbProvider?,
)

/** A place to let go: [label] is shown on the dragged badge ("Move to Music"). */
class DropSpot(
    val key: String,
    val label: String,
    val onDrop: (Set<String>) -> Unit,
    val onOpen: (() -> Unit)? = null,
)

/** A part of the screen that takes drops: a file list, or one part of the path bar. */
class DropZone(val space: String) {
    internal var coordinates: LayoutCoordinates? = null
    internal var spotAt: (local: Offset, session: DragSession) -> DropSpot? = { _, _ -> null }
    /** Scrolls if the finger is near an edge; false when there's nothing to scroll. */
    internal var scroll: (suspend (local: Offset, height: Float) -> Boolean)? = null

    internal fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInRoot()
    internal fun toLocal(root: Offset): Offset = root - (coordinates?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero)
}

@Stable
class FileDrag {
    var session by mutableStateOf<DragSession?>(null)
        private set
    var position by mutableStateOf(Offset.Zero)
        private set
    var hovered by mutableStateOf<DropSpot?>(null)
        private set

    private val zones = mutableListOf<DropZone>()
    internal var hoverOpened = false

    val active: Boolean get() = session != null

    /** [at] is in root coordinates. */
    fun start(session: DragSession, at: Offset) {
        this.session = session
        position = at
        hovered = null
        refresh()
    }

    internal fun register(zone: DropZone) {
        zones += zone
    }

    internal fun unregister(zone: DropZone) {
        zones -= zone
    }

    internal fun moveTo(at: Offset) {
        position = at
        refresh()
    }

    /** Works out what's under the finger again (lists scroll and folders open while you hold still). */
    internal fun refresh() {
        val s = session ?: return
        val spot = zoneUnderFinger()?.let { it.spotAt(it.toLocal(position), s) }
        if (spot?.key != hovered?.key) hoverOpened = false
        hovered = spot
    }

    internal fun zoneUnderFinger(): DropZone? {
        val s = session ?: return null
        // The smallest one wins: a folder's path bar part or list sits inside bigger areas.
        return zones.filter { it.space == s.space && it.bounds()?.contains(position) == true }
            .minByOrNull { z -> z.bounds()?.let { it.width * it.height } ?: Float.MAX_VALUE }
    }

    internal fun drop() {
        val s = session
        val spot = hovered
        cancel()
        if (s != null && spot != null) spot.onDrop(s.paths)
    }

    fun cancel() {
        session = null
        hovered = null
        hoverOpened = false
    }
}

val LocalFileDrag = staticCompositionLocalOf { FileDrag() }

/** Empty space (like an empty folder) that takes drops into the folder that's open. */
@Composable
fun Modifier.dropHere(drop: PaneDrop?): Modifier =
    if (drop == null) {
        this
    } else {
        dropZone(drop.space) { _, session ->
            if (session.origin == drop.here) null else DropSpot("here:" + drop.here, drop.hereLabel, { paths -> drop.onDropInto(null, paths) })
        }
    }

/** Makes this element take drops. [spotAt] gets positions relative to the element. */
@Composable
fun Modifier.dropZone(
    space: String,
    scroll: (suspend (local: Offset, height: Float) -> Boolean)? = null,
    spotAt: (local: Offset, session: DragSession) -> DropSpot?,
): Modifier {
    val drag = LocalFileDrag.current
    val zone = remember(space) { DropZone(space) }
    val currentSpotAt by rememberUpdatedState(spotAt)
    val currentScroll by rememberUpdatedState(scroll)
    zone.spotAt = { local, session -> currentSpotAt(local, session) }
    zone.scroll = if (scroll == null) null else { local, height -> currentScroll?.invoke(local, height) == true }
    DisposableEffect(drag, zone) {
        drag.register(zone)
        onDispose { drag.unregister(zone) }
    }
    return onGloballyPositioned {
        zone.coordinates = it
        if (drag.active) drag.refresh() // new content appeared under a finger that holds still
    }
}

/**
 * Wraps the whole app. Once a drag has started, this follows the finger everywhere, even while
 * the folder underneath changes, and draws the badge with what you're carrying.
 */
@Composable
fun FileDragHost(drag: FileDrag, content: @Composable BoxScope.() -> Unit) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var host by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val session = drag.session

    // Holding still over a folder (or part of the path bar) opens it.
    val hoveredKey = drag.hovered?.key
    LaunchedEffect(session, hoveredKey) {
        val spot = drag.hovered ?: return@LaunchedEffect
        val open = spot.onOpen ?: return@LaunchedEffect
        if (session == null || drag.hoverOpened) return@LaunchedEffect
        delay(HOVER_OPEN_MILLIS)
        if (drag.session !== session || drag.hovered?.key != spot.key) return@LaunchedEffect
        drag.hoverOpened = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        open()
        // Let the folder that opened show up, then see what's under the finger now.
        repeat(2) { withFrameNanos { } }
        drag.refresh()
    }

    // Near the top or bottom edge of a list, it scrolls by itself.
    LaunchedEffect(session) {
        if (session == null) return@LaunchedEffect
        snapshotFlow { drag.position }.collectLatest {
            while (isActive) {
                val zone = drag.zoneUnderFinger() ?: break
                val scroll = zone.scroll ?: break
                val height = zone.coordinates?.takeIf { c -> c.isAttached }?.size?.height?.toFloat() ?: break
                if (!scroll(zone.toLocal(drag.position), height)) break
                drag.refresh()
                withFrameNanos { }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { host = it }
            .pointerInput(drag) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!drag.active) continue
                        val change = event.changes.firstOrNull { it.pressed } ?: event.changes.firstOrNull() ?: continue
                        // An "up" that arrives already consumed is Android cancelling the touch.
                        val cancelled = !change.pressed && change.isConsumed
                        event.changes.forEach { it.consume() } // nothing underneath scrolls or clicks meanwhile
                        val at = host?.takeIf { it.isAttached }?.localToRoot(change.position) ?: change.position
                        when {
                            change.pressed -> drag.moveTo(at)
                            cancelled -> drag.cancel()
                            else -> drag.drop()
                        }
                    }
                }
            },
    ) {
        content()
        if (session != null) {
            val origin = host?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
            val p = drag.position - origin
            val hovered = drag.hovered
            CompositionLocalProvider(LocalThumbs provides session.thumbs) {
                Row(
                    Modifier
                        .offset {
                            with(density) {
                                IntOffset((p.x - 32.dp.toPx()).roundToInt().coerceAtLeast(0), (p.y - 100.dp.toPx()).roundToInt().coerceAtLeast(0))
                            }
                        }
                        .widthIn(max = 260.dp)
                        .shadow(10.dp, RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        FileThumb(session.preview, 44.dp)
                        if (session.paths.size > 1) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-8).dp)
                                    .size(22.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    session.paths.size.coerceAtMost(99).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.padding(end = 8.dp)) {
                        Text(
                            if (session.paths.size == 1) session.preview.name else itemCount(session.paths.size),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        Text(
                            if (hovered != null) "Move to ${hovered.label}" else "Drag onto a folder",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hovered != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
