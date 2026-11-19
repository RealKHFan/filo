package app.filo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.filo.Graph
import app.filo.net.Protocol
import app.filo.net.Server
import app.filo.ui.FakeApps
import app.filo.ui.FakeMedia
import app.filo.ui.NoBiometric
import app.filo.vault.PinKdf
import app.filo.vault.SoftwareKeyWrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

// Conscrypt off: Robolectric would install it for the whole test run, and the plain-JVM FTPS test then can't use TLS.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun graph(context: Context) = Graph(
        context,
        storageRoot = tmp.root,
        permission = StoragePermission { true },
        media = FakeMedia(tmp.root),
        apps = FakeApps,
        deviceKey = SoftwareKeyWrap(),
        biometricKey = NoBiometric,
        pinKdf = PinKdf(1_000),
        serverKey = SoftwareKeyWrap(),
    )

    @Test fun `settings, views, favorites and servers come along`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val old = graph(context)
        old.vault.setup("123456")
        old.vault.createFolder("", "Trips")
        old.settings.update { it.copy(themeMode = ThemeMode.DARK, autoLockSeconds = 30, networkServers = true, showHidden = true) }
        old.views.set("folder:/storage/emulated/0/DCIM", ViewPrefs(ViewLevel.GRID_4, SortOrder(SortField.SIZE, ascending = true)))
        old.views.set(old.vault.viewKey("Trips"), ViewPrefs(ViewLevel.GRID_2))
        old.favorites.add(listOf("/storage/emulated/0/Documents"))
        val nas = Server("2f7c1a52-0000-4000-8000-000000000001", "NAS", Protocol.SMB, "192.168.1.10", user = "tim", certificate = null)
        old.servers.save(nas, "s3cret")
        val p = AppBackup.collect(old)

        // The new phone: its own preferences and a new hidden folder (so a new folder key).
        context.getSharedPreferences("filo", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("filo_views", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("filo_servers", Context.MODE_PRIVATE).edit().clear().commit()
        old.vault.erase()
        val new = graph(context)
        new.vault.setup("999999")
        new.favorites.add(listOf("/storage/emulated/0/Music"))
        assertNull(new.views.all.value[new.vault.viewKey("Trips")])

        AppBackup.apply(new, p)

        val s = new.settings.state.value
        assertEquals(ThemeMode.DARK, s.themeMode)
        assertEquals(30, s.autoLockSeconds)
        assertEquals(true, s.networkServers)
        assertEquals(true, s.showHidden)
        assertEquals(ViewPrefs(ViewLevel.GRID_4, SortOrder(SortField.SIZE, ascending = true)), new.views.all.value["folder:/storage/emulated/0/DCIM"])
        assertEquals("hidden folder views follow the folder name", ViewPrefs(ViewLevel.GRID_2), new.views.all.value[new.vault.viewKey("Trips")])
        assertEquals(setOf("/storage/emulated/0/Music", "/storage/emulated/0/Documents"), new.favorites.paths.value)
        assertEquals(listOf(nas), new.servers.servers.value)
        assertEquals("s3cret", new.servers.password(nas.id))
    }

    @Test fun `odd values from a backup are ignored`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val g = graph(context)
        g.vault.setup("123456")
        val p = java.util.Properties().apply {
            setProperty("setting.autoLockSeconds", "999999")
            setProperty("setting.themeMode", "NEON")
            setProperty("favorite.0", "relative/path")
            setProperty("server.0.id", "a,b")
            setProperty("server.0.data", """{"host":"1.2.3.4"}""")
            setProperty("view.0.key", "vault:abc")
            setProperty("view.0.value", "GRID_2|NAME|true")
        }
        AppBackup.apply(g, p)
        assertEquals(5, g.settings.state.value.autoLockSeconds)
        assertEquals(ThemeMode.SYSTEM, g.settings.state.value.themeMode)
        assertEquals(emptySet<String>(), g.favorites.paths.value)
        assertEquals(emptyList<Server>(), g.servers.servers.value)
        assertNull(g.views.all.value["vault:abc"])
    }
}
