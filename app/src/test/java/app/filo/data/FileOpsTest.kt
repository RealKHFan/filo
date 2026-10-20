package app.filo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException

class FileOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(path: String, text: String = path): File =
        File(tmp.root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun `unique target adds a number before the extension`() {
        file("photo.jpg")
        file("photo (1).jpg")
        assertEquals("photo (2).jpg", FileOps.uniqueTarget(tmp.root, "photo.jpg").name)
        assertEquals("notes", FileOps.uniqueTarget(tmp.root, "notes").name)
        File(tmp.root, "notes").mkdir()
        assertEquals("notes (1)", FileOps.uniqueTarget(tmp.root, "notes").name)
        file(".bashrc")
        assertEquals(".bashrc (1)", FileOps.uniqueTarget(tmp.root, ".bashrc").name)
    }

    @Test fun `name validation`() {
        assertTrue(FileOps.isValidName("Holiday 2026.jpg"))
        assertTrue(FileOps.isValidName(".hidden"))
        listOf("", " ", ".", "..", "a/b", " lead", "trail ", "x".repeat(256)).forEach {
            assertFalse("'$it' should be invalid", FileOps.isValidName(it))
        }
    }

    @Test fun `list hides dot files unless asked and never shows the trash folder`() {
        file("a.txt")
        file(".secret")
        File(tmp.root, TrashManager.DIR_NAME).mkdir()
        File(tmp.root, "dir").mkdir()
        file("dir/x.txt")
        file("dir/.y")
        val visible = FileOps.list(tmp.root, showHidden = false)
        assertEquals(setOf("a.txt", "dir"), visible.map { it.name }.toSet())
        assertEquals(1, visible.first { it.name == "dir" }.childCount)
        val all = FileOps.list(tmp.root, showHidden = true)
        assertEquals(setOf("a.txt", "dir", ".secret"), all.map { it.name }.toSet())
        assertEquals(2, all.first { it.name == "dir" }.childCount)
    }

    @Test fun `copy file and folder keeps content and dates`() {
        val src = file("src/a.txt", "hello")
        src.setLastModified(1_600_000_000_000)
        file("src/sub/b.txt", "world")
        val dest = File(tmp.root, "dest").apply { mkdir() }
        var bytes = 0L
        val copied = FileOps.copy(File(tmp.root, "src"), dest, onBytes = { bytes += it })
        assertEquals("hello", File(copied, "a.txt").readText())
        assertEquals("world", File(copied, "sub/b.txt").readText())
        assertEquals(1_600_000_000_000, File(copied, "a.txt").lastModified())
        assertEquals(10, bytes)
        assertTrue(src.exists())
        // Copy again: gets a new name instead of overwriting
        assertEquals("src (1)", FileOps.copy(File(tmp.root, "src"), dest).name)
    }

    @Test fun `a folder cannot be copied or moved into itself`() {
        val dir = File(tmp.root, "dir").apply { mkdir() }
        val inner = File(dir, "inner").apply { mkdir() }
        for (op in listOf<(File, File) -> File>({ s, d -> FileOps.copy(s, d) }, { s, d -> FileOps.move(s, d) })) {
            try {
                op(dir, inner)
                fail("expected an error")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("into itself"))
            }
        }
        assertTrue(inner.exists())
    }

    @Test fun `move renames and does nothing when already in place`() {
        val src = file("a/x.txt", "x")
        val dest = File(tmp.root, "b").apply { mkdir() }
        val moved = FileOps.move(src, dest)
        assertFalse(src.exists())
        assertEquals("x", moved.readText())
        assertEquals(moved, FileOps.move(moved, dest))
    }

    @Test fun `cancelled copy leaves nothing half-written`() {
        val big = File(tmp.root, "big.bin").apply { writeBytes(ByteArray(2_000_000)) }
        val dest = File(tmp.root, "dest").apply { mkdir() }
        var calls = 0
        try {
            FileOps.copy(big, dest, isCancelled = { calls++ > 2 })
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(0, dest.listFiles()!!.size)
    }

    @Test fun `rename handles conflicts and case-only changes`() {
        val a = file("a.txt")
        file("b.txt")
        try {
            FileOps.rename(a, "b.txt")
            fail("expected conflict")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("already exists"))
        }
        val upper = FileOps.rename(a, "A.txt")
        assertEquals("A.txt", upper.name)
        assertTrue(upper.exists())
        assertEquals(setOf("A.txt", "b.txt"), tmp.root.list()!!.toSet())
        try {
            FileOps.rename(upper, "bad/name")
            fail("expected invalid name")
        } catch (_: IOException) {
        }
    }

    @Test fun `create folder`() {
        val f = FileOps.createFolder(tmp.root, "New")
        assertTrue(f.isDirectory)
        try {
            FileOps.createFolder(tmp.root, "New")
            fail("expected exists")
        } catch (_: IOException) {
        }
    }

    @Test fun `sizes are shown like Android settings`() {
        val us = Locale.US
        assertEquals("0 B", FileOps.formatSize(0, us))
        assertEquals("999 B", FileOps.formatSize(999, us))
        assertEquals("1 kB", FileOps.formatSize(1000, us))
        assertEquals("1.5 MB", FileOps.formatSize(1_500_000, us))
        assertEquals("1 MB", FileOps.formatSize(999_999, us))
        assertEquals("82 GB", FileOps.formatSize(82_000_000_000, us))
        assertEquals("128 GB", FileOps.formatSize(128_000_000_000, us))
        assertEquals("1,5 MB", FileOps.formatSize(1_500_000, Locale.GERMANY))
    }

    @Test fun `file kinds and mime types`() {
        assertEquals(FileKind.IMAGE, FileKind.ofName("IMG_1.JPG"))
        assertEquals(FileKind.VIDEO, FileKind.ofName("clip.mkv"))
        assertEquals(FileKind.AUDIO, FileKind.ofName("song.opus"))
        assertEquals(FileKind.DOCUMENT, FileKind.ofName("cv.pdf"))
        assertEquals(FileKind.ARCHIVE, FileKind.ofName("backup.zip"))
        assertEquals(FileKind.APK, FileKind.ofName("app.apk"))
        assertEquals(FileKind.OTHER, FileKind.ofName("data.bin"))
        assertEquals("application/octet-stream", MimeTypes.of("noextension"))
    }
}
