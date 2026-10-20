package app.filo.data

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ReviewAnalyzerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_750_000_000_000L

    private fun file(path: String, bytes: ByteArray, ageDays: Int = 1): File =
        File(tmp.root, path).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
            setLastModified(now - TimeUnit.DAYS.toMillis(ageDays.toLong()))
        }

    @Test fun `finds large, duplicate, old, screenshot, installer and empty items`() {
        val photo = ByteArray(5000) { (it * 7).toByte() }
        file("DCIM/Camera/IMG_1.jpg", photo, ageDays = 5)
        file("Download/IMG_1 copy.jpg", photo, ageDays = 3)
        file("Pictures/backup/IMG_1.jpg", photo, ageDays = 1)
        file("Pictures/same-size-different.jpg", ByteArray(5000) { 1 })
        file("Movies/big.mp4", ByteArray(2_000), ageDays = 2)
        file("Download/old.pdf", ByteArray(10), ageDays = 200)
        file("Download/new.pdf", ByteArray(11), ageDays = 2)
        file("Pictures/Screenshots/Screenshot_1.png", ByteArray(20))
        file("Download/game.apk", ByteArray(30))
        File(tmp.root, "Empty/Inner").mkdirs()
        // Never reported: hidden things, Android/ and Filo's own trash
        file(".hidden/dup.jpg", photo)
        file("Pictures/.thumbnails/t.jpg", photo)
        file("Android/data/x.apk", ByteArray(30))
        file("${TrashManager.DIR_NAME}/items/abc", photo)

        val result = ReviewAnalyzer(tmp.root, now = { now }, largeThreshold = 1_500).analyze()

        assertEquals(9, result.fileCount)
        assertEquals(1, result.duplicates.size)
        val dup = result.duplicates.first()
        assertEquals(3, dup.files.size)
        assertEquals(10_000, dup.reclaimable)
        assertTrue("oldest copy first", dup.files.first().path.endsWith("DCIM/Camera/IMG_1.jpg"))

        assertEquals(setOf("IMG_1.jpg", "IMG_1 copy.jpg", "same-size-different.jpg", "big.mp4"), result.large.map { it.name }.toSet())
        assertEquals(listOf("old.pdf"), result.oldDownloads.map { it.name })
        assertEquals(listOf("Screenshot_1.png"), result.screenshots.map { it.name })
        assertEquals(listOf("game.apk"), result.installers.map { it.name })
        assertEquals(listOf("Inner"), result.emptyFolders.map { it.name })
        assertEquals(5000L * 4 + 20, result.bytesByKind[FileKind.IMAGE])
        assertEquals(2000L, result.bytesByKind[FileKind.VIDEO])
        assertEquals(21L, result.bytesByKind[FileKind.DOCUMENT])

        val after = result.without(setOf(dup.files[1].path))
        assertEquals(2, after.duplicates.first().files.size)
        val gone = after.without(setOf(dup.files[2].path))
        assertTrue("a single copy isn't a duplicate", gone.duplicates.isEmpty())
    }
}

class FileSearchTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `finds names anywhere, ignores case, respects hidden`() = runBlocking {
        listOf("Documents/Tax 2026.pdf", "Documents/old/TAX-2025.pdf", "Music/song.mp3", ".private/tax.txt")
            .forEach { File(tmp.root, it).apply { parentFile!!.mkdirs(); writeText("x") } }
        File(tmp.root, "TaxFolder").mkdir()

        val found = FileSearch.search(tmp.root, "tax", showHidden = false).last().map { it.name }.toSet()
        assertEquals(setOf("Tax 2026.pdf", "TAX-2025.pdf", "TaxFolder"), found)

        val withHidden = FileSearch.search(tmp.root, "TAX", showHidden = true).last().map { it.name }.toSet()
        assertTrue("tax.txt" in withHidden)

        assertTrue(FileSearch.search(tmp.root, "  ", showHidden = false).last().isEmpty())
        assertEquals(2, FileSearch.search(tmp.root, "tax", showHidden = false, limit = 2).last().size)
    }
}
