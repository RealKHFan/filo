package app.filo.data

import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

data class TrashItem(
    val id: String,
    val name: String,
    val originalPath: String,
    val deletedAt: Long,
    val isDirectory: Boolean,
    val size: Long,
)

/**
 * Deleted files are moved into a hidden ".filo_trash" folder on the same storage, so deleting and
 * restoring is instant. Items are removed for good after [RETENTION_DAYS].
 */
class TrashManager(
    private val trashDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DIR_NAME = ".filo_trash"
        const val RETENTION_DAYS = 30
    }

    private val itemsDir = File(trashDir, "items")
    private val metaDir = File(trashDir, "meta")

    private fun ensureDirs() {
        if (!itemsDir.isDirectory && !itemsDir.mkdirs()) throw IOException("Couldn't create the trash folder")
        metaDir.mkdirs()
        File(trashDir, ".nomedia").let { if (!it.exists()) it.createNewFile() } // keeps galleries out
    }

    fun moveToTrash(file: File): TrashItem {
        if (!file.exists()) throw IOException("${file.name} no longer exists")
        ensureDirs()
        val id = UUID.randomUUID().toString()
        val item = TrashItem(
            id = id,
            name = file.name,
            originalPath = file.absolutePath,
            deletedAt = clock(),
            isDirectory = file.isDirectory,
            size = FileOps.totalSize(file),
        )
        val target = File(itemsDir, id)
        // Write where it came from first, so a crash can never leave an untraceable file in trash.
        writeMeta(item)
        try {
            if (!file.renameTo(target)) {
                // Different volume (SD card): copy into trash, then remove the original.
                val copied = FileOps.copy(file, itemsDir)
                if (!copied.renameTo(target)) throw IOException("Couldn't move ${file.name} to trash")
                if (!file.deleteRecursively()) throw IOException("Couldn't remove ${file.name}")
            }
        } catch (e: Throwable) {
            if (!target.exists()) File(metaDir, "$id.properties").delete()
            throw e
        }
        return item
    }

    fun list(): List<TrashItem> =
        (metaDir.listFiles() ?: emptyArray())
            .mapNotNull { readMeta(it) }
            .filter { File(itemsDir, it.id).exists() }
            .sortedByDescending { it.deletedAt }

    fun fileOf(item: TrashItem): File = File(itemsDir, item.id)

    fun totalSize(): Long = list().sumOf { it.size }

    /** Puts the item back where it was. If that name is taken now, it gets a " (1)" suffix. */
    fun restore(item: TrashItem): File {
        val original = File(item.originalPath)
        val parent = original.parentFile ?: throw IOException("Can't restore ${item.name}")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Couldn't recreate ${parent.name}")
        val dest = FileOps.uniqueTarget(parent, item.name)
        val stored = fileOf(item)
        if (!stored.renameTo(dest)) {
            val copied = FileOps.copy(stored, parent)
            if (copied.name != dest.name && !copied.renameTo(dest)) throw IOException("Couldn't restore ${item.name}")
            stored.deleteRecursively()
        }
        File(metaDir, "${item.id}.properties").delete()
        return dest
    }

    fun delete(item: TrashItem) {
        fileOf(item).deleteRecursively()
        File(metaDir, "${item.id}.properties").delete()
    }

    fun empty() = list().forEach { delete(it) }

    fun daysLeft(item: TrashItem): Int {
        val elapsed = TimeUnit.MILLISECONDS.toDays(clock() - item.deletedAt).toInt()
        return (RETENTION_DAYS - elapsed).coerceAtLeast(0)
    }

    /** Removes everything older than the retention period. Returns how many items were removed. */
    fun purgeExpired(): Int {
        val limit = clock() - TimeUnit.DAYS.toMillis(RETENTION_DAYS.toLong())
        val expired = list().filter { it.deletedAt < limit }
        expired.forEach { delete(it) }
        return expired.size
    }

    private fun writeMeta(item: TrashItem) {
        val props = Properties().apply {
            setProperty("name", item.name)
            setProperty("originalPath", item.originalPath)
            setProperty("deletedAt", item.deletedAt.toString())
            setProperty("isDirectory", item.isDirectory.toString())
            setProperty("size", item.size.toString())
        }
        File(metaDir, "${item.id}.properties").outputStream().use { props.store(it, null) }
    }

    private fun readMeta(file: File): TrashItem? = try {
        val props = Properties().apply { file.inputStream().use { load(it) } }
        TrashItem(
            id = file.name.removeSuffix(".properties"),
            name = props.getProperty("name"),
            originalPath = props.getProperty("originalPath"),
            deletedAt = props.getProperty("deletedAt").toLong(),
            isDirectory = props.getProperty("isDirectory").toBoolean(),
            size = props.getProperty("size").toLong(),
        )
    } catch (_: Exception) {
        null
    }
}
