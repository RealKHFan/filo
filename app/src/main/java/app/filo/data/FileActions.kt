package app.filo.data

import android.content.Context
import app.filo.Graph
import app.filo.vault.VaultItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A running task, shown as a progress dialog. progress == null means "unknown". */
data class Operation(val title: String, val progress: Float?)

data class UserMessage(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

enum class ClipMode { COPY, MOVE }

data class Clip(val mode: ClipMode, val paths: List<String>)

/** Every change to files goes through here, so lists refresh and the gallery stays in sync. */
class FileActions(private val context: Context, private val graph: Graph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private val _operation = MutableStateFlow<Operation?>(null)
    val operation: StateFlow<Operation?> = _operation.asStateFlow()

    /** Increments after every change; screens reload when it does. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private val _clip = MutableStateFlow<Clip?>(null)
    val clip: StateFlow<Clip?> = _clip.asStateFlow()

    /** Files waiting to be hidden until the hidden folder is unlocked. */
    val pendingVault = MutableStateFlow<List<String>>(emptyList())

    fun notify(text: String) {
        _messages.tryEmit(UserMessage(text))
    }

    fun cancel() {
        job?.cancel()
    }

    fun refresh() {
        _changes.value++
    }

    private fun run(
        title: String,
        totalBytes: Long = 0,
        block: suspend (onBytes: (Long) -> Unit, isCancelled: () -> Boolean) -> UserMessage?,
    ) {
        if (job?.isActive == true) {
            notify("Wait until the current task is done")
            return
        }
        job = scope.launch {
            _operation.value = Operation(title, if (totalBytes > 0) 0f else null)
            try {
                val message = withContext(Dispatchers.IO) {
                    val ctx = currentCoroutineContext()
                    var done = 0L
                    var lastUpdate = 0L
                    block(
                        { n ->
                            done += n
                            val now = System.nanoTime()
                            if (totalBytes > 0 && now - lastUpdate > 80_000_000L) {
                                lastUpdate = now
                                _operation.value = Operation(title, (done.toFloat() / totalBytes).coerceIn(0f, 1f))
                            }
                        },
                        { !ctx.isActive },
                    )
                }
                message?.let { _messages.tryEmit(it) }
            } catch (_: CancellationException) {
                notify("Cancelled")
            } catch (e: Exception) {
                notify(e.message ?: "Something went wrong")
            } finally {
                _operation.value = null
                _changes.value++
            }
        }
    }

    private fun items(n: Int) = if (n == 1) "1 item" else "$n items"

    private fun files(paths: Collection<String>) = paths.map(::File)

    // ---- Trash ----

    fun moveToTrash(paths: Collection<String>) {
        val list = files(paths)
        run("Moving to trash") { _, _ ->
            val trashed = list.map { graph.trash.moveToTrash(it) }
            graph.favorites.remove(paths)
            graph.review.forget(paths.toSet())
            Opener.notifyMedia(context, paths)
            UserMessage("Moved ${items(trashed.size)} to trash", "Undo") { restoreFromTrash(trashed) }
        }
    }

    fun restoreFromTrash(items: List<TrashItem>) {
        run("Restoring") { _, _ ->
            val restored = items.map { graph.trash.restore(it).absolutePath }
            Opener.notifyMedia(context, restored)
            UserMessage("Restored ${items(restored.size)}")
        }
    }

    fun deleteForever(items: List<TrashItem>) {
        run("Deleting") { _, _ ->
            items.forEach { graph.trash.delete(it) }
            UserMessage("Deleted ${items(items.size)} for good")
        }
    }

    fun emptyTrash() {
        run("Emptying trash") { _, _ ->
            val n = graph.trash.list().size
            graph.trash.empty()
            UserMessage("Trash emptied ($n)")
        }
    }

    // ---- Single-file changes ----

    fun rename(path: String, newName: String) {
        run("Renaming") { _, _ ->
            val renamed = FileOps.rename(File(path), newName)
            graph.favorites.replace(path, renamed.absolutePath)
            Opener.notifyMedia(context, listOf(path, renamed.absolutePath))
            null
        }
    }

    fun createFolder(parent: File, name: String) {
        run("Creating folder") { _, _ ->
            FileOps.createFolder(parent, name)
            null
        }
    }

    // ---- Copy / move ----

    fun setClip(mode: ClipMode, paths: Collection<String>) {
        // No message here: it would cover the Paste bar, which already explains what to do.
        _clip.value = Clip(mode, paths.toList())
    }

    fun clearClip() {
        _clip.value = null
    }

    fun paste(destDir: File) {
        val clip = _clip.value ?: return
        val sources = files(clip.paths)
        val total = sources.sumOf { FileOps.totalSize(it) }
        val copying = clip.mode == ClipMode.COPY
        run(if (copying) "Copying" else "Moving", total) { onBytes, cancelled ->
            val created = mutableListOf<String>()
            for (src in sources) {
                val target = if (copying) {
                    FileOps.copy(src, destDir, onBytes, cancelled)
                } else {
                    FileOps.move(src, destDir, onBytes, cancelled).also {
                        graph.favorites.replace(src.absolutePath, it.absolutePath)
                    }
                }
                created += target.absolutePath
            }
            _clip.value = null
            Opener.notifyMedia(context, created + if (copying) emptyList() else clip.paths)
            UserMessage(if (copying) "Copied ${items(created.size)}" else "Moved ${items(created.size)}")
        }
    }

    // ---- Zip ----

    fun compress(paths: Collection<String>, destDir: File) {
        val sources = files(paths)
        run("Compressing", sources.sumOf { FileOps.totalSize(it) }) { onBytes, cancelled ->
            val zip = ZipUtils.compress(sources, destDir, onBytes = onBytes, isCancelled = cancelled)
            Opener.notifyMedia(context, listOf(zip.absolutePath))
            UserMessage("Created ${zip.name}")
        }
    }

    fun extract(path: String) {
        val zip = File(path)
        run("Extracting", zip.length()) { onBytes, cancelled ->
            val folder = ZipUtils.extract(zip, zip.parentFile!!, onBytes, cancelled)
            Opener.notifyMedia(context, listOf(folder.absolutePath))
            UserMessage("Extracted to ${folder.name}")
        }
    }

    // ---- Apps ----

    fun saveApk(app: AppInfo) {
        run("Saving ${app.label}", app.appSize) { _, _ ->
            val file = AppActions.saveApk(app, graph.downloadsDir)
            Opener.notifyMedia(context, listOf(file.absolutePath))
            UserMessage("Saved ${file.name} to Download")
        }
    }

    // ---- Hidden folder ----

    fun moveToVault(paths: Collection<String>, onDone: () -> Unit = {}) {
        val sources = files(paths).filter { it.isFile }
        if (sources.isEmpty()) {
            notify("Only files can go into the hidden folder")
            return
        }
        run("Moving to hidden folder", sources.sumOf { it.length() } * 2) { onBytes, _ ->
            val count = graph.vault.addAll(sources, deleteOriginal = true) { onBytes(it * 2) }.size
            graph.favorites.remove(sources.map { it.absolutePath })
            graph.review.forget(paths.toSet())
            Opener.notifyMedia(context, sources.map { it.absolutePath })
            withContext(Dispatchers.Main) { onDone() }
            UserMessage("Moved ${items(count)} to the hidden folder")
        }
    }

    fun restoreFromVault(items: List<VaultItem>) {
        run("Restoring", items.sumOf { it.size }) { _, _ ->
            val restored = graph.vault.restoreAll(items, graph.downloadsDir).map { it.absolutePath }
            Opener.notifyMedia(context, restored)
            UserMessage("Restored ${items(restored.size)}")
        }
    }

    fun deleteFromVault(items: List<VaultItem>) {
        run("Deleting") { _, _ ->
            graph.vault.deleteAll(items)
            UserMessage("Deleted ${items(items.size)} for good")
        }
    }
}
