package app.filo.vault

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.math.log2

/**
 * A backup of the hidden folder, for moving to another phone.
 *
 * Layout: "FILOBAK1" | Argon2id memory (KiB), iterations, parallelism | 16-byte salt | StreamCipher data.
 * The data is sealed with the key from your password: the folder list and Filo's settings, then every
 * hidden file with its name and folder. Without the password none of it can be read or changed.
 */
object BackupFile {
    const val EXTENSION = "filo"
    const val MIN_LENGTH = 12
    private val MAGIC = "FILOBAK1".toByteArray(Charsets.US_ASCII)

    fun isBackup(name: String) = name.endsWith(".$EXTENSION", ignoreCase = true)

    /** Starts a backup in [out]. Write the content into what this returns, then close that (it leaves [out] open). */
    fun create(out: OutputStream, password: String, kdf: PasswordKdf = PasswordKdf(), random: SecureRandom = SecureRandom()): OutputStream {
        val salt = ByteArray(16).also(random::nextBytes)
        val key = kdf.derive(password, salt)
        DataOutputStream(out).apply {
            write(MAGIC)
            writeInt(kdf.memoryKiB)
            writeInt(kdf.iterations)
            writeInt(kdf.parallelism)
            write(salt)
            flush()
        }
        return StreamCipher.Sealer(out, key, random)
    }

    /** Opens a backup for reading. The password is checked right here. */
    fun open(input: InputStream, password: String): InputStream {
        val data = DataInputStream(input)
        try {
            val magic = ByteArray(MAGIC.size).also(data::readFully)
            if (!magic.contentEquals(MAGIC)) throw NotABackupException()
            val memory = data.readInt()
            val iterations = data.readInt()
            val parallelism = data.readInt()
            // A changed file must not make Filo use gigabytes of memory or think for an hour.
            if (memory !in 8_192..262_144 || iterations !in 1..10 || parallelism !in 1..4) throw DamagedBackupException()
            val salt = ByteArray(16).also(data::readFully)
            return StreamCipher.Opener(input, PasswordKdf(memory, iterations, parallelism).derive(password, salt))
        } catch (_: EOFException) {
            throw NotABackupException()
        } catch (_: AEADBadTagException) {
            throw WrongPasswordException()
        } catch (_: GeneralSecurityException) {
            throw DamagedBackupException()
        }
    }
}

class NotABackupException : IOException("This isn't a Filo backup")
class WrongPasswordException : IOException("Wrong password")
class DamagedBackupException : IOException("This backup is damaged or incomplete")

/** Where a backup comes from: a file in Filo, or one picked with Android's file picker. */
class BackupSource(val name: String, val size: Long, val open: () -> InputStream)

/** A backup whose password was checked, ready to read. [onBytes] reports progress through the file. */
class OpenedBackup private constructor(val name: String, val size: Long, private val raw: InputStream, password: String) : Closeable {
    var onBytes: (Long) -> Unit = {}
    private val counted = object : FilterInputStream(raw) {
        override fun read(): Int = super.read().also { if (it >= 0) onBytes(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
    }
    val content: InputStream = BackupFile.open(counted.buffered(256 * 1024), password)

    override fun close() = raw.close()

    companion object {
        fun open(source: BackupSource, password: String): OpenedBackup {
            val raw = source.open()
            return try {
                OpenedBackup(source.name, source.size, raw, password)
            } catch (e: Throwable) {
                raw.close()
                throw e
            }
        }
    }
}

/** A rough guess of how hard a backup password is to guess, so weak ones are refused. */
enum class PasswordStrength(val label: String) {
    TOO_SHORT("Too short"),
    WEAK("Too easy to guess"),
    OKAY("Okay"),
    STRONG("Strong"),
    VERY_STRONG("Very strong"),
    ;

    val acceptable: Boolean get() = this >= OKAY

    companion object {
        private val common = listOf(
            "password", "passwort", "qwertz", "qwerty", "123456", "letmein", "iloveyou", "admin", "welcome",
            "hallo", "master", "monkey", "dragon", "football", "sunshine", "princess", "geheim", "filo",
        )

        fun of(password: String): PasswordStrength {
            if (password.codePointCount(0, password.length) < BackupFile.MIN_LENGTH) return TOO_SHORT
            val bits = bits(password)
            return when {
                bits < 50 -> WEAK
                bits < 70 -> OKAY
                bits < 100 -> STRONG
                else -> VERY_STRONG
            }
        }

        /** Characters that repeat or continue a run (aaaa, 1234, dcba) count for little, common words as one. */
        internal fun bits(password: String): Double {
            var pool = 0
            if (password.any { it.isLowerCase() }) pool += 26
            if (password.any { it.isUpperCase() }) pool += 26
            if (password.any { it.isDigit() }) pool += 10
            if (password.any { !it.isLetterOrDigit() && it.code < 128 }) pool += 33
            if (password.any { it.code >= 128 }) pool += 100
            var text = password.lowercase()
            var effective = 0.0
            for (word in common) {
                while (word in text) {
                    text = text.replaceFirst(word, "\u0000")
                    effective += 1
                }
            }
            var previous: Char? = null
            for (c in text) {
                if (c == '\u0000') {
                    previous = null
                    continue
                }
                val step = previous?.let { c.code - it.code }
                effective += if (step != null && step in -1..1) 0.25 else 1.0
                previous = c
            }
            return effective * log2(pool.coerceAtLeast(2).toDouble())
        }
    }
}
