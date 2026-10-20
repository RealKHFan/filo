package app.filo.data

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class Category(val title: String) {
    IMAGES("Images"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    DOCUMENTS("Documents"),
    APPS("Apps"),
}

/** Category lists and "recent files". Backed by Android's media index, which is fast. */
interface MediaSource {
    suspend fun category(category: Category, showHidden: Boolean): List<FileEntry>
    suspend fun recent(limit: Int, showHidden: Boolean): List<FileEntry>

    /** How much space each category takes, in bytes. */
    suspend fun categorySizes(showHidden: Boolean): Map<Category, Long>
}

class MediaStoreSource(private val context: Context) : MediaSource {
    private val uri = MediaStore.Files.getContentUri("external")

    @Suppress("DEPRECATION") // DATA is the real path; file managers with all-files access may use it.
    private val projection = arrayOf(
        MediaStore.Files.FileColumns.DATA,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
    )

    override suspend fun category(category: Category, showHidden: Boolean): List<FileEntry> {
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val mime = MediaStore.Files.FileColumns.MIME_TYPE
        val (selection, args) = when (category) {
            Category.IMAGES -> "$type = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            Category.VIDEOS -> "$type = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            Category.AUDIO -> "$type = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())
            Category.APPS -> "$mime = ?" to arrayOf(MimeTypes.of("x.apk"))
            Category.DOCUMENTS -> {
                val mimes = MimeTypes.documentExtensions.map { MimeTypes.of("x.$it") }.distinct()
                "($mime IN (${mimes.joinToString { "?" }}) OR $mime LIKE 'text/%')" to mimes.toTypedArray()
            }
        }
        return query(selection, args, limit = 0, showHidden = showHidden)
            .let { list -> if (category == Category.DOCUMENTS) list.filter { it.kind == FileKind.DOCUMENT } else list }
    }

    override suspend fun recent(limit: Int, showHidden: Boolean): List<FileEntry> =
        query("${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL", emptyArray(), limit * 3, showHidden)
            .take(limit)

    @Suppress("DEPRECATION")
    private suspend fun query(
        selection: String,
        args: Array<String>,
        limit: Int,
        showHidden: Boolean,
    ): List<FileEntry> = withContext(Dispatchers.IO) {
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")
            if (limit > 0) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val result = mutableListOf<FileEntry>()
        try {
            context.contentResolver.query(uri, projection, queryArgs, null)?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: continue
                    if (!showHidden && isHiddenPath(path)) continue
                    if (path.contains("/${TrashManager.DIR_NAME}/")) continue
                    result += FileEntry(
                        path = path,
                        name = File(path).name,
                        isDirectory = false,
                        size = cursor.getLong(sizeCol),
                        lastModified = cursor.getLong(dateCol) * 1000,
                    )
                    if (limit > 0 && result.size >= limit) break
                }
            }
        } catch (_: Exception) {
            // The media index can be briefly unavailable (e.g. right after boot); show nothing then.
        }
        result
    }

    @Suppress("DEPRECATION")
    override suspend fun categorySizes(showHidden: Boolean): Map<Category, Long> = withContext(Dispatchers.IO) {
        val sizes = Category.entries.associateWith { 0L }.toMutableMap()
        val apk = MimeTypes.of("x.apk")
        val docMimes = MimeTypes.documentExtensions.map { MimeTypes.of("x.$it") }.toSet()
        val columns = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        try {
            context.contentResolver.query(uri, columns, "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL", null, null)?.use { c ->
                val dataCol = c.getColumnIndexOrThrow(columns[0])
                val sizeCol = c.getColumnIndexOrThrow(columns[1])
                val typeCol = c.getColumnIndexOrThrow(columns[2])
                val mimeCol = c.getColumnIndexOrThrow(columns[3])
                while (c.moveToNext()) {
                    val path = c.getString(dataCol) ?: continue
                    if (!showHidden && isHiddenPath(path)) continue
                    if (path.contains("/${TrashManager.DIR_NAME}/")) continue
                    val mime = c.getString(mimeCol) ?: ""
                    val category = when (c.getInt(typeCol)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> Category.IMAGES
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> Category.VIDEOS
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> Category.AUDIO
                        else -> when {
                            mime == apk -> Category.APPS
                            (mime in docMimes || mime.startsWith("text/")) && FileKind.ofName(path) == FileKind.DOCUMENT -> Category.DOCUMENTS
                            else -> null
                        }
                    } ?: continue
                    sizes[category] = sizes.getValue(category) + c.getLong(sizeCol)
                }
            }
        } catch (_: Exception) {
        }
        sizes
    }

    private fun isHiddenPath(path: String) = path.split('/').any { it.startsWith(".") }
}
