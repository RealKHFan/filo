package app.filo.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipUtils {
    /** Extracts [zip] into a new folder next to it. Refuses entries that try to escape that folder. */
    fun extract(
        zip: File,
        destParent: File,
        onBytes: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): File {
        val outDir = FileOps.uniqueTarget(destParent, zip.name.substringBeforeLast('.', zip.name))
        if (!outDir.mkdirs()) throw IOException("Couldn't create ${outDir.name}")
        val outCanonical = outDir.canonicalPath
        try {
            ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val target = File(outDir, entry.name)
                    val canonical = target.canonicalPath
                    if (canonical != outCanonical && !canonical.startsWith(outCanonical + File.separator)) {
                        throw IOException("This archive contains an unsafe path and was not extracted")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { pump(zis, it, onBytes, isCancelled) }
                        if (entry.time > 0) target.setLastModified(entry.time)
                    }
                }
            }
        } catch (e: Throwable) {
            outDir.deleteRecursively()
            if (e is IllegalArgumentException) throw IOException("This archive uses file names Filo can't read")
            throw e
        }
        return outDir
    }

    /** Packs [files] into a new zip inside [destDir]. */
    fun compress(
        files: List<File>,
        destDir: File,
        name: String = defaultName(files),
        onBytes: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): File {
        val target = FileOps.uniqueTarget(destDir, name)
        try {
            ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zos ->
                for (file in files) add(zos, file, file.name, target, onBytes, isCancelled)
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        return target
    }

    fun defaultName(files: List<File>): String =
        if (files.size == 1) "${files[0].name.substringBeforeLast('.', files[0].name)}.zip" else "Archive.zip"

    private fun add(
        zos: ZipOutputStream,
        file: File,
        entryName: String,
        target: File,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        if (file.absolutePath == target.absolutePath) return
        if (file.isDirectory) {
            zos.putNextEntry(ZipEntry("$entryName/").apply { time = file.lastModified() })
            zos.closeEntry()
            for (child in file.listFiles() ?: emptyArray()) {
                add(zos, child, "$entryName/${child.name}", target, onBytes, isCancelled)
            }
        } else {
            zos.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
            file.inputStream().use { pump(it, zos, onBytes, isCancelled) }
            zos.closeEntry()
        }
    }

    private fun pump(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            if (isCancelled()) throw CancellationException()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            onBytes(n.toLong())
        }
    }
}
