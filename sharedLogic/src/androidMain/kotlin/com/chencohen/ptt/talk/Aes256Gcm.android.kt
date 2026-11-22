package com.chencohen.ptt.talk

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual object Aes256Gcm {
    actual fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    actual fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? = try {
        cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)
    } catch (_: AEADBadTagException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(key.size == 32 && nonce.size == 12)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
    }
}
