package app.filo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.filo.Graph
import app.filo.data.ClipMode
import app.filo.data.StoragePermission
import app.filo.net.LoginFailedException
import app.filo.net.Protocol
import app.filo.net.RemoteConnector
import app.filo.net.RemoteFile
import app.filo.net.RemoteFs
import app.filo.net.RemotePath
import app.filo.net.Server
import app.filo.net.ServerReplyException
import app.filo.net.copyStream
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
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A pretend server: a folder on the test machine. Its top-level folders act as SMB shares. */
internal class DirFs(private val root: File, override val home: String = "") : RemoteFs {
    override val isConnected = true
    private fun at(path: String) = File(root, path)

    override fun list(path: String) = (at(path).listFiles() ?: throw ServerReplyException("Not found on the server")).map {
        RemoteFile(RemotePath.join(path, it.name), it.name, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified())
    }

    override fun read(path: String, out: OutputStream, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) =
        at(path).inputStream().use { copyStream(it, out, onBytes, isCancelled) }

    override fun readHead(path: String, max: Int): ByteArray = at(path).inputStream().use { it.readNBytes(max) }

    override fun write(path: String, input: InputStream, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) =
        at(path).outputStream().use { copyStream(input, it, onBytes, isCancelled) }

    override fun mkdir(path: String) {
        if (!at(path).mkdir()) throw ServerReplyException("Something with that name is already there")
    }

    override fun delete(path: String, isDirectory: Boolean) {
        at(path).deleteRecursively()
    }

    override fun rename(from: String, to: String) {
        if (!at(from).renameTo(at(to))) throw ServerReplyException("Couldn't move")
    }

    override fun close() {}
}

@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class NetworkUiTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var root: File
    private lateinit var nas: File
    private lateinit var graph: Graph
    private var networkAllowed = true
    private val passwordsUsed = mutableListOf<String>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("filo", "filo_servers", "filo_views").forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        root = tmp.newFolder("storage")
        File(root, "Download").mkdirs()
        File(root, "Documents").mkdirs()
        File(root, "Documents/Report.pdf").writeBytes(ByteArray(40_000))
        nas = tmp.newFolder("nas")
        File(nas, "Media/Docs").mkdirs()
        File(nas, "Media/notes.txt").writeText("hello from the NAS")
        picture(File(nas, "Media/Holiday.jpg"))
        File(nas, "Backup").mkdirs()
        ShadowStatFs.registerStats(root, 31_250_000, 11_230_469, 11_230_469)
        graph = Graph(
            context,
            storageRoot = root,
            permission = StoragePermission { true },
            media = FakeMedia(root),
            apps = FakeApps,
            deviceKey = SoftwareKeyWrap(),
            biometricKey = NoBiometric,
            pinKdf = PinKdf(1_000),
            networkPermission = { networkAllowed },
            serverKey = SoftwareKeyWrap(),
            connector = RemoteConnector { server, password ->
                passwordsUsed += password
                if (server.user.isNotEmpty() && password != "secret") throw LoginFailedException()
                DirFs(nas)
            },
        ).also { g -> g.settings.update { it.copy(dynamicColor = false) } }
    }

    private fun picture(file: File) {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(0xFF378ADD.toInt())
        file.parentFile!!.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun show() {
        compose.setContent { FiloApp(graph) }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        Thread.sleep(400)
        compose.waitForIdle()
        captureScreenRoboImage("build/screenshots/$name.png")
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private val vertical = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun gone(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()

    private fun clickable(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text) and hasClickAction())

    private fun described(desc: String): SemanticsNodeInteraction = compose.onNode(hasContentDescription(desc))

    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun addServer(): Server {
        val server = Server(graph.servers.newId(), "Home NAS", Protocol.SMB, "192.168.1.20", user = "anna")
        graph.servers.save(server, "secret")
        return server
    }

    @Test fun switchOnAddServerBrowseAndCopyToPhone() {
        show()
        waitFor("Collections")
        assertTrue("no Network tile while it's off", gone("Network"))

        described("Open menu").performClick()
        clickable("Settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("File servers"))
        clickable("File servers").performClick()
        assertTrue(graph.settings.state.value.networkServers)
        shot("39_settings_network")
        back()

        compose.onNode(vertical).performScrollToNode(hasText("Network"))
        clickable("Network").performClick()
        waitFor("No servers yet")
        compose.onNode(hasText("Add server"), useUnmergedTree = true).performClick() // the floating button
        waitFor("Address")
        compose.onNode(hasText("Address") and hasSetTextAction()).performTextInput("192.168.1.20")
        compose.onNode(hasText("User name") and hasSetTextAction()).performTextInput("anna")
        waitFor("Password")
        compose.onNode(hasText("Password") and hasSetTextAction()).performTextInput("secret")
        compose.onNode(hasText("Name (optional)") and hasSetTextAction()).performTextInput("Home NAS")
        shot("40_add_server")
        clickable("Save").performClick()

        waitFor("Home NAS")
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("filo_servers", Context.MODE_PRIVATE)
        assertFalse("password is never stored readable", prefs.all.values.any { it.toString().contains("secret") })
        shot("41_servers")

        clickable("Home NAS").performClick()
        waitFor("Media") // the shares
        waitFor("Backup")
        clickable("Media").performClick()
        waitFor("notes.txt")
        waitFor("Holiday.jpg")
        assertEquals(listOf("secret"), passwordsUsed.distinct())
        shot("42_server_folder")

        // Download: one tap, straight into the phone's Download folder
        compose.onNode(hasText("notes.txt")).performTouchInput { longClick() }
        waitFor("1 selected")
        described("Download").performClick()
        compose.waitUntil(10_000) { File(root, "Download/notes.txt").exists() }
        assertEquals("hello from the NAS", File(root, "Download/notes.txt").readText())
        assertTrue("download keeps the original on the server", File(nas, "Media/notes.txt").exists())
        waitFor("Saved 1 item to Download")
        clickable("Show").performClick()
        waitFor("Download") // the phone's Download folder, with the file
        waitFor("notes.txt")
        back()

        // Copy to…: choose the storage first, then paste there
        waitFor("Holiday.jpg")
        compose.onNode(hasText("Holiday.jpg")).performTouchInput { longClick() }
        waitFor("1 selected")
        described("More actions").performClick()
        clickable("Copy to…").performClick()
        waitFor("Copy 1 item to…")
        shot("44_storage_picker")
        clickable("Internal storage").performClick()
        waitFor("1 item to copy") // on the phone's Storage tab, Paste bar ready
        clickable("Documents").performClick()
        waitFor("Report.pdf")
        clickable("Paste").performClick()
        compose.waitUntil(10_000) { File(root, "Documents/Holiday.jpg").exists() }
        assertTrue(File(nas, "Media/Holiday.jpg").exists())
    }

    @Test fun networkPermissionOffShowsTheWayToAppInfo() {
        networkAllowed = false
        graph.settings.update { it.copy(networkServers = true) }
        addServer()
        show()
        compose.onNode(vertical).performScrollToNode(hasText("Network"))
        clickable("Network").performClick()
        waitFor("Allow Filo to use the network")
        clickable("Open App info").assertExists()
        shot("43_network_permission")
    }

    @Test fun uploadFromPhoneAndDragIntoAFolder() {
        graph.settings.update { it.copy(networkServers = true) }
        val server = addServer()
        show()
        graph.actions.setClip(ClipMode.COPY, listOf(File(root, "Documents/Report.pdf").absolutePath))
        compose.onNode(vertical).performScrollToNode(hasText("Network"))
        clickable("Network").performClick()
        clickable("Home NAS").performClick()
        waitFor("Backup") // the shares have loaded
        clickable("Media").performClick()
        waitFor("notes.txt")
        waitFor("1 item to copy")
        clickable("Paste").performClick()
        compose.waitUntil(10_000) { File(nas, "Media/Report.pdf").length() == 40_000L }
        waitFor("Report.pdf")

        // Drag notes.txt onto the Docs folder
        described("Show as list").let { if (compose.onAllNodes(hasContentDescription("Show as list")).fetchSemanticsNodes().isNotEmpty()) it.performClick() }
        compose.onNode(hasText("notes.txt")).performTouchInput { longClick() }
        waitFor("1 selected")
        val from = compose.onNode(hasText("notes.txt") and hasClickAction()).fetchSemanticsNode().boundsInRoot.center
        val to = compose.onNode(hasText("Docs") and hasClickAction()).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(from)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            for (i in 1..10) moveTo(lerp(from, to, i / 10f))
        }
        waitFor("Move to Docs")
        compose.onRoot().performTouchInput { up() }
        compose.waitUntil(10_000) { File(nas, "Media/Docs/notes.txt").exists() }
        assertFalse(File(nas, "Media/notes.txt").exists())
        waitFor("Moved 1 item to Docs")
        assertEquals(server.id, graph.servers.servers.value.single().id)
    }

    // Dialogs with text fields need Robolectric's small default screen (see AppUiTest).
    @Config(qualifiers = "w320dp-h470dp-mdpi")
    @Test fun renameNewFolderAndDeleteOnServer() {
        graph.settings.update { it.copy(networkServers = true) }
        addServer()
        show()
        described("Open menu").performClick()
        clickable("Network").performClick()
        clickable("Home NAS").performClick()
        waitFor("Backup") // the shares have loaded
        clickable("Media").performClick()
        waitFor("notes.txt")
        described("Show as list").let { if (compose.onAllNodes(hasContentDescription("Show as list")).fetchSemanticsNodes().isNotEmpty()) it.performClick() }

        described("More options for notes.txt").performClick()
        clickable("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("todo.txt")
        clickable("Rename").performClick()
        compose.waitUntil(10_000) { File(nas, "Media/todo.txt").exists() }

        described("More options").performClick()
        clickable("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Trips")
        clickable("Create").performClick()
        compose.waitUntil(10_000) { File(nas, "Media/Trips").isDirectory }

        waitFor("todo.txt")
        described("More options for todo.txt").performClick()
        clickable("Delete for good").performScrollTo().performClick()
        waitFor("Delete 1 item for good?")
        clickable("Delete").performClick()
        compose.waitUntil(10_000) { !File(nas, "Media/todo.txt").exists() }
    }

    @Test fun copyFromPhoneOffersTheServer() {
        graph.settings.update { it.copy(networkServers = true) }
        addServer()
        show()
        clickable("Storage").performClick()
        clickable("Documents").performClick()
        waitFor("Report.pdf")
        described("More options for Report.pdf").performClick()
        clickable("Copy to…").performClick()
        waitFor("Copy 1 item to…")
        clickable("Home NAS").performClick()
        waitFor("Media") // the server opens; choose the folder
        clickable("Media").performClick()
        waitFor("1 item to copy")
        clickable("Paste").performClick()
        compose.waitUntil(10_000) { File(nas, "Media/Report.pdf").length() == 40_000L }
        assertTrue("copy keeps the phone's file", File(root, "Documents/Report.pdf").exists())
    }
}
