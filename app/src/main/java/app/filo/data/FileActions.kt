package app.filo.data

import android.content.Context
import app.filo.Graph
import app.filo.net.RemoteFile
import app.filo.net.RemotePath
import app.filo.net.Server
import app.filo.net.ServerSessions.Channel
import app.filo.vault.VaultItem
import app.filo.vault.VaultManager
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
data class Operation(val title: String, val progress: Float?, val detail: String? = null)

/** "12.3 MB/s · about 40 s left" once a transfer has been running for a moment. */
internal fun transferDetail(done: Long, total: Long, elapsedNanos: Long): String? {
    if (elapsedNanos < 1_500_000_000L || done <= 0) return null
    val perSecond = done * 1e9 / elapsedNanos
    val left = ((total - done).coerceAtLeast(0) / perSecond).toLong()
    val speed = if (perSecond >= 1e6) "%.1f MB/s".format(perSecond / 1e6) else "%.0f kB/s".format(perSecond / 1e3)
    val remaining = when {
        left < 5 -> "almost done"
        left < 60 -> "about $left s left"
        left < 3600 -> "about ${(left + 30) / 60} min left"
        else -> "about ${left / 3600} h ${(left % 3600) / 60} min left"
    }
    return "$speed · $remaining"
}

data class UserMessage(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

enum class ClipMode { COPY, MOVE }

/** Files waiting for Paste. [server] is set when they are on a file server ([remote] then lists them). */
data class Clip(
    val mode: ClipMode,
    val paths: List<String>,
    val server: Server? = null,
    val remote: List<RemoteFile> = emptyList(),
)

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

    /** Files and folders waiting to be hidden until the hidden folder is unlocked. */
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
        measure: (suspend () -> Long)? = null,
        block: suspend (onBytes: (Long) -> Unit, isCancelled: () -> Boolean) -> UserMessage?,
    ) {
        if (job?.isActive == true) {
            notify("Wait until the current task is done")
            return
        }
        job = scope.launch {
            _operation.value = Operation(title, if (totalBytes > 0 || measure != null) 0f else null)
            try {
                val message = withContext(Dispatchers.IO) {
                    val ctx = currentCoroutineContext()
                    val total = measure?.invoke() ?: totalBytes
                    var done = 0L
                    var lastUpdate = 0L
                    val started = System.nanoTime()
                    block(
                        { n ->
                            done += n
                            val now = System.nanoTime()
                            if (total > 0 && now - lastUpdate > 80_000_000L) {
                                lastUpdate = now
                                _operation.value = Operation(title, (done.toFloat() / total).coerceIn(0f, 1f), transferDetail(done, total, now - started))
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
            val trashed = mutableListOf<TrashItem>()
            val problem = try {
                list.forEach { trashed += graph.trash.moveToTrash(it) }
                null
            } catch (e: Exception) {
                e
            }
            // Also when only part of it worked: what is in the trash must leave favorites and the gallery.
            val gone = trashed.map { it.originalPath }
            graph.favorites.remove(gone)
            graph.review.forget(gone.toSet())
            Opener.notifyMedia(context, gone)
            if (problem != null && trashed.isEmpty()) throw problem
            val done = "Moved ${items(trashed.size)} to trash"
            UserMessage(if (problem == null) done else "$done. ${problem.message ?: "The rest couldn't be moved."}", "Undo") { restoreFromTrash(trashed) }
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
        if (clip.server != null) {
            downloadClip(clip, clip.server, destDir)
            return
        }
        val sources = files(clip.paths)
        val copying = clip.mode == ClipMode.COPY
        run(if (copying) "Copying" else "Moving", measure = { sources.sumOf { FileOps.totalSize(it) } }) { onBytes, cancelled ->
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

    /** Drag and drop: moves files and folders into [destDir], with Undo. */
    fun moveInto(paths: Collection<String>, destDir: File) {
        val sources = files(paths).filter { it.exists() && it.parentFile?.absolutePath != destDir.absolutePath }
        if (sources.isEmpty()) return
        if (sources.any { it.isDirectory && FileOps.isSameOrInside(destDir, it) }) {
            notify("A folder can't be moved into itself")
            return
        }
        val name = graph.volumes().firstOrNull { it.root.absolutePath == destDir.absolutePath }?.label ?: destDir.name
        run("Moving", measure = { sources.sumOf { FileOps.totalSize(it) } }) { onBytes, cancelled ->
            val moved = mutableListOf<Pair<File, File>>() // where it was → where it is now
            try {
                for (src in sources) {
                    val target = FileOps.move(src, destDir, onBytes, cancelled)
                    graph.favorites.replace(src.absolutePath, target.absolutePath)
                    moved += src to target
                }
            } finally {
                Opener.notifyMedia(context, moved.flatMap { (from, to) -> listOf(from.absolutePath, to.absolutePath) })
            }
            UserMessage("Moved ${items(moved.size)} to $name", "Undo") { moveBack(moved) }
        }
    }

    private fun moveBack(moved: List<Pair<File, File>>) {
        run("Moving back") { _, cancelled ->
            val back = moved.map { (from, now) ->
                FileOps.move(now, from.parentFile!!, {}, cancelled).also { graph.favorites.replace(now.absolutePath, it.absolutePath) }
            }
            Opener.notifyMedia(context, moved.map { it.second.absolutePath } + back.map { it.absolutePath })
            UserMessage("Moved ${items(back.size)} back")
        }
    }

    // ---- Zip ----

    fun compress(paths: Collection<String>, destDir: File) {
        val sources = files(paths)
        run("Compressing", measure = { sources.sumOf { FileOps.totalSize(it) } }) { onBytes, cancelled ->
            val zip = ZipUtils.compress(sources, destDir, onBytes = onBytes, isCancelled = cancelled)
            Opener.notifyMedia(context, listOf(zip.absolutePath))
            UserMessage("Created ${zip.name}")
        }
    }

    fun extract(path: String) {
        val zip = File(path)
        run("Extracting", measure = { ZipUtils.unpackedSize(zip) }) { onBytes, cancelled ->
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

    /** Hides files and whole folders into [into], a folder inside the hidden folder ("" is the top). */
    fun moveToVault(paths: Collection<String>, into: String = graph.vault.openFolder.value, onDone: () -> Unit = {}) {
        val protected = setOf(graph.storageRoot, File(graph.storageRoot, "Android")).map { it.absolutePath }
        if (paths.any { File(it).absolutePath in protected }) {
            notify("Internal storage and its Android folder can't be hidden")
            return
        }
        val sources = files(paths).filter { it.exists() }
        if (sources.isEmpty()) return
        var contained: List<File> = emptyList()
        run("Moving to hidden folder", measure = {
            contained = sources.flatMap { if (it.isDirectory) it.walkTopDown().toList() else listOf(it) }
            contained.filter { it.isFile }.sumOf { it.length() }
        }) { onBytes, _ ->
            try {
                graph.vault.hide(sources, into, onBytes)
            } finally {
                // Also when only part of it worked: whatever left normal storage must leave the gallery too.
                val gone = contained.map { it.absolutePath }.filterNot { File(it).exists() }
                graph.favorites.remove(gone)
                graph.review.forget(gone.toSet())
                Opener.notifyMedia(context, gone)
            }
            withContext(Dispatchers.Main) { onDone() }
            UserMessage("Moved ${items(sources.size)} to the hidden folder")
        }
    }

    /** Moves hidden files and folders back to normal storage (where they came from, or Download). */
    fun restoreFromVault(items: List<VaultItem>, folders: List<String> = emptyList()) {
        if (items.isEmpty() && folders.isEmpty()) return
        run("Moving out of hidden folder") { _, _ ->
            val restored = graph.vault.restoreEverything(items, folders, graph.downloadsDir)
            Opener.notifyMedia(context, restored.flatMap { f -> f.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList() })
            UserMessage("Moved ${items(items.size + folders.size)} out of the hidden folder")
        }
    }

    fun deleteFromVault(items: List<VaultItem>, folders: List<String> = emptyList()) {
        if (items.isEmpty() && folders.isEmpty()) return
        run("Deleting") { _, _ ->
            graph.vault.deleteEverything(items, folders)
            UserMessage("Deleted ${items(items.size + folders.size)} for good")
        }
    }

    fun createVaultFolder(parent: String, name: String, onCreated: (String) -> Unit = {}) {
        run("Creating folder") { _, _ ->
            val path = graph.vault.createFolder(parent, name.trim())
            withContext(Dispatchers.Main) { onCreated(path) }
            null
        }
    }

    fun renameInVault(item: VaultItem?, folder: String?, newName: String) {
        run("Renaming") { _, _ ->
            if (item != null) graph.vault.renameItem(item.id, newName.trim())
            if (folder != null) graph.vault.renameFolder(folder, newName.trim())
            null
        }
    }

    /** Moves hidden files and folders to another folder inside the hidden folder, with Undo. */
    fun moveInVault(items: List<VaultItem>, folders: List<String>, destination: String, onDone: () -> Unit = {}) {
        val moving = items.filter { it.folder != destination }
        val movingFolders = folders.filter { VaultManager.parentOf(it) != destination }
        if (moving.isEmpty() && movingFolders.isEmpty()) return
        if (movingFolders.any { VaultManager.isInside(destination, it) }) {
            notify("A folder can't be moved into itself")
            return
        }
        run("Moving") { _, _ ->
            val movedTo = graph.vault.move(moving.map { it.id }, movingFolders, destination)
            withContext(Dispatchers.Main) { onDone() }
            val name = if (destination.isEmpty()) "Hidden folder" else VaultManager.nameOf(destination)
            UserMessage("Moved ${items(moving.size + movingFolders.size)} to $name", "Undo") {
                // Back to where each one was.
                run("Moving back") { _, _ ->
                    moving.groupBy { it.folder }.forEach { (from, list) -> graph.vault.move(list.map { it.id }, emptyList(), from) }
                    movedTo.entries.groupBy { VaultManager.parentOf(it.key) }.forEach { (from, list) ->
                        graph.vault.move(emptyList(), list.map { it.value }, from)
                    }
                    null
                }
            }
        }
    }
    // ---- File servers ----

    fun setRemoteClip(mode: ClipMode, server: Server, files: List<RemoteFile>) {
        _clip.value = Clip(mode, files.map { it.path }, server, files)
    }

    private val sessions get() = graph.sessions

    private suspend fun remoteSize(server: Server, files: List<RemoteFile>, isCancelled: () -> Boolean): Long = files.sumOf { f ->
        if (isCancelled()) throw CancellationException("Cancelled")
        if (f.isDirectory) remoteSize(server, sessions.use(server, Channel.TRANSFER) { it.list(f.path) }, isCancelled) else f.size
    }

    private suspend fun names(server: Server, folder: String): MutableSet<String> =
        sessions.use(server, Channel.TRANSFER) { fs -> fs.list(folder).map { it.name } }.toMutableSet()

    /** Server → phone. Folders come with everything inside. */
    private suspend fun download(server: Server, file: RemoteFile, destDir: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean): File {
        val target = FileOps.uniqueTarget(destDir, file.name)
        try {
            if (file.isDirectory) {
                if (!target.mkdirs()) throw java.io.IOException("Couldn't create ${target.name}")
                for (child in sessions.use(server, Channel.TRANSFER) { it.list(file.path) }) download(server, child, target, onBytes, cancelled)
            } else {
                target.outputStream().buffered().use { out ->
                    sessions.use(server, Channel.TRANSFER) { it.read(file.path, out, onBytes, cancelled) }
                }
            }
        } catch (e: Throwable) {
            target.deleteRecursively() // never leave half-copied files behind
            throw e
        }
        return target
    }

    /** Phone → server. [taken] are the names already in [destPath]. */
    private suspend fun upload(server: Server, src: File, destPath: String, taken: MutableSet<String>, onBytes: (Long) -> Unit, cancelled: () -> Boolean): String {
        val name = RemotePath.unique(taken, src.name)
        taken += name
        val target = RemotePath.join(destPath, name)
        try {
            if (src.isDirectory) {
                sessions.use(server, Channel.TRANSFER) { it.mkdir(target) }
                val inside = mutableSetOf<String>()
                src.listFiles()?.forEach { upload(server, it, target, inside, onBytes, cancelled) }
            } else {
                src.inputStream().buffered().use { input ->
                    sessions.use(server, Channel.TRANSFER) { it.write(target, input, onBytes, cancelled) }
                }
            }
        } catch (e: Throwable) {
            runCatching { sessions.use(server, Channel.TRANSFER) { it.delete(target, src.isDirectory) } }
            throw e
        }
        return target
    }

    /** Server → server (or within one), through a temporary file on the phone. */
    private suspend fun copyRemote(
        from: Server,
        file: RemoteFile,
        to: Server,
        destPath: String,
        taken: MutableSet<String>,
        onBytes: (Long) -> Unit,
        cancelled: () -> Boolean,
    ): String {
        val name = RemotePath.unique(taken, file.name)
        taken += name
        val target = RemotePath.join(destPath, name)
        if (file.isDirectory) {
            sessions.use(to, Channel.TRANSFER) { it.mkdir(target) }
            val inside = mutableSetOf<String>()
            for (child in sessions.use(from, Channel.TRANSFER) { it.list(file.path) }) copyRemote(from, child, to, target, inside, onBytes, cancelled)
            return target
        }
        val temp = File.createTempFile("filo", ".part", context.cacheDir)
        try {
            temp.outputStream().buffered().use { out -> sessions.use(from, Channel.TRANSFER) { it.read(file.path, out, {}, cancelled) } }
            temp.inputStream().buffered().use { input -> sessions.use(to, Channel.TRANSFER) { it.write(target, input, onBytes, cancelled) } }
        } catch (e: Throwable) {
            runCatching { sessions.use(to, Channel.TRANSFER) { it.delete(target, false) } }
            throw e
        } finally {
            temp.delete()
        }
        return target
    }

    private fun downloadClip(clip: Clip, server: Server, destDir: File) {
        val moving = clip.mode == ClipMode.MOVE
        run(if (moving) "Moving" else "Copying", measure = { remoteSize(server, clip.remote) { false } }) { onBytes, cancelled ->
            val created = clip.remote.map { download(server, it, destDir, onBytes, cancelled) }
            if (moving) clip.remote.forEach { f -> sessions.use(server, Channel.TRANSFER) { it.delete(f.path, f.isDirectory) } }
            _clip.value = null
            Opener.notifyMedia(context, created.flatMap { f -> f.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList() })
            UserMessage("${if (moving) "Moved" else "Copied"} ${items(created.size)} to ${destDir.name}")
        }
    }

    /** Saves files (and folders) from a server into the phone's Download folder. */
    fun downloadFromServer(server: Server, files: List<RemoteFile>, onShow: ((File) -> Unit)? = null) {
        if (files.isEmpty()) return
        val dest = graph.downloadsDir
        run("Downloading", measure = { remoteSize(server, files) { false } }) { onBytes, cancelled ->
            if (!dest.isDirectory && !dest.mkdirs()) throw java.io.IOException("Couldn't open the Download folder")
            val created = files.map { download(server, it, dest, onBytes, cancelled) }
            Opener.notifyMedia(context, created.flatMap { f -> f.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList() })
            UserMessage("Saved ${items(created.size)} to Download", if (onShow != null) "Show" else null) { onShow?.invoke(created.first()) }
        }
    }

    /** Paste bar inside a server folder: files from the phone, this server or another one. */
    fun pasteToServer(server: Server, destPath: String) {
        val clip = _clip.value ?: return
        val moving = clip.mode == ClipMode.MOVE
        val source = clip.server
        if (source != null && clip.remote.any { it.isDirectory && RemotePath.isInside(destPath, it.path) && source.id == server.id }) {
            notify("A folder can't be moved into itself")
            return
        }
        val renaming = source != null && source.id == server.id && moving && canRename(server, clip.remote, destPath)
        val measure: suspend () -> Long = {
            when {
                source == null -> files(clip.paths).sumOf { FileOps.totalSize(it) }
                renaming -> 0L
                else -> remoteSize(source, clip.remote) { false }
            }
        }
        run(if (moving) "Moving" else "Copying", measure = measure) { onBytes, cancelled ->
            val taken = names(server, destPath)
            val count = when {
                source == null -> {
                    val files = files(clip.paths).filter { it.exists() }
                    files.forEach { upload(server, it, destPath, taken, onBytes, cancelled) }
                    if (moving) {
                        files.forEach { it.deleteRecursively() }
                        graph.favorites.remove(clip.paths)
                        graph.review.forget(clip.paths.toSet())
                        Opener.notifyMedia(context, clip.paths)
                    }
                    files.size
                }
                renaming -> {
                    clip.remote.filter { RemotePath.parent(it.path) != destPath }.forEach { f ->
                        val name = RemotePath.unique(taken, f.name)
                        taken += name
                        sessions.use(server, Channel.TRANSFER) { it.rename(f.path, RemotePath.join(destPath, name)) }
                    }
                    clip.remote.size
                }
                else -> {
                    clip.remote.forEach { copyRemote(source, it, server, destPath, taken, onBytes, cancelled) }
                    if (moving) clip.remote.forEach { f -> sessions.use(source, Channel.TRANSFER) { it.delete(f.path, f.isDirectory) } }
                    clip.remote.size
                }
            }
            _clip.value = null
            UserMessage("${if (moving) "Moved" else "Copied"} ${items(count)} to ${RemotePath.name(destPath).ifEmpty { server.displayName }}")
        }
    }

    /** SMB can only rename inside one share; between shares it's copy and delete. */
    private fun canRename(server: Server, files: List<RemoteFile>, destPath: String): Boolean =
        server.protocol != app.filo.net.Protocol.SMB || files.all { it.path.substringBefore('/') == destPath.substringBefore('/') && destPath.isNotEmpty() }

    /** Drag and drop inside a server, with Undo. */
    fun moveOnServer(server: Server, files: List<RemoteFile>, destPath: String, onDone: () -> Unit = {}) {
        val moving = files.filter { RemotePath.parent(it.path) != destPath }
        if (moving.isEmpty()) return
        if (moving.any { it.isDirectory && RemotePath.isInside(destPath, it.path) }) {
            notify("A folder can't be moved into itself")
            return
        }
        if (!canRename(server, moving, destPath)) {
            setRemoteClip(ClipMode.MOVE, server, moving)
            pasteToServer(server, destPath)
            return
        }
        run("Moving") { _, _ ->
            val taken = names(server, destPath)
            val moved = moving.map { f ->
                val name = RemotePath.unique(taken, f.name)
                taken += name
                val target = RemotePath.join(destPath, name)
                sessions.use(server, Channel.TRANSFER) { it.rename(f.path, target) }
                f.path to target
            }
            withContext(Dispatchers.Main) { onDone() }
            UserMessage("Moved ${items(moved.size)} to ${RemotePath.name(destPath).ifEmpty { server.displayName }}", "Undo") {
                run("Moving back") { _, _ ->
                    moved.forEach { (from, now) -> sessions.use(server, Channel.TRANSFER) { it.rename(now, from) } }
                    null
                }
            }
        }
    }

    fun deleteOnServer(server: Server, files: List<RemoteFile>, onDone: () -> Unit = {}) {
        run("Deleting") { _, cancelled ->
            files.forEach { f ->
                if (cancelled()) throw CancellationException("Cancelled")
                sessions.use(server, Channel.TRANSFER) { it.delete(f.path, f.isDirectory) }
            }
            withContext(Dispatchers.Main) { onDone() }
            UserMessage("Deleted ${items(files.size)} from ${server.displayName}")
        }
    }

    fun renameOnServer(server: Server, file: RemoteFile, newName: String) {
        run("Renaming") { _, _ ->
            val parent = RemotePath.parent(file.path)
            if (newName != file.name && newName in names(server, parent)) throw java.io.IOException("That name is already taken")
            sessions.use(server, Channel.TRANSFER) { it.rename(file.path, RemotePath.join(parent, newName)) }
            null
        }
    }

    fun createFolderOnServer(server: Server, parent: String, name: String) {
        run("Creating folder") { _, _ ->
            if (name in names(server, parent)) throw java.io.IOException("That name is already taken")
            sessions.use(server, Channel.TRANSFER) { it.mkdir(RemotePath.join(parent, name)) }
            null
        }
    }

    /** Downloads files to Filo's private cache to open or share them; those copies are deleted next time Filo starts. */
    fun openFromServer(server: Server, files: List<RemoteFile>, chooser: Boolean = false, share: Boolean = false) {
        if (files.isEmpty()) return
        run("Downloading", files.sumOf { it.size }) { onBytes, cancelled ->
            val dir = File(context.cacheDir, "net_open/" + java.util.UUID.randomUUID())
            val local = files.map { f ->
                val target = File(dir, f.name).apply { parentFile!!.mkdirs() }
                try {
                    target.outputStream().buffered().use { out -> sessions.use(server, Channel.TRANSFER) { it.read(f.path, out, onBytes, cancelled) } }
                } catch (e: Throwable) {
                    target.delete()
                    throw e
                }
                target
            }
            withContext(Dispatchers.Main) {
                when {
                    share -> Opener.share(context, local)
                    !Opener.open(context, local.first(), chooser) -> notify("No app on your phone can open this file")
                }
            }
            null
        }
    }
}
