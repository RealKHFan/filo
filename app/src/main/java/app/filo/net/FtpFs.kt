package app.filo.net

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/** An error the server reported itself (not a broken connection), so trying again won't help. */
class ServerReplyException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * FTP, or FTPS (FTP with TLS, "explicit" mode on port 21). Home servers usually have their own
 * certificate, so FTPS works like SSH: the certificate is remembered the first time, and Filo
 * refuses to continue if it ever changes.
 */
class FtpFs(
    address: InetAddress,
    port: Int,
    user: String,
    password: String,
    secure: Boolean,
    pinnedCertificate: String? = null,
) : RemoteFs {
    private val trust = PinningTrustManager(pinnedCertificate)
    private val ftp: FTPClient = if (secure) FTPSClient("TLS", false).apply { trustManager = trust } else FTPClient()

    /** FTPS: the certificate seen while connecting, to remember for next time. */
    val certificate: String? get() = trust.seen

    override val home: String

    init {
        ftp.controlEncoding = "UTF-8"
        ftp.connectTimeout = 15_000
        ftp.setDefaultTimeout(30_000)
        ftp.setDataTimeout(Duration.ofSeconds(30))
        ftp.bufferSize = 256 * 1024
        try {
            try {
                ftp.connect(address, port)
            } catch (e: SSLException) {
                val changed = trust.rejected
                throw if (changed != null) CertificateChangedException(changed) else e
            }
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) throw ServerReplyException("The server refused the connection")
            ftp.soTimeout = 30_000
            val loggedIn = if (user.isEmpty()) ftp.login("anonymous", "filo@") else ftp.login(user, password)
            if (!loggedIn) throw LoginFailedException()
            if (ftp is FTPSClient) {
                ftp.execPBSZ(0)
                ftp.execPROT("P") // file contents encrypted too, not only the password
            }
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftp.enterLocalPassiveMode()
            home = RemotePath.normalize(ftp.printWorkingDirectory() ?: "/")
        } catch (e: Throwable) {
            runCatching { ftp.disconnect() }
            throw e
        }
    }

    override val isConnected: Boolean
        get() = ftp.isConnected && runCatching { ftp.sendNoOp() }.getOrDefault(false)

    private fun abs(path: String) = "/" + RemotePath.normalize(path)

    private val machineListing by lazy { runCatching { ftp.hasFeature("MLSD") }.getOrDefault(false) }

    private fun fail(what: String): Nothing {
        val reply = ftp.replyString?.trim().orEmpty()
        throw ServerReplyException(
            when (ftp.replyCode) {
                550 -> "$what: not found, or the server doesn't allow it"
                552 -> "$what: the server is full"
                553 -> "$what: the server doesn't allow that name"
                530 -> "$what: not allowed for this user"
                else -> if (reply.isEmpty()) what else "$what ($reply)"
            },
        )
    }

    override fun list(path: String): List<RemoteFile> {
        val folder = RemotePath.normalize(path)
        val files: Array<FTPFile?> = if (machineListing) {
            ftp.mlistDir(abs(folder))
        } else {
            if (!ftp.changeWorkingDirectory(abs(folder))) fail("Couldn't open the folder")
            ftp.listFiles()
        }
        if (!FTPReply.isPositiveCompletion(ftp.replyCode) && files.isEmpty()) fail("Couldn't open the folder")
        return files.filterNotNull()
            .filter { it.name != null && it.name != "." && it.name != ".." }
            .map { f ->
                val name = f.name.substringAfterLast('/')
                RemoteFile(
                    path = RemotePath.join(folder, name),
                    name = name,
                    isDirectory = f.isDirectory,
                    size = if (f.isDirectory) 0 else f.size.coerceAtLeast(0),
                    lastModified = f.timestamp?.timeInMillis ?: 0L,
                )
            }
    }

    override fun read(path: String, out: OutputStream, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) {
        val stream = ftp.retrieveFileStream(abs(path)) ?: fail("Couldn't read ${RemotePath.name(path)}")
        try {
            stream.use { copyStream(it, out, onBytes, isCancelled) }
        } catch (e: Throwable) {
            runCatching { ftp.abort() }
            throw e
        }
        if (!ftp.completePendingCommand()) fail("Couldn't read ${RemotePath.name(path)}")
    }

    override fun readHead(path: String, max: Int): ByteArray {
        val stream = ftp.retrieveFileStream(abs(path)) ?: fail("Couldn't read ${RemotePath.name(path)}")
        val buffer = ByteArray(max)
        var filled = 0
        try {
            while (filled < max) {
                val n = stream.read(buffer, filled, max - filled)
                if (n < 0) break
                filled += n
            }
        } finally {
            // Stopping early: most servers answer "transfer aborted", which has to be read before the
            // next command. Some just hang up instead; then this connection is replaced next time.
            runCatching { stream.close() }
            val inStep = runCatching { ftp.completePendingCommand(); ftp.sendNoOp() }.getOrDefault(false)
            if (!inStep) broken = true
        }
        return buffer.copyOf(filled)
    }

    @Volatile private var broken = false

    override val isUsable: Boolean get() = !broken && ftp.isConnected

    override fun write(path: String, input: InputStream, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) {
        val stream = ftp.storeFileStream(abs(path)) ?: fail("Couldn't save ${RemotePath.name(path)}")
        try {
            stream.use { copyStream(input, it, onBytes, isCancelled) }
        } catch (e: Throwable) {
            runCatching { ftp.abort() }
            throw e
        }
        if (!ftp.completePendingCommand()) fail("Couldn't save ${RemotePath.name(path)}")
    }

    override fun mkdir(path: String) {
        if (!ftp.makeDirectory(abs(path))) fail("Couldn't create the folder")
    }

    override fun delete(path: String, isDirectory: Boolean) {
        if (!isDirectory) {
            if (!ftp.deleteFile(abs(path))) fail("Couldn't delete ${RemotePath.name(path)}")
            return
        }
        list(path).forEach { delete(it.path, it.isDirectory) }
        if (!ftp.removeDirectory(abs(path))) fail("Couldn't delete ${RemotePath.name(path)}")
    }

    override fun rename(from: String, to: String) {
        if (!ftp.rename(abs(from), abs(to))) fail("Couldn't move ${RemotePath.name(from)}")
    }

    override fun close() {
        runCatching { ftp.logout() }
        runCatching { ftp.disconnect() }
    }
}

/** Accepts the certificate it was told to expect, or any certificate the first time (remembered by the caller). */
private class PinningTrustManager(private val pinned: String?) : X509TrustManager {
    @Volatile var seen: String? = null
    @Volatile var rejected: String? = null

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("No certificate")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString(":") { "%02X".format(it) }
        if (pinned != null && !pinned.equals(fingerprint, ignoreCase = true)) {
            rejected = fingerprint
            throw CertificateException("Certificate changed")
        }
        seen = fingerprint
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) =
        throw CertificateException("Not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
