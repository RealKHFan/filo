package app.filo.vault

import app.filo.data.FileKind
import app.filo.data.FileOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

data class VaultItem(
    val id: String,
    val name: String,
    val size: Long,
    val addedAt: Long,
    val originalPath: String,
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
        writeIndex(emptyList(), key())
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
        _items.value = readIndex(key)
        _unlocked.value = true
        clearTemp()
    }

    @Synchronized
    fun lock() {
        folderKeyBytes?.fill(0)
        folderKeyBytes = null
        folderKey = null
        _items.value = emptyList()
        _unlocked.value = false
    }

    private fun key(): SecretKey = folderKey ?: throw IllegalStateException("The hidden folder is locked")

    // ---- Files ----

    /**
     * Hides several files. The folder key is taken once at the start, so the batch finishes even if
     * the folder locks itself meanwhile (Filo left the screen).
     */
    fun addAll(sources: List<File>, deleteOriginal: Boolean = true, onBytes: (Long) -> Unit = {}): List<VaultItem> {
        val key = key()
        return sources.map { add(it, deleteOriginal, key, onBytes) }
    }

    /** Encrypts [source] into the hidden folder, checks the copy, then deletes the original. */
    fun add(source: File, deleteOriginal: Boolean = true, key: SecretKey = key(), onBytes: (Long) -> Unit = {}): VaultItem {
        if (!source.isFile) throw IOException("Only files can go into the hidden folder")
        val id = UUID.randomUUID().toString()
        val blob = File(blobDir, "$id.bin")
        try {
            source.inputStream().buffered().use { input ->
                blob.outputStream().buffered().use { output ->
                    StreamCipher.encrypt(CountingInputStream(input, onBytes), output, key)
                }
            }
            // Read the encrypted copy back completely before touching the original.
            blob.inputStream().buffered().use { StreamCipher.decrypt(it, NullOutputStream, key) }
        } catch (e: Throwable) {
            blob.delete()
            throw e
        }
        val item = VaultItem(id, source.name, source.length(), clock(), source.absolutePath)
        synchronized(this) {
            // Read the list from disk (not memory): if the folder locked meanwhile, nothing is lost.
            val updated = readIndex(key) + item
            writeIndex(updated, key)
            if (folderKey === key) _items.value = updated
        }
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

    fun deleteAll(items: List<VaultItem>) {
        val key = key()
        items.forEach { delete(it, key) }
    }

    @Synchronized
    fun delete(item: VaultItem, key: SecretKey = key()) {
        val updated = readIndex(key).filterNot { it.id == item.id }
        writeIndex(updated, key)
        if (folderKey === key) _items.value = updated
        File(blobDir, "${item.id}.bin").delete()
    }

    /** Small files (thumbnails, the image viewer) are decrypted in memory only. */
    fun readBytes(item: VaultItem, maxBytes: Long = 40_000_000L): ByteArray? {
        if (item.size > maxBytes) return null
        val out = ByteArrayOutputStream(item.size.toInt().coerceAtLeast(32))
        File(blobDir, "${item.id}.bin").inputStream().buffered().use { StreamCipher.decrypt(it, out, key()) }
        return out.toByteArray()
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

    // ---- Encrypted index (file names, sizes, dates) ----

    private fun writeIndex(items: List<VaultItem>, key: SecretKey) {
        val props = Properties()
        props.setProperty("count", items.size.toString())
        items.forEachIndexed { i, item ->
            props.setProperty("$i.id", item.id)
            props.setProperty("$i.name", item.name)
            props.setProperty("$i.size", item.size.toString())
            props.setProperty("$i.added", item.addedAt.toString())
            props.setProperty("$i.origin", item.originalPath)
        }
        val plain = ByteArrayOutputStream().also { props.store(it, null) }.toByteArray()
        val tmp = File(dir, "index.tmp")
        tmp.outputStream().use { StreamCipher.encrypt(ByteArrayInputStream(plain), it, key) }
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            if (!tmp.renameTo(indexFile)) throw IOException("Couldn't save the hidden folder list")
        }
    }

    private fun readIndex(key: SecretKey): List<VaultItem> {
        if (!indexFile.exists()) return emptyList()
        val plain = ByteArrayOutputStream()
        indexFile.inputStream().buffered().use { StreamCipher.decrypt(it, plain, key) }
        val props = Properties().apply { load(ByteArrayInputStream(plain.toByteArray())) }
        val count = props.getProperty("count")?.toIntOrNull() ?: 0
        return (0 until count).mapNotNull { i ->
            val id = props.getProperty("$i.id") ?: return@mapNotNull null
            VaultItem(
                id = id,
                name = props.getProperty("$i.name"),
                size = props.getProperty("$i.size").toLong(),
                addedAt = props.getProperty("$i.added").toLong(),
                originalPath = props.getProperty("$i.origin"),
            )
        }
    }

    // ---- helpers ----

    private fun load(): Properties = Properties().apply {
        if (keysFile.exists()) keysFile.inputStream().use { load(it) }
    }

    private fun save(props: Properties) {
        dir.mkdirs()
        val tmp = File(dir, "keys.tmp")
        tmp.outputStream().use { props.store(it, null) }
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
