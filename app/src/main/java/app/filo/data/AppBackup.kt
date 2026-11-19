package app.filo.data

import app.filo.Graph
import java.util.Properties

/**
 * Filo's own settings that travel inside a hidden-folder backup, so the other phone feels the same:
 * settings, list/grid and sort per folder, favorites, and saved file servers with their passwords.
 * Importing takes over the backup's settings; favorites and servers are added to what's there.
 */
object AppBackup {
    private const val VAULT_VIEW = "vault:"

    fun collect(graph: Graph): Properties {
        val p = Properties()
        val s = graph.settings.state.value
        p["setting.showHidden"] = s.showHidden.toString()
        p["setting.themeMode"] = s.themeMode.name
        p["setting.dynamicColor"] = s.dynamicColor.toString()
        p["setting.autoLockSeconds"] = s.autoLockSeconds.toString()
        p["setting.showSystemApps"] = s.showSystemApps.toString()
        p["setting.fullAppSizes"] = s.fullAppSizes.toString()
        p["setting.networkServers"] = s.networkServers.toString()

        val views = graph.views.all.value
        views.filterKeys { !it.startsWith(VAULT_VIEW) }.entries.forEachIndexed { i, (key, value) ->
            p["view.$i.key"] = key
            p["view.$i.value"] = ViewPrefsStore.encode(value)
        }
        // Hidden folders remember their view under a key made from this phone's folder key, so they travel by name.
        (graph.vault.folders.value + "").mapNotNull { f -> views[graph.vault.viewKey(f)]?.let { f to it } }.forEachIndexed { i, (folder, value) ->
            p["vaultView.$i.folder"] = folder
            p["vaultView.$i.value"] = ViewPrefsStore.encode(value)
        }

        graph.favorites.paths.value.forEachIndexed { i, path -> p["favorite.$i"] = path }

        graph.servers.servers.value.forEachIndexed { i, server ->
            p["server.$i.id"] = server.id
            p["server.$i.data"] = graph.servers.toText(server)
            graph.servers.password(server.id)?.let { p["server.$i.password"] = it }
        }
        return p
    }

    fun apply(graph: Graph, p: Properties) {
        fun flag(name: String) = p.getProperty("setting.$name")?.toBooleanStrictOrNull()
        graph.settings.update { s ->
            s.copy(
                showHidden = flag("showHidden") ?: s.showHidden,
                themeMode = ThemeMode.entries.firstOrNull { it.name == p.getProperty("setting.themeMode") } ?: s.themeMode,
                dynamicColor = flag("dynamicColor") ?: s.dynamicColor,
                autoLockSeconds = p.getProperty("setting.autoLockSeconds")?.toIntOrNull()?.takeIf { it in AUTO_LOCK_CHOICES } ?: s.autoLockSeconds,
                showSystemApps = flag("showSystemApps") ?: s.showSystemApps,
                fullAppSizes = flag("fullAppSizes") ?: s.fullAppSizes,
                networkServers = flag("networkServers") ?: s.networkServers,
            )
        }

        numbered(p, "view.", ".key").forEachIndexed { i, key ->
            ViewPrefsStore.decode(p.getProperty("view.$i.value").orEmpty())?.let { if (!key.startsWith(VAULT_VIEW)) graph.views.set(key, it) }
        }
        numbered(p, "vaultView.", ".folder").forEachIndexed { i, folder ->
            ViewPrefsStore.decode(p.getProperty("vaultView.$i.value").orEmpty())?.let { graph.views.set(graph.vault.viewKey(folder), it) }
        }

        graph.favorites.add(numbered(p, "favorite.").filter { it.startsWith("/") })

        numbered(p, "server.", ".id").forEachIndexed { i, id ->
            val server = graph.servers.fromText(id, p.getProperty("server.$i.data").orEmpty()) ?: return@forEachIndexed
            graph.servers.save(server, p.getProperty("server.$i.password"))
            graph.sessions.close(server.id) // an open connection may use the old settings
        }
    }

    private fun numbered(p: Properties, prefix: String, suffix: String = ""): List<String> =
        generateSequence(0) { it + 1 }.map { p.getProperty("$prefix$it$suffix") }.takeWhile { it != null }.map { it!! }.toList()
}
