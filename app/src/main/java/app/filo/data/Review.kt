package app.filo.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

data class DuplicateGroup(val size: Long, val files: List<FileEntry>) {
    /** Space you'd get back by keeping one copy. */
    val reclaimable: Long get() = size * (files.size - 1)
}

data class ReviewResult(
    val bytesByKind: Map<FileKind, Long>,
    val fileCount: Int,
    val large: List<FileEntry>,
    val duplicates: List<DuplicateGroup>,
    val oldDownloads: List<FileEntry>,
    val screenshots: List<FileEntry>,
    val installers: List<FileEntry>,
    val emptyFolders: List<FileEntry>,
) {
    val totalFileBytes: Long get() = bytesByKind.values.sum()
}

enum class ReviewKind(val title: String) {
    LARGE("Large files"),
    DUPLICATES("Duplicate files"),
    OLD_DOWNLOADS("Old downloads"),
    SCREENSHOTS("Screenshots"),
    INSTALLERS("Installer files"),
    EMPTY_FOLDERS("Empty folders"),
}

fun ReviewResult.entries(kind: ReviewKind): List<FileEntry> = when (kind) {
    ReviewKind.LARGE -> large
    ReviewKind.DUPLICATES -> duplicates.flatMap { it.files }
    ReviewKind.OLD_DOWNLOADS -> oldDownloads
    ReviewKind.SCREENSHOTS -> screenshots
    ReviewKind.INSTALLERS -> installers
    ReviewKind.EMPTY_FOLDERS -> emptyFolders
}

/** Removes entries (after the user deleted them) from every list. */
fun ReviewResult.without(paths: Set<String>): ReviewResult {
    fun List<FileEntry>.keep() = filter { it.path !in paths }
    return copy(
        large = large.keep(),
        duplicates = duplicates.map { it.copy(files = it.files.keep()) }.filter { it.files.size > 1 },
        oldDownloads = oldDownloads.keep(),
        screenshots = screenshots.keep(),
        installers = installers.keep(),
        emptyFolders = emptyFolders.keep(),
    )
}

/**
 * Looks through storage and reports facts: what is big, what exists twice, what is old.
 * It never deletes anything; the person decides.
 */
class ReviewAnalyzer(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val largeThreshold: Long = 100_000_000L,
    private val oldDownloadDays: Int = 90,
) {
    private val skipAtRoot = setOf("Android", TrashManager.DIR_NAME)

    fun analyze(onProgress: (Int) -> Unit = {}, isCancelled: () -> Boolean = { false }): ReviewResult {
        val bytesByKind = mutableMapOf<FileKind, Long>()
        val large = mutableListOf<FileEntry>()
        val oldDownloads = mutableListOf<FileEntry>()
        val screenshots = mutableListOf<FileEntry>()
        val installers = mutableListOf<FileEntry>()
        val emptyFolders = mutableListOf<FileEntry>()
        val bySize = HashMap<Long, MutableList<FileEntry>>()
        val downloads = File(root, "Download").absolutePath + File.separator
        val oldLimit = now() - TimeUnit.DAYS.toMillis(oldDownloadDays.toLong())
        var count = 0

        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            if (isCancelled()) throw CancellationException()
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            if (children.isEmpty() && dir != root) {
                emptyFolders += FileEntry.from(dir)
                continue
            }
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (dir == root && child.name in skipAtRoot) continue
                if (FileOps.isSymlink(child)) continue
                if (child.isDirectory) {
                    stack.addLast(child)
                    continue
                }
                val entry = FileEntry.from(child)
                count++
                if (count % 500 == 0) onProgress(count)
                val kind = entry.kind
                bytesByKind[kind] = (bytesByKind[kind] ?: 0L) + entry.size
                if (entry.size >= largeThreshold) large += entry
                if (kind == FileKind.APK) installers += entry
                if (entry.path.startsWith(downloads) && entry.lastModified in 1 until oldLimit) oldDownloads += entry
                if ((kind == FileKind.IMAGE || kind == FileKind.VIDEO) &&
                    dir.name.equals("Screenshots", ignoreCase = true)
                ) {
                    screenshots += entry
                }
                if (entry.size > 0) bySize.getOrPut(entry.size) { mutableListOf() } += entry
            }
        }
        onProgress(count)

        val duplicates = findDuplicates(bySize, isCancelled)
        return ReviewResult(
            bytesByKind = bytesByKind,
            fileCount = count,
            large = large.sortedByDescending { it.size },
            duplicates = duplicates.sortedByDescending { it.reclaimable },
            oldDownloads = oldDownloads.sortedBy { it.lastModified },
            screenshots = screenshots.sortedBy { it.lastModified },
            installers = installers.sortedByDescending { it.size },
            emptyFolders = emptyFolders.sortedBy { it.path },
        )
    }

    /** Same size → same first/last 16 kB → same full SHA-256. Only the last step reads whole files. */
    private fun findDuplicates(
        bySize: Map<Long, List<FileEntry>>,
        isCancelled: () -> Boolean,
    ): List<DuplicateGroup> {
        val groups = mutableListOf<DuplicateGroup>()
        for ((size, sameSize) in bySize) {
            if (sameSize.size < 2) continue
            val byQuick = sameSize.groupBy { quickHash(it.file) }.filterKeys { it != null }
            for (quickGroup in byQuick.values) {
                if (quickGroup.size < 2) continue
                val byFull = quickGroup.groupBy {
                    if (isCancelled()) throw CancellationException()
                    fullHash(it.file)
                }.filterKeys { it != null }
                for (group in byFull.values) {
                    if (group.size > 1) groups += DuplicateGroup(size, group.sortedBy { it.lastModified })
                }
            }
        }
        return groups
    }

    private fun quickHash(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val chunk = 16 * 1024
            val buffer = ByteArray(chunk)
            val head = raf.read(buffer)
            if (head > 0) digest.update(buffer, 0, head)
            if (raf.length() > chunk * 2) {
                raf.seek(raf.length() - chunk)
                val tail = raf.read(buffer)
                if (tail > 0) digest.update(buffer, 0, tail)
            }
        }
        digest.digest().toHex()
    } catch (_: Exception) {
        null
    }

    private fun fullHash(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().toHex()
    } catch (_: Exception) {
        null
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
