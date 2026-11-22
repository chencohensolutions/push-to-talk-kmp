package com.chencohen.ptt.talk

// iOS comes after phase 6 (docs/design.md): CryptoKit's AES.GCM goes here then, through a small Swift shim.
internal actual object Aes256Gcm {
    actual fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        throw UnsupportedOperationException("Talk isn't built for iOS yet")

    actual fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? =
        throw UnsupportedOperationException("Talk isn't built for iOS yet")
}
