package app.filo.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.filo.Graph
import app.filo.data.Category
import app.filo.data.FileEntry
import app.filo.data.FileKind
import app.filo.data.FileOps
import app.filo.data.ReviewKind
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

val LocalGraph = staticCompositionLocalOf<Graph> { error("Graph not provided") }

/** Lets any screen switch the bottom tab or open the ☰ menu. */
class HomeController {
    val requestedPage = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val drawerRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun openMenu() {
        drawerRequests.tryEmit(Unit)
    }

    /** Goes back to the tabs (if a detail screen is open) and shows [page]. */
    fun showTab(nav: androidx.navigation.NavController, page: Int) {
        requestedPage.value = page
        if (nav.currentDestination?.route != Routes.HOME) nav.popBackStack(Routes.HOME, inclusive = false)
    }
}

val LocalHome = staticCompositionLocalOf { HomeController() }

object Tabs {
    const val REVIEW = 0
    const val BROWSE = 1
    const val STORAGE = 2
    const val TRASH = 3
    const val COUNT = 4
}

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

object Routes {
    const val HOME = "home"
    const val STORAGE = "storage" // shortcut target: home on the Storage tab
    const val ABOUT = "about"
    const val APPS = "apps"
    const val LICENSE = "license/{which}"

    fun license(which: String) = "license/$which"
    const val FOLDER = "folder?path={path}&pick={pick}&highlight={highlight}"
    const val CATEGORY = "category/{category}"
    const val REVIEW_LIST = "review/{kind}"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val VAULT = "vault"
    const val SETTINGS = "settings"
    const val IMAGE = "image?path={path}"
    const val VAULT_IMAGE = "vault_image/{id}"

    fun folder(path: String, pickForVault: Boolean = false, highlight: String? = null) =
        "folder?path=${Uri.encode(path)}&pick=$pickForVault" + (highlight?.let { "&highlight=${Uri.encode(it)}" } ?: "")
    fun category(category: Category) = "category/${category.name}"
    fun reviewList(kind: ReviewKind) = "review/${kind.name}"
    fun image(path: String) = "image?path=${Uri.encode(path)}"
    fun vaultImage(id: String) = "vault_image/$id"
}

fun FileKind.icon(): ImageVector = when (this) {
    FileKind.FOLDER -> Icons.Outlined.Folder
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.VIDEO -> Icons.Outlined.Movie
    FileKind.AUDIO -> Icons.Outlined.MusicNote
    FileKind.DOCUMENT -> Icons.Outlined.Description
    FileKind.ARCHIVE -> Icons.Outlined.FolderZip
    FileKind.APK -> Icons.Outlined.Android
    FileKind.OTHER -> @Suppress("DEPRECATION") Icons.Outlined.InsertDriveFile
}

fun formatDate(millis: Long): String =
    if (millis <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

fun formatSize(bytes: Long) = FileOps.formatSize(bytes)

fun itemCount(n: Int) = when (n) {
    -1 -> "Folder"
    1 -> "1 item"
    else -> "$n items"
}

fun FileEntry.subtitle(): String =
    if (isDirectory) "${itemCount(childCount)} · ${formatDate(lastModified)}"
    else "${formatSize(size)} · ${formatDate(lastModified)}"

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

data class TileSpec(val label: String, val icon: ImageVector, val detail: String? = null, val onClick: () -> Unit)

/** Rounded tile used for categories and collections on the Browse tab. */
@Composable
fun Tile(spec: TileSpec, modifier: Modifier = Modifier) {
    Surface(
        onClick = spec.onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(spec.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(spec.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    spec.detail ?: " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun TileGrid(tiles: List<TileSpec>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { Tile(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun KindIcon(kind: FileKind, size: Dp, modifier: Modifier = Modifier, corner: Dp = size * 0.28f) {
    val folder = kind == FileKind.FOLDER
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(if (folder) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            kind.icon(),
            contentDescription = null,
            tint = if (folder) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/** Real preview for pictures, videos and app installers; a type icon for everything else. */
@Composable
fun FileThumb(entry: FileEntry, size: Dp, modifier: Modifier = Modifier, corner: Dp = size * 0.28f) {
    when (entry.kind) {
        FileKind.IMAGE, FileKind.VIDEO -> {
            val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(64) * 2
            Box(modifier.size(size).clip(RoundedCornerShape(corner))) {
                KindIcon(entry.kind, size, Modifier.matchParentSize(), corner = corner)
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(entry.file).size(px).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                if (entry.kind == FileKind.VIDEO) {
                    Icon(
                        Icons.Outlined.PlayCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(size * 0.4f),
                    )
                }
            }
        }
        FileKind.APK -> ApkThumb(entry, size, modifier)
        else -> KindIcon(entry.kind, size, modifier)
    }
}

@Composable
private fun ApkThumb(entry: FileEntry, size: Dp, modifier: Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(48)
    val icon by produceState<ImageBitmap?>(null, entry.path) {
        value = withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(entry.path, 0)?.applicationInfo ?: return@withContext null
                info.sourceDir = entry.path
                info.publicSourceDir = entry.path
                info.loadIcon(pm).toBitmap(px, px).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        androidx.compose.foundation.Image(bitmap, contentDescription = null, modifier = modifier.size(size))
    } else {
        KindIcon(FileKind.APK, size, modifier)
    }
}
