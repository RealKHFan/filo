package app.filo

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import app.filo.ui.FiloApp
import app.filo.ui.Routes
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : FragmentActivity() {
    companion object {
        const val ACTION_STORAGE = "app.filo.action.STORAGE"
        const val ACTION_DOWNLOADS = "app.filo.action.DOWNLOADS"
    }

    private val pendingRoute = MutableStateFlow<String?>(null)
    private val graph get() = (application as FiloApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        (application as FiloApplication).housekeeping()
        if (savedInstanceState == null) pendingRoute.value = routeFor(intent)
        setContent {
            FiloApp(graph = graph, pendingRoute = pendingRoute, onRouteHandled = { pendingRoute.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFor(intent)?.let { pendingRoute.value = it }
    }

    override fun onResume() {
        super.onResume()
        graph.permission.refresh()
        graph.actions.refresh()
    }

    private fun routeFor(intent: Intent?): String? = when (intent?.action) {
        ACTION_STORAGE -> Routes.STORAGE
        ACTION_DOWNLOADS -> Routes.folder(graph.downloadsDir.absolutePath)
        else -> null
    }
}
