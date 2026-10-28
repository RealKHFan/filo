package app.filo

import android.content.Context
import android.os.Environment
import app.filo.data.AndroidAppsSource
import app.filo.data.AppsSource
import app.filo.data.FavoritesStore
import app.filo.data.FileActions
import app.filo.data.MediaSource
import app.filo.data.MediaStoreSource
import app.filo.data.ReviewAnalyzer
import app.filo.data.ReviewResult
import app.filo.data.SettingsStore
import app.filo.data.StorageInfo
import app.filo.data.StoragePermission
import app.filo.data.TrashManager
import app.filo.data.ViewPrefsStore
import app.filo.data.without
import app.filo.net.DefaultConnector
import app.filo.net.NetworkPermission
import app.filo.net.RemoteConnector
import app.filo.net.RemoteFile
import app.filo.net.ServerSessions
import app.filo.net.ServerStore
import app.filo.vault.AndroidBiometricKey
import app.filo.vault.AndroidDeviceKey
import app.filo.vault.BiometricKey
import app.filo.vault.KeyWrap
import app.filo.vault.PinKdf
import app.filo.vault.VaultManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Everything the app needs, created once. Tests build their own with fakes. */
class Graph(
    val context: Context,
    val storageRoot: File = Environment.getExternalStorageDirectory(),
    val permission: StoragePermission = StoragePermission.forContext(context),
    val media: MediaSource = MediaStoreSource(context),
    val apps: AppsSource = AndroidAppsSource(context),
    deviceKey: KeyWrap = AndroidDeviceKey(context.packageManager),
    val biometricKey: BiometricKey = AndroidBiometricKey(),
    pinKdf: PinKdf = PinKdf(),
    /** Android's Network permission (GrapheneOS can switch it off). */
    val networkPermission: NetworkPermission = NetworkPermission.forContext(context),
    serverKey: KeyWrap = AndroidDeviceKey(context.packageManager, alias = "filo_servers_v1"),
    connector: RemoteConnector? = null,
) {
    private val prefs = context.getSharedPreferences("filo", Context.MODE_PRIVATE)
    val settings = SettingsStore(prefs)
    /** View size and sort, remembered per folder and list. */
    val views = ViewPrefsStore(context.getSharedPreferences("filo_views", Context.MODE_PRIVATE))

    /** The pictures the image viewer can swipe through (set by the screen that opened it). */
    @Volatile var viewerPaths: List<String> = emptyList()
    val favorites = FavoritesStore(prefs)
    val trash = TrashManager(File(storageRoot, TrashManager.DIR_NAME))
    val vault = VaultManager(File(context.filesDir, "vault"), File(context.cacheDir, "vault_open"), deviceKey, pinKdf)
    val review = ReviewController(this)
    /** File servers (SMB, FTP). Passwords are sealed with the phone's hardware key. */
    val servers = ServerStore(context.getSharedPreferences("filo_servers", Context.MODE_PRIVATE), serverKey)
    val sessions = ServerSessions(
        servers,
        connector ?: DefaultConnector { server, certificate ->
            // Onto the saved copy, and only if it's still the same server: it may have been edited or deleted meanwhile.
            servers.get(server.id)?.takeIf { it.host == server.host && it.port == server.port && it.certificate == null }
                ?.let { servers.save(it.copy(certificate = certificate), null) }
        },
        networkPermission,
    ) { settings.state.value.networkServers }

    /** The server pictures the image viewer can swipe through. */
    @Volatile var remoteViewer: List<RemoteFile> = emptyList()
    val actions = FileActions(context, this)

    val downloadsDir: File get() = File(storageRoot, Environment.DIRECTORY_DOWNLOADS)

    fun volumes() = StorageInfo.volumes(context, storageRoot)
}

class ReviewController(private val graph: Graph) {
    sealed interface State {
        data object Idle : State
        data class Scanning(val files: Int) : State
        data class Done(val result: ReviewResult) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun scan() {
        job?.cancel()
        job = scope.launch {
            _state.value = State.Scanning(0)
            try {
                val result = ReviewAnalyzer(graph.storageRoot).analyze(
                    onProgress = { _state.value = State.Scanning(it) },
                    isCancelled = { !isActive },
                )
                _state.value = State.Done(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "Couldn't read storage")
            }
        }
    }

    /** Drops files the person just deleted from the results. */
    fun forget(paths: Set<String>) {
        val current = _state.value
        if (current is State.Done) _state.value = State.Done(current.result.without(paths))
    }
}
