package app.filo.vault

/**
 * Locks the hidden folder a few seconds after Filo leaves the screen (another app, home screen,
 * screen off). The delay comes from Settings.
 *
 * Android may pause Filo's process in the background, so a timer alone isn't trusted: when Filo
 * comes back, the elapsed time is checked too, and the folder is locked before anything shows.
 */
class AutoLock(
    private val now: () -> Long,
    private val delayMillis: () -> Long,
    private val schedule: (delay: Long, action: () -> Unit) -> Unit,
    private val cancel: () -> Unit,
    private val lock: () -> Unit,
) {
    private var leftAt: Long? = null

    fun onBackground() {
        leftAt = now()
        val delay = delayMillis()
        if (delay <= 0) lock() else schedule(delay, lock)
    }

    fun onForeground() {
        val left = leftAt ?: return
        leftAt = null
        cancel()
        if (now() - left >= delayMillis()) lock()
    }
}
