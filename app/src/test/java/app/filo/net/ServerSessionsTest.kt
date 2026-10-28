package app.filo.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.filo.vault.SoftwareKeyWrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch

// Conscrypt off: Robolectric would install it for the whole test run, and the plain-JVM FTPS test then can't use TLS.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ServerSessionsTest {
    private class FakeFs : RemoteFs {
        @Volatile var closed = false
        override val home = ""
        override val isConnected get() = !closed
        override fun list(path: String) = emptyList<RemoteFile>()
        override fun read(path: String, out: OutputStream, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) = Unit
        override fun readHead(path: String, max: Int) = ByteArray(0)
        override fun write(path: String, input: InputStream, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) = Unit
        override fun mkdir(path: String) = Unit
        override fun delete(path: String, isDirectory: Boolean) = Unit
        override fun rename(from: String, to: String) = Unit
        override fun close() {
            closed = true
        }
    }

    @Test fun `a connection that finishes opening after the server was edited is closed too`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ServerStore(context.getSharedPreferences("sessions_test", Context.MODE_PRIVATE), SoftwareKeyWrap())
        val connecting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fs = FakeFs()
        val sessions = ServerSessions(store, { _, _ -> connecting.countDown(); release.await(); fs }, { true }, { true })
        val listing = async(Dispatchers.Default) { sessions.use(Server("s", "NAS", Protocol.FTP, "127.0.0.1")) { it.list("") } }
        connecting.await()
        sessions.close("s") // the server was edited while Filo was still connecting
        release.countDown()
        listing.await()
        val deadline = System.currentTimeMillis() + 5_000 // it is closed on its own thread
        while (!fs.closed && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("connection left open", fs.closed)
    }
}
