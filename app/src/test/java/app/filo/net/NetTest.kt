package app.filo.net

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.mockftpserver.fake.FakeFtpServer
import org.mockftpserver.fake.UserAccount
import org.mockftpserver.fake.filesystem.DirectoryEntry
import org.mockftpserver.fake.filesystem.FileEntry
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.SocketException

class LocalNetworkTest {
    private fun local(ip: String) = LocalNetwork.isLocal(InetAddress.getByName(ip))

    @Test fun `home, link-local and VPN addresses are local, the internet is not`() {
        listOf(
            "192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.254", "169.254.10.10", "127.0.0.1",
            "100.64.0.1", "100.101.102.103", "100.127.255.255", // Tailscale and other CGNAT VPNs
            "fe80::1", "fd7a:115c:a1e0::1", "fc00::5", "::1",
        ).forEach { assertTrue(it, local(it)) }
        listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "100.63.255.255", "192.169.0.1", "2001:4860:4860::8888", "2a00:1450::1")
            .forEach { assertFalse(it, local(it)) }
    }

    @Test fun `servers on the internet are refused before connecting`() {
        assertEquals(InetAddress.getByName("192.168.178.2"), LocalNetwork.resolve("192.168.178.2"))
        assertEquals(InetAddress.getByName("fd00::2"), LocalNetwork.resolve("[fd00::2]"))
        try {
            LocalNetwork.resolve("93.184.216.34")
            fail("internet address accepted")
        } catch (_: NotLocalException) {
        }
    }

    @Test fun `blocked network is recognised`() {
        assertTrue(SocketException("socket failed: EACCES (Permission denied)").isNetworkBlocked())
        assertTrue(RuntimeException(SocketException("Operation not permitted")).isNetworkBlocked())
        assertFalse(SocketException("Connection refused").isNetworkBlocked())
    }

    @Test fun `unique names on a server`() {
        assertEquals("a.jpg", RemotePath.unique(setOf("b.jpg"), "a.jpg"))
        assertEquals("a (2).jpg", RemotePath.unique(setOf("a.jpg", "a (1).jpg"), "a.jpg"))
        assertEquals("Music (1)", RemotePath.unique(setOf("Music"), "Music"))
        assertEquals("share/a/b", RemotePath.normalize("\\share\\a//b/"))
    }
}

class FtpFsTest {
    private lateinit var server: FakeFtpServer

    @Before fun start() {
        server = FakeFtpServer().apply {
            serverControlPort = 0
            addUserAccount(UserAccount("anna", "secret", "/home/anna"))
            fileSystem = UnixFakeFileSystem().apply {
                add(DirectoryEntry("/home/anna"))
                add(DirectoryEntry("/home/anna/Photos"))
                add(FileEntry("/home/anna/Photos/beach.jpg", "jpeg bytes"))
                add(FileEntry("/home/anna/notes.txt", "hello"))
            }
            start()
        }
    }

    @After fun stop() = server.stop()

    private fun connect(password: String = "secret") =
        FtpFs(InetAddress.getLoopbackAddress(), server.serverControlPort, "anna", password, secure = false)

    @Test fun `browse, read, upload, rename, delete`() {
        connect().use { fs ->
            assertEquals("home/anna", fs.home)
            val top = fs.list(fs.home).associateBy { it.name }
            assertEquals(setOf("Photos", "notes.txt"), top.keys)
            assertTrue(top.getValue("Photos").isDirectory)
            assertEquals(5L, top.getValue("notes.txt").size)

            val out = ByteArrayOutputStream()
            var counted = 0L
            fs.read("home/anna/notes.txt", out, onBytes = { counted += it })
            assertEquals("hello", out.toString())
            assertEquals(5L, counted)

            val data = ByteArray(200_000) { (it % 251).toByte() }
            fs.write("home/anna/Photos/upload.bin", ByteArrayInputStream(data))
            val back = ByteArrayOutputStream().also { fs.read("home/anna/Photos/upload.bin", it) }.toByteArray()
            assertArrayEquals(data, back)

            // Reading just the start of a file (photo previews). This test server hangs up when a
            // download stops early, so the connection reports itself unusable and gets replaced.
            assertArrayEquals(data.copyOf(1000), fs.readHead("home/anna/Photos/upload.bin", 1000))
        }
        connect().use { fs ->
            fs.mkdir("home/anna/Trips")
            fs.rename("home/anna/Photos", "home/anna/Trips/Photos")
            assertEquals(setOf("beach.jpg", "upload.bin"), fs.list("home/anna/Trips/Photos").map { it.name }.toSet())

            fs.delete("home/anna/Trips", isDirectory = true) // with everything inside
            assertEquals(setOf("notes.txt"), fs.list("home/anna").map { it.name }.toSet())
            assertTrue(fs.isConnected)
        }
    }

    @Test fun `wrong password`() {
        try {
            connect("nope")
            fail("logged in with a wrong password")
        } catch (_: LoginFailedException) {
        }
    }

    @Test fun `server errors are reported as answers, not broken connections`() {
        connect().use { fs ->
            try {
                fs.read("home/anna/missing.txt", ByteArrayOutputStream())
                fail("read a missing file")
            } catch (e: ServerReplyException) {
                assertTrue(e.message!!, e.message!!.contains("not found"))
            }
        }
    }
}

/**
 * Talks to a real SMB server when one is running (CI doesn't have one):
 *   smbserver.py -smb2support -username anna -password secret -port 4445 Media /tmp/share
 *   FILO_SMB_TEST_PORT=4445 ./gradlew testDebugUnitTest
 */
class SmbFsTest {
    @Test fun `browse, read, upload, rename, delete on a real server`() {
        val port = System.getenv("FILO_SMB_TEST_PORT")?.toIntOrNull()
        assumeTrue("no SMB test server", port != null)
        SmbFs(InetAddress.getLoopbackAddress(), port!!, "anna", "secret", "").use { fs ->
            assertTrue("the server answers on an encrypted connection", fs.isConnected)
            assertEquals(listOf("Media", "Public"), fs.list("").map { it.name }) // no IPC$ or admin shares
            fs.mkdir("Media/Filo test")
            val data = ByteArray(300_000) { (it % 253).toByte() }
            fs.write("Media/Filo test/a.bin", ByteArrayInputStream(data))
            assertEquals(listOf("a.bin"), fs.list("Media/Filo test").map { it.name })
            assertEquals(300_000L, fs.list("Media/Filo test").single().size)
            assertArrayEquals(data, ByteArrayOutputStream().also { fs.read("Media/Filo test/a.bin", it) }.toByteArray())
            assertArrayEquals(data.copyOf(2048), fs.readHead("Media/Filo test/a.bin", 2048))
            // A file bigger than one chunk, read with several chunks on their way at once
            val big = ByteArray(5_300_000) { (it % 247).toByte() }
            fs.write("Media/Filo test/big.bin", ByteArrayInputStream(big))
            assertArrayEquals(big, ByteArrayOutputStream().also { fs.read("Media/Filo test/big.bin", it) }.toByteArray())
            fs.delete("Media/Filo test/big.bin", isDirectory = false)
            fs.rename("Media/Filo test/a.bin", "Media/Filo test/b.bin")
            assertEquals(listOf("b.bin"), fs.list("Media/Filo test").map { it.name })
            fs.delete("Media/Filo test", isDirectory = true)
            assertFalse(fs.list("Media").any { it.name == "Filo test" })
        }
        // Guest access to a public share, and a wrong password
        SmbFs(InetAddress.getLoopbackAddress(), port, "", "", "").use { fs ->
            assertEquals(listOf("hello.txt"), fs.list("Public").map { it.name })
            assertTrue("the server answers a guest connection", fs.isConnected)
        }
        try {
            SmbFs(InetAddress.getLoopbackAddress(), port, "anna", "wrong", "").close()
            fail("logged in with a wrong password")
        } catch (_: LoginFailedException) {
        }
    }
}

/**
 * FTPS against a real server when one is running (vsftpd with a self-signed certificate):
 *   FILO_FTPS_TEST_PORT=2121 ./gradlew testDebugUnitTest
 */
class FtpsTest {
    @Test fun `encrypted FTP remembers the certificate and refuses a different one`() {
        val port = System.getenv("FILO_FTPS_TEST_PORT")?.toIntOrNull()
        assumeTrue("no FTPS test server", port != null)
        val local = InetAddress.getLoopbackAddress()
        val first = FtpFs(local, port!!, "anna", "secret", secure = true)
        val certificate = first.certificate!!
        first.use { fs ->
            val data = ByteArray(150_000) { (it % 249).toByte() }
            fs.write(RemotePath.join(fs.home, "filo.bin"), ByteArrayInputStream(data))
            assertArrayEquals(data, ByteArrayOutputStream().also { fs.read(RemotePath.join(fs.home, "filo.bin"), it) }.toByteArray())
            assertTrue(fs.list(fs.home).any { it.name == "filo.bin" })
            assertArrayEquals(data.copyOf(4096), fs.readHead(RemotePath.join(fs.home, "filo.bin"), 4096))
            assertTrue("still in step after stopping early", fs.list(fs.home).any { it.name == "filo.bin" })
            fs.delete(RemotePath.join(fs.home, "filo.bin"), isDirectory = false)
        }
        FtpFs(local, port, "anna", "secret", secure = true, pinnedCertificate = certificate).close()
        try {
            FtpFs(local, port, "anna", "secret", secure = true, pinnedCertificate = "00:11:22").close()
            fail("accepted a different certificate")
        } catch (e: CertificateChangedException) {
            assertEquals(certificate, e.fingerprint)
        }
    }
}
