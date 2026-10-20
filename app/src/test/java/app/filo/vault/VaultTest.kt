package app.filo.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class StreamCipherTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val chunk = StreamCipher.CHUNK_SIZE

    private fun encrypt(data: ByteArray, k: SecretKey = key) =
        ByteArrayOutputStream().also { StreamCipher.encrypt(ByteArrayInputStream(data), it, k) }.toByteArray()

    private fun decrypt(data: ByteArray, k: SecretKey = key) =
        ByteArrayOutputStream().also { StreamCipher.decrypt(ByteArrayInputStream(data), it, k) }.toByteArray()

    private fun assertRejected(data: ByteArray, k: SecretKey = key) {
        try {
            decrypt(data, k)
            fail("tampered data was accepted")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test fun `round trip at every chunk boundary`() {
        val random = SecureRandom()
        for (size in listOf(0, 1, 15, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 17, 1_000_000)) {
            val plain = ByteArray(size).also(random::nextBytes)
            val sealed = encrypt(plain)
            assertArrayEquals("size $size", plain, decrypt(sealed))
            val chunks = maxOf(1, (size + chunk - 1) / chunk)
            assertEquals("size $size", 12 + size + 16L * chunks, sealed.size.toLong())
        }
    }

    @Test fun `same file encrypts differently each time`() {
        val plain = "same".toByteArray()
        assertFalse(encrypt(plain).contentEquals(encrypt(plain)))
    }

    @Test fun `any change, cut, reorder, extra data or wrong key is detected`() {
        val plain = ByteArray(3 * chunk + 100) { it.toByte() }
        val sealed = encrypt(plain)
        val sealedChunk = chunk + 16
        val header = 12

        assertRejected(sealed.copyOf().also { it[header + 5] = (it[header + 5] + 1).toByte() }) // flipped byte
        assertRejected(sealed.copyOf().also { it[3] = 0 }) // header changed
        assertRejected(sealed.copyOf(header + 2 * sealedChunk)) // cut at a chunk boundary
        assertRejected(sealed.copyOf(sealed.size - 1)) // cut by one byte
        assertRejected(sealed + byteArrayOf(1, 2, 3)) // extra data
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, header + sealedChunk, swapped, header, sealedChunk)
        System.arraycopy(sealed, header, swapped, header + sealedChunk, sealedChunk)
        assertRejected(swapped) // chunks reordered
        assertRejected(sealed, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        assertRejected("not a vault file at all".toByteArray())
    }
}

class VaultManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000L
    private val deviceKey = SoftwareKeyWrap()
    private val kdf = PinKdf(iterations = 1_000) // fast for tests; the app uses 150 000

    private fun vault() = VaultManager(File(tmp.root, "vault"), File(tmp.root, "cache"), deviceKey, kdf) { now }

    private fun storageFile(name: String, bytes: ByteArray) =
        File(tmp.root, "storage/DCIM/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    @Test fun `PIN rules`() {
        assertTrue(VaultManager.isValidPin("012345"))
        listOf("12345", "1234567", "12a456", "", "١٢٣٤٥٦").forEach { assertFalse(it, VaultManager.isValidPin(it)) }
        val v = vault()
        try {
            v.setup("1234")
            fail("short PIN accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(v.isSetUp)
    }

    @Test fun `hide a file, open it, lock, unlock, restore`() {
        val v = vault()
        v.setup("246810")
        assertTrue(v.unlocked.value)
        val bytes = ByteArray(200_000).also(SecureRandom()::nextBytes)
        val original = storageFile("secret.jpg", bytes)

        val item = v.add(original)
        assertFalse("original removed", original.exists())
        assertEquals(listOf(item), v.items.value)
        assertEquals("secret.jpg", item.name)
        assertArrayEquals(bytes, v.readBytes(item))

        // Nothing readable on disk: no file names, no content
        val vaultDir = File(tmp.root, "vault")
        vaultDir.walk().filter { it.isFile }.forEach { f ->
            val raw = f.readBytes()
            assertFalse("name visible in ${f.name}", String(raw, Charsets.ISO_8859_1).contains("secret.jpg"))
            assertFalse("content visible in ${f.name}", raw.size >= 64 && String(raw, Charsets.ISO_8859_1).contains(String(bytes.copyOfRange(1000, 1032), Charsets.ISO_8859_1)))
        }

        v.lock()
        assertFalse(v.unlocked.value)
        assertTrue(v.items.value.isEmpty())
        try {
            v.readBytes(item)
            fail("read while locked")
        } catch (_: IllegalStateException) {
        }

        // "Restart the app": a new manager reads everything back
        val again = vault()
        assertTrue(again.isSetUp)
        assertEquals(UnlockResult.Success, again.unlockWithPin("246810"))
        assertEquals(listOf(item), again.items.value)

        val restored = again.restore(again.items.value.first(), File(tmp.root, "fallback"))
        assertEquals(original.absolutePath, restored.absolutePath)
        assertArrayEquals(bytes, restored.readBytes())
        assertTrue(again.items.value.isEmpty())
    }

    @Test fun `restore falls back when the original folder is gone`() {
        val v = vault()
        v.setup("111111")
        val f = storageFile("a.txt", "hi".toByteArray())
        val item = v.add(f)
        f.parentFile!!.deleteRecursively()
        val fallback = File(tmp.root, "storage/Download")
        assertEquals(File(fallback, "a.txt"), v.restore(item, fallback))
    }

    @Test fun `wrong PINs give five tries, then waits that grow`() {
        val v = vault()
        v.setup("123456")
        v.lock()
        for (left in 4 downTo 1) assertEquals(UnlockResult.WrongPin(left), v.unlockWithPin("000000"))
        val lockout = v.unlockWithPin("000000")
        assertEquals(UnlockResult.LockedOut(now + 30_000), lockout)
        // Even the right PIN is refused during the wait
        assertTrue(v.unlockWithPin("123456") is UnlockResult.LockedOut)
        now += 30_001
        assertEquals(UnlockResult.LockedOut(now + 60_000), v.unlockWithPin("999999"))
        now += 60_001
        assertEquals(UnlockResult.Success, v.unlockWithPin("123456"))
        v.lock()
        assertEquals("counter reset after success", UnlockResult.WrongPin(4), v.unlockWithPin("000000"))

        assertEquals(30_000, VaultManager.lockoutDelay(5))
        assertEquals(120_000, VaultManager.lockoutDelay(7))
        assertEquals(3_600_000, VaultManager.lockoutDelay(40))
        assertEquals(0, VaultManager.lockoutDelay(4))
    }

    @Test fun `lockout survives an app restart`() {
        vault().apply { setup("123456"); lock() }
        repeat(5) { vault().unlockWithPin("000000") }
        assertTrue(vault().unlockWithPin("123456") is UnlockResult.LockedOut)
    }

    @Test fun `change PIN`() {
        val v = vault()
        v.setup("123456")
        assertFalse(v.changePin("654321", "111111"))
        assertTrue(v.changePin("123456", "111111"))
        v.lock()
        assertTrue(v.unlockWithPin("123456") is UnlockResult.WrongPin)
        assertEquals(UnlockResult.Success, v.unlockWithPin("111111"))
    }

    @Test fun `locking while a file is being hidden loses nothing`() {
        val v = vault()
        v.setup("123456")
        val first = v.add(storageFile("one.txt", "1".toByteArray()))
        var locked = false
        val second = v.add(storageFile("two.bin", ByteArray(300_000))) {
            if (!locked) {
                locked = true
                v.lock() // Filo left the screen mid-way
            }
        }
        assertTrue(locked)
        assertEquals(UnlockResult.Success, v.unlockWithPin("123456"))
        assertEquals(setOf(first.id, second.id), v.items.value.map { it.id }.toSet())
    }

    @Test fun `delete, decrypt for viewing, erase`() {
        val v = vault()
        v.setup("123456")
        val a = v.add(storageFile("a.mp4", ByteArray(1234)))
        val b = v.add(storageFile("b.pdf", "pdf".toByteArray()))
        val viewing = v.decryptForViewing(b)
        assertEquals("pdf", viewing.readText())
        v.delete(a)
        assertEquals(listOf(b), v.items.value)
        v.lock()
        assertEquals(UnlockResult.Success, v.unlockWithPin("123456"))
        assertFalse("temporary copies are removed on unlock", viewing.exists())
        v.erase()
        assertFalse(v.isSetUp)
        assertFalse(File(tmp.root, "vault").exists())
    }

    @Test fun `fingerprint unlock with a second sealed copy`() {
        val v = vault()
        v.setup("123456")
        val bioKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        v.enableBiometric(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, bioKey) })
        assertTrue(v.biometricEnabled)
        v.lock()
        repeat(4) { v.unlockWithPin("000000") }
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, bioKey, GCMParameterSpec(128, v.biometricIv()))
        }
        assertTrue(v.unlockWithBiometric(decrypt))
        assertTrue(v.unlocked.value)
        v.lock()
        assertEquals("fingerprint resets wrong-PIN count", UnlockResult.WrongPin(4), v.unlockWithPin("000000"))
        v.disableBiometric()
        assertFalse(v.biometricEnabled)
    }

    @Test fun `vault copied to another phone can't be opened even with the PIN`() {
        val v = vault()
        v.setup("123456")
        v.lock()
        val elsewhere = VaultManager(File(tmp.root, "vault"), File(tmp.root, "cache"), SoftwareKeyWrap(), kdf) { now }
        try {
            elsewhere.unlockWithPin("123456")
            fail("opened without this phone's hardware key")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test fun `real PIN strength setting derives a key`() {
        val start = System.nanoTime()
        val k = PinKdf().derive("123456", ByteArray(16))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(32, k.encoded.size)
        assertEquals(150_000, PinKdf.DEFAULT_ITERATIONS)
        println("PBKDF2 with 150k iterations took $ms ms on this machine")
    }
}
