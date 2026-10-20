package app.filo.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoLockTest {
    private var now = 0L
    private var delaySeconds = 5
    private var locks = 0
    private var scheduled: Pair<Long, () -> Unit>? = null

    private val autoLock = AutoLock(
        now = { now },
        delayMillis = { delaySeconds * 1000L },
        schedule = { delay, action -> scheduled = delay to action },
        cancel = { scheduled = null },
        lock = { locks++ },
    )

    @Test fun `quick trip to another app keeps it open`() {
        autoLock.onBackground()
        assertEquals(5_000L, scheduled!!.first)
        now += 3_000
        autoLock.onForeground()
        assertNull("timer cancelled", scheduled)
        assertEquals(0, locks)
    }

    @Test fun `timer locks after the delay`() {
        autoLock.onBackground()
        now += 5_000
        scheduled!!.second()
        assertEquals(1, locks)
    }

    @Test fun `locks on return even if the timer never ran`() {
        autoLock.onBackground()
        now += 60_000 // Android paused Filo, the timer didn't fire
        autoLock.onForeground()
        assertEquals(1, locks)
    }

    @Test fun `immediately means immediately`() {
        delaySeconds = 0
        autoLock.onBackground()
        assertEquals(1, locks)
        assertNull(scheduled)
    }

    @Test fun `first start does nothing`() {
        autoLock.onForeground()
        assertEquals(0, locks)
    }
}

class VaultBatchTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a batch finishes even if the folder locks halfway`() {
        val v = VaultManager(File(tmp.root, "vault"), File(tmp.root, "cache"), SoftwareKeyWrap(), PinKdf(1_000))
        v.setup("123456")
        val files = (1..3).map { i -> File(tmp.root, "f$i.bin").apply { writeBytes(ByteArray(100_000) { i.toByte() }) } }
        var first = true
        val added = v.addAll(files) {
            if (first) {
                first = false
                v.lock()
            }
        }
        assertEquals(3, added.size)
        files.forEach { assertEquals(false, it.exists()) }
        assertEquals(UnlockResult.Success, v.unlockWithPin("123456"))
        assertEquals(3, v.items.value.size)

        val restored = v.restoreAll(v.items.value, File(tmp.root, "out"))
        assertEquals(3, restored.size)
        restored.forEach { assertEquals(100_000L, it.length()) }
        assertEquals(0, v.items.value.size)
    }
}
