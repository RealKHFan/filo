package app.filo.vault

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Seals small secrets with a key that never leaves this phone's security chip. */
interface KeyWrap {
    fun wrap(data: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}

/** AES-256-GCM with a plain in-memory key: "iv | ciphertext". */
object Gcm {
    private val random = SecureRandom()

    fun seal(key: SecretKey, data: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(data)
    }

    fun open(key: SecretKey, blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}

/** Turns the 6-digit PIN into a key. Slow on purpose. */
class PinKdf(val iterations: Int = DEFAULT_ITERATIONS) {
    companion object {
        const val DEFAULT_ITERATIONS = 150_000
    }

    fun derive(pin: String, salt: ByteArray): SecretKey {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }
}

/** Used by unit tests, where there is no security chip. */
class SoftwareKeyWrap(private val key: SecretKey = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")) : KeyWrap {
    override fun wrap(data: ByteArray) = Gcm.seal(key, data)
    override fun unwrap(blob: ByteArray) = Gcm.open(key, blob)
}

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

/**
 * Hardware-backed key (StrongBox / Titan M2 on Pixel when available). It binds the hidden folder to
 * this phone, so a copied vault can't be brute-forced elsewhere even though the PIN is only 6 digits.
 */
class AndroidDeviceKey(
    private val packageManager: PackageManager,
    private val alias: String = "filo_device_v1",
) : KeyWrap {
    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return try {
            generate(strongBox)
        } catch (e: ProviderException) {
            if (strongBox) generate(false) else throw e
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    override fun wrap(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(data)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}

/** Creates ciphers for fingerprint unlock. Each use needs a fresh fingerprint touch. */
interface BiometricKey {
    /** For turning fingerprint on: creates a new key and a cipher to unlock with a touch. */
    fun newEncryptCipher(): Cipher

    /** For unlocking. Throws KeyPermanentlyInvalidatedException if fingerprints changed. */
    fun decryptCipher(iv: ByteArray): Cipher

    fun delete()
}

class AndroidBiometricKey(private val alias: String = "filo_fingerprint_v1") : BiometricKey {
    override fun newEncryptCipher(): Cipher {
        delete()
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        val key = generator.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(iv: ByteArray): Cipher {
        val key = keyStore().getKey(alias, null) as? SecretKey
            ?: throw android.security.keystore.KeyPermanentlyInvalidatedException("Fingerprint key missing")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
    }

    override fun delete() {
        try {
            keyStore().deleteEntry(alias)
        } catch (_: Exception) {
        }
    }
}
