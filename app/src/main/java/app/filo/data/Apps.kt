package app.filo.data

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One installed app. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    /** Size of the app's own files (APK and its parts). Always known. */
    val appSize: Long,
    /** App + data + cache, like Android settings shows. Only with Usage access. */
    val fullSize: Long?,
    val isSystem: Boolean,
    val updatedAt: Long,
    val apkPaths: List<String>,
) {
    val shownSize: Long get() = fullSize ?: appSize
}

interface AppsSource {
    suspend fun installed(includeSystem: Boolean, fullSizes: Boolean): List<AppInfo>
    fun hasUsageAccess(): Boolean
}

class AndroidAppsSource(private val context: Context) : AppsSource {
    private val pm: PackageManager get() = context.packageManager

    override fun hasUsageAccess(): Boolean = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    override suspend fun installed(includeSystem: Boolean, fullSizes: Boolean): List<AppInfo> = withContext(Dispatchers.IO) {
        val stats = if (fullSizes && hasUsageAccess()) context.getSystemService(StorageStatsManager::class.java) else null
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0).mapNotNull { app ->
            val system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
            if (system && !includeSystem) return@mapNotNull null
            try {
                val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
                @Suppress("DEPRECATION")
                val pkg = pm.getPackageInfo(app.packageName, 0)
                val full = stats?.let {
                    try {
                        val s = it.queryStatsForPackage(StorageManager.UUID_DEFAULT, app.packageName, Process.myUserHandle())
                        s.appBytes + s.dataBytes + s.cacheBytes
                    } catch (_: Exception) {
                        null
                    }
                }
                AppInfo(
                    packageName = app.packageName,
                    label = app.loadLabel(pm).toString(),
                    versionName = pkg.versionName ?: "",
                    appSize = paths.sumOf { File(it).length() },
                    fullSize = full,
                    isSystem = system,
                    updatedAt = pkg.lastUpdateTime,
                    apkPaths = paths,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** What you can do with an installed app. All of it is handed to Android itself. */
object AppActions {
    fun open(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun info(context: Context, packageName: String) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Android shows its own "Do you want to uninstall?" confirmation. */
    fun uninstall(context: Context, packageName: String) {
        @Suppress("DEPRECATION")
        context.startActivity(
            Intent(Intent.ACTION_DELETE, "package:$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Opens Android's own Usage access page for Filo. No pop-up from Filo. */
    fun openUsageAccessSettings(context: Context) {
        val specific = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri())
        try {
            context.startActivity(specific.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Copies the app's installer into [destDir]. Apps made of several parts are saved together as
     * one ".apks" file (installable with split-APK installers); simple apps as a normal ".apk".
     */
    fun saveApk(app: AppInfo, destDir: File): File {
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("Couldn't open ${destDir.name}")
        val base = "${app.label}-${app.versionName}".replace(Regex("[\\\\/:*?\"<>|]"), "_").trim('-', ' ')
        if (app.apkPaths.size == 1) {
            val target = FileOps.uniqueTarget(destDir, "$base.apk")
            try {
                File(app.apkPaths.first()).copyTo(target)
            } catch (e: Throwable) {
                target.delete() // a half-saved APK would only fail to install later
                throw e
            }
            return target
        }
        val target = FileOps.uniqueTarget(destDir, "$base.apks")
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                app.apkPaths.forEach { path ->
                    zip.putNextEntry(ZipEntry(File(path).name))
                    File(path).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        return target
    }
}
