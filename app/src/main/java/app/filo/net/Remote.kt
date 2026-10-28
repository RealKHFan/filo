package app.filo.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

enum class Protocol(val label: String, val defaultPort: Int) {
    SMB("SMB", 445),
    FTP("FTP", 21),
    FTPS("FTPS", 21),
}

/** A saved file server. The password is kept separately, encrypted (see ServerStore). */
data class Server(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val host: String,
    val port: Int? = null,
    /** Folder to open first: "share/folder" for SMB, "folder" for FTP. Empty: all shares, or the FTP home folder. */
    val startPath: String = "",
    /** Empty: guest (SMB) or anonymous (FTP). */
    val user: String = "",
    val domain: String = "",
    val savePassword: Boolean = true,
    /** FTPS: fingerprint of the server's certificate, remembered the first time (like SSH). */
    val certificate: String? = null,
) {
    val displayName: String get() = name.ifBlank { host }
    val effectivePort: Int get() = port ?: protocol.defaultPort
    val summary: String get() = "${protocol.label} · $host" + if (port != null) ":$port" else ""
}

/** A file or folder on a server. [path] is like "share/folder/file.jpg" ("" is the top). */
data class RemoteFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val hidden: Boolean = name.startsWith("."),
)

object RemotePath {
    fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
    fun parent(path: String) = path.substringBeforeLast('/', "")
    fun name(path: String) = path.substringAfterLast('/')
    fun normalize(path: String) = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    fun isInside(path: String, folder: String) = folder.isEmpty() || path == folder || path.startsWith("$folder/")

    /** "photo.jpg" → "photo (1).jpg" when [taken] already has that name. */
    fun unique(taken: Set<String>, name: String): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var i = 1
        while ("$base ($i)$ext" in taken) i++
        return "$base ($i)$ext"
    }
}

/** One open connection to a server. Not thread-safe: ServerSessions makes sure calls don't overlap. */
interface RemoteFs : Closeable {
    /** Where browsing starts when no folder is set: "" (all shares) for SMB, the home folder for FTP. */
    val home: String
    val isConnected: Boolean

    /** False when the connection is known to be unusable without asking the server (then it's replaced). */
    val isUsable: Boolean get() = true
    fun list(path: String): List<RemoteFile>
    fun read(path: String, out: OutputStream, onBytes: (Long) -> Unit = {}, isCancelled: () -> Boolean = { false })

    /** The first [max] bytes of a file: enough for the small preview picture stored inside most photos. */
    fun readHead(path: String, max: Int): ByteArray
    fun write(path: String, input: InputStream, onBytes: (Long) -> Unit = {}, isCancelled: () -> Boolean = { false })
    fun mkdir(path: String)
    fun delete(path: String, isDirectory: Boolean)
    fun rename(from: String, to: String)
}

internal fun copyStream(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        if (isCancelled()) throw CancellationException("Cancelled")
        val n = input.read(buffer)
        if (n < 0) break
        output.write(buffer, 0, n)
        onBytes(n.toLong())
    }
}

/**
 * Filo only talks to servers on your own network: home addresses (10.x, 172.16-31.x, 192.168.x),
 * link-local addresses, the phone itself, and VPN ranges such as Tailscale (100.64.0.0/10,
 * fd00::/8). Anything on the internet is refused before a connection is made.
 */
object LocalNetwork {
    fun isLocal(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val b = address.address
        return when (address) {
            is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127 // 100.64.0.0/10
            is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC // fc00::/7, unique local addresses
            else -> false
        }
    }

    /** The first local address [host] stands for; the connection then goes to exactly that address. */
    fun resolve(host: String): InetAddress {
        val name = host.trim().removePrefix("[").removeSuffix("]")
        val all = try {
            InetAddress.getAllByName(name)
        } catch (e: UnknownHostException) {
            throw IOException("Couldn't find \"$host\" on your network", e)
        }
        return all.firstOrNull(::isLocal) ?: throw NotLocalException(host)
    }
}

class NotLocalException(host: String) :
    IOException("$host isn't on your local network. Filo only connects to servers at home or over your own VPN.")

/** Android's Network permission (GrapheneOS lets you switch it off; then Filo can't reach anything). */
fun interface NetworkPermission {
    fun granted(): Boolean

    companion object {
        fun forContext(context: Context) = NetworkPermission {
            context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
        }
    }
}

class NetworkBlockedException : IOException("Filo isn't allowed to use the network")

class PasswordNeededException : IOException("Password needed")

class LoginFailedException(message: String = "Wrong user name or password") : IOException(message)

class CertificateChangedException(val fingerprint: String) :
    IOException("The server's certificate changed. If you expected that, edit the server and forget the old certificate.")

/** A connection refused because Android blocks Filo's network access looks like EACCES / EPERM. */
fun Throwable.isNetworkBlocked(): Boolean = generateSequence(this) { it.cause }.take(10).any { e ->
    val m = e.message.orEmpty()
    e is NetworkBlockedException ||
        ((e is SocketException || e.javaClass.simpleName == "ErrnoException") &&
            (m.contains("EACCES") || m.contains("EPERM") || m.contains("Permission denied") || m.contains("Operation not permitted")))
}
