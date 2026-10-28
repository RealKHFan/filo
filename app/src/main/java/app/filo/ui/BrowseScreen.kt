package app.filo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.filo.data.Category
import app.filo.data.FileEntry
import app.filo.data.FileKind
import app.filo.data.FileOps
import app.filo.data.Opener
import app.filo.data.StorageInfo
import app.filo.data.StorageSpace
import app.filo.data.StorageVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BrowseScreen(nav: NavController) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val changes by graph.actions.changes.collectAsStateWithLifecycle()
    val recent by produceState(emptyList<FileEntry>(), changes, settings.showHidden) {
        value = graph.media.recent(15, settings.showHidden)
    }
    val volumes = remember { graph.volumes() }
    val spaces by produceState(emptyMap<String, StorageSpace>(), changes) {
        value = withContext(Dispatchers.IO) { volumes.associate { it.root.absolutePath to StorageInfo.space(it.root) } }
    }
    val sizes by produceState(emptyMap<Category, Long>(), changes, settings.showHidden) {
        value = graph.media.categorySizes(settings.showHidden)
    }
    val downloadsSize by produceState<Long?>(null, changes) {
        value = withContext(Dispatchers.IO) { FileOps.totalSize(graph.downloadsDir) }
    }
    val appsSize by produceState<Long?>(null, changes, settings.showSystemApps, settings.fullAppSizes) {
        value = graph.apps.installed(settings.showSystemApps, settings.fullAppSizes).sumOf { it.shownSize }
    }
    val home = LocalHome.current
    val favoriteCount = graph.favorites.paths.collectAsStateWithLifecycle().value.size
    fun size(category: Category) = sizes[category]?.let(::formatSize)

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item { SearchPill(onSearch = { nav.navigate(Routes.SEARCH) }, onMenu = home::openMenu) }

        if (recent.isNotEmpty()) {
            item { SectionHeader("Recent") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recent, key = { it.path }) { entry ->
                        RecentCard(entry) {
                            if (entry.kind == FileKind.IMAGE) {
                                graph.viewerPaths = recent.filter { it.kind == FileKind.IMAGE }.map { it.path }
                                nav.navigate(Routes.image(entry.path))
                            } else if (!Opener.open(context, entry.file)) {
                                graph.actions.notify("No app on your phone can open this file")
                            }
                        }
                    }
                }
            }
        }

        item { SectionHeader("Storage") }
        items(volumes, key = { it.root.absolutePath }) { volume ->
            StorageCard(volume, spaces[volume.root.absolutePath]) {
                if (volume.isPrimary) home.requestedPage.value = Tabs.STORAGE else nav.navigate(Routes.folder(volume.root.absolutePath))
            }
            Spacer(Modifier.height(8.dp))
        }

        item { SectionHeader("Categories") }
        item {
            TileGrid(
                listOf(
                    TileSpec("Downloads", Icons.Outlined.Download, downloadsSize?.let(::formatSize)) {
                        nav.navigate(Routes.folder(graph.downloadsDir.absolutePath))
                    },
                    TileSpec("Images", Icons.Outlined.Image, size(Category.IMAGES)) { nav.navigate(Routes.category(Category.IMAGES)) },
                    TileSpec("Videos", Icons.Outlined.Movie, size(Category.VIDEOS)) { nav.navigate(Routes.category(Category.VIDEOS)) },
                    TileSpec("Audio", Icons.Outlined.MusicNote, size(Category.AUDIO)) { nav.navigate(Routes.category(Category.AUDIO)) },
                    TileSpec("Documents", Icons.Outlined.Description, size(Category.DOCUMENTS)) { nav.navigate(Routes.category(Category.DOCUMENTS)) },
                    TileSpec("Apps", Icons.Outlined.Android, appsSize?.let(::formatSize)) { nav.navigate(Routes.APPS) },
                ),
            )
        }

        item { SectionHeader("Collections") }
        item {
            val servers by graph.servers.servers.collectAsStateWithLifecycle()
            TileGrid(
                listOfNotNull(
                    TileSpec("Favorites", Icons.Outlined.Star, itemCount(favoriteCount)) { nav.navigate(Routes.FAVORITES) },
                    TileSpec("Hidden folder", Icons.Outlined.Lock, "PIN protected") { nav.navigate(Routes.VAULT) },
                    // File servers: only once switched on in Settings.
                    if (settings.networkServers) {
                        TileSpec("Network", Icons.Outlined.Lan, if (servers.isEmpty()) "SMB, FTP" else if (servers.size == 1) "1 server" else "${servers.size} servers") {
                            nav.navigate(Routes.NETWORK)
                        }
                    } else {
                        null
                    },
                ),
            )
        }
    }
}

@Composable
private fun SearchPill(onSearch: () -> Unit, onMenu: () -> Unit) {
    Surface(
        onClick = onSearch,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(56.dp).semantics { role = Role.Button },
    ) {
        Row(Modifier.padding(start = 4.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, contentDescription = "Open menu") }
            Spacer(Modifier.width(4.dp))
            Text(
                "Search in Filo",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecentCard(entry: FileEntry, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.width(112.dp).padding(6.dp)) {
            if (entry.kind == FileKind.IMAGE || entry.kind == FileKind.VIDEO) {
                FileThumb(entry, 100.dp)
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(100.dp),
                ) {
                    Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        FileThumb(entry, 48.dp)
                    }
                }
            }
            Text(
                entry.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

/** Internal storage at the top of Browse, one tap away (not hidden at the bottom). */
@Composable
private fun StorageCard(volume: StorageVolume, space: StorageSpace?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (volume.isPrimary) Icons.Outlined.Smartphone else Icons.Outlined.SdCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    volume.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "Open",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (space != null && space.total > 0) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { space.usedFraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${formatSize(space.used)} of ${formatSize(space.total)} used · ${formatSize(space.free)} free",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
