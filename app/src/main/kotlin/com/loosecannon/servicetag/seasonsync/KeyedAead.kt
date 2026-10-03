package com.loosecannon.servicetag.seasonsync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * #16 (C18, R16-5) — authenticated encryption under a named key: the one seam between [KeystoreSecretStore] and the
 * place its keys live. [AndroidKeystoreAead] keeps them in the Android Keystore; [JdkAead] keeps them in memory and
 * uses the JDK's own AES-GCM, so the whole store runs on the JVM. Both seal alike: AES-256-GCM with a fresh 12-byte IV
 * the cipher draws for every seal, and the sealed bytes are that IV followed by the GCM output (the ciphertext, then
 * the 16-byte tag).
 *
 * Nothing here keeps, prints or logs a plaintext, and no exception message carries one.
 */
interface KeyedAead {
    /** Seals [plaintext] under [alias]'s key, creating the key when there is none. */
    fun seal(alias: String, plaintext: ByteArray): ByteArray

    /**
     * Opens what [seal] produced. Throws [MissingKeyException] when [alias] names no key, and another
     * [GeneralSecurityException] (an [AEADBadTagException] when the tag fails) when the bytes are short or changed.
     */
    fun open(alias: String, sealed: ByteArray): ByteArray

    fun hasKey(alias: String): Boolean

    /** Deletes [alias]'s key; a missing key is nothing to delete, not a failure. */
    fun deleteKey(alias: String)

    fun aliases(): Set<String>
}

/** [KeyedAead.open]'s answer when the alias names no key: after a platform restore the key never comes back (H5). */
class MissingKeyException : GeneralSecurityException("no key under this alias")

/**
 * The device's [KeyedAead]: one AES-256-GCM key per alias in `AndroidKeyStore`, for encryption and decryption only,
 * never exportable, and usable without the owner's authentication, because background reads must work (R16-5, D9).
 * The Keystore is loaded on first use, never at construction.
 */
class AndroidKeystoreAead : KeyedAead {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun seal(alias: String, plaintext: ByteArray): ByteArray =
        keystoreCall { AesGcm.seal(keyOrNull(alias) ?: create(alias), plaintext) }

    override fun open(alias: String, sealed: ByteArray): ByteArray =
        keystoreCall { AesGcm.open(keyOrNull(alias) ?: throw MissingKeyException(), sealed) }

    override fun hasKey(alias: String): Boolean = keyStore.containsAlias(alias)

    override fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    override fun aliases(): Set<String> = keyStore.aliases().toList().toSet()

    private fun keyOrNull(alias: String): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    private fun create(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    /** The Keystore reports some failures as a [ProviderException]; the store hears every one as a security failure. */
    private inline fun <T> keystoreCall(block: () -> T): T =
        try {
            block()
        } catch (e: ProviderException) {
            throw GeneralSecurityException("the Keystore refused the operation", e)
        }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}

/**
 * The JVM's [KeyedAead]: the same AES-256-GCM through the JDK, its keys in memory for the life of the instance — so a
 * new instance over an old directory stands for a platform restore, whose keys never come back. The test graph's.
 */
class JdkAead : KeyedAead {
    private val keys = ConcurrentHashMap<String, SecretKey>()

    override fun seal(alias: String, plaintext: ByteArray): ByteArray =
        AesGcm.seal(keys.computeIfAbsent(alias) { newKey() }, plaintext)

    override fun open(alias: String, sealed: ByteArray): ByteArray =
        AesGcm.open(keys[alias] ?: throw MissingKeyException(), sealed)

    override fun hasKey(alias: String): Boolean = keys.containsKey(alias)

    override fun deleteKey(alias: String) {
        keys.remove(alias)
    }

    override fun aliases(): Set<String> = keys.keys.toSet()

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }.generateKey()
}

private const val KEY_BITS = 256

/** The one layout both implementations share: the IV the cipher drew, then its output. */
private object AesGcm {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
    const val TAG_BITS = 128

    fun seal(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No IV is passed in: the cipher draws a fresh one for every seal, as the Keystore insists.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv: ByteArray? = cipher.iv
        if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("the cipher drew an unexpected IV")
        return iv + cipher.doFinal(plaintext)
    }

    fun open(key: SecretKey, sealed: ByteArray): ByteArray {
        if (sealed.size < IV_BYTES + TAG_BITS / 8) throw AEADBadTagException("the sealed bytes are too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }
}
