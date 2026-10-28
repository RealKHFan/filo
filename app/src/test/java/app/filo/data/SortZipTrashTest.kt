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
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SortingTest {
    private fun entry(name: String, dir: Boolean = false, size: Long = 0, date: Long = 0) =
        FileEntry("/x/$name", name, dir, size, date)

    @Test fun `natural name order`() {
        val names = listOf("file10.txt", "File2.txt", "file1.txt", "apple", "Banana", "file02b")
        val sorted = names.sortedWith(NaturalOrder)
        assertEquals(listOf("apple", "Banana", "file1.txt", "File2.txt", "file02b", "file10.txt"), sorted)
    }

    @Test fun `folders first then by field`() {
        val list = listOf(
            entry("b.txt", size = 5, date = 3),
            entry("Zdir", dir = true),
            entry("a.txt", size = 9, date = 1),
            entry("adir", dir = true),
        )
        fun names(order: SortOrder, foldersFirst: Boolean = true) = list.sortedBy(order, foldersFirst).map { it.name }
        assertEquals(listOf("adir", "Zdir", "a.txt", "b.txt"), names(SortOrder()))
        assertEquals(listOf("Zdir", "adir", "b.txt", "a.txt"), names(SortOrder(SortField.NAME, ascending = false)))
        assertEquals(listOf("Zdir", "adir", "a.txt", "b.txt"), names(SortOrder(SortField.SIZE, ascending = false)))
        assertEquals(listOf("Zdir", "adir", "b.txt", "a.txt"), names(SortOrder(SortField.DATE, ascending = false)))
        assertEquals(listOf("b.txt", "a.txt", "Zdir", "adir"), names(SortOrder(SortField.DATE, ascending = false), foldersFirst = false))
    }
}

class ZipUtilsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `compress and extract round trip`() {
        val folder = File(tmp.root, "Trip").apply { mkdirs() }
        File(folder, "a.txt").writeText("alpha")
        File(folder, "sub").mkdir()
        File(folder, "sub/b.bin").writeBytes(ByteArray(300_000) { (it % 251).toByte() })
        val single = File(tmp.root, "notes.md").apply { writeText("# hi") }

        val zip = ZipUtils.compress(listOf(folder, single), tmp.root)
        assertEquals("Archive.zip", zip.name)
        assertEquals("Trip.zip", ZipUtils.defaultName(listOf(folder)))
        assertEquals("progress counts unpacked bytes", 5L + 300_000 + 4, ZipUtils.unpackedSize(zip))
        val broken = File(tmp.root, "broken.zip").apply { writeText("not a zip") }
        assertEquals(broken.length(), ZipUtils.unpackedSize(broken))

        var unpacked = 0L
        val out = ZipUtils.extract(zip, tmp.root, onBytes = { unpacked += it })
        assertEquals(ZipUtils.unpackedSize(zip), unpacked)
        assertEquals("Archive", out.name)
        assertEquals("alpha", File(out, "Trip/a.txt").readText())
        assertTrue(File(out, "Trip/sub/b.bin").readBytes().contentEquals(File(folder, "sub/b.bin").readBytes()))
        assertEquals("# hi", File(out, "notes.md").readText())
        // Extracting again creates "Archive (1)" instead of mixing files
        assertEquals("Archive (1)", ZipUtils.extract(zip, tmp.root).name)
    }

    @Test fun `unzipping never fills the phone, even when the zip lies about its size`() {
        val bomb = File(tmp.root, "bomb.zip")
        val dataEnd: Long
        ZipOutputStream(bomb.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("zeros.bin"))
            val mb = ByteArray(1024 * 1024)
            repeat(100) { z.write(mb) } // 100 MB that packs into about 100 kB
            z.closeEntry()
            dataEnd = bomb.length()
        }
        val roomFor20Mb = tmp.root.usableSpace - 20_000_000

        // Honest size: refused before anything is written
        var written = 0L
        try {
            ZipUtils.extract(bomb, tmp.root, onBytes = { written += it }, keepFree = roomFor20Mb)
            fail("unzipped 100 MB with room for 20")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Not enough free space"))
        }
        assertEquals(0L, written)
        assertFalse(File(tmp.root, "bomb").exists())

        // Lying zip (its list of contents cut off, so the size can't be known first): stopped on the way
        java.io.RandomAccessFile(bomb, "rw").use { it.setLength(dataEnd) }
        try {
            ZipUtils.extract(bomb, tmp.root, onBytes = { written += it }, keepFree = roomFor20Mb)
            fail("unzipped 100 MB with room for 20")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Not enough free space"))
        }
        assertTrue("stopped early, not after $written bytes", written in 1..60_000_000)
        assertFalse("half-unpacked folder removed", File(tmp.root, "bomb").exists())
    }

    @Test fun `zip slip is refused and leaves nothing behind`() {
        val evil = File(tmp.root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("ok.txt")); z.write("fine".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("../../escaped.txt")); z.write("bad".toByteArray()); z.closeEntry()
        }
        val dest = File(tmp.root, "dest").apply { mkdir() }
        try {
            ZipUtils.extract(evil, dest)
            fail("expected refusal")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("unsafe"))
        }
        assertFalse(File(tmp.root, "escaped.txt").exists())
        assertEquals(0, dest.listFiles()!!.size)
    }
}

class TrashManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_700_000_000_000L
    private val trash by lazy { TrashManager(File(tmp.root, TrashManager.DIR_NAME)) { now } }

    @Test fun `move, list, restore`() {
        val file = File(tmp.root, "Download/report.pdf").apply { parentFile!!.mkdirs(); writeText("pdf") }
        val item = trash.moveToTrash(file)
        assertFalse(file.exists())
        assertEquals(listOf(item), trash.list())
        assertEquals(3, item.size)
        assertTrue(File(tmp.root, "${TrashManager.DIR_NAME}/.nomedia").exists())

        val restored = trash.restore(item)
        assertEquals(file, restored)
        assertEquals("pdf", restored.readText())
        assertTrue(trash.list().isEmpty())
    }

    @Test fun `restore recreates a deleted parent and avoids overwriting`() {
        val dir = File(tmp.root, "Music/Album").apply { mkdirs() }
        val song = File(dir, "song.mp3").apply { writeText("v1") }
        val item = trash.moveToTrash(song)
        dir.deleteRecursively()
        assertEquals("v1", trash.restore(item).readText())

        val again = trash.moveToTrash(File(dir, "song.mp3"))
        File(dir, "song.mp3").writeText("new")
        val restored = trash.restore(again)
        assertEquals("song (1).mp3", restored.name)
        assertEquals("new", File(dir, "song.mp3").readText())
    }

    @Test fun `folders can be trashed and restored`() {
        val dir = File(tmp.root, "Projects").apply { mkdirs() }
        File(dir, "a.txt").writeText("12345")
        val item = trash.moveToTrash(dir)
        assertTrue(item.isDirectory)
        assertEquals(5, item.size)
        assertEquals("12345", File(trash.restore(item), "a.txt").readText())
    }

    @Test fun `items expire after 30 days`() {
        val a = trash.moveToTrash(File(tmp.root, "a").apply { writeText("a") })
        now += TimeUnit.DAYS.toMillis(10)
        val b = trash.moveToTrash(File(tmp.root, "b").apply { writeText("b") })
        assertEquals(20, trash.daysLeft(a))
        assertEquals(30, trash.daysLeft(b))
        now += TimeUnit.DAYS.toMillis(21)
        assertEquals(1, trash.purgeExpired())
        assertEquals(listOf(b), trash.list())
        trash.empty()
        assertTrue(trash.list().isEmpty())
        assertEquals(0, File(tmp.root, "${TrashManager.DIR_NAME}/items").listFiles()!!.size)
    }
}

class ViewPrefsTest {
    private val root = java.io.File("/storage/emulated/0")

    @Test fun `photo folders start as a grid, others as a list`() {
        fun level(path: String) = ViewPrefsStore.defaultsForFolder(java.io.File(root, path), root).level
        assertEquals(ViewLevel.GRID_3, level("DCIM"))
        assertEquals(ViewLevel.GRID_3, level("DCIM/Camera"))
        assertEquals(ViewLevel.GRID_3, level("Pictures/Screenshots"))
        assertEquals(ViewLevel.GRID_3, level("Download/Screenshots"))
        assertEquals(ViewLevel.LIST, level("Download"))
        assertEquals(ViewLevel.LIST, level("Documents/Work"))
        assertEquals(ViewLevel.GRID_3, ViewPrefsStore.defaultsForCategory(Category.IMAGES).level)
        assertEquals(ViewLevel.LIST, ViewPrefsStore.defaultsForCategory(Category.AUDIO).level)
    }

    @Test fun `pinch steps and saving`() {
        assertEquals(ViewLevel.LIST_LARGE, ViewLevel.LIST.bigger())
        assertEquals(ViewLevel.LIST_LARGE, ViewLevel.LIST_LARGE.bigger())
        assertEquals(ViewLevel.GRID_2, ViewLevel.LIST.smaller())
        assertEquals(ViewLevel.GRID_5, ViewLevel.GRID_5.smaller())
        val prefs = ViewPrefs(ViewLevel.GRID_4, SortOrder(SortField.SIZE, ascending = false))
        assertEquals(prefs, ViewPrefsStore.decode(ViewPrefsStore.encode(prefs)))
        assertEquals(null, ViewPrefsStore.decode("garbage"))
    }
}
