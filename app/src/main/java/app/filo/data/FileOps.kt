package app.filo.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.CancellationException

/** Plain java.io file operations. No Android types, so everything here is unit-tested on the JVM. */
object FileOps {
    /** Folders Filo creates for itself; never shown in lists. */
    val internalNames = setOf(TrashManager.DIR_NAME)

    fun list(dir: File, showHidden: Boolean): List<FileEntry> =
        (dir.listFiles() ?: emptyArray())
            .asSequence()
            .filter { it.name !in internalNames }
            .filter { showHidden || !it.name.startsWith(".") }
            .map { FileEntry.from(it, countChildren = true, showHidden = showHidden) }
            .toList()

    fun isValidName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.trim() == name &&
            name.none { it == '/' || it == '\u0000' } && name.toByteArray().size <= 255

    /** "photo.jpg" → "photo (1).jpg" when the name is taken. */
    fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var i = 1
        while (true) {
            candidate = File(dir, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    fun isSameOrInside(child: File, parent: File): Boolean {
        val c = child.canonicalPath
        val p = parent.canonicalPath
        return c == p || c.startsWith(p + File.separator)
    }

    fun isSymlink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (_: Exception) {
        false
    }

    fun totalSize(file: File): Long {
        if (!file.isDirectory || isSymlink(file)) return file.length()
        return (file.listFiles() ?: emptyArray()).sumOf { totalSize(it) }
    }

    fun copy(
        src: File,
        destDir: File,
        onBytes: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): File {
        if (!src.exists()) throw IOException("${src.name} no longer exists")
        if (src.isDirectory && isSameOrInside(destDir, src)) throw IOException("A folder can't be copied into itself")
        val target = uniqueTarget(destDir, src.name)
        try {
            copyRecursive(src, target, onBytes, isCancelled)
        } catch (e: Throwable) {
            target.deleteRecursively() // never leave half-copied files behind
            throw e
        }
        return target
    }

    fun move(
        src: File,
        destDir: File,
        onBytes: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): File {
        if (!src.exists()) throw IOException("${src.name} no longer exists")
        if (src.isDirectory && isSameOrInside(destDir, src)) throw IOException("A folder can't be moved into itself")
        if (src.parentFile?.canonicalPath == destDir.canonicalPath) return src
        val target = uniqueTarget(destDir, src.name)
        val size = totalSize(src)
        if (src.renameTo(target)) {
            onBytes(size)
            return target
        }
        // Different storage volume: copy, then delete the original only after the copy succeeded.
        try {
            copyRecursive(src, target, onBytes, isCancelled)
        } catch (e: Throwable) {
            target.deleteRecursively()
            throw e
        }
        if (!src.deleteRecursively()) throw IOException("Copied ${src.name}, but couldn't remove the original")
        return target
    }

    private fun copyRecursive(src: File, target: File, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException()
        if (src.isDirectory && !isSymlink(src)) {
            if (!target.mkdirs() && !target.isDirectory) throw IOException("Couldn't create ${target.name}")
            for (child in src.listFiles() ?: emptyArray()) {
                copyRecursive(child, File(target, child.name), onBytes, isCancelled)
            }
        } else {
            src.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (isCancelled()) throw CancellationException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        }
        target.setLastModified(src.lastModified())
    }

    fun rename(file: File, newName: String): File {
        if (!isValidName(newName)) throw IOException("That name isn't allowed")
        if (newName == file.name) return file
        val parent = file.parentFile ?: throw IOException("Can't rename this item")
        val target = File(parent, newName)
        val onlyCaseChanges = newName.equals(file.name, ignoreCase = true)
        if (target.exists() && !onlyCaseChanges) throw IOException("\"$newName\" already exists")
        if (onlyCaseChanges) {
            // Phone storage ignores letter case, so go through a temporary name.
            val temp = uniqueTarget(parent, ".filo-rename-${System.nanoTime()}")
            if (!file.renameTo(temp) || !temp.renameTo(target)) throw IOException("Couldn't rename ${file.name}")
            return target
        }
        if (!file.renameTo(target)) throw IOException("Couldn't rename ${file.name}")
        return target
    }

    fun createFolder(parent: File, name: String): File {
        if (!isValidName(name)) throw IOException("That name isn't allowed")
        val folder = File(parent, name)
        if (folder.exists()) throw IOException("\"$name\" already exists")
        if (!folder.mkdir()) throw IOException("Couldn't create \"$name\"")
        return folder
    }

    /** Sizes in SI units like Android's own settings: 1 kB = 1000 bytes. */
    fun formatSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1000) return "$bytes B"
        val units = arrayOf("kB", "MB", "GB", "TB", "PB")
        var value = bytes / 1000.0
        var unit = 0
        // 999.6 kB would print as "1000 kB", so step up a unit before rounding does that.
        while (value >= 999.5 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        val pattern = if (value < 10) "0.#" else "0"
        val text = DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale)).format(value)
        return "$text ${units[unit]}"
    }
}
