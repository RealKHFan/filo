package app.filo.vault

import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.min

/**
 * Encrypts files of any size with AES-256-GCM in 64 kB chunks.
 *
 * Layout:  "FILO1" | 7-byte random prefix | chunk 0 | chunk 1 | ... | last chunk
 * Each chunk is GCM-sealed with nonce = prefix | 4-byte chunk number | last-chunk flag,
 * and the header is authenticated too. That detects any change, reordering, or cut-off file.
 */
object StreamCipher {
    private val MAGIC = "FILO1".toByteArray(Charsets.US_ASCII)
    private const val PREFIX_SIZE = 7
    private const val TAG_SIZE = 16
    const val CHUNK_SIZE = 64 * 1024
    private const val SEALED_SIZE = CHUNK_SIZE + TAG_SIZE
    private val HEADER_SIZE = MAGIC.size + PREFIX_SIZE

    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey, random: SecureRandom = SecureRandom()) {
        Sealer(output, key, random).use { input.copyTo(it, CHUNK_SIZE) }
    }

    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey) {
        Opener(input, key).copyTo(output, CHUNK_SIZE)
    }

    /**
     * Encrypts what is written to it into [out]. close() seals the last chunk; [out] itself stays open.
     * A full chunk is only sealed once more data follows, because the last one is marked as last.
     */
    class Sealer(private val out: OutputStream, private val key: SecretKey, random: SecureRandom = SecureRandom()) : OutputStream() {
        private val prefix = ByteArray(PREFIX_SIZE).also(random::nextBytes)
        private val header = MAGIC + prefix
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val buffer = ByteArray(CHUNK_SIZE)
        private var filled = 0
        private var counter = 0
        private var started = false
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "Already closed" }
            start()
            var from = off
            var left = len
            while (left > 0) {
                if (filled == CHUNK_SIZE) seal(last = false)
                val n = min(left, CHUNK_SIZE - filled)
                System.arraycopy(b, from, buffer, filled, n)
                filled += n
                from += n
                left -= n
            }
        }

        override fun close() {
            if (closed) return
            start()
            seal(last = true)
            closed = true
            out.flush()
        }

        private fun start() {
            if (started) return
            out.write(header)
            started = true
        }

        private fun seal(last: Boolean) {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * 8, nonce(prefix, counter, last)))
            cipher.updateAAD(header)
            out.write(cipher.doFinal(buffer, 0, filled))
            filled = 0
            if (!last) {
                if (counter == Int.MAX_VALUE) throw GeneralSecurityException("File too large")
                counter++
            }
        }
    }

    /**
     * Decrypts [input] while it is read. Every chunk is checked before any of it is handed out, and
     * a file cut off anywhere fails at its end. The first chunk is checked right away, so a wrong key
     * fails here and not on the first read.
     */
    class Opener(private val input: InputStream, private val key: SecretKey) : InputStream() {
        private val header = ByteArray(HEADER_SIZE)
        private val prefix: ByteArray
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private var current = ByteArray(SEALED_SIZE)
        private var next = ByteArray(SEALED_SIZE)
        private var currentLength: Int
        private var counter = 0
        private var plain = ByteArray(0)
        private var position = 0
        private var finished = false

        init {
            if (readFully(input, header) != HEADER_SIZE || !header.copyOf(MAGIC.size).contentEquals(MAGIC)) {
                throw GeneralSecurityException("Not a Filo hidden-folder file")
            }
            prefix = header.copyOfRange(MAGIC.size, HEADER_SIZE)
            currentLength = readFully(input, current)
            openChunk()
        }

        /** Decrypts the next chunk into [plain]; false once the last one was read. */
        private fun openChunk(): Boolean {
            if (finished) return false
            if (currentLength < TAG_SIZE) throw GeneralSecurityException("File is cut off")
            val nextLength = if (currentLength == SEALED_SIZE) readFully(input, next) else 0
            val last = nextLength == 0
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * 8, nonce(prefix, counter, last)))
            cipher.updateAAD(header)
            plain = cipher.doFinal(current, 0, currentLength) // throws if anything was tampered with
            position = 0
            if (last) {
                finished = true
            } else {
                counter++
                val swap = current
                current = next
                next = swap
                currentLength = nextLength
            }
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= plain.size) if (!openChunk()) return -1
            val n = min(len, plain.size - position)
            System.arraycopy(plain, position, b, off, n)
            position += n
            return n
        }

        override fun close() = input.close()
    }

    private fun nonce(prefix: ByteArray, counter: Int, last: Boolean): ByteArray {
        val n = ByteArray(12)
        prefix.copyInto(n, 0)
        n[7] = (counter ushr 24).toByte()
        n[8] = (counter ushr 16).toByte()
        n[9] = (counter ushr 8).toByte()
        n[10] = counter.toByte()
        n[11] = if (last) 1 else 0
        return n
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
