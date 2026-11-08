package app.filo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The handle on the right edge for long lists and grids: it shows up while the list moves, and dragging
 * it jumps through the list. [bottom] keeps it clear of buttons at the bottom of the screen.
 */
@Composable
fun BoxScope.FastScroller(state: ScrollableState, bottom: Dp = 0.dp) {
    // Only for lists of more than two screens; shorter ones are quick to scroll anyway.
    val long by remember(state) { derivedStateOf { position(state)?.let { it.total > it.visible * 2 } == true } }
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val moving = state.isScrollInProgress
    LaunchedEffect(moving, dragging) {
        if (moving || dragging) {
            shown = true
        } else {
            delay(1500)
            shown = false
        }
    }

    var trackHeight by remember { mutableIntStateOf(0) }
    val thumbHeight = with(LocalDensity.current) { 56.dp.roundToPx() }
    val range = (trackHeight - thumbHeight).coerceAtLeast(1).toFloat()
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragStart by remember { mutableStateOf<Position?>(null) }
    val scope = rememberCoroutineScope()
    val color = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

    Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(bottom = bottom).width(48.dp).onSizeChanged { trackHeight = it.height }) {
        AnimatedVisibility(shown && long, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .offset { IntOffset(0, (if (dragging) dragY else (position(state)?.fraction ?: 0f) * range).roundToInt()) }
                    .size(48.dp, 56.dp)
                    .clearAndSetSemantics { testTag = "fast_scroll" } // TalkBack scrolls with its own gestures
                    .draggable(
                        rememberDraggableState { delta ->
                            dragY = (dragY + delta).coerceIn(0f, range)
                            val start = dragStart ?: return@rememberDraggableState
                            scope.launch { scrollTo(state, start, dragY / range) }
                        },
                        Orientation.Vertical,
                        startDragImmediately = true,
                        onDragStarted = {
                            dragStart = position(state)
                            dragY = (dragStart?.fraction ?: 0f) * range
                            dragging = true
                        },
                        onDragStopped = { dragging = false },
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.padding(end = 4.dp).size(if (dragging) 10.dp else 8.dp, 48.dp).background(color, CircleShape))
            }
        }
    }
}

/**
 * Where a list or grid is, counted in items. [first] is the first visible item plus the part of it
 * scrolled past; a grid row holds [span] items of [rowSize] pixels.
 */
internal class Position(val total: Int, val visible: Int, val span: Int, val first: Float, val rowSize: Int, val atEnd: Boolean) {
    private val lastFirst get() = (total - visible).coerceAtLeast(1)
    val fraction: Float get() = if (atEnd) 1f else (first / lastFirst).coerceIn(0f, 1f)

    /** The item to scroll to for [fraction] of the way down, and how far into its row. */
    fun target(fraction: Float): Pair<Int, Int> {
        if (fraction >= 1f) return total - 1 to 0 // the list stops at its real end
        val rows = fraction * lastFirst / span
        val row = floor(rows)
        return (row * span).toInt().coerceIn(0, (total - 1).coerceAtLeast(0)) to ((rows - row) * rowSize).toInt()
    }
}

internal fun position(state: ScrollableState): Position? = when (state) {
    is LazyListState -> state.layoutInfo.let { info ->
        val item = info.visibleItemsInfo.firstOrNull() ?: return null
        val size = item.size.coerceAtLeast(1)
        Position(info.totalItemsCount, info.visibleItemsInfo.size, 1, state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset.toFloat() / size, size, !state.canScrollForward)
    }
    is LazyGridState -> state.layoutInfo.let { info ->
        val item = info.visibleItemsInfo.firstOrNull() ?: return null
        val span = info.maxSpan.coerceAtLeast(1)
        val size = item.size.height.coerceAtLeast(1)
        Position(info.totalItemsCount, info.visibleItemsInfo.size, span, state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset.toFloat() / size * span, size, !state.canScrollForward)
    }
    else -> null
}

/** Measured once when the drag starts, so the list doesn't wobble as rows of other heights come by. */
private suspend fun scrollTo(state: ScrollableState, start: Position, fraction: Float) {
    val (index, offset) = start.target(fraction)
    when (state) {
        is LazyListState -> state.scrollToItem(index, offset)
        is LazyGridState -> state.scrollToItem(index, offset)
    }
}
