package app.filo.vault

import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
    private val HEADER_SIZE = MAGIC.size + PREFIX_SIZE

    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey, random: SecureRandom = SecureRandom()) {
        val prefix = ByteArray(PREFIX_SIZE).also(random::nextBytes)
        val header = MAGIC + prefix
        output.write(header)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var current = ByteArray(CHUNK_SIZE)
        var next = ByteArray(CHUNK_SIZE)
        var currentLength = readFully(input, current)
        var counter = 0
        while (true) {
            val nextLength = if (currentLength == CHUNK_SIZE) readFully(input, next) else 0
            val last = nextLength == 0
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * 8, nonce(prefix, counter, last)))
            cipher.updateAAD(header)
            output.write(cipher.doFinal(current, 0, currentLength))
            if (last) break
            if (counter == Int.MAX_VALUE) throw GeneralSecurityException("File too large")
            counter++
            val swap = current
            current = next
            next = swap
            currentLength = nextLength
        }
    }

    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey) {
        val header = ByteArray(HEADER_SIZE)
        if (readFully(input, header) != HEADER_SIZE || !header.copyOf(MAGIC.size).contentEquals(MAGIC)) {
            throw GeneralSecurityException("Not a Filo hidden-folder file")
        }
        val prefix = header.copyOfRange(MAGIC.size, HEADER_SIZE)
        val sealedSize = CHUNK_SIZE + TAG_SIZE
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var current = ByteArray(sealedSize)
        var next = ByteArray(sealedSize)
        var currentLength = readFully(input, current)
        var counter = 0
        while (true) {
            if (currentLength < TAG_SIZE) throw GeneralSecurityException("File is cut off")
            val nextLength = if (currentLength == sealedSize) readFully(input, next) else 0
            val last = nextLength == 0
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * 8, nonce(prefix, counter, last)))
            cipher.updateAAD(header)
            output.write(cipher.doFinal(current, 0, currentLength)) // throws if anything was tampered with
            if (last) break
            counter++
            val swap = current
            current = next
            next = swap
            currentLength = nextLength
        }
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
