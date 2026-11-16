//
//  KeystoreSessionStore.kt
//  app
//
//  `SessionStore` backed by the Android Keystore: persists credentials
//  + tokens so warm launch can auto-login without a password round
//  trip. No secret ever reaches plain storage — the record is sealed
//  with an AES-GCM key the Keystore generates and never exports (the
//  port of `KeychainSessionStore` + `KeychainStore`). The app-side half
//  of the injected store seam.
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pandoracore.SessionRecord
import pandoracore.SessionStore

class KeystoreSessionStore(
    context: Context,
    private val account: String = "session",
) : SessionStore {

    private val json = Json
    private val sealFile: File =
        File(context.filesDir, "session-$account.seal")

    private val key: SecretKey by lazy { obtainKey() }

    override fun save(record: SessionRecord) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val sealed = cipher.iv + cipher.doFinal(json.encodeToString(record).toByteArray(Charsets.UTF_8))
        val tmp = File(sealFile.parentFile, sealFile.name + ".tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(sealFile)) {
            sealFile.writeBytes(sealed)
            tmp.delete()
        }
    }

    override fun load(): SessionRecord? {
        // A missing file or an unsealable blob both mean "no usable
        // session" — the user sees the login screen and the next save
        // overwrites it.
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
            val plain = cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH)
            json.decodeFromString<SessionRecord>(plain.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    override fun clear() {
        sealFile.delete()
    }

    /** The Keystore-held AES key, generated on first use. The key
     *  material never leaves the secure hardware — only this app can
     *  use it, which is the property the Keychain provided on iOS. */
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
