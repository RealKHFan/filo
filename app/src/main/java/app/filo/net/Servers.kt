package app.filo.net

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import app.filo.vault.KeyWrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Saved servers. Passwords are encrypted with a key from the phone's security chip. */
class ServerStore(private val prefs: SharedPreferences, private val key: KeyWrap) {
    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    fun get(id: String): Server? = _servers.value.firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString()

    /** [password]: null keeps the saved one, "" removes it. */
    @Synchronized
    fun save(server: Server, password: String?) {
        val list = _servers.value.toMutableList()
        val index = list.indexOfFirst { it.id == server.id }
        if (index >= 0) list[index] = server else list += server
        prefs.edit {
            putString("order", list.joinToString(",") { it.id })
            putString("s.${server.id}", encode(server).toString())
            when {
                !server.savePassword || password == "" -> remove("p.${server.id}")
                password != null -> putString("p.${server.id}", Base64.encodeToString(key.wrap(password.toByteArray()), Base64.NO_WRAP))
            }
        }
        _servers.value = list
    }

    @Synchronized
    fun remove(id: String) {
        val list = _servers.value.filterNot { it.id == id }
        prefs.edit {
            putString("order", list.joinToString(",") { it.id })
            remove("s.$id")
            remove("p.$id")
        }
        _servers.value = list
    }

    fun hasPassword(id: String) = prefs.contains("p.$id")

    fun password(id: String): String? = prefs.getString("p.$id", null)?.let {
        try {
            String(key.unwrap(Base64.decode(it, Base64.NO_WRAP)))
        } catch (_: Exception) {
            null // the phone's key changed (e.g. restored to another phone): ask again
        }
    }

    private fun load(): List<Server> = prefs.getString("order", "").orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { id ->
        prefs.getString("s.$id", null)?.let { runCatching { decode(id, JSONObject(it)) }.getOrNull() }
    }

    private fun encode(s: Server) = JSONObject().apply {
        put("name", s.name)
        put("protocol", s.protocol.name)
        put("host", s.host)
        s.port?.let { put("port", it) }
        put("start", s.startPath)
        put("user", s.user)
        put("domain", s.domain)
        put("savePassword", s.savePassword)
        s.certificate?.let { put("certificate", it) }
    }

    private fun decode(id: String, o: JSONObject) = Server(
        id = id,
        name = o.optString("name"),
        protocol = Protocol.entries.firstOrNull { it.name == o.optString("protocol") } ?: Protocol.SMB,
        host = o.getString("host"),
        port = if (o.has("port")) o.getInt("port") else null,
        startPath = o.optString("start"),
        user = o.optString("user"),
        domain = o.optString("domain"),
        savePassword = o.optBoolean("savePassword", true),
        certificate = if (o.has("certificate")) o.getString("certificate") else null,
    )
}

/** Opens connections. Tests replace it with a pretend server. */
fun interface RemoteConnector {
    fun connect(server: Server, password: String): RemoteFs
}

/** The real thing: only after checking the address is on your local network. */
class DefaultConnector(private val onCertificate: (Server, String) -> Unit) : RemoteConnector {
    override fun connect(server: Server, password: String): RemoteFs {
        val address = LocalNetwork.resolve(server.host)
        return when (server.protocol) {
            Protocol.SMB -> SmbFs(address, server.effectivePort, server.user, password, server.domain)
            Protocol.FTP -> FtpFs(address, server.effectivePort, server.user, password, secure = false)
            Protocol.FTPS -> FtpFs(address, server.effectivePort, server.user, password, secure = true, pinnedCertificate = server.certificate).also { fs ->
                val seen = fs.certificate
                if (server.certificate == null && seen != null) onCertificate(server, seen)
            }
        }
    }
}

/**
 * Keeps connections open while you use them, one at a time per connection. Browsing, thumbnails
 * and copying each get their own connection, so a big copy or a slow picture never blocks the list.
 */
class ServerSessions(
    private val store: ServerStore,
    private val connector: RemoteConnector,
    private val permission: NetworkPermission,
    private val enabled: () -> Boolean,
) {
    enum class Channel { BROWSE, THUMBS, TRANSFER }

    private class Slot {
        val mutex = Mutex()
        var fs: RemoteFs? = null
        var lastUsed = 0L
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /** Passwords typed in for servers that don't save them; forgotten when Filo closes. */
    private val typedPasswords = ConcurrentHashMap<String, String>()

    fun rememberForNow(serverId: String, password: String) {
        typedPasswords[serverId] = password
    }

    fun forgetForNow(serverId: String) {
        typedPasswords.remove(serverId)
    }

    suspend fun <T> use(server: Server, channel: Channel = Channel.BROWSE, block: (RemoteFs) -> T): T {
        if (!enabled()) throw IOException("File servers are switched off in Settings")
        if (!permission.granted()) throw NetworkBlockedException()
        val key = "${server.id}/$channel"
        val slot = slots.getOrPut(key) { Slot() }
        return slot.mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    var fs = slot.fs
                    // A connection that sat unused for a while may have been closed by the server.
                    if (fs != null && (!fs.isUsable || System.currentTimeMillis() - slot.lastUsed > 20_000 && !fs.isConnected)) {
                        closeLater(fs)
                        fs = null
                    }
                    val open = fs ?: connect(server).also { slot.fs = it }
                    val result = block(open)
                    slot.lastUsed = System.currentTimeMillis()
                    result
                } catch (e: Throwable) {
                    // Anything but an answer from the server: start with a fresh connection next time.
                    if (e !is ServerReplyException) {
                        slot.fs?.let(::closeLater)
                        slot.fs = null
                    }
                    if (e.isNetworkBlocked()) throw NetworkBlockedException()
                    throw e
                } finally {
                    // close() ran meanwhile (the server was edited or deleted): a connection opened since must not stay open.
                    if (slots[key] !== slot) {
                        slot.fs?.let(::closeLater)
                        slot.fs = null
                    }
                }
            }
        }
    }

    private fun connect(server: Server): RemoteFs {
        val password = store.password(server.id) ?: typedPasswords[server.id]
        if (server.user.isNotEmpty() && password == null) throw PasswordNeededException()
        return try {
            connector.connect(server, password.orEmpty())
        } catch (e: LoginFailedException) {
            typedPasswords.remove(server.id)
            throw e
        }
    }

    /** Saying goodbye to a server that stopped answering waits for timeouts, so it happens on the side. */
    private fun closeLater(fs: RemoteFs) = Thread { runCatching { fs.close() } }.start()

    /** Closes the connections to one server (after editing it), or to all. */
    fun close(serverId: String? = null) {
        slots.entries.filter { serverId == null || it.key.startsWith("$serverId/") }.forEach { (key, slot) ->
            slots.remove(key) // first, so a use() still running sees it and closes what it opens
            slot.fs?.let(::closeLater)
        }
    }
}
