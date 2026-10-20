package app.filo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.filo.Graph
import app.filo.data.AppInfo
import app.filo.data.AppsSource
import app.filo.data.Category
import app.filo.data.FileEntry
import app.filo.data.FileKind
import app.filo.data.MediaSource
import app.filo.data.StoragePermission
import app.filo.data.ThemeMode
import app.filo.data.TrashManager
import app.filo.vault.BiometricKey
import app.filo.vault.PinKdf
import app.filo.vault.SoftwareKeyWrap
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.allowHardware
import coil3.imageDecoderEnabled
import coil3.video.VideoFrameDecoder
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/** Index of the phone's media, faked from the test folder. */
private class FakeMedia(private val root: File) : MediaSource {
    private fun all(showHidden: Boolean) = root.walkTopDown()
        .onEnter { it == root || ((showHidden || !it.name.startsWith(".")) && it.name != TrashManager.DIR_NAME) }
        .filter { it.isFile && (showHidden || !it.name.startsWith(".")) }
        .map { FileEntry.from(it) }
        .sortedByDescending { it.lastModified }
        .toList()

    override suspend fun category(category: Category, showHidden: Boolean): List<FileEntry> {
        val kinds = when (category) {
            Category.IMAGES -> setOf(FileKind.IMAGE)
            Category.VIDEOS -> setOf(FileKind.VIDEO)
            Category.AUDIO -> setOf(FileKind.AUDIO)
            Category.DOCUMENTS -> setOf(FileKind.DOCUMENT)
            Category.APPS -> setOf(FileKind.APK)
        }
        return all(showHidden).filter { it.kind in kinds }
    }

    override suspend fun recent(limit: Int, showHidden: Boolean) = all(showHidden).take(limit)

    override suspend fun categorySizes(showHidden: Boolean) =
        Category.entries.associateWith { c -> category(c, showHidden).sumOf { it.size } }
}

private object FakeApps : AppsSource {
    private val all = listOf(
        AppInfo("org.example.notes", "Notes", "2.1", 14_500_000, null, false, 1_760_000_000_000, listOf("/x/base.apk")),
        AppInfo("org.example.maps", "Offline Maps", "5.0.3", 96_000_000, null, false, 1_755_000_000_000, listOf("/x/base.apk", "/x/split.apk")),
        AppInfo("org.example.camera", "Camera", "9", 41_000_000, null, true, 1_700_000_000_000, listOf("/x/base.apk")),
    )

    override suspend fun installed(includeSystem: Boolean, fullSizes: Boolean) = all.filter { includeSystem || !it.isSystem }
    override fun hasUsageAccess() = false
}

private object NoBiometric : BiometricKey {
    override fun newEncryptCipher(): Cipher = throw UnsupportedOperationException()
    override fun decryptCipher(iv: ByteArray): Cipher = throw UnsupportedOperationException()
    override fun delete() {}
}

@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class AppUiTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var root: File
    private lateinit var graph: Graph
    private val now = System.currentTimeMillis()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("filo", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "vault").deleteRecursively()
        root = tmp.newFolder("storage")
        seed()
        // A 128 GB phone with 46 GB free, and a picture loader the simulator can run.
        ShadowStatFs.registerStats(root, 31_250_000, 11_230_469, 11_230_469)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).allowHardware(false).imageDecoderEnabled(false).components { add(VideoFrameDecoder.Factory()) }.build())
        graph = newGraph(context, granted = true)
    }

    private fun newGraph(context: Context, granted: Boolean) = Graph(
        context,
        storageRoot = root,
        permission = StoragePermission { granted },
        media = FakeMedia(root),
        apps = FakeApps,
        deviceKey = SoftwareKeyWrap(),
        biometricKey = NoBiometric,
        pinKdf = PinKdf(1_000),
    ).also { g -> g.settings.update { it.copy(dynamicColor = false) } }

    private fun picture(path: String, color: Int, ageDays: Int) {
        val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(color)
            drawCircle(150f, 90f, 40f, Paint().apply { this.color = Color.WHITE; alpha = 170 })
        }
        val f = File(root, path).apply { parentFile!!.mkdirs() }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f.setLastModified(now - TimeUnit.DAYS.toMillis(ageDays.toLong()))
    }

    private fun file(path: String, bytes: Int, ageDays: Int) {
        File(root, path).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(bytes) { (it % 97).toByte() })
            setLastModified(now - TimeUnit.DAYS.toMillis(ageDays.toLong()))
        }
    }

    private fun seed() {
        picture("DCIM/Camera/IMG_0001.png", 0xFF1D9E75.toInt(), 1)
        picture("DCIM/Camera/IMG_0002.png", 0xFF378ADD.toInt(), 2)
        picture("DCIM/Camera/IMG_0003.png", 0xFFD85A30.toInt(), 3)
        picture("Pictures/Screenshots/Screenshot_20261019.png", 0xFF7F77DD.toInt(), 1)
        file("Download/Invoice October.pdf", 182_000, 1)
        file("Download/Boarding pass.pdf", 64_000, 120)
        file("Download/Podcast episode 12.mp3", 4_200_000, 160)
        file("Download/Photos.zip", 2_400_000, 4)
        file("Download/OldGame.apk", 3_100_000, 200)
        file("Documents/Notes.txt", 2_000, 5)
        file("Documents/Budget 2026.xlsx", 18_000, 6)
        file("Movies/Holiday.mp4", 150_000_000, 30)
        file("Music/Duplicate song.mp3", 5_100_000, 40)
        file("Music/Copy of Duplicate song.mp3", 5_100_000, 10)
        file("Pictures/.thumbnails/thumb1.jpg", 4_000, 1)
        File(root, "Alarms").mkdirs()
        File(root, "Old empty folder").mkdirs()
    }

    private fun show() {
        compose.setContent { FiloApp(graph) }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        Thread.sleep(400) // let thumbnails finish decoding
        compose.waitForIdle()
        captureScreenRoboImage("build/screenshots/$name.png")
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun clickable(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text) and hasClickAction())

    private fun described(desc: String): SemanticsNodeInteraction = compose.onNode(hasContentDescription(desc))

    private fun openTab(name: String) {
        clickable(name).performClick()
        compose.waitForIdle()
    }

    private fun typePin(pin: String) = pin.forEach { clickable(it.toString()).performClick() }

    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    // ------------------------------------------------------------------

    @Test fun browseShowsStorageCategoriesAndCollections() {
        show()
        waitFor("Recent")
        compose.onAllNodes(hasText("SD card")).fetchSemanticsNodes().let { assertTrue("no phantom SD card", it.isEmpty()) }
        waitFor("used", substring = true)
        listOf("Search in Filo", "Internal storage", "Downloads", "Images", "Videos", "Audio", "Documents", "Apps", "Favorites", "Hidden folder")
            .forEach { compose.onNodeWithText(it).assertExists() }
        // Each category shows how much space it takes
        val imageBytes = root.walkTopDown().filter { it.extension == "png" && !it.path.contains("/.") }.sumOf { it.length() }
        waitFor(app.filo.data.FileOps.formatSize(imageBytes))
        waitFor(app.filo.data.FileOps.formatSize(150_000_000)) // Movies/Holiday.mp4 under Videos
        described("Open menu").assertExists()
        assertTrue("settings moved to the menu", compose.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isEmpty())
        shot("01_browse")
    }

    @Test fun browseInDarkTheme() {
        graph.settings.update { it.copy(themeMode = ThemeMode.DARK) }
        show()
        waitFor("Recent")
        shot("02_browse_dark")
    }

    @Test fun storageTabOpensInternalStorageWithBreadcrumbs() {
        show()
        openTab("Storage")
        waitFor("DCIM")
        listOf("Download", "Documents", "Movies", "Music", "Pictures").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onAllNodes(hasText(".thumbnails")).fetchSemanticsNodes().let { assertTrue("hidden files are hidden", it.isEmpty()) }
        shot("03_storage")

        clickable("Download").performClick()
        waitFor("Invoice October.pdf")
        clickable("Internal storage").assertExists() // breadcrumb back to the top
        shot("04_folder_download")

        described("Show as grid").performClick()
        waitFor("Invoice October.pdf")
        shot("05_folder_grid")
        described("Show as list").performClick()

        back()
        waitFor("DCIM")
    }

    @Test fun selectMoveToTrashAndUndo() {
        show()
        openTab("Storage")
        clickable("Download").performClick()
        waitFor("Invoice October.pdf")
        compose.onNode(hasText("Invoice October.pdf")).performTouchInput { longClick() }
        waitFor("1 selected")
        shot("06_selection")
        described("Move to trash").performClick()
        val invoice = File(root, "Download/Invoice October.pdf")
        compose.waitUntil(10_000) { !invoice.exists() && graph.trash.list().size == 1 }
        waitFor("Moved 1 item to trash")
        clickable("Undo").performClick()
        compose.waitUntil(10_000) { invoice.exists() }
        assertTrue(graph.trash.list().isEmpty())
    }

    // Robolectric can't settle a dialog with a text field on a tall screen (a stock Material dialog
    // shows the same), so this test uses Robolectric's default screen. Real phones are unaffected.
    @Config(qualifiers = "w320dp-h470dp-mdpi")
    @Test fun actionSheetRenameNewFolderCopyPaste() {
        show()
        openTab("Storage")
        clickable("Documents").performClick()
        waitFor("Notes.txt")
        described("More options for Notes.txt").performClick()
        waitFor("Move to hidden folder")
        shot("07_action_sheet")

        clickable("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Ideas.txt")
        clickable("Rename").performClick()
        compose.waitUntil(10_000) { File(root, "Documents/Ideas.txt").exists() }
        assertFalse(File(root, "Documents/Notes.txt").exists())

        described("More options").performClick()
        clickable("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Trips")
        clickable("Create").performClick()
        compose.waitUntil(10_000) { File(root, "Documents/Trips").isDirectory }

        waitFor("Ideas.txt")
        described("More options for Ideas.txt").performClick()
        clickable("Copy to…").performClick()
        waitFor("1 item to copy")
        clickable("Internal storage").performClick()
        clickable("Download").performClick()
        waitFor("Invoice October.pdf")
        shot("08_paste_bar")
        clickable("Paste").performClick()
        compose.waitUntil(10_000) { File(root, "Download/Ideas.txt").exists() }
        assertTrue("copy keeps the original", File(root, "Documents/Ideas.txt").exists())
    }

    @Test fun searchFindsFilesAnywhere() {
        show()
        clickable("Search in Filo").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("invoice")
        waitFor("Invoice October.pdf")
        waitFor("1 result")
        shot("09_search")
    }

    @Test fun reviewShowsFactsAndTrashesChosenDuplicates() {
        show()
        openTab("Review")
        waitFor("keeping one of each", substring = true)
        shot("10_review")
        val list = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        listOf("Large files", "Duplicate files", "Old downloads", "Screenshots", "Installer files", "Empty folders").forEach {
            compose.onNode(list).performScrollToNode(hasText(it))
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNode(list).performScrollToNode(hasText("Large files"))

        clickable("Duplicate files").performClick()
        waitFor("Select extra copies")
        clickable("Select extra copies").performClick()
        waitFor("Move to trash")
        shot("11_review_duplicates")
        clickable("Move to trash").performClick()
        compose.waitUntil(10_000) { graph.trash.list().size == 1 }
        assertTrue("oldest copy kept", File(root, "Music/Duplicate song.mp3").exists())
        assertFalse(File(root, "Music/Copy of Duplicate song.mp3").exists())
    }

    @Test fun hiddenFolderSetupHideLockUnlock() {
        show()
        clickable("Hidden folder").performClick()
        waitFor("Create a PIN")
        shot("12_hidden_create_pin")
        typePin("123456")
        waitFor("Confirm your PIN")
        typePin("123456")
        waitFor("Your hidden folder is empty")
        assertTrue(graph.vault.isSetUp)
        assertTrue(compose.onAllNodes(hasContentDescription("Lock now")).fetchSemanticsNodes().isEmpty())
        back()
        compose.waitUntil(5_000) { !graph.vault.unlocked.value } // going back out locks it

        openTab("Storage")
        clickable("DCIM").performClick()
        clickable("Camera").performClick()
        waitFor("IMG_0001.png")
        described("More options for IMG_0001.png").performClick()
        clickable("Move to hidden folder").performClick()
        waitFor("Hidden folder is locked")
        waitFor("Unlock to hide 1 file")
        typePin("000000")
        waitFor("Wrong PIN", substring = true)
        shot("13_hidden_unlock_wrong_pin")
        typePin("123456")
        val photo = File(root, "DCIM/Camera/IMG_0001.png")
        compose.waitUntil(10_000) { !photo.exists() && graph.vault.items.value.size == 1 }
        waitFor("IMG_0001.png")
        shot("14_hidden_folder_content")
        back()
        compose.waitUntil(5_000) { !graph.vault.unlocked.value }
    }

    @Test fun trashTabSelectAndRestore() {
        graph.trash.moveToTrash(File(root, "Download/Boarding pass.pdf"))
        graph.trash.moveToTrash(File(root, "Old empty folder"))
        graph.trash.moveToTrash(File(root, "DCIM/Camera/IMG_0002.png"))
        show()
        openTab("Trash")
        waitFor("Boarding pass.pdf")
        waitFor("30 days left", substring = true)
        waitFor("from Internal storage", substring = true)
        shot("15_trash")

        compose.onNode(hasText("Boarding pass.pdf")).performTouchInput { longClick() }
        waitFor("1 selected")
        described("Restore").performClick()
        compose.waitUntil(10_000) { File(root, "Download/Boarding pass.pdf").exists() }
        assertEquals(2, graph.trash.list().size)

        described("More options for IMG_0002.png").performClick()
        waitFor("Delete for good")
        shot("16_trash_options")
    }

    @Test fun swipeOnTheBottomBarOnly() {
        show()
        waitFor("Search in Filo")
        // Swiping the page itself does nothing
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        waitFor("Search in Filo")
        assertTrue(compose.onAllNodes(hasText("DCIM")).fetchSemanticsNodes().isEmpty())
        // Swiping along the bottom bar switches tabs
        val bar = compose.onNode(hasTestTag("tab_bar"))
        bar.performTouchInput { swipeLeft() }
        waitFor("DCIM") // Storage
        bar.performTouchInput { swipeLeft() }
        waitFor("Trash is empty")
        bar.performTouchInput { swipeRight() }
        bar.performTouchInput { swipeRight() }
        bar.performTouchInput { swipeRight() }
        waitFor("What takes up space", substring = true) // Review
    }

    @Test fun appsShowsInstalledAppsAndInstallerFiles() {
        show()
        waitFor(app.filo.data.FileOps.formatSize(14_500_000 + 96_000_000)) // Apps tile: installed apps, system apps hidden
        clickable("Apps").performClick()
        waitFor("Offline Maps")
        waitFor("Notes")
        assertTrue("system apps hidden by default", compose.onAllNodes(hasText("Camera")).fetchSemanticsNodes().isEmpty())
        shot("22_apps_installed")
        clickable("Installer files").performClick()
        waitFor("OldGame.apk")
        shot("24_apps_installer_files")
        clickable("Installed").performClick()
        waitFor("Offline Maps")
        clickable("Offline Maps").performClick()
        waitFor("Save APK to Download")
        listOf("Open", "App info", "Uninstall").forEach { clickable(it).assertExists() }
        shot("23_app_options")
    }

    @Test fun aboutLinksToRepoAndShowsLicense() {
        show()
        described("Open menu").performClick()
        clickable("About").performClick()
        waitFor("github.com/RealKHFan/filo")
        assertTrue("no AI line", compose.onAllNodes(hasText("Made with help from Claude")).fetchSemanticsNodes().isEmpty())
        clickable("License").performClick()
        waitFor("GNU GENERAL PUBLIC LICENSE", substring = true)
        shot("25_license")
    }

    @Test fun menuHasFavoritesHiddenFolderSettingsAbout() {
        show()
        described("Open menu").performClick()
        waitFor("About")
        listOf("Settings", "About").forEach { clickable(it).assertExists() }
        val menuWidth = compose.onNode(hasText("About") and hasClickAction()).fetchSemanticsNode().boundsInRoot.width
        val screenWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue("menu is about 60% wide", menuWidth < screenWidth * 0.7f)
        // Favorites and Hidden folder are on Browse and in the menu
        listOf("Favorites", "Hidden folder").forEach { compose.onAllNodes(hasText(it) and hasClickAction()).assertCountEquals(2) }
        shot("17_menu")
        clickable("About").performClick()
        waitFor("Version 1.2.0")
        shot("18_about")
    }

    @Test fun settingsShowHiddenFilesAndAutoLock() {
        show()
        described("Open menu").performClick()
        clickable("Settings").performClick()
        waitFor("Show hidden files")
        waitFor("After 5 seconds")
        shot("19_settings")
        clickable("Lock after leaving Filo").performClick()
        clickable("After 30 seconds").performClick()
        assertEquals(30, graph.settings.state.value.autoLockSeconds)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Show system apps"))
        clickable("Show system apps").performClick()
        assertTrue(graph.settings.state.value.showSystemApps)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Show full app sizes"))
        waitFor("never asks with a pop-up", substring = true)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Show hidden files"))
        clickable("Show hidden files").performClick()
        assertTrue(graph.settings.state.value.showHidden)
        back()
        openTab("Storage")
        clickable("Pictures").performClick()
        waitFor(".thumbnails")
    }

    @Test fun categoryImages() {
        show()
        clickable("Images").performClick()
        waitFor("IMG_0002.png")
        shot("20_category_images")
    }

    @Test fun permissionScreenWhenAccessIsMissing() {
        graph = newGraph(ApplicationProvider.getApplicationContext(), granted = false)
        show()
        waitFor("Welcome to Filo")
        clickable("Allow access").assertExists()
        shot("21_permission")
    }
}
