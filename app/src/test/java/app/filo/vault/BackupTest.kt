package app.filo.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Properties
import java.util.concurrent.CancellationException

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    // Light settings so the tests are quick; the app uses 64 MB and 3 passes.
    private val fastKdf = PasswordKdf(memoryKiB = 8_192, iterations = 1, parallelism = 1)
    private val password = "correct horse battery staple"

    /** Each "phone" has its own hardware key, PIN and storage. */
    private fun phone(name: String, pin: String = "123456") =
        VaultManager(File(tmp.root, "$name/vault"), File(tmp.root, "$name/cache"), SoftwareKeyWrap(), PinKdf(1_000)).apply { setup(pin) }

    private fun file(path: String, text: String) = File(tmp.root, "files/$path").apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun export(vault: VaultManager, extras: Properties = Properties(), pw: String = password): ByteArray {
        val out = ByteArrayOutputStream()
        BackupFile.create(out, pw, fastKdf).use { vault.writeBackup(it, extras) }
        return out.toByteArray()
    }

    private fun import(vault: VaultManager, backup: ByteArray, pw: String = password) =
        vault.readBackup(BackupFile.open(ByteArrayInputStream(backup), pw))

    private fun contents(vault: VaultManager) =
        vault.items.value.associate { "${it.folder}/${it.name}" to String(vault.readBytes(it)!!) }

    @Test fun `everything moves to another phone with a different PIN`() {
        val old = phone("old")
        old.hide(listOf(file("note.txt", "top")))
        file("Trips/2026/beach.jpg", "sand")
        file("Trips/map.png", "map")
        File(tmp.root, "files/Trips/empty").mkdirs()
        old.hide(listOf(File(tmp.root, "files/Trips")))
        val big = file("big.bin", "x".repeat(3 * StreamCipher.CHUNK_SIZE + 5))
        old.hide(listOf(big))
        val extras = Properties().apply { setProperty("setting.themeMode", "DARK") }

        val backup = export(old, extras)
        assertFalse("nothing readable inside", String(backup, Charsets.ISO_8859_1).contains("beach"))

        val new = phone("new", pin = "999999")
        val read = import(new, backup)
        assertEquals(4, read.added)
        assertEquals(0, read.skipped)
        assertEquals("DARK", read.extras.getProperty("setting.themeMode"))
        assertEquals(contents(old), contents(new))
        assertEquals(setOf("Trips", "Trips/2026", "Trips/empty"), new.folders.value)
        assertEquals(old.items.value.map { it.id }.toSet(), new.items.value.map { it.id }.toSet())

        // Still there after locking and unlocking with the new phone's own PIN.
        new.lock()
        assertEquals(UnlockResult.Success, new.unlockWithPin("999999"))
        assertEquals(contents(old), contents(new))

        // A folder moved back out goes where it came from, as on the old phone.
        File(tmp.root, "files/Trips").deleteRecursively()
        new.restoreFolder("Trips", File(tmp.root, "fallback"))
        assertEquals("sand", File(tmp.root, "files/Trips/2026/beach.jpg").readText())
    }

    @Test fun `back and forth adds only what is new`() {
        val a = phone("a")
        a.hide(listOf(file("one.txt", "1"), file("two.txt", "2")))
        val b = phone("b")
        assertEquals(2, import(b, export(a)).added)

        val again = import(b, export(a))
        assertEquals("the same backup twice adds nothing", 0, again.added)
        assertEquals(2, again.skipped)

        b.hide(listOf(file("three.txt", "3")))
        b.renameItem(b.items.value.first { it.name == "one.txt" }.id, "uno.txt")
        val back = import(a, export(b))
        assertEquals("only the new file comes back", 1, back.added)
        assertEquals(setOf("one.txt", "two.txt", "three.txt"), a.items.value.map { it.name }.toSet())

        // The same photo hidden separately on both phones isn't doubled either.
        val c = phone("c")
        c.hide(listOf(file("two.txt", "2")))
        assertEquals(2, import(c, export(a)).added)
        assertEquals(3, c.items.value.size)
    }

    @Test fun `wrong password, other files and impossible settings are refused`() {
        val vault = phone("p")
        vault.hide(listOf(file("a.txt", "secret")))
        val backup = export(vault)
        try {
            import(phone("q"), backup, pw = "correct horse battery stapl")
            fail("wrong password accepted")
        } catch (_: WrongPasswordException) {
        }
        try {
            BackupFile.open(ByteArrayInputStream("PK\u0003\u0004 a zip file".toByteArray()), password)
            fail("not a backup")
        } catch (_: NotABackupException) {
        }
        // A changed header asking for 4 GB of memory is refused before anything is allocated.
        val greedy = backup.copyOf().also { it[8] = 0x7F }
        try {
            BackupFile.open(ByteArrayInputStream(greedy), password)
            fail("greedy settings accepted")
        } catch (_: DamagedBackupException) {
        }
    }

    @Test fun `a damaged or cut-off backup keeps what was read before`() {
        val old = phone("old")
        old.hide(listOf(file("first.txt", "1")))
        old.hide(listOf(file("second.bin", "y".repeat(4 * StreamCipher.CHUNK_SIZE))))
        val backup = export(old)

        val cut = phone("cut")
        try {
            import(cut, backup.copyOf(backup.size - 3 * StreamCipher.CHUNK_SIZE))
            fail("cut-off backup accepted")
        } catch (_: DamagedBackupException) {
        }
        assertEquals(listOf("first.txt"), cut.items.value.map { it.name })
        assertEquals("no half file left behind", 1, File(tmp.root, "cut/vault/blobs").listFiles()!!.size)

        val flipped = phone("flipped")
        try {
            import(flipped, backup.copyOf().also { it[it.size - 100] = (it[it.size - 100] + 1).toByte() })
            fail("changed backup accepted")
        } catch (_: DamagedBackupException) {
        }
        assertEquals(listOf("first.txt"), flipped.items.value.map { it.name })
    }

    @Test fun `names and ids from a backup can't escape the hidden folder`() {
        fun crafted(id: String, name: String, folder: String): ByteArray {
            val out = ByteArrayOutputStream()
            BackupFile.create(out, password, fastKdf).use { sealed ->
                val data = DataOutputStream(sealed)
                fun block(p: Properties) {
                    val bytes = ByteArrayOutputStream().also { p.store(it, null) }.toByteArray()
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
                block(Properties().apply { setProperty("format", "1") })
                block(Properties())
                data.writeBoolean(true)
                data.writeUTF(id)
                data.writeUTF(name)
                data.writeUTF(folder)
                data.writeUTF("/x")
                data.writeLong(0)
                data.writeLong(4)
                data.writeInt(4)
                data.write("evil".toByteArray())
                data.writeInt(0)
                data.writeBoolean(false)
                data.flush()
            }
            return out.toByteArray()
        }
        val vault = phone("v")
        for ((id, name, folder) in listOf(
            Triple("../../../escaped", "a.txt", ""),
            Triple("0f0e0d0c-1111", "../a.txt", ""),
            Triple("0f0e0d0c-1111", "a.txt", "Trips/../.."),
            Triple("0f0e0d0c-1111", "a.txt", "/abs"),
        )) {
            try {
                import(vault, crafted(id, name, folder))
                fail("accepted $id $name $folder")
            } catch (_: DamagedBackupException) {
            }
        }
        assertTrue(vault.items.value.isEmpty())
        assertFalse(File(tmp.root, "v/escaped.bin").exists())
        assertEquals(1, import(vault, crafted("0f0e0d0c-1111", "fine.txt", "Ok/Here")).added)
        assertEquals("evil", String(vault.readBytes(vault.items.value.single())!!))
    }

    @Test fun `cancelling an export stops it`() {
        val vault = phone("p")
        vault.hide(listOf(file("a.bin", "z".repeat(3 * StreamCipher.CHUNK_SIZE))))
        var chunks = 0
        try {
            BackupFile.create(ByteArrayOutputStream(), password, fastKdf).use { vault.writeBackup(it, Properties(), onBytes = { chunks++ }, isCancelled = { chunks >= 1 }) }
            fail("not cancelled")
        } catch (_: CancellationException) {
        }
        assertEquals(1, chunks)
    }

    @Test fun `password strength`() {
        assertEquals(PasswordStrength.TOO_SHORT, PasswordStrength.of("Sh0rt!"))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.of("aaaaaaaaaaaaaaaa"))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.of("password1234"))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.of("Qwertz123456!"))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.of("abcdefghijklmnop"))
        assertTrue(PasswordStrength.of("Summer2026!!").acceptable)
        assertTrue(PasswordStrength.of("velvet-otter-cactus-91").acceptable)
        assertEquals(PasswordStrength.VERY_STRONG, PasswordStrength.of("correct horse battery staple"))
        assertFalse(PasswordStrength.WEAK.acceptable)
    }

    @Test fun `the key depends on password and salt, and the same password typed differently matches`() {
        val salt = ByteArray(16) { it.toByte() }
        val k1 = fastKdf.derive("Café au lait 2026", salt).encoded
        val decomposed = "Café au lait 2026" // é as e + accent, as some keyboards send it
        assertTrue(k1.contentEquals(fastKdf.derive(decomposed, salt).encoded))
        assertFalse(k1.contentEquals(fastKdf.derive("Café au lait 2027", salt).encoded))
        assertFalse(k1.contentEquals(fastKdf.derive("Café au lait 2026", ByteArray(16)).encoded))
    }
}
