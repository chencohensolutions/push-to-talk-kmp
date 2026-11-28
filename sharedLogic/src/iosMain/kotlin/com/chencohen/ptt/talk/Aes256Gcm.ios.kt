package com.chencohen.ptt.talk

import com.chencohen.ptt.ios.IosHost
import com.chencohen.ptt.ios.toByteArray
import com.chencohen.ptt.ios.toNSData

/** AES-256-GCM through CryptoKit, which only Swift reaches: the app's host (IosPtt sets it at start). */
internal actual object Aes256Gcm {
    var host: IosHost? = null

    private fun host(): IosHost = host ?: throw IllegalStateException("The iOS host isn't set yet")

    actual fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        return host().aesGcmSeal(key.toNSData(), nonce.toNSData(), aad.toNSData(), plaintext.toNSData())?.toByteArray()
            ?: throw IllegalStateException("AES-GCM couldn't seal")
    }

    actual fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (key.size != 32 || nonce.size != 12) return null
        return host().aesGcmOpen(key.toNSData(), nonce.toNSData(), aad.toNSData(), ciphertext.toNSData())?.toByteArray()
    }
}
