package app.filo.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2Packet
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.messages.SMB2Echo
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.NamedPipe
import com.hierynomus.smbj.share.PipeShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.Interface
import com.rapid7.client.dcerpc.transport.RPCTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import com.hierynomus.security.AEADBlockCipher
import com.hierynomus.security.Cipher
import com.hierynomus.security.DerivationFunction
import com.hierynomus.security.Mac
import com.hierynomus.security.MessageDigest
import com.hierynomus.security.SecurityProvider
import com.hierynomus.security.jce.JceSecurityProvider
import java.util.EnumSet
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Windows shares, Samba and NAS boxes over SMB 2/3. Data is encrypted when the server supports
 * SMB 3 encryption. Paths start with the share: "Media/Photos/a.jpg"; "" lists the shares.
 */
class SmbFs(
    address: InetAddress,
    port: Int,
    user: String,
    password: String,
    domain: String,
) : RemoteFs {
    private val client = SMBClient(
        SmbConfig.builder()
            .withSecurityProvider(FastSecurityProvider())
            // Encrypted when the server supports it. Guest logins have no key to encrypt with.
            .withEncryptData(user.isNotEmpty())
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build(),
    )
    private val connection: Connection
    private val session: Session
    private val shares = HashMap<String, DiskShare>()

    init {
        try {
            connection = client.connect(address.hostAddress, port)
            session = try {
                if (user.isEmpty()) {
                    // No user name: an anonymous login works with most servers; some only allow "guest".
                    try {
                        connection.authenticate(AuthenticationContext.anonymous())
                    } catch (_: Exception) {
                        connection.authenticate(AuthenticationContext.guest())
                    }
                } else {
                    connection.authenticate(AuthenticationContext(user, password.toCharArray(), domain.ifBlank { null }))
                }
            } catch (e: SMBApiException) {
                throw if (e.status == NtStatus.STATUS_LOGON_FAILURE || e.status == NtStatus.STATUS_ACCESS_DENIED) LoginFailedException() else friendly(e)
            }
        } catch (e: Throwable) {
            client.close()
            throw e
        }
    }

    override val home = ""

    // After the NAS restarts or Wi-Fi drops, the socket still says "connected": only an answer proves it.
    override val isConnected: Boolean
        get() = connection.isConnected && runCatching {
            connection.send<SMB2Packet>(SMB2Echo(connection.connectionContext.negotiatedProtocol.dialect)).get(5, TimeUnit.SECONDS)
        }.isSuccess

    private fun split(path: String): Pair<String, String> {
        val p = RemotePath.normalize(path)
        return p.substringBefore('/') to p.substringAfter('/', "").replace('/', '\\')
    }

    private fun share(name: String): DiskShare = shares[name]?.takeIf { it.isConnected } ?: run {
        val share = smb { session.connectShare(name) } as? DiskShare ?: throw IOException("\"$name\" isn't a folder share")
        shares[name] = share
        share
    }

    override fun list(path: String): List<RemoteFile> {
        if (RemotePath.normalize(path).isEmpty()) return listShares()
        val (shareName, inner) = split(path)
        return smb { share(shareName).list(inner) }
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val attributes = info.fileAttributes
                val dir = attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                RemoteFile(
                    path = RemotePath.join(RemotePath.normalize(path), info.fileName),
                    name = info.fileName,
                    isDirectory = dir,
                    size = if (dir) 0 else info.endOfFile,
                    lastModified = info.lastWriteTime.toEpochMillis(),
                    hidden = info.fileName.startsWith(".") || attributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L,
                )
            }
    }

    /** Asks the server for its shares (the same list Windows shows), leaving out admin shares like C$. */
    private fun listShares(): List<RemoteFile> {
        val shares = try {
            // Through smbj's own pipe, so the request is encrypted like everything else.
            val ipc = session.connectShare("IPC$") as PipeShare
            try {
                ipc.open(
                    "srvsvc",
                    SMB2ImpersonationLevel.Impersonation,
                    EnumSet.of(AccessMask.MAXIMUM_ALLOWED),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                    SMB2CreateDisposition.FILE_OPEN_IF,
                    null,
                ).let { pipe ->
                    val transport = PipeTransport(pipe)
                    transport.bind(Interface.SRVSVC_V3_0, Interface.NDR_32BIT_V2)
                    ServerService(transport).shares1
                    // The pipe isn't closed on its own: some Samba versions crash on that request
                    // (and take the connection with them). Leaving IPC$ below closes it on the server.
                }
            } finally {
                runCatching { ipc.close() }
            }
        } catch (e: Exception) {
            throw IOException("This server doesn't list its shares. Edit the server and type the share name under Folder to open.", e)
        }
        return shares
            .filter { (it.type and 0x0FFFFFFF) == 0 && (it.type.toLong() and 0x80000000L) == 0L && !it.netName.endsWith("$") }
            .map { RemoteFile(it.netName, it.netName, isDirectory = true, size = 0, lastModified = 0) }
            .sortedBy { it.name.lowercase() }
    }

    /** Several chunks are requested at once, so the network never sits idle waiting for the next answer. */
    override fun read(path: String, out: OutputStream, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) {
        val (shareName, inner) = split(path)
        val file = smb {
            share(shareName).openFile(inner, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        }
        val chunk = chunkSize(connection.negotiatedProtocol.maxReadSize)
        val inFlight = inFlight(chunk)
        val pool = Executors.newFixedThreadPool(inFlight)
        try {
            file.use { f ->
                val pending = ArrayDeque<Future<ByteArray>>()
                var next = 0L
                fun request() {
                    val offset = next
                    next += chunk
                    pending += pool.submit<ByteArray> { readChunk(f, offset, chunk) }
                }
                repeat(inFlight) { request() }
                while (pending.isNotEmpty()) {
                    if (isCancelled()) throw CancellationException("Cancelled")
                    val data = await(pending.removeFirst())
                    if (data.isNotEmpty()) {
                        out.write(data)
                        onBytes(data.size.toLong())
                    }
                    if (data.size < chunk) break // end of the file
                    request()
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    override fun readHead(path: String, max: Int): ByteArray {
        val (shareName, inner) = split(path)
        val file = smb {
            share(shareName).openFile(inner, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        }
        return file.use { readChunk(it, 0, max) }
    }

    private fun readChunk(f: com.hierynomus.smbj.share.File, offset: Long, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val n = smb { f.read(buffer, offset + filled, filled, size - filled) }
            if (n <= 0) break
            filled += n
        }
        return if (filled == size) buffer else buffer.copyOf(filled)
    }

    private fun <T> await(future: Future<T>): T = try {
        future.get(2, TimeUnit.MINUTES)
    } catch (e: ExecutionException) {
        val cause = e.cause
        throw when (cause) {
            is SMBApiException -> friendly(cause)
            is IOException -> cause
            null -> IOException(e)
            else -> IOException(cause.message ?: "Transfer failed", cause)
        }
    }

    override fun write(path: String, input: InputStream, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) {
        val (shareName, inner) = split(path)
        if (inner.isEmpty()) throw IOException("Files can't be put next to the shares, open a share first")
        val file = smb {
            share(shareName).openFile(
                inner,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null,
            )
        }
        // Like reading: a few chunks travel at once.
        val chunk = chunkSize(connection.negotiatedProtocol.maxWriteSize)
        val inFlight = inFlight(chunk)
        file.use { f ->
            val pending = ArrayDeque<Pair<Future<Long>, Int>>()
            fun settle() {
                val (future, size) = pending.removeFirst()
                if (await(future) < size) throw IOException("The server didn't take the whole file")
                onBytes(size.toLong())
            }
            var offset = 0L
            while (true) {
                if (isCancelled()) throw CancellationException("Cancelled")
                val buffer = ByteArray(chunk)
                var n = 0
                while (n < chunk) {
                    val r = input.read(buffer, n, chunk - n)
                    if (r < 0) break
                    n += r
                }
                if (n == 0) break
                pending += smb { f.writeAsync(buffer, offset, 0, n) } to n
                offset += n
                if (pending.size >= inFlight) settle()
                if (n < chunk) break
            }
            while (pending.isNotEmpty()) settle()
        }
    }

    override fun mkdir(path: String) {
        val (shareName, inner) = split(path)
        if (inner.isEmpty()) throw IOException("Shares can only be created on the server itself")
        smb { share(shareName).mkdir(inner) }
    }

    override fun delete(path: String, isDirectory: Boolean) {
        val (shareName, inner) = split(path)
        if (inner.isEmpty()) throw IOException("Shares can't be deleted from here")
        smb { if (isDirectory) share(shareName).rmdir(inner, true) else share(shareName).rm(inner) }
    }

    override fun rename(from: String, to: String) {
        val (a, innerFrom) = split(from)
        val (b, innerTo) = split(to)
        if (a != b || innerFrom.isEmpty() || innerTo.isEmpty()) throw IOException("Can't move between shares")
        val entry = smb {
            share(a).open(innerFrom, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        }
        entry.use { smb { it.rename(innerTo, false) } }
    }

    override fun close() {
        shares.values.forEach { runCatching { it.close() } }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    private inline fun <T> smb(block: () -> T): T = try {
        block()
    } catch (e: SMBApiException) {
        throw friendly(e)
    }

    private fun friendly(e: SMBApiException): IOException = ServerReplyException(
        when (e.status) {
            NtStatus.STATUS_ACCESS_DENIED -> "The server doesn't allow that"
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> "Something with that name is already there"
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE -> "Not found on the server"
            NtStatus.STATUS_BAD_NETWORK_NAME -> "There's no share with that name on the server"
            NtStatus.STATUS_DISK_FULL -> "The server is full"
            NtStatus.STATUS_SHARING_VIOLATION -> "The file is in use on the server"
            NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> "The folder isn't empty"
            else -> "The server said: ${e.status.name.removePrefix("STATUS_").lowercase().replace('_', ' ')}"
        },
        e,
    )
}

/** DCE/RPC over an SMB named pipe (used to ask the server for its list of shares). */
private class PipeTransport(private val pipe: NamedPipe) : RPCTransport() {
    override fun transact(request: ByteArray, response: ByteArray): Int = pipe.transact(request, response)

    override fun write(data: ByteArray) {
        pipe.write(data)
    }

    override fun read(buffer: ByteArray): Int = pipe.read(buffer)
}

/**
 * How many chunks may be on their way at the same time, so Wi-Fi round trips don't leave the network
 * idle. Old servers (SMB 2.0) only take 64 kB per request; then a few more travel at once.
 */
private fun inFlight(chunk: Int) = if (chunk >= 512 * 1024) 4 else 8

/** 1 MB chunks, or less when the server allows less. */
private fun chunkSize(serverMax: Int) = serverMax.coerceIn(64 * 1024, 1024 * 1024)

/**
 * Encryption (AES-GCM) through the phone's own crypto: Android's BoringSSL uses the processor's AES
 * instructions, much faster than plain Java for big transfers. Everything else, such as the MD4 that
 * password logins need, key derivation and AES-CCM on older servers, stays with Bouncy Castle.
 */
private class FastSecurityProvider : SecurityProvider {
    private val system = JceSecurityProvider()
    private val fallback = BCSecurityProvider()

    override fun getDigest(name: String): MessageDigest = fallback.getDigest(name)
    override fun getMac(name: String): Mac = fallback.getMac(name)
    override fun getCipher(name: String): Cipher = fallback.getCipher(name)
    override fun getDerivationFunction(name: String): DerivationFunction = fallback.getDerivationFunction(name)

    override fun getAEADBlockCipher(name: String): AEADBlockCipher =
        if (systemWorks(name)) system.getAEADBlockCipher(name) else fallback.getAEADBlockCipher(name)

    /** Checked once per algorithm: the phone's cipher must give exactly the same result as Bouncy Castle. */
    private fun systemWorks(name: String): Boolean = verified.getOrPut(name) {
        try {
            val key = ByteArray(16) { (it * 7 + 1).toByte() }
            val aad = ByteArray(52) { (it * 3).toByte() }
            val data = ByteArray(3000) { (it * 13).toByte() }
            fun seal(p: SecurityProvider): ByteArray {
                val c = p.getAEADBlockCipher(name)
                c.init(Cipher.CryptMode.ENCRYPT, key, javax.crypto.spec.GCMParameterSpec(128, ByteArray(if (name.contains("CCM")) 11 else 12) { it.toByte() }))
                c.updateAAD(aad, 0, aad.size)
                return c.doFinal(data, 0, data.size)
            }
            seal(system).contentEquals(seal(fallback))
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        val verified = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    }
}
