package app.filo.data

import java.io.File

/** One file or folder as shown in a list. */
data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    /** Number of visible children for folders, -1 if unknown. */
    val childCount: Int = -1,
) {
    val file: File get() = File(path)
    val extension: String get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
    val kind: FileKind get() = if (isDirectory) FileKind.FOLDER else FileKind.ofName(name)
    val isHidden: Boolean get() = name.startsWith(".")

    companion object {
        fun from(file: File, countChildren: Boolean = false, showHidden: Boolean = false): FileEntry {
            val dir = file.isDirectory
            val count = if (dir && countChildren) {
                file.list()?.count { showHidden || !it.startsWith(".") } ?: 0
            } else {
                -1
            }
            return FileEntry(
                path = file.absolutePath,
                name = file.name,
                isDirectory = dir,
                size = if (dir) 0 else file.length(),
                lastModified = file.lastModified(),
                childCount = count,
            )
        }
    }
}

enum class FileKind(val label: String) {
    FOLDER("Folders"),
    IMAGE("Images"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    DOCUMENT("Documents"),
    ARCHIVE("Archives"),
    APK("Installer files"),
    OTHER("Other files");

    companion object {
        fun ofName(name: String): FileKind {
            val mime = MimeTypes.of(name)
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "apk" -> APK
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                ext in MimeTypes.archiveExtensions -> ARCHIVE
                ext in MimeTypes.documentExtensions || mime.startsWith("text/") -> DOCUMENT
                else -> OTHER
            }
        }
    }
}

/** Small built-in extension → MIME table, so file types work the same everywhere (and in tests). */
object MimeTypes {
    private val table = mapOf(
        // images
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
        "avif" to "image/avif", "svg" to "image/svg+xml", "dng" to "image/x-adobe-dng", "ico" to "image/x-icon",
        // video
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "3gp" to "video/3gpp", "mov" to "video/quicktime", "avi" to "video/x-msvideo", "ts" to "video/mp2t",
        // audio
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg",
        "oga" to "audio/ogg", "opus" to "audio/opus", "flac" to "audio/flac", "wav" to "audio/wav",
        "amr" to "audio/amr", "mid" to "audio/midi", "midi" to "audio/midi",
        // documents
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "html" to "text/html", "htm" to "text/html", "xml" to "text/xml", "json" to "application/json",
        "log" to "text/plain", "rtf" to "application/rtf", "epub" to "application/epub+zip",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "vcf" to "text/x-vcard", "ics" to "text/calendar",
        // archives
        "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar", "gz" to "application/gzip", "tgz" to "application/gzip",
        "xz" to "application/x-xz", "bz2" to "application/x-bzip2",
        // apps
        "apk" to "application/vnd.android.package-archive",
    )

    val documentExtensions = setOf(
        "pdf", "txt", "md", "csv", "html", "htm", "xml", "json", "log", "rtf", "epub", "doc", "docx",
        "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "vcf", "ics",
    )
    val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2")

    fun of(name: String): String =
        table[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
}

enum class SortField(val label: String) { NAME("Name"), DATE("Date modified"), SIZE("Size"), TYPE("Type") }

data class SortOrder(val field: SortField = SortField.NAME, val ascending: Boolean = true)

/** "file2" sorts before "file10", case-insensitive. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}

fun List<FileEntry>.sortedBy(order: SortOrder, foldersFirst: Boolean = true): List<FileEntry> {
    val byName = Comparator<FileEntry> { x, y -> NaturalOrder.compare(x.name, y.name) }
    val base: Comparator<FileEntry> = when (order.field) {
        SortField.NAME -> byName
        SortField.DATE -> compareBy<FileEntry> { it.lastModified }.then(byName)
        SortField.SIZE -> compareBy<FileEntry> { if (it.isDirectory) it.childCount.toLong() else it.size }.then(byName)
        SortField.TYPE -> compareBy<FileEntry> { it.extension }.then(byName)
    }
    val directed = if (order.ascending) base else base.reversed()
    val comparator = if (foldersFirst) compareBy<FileEntry> { !it.isDirectory }.then(directed) else directed
    return sortedWith(comparator)
}
