package app.filo.vault

import app.filo.data.ExactBytes
import app.filo.data.FileKind
import app.filo.data.FileOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

data class VaultItem(
    val id: String,
    val name: String,
    val size: Long,
    val addedAt: Long,
    val originalPath: String,
    /** Folder inside the hidden folder, like "Trips/2026". "" is the top. */
    val folder: String = "",
) {
    val kind: FileKind get() = FileKind.ofName(name)
}

sealed interface UnlockResult {
    data object Success : UnlockResult
    data class WrongPin(val attemptsBeforeLockout: Int) : UnlockResult
    data class LockedOut(val untilMillis: Long) : UnlockResult
}

/**
 * The hidden folder.
 *
 * - Files are encrypted (AES-256-GCM) with a random 256-bit folder key and kept in Filo's private app
 *   storage, where no other app, gallery or file manager can see them.
 * - The folder key is sealed twice: with a key derived from your 6-digit PIN, and then with a
 *   hardware key that never leaves this phone. Fingerprint unlock keeps a second copy sealed by a
 *   key that only works right after a fingerprint touch.
 * - Wrong PINs: 5 free tries, then waits of 30 s, 1 min, 2 min... up to 1 hour.
 * - Even file names are encrypted.
 */
class VaultManager(
    private val dir: File,
    private val tempDir: File,
    private val deviceKey: KeyWrap,
    private val kdf: PinKdf = PinKdf(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val PIN_LENGTH = 6

        fun parentOf(folder: String) = folder.substringBeforeLast('/', "")
        fun nameOf(folder: String) = folder.substringAfterLast('/')
        fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
        fun isInside(path: String, folder: String) = folder.isEmpty() || path == folder || path.startsWith("$folder/")
        const val FREE_ATTEMPTS = 5
        private const val MAX_LOCKOUT = 60 * 60 * 1000L

        fun isValidPin(pin: String) = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

        /** 30 s after the 5th wrong PIN, doubling each time, at most 1 hour. */
        fun lockoutDelay(failures: Int): Long {
            if (failures < FREE_ATTEMPTS) return 0
            val steps = (failures - FREE_ATTEMPTS).coerceAtMost(10)
            return (30_000L shl steps).coerceAtMost(MAX_LOCKOUT)
        }
    }

    private val random = SecureRandom()
    private val keysFile = File(dir, "keys.properties")
    private val indexFile = File(dir, "index.bin")
    private val blobDir = File(dir, "blobs")

    private var folderKeyBytes: ByteArray? = null
    private var folderKey: SecretKey? = null

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()

    private val _setUp = MutableStateFlow(keysFile.exists())
    val setUp: StateFlow<Boolean> = _setUp.asStateFlow()

    val isSetUp: Boolean get() = keysFile.exists()

    @Synchronized
    fun setup(pin: String) {
        check(!isSetUp) { "Hidden folder already exists" }
        require(isValidPin(pin)) { "PIN must be $PIN_LENGTH digits" }
        if (!blobDir.isDirectory && !blobDir.mkdirs()) throw IOException("Couldn't create the hidden folder")
        val raw = ByteArray(32).also(random::nextBytes)
        val props = Properties()
        sealWithPin(props, pin, raw)
        props.setProperty("failures", "0")
        save(props)
        open(raw)
        writeIndex(Index(), key())
        _setUp.value = true
    }

    private fun sealWithPin(props: Properties, pin: String, raw: ByteArray) {
        val salt = ByteArray(16).also(random::nextBytes)
        val pinKey = kdf.derive(pin, salt)
        val blob = deviceKey.wrap(Gcm.seal(pinKey, raw))
        props.setProperty("pin.salt", b64(salt))
        props.setProperty("pin.iterations", kdf.iterations.toString())
        props.setProperty("pin.blob", b64(blob))
    }

    fun lockedUntil(): Long = load().getProperty("lockedUntil")?.toLongOrNull() ?: 0L

    @Synchronized
    fun unlockWithPin(pin: String): UnlockResult {
        val props = load()
        val now = clock()
        val lockedUntil = props.getProperty("lockedUntil")?.toLongOrNull() ?: 0L
        if (now < lockedUntil) return UnlockResult.LockedOut(lockedUntil)

        val raw = if (isValidPin(pin)) openWithPin(props, pin) else null
        if (raw == null) {
            val failures = (props.getProperty("failures")?.toIntOrNull() ?: 0) + 1
            props.setProperty("failures", failures.toString())
            val delay = lockoutDelay(failures)
            return if (delay > 0) {
                props.setProperty("lockedUntil", (now + delay).toString())
                save(props)
                UnlockResult.LockedOut(now + delay)
            } else {
                save(props)
                UnlockResult.WrongPin(FREE_ATTEMPTS - failures)
            }
        }
        resetFailures(props)
        open(raw)
        return UnlockResult.Success
    }

    /** null means the PIN was wrong. Other failures (broken keystore) throw. */
    private fun openWithPin(props: Properties, pin: String): ByteArray? {
        val salt = unb64(props.getProperty("pin.salt"))
        val iterations = props.getProperty("pin.iterations").toInt()
        val inner = deviceKey.unwrap(unb64(props.getProperty("pin.blob")))
        return try {
            Gcm.open(PinKdf(iterations).derive(pin, salt), inner)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    @Synchronized
    fun changePin(currentPin: String, newPin: String): Boolean {
        require(isValidPin(newPin)) { "PIN must be $PIN_LENGTH digits" }
        val props = load()
        val raw = openWithPin(props, currentPin) ?: return false
        sealWithPin(props, newPin, raw)
        save(props)
        return true
    }

    // ---- Fingerprint ----

    val biometricEnabled: Boolean get() = load().getProperty("bio.blob") != null

    fun biometricIv(): ByteArray? = load().getProperty("bio.iv")?.let(::unb64)

    /** [cipher] comes from a successful fingerprint prompt (encrypt mode). */
    @Synchronized
    fun enableBiometric(cipher: Cipher) {
        val raw = folderKeyBytes ?: throw IllegalStateException("Unlock the hidden folder first")
        val blob = cipher.doFinal(raw)
        val props = load()
        props.setProperty("bio.iv", b64(cipher.iv))
        props.setProperty("bio.blob", b64(blob))
        save(props)
    }

    @Synchronized
    fun disableBiometric() {
        val props = load()
        props.remove("bio.iv")
        props.remove("bio.blob")
        save(props)
    }

    /** [cipher] comes from a successful fingerprint prompt (decrypt mode). */
    @Synchronized
    fun unlockWithBiometric(cipher: Cipher): Boolean {
        val props = load()
        val blob = props.getProperty("bio.blob")?.let(::unb64) ?: return false
        val raw = try {
            cipher.doFinal(blob)
        } catch (_: GeneralSecurityException) {
            return false
        }
        resetFailures(props)
        open(raw)
        return true
    }

    private fun resetFailures(props: Properties) {
        props.setProperty("failures", "0")
        props.remove("lockedUntil")
        save(props)
    }

    // ---- Open / lock ----

    private fun open(raw: ByteArray) {
        folderKeyBytes = raw
        val key = SecretKeySpec(raw, "AES")
        folderKey = key
        publish(readIndex(key), key)
        _unlocked.value = true
        clearTemp()
    }

    @Synchronized
    fun lock() {
        folderKeyBytes?.fill(0)
        folderKeyBytes = null
        folderKey = null
        _items.value = emptyList()
        _folders.value = emptySet()
        openFolder.value = ""
        _unlocked.value = false
    }

    private fun key(): SecretKey = folderKey ?: throw IllegalStateException("The hidden folder is locked")

    // ---- Folders inside the hidden folder ----
    //
    // Folders are paths like "Trips/2026" ("" is the top). They live in the encrypted index, so their
    // names are as private as file names.

    private val _folders = MutableStateFlow<Set<String>>(emptySet())
    val folders: StateFlow<Set<String>> = _folders.asStateFlow()

    /** The folder you're looking at inside the hidden folder. Kept in memory only; back to the top on lock. */
    val openFolder = MutableStateFlow("")

    /** Where hidden folders came from, so moving one back out puts it in the same place. */
    private data class Index(
        val items: List<VaultItem> = emptyList(),
        val folders: Set<String> = emptySet(),
        val origins: Map<String, String> = emptyMap(),
    )

    private fun publish(index: Index, key: SecretKey) {
        if (folderKey === key) {
            _items.value = index.items
            _folders.value = index.folders
        }
    }

    /** Reads the list from disk, changes it, saves it. Nothing is lost if the folder locked meanwhile. */
    @Synchronized
    private fun update(key: SecretKey, change: (Index) -> Index): Index {
        val next = change(readIndex(key))
        writeIndex(next, key)
        publish(next, key)
        return next
    }

    private fun withParents(folder: String): Set<String> {
        if (folder.isEmpty()) return emptySet()
        val parts = folder.split('/')
        return (1..parts.size).map { parts.take(it).joinToString("/") }.toSet()
    }

    private fun uniqueFolder(existing: Set<String>, parent: String, name: String): String {
        var candidate = join(parent, name)
        var i = 1
        while (candidate in existing) candidate = join(parent, "$name (${i++})")
        return candidate
    }

    private fun checkName(name: String) {
        if (!FileOps.isValidName(name)) throw IOException("That name isn't allowed")
    }

    fun createFolder(parent: String, name: String): String {
        checkName(name)
        var created = ""
        update(key()) { idx ->
            val path = join(parent, name)
            if (path in idx.folders) throw IOException("\"$name\" already exists")
            created = path
            idx.copy(folders = idx.folders + withParents(path))
        }
        return created
    }

    fun renameFolder(folder: String, newName: String) {
        require(folder.isNotEmpty())
        checkName(newName)
        update(key()) { idx ->
            val target = join(parentOf(folder), newName)
            if (target != folder && target in idx.folders) throw IOException("\"$newName\" already exists")
            fun moved(p: String) = if (isInside(p, folder)) target + p.removePrefix(folder) else p
            idx.copy(
                items = idx.items.map { it.copy(folder = moved(it.folder)) },
                folders = idx.folders.map(::moved).toSet(),
                origins = idx.origins.mapKeys { moved(it.key) },
            )
        }
    }

    fun renameItem(id: String, newName: String) {
        checkName(newName)
        update(key()) { idx -> idx.copy(items = idx.items.map { if (it.id == id) it.copy(name = newName) else it }) }
    }

    /**
     * Moves hidden files and folders into [destination], another folder in here.
     * Returns where each moved folder ended up (its name gets " (1)" if one with that name is already there).
     */
    fun move(itemIds: Collection<String>, folderPaths: Collection<String>, destination: String): Map<String, String> {
        val tops = folderPaths.filter { f -> f.isNotEmpty() && folderPaths.none { other -> other != f && isInside(f, other) } }
        val movedTo = mutableMapOf<String, String>()
        update(key()) { idx ->
            movedTo.clear()
            var folders = idx.folders + withParents(destination)
            var items = idx.items
            var origins = idx.origins
            for (f in tops) {
                if (isInside(destination, f)) throw IOException("A folder can't be moved into itself")
                if (parentOf(f) == destination) continue
                val target = uniqueFolder(folders, destination, nameOf(f))
                movedTo[f] = target
                fun moved(p: String) = if (isInside(p, f)) target + p.removePrefix(f) else p
                folders = folders.map(::moved).toSet()
                items = items.map { it.copy(folder = moved(it.folder)) }
                origins = origins.mapKeys { moved(it.key) }
            }
            val ids = itemIds.toSet()
            Index(items.map { if (it.id in ids) it.copy(folder = destination) else it }, folders, origins)
        }
        return movedTo
    }

    // ---- Files ----

    /**
     * Hides files and whole folders (with everything inside) into [into]. The folder key is taken
     * once at the start, so the batch finishes even if the folder locks itself meanwhile.
     * Returns how many files were hidden.
     */
    fun hide(sources: List<File>, into: String = "", onBytes: (Long) -> Unit = {}): Int {
        val key = key()
        var count = 0
        for (source in sources) {
            if (source.isDirectory) {
                count += hideFolder(source, into, key, onBytes)
            } else {
                add(source, deleteOriginal = true, key = key, folder = into, onBytes = onBytes)
                count++
            }
        }
        return count
    }

    /** Copies the folder structure and every file in; the original is removed only after all of it worked. */
    private fun hideFolder(dir: File, into: String, key: SecretKey, onBytes: (Long) -> Unit): Int {
        var top = ""
        update(key) { idx ->
            top = uniqueFolder(idx.folders, into, dir.name)
            val subfolders = dir.walkTopDown().filter { it.isDirectory && it != dir }.map { join(top, it.relativeTo(dir).path) }
            idx.copy(folders = idx.folders + withParents(top) + subfolders, origins = idx.origins + (top to dir.absolutePath))
        }
        val hidden = mutableListOf<File>()
        try {
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.parentFile!!.relativeTo(dir).path
                add(file, deleteOriginal = false, key = key, folder = if (relative.isEmpty()) top else join(top, relative), onBytes = onBytes)
                hidden += file
            }
        } catch (e: Throwable) {
            // Something failed: take the half-copied folder back out of here, the original stays untouched.
            try {
                deleteFolder(top, key)
            } catch (_: Throwable) {
            }
            throw e
        }
        // Only what was hidden: a photo saved into the folder meanwhile stays where it is.
        val kept = hidden.count { !it.delete() }
        dir.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() } // only empty folders go
        if (kept > 0) throw IOException("${dir.name} was hidden, but the original couldn't be fully deleted")
        return hidden.size
    }

    /** Hides several files into the top of the hidden folder. */
    fun addAll(sources: List<File>, deleteOriginal: Boolean = true, onBytes: (Long) -> Unit = {}): List<VaultItem> {
        val key = key()
        return sources.map { add(it, deleteOriginal, key, "", onBytes) }
    }

    /** Encrypts [source] into the hidden folder, checks the copy, then deletes the original. */
    fun add(
        source: File,
        deleteOriginal: Boolean = true,
        key: SecretKey = key(),
        folder: String = "",
        onBytes: (Long) -> Unit = {},
    ): VaultItem {
        if (!source.isFile) throw IOException("${source.name} isn't a file")
        val id = UUID.randomUUID().toString()
        val blob = File(blobDir, "$id.bin")
        try {
            source.inputStream().buffered().use { input ->
                writeSynced(blob) { output -> StreamCipher.encrypt(CountingInputStream(input, onBytes), output, key) }
            }
            // Read the encrypted copy back completely before touching the original.
            blob.inputStream().buffered().use { StreamCipher.decrypt(it, NullOutputStream, key) }
        } catch (e: Throwable) {
            blob.delete()
            throw e
        }
        val item = VaultItem(id, source.name, source.length(), clock(), source.absolutePath, folder)
        update(key) { idx -> idx.copy(items = idx.items + item, folders = idx.folders + withParents(folder)) }
        if (deleteOriginal && !source.delete()) throw IOException("${source.name} was hidden, but the original couldn't be deleted")
        return item
    }

    /** Decrypts [item] into [destDir] (keeps it in the hidden folder too). */
    fun export(item: VaultItem, destDir: File, key: SecretKey = key()): File {
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("Couldn't open ${destDir.name}")
        val target = FileOps.uniqueTarget(destDir, item.name)
        try {
            decryptTo(item, target, key)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        return target
    }

    /** Puts files back into normal storage (their old folder if it still exists). */
    fun restoreAll(items: List<VaultItem>, fallbackDir: File): List<File> {
        val key = key()
        return items.map { restore(it, fallbackDir, key) }
    }

    fun restore(item: VaultItem, fallbackDir: File, key: SecretKey = key()): File {
        val original = File(item.originalPath).parentFile
        val dest = if (original != null && original.isDirectory) original else fallbackDir
        val file = export(item, dest, key)
        delete(item, key)
        return file
    }

    /** Moves files and whole folders back out. Folders go back where they came from, or to [fallbackDir]. */
    fun restoreEverything(items: List<VaultItem>, folders: List<String>, fallbackDir: File): List<File> {
        val key = key()
        val tops = folders.filter { f -> folders.none { other -> other != f && isInside(f, other) } }
        val inSelectedFolders = items.filter { item -> tops.any { isInside(item.folder, it) } }
        return tops.map { restoreFolder(it, fallbackDir, key) } + (items - inSelectedFolders.toSet()).map { restore(it, fallbackDir, key) }
    }

    fun restoreFolder(folder: String, fallbackDir: File, key: SecretKey = key()): File {
        require(folder.isNotEmpty())
        val idx = readIndex(key)
        val origin = idx.origins[folder]?.let(::File)
        val parent = origin?.parentFile?.takeIf { it.isDirectory } ?: fallbackDir
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Couldn't open ${parent.name}")
        val target = FileOps.uniqueTarget(parent, nameOf(folder))
        if (!target.mkdirs()) throw IOException("Couldn't create ${target.name}")
        try {
            idx.folders.filter { isInside(it, folder) && it != folder }.forEach { File(target, it.removePrefix("$folder/")).mkdirs() }
            idx.items.filter { isInside(it.folder, folder) }.forEach { item ->
                val sub = if (item.folder == folder) target else File(target, item.folder.removePrefix("$folder/"))
                export(item, sub, key)
            }
        } catch (e: Throwable) {
            // Everything stays in the hidden folder; remove the half-written copy.
            target.deleteRecursively()
            throw e
        }
        deleteFolder(folder, key)
        return target
    }

    fun deleteAll(items: List<VaultItem>) {
        val key = key()
        items.forEach { delete(it, key) }
    }

    /** Deletes files and whole folders for good. */
    fun deleteEverything(items: List<VaultItem>, folders: List<String>) {
        val key = key()
        folders.forEach { deleteFolder(it, key) }
        items.forEach { delete(it, key) }
    }

    fun delete(item: VaultItem, key: SecretKey = key()) {
        update(key) { idx -> idx.copy(items = idx.items.filterNot { it.id == item.id }) }
        File(blobDir, "${item.id}.bin").delete()
    }

    fun deleteFolder(folder: String, key: SecretKey = key()) {
        require(folder.isNotEmpty())
        val removed = mutableListOf<VaultItem>()
        update(key) { idx ->
            val (gone, keep) = idx.items.partition { isInside(it.folder, folder) }
            removed += gone
            idx.copy(
                items = keep,
                folders = idx.folders.filterNot { isInside(it, folder) }.toSet(),
                origins = idx.origins.filterKeys { !isInside(it, folder) },
            )
        }
        removed.forEach { File(blobDir, "${it.id}.bin").delete() }
    }

    /** A key for remembering a folder's view settings that doesn't reveal the folder's name. */
    fun viewKey(folder: String): String {
        val raw = folderKeyBytes ?: return "vault:locked"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(raw, "HmacSHA256"))
        return "vault:" + mac.doFinal("view:$folder".toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }

    /** Small files (thumbnails, the image viewer) are decrypted in memory only. */
    fun readBytes(item: VaultItem, maxBytes: Long = 40_000_000L): ByteArray? {
        if (item.size > maxBytes) return null
        val out = ExactBytes(item.size)
        File(blobDir, "${item.id}.bin").inputStream().buffered().use { StreamCipher.decrypt(it, out, key()) }
        return out.bytes()
    }

    /**
     * For opening a file in another app (e.g. a video player) it has to be decrypted to Filo's
     * private cache. Those copies are deleted the next time the hidden folder is opened or locked.
     */
    fun decryptForViewing(item: VaultItem): File {
        val folder = File(tempDir, item.id)
        val target = File(folder, item.name)
        if (target.exists() && target.length() == item.size) return target
        folder.mkdirs()
        decryptTo(item, target, key())
        return target
    }

    fun clearTemp() {
        tempDir.deleteRecursively()
    }

    /** Forgot the PIN: everything inside is destroyed. There is no way to recover it. */
    @Synchronized
    fun erase() {
        lock()
        clearTemp()
        dir.deleteRecursively()
        _setUp.value = false
    }

    private fun decryptTo(item: VaultItem, target: File, key: SecretKey) {
        File(blobDir, "${item.id}.bin").inputStream().buffered().use { input ->
            target.outputStream().buffered().use { output -> StreamCipher.decrypt(input, output, key) }
        }
    }

    // ---- Encrypted index (names, folders, sizes, dates, origins) ----

    private fun writeIndex(index: Index, key: SecretKey) {
        val props = Properties()
        props.setProperty("count", index.items.size.toString())
        index.items.forEachIndexed { i, item ->
            props.setProperty("$i.id", item.id)
            props.setProperty("$i.name", item.name)
            props.setProperty("$i.size", item.size.toString())
            props.setProperty("$i.added", item.addedAt.toString())
            props.setProperty("$i.origin", item.originalPath)
            props.setProperty("$i.folder", item.folder)
        }
        val folders = index.folders.sorted()
        props.setProperty("folders", folders.size.toString())
        folders.forEachIndexed { i, f -> props.setProperty("f.$i", f) }
        props.setProperty("origins", index.origins.size.toString())
        index.origins.entries.forEachIndexed { i, (folder, path) ->
            props.setProperty("o.$i.folder", folder)
            props.setProperty("o.$i.path", path)
        }
        val plain = ByteArrayOutputStream().also { props.store(it, null) }.toByteArray()
        val tmp = File(dir, "index.tmp")
        writeSynced(tmp) { StreamCipher.encrypt(ByteArrayInputStream(plain), it, key) }
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            if (!tmp.renameTo(indexFile)) throw IOException("Couldn't save the hidden folder list")
        }
    }

    private fun readIndex(key: SecretKey): Index {
        if (!indexFile.exists()) return Index()
        val plain = ByteArrayOutputStream()
        indexFile.inputStream().buffered().use { StreamCipher.decrypt(it, plain, key) }
        val props = Properties().apply { load(ByteArrayInputStream(plain.toByteArray())) }
        val count = props.getProperty("count")?.toIntOrNull() ?: 0
        val items = (0 until count).mapNotNull { i ->
            val id = props.getProperty("$i.id") ?: return@mapNotNull null
            VaultItem(
                id = id,
                name = props.getProperty("$i.name"),
                size = props.getProperty("$i.size").toLong(),
                addedAt = props.getProperty("$i.added").toLong(),
                originalPath = props.getProperty("$i.origin"),
                folder = props.getProperty("$i.folder") ?: "", // older versions had no folders
            )
        }
        val folders = (0 until (props.getProperty("folders")?.toIntOrNull() ?: 0)).mapNotNull { props.getProperty("f.$it") }.toMutableSet()
        items.forEach { folders += withParents(it.folder) }
        val origins = (0 until (props.getProperty("origins")?.toIntOrNull() ?: 0)).mapNotNull { i ->
            val f = props.getProperty("o.$i.folder") ?: return@mapNotNull null
            val p = props.getProperty("o.$i.path") ?: return@mapNotNull null
            f to p
        }.toMap()
        return Index(items, folders, origins)
    }

    // ---- helpers ----

    private fun load(): Properties = Properties().apply {
        if (keysFile.exists()) keysFile.inputStream().use { load(it) }
    }

    /** Writes [file] all the way to the disk, so a sudden power loss can't leave it empty or half written. */
    private inline fun writeSynced(file: File, write: (OutputStream) -> Unit) {
        FileOutputStream(file).use { out ->
            val buffered = out.buffered()
            write(buffered)
            buffered.flush()
            out.fd.sync()
        }
    }

    private fun save(props: Properties) {
        dir.mkdirs()
        val tmp = File(dir, "keys.tmp")
        writeSynced(tmp) { props.store(it, null) }
        if (!tmp.renameTo(keysFile)) {
            keysFile.delete()
            if (!tmp.renameTo(keysFile)) throw IOException("Couldn't save hidden folder settings")
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private class CountingInputStream(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) onBytes(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
