package app.filo.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class VaultFolderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val deviceKey = SoftwareKeyWrap()
    private val kdf = PinKdf(iterations = 1_000)

    private fun vault() = VaultManager(File(tmp.root, "vault"), File(tmp.root, "cache"), deviceKey, kdf)

    private val storage get() = File(tmp.root, "storage")
    private val download get() = File(storage, "Download")

    private fun file(path: String, text: String = path) =
        File(storage, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun VaultManager.item(name: String) = items.value.single { it.name == name }

    @Test fun `hide a whole folder with subfolders, then move it back where it came from`() {
        val v = vault()
        v.setup("123456")
        file("Pictures/Trip/day1.jpg", "one")
        file("Pictures/Trip/Beach/sea.jpg", "sea")
        File(storage, "Pictures/Trip/Empty").mkdirs()
        val trip = File(storage, "Pictures/Trip")

        assertEquals(2, v.hide(listOf(trip)))
        assertFalse("original removed after everything was hidden", trip.exists())
        assertEquals(setOf("Trip", "Trip/Beach", "Trip/Empty"), v.folders.value)
        assertEquals("Trip", v.item("day1.jpg").folder)
        assertEquals("Trip/Beach", v.item("sea.jpg").folder)

        // Folder names are encrypted like file names
        File(tmp.root, "vault").walk().filter { it.isFile }.forEach { f ->
            val raw = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse("folder name visible in ${f.name}", raw.contains("Beach") || raw.contains("Trip"))
        }

        // Still there after "restarting" the app
        v.lock()
        val again = vault()
        assertEquals(UnlockResult.Success, again.unlockWithPin("123456"))
        assertEquals(setOf("Trip", "Trip/Beach", "Trip/Empty"), again.folders.value)

        val back = again.restoreFolder("Trip", download)
        assertEquals(trip, back)
        assertEquals("one", File(trip, "day1.jpg").readText())
        assertEquals("sea", File(trip, "Beach/sea.jpg").readText())
        assertTrue("empty subfolders come back too", File(trip, "Empty").isDirectory)
        assertTrue(again.items.value.isEmpty())
        assertTrue(again.folders.value.isEmpty())
    }

    @Test fun `a folder whose old place is gone comes back in Download`() {
        val v = vault()
        v.setup("123456")
        file("Old/Place/Secret/a.txt", "a")
        v.hide(listOf(File(storage, "Old/Place/Secret")))
        File(storage, "Old").deleteRecursively()
        val back = v.restoreFolder("Secret", download)
        assertEquals(File(download, "Secret"), back)
        assertEquals("a", File(back, "a.txt").readText())
    }

    @Test fun `a folder can be hidden into another folder, names never clash`() {
        val v = vault()
        v.setup("123456")
        v.createFolder("", "Private")
        file("A/Docs/x.txt")
        file("B/Docs/y.txt")
        v.hide(listOf(File(storage, "A/Docs")), into = "Private")
        v.hide(listOf(File(storage, "B/Docs")), into = "Private")
        assertEquals(setOf("Private", "Private/Docs", "Private/Docs (1)"), v.folders.value)
        assertEquals("Private/Docs", v.item("x.txt").folder)
        assertEquals("Private/Docs (1)", v.item("y.txt").folder)
    }

    @Test fun `create, rename, move and delete folders`() {
        val v = vault()
        v.setup("123456")
        assertEquals("Work", v.createFolder("", "Work"))
        assertEquals("Work/2026", v.createFolder("Work", "2026"))
        try {
            v.createFolder("", "Work")
            fail("duplicate folder")
        } catch (_: IOException) {
        }
        try {
            v.createFolder("", "a/b")
            fail("slash in a name")
        } catch (_: IOException) {
        }

        val photo = v.add(file("DCIM/p.jpg"))
        val note = v.add(file("Documents/n.txt"), folder = "Work/2026")
        assertEquals("", photo.folder)

        // Move a file into a folder and a folder into another one
        v.createFolder("", "Archive")
        v.move(listOf(photo.id), emptyList(), "Work")
        assertEquals("Work", v.item("p.jpg").folder)
        v.move(emptyList(), listOf("Work"), "Archive")
        assertEquals(setOf("Archive", "Archive/Work", "Archive/Work/2026"), v.folders.value)
        assertEquals("Archive/Work", v.item("p.jpg").folder)
        assertEquals("Archive/Work/2026", v.item("n.txt").folder)

        // Never into itself
        try {
            v.move(emptyList(), listOf("Archive"), "Archive/Work")
            fail("moved a folder into itself")
        } catch (_: IOException) {
        }

        // Rename a folder: everything inside follows
        v.renameFolder("Archive/Work", "Job")
        assertEquals(setOf("Archive", "Archive/Job", "Archive/Job/2026"), v.folders.value)
        assertEquals("Archive/Job/2026", v.item("n.txt").folder)
        v.renameItem(note.id, "notes.txt")
        assertEquals("Archive/Job/2026", v.item("notes.txt").folder)

        // Delete a folder: the files inside are gone for good
        val blobs = File(tmp.root, "vault/blobs")
        assertEquals(2, blobs.listFiles()!!.size)
        v.deleteFolder("Archive/Job/2026")
        assertEquals(setOf("Archive", "Archive/Job"), v.folders.value)
        assertEquals(listOf("p.jpg"), v.items.value.map { it.name })
        assertEquals(1, blobs.listFiles()!!.size)
    }

    @Test fun `move out a mix of files and folders`() {
        val v = vault()
        v.setup("123456")
        file("Music/Set/a.mp3", "a")
        v.hide(listOf(File(storage, "Music/Set")))
        val loose = v.add(file("Download/b.txt", "b"))
        val restored = v.restoreEverything(listOf(loose), listOf("Set"), download)
        assertEquals(setOf(File(storage, "Music/Set"), File(download, "b.txt")), restored.toSet())
        assertTrue(v.items.value.isEmpty())
        assertEquals("a", File(storage, "Music/Set/a.mp3").readText())
    }

    @Test fun `a hidden folder that fails half way leaves the original untouched`() {
        val v = vault()
        v.setup("123456")
        file("Stuff/one.txt", "1")
        file("Stuff/two.txt", "2")
        val stuff = File(storage, "Stuff")
        var calls = 0
        try {
            v.hide(listOf(stuff)) {
                if (++calls == 2) throw IOException("disk full")
            }
            fail("expected failure")
        } catch (_: IOException) {
        }
        assertEquals("1", File(stuff, "one.txt").readText())
        assertEquals("2", File(stuff, "two.txt").readText())
        assertTrue("half copy removed", v.items.value.isEmpty() && v.folders.value.isEmpty())
        assertEquals(0, File(tmp.root, "vault/blobs").listFiles()!!.size)
    }

    @Test fun `a file saved into the folder while it is being hidden stays where it is`() {
        val v = vault()
        v.setup("123456")
        file("Camera/one.jpg", "1")
        val camera = File(storage, "Camera")
        val late = File(camera, "late.jpg")
        assertEquals(1, v.hide(listOf(camera)) { if (!late.exists()) late.writeText("new photo") })
        assertEquals("new photo", late.readText())
        assertFalse(File(camera, "one.jpg").exists())
        assertEquals(listOf("one.jpg"), v.items.value.map { it.name })
    }

    @Test fun `vault from before folders still opens`() {
        val v = vault()
        v.setup("123456")
        val data = "old".toByteArray()
        val item = v.add(file("x.txt", "old"))
        v.lock()
        // Same index file, read again: no folder data means "top"
        val again = vault()
        again.unlockWithPin("123456")
        assertEquals("", again.items.value.single().folder)
        assertArrayEquals(data, again.readBytes(item))
    }

    @Test fun `view settings key doesn't reveal the folder name`() {
        val v = vault()
        v.setup("123456")
        val key = v.viewKey("Secret plans")
        assertTrue(key.startsWith("vault:"))
        assertFalse(key.contains("Secret"))
        assertEquals(key, v.viewKey("Secret plans"))
        assertNotEquals(key, v.viewKey("Other"))
        v.lock()
        vault().unlockWithPin("123456")
        val again = vault().apply { unlockWithPin("123456") }
        assertEquals("same key after unlocking again", key, again.viewKey("Secret plans"))
    }

    @Test fun `open folder goes back to the top on lock`() {
        val v = vault()
        v.setup("123456")
        v.createFolder("", "A")
        v.openFolder.value = "A"
        v.lock()
        assertEquals("", v.openFolder.value)
    }
}
