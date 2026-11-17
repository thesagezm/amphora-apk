//
//  KeystoreSecretStore.kt
//  app
//
//  The generic app-secret store — the port of `KeychainStore`'s role
//  (set / data / remove, account-scoped). `KeystoreSessionStore` covers
//  the Pandora session; this one holds the other secrets (the Last.fm
//  session key today). Same discipline: the payload is sealed with an
//  AES-GCM key the Android Keystore generates and never exports — no
//  secret ever reaches plain storage.
//
//  Port delta: iOS's KeychainStore takes raw Data blobs per account;
//  here each account is its own seal file, so the store carries one
//  opaque payload (the Scrobbler JSON-encodes its session before
//  handing it over — exactly what the Swift call site did).
//

package dev.personal.amphora.pandora

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreSecretStore(
    context: Context,
    private val account: String,
) {

    private val sealFile: File =
        File(context.filesDir, "secret-$account.seal")

    private val key: SecretKey by lazy { obtainKey() }

    /** Seals the payload. Failures are best-effort writes like the
     *  session store's — the next save retries. */
    fun set(data: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val sealed = cipher.iv + cipher.doFinal(data)
        val tmp = File(sealFile.parentFile, sealFile.name + ".tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(sealFile)) {
            sealFile.writeBytes(sealed)
            tmp.delete()
        }
    }

    /** The payload, or null when absent or unsealable ("no secret" —
     *  the owner re-authenticates; the next save overwrites). */
    fun data(): ByteArray? {
        if (!sealFile.exists()) return null
        return try {
            val sealed = sealFile.readBytes()
            if (sealed.size <= IV_LENGTH) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, sealed, 0, IV_LENGTH),
            )
            cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH)
        } catch (_: Exception) {
            null
        }
    }

    fun remove() {
        sealFile.delete()
    }

    /** The Keystore-held AES key, generated on first use (the same
     *  derivation `KeystoreSessionStore` uses — a shared alias keeps
     *  one key for the app's secrets). */
    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "amphora.session"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
