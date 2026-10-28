package app.filo.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.filo.Graph
import app.filo.data.ClipMode
import app.filo.data.ExactBytes
import app.filo.data.FileEntry
import app.filo.data.FileKind
import app.filo.data.FileOps
import app.filo.data.ViewLevel
import app.filo.data.ViewPrefs
import app.filo.data.ViewPrefsStore
import app.filo.data.sortedBy
import app.filo.net.CertificateChangedException
import app.filo.net.LoginFailedException
import app.filo.net.PasswordNeededException
import app.filo.net.Protocol
import app.filo.net.RemoteFile
import app.filo.net.RemotePath
import app.filo.net.Server
import app.filo.net.ServerSessions.Channel
import app.filo.net.isNetworkBlocked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------- Network permission

/** Filo's page in Android settings, where Permissions → Network can be switched on. */
fun openAppInfo(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Android's Network permission, checked again every time Filo comes back to the front. */
@Composable
fun rememberNetworkAllowed(): Boolean {
    val graph = LocalGraph.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember { mutableStateOf(graph.networkPermission.granted()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = graph.networkPermission.granted()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return allowed
}

/** Android has no pop-up for the Network permission, so Filo explains it and opens the right page. */
@Composable
fun NetworkPermissionCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Allow Filo to use the network", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Android keeps Filo offline until you allow it. Tap below, then Permissions → Network → Allow.\n\n" +
                "This is the only permission file servers need. Filo only connects to servers on your home network.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { openAppInfo(context) }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open App info")
        }
    }
}

// ---------------------------------------------------------------- Server list

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(nav: NavController) {
    val graph = LocalGraph.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val servers by graph.servers.servers.collectAsStateWithLifecycle()
    val allowed = rememberNetworkAllowed()
    var removing by remember { mutableStateOf<Server?>(null) }
    val ready = settings.networkServers && allowed

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            if (ready) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.serverEdit(null)) },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text("Add server") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                !settings.networkServers -> {
                    EmptyState(Icons.Outlined.Lan, "File servers are off", "Switch them on to reach SMB and FTP servers on your home network.")
                    Button(
                        onClick = { graph.settings.update { it.copy(networkServers = true) } },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("Turn on") }
                }
                !allowed -> NetworkPermissionCard()
                servers.isEmpty() -> EmptyState(
                    Icons.Outlined.Dns,
                    "No servers yet",
                    "Add your NAS, a shared folder on your PC, or an FTP server. Filo only connects to addresses on your home network.",
                )
                else -> LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                    items(servers, key = { it.id }) { server ->
                        var menu by remember { mutableStateOf(false) }
                        ListItem(
                            leadingContent = {
                                Box(
                                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Outlined.Dns, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                            },
                            headlineContent = { Text(server.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(server.summary + if (server.user.isNotEmpty()) " · ${server.user}" else " · guest", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More options for ${server.displayName}") }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = {
                                            menu = false
                                            nav.navigate(Routes.serverEdit(server.id))
                                        })
                                        DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Outlined.DeleteForever, null) }, onClick = {
                                            menu = false
                                            removing = server
                                        })
                                    }
                                }
                            },
                            modifier = Modifier.clickableRow { nav.navigate(Routes.server(server.id)) },
                        )
                    }
                }
            }
        }
    }

    removing?.let { server ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${server.displayName}?") },
            text = { Text("Filo forgets this server and its saved password. Nothing on the server changes.") },
            confirmButton = {
                Button(onClick = {
                    removing = null
                    graph.sessions.close(server.id)
                    graph.sessions.forgetForNow(server.id)
                    graph.servers.remove(server.id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Add / edit a server

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(nav: NavController, id: String?) {
    val graph = LocalGraph.current
    val existing = remember(id) { id?.let { graph.servers.get(it) } }
    val hasSavedPassword = remember(id) { id != null && graph.servers.hasPassword(id) }
    var protocol by remember { mutableStateOf(existing?.protocol ?: Protocol.SMB) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember { mutableStateOf(existing?.port?.toString().orEmpty()) }
    var folder by remember { mutableStateOf(existing?.startPath.orEmpty()) }
    var user by remember { mutableStateOf(existing?.user.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var domain by remember { mutableStateOf(existing?.domain.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var savePassword by remember { mutableStateOf(existing?.savePassword ?: true) }
    var certificate by remember { mutableStateOf(existing?.certificate) }

    val portNumber = port.toIntOrNull()
    val portOk = port.isEmpty() || (portNumber != null && portNumber in 1..65535)
    val hostOk = host.isNotBlank() && host.none { it.isWhitespace() || it == '/' || it == '\\' }
    val canSave = hostOk && portOk

    fun save() {
        val server = Server(
            id = existing?.id ?: graph.servers.newId(),
            name = name.trim(),
            protocol = protocol,
            host = host.trim(),
            port = portNumber,
            startPath = RemotePath.normalize(folder),
            user = user.trim(),
            domain = if (protocol == Protocol.SMB) domain.trim() else "",
            savePassword = savePassword,
            certificate = if (protocol == Protocol.FTPS) certificate else null,
        )
        val typed = password.takeIf { it.isNotEmpty() }
        graph.servers.save(server, if (server.user.isEmpty()) "" else typed)
        graph.sessions.close(server.id) // the next visit connects with the new settings
        if (!savePassword && typed != null) graph.sessions.rememberForNow(server.id, typed) else graph.sessions.forgetForNow(server.id)
        nav.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add server" else "Edit server") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Outlined.Close, "Cancel") } },
                actions = { TextButton(onClick = ::save, enabled = canSave) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Protocol.entries.forEachIndexed { index, p ->
                    SegmentedButton(
                        selected = protocol == p,
                        onClick = { protocol = p },
                        shape = SegmentedButtonDefaults.itemShape(index, Protocol.entries.size),
                    ) { Text(p.label) }
                }
            }
            Text(
                when (protocol) {
                    Protocol.SMB -> "Windows shares, Samba and most NAS boxes. Encrypted when the server supports it."
                    Protocol.FTP -> "Plain FTP: nothing is encrypted, not even the password. Use FTPS if your server offers it."
                    Protocol.FTPS -> "FTP with encryption. The server's certificate is remembered the first time."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Address") },
                placeholder = { Text("192.168.1.20 or the server's name") },
                singleLine = true,
                isError = host.isNotEmpty() && !hostOk,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { v -> if (v.length <= 5 && v.all(Char::isDigit)) port = v },
                label = { Text("Port") },
                placeholder = { Text("${protocol.defaultPort}") },
                singleLine = true,
                isError = !portOk,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                label = { Text("Folder to open (optional)") },
                placeholder = { Text(if (protocol == Protocol.SMB) "Share/Folder" else "folder") },
                supportingText = { Text(if (protocol == Protocol.SMB) "Empty: all shares on the server" else "Empty: your home folder on the server") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("User name") },
                supportingText = { Text(if (protocol == Protocol.SMB) "Empty: guest access" else "Empty: anonymous") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (user.isNotBlank()) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    supportingText = { if (hasSavedPassword && savePassword) Text("Saved. Type a new one to change it.") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPassword) "Hide password" else "Show password")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Remember password", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (savePassword) "Encrypted with your phone's security chip" else "Asked once each time Filo starts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = savePassword, onCheckedChange = { savePassword = it })
                }
                if (protocol == Protocol.SMB) {
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text("Domain (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                placeholder = { Text(host.ifBlank { "My NAS" }) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val cert = certificate
            if (protocol == Protocol.FTPS && cert != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Remembered certificate", style = MaterialTheme.typography.bodyLarge)
                        Text(cert.take(23) + "…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { certificate = null }) { Text("Forget") }
                }
            }
            Text(
                "Filo only connects to addresses on your home network or your own VPN (like Tailscale).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Browsing a server

private const val REMOTE = "remote:"
private val SERVER_ROOT = File("/server")

private fun RemoteFile.toEntry() = FileEntry(REMOTE + path, name, isDirectory, size, lastModified)

private fun remotePathOf(entryPath: String) = entryPath.removePrefix(REMOTE)

class ServerViewModel(private val graph: Graph, private val serverId: String) : ViewModel() {
    sealed interface Listing {
        data object Loading : Listing
        data class Loaded(val path: String, val files: List<RemoteFile>) : Listing
        data class Failed(val problem: Problem, val message: String) : Listing
    }

    enum class Problem { NETWORK, PASSWORD, LOGIN, OTHER }

    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()
    private val _listing = MutableStateFlow<Listing>(Listing.Loading)
    val listing: StateFlow<Listing> = _listing.asStateFlow()
    private val retries = MutableStateFlow(0)

    /** Where this server opens; Back goes up to here, then out. */
    var start: String? = null
        private set

    init {
        viewModelScope.launch {
            combine(_path, retries, graph.actions.changes, graph.servers.servers.map { list -> list.firstOrNull { it.id == serverId } }) { p, _, _, s -> p to s }
                .collectLatest { (p, server) -> if (server != null) load(server, p) }
        }
    }

    private suspend fun load(server: Server, path: String?) {
        val current = _listing.value
        if (current !is Listing.Loaded || current.path != path) _listing.value = Listing.Loading
        _listing.value = try {
            val folder = path ?: graph.sessions.use(server) { fs -> RemotePath.normalize(server.startPath).ifEmpty { fs.home } }.also {
                start = it
                _path.value = it
            }
            Listing.Loaded(folder, graph.sessions.use(server) { it.list(folder) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when {
                e.isNetworkBlocked() -> Listing.Failed(Problem.NETWORK, "")
                e is PasswordNeededException -> Listing.Failed(Problem.PASSWORD, "")
                e is LoginFailedException || e is CertificateChangedException -> Listing.Failed(Problem.LOGIN, e.message.orEmpty())
                else -> Listing.Failed(Problem.OTHER, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun open(path: String) {
        _path.value = path
    }

    fun retry() {
        retries.value++
    }
}

/**
 * Server pictures as previews, kept in memory only while you browse. Photos carry a small preview
 * at their very start, so only that bit is downloaded first. Big grid cells get a sharp version
 * afterwards, but only while no copy is running, so previews never slow down a transfer.
 */
private class RemoteThumbs(private val graph: Graph, private val server: () -> Server?, private val lookup: () -> Map<String, RemoteFile>) : ThumbProvider {
    private val cache = LruCache<String, ImageBitmap>(200)
    private val sharp = HashSet<String>()

    @Composable
    override fun Thumb(entry: FileEntry, size: Dp, modifier: Modifier, corner: Dp) {
        val file = lookup()[entry.path]
        val wantSharp = size > 100.dp
        val bitmap by produceState(cache.get(entry.path), entry.path, wantSharp) {
            val s = server()
            if (s == null || file == null || entry.kind != FileKind.IMAGE || file.size <= 0) return@produceState
            try {
                if (value == null) {
                    val head = graph.sessions.use(s, Channel.THUMBS) { fs -> fs.readHead(file.path, PREVIEW_HEAD) }
                    value = withContext(Dispatchers.Default) {
                        if (head.size.toLong() >= file.size) decodeImage(head, 256) else exifPreview(head)
                    }
                    if (head.size.toLong() >= file.size) sharp += entry.path
                    value?.let { cache.put(entry.path, it) }
                }
                if ((value == null || wantSharp) && entry.path !in sharp && file.size <= 12_000_000) {
                    graph.actions.operation.first { it == null } // wait until no copy is running
                    pictureSlots.withPermit {
                        val bytes = graph.sessions.use(s, Channel.THUMBS) { fs -> ExactBytes(file.size).also { fs.read(file.path, it) }.bytes() }
                        withContext(Dispatchers.Default) { decodeImage(bytes, 384) }
                    }?.let {
                        value = it
                        cache.put(entry.path, it)
                        sharp += entry.path
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // not a picture Android can read, too big for memory, or the server said no: keep the icon
            }
        }
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(RoundedCornerShape(corner)))
        } else {
            KindIcon(entry.kind, size, modifier, corner)
        }
    }
}

private const val PREVIEW_HEAD = 128 * 1024

/** The small preview a camera stores inside a photo (EXIF), turned the right way up. */
private fun exifPreview(head: ByteArray): ImageBitmap? = try {
    val exif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(head))
    val thumb = exif.thumbnailBitmap
    if (thumb == null) {
        null
    } else {
        val degrees = exif.rotationDegrees
        val upright = if (degrees == 0) thumb else android.graphics.Bitmap.createBitmap(
            thumb, 0, 0, thumb.width, thumb.height, android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }, true,
        )
        upright.asImageBitmap()
    }
} catch (_: Exception) {
    null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(nav: NavController, id: String) {
    val graph = LocalGraph.current
    val actions = graph.actions
    val servers by graph.servers.servers.collectAsStateWithLifecycle()
    val server = servers.firstOrNull { it.id == id }
    val vm: ServerViewModel = viewModel(key = "server:$id") { ServerViewModel(graph, id) }
    val path by vm.path.collectAsStateWithLifecycle()
    val listing by vm.listing.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val views by graph.views.all.collectAsStateWithLifecycle()
    val clip by actions.clip.collectAsStateWithLifecycle()
    val allowed = rememberNetworkAllowed()

    LaunchedEffect(server == null) { if (server == null) nav.popBackStack() }
    if (server == null) return
    LaunchedEffect(allowed) { if (allowed) vm.retry() }

    val here = path.orEmpty()
    // The list of SMB shares: they can't be renamed, deleted or moved, and Download would copy a whole disk.
    val atShares = server.protocol == Protocol.SMB && here.isEmpty()
    val viewKey = "server:$id:$here"
    val view = views[viewKey] ?: ViewPrefsStore.defaultsForFolder(File(SERVER_ROOT, here), SERVER_ROOT)
    fun setView(next: ViewPrefs) = graph.views.set(viewKey, next)

    val loaded = listing as? ServerViewModel.Listing.Loaded
    val files = remember(loaded, settings.showHidden) { loaded?.files.orEmpty().filter { settings.showHidden || !it.hidden } }
    // Everything seen while browsing, so dragged files are still known after opening another folder.
    val known = remember { HashMap<String, RemoteFile>() }
    files.forEach { known[REMOTE + it.path] = it }
    val byPath = remember(files) { files.associateBy { REMOTE + it.path } }
    val entries = remember(files, view.sort) { files.map { it.toEntry() }.sortedBy(view.sort) }
    val currentServer by rememberUpdatedState(server)
    val thumbs = remember { RemoteThumbs(graph, { currentServer }) { known } }

    var selection by remember(here) { mutableStateOf(emptySet<String>()) }
    var sheetFor by remember { mutableStateOf<FileEntry?>(null) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var info by remember { mutableStateOf<RemoteFile?>(null) }
    var deleting by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val start = vm.start ?: ""
    val atStart = here == start || here.isEmpty()
    fun up() = vm.open(RemotePath.parent(here))
    BackHandler(enabled = selection.isNotEmpty() || !atStart) {
        if (selection.isNotEmpty()) selection = emptySet() else up()
    }

    val home = LocalHome.current
    val selected = selection.mapNotNull { byPath[it] }
    var picking by remember { mutableStateOf<Pair<ClipMode, List<RemoteFile>>?>(null) }

    /** Copy to… / Move to…: first choose the storage (the phone or a server), then paste there. */
    fun clip(mode: ClipMode, list: List<RemoteFile>) {
        if (list.isNotEmpty()) picking = mode to list
    }

    fun download(list: List<RemoteFile>) {
        actions.downloadFromServer(server, list) { file ->
            nav.navigate(Routes.folder(file.parentFile!!.absolutePath, highlight = file.absolutePath))
        }
        selection = emptySet()
    }

    fun open(entry: FileEntry) {
        val file = byPath[entry.path] ?: return
        when {
            file.isDirectory -> vm.open(file.path)
            entry.kind == FileKind.IMAGE -> {
                graph.remoteViewer = entries.filter { it.kind == FileKind.IMAGE }.mapNotNull { byPath[it.path] }
                nav.navigate(Routes.serverImage(id, file.path))
            }
            else -> actions.openFromServer(server, listOf(file))
        }
    }

    val dropSpace = "net:$id"
    val title = if (here.isEmpty() || here == start && start.isEmpty()) server.displayName else RemotePath.name(here)

    CompositionLocalProvider(LocalThumbs provides thumbs) {
        Scaffold(
            topBar = {
                if (selection.isNotEmpty()) {
                    var more by remember { mutableStateOf(false) }
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Outlined.Close, "Clear selection") } },
                        title = { Text("${selection.size} selected") },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        actions = {
                            IconButton(onClick = { selection = entries.map { it.path }.toSet() }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                            IconButton(onClick = { download(selected) }) { Icon(Icons.Outlined.Download, "Download") }
                            IconButton(onClick = { deleting = selected }) { Icon(Icons.Outlined.DeleteForever, "Delete for good") }
                            IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "More actions") }
                            DropdownMenu(more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(
                                    text = { Text("Copy to…") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = {
                                        more = false
                                        clip(ClipMode.COPY, selected)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Move to…") },
                                    leadingIcon = { @Suppress("DEPRECATION") Icon(Icons.Outlined.DriveFileMove, null) },
                                    onClick = {
                                        more = false
                                        clip(ClipMode.MOVE, selected)
                                    },
                                )
                                if (selected.isNotEmpty() && selected.none { it.isDirectory }) {
                                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
                                        more = false
                                        actions.openFromServer(server, selected, share = true)
                                        selection = emptySet()
                                    })
                                }
                                if (selected.size == 1) {
                                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = {
                                        more = false
                                        renaming = entries.firstOrNull { it.path in selection }
                                    })
                                }
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = { if (atStart) nav.popBackStack() else up() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        },
                        actions = {
                            IconButton(onClick = { setView(view.copy(level = if (view.level.isGrid) ViewLevel.LIST else ViewLevel.GRID_3)) }) {
                                if (view.level.isGrid) Icon(Icons.AutoMirrored.Outlined.List, "Show as list") else Icon(Icons.Outlined.GridView, "Show as grid")
                            }
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                            SortMenu(sortMenu, view.sort, onDismiss = { sortMenu = false }, onChange = { setView(view.copy(sort = it)) })
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                if (loaded != null && !atShares) {
                                    DropdownMenuItem(text = { Text("New folder") }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) }, onClick = {
                                        menu = false
                                        newFolder = true
                                    })
                                }
                                DropdownMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = {
                                    menu = false
                                    vm.retry()
                                })
                                DropdownMenuItem(text = { Text("Edit server") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = {
                                    menu = false
                                    nav.navigate(Routes.serverEdit(id))
                                })
                            }
                        },
                    )
                }
            },
            bottomBar = {
                val c = clip
                if (c != null && loaded != null && selection.isEmpty() && !atShares) {
                    ActionBar(
                        text = "${itemCount(c.paths.size)} to ${if (c.mode == ClipMode.COPY) "copy" else "move"}",
                        confirm = if (c.mode == ClipMode.COPY) "Paste" else "Move here",
                        enabled = true,
                        onCancel = { actions.clearClip() },
                        onConfirm = { actions.pasteToServer(server, here) },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (path != null) {
                    Breadcrumbs(
                        server.displayName,
                        SERVER_ROOT,
                        File(SERVER_ROOT, here),
                        onOpen = { vm.open(it.absolutePath.removePrefix(SERVER_ROOT.absolutePath).trim('/')) },
                        drop = CrumbDrop(dropSpace, { it.absolutePath.removePrefix(SERVER_ROOT.absolutePath).trim('/') }) { target, paths ->
                            actions.moveOnServer(server, paths.mapNotNull { known[it] }, target.absolutePath.removePrefix(SERVER_ROOT.absolutePath).trim('/'))
                        },
                    )
                }
                val paneDrop = PaneDrop(dropSpace, here, title) { target, paths ->
                    val destination = target?.let { remotePathOf(it.path) } ?: here
                    actions.moveOnServer(server, paths.mapNotNull { known[it] }, destination)
                    selection = emptySet()
                }
                when (val l = listing) {
                    ServerViewModel.Listing.Loading -> Loading()
                    is ServerViewModel.Listing.Failed -> when (l.problem) {
                        ServerViewModel.Problem.NETWORK -> NetworkPermissionCard()
                        ServerViewModel.Problem.PASSWORD -> PasswordPrompt(server) { vm.retry() }
                        ServerViewModel.Problem.LOGIN -> Problem(Icons.Outlined.Lock, "Couldn't sign in", l.message, "Edit server") { nav.navigate(Routes.serverEdit(id)) }
                        ServerViewModel.Problem.OTHER -> Problem(Icons.Outlined.CloudOff, "Couldn't open ${title}", l.message, "Try again") { vm.retry() }
                    }
                    is ServerViewModel.Listing.Loaded -> if (entries.isEmpty()) {
                        Box(Modifier.fillMaxSize().dropHere(paneDrop)) {
                            EmptyState(Icons.Outlined.FolderOpen, "This folder is empty", if (settings.showHidden) "There are no files here." else "There are no files here. Hidden files are not shown.")
                        }
                    } else {
                        key(here) {
                            FileListPane(
                                entries = entries,
                                level = view.level,
                                selection = selection,
                                favorites = emptySet(),
                                onOpen = ::open,
                                onSelectionChange = { selection = it },
                                onMore = if (atShares) null else { entry -> sheetFor = entry },
                                onLevelChange = { setView(view.copy(level = it)) },
                                selectable = { !atShares },
                                drop = if (atShares) null else paneDrop,
                            )
                        }
                    }
                }
            }
        }

        sheetFor?.let { entry ->
            val file = byPath[entry.path]
            if (file != null) {
                RemoteFileSheet(
                    entry = entry,
                    onDismiss = { sheetFor = null },
                    onDownload = { download(listOf(file)) },
                    onOpenWith = { actions.openFromServer(server, listOf(file), chooser = true) },
                    onShare = { actions.openFromServer(server, listOf(file), share = true) },
                    onCopy = { clip(ClipMode.COPY, listOf(file)) },
                    onMove = { clip(ClipMode.MOVE, listOf(file)) },
                    onRename = { renaming = entry },
                    onInfo = { info = file },
                    onDelete = { deleting = listOf(file) },
                )
            }
        }
    }

    renaming?.let { entry ->
        val file = byPath[entry.path]
        NameDialog(
            title = "Rename",
            initial = entry.name,
            selectBaseName = !entry.isDirectory,
            confirm = "Rename",
            exists = { name -> files.any { it.name == name && it.path != file?.path } },
            onDismiss = { renaming = null },
            onConfirm = { name ->
                if (file != null) actions.renameOnServer(server, file, name)
                selection = emptySet()
            },
        )
    }

    if (newFolder) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirm = "Create",
            exists = { name -> files.any { it.name == name } },
            onDismiss = { newFolder = false },
            onConfirm = { actions.createFolderOnServer(server, here, it) },
        )
    }

    deleting?.let { list ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${itemCount(list.size)} for good?") },
            text = {
                Text(
                    "Files deleted on ${server.displayName} don't go to Trash and can't be brought back." +
                        if (list.any { it.isDirectory }) " Everything inside the folders is deleted too." else "",
                )
            },
            confirmButton = {
                Button(onClick = {
                    deleting = null
                    actions.deleteOnServer(server, list)
                    selection = emptySet()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    picking?.let { (mode, list) ->
        StoragePicker(mode, list.size, currentServerId = id, onDismiss = { picking = null }) { destination ->
            actions.setRemoteClip(mode, server, list)
            selection = emptySet()
            openDestination(destination, nav, home, currentServerId = id)
        }
    }

    info?.let { file ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text("File info") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoRow("Name", file.name)
                    InfoRow("Location", "${server.displayName}/${RemotePath.parent(file.path)}".trimEnd('/'))
                    if (!file.isDirectory) InfoRow("Size", "${formatSize(file.size)} (${"%,d".format(file.size)} bytes)")
                    if (file.lastModified > 0) InfoRow("Modified", formatDate(file.lastModified))
                }
            },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Problem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(icon, title, body)
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}

/** For servers that don't keep the password: asked once, remembered until Filo closes. */
@Composable
private fun PasswordPrompt(server: Server, onDone: () -> Unit) {
    val graph = LocalGraph.current
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Password for ${server.user}", style = MaterialTheme.typography.titleMedium)
        Text("on ${server.displayName}. It's kept only until Filo closes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = password.isNotEmpty(),
            onClick = {
                graph.sessions.rememberForNow(server.id, password)
                onDone()
            },
            modifier = Modifier.align(Alignment.End),
        ) { Text("Connect") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteFileSheet(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            ListItem(
                leadingContent = { FileThumb(entry, 44.dp) },
                headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(entry.subtitle()) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            @Composable
            fun action(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, run: () -> Unit) {
                ListItem(
                    leadingContent = { Icon(icon, null) },
                    headlineContent = { Text(text) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickableRow {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onDismiss()
                            run()
                        }
                    },
                )
            }
            action("Download", Icons.Outlined.Download, onDownload)
            if (!entry.isDirectory) {
                action("Open with", Icons.AutoMirrored.Outlined.OpenInNew, onOpenWith)
                action("Share", Icons.Outlined.Share, onShare)
            }
            action("Copy to…", Icons.Outlined.ContentCopy, onCopy)
            @Suppress("DEPRECATION")
            action("Move to…", Icons.Outlined.DriveFileMove, onMove)
            action("Rename", Icons.Outlined.Edit, onRename)
            action("File info", Icons.Outlined.Info, onInfo)
            action("Delete for good", Icons.Outlined.DeleteForever, onDelete)
        }
    }
}

/** Pictures on a server, downloaded one at a time as you swipe. */
@Composable
fun ServerImageScreen(nav: NavController, id: String, path: String) {
    val graph = LocalGraph.current
    val server = remember(id) { graph.servers.get(id) }
    val pictures = remember { graph.remoteViewer.ifEmpty { listOfNotNull(RemoteFile(path, RemotePath.name(path), false, 0, 0)) } }
    val start = remember { pictures.indexOfFirst { it.path == path }.coerceAtLeast(0) }
    if (server == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    PhotoPager(
        count = pictures.size,
        initialPage = start,
        title = { pictures[it].name },
        onBack = { nav.popBackStack() },
        actions = { page ->
            IconButton(onClick = { graph.actions.openFromServer(server, listOf(pictures[page]), share = true) }) {
                Icon(Icons.Outlined.Share, "Share", tint = Color.White)
            }
        },
    ) { page, modifier ->
        val file = pictures[page]
        var failed by remember(file.path) { mutableStateOf(false) }
        val bitmap by produceState<ImageBitmap?>(null, file.path) {
            value = try {
                if (file.size > pictureLimit()) {
                    null
                } else {
                    val bytes = graph.sessions.use(server, Channel.BROWSE) { fs -> ExactBytes(file.size).also { fs.read(file.path, it) }.bytes() }
                    withContext(Dispatchers.Default) { decodeImage(bytes, 2048) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null // not a picture Android can read, too big for memory, or the server said no
            }
            failed = value == null
        }
        val bmp = bitmap
        when {
            bmp != null -> Image(bmp, contentDescription = file.name, contentScale = ContentScale.Fit, modifier = modifier)
            failed -> PictureProblem(modifier)
            else -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.testTag("loading_picture")) }
        }
    }
}
