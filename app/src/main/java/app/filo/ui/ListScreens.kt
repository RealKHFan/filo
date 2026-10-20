package app.filo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.filo.data.Category
import app.filo.data.FileEntry
import app.filo.data.FileSearch
import app.filo.data.SortField
import app.filo.data.SortOrder
import app.filo.data.sortedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CategoryScreen(
    nav: NavController,
    category: Category,
    title: String = category.title,
    topContent: (@Composable () -> Unit)? = null,
) {
    val graph = LocalGraph.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val changes by graph.actions.changes.collectAsStateWithLifecycle()
    var sort by rememberSaveable(stateSaver = sortSaver) { mutableStateOf(SortOrder(SortField.DATE, ascending = false)) }
    val entries by produceState<List<FileEntry>?>(null, changes, settings.showHidden, sort) {
        value = graph.media.category(category, settings.showHidden).sortedBy(sort, foldersFirst = false)
    }
    FileCollectionScreen(
        title = title,
        entries = entries,
        nav = nav,
        onBack = { nav.popBackStack() },
        onOpenFolder = { nav.navigate(Routes.folder(it.absolutePath)) },
        sort = sort,
        onSortChange = { sort = it },
        emptyTitle = if (category == Category.APPS) "No installer files" else "No ${category.title.lowercase()} yet",
        emptyBody = if (category == Category.APPS) ".apk files saved on your phone show up here." else "${category.title} saved on your phone show up here.",
        topContent = topContent,
    )
}

private val sortSaver = androidx.compose.runtime.saveable.Saver<SortOrder, String>(
    save = { "${it.field.name}:${it.ascending}" },
    restore = { SortOrder(SortField.valueOf(it.substringBefore(':')), it.substringAfter(':').toBoolean()) },
)

@Composable
fun FavoritesScreen(nav: NavController) {
    val graph = LocalGraph.current
    val favorites by graph.favorites.paths.collectAsStateWithLifecycle()
    val changes by graph.actions.changes.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val entries by produceState<List<FileEntry>?>(null, favorites, changes, settings.sort) {
        value = withContext(Dispatchers.IO) {
            favorites.map(::File).filter { it.exists() }.map { FileEntry.from(it, countChildren = true) }.sortedBy(settings.sort)
        }
    }
    FileCollectionScreen(
        title = "Favorites",
        entries = entries,
        nav = nav,
        onBack = { nav.popBackStack() },
        onOpenFolder = { nav.navigate(Routes.folder(it.absolutePath)) },
        sort = settings.sort,
        onSortChange = { order -> graph.settings.update { it.copy(sort = order) } },
        emptyTitle = "No favorites yet",
        emptyBody = "Tap ⋮ next to a file or folder and choose Add to favorites.",
    )
}

@Composable
fun SearchScreen(nav: NavController) {
    val graph = LocalGraph.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val changes by graph.actions.changes.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val results by produceState<List<FileEntry>?>(emptyList(), query, settings.showHidden, changes) {
        if (query.isBlank()) {
            value = emptyList()
            return@produceState
        }
        delay(250) // wait until typing pauses
        searching = true
        value = null
        FileSearch.search(graph.storageRoot, query, settings.showHidden).collect { value = it }
        searching = false
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search file and folder names") },
                    singleLine = true,
                    leadingIcon = {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") }
                    },
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
    ) { padding ->
        val list = results
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when {
                query.isBlank() -> EmptyState(Icons.Outlined.SearchOff, "Search your storage", "Type part of a file or folder name.")
                list == null -> Loading()
                list.isEmpty() && !searching -> EmptyState(Icons.Outlined.SearchOff, "No results", "Nothing matches \"$query\".")
                else -> FileCollectionScreen(
                    title = if (list.size == 1) "1 result" else "${list.size} results",
                    entries = list,
                    nav = nav,
                    onBack = null,
                    onOpenFolder = { nav.navigate(Routes.folder(it.absolutePath)) },
                    sort = settings.sort,
                    onSortChange = { order -> graph.settings.update { it.copy(sort = order) } },
                    showSearchAction = false,
                )
            }
        }
    }
}
