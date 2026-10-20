package app.filo.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.filo.data.ViewLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min

/**
 * Long-press a file, then drag across others: everything from the first to the one under your finger
 * gets selected. Near the top or bottom edge the list scrolls by itself.
 */
class DragSelect {
    internal var anchor = -1
    internal var current = -1
    internal var base: Set<String> = emptySet()
    internal var lastPosition: Offset? = null

    /** The release after a long press must not also count as a tap. */
    internal var swallowNextClick = false

    fun consumeClick(): Boolean {
        val swallow = swallowNextClick
        swallowNextClick = false
        return swallow
    }
}

@Composable
fun Modifier.dragToSelect(
    state: DragSelect,
    keys: List<String>,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    itemAt: (Offset) -> String?,
    scrollBy: suspend (Float) -> Float,
    enabled: Boolean = true,
): Modifier {
    val currentKeys by rememberUpdatedState(keys)
    val currentSelection by rememberUpdatedState(selection)
    val change by rememberUpdatedState(onSelectionChange)
    val find by rememberUpdatedState(itemAt)
    val haptics = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    var speed by remember { mutableFloatStateOf(0f) }

    fun update(position: Offset) {
        if (state.anchor < 0) return
        val key = find(position) ?: return
        val index = currentKeys.indexOf(key)
        if (index < 0 || index == state.current) return
        state.current = index
        val range = currentKeys.subList(min(state.anchor, index), max(state.anchor, index) + 1)
        change(state.base + range)
    }

    LaunchedEffect(speed) {
        if (speed == 0f) return@LaunchedEffect
        while (isActive) {
            scrollBy(speed)
            state.lastPosition?.let(::update)
            delay(16)
        }
    }

    if (!enabled) return this
    return this.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { position ->
                val key = find(position) ?: return@detectDragGesturesAfterLongPress
                val index = currentKeys.indexOf(key)
                if (index < 0) return@detectDragGesturesAfterLongPress
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                state.anchor = index
                state.current = index
                state.base = currentSelection
                state.lastPosition = position
                state.swallowNextClick = true
                change(currentSelection + key)
            },
            onDrag = { pointer, _ ->
                if (state.anchor < 0) return@detectDragGesturesAfterLongPress
                pointer.consume()
                state.lastPosition = pointer.position
                update(pointer.position)
                val y = pointer.position.y
                speed = when {
                    y < edge -> -(edge - y) / edge * 30f
                    y > size.height - edge -> (y - (size.height - edge)) / edge * 30f
                    else -> 0f
                }
            },
            onDragEnd = {
                // The item under the finger handles its release first, so by now the tap was swallowed.
                state.anchor = -1
                state.lastPosition = null
                state.swallowNextClick = false
                speed = 0f
            },
            onDragCancel = {
                state.anchor = -1
                state.lastPosition = null
                state.swallowNextClick = false
                speed = 0f
            },
        )
    }
}

/**
 * Two-finger pinch changes the size of items: pinch out for bigger, pinch in for smaller.
 * Works on top of the list so normal one-finger scrolling isn't affected.
 */
@Composable
fun Modifier.pinchToResize(level: ViewLevel, onChange: (ViewLevel) -> Unit): Modifier {
    val currentLevel by rememberUpdatedState(level)
    val change by rememberUpdatedState(onChange)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            var done = false // one step per pinch, so it never jumps several sizes at once
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    event.changes.forEach { it.consume() }
                    if (!done) {
                        zoom *= event.calculateZoom()
                        val level = currentLevel
                        val next = when {
                            zoom > 1.35f -> level.bigger()
                            zoom < 0.74f -> level.smaller()
                            else -> level
                        }
                        if (next != level) {
                            done = true
                            change(next)
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
