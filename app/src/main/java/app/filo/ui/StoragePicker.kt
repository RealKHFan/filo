package app.filo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.filo.data.ClipMode
import app.filo.data.StorageVolume
import app.filo.net.Server

/** Where "Copy to…" or "Move to…" should go: the phone, or one of your servers. */
sealed interface Destination {
    data class Phone(val volume: StorageVolume) : Destination
    data class Remote(val server: Server) : Destination
}

/**
 * Picks the storage for Copy to… / Move to…. Afterwards Filo opens it with the Paste bar ready,
 * so you only choose the folder and tap Paste.
 */
@Composable
fun StoragePicker(
    mode: ClipMode,
    count: Int,
    currentServerId: String?,
    onDismiss: () -> Unit,
    onPick: (Destination) -> Unit,
) {
    val graph = LocalGraph.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val servers by graph.servers.servers.collectAsStateWithLifecycle()
    val volumes = remember { graph.volumes() }

    @Composable
    fun option(icon: ImageVector, title: String, subtitle: String?, destination: Destination) {
        ListItem(
            leadingContent = {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            },
            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickableRow {
                onDismiss()
                onPick(destination)
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${if (mode == ClipMode.COPY) "Copy" else "Move"} ${itemCount(count)} to…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionHeader("This phone", Modifier.padding(start = 4.dp))
                volumes.forEach { v ->
                    option(if (v.isPrimary) Icons.Outlined.PhoneAndroid else Icons.Outlined.SdCard, v.label, null, Destination.Phone(v))
                }
                if (settings.networkServers && servers.isNotEmpty()) {
                    SectionHeader("Network", Modifier.padding(start = 4.dp))
                    servers.forEach { s ->
                        option(
                            Icons.Outlined.Dns,
                            s.displayName,
                            if (s.id == currentServerId) "This server" else s.summary,
                            Destination.Remote(s),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Opens the picked storage so the Paste bar is right there. Staying put when already in it. */
fun openDestination(
    destination: Destination,
    nav: NavController,
    home: HomeController,
    currentDir: java.io.File? = null,
    currentServerId: String? = null,
) {
    when (destination) {
        is Destination.Phone -> {
            val root = destination.volume.root
            when {
                currentDir != null && app.filo.data.FileOps.isSameOrInside(currentDir, root) -> Unit
                destination.volume.isPrimary -> home.showTab(nav, Tabs.STORAGE)
                else -> nav.navigate(Routes.folder(root.absolutePath))
            }
        }
        is Destination.Remote -> if (destination.server.id != currentServerId) nav.navigate(Routes.server(destination.server.id))
    }
}
