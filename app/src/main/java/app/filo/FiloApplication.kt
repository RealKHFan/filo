package app.filo

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.filo.vault.AutoLock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder

class FiloApplication : Application(), SingletonImageLoader.Factory {
    /** Replaced by tests before any screen is shown. */
    var graphFactory: () -> Graph = { Graph(this) }
    val graph: Graph by lazy { graphFactory() }

    override fun onCreate() {
        super.onCreate()
        try {
            // The hidden folder locks itself a few seconds after Filo leaves the screen (see Settings).
            val handler = Handler(Looper.getMainLooper())
            val token = Any()
            val autoLock = AutoLock(
                now = SystemClock::elapsedRealtime,
                delayMillis = { graph.settings.state.value.autoLockSeconds * 1000L },
                schedule = { delay, action -> handler.postAtTime(Runnable { action() }, token, SystemClock.uptimeMillis() + delay) },
                cancel = { handler.removeCallbacksAndMessages(token) },
                lock = { graph.vault.lock() },
            )
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        // Pause, not stop: the recent-apps view keeps Filo "visible" but no longer in front.
                        Lifecycle.Event.ON_PAUSE -> autoLock.onBackground()
                        Lifecycle.Event.ON_RESUME -> autoLock.onForeground()
                        else -> Unit
                    }
                },
            )
        } catch (_: Exception) {
        }
    }

    private var housekeepingDone = false

    /** Once per app start: delete old trash and leftover decrypted copies. */
    fun housekeeping() {
        if (housekeepingDone) return
        housekeepingDone = true
        Thread {
            try {
                graph.vault.clearTemp()
                if (graph.permission.granted.value) graph.trash.purgeExpired()
            } catch (_: Exception) {
            }
        }.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}
