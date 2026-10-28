package app.filo.net

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.OutputStream
import java.net.InetAddress

/**
 * Measures transfer speed against the local test servers (FILO_SPEED_TEST=1). Ports 14445 and 24445
 * are a small proxy in front of the SMB server that adds Wi-Fi-like delay and a 40 MB/s limit.
 */
class SpeedTest {
    private object Sink : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private fun measure(label: String, block: () -> Long) {
        val start = System.nanoTime()
        val bytes = block()
        val seconds = (System.nanoTime() - start) / 1e9
        println("SPEED $label: ${"%.1f".format(bytes / 1e6 / seconds)} MB/s (${bytes / 1_000_000} MB in ${"%.1f".format(seconds)} s)")
    }

    @Test fun downloadSpeed() {
        assumeTrue(System.getenv("FILO_SPEED_TEST") != null)
        val local = InetAddress.getLoopbackAddress()
        SmbFs(local, 4445, "anna", "secret", "").use { fs ->
            measure("SMB encrypted, no latency") { var n = 0L; fs.read("Media/big.bin", Sink, { n += it }); n }
        }
        SmbFs(local, 14445, "anna", "secret", "").use { fs ->
            measure("SMB encrypted, Wi-Fi-like (3 ms each way, 40 MB/s)") { var n = 0L; fs.read("Media/big.bin", Sink, { n += it }); n }
        }
        SmbFs(local, 14445, "anna", "secret", "").use { fs ->
            val data = ByteArray(100_000_000) { (it % 251).toByte() }
            measure("SMB upload, Wi-Fi-like") { fs.write("Media/up.bin", java.io.ByteArrayInputStream(data)); data.size.toLong() }
            fs.delete("Media/up.bin", false)
        }
        SmbFs(local, 24445, "anna", "secret", "").use { fs ->
            measure("SMB encrypted, busy Wi-Fi (20 ms each way, 40 MB/s)") { var n = 0L; fs.read("Media/big.bin", Sink, { n += it }); n }
        }
        FtpFs(local, 2121, "anna", "secret", secure = true).use { fs ->
            measure("FTPS") { var n = 0L; fs.read("home/anna/big.bin", Sink, { n += it }); n }
        }
    }
}
