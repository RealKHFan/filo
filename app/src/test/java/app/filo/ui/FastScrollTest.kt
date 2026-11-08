package app.filo.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.filo.data.FileEntry
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class FastScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `a list position maps to the right item and back`() {
        // 300 rows of 100 px, 10 on screen: the first visible row runs from 0 to 290.
        val top = Position(total = 300, visible = 10, span = 1, first = 0f, rowSize = 100, atEnd = false)
        assertEquals(0f, top.fraction)
        assertEquals(145 to 0, top.target(0.5f))
        assertEquals(29 to 0, top.target(0.1f))
        assertEquals(299 to 0, top.target(1f)) // the very end, past the bottom padding too
        assertEquals(0.5f, Position(300, 10, 1, 145f, 100, false).fraction)
        assertEquals(1f, Position(300, 10, 1, 280f, 100, atEnd = true).fraction)
    }

    @Test fun `a grid position counts whole rows`() {
        // 4 per row: halfway lands on the start of a row, with the rest as an offset into it.
        val grid = Position(total = 1000, visible = 24, span = 4, first = 0f, rowSize = 200, atEnd = false)
        val (index, offset) = grid.target(0.5f)
        assertEquals(0, index % 4)
        assertEquals(488, index)
        assertEquals(0, offset)
        assertEquals(100, grid.target(0.5f + 2f / 976).second) // half a row further
    }

    private fun showList(count: Int): LazyListState {
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                Surface {
                    state = rememberLazyListState()
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                            items(count) { Text("Item $it", Modifier.height(56.dp)) }
                        }
                        FastScroller(state)
                    }
                }
            }
        }
        return state
    }

    private fun handleShown() = compose.onAllNodesWithTagCount("fast_scroll") > 0

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test fun `the handle shows while scrolling and dragging it reaches the end`() {
        val state = showList(500)
        compose.waitForIdle()
        assertFalse("hidden until the list moves", handleShown())

        compose.onRoot().performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 300f) }
        compose.waitUntil(3_000) { handleShown() }

        compose.onNodeWithTag("fast_scroll").performTouchInput {
            down(center)
            moveBy(Offset(0f, 5_000f))
            up()
        }
        compose.waitForIdle()
        assertFalse("reached the end", state.canScrollForward)
        assertEquals(499, state.layoutInfo.visibleItemsInfo.last().index)

        compose.onNodeWithTag("fast_scroll").performTouchInput {
            down(center)
            moveBy(Offset(0f, -10_000f))
            up()
        }
        compose.waitForIdle()
        assertEquals("back at the top", 0, state.firstVisibleItemIndex)

        compose.waitUntil(5_000) { !handleShown() } // fades out after a moment
    }

    @Test fun `dragging halfway down a grid lands halfway`() {
        lateinit var state: LazyGridState
        compose.setContent {
            MaterialTheme {
                Surface {
                    state = rememberLazyGridState()
                    Box(Modifier.fillMaxSize()) {
                        LazyVerticalGrid(GridCells.Fixed(4), state = state, modifier = Modifier.fillMaxSize()) {
                            items(1000) { Text("Cell $it", Modifier.height(100.dp)) }
                        }
                        FastScroller(state)
                    }
                }
            }
        }
        compose.onRoot().performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 300f) }
        compose.waitUntil(3_000) { handleShown() }
        val trackHeight = compose.onRoot().fetchSemanticsNode().size.height
        val thumb = compose.onNodeWithTag("fast_scroll").fetchSemanticsNode()
        val thumbHeight = thumb.size.height
        val target = (trackHeight - thumbHeight) / 2f
        compose.onNodeWithTag("fast_scroll").performTouchInput {
            down(center)
            moveBy(Offset(0f, target - thumb.positionInRoot.y))
            up()
        }
        compose.waitForIdle()
        val first = state.firstVisibleItemIndex
        assertTrue("first visible $first", first in 440..520)
        assertEquals(0, first % 4)
    }

    @Test fun `short lists get no handle`() {
        showList(12)
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertFalse(handleShown())
    }

    @Test fun `screenshot of a long folder while dragging the handle`() {
        val now = System.currentTimeMillis()
        val entries = (1..400).map { i ->
            FileEntry("/storage/Documents/Report $i.pdf", "Report $i.pdf", false, 120_000L + i * 1000, now - i * 86_400_000L)
        }
        compose.setContent {
            MaterialTheme {
                Surface {
                    FileListPane(
                        entries = entries,
                        level = app.filo.data.ViewLevel.LIST,
                        selection = emptySet(),
                        favorites = emptySet(),
                        onOpen = {},
                        onSelectionChange = {},
                        onMore = {},
                        onLevelChange = null,
                    )
                }
            }
        }
        compose.onRoot().performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 300f) }
        compose.waitUntil(3_000) { handleShown() }
        compose.onNodeWithTag("fast_scroll").performTouchInput {
            down(center)
            moveBy(Offset(0f, 900f))
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/screenshots/60_fast_scroll.png")
        compose.onNodeWithTag("fast_scroll").performTouchInput { up() }
    }
}
