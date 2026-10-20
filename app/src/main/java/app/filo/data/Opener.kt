package app.filo.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Hands files to other apps on the phone (viewers, the share sheet, the package installer). */
object Opener {
    private fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Returns false if no app on the phone can open this type. */
    fun open(context: Context, file: File, chooser: Boolean = false): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file), MimeTypes.of(file.name).takeUnless { it.endsWith("octet-stream") } ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val toStart = if (chooser) Intent.createChooser(intent, "Open with") else intent
        return try {
            context.startActivity(toStart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = files.map { uri(context, it) }
        val mimes = files.map { MimeTypes.of(it.name) }.distinct()
        val type = when {
            mimes.size == 1 -> mimes.first()
            mimes.all { it.startsWith("image/") } -> "image/*"
            mimes.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = type
        intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Tells the gallery and other apps that files appeared, changed or disappeared. */
    fun notifyMedia(context: Context, paths: Collection<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null)
        } catch (_: Exception) {
        }
    }
}
