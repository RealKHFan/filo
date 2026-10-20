package app.filo.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class StorageVolume(val label: String, val root: File, val isPrimary: Boolean)

data class StorageSpace(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0)
    val usedFraction: Float get() = if (total > 0) used.toFloat() / total else 0f
}

object StorageInfo {
    fun space(root: File): StorageSpace = try {
        val stat = StatFs(root.path)
        StorageSpace(stat.totalBytes, stat.availableBytes)
    } catch (_: Exception) {
        StorageSpace(0, 0)
    }

    /** Internal storage plus any SD card or USB drive Android exposes. */
    fun volumes(context: Context, primary: File): List<StorageVolume> {
        val result = mutableListOf(StorageVolume("Internal storage", primary, isPrimary = true))
        // The first entry is always the phone's own storage; the rest are removable.
        context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { appDir ->
            val path = appDir.absolutePath
            val idx = path.indexOf("/Android/data/")
            if (idx <= 0) return@forEach
            val root = File(path.substring(0, idx))
            if (root.absolutePath != primary.absolutePath && root.canRead()) {
                result += StorageVolume("SD card", root, isPrimary = false)
            }
        }
        return result
    }
}

/** Whether Filo may read and write all files. Re-checked every time the app comes back. */
class StoragePermission(private val checker: () -> Boolean) {
    private val _granted = MutableStateFlow(checker())
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    fun refresh() {
        _granted.value = checker()
    }

    companion object {
        fun forContext(context: Context) = StoragePermission {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
    }
}
