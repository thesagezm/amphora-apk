//
//  PandoraCrypto.kt
//  pandoracore
//
//  Port of PandoraCore/Crypto/PandoraCrypto.swift — Blowfish-ECB
//  primitives for Pandora's legacy JSON API (v5).
//
//  Protocol shape, derived from `pithos.pandora` (verified against the
//  vendored `pithos/pandora/blowfish.py` + `pandora.py`):
//
//  - Request bodies: zero-pad each 8-byte chunk (pad only a partial final
//    chunk, never a full extra block) → Blowfish-ECB with `encryptKey`
//    → lowercase hex string, sent as the POST body.
//  - Response fields (e.g. `syncTime`): hex string → Blowfish-ECB decrypt
//    with `decryptKey` → raw bytes (callers strip trailing zero bytes).
//
//  The stock JVM/JCE ships Blowfish-ECB, so the iOS CryptoSwift dependency
//  needs no Android equivalent.
//
//  Hard rule: no Android, no UI, no OkHttp — pure logic for every platform.
//

package pandoracore

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Port of `PandoraCryptoError`. */
sealed class PandoraCryptoException(message: String) : Exception(message) {
    /** Input was not valid hexadecimal, or had an odd number of digits. */
    data object InvalidHex : PandoraCryptoException("invalid hex")

    /** Ciphertext length was not a multiple of the 8-byte Blowfish block. */
    data class NotBlockAligned(val actual: kotlin.Int) :
        PandoraCryptoException("ciphertext length $actual is not a multiple of the 8-byte Blowfish block")
}

object PandoraCrypto {

    /** Blowfish block size in bytes. */
    const val BLOCK_SIZE: kotlin.Int = 8

    private fun cipher(key: String, mode: kotlin.Int): Cipher =
        Cipher.getInstance("Blowfish/ECB/NoPadding").apply {
            init(mode, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "Blowfish"))
        }

    /**
     * Encrypts `plaintext` with Blowfish-ECB under `key`.
     * Zero-pads to the block boundary first; an already-aligned input is
     * left untouched (matching pithos' per-chunk `pad`), so a message whose
     * length is a multiple of 8 gains no extra block.
     */
    fun encrypt(plaintext: ByteArray, key: String): ByteArray {
        if (plaintext.isEmpty()) return ByteArray(0)
        val remainder = plaintext.size % BLOCK_SIZE
        val padded = if (remainder != 0) {
            plaintext + ByteArray(BLOCK_SIZE - remainder)
        } else {
            plaintext
        }
        return cipher(key, Cipher.ENCRYPT_MODE).doFinal(padded)
    }

    /**
     * Decrypts Blowfish-ECB `ciphertext` under `key`.
     * Trailing zero-padding from the encrypt path is NOT stripped here —
     * callers slice fields exactly as pithos does (`syncTime[4:14]`).
     */
    fun decrypt(ciphertext: ByteArray, key: String): ByteArray {
        if (ciphertext.isEmpty()) return ByteArray(0)
        if (ciphertext.size % BLOCK_SIZE != 0) {
            throw PandoraCryptoException.NotBlockAligned(ciphertext.size)
        }
        return try {
            cipher(key, Cipher.DECRYPT_MODE).doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("blowfish decrypt failed", e)
        }
    }

    /** Encrypts then hex-encodes — the exact wire form of a request body. */
    fun encryptToHex(plaintext: ByteArray, key: String): String =
        hexString(encrypt(plaintext, key))

    /** Hex-decodes then decrypts — the exact inverse of `encryptToHex`. */
    fun decryptFromHex(hex: String, key: String): ByteArray =
        decrypt(dataFromHex(hex), key)

    // MARK: Wire conveniences keyed with `PandoraAPI` constants

    /** Encrypts a JSON request body for transmission (uses `encryptKey`). */
    fun encryptRequestBody(json: ByteArray): String =
        encryptToHex(json, PandoraAPI.encryptKey)

    /** Decrypts a hex-encoded response field such as `syncTime`
     *  (uses `decryptKey`). */
    fun decryptHexField(hex: String): ByteArray =
        decryptFromHex(hex, PandoraAPI.decryptKey)

    // MARK: Hex helpers (owned locally: lowercase output, strict input)

    internal fun hexString(data: ByteArray): String = buildString(data.size * 2) {
        for (byte in data) append(String.format("%02x", byte))
    }

    internal fun dataFromHex(hex: String): ByteArray {
        val trimmed = hex.trim()
        if (trimmed.length % 2 != 0) throw PandoraCryptoException.InvalidHex
        val bytes = ByteArray(trimmed.length / 2)
        for (i in bytes.indices) {
            val byte = ((Character.digit(trimmed[i * 2], 16) shl 4)
                or Character.digit(trimmed[i * 2 + 1], 16))
            if (byte < 0) throw PandoraCryptoException.InvalidHex
            bytes[i] = byte.toByte()
        }
        return bytes
    }
}
