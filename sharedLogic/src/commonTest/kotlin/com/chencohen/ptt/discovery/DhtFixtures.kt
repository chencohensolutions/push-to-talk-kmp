package com.chencohen.ptt.discovery

/**
 * A server's DHT record as ptt-server's `discovery` package writes it: made on Nov 21, 2026 with journal-server-go's
 * record.go sealing code and ptt-server's salt and info string (a fixed DHT key and nonce), for the phones' DHT client
 * to read back exactly. All hex.
 */
object DhtFixtures {
    /** The server's ed25519 public key in the DHT (`serverInfo.discovery.key`). */
    const val KEY = "d72c51a2123f3273e7cb48745fd4d8c5044feb1be4b583c1d82538d894c8cbab"

    /** The server's fingerprint (`serverInfo.discovery.fingerprint`): the seal's key is made from it. */
    const val FINGERPRINT = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    /** BEP 44 salt, as text. */
    const val SALT = "ptt-server/v1"

    /** SHA-1(KEY bytes + SALT bytes): the DHT target to `get`. */
    const val TARGET = "e97f305f4f89129d89724fd077a008eb298b885e"

    const val SEQ = 1795287600L

    /** The item's value `v` (a bencoded byte string in the reply): 24-byte nonce, then XChaCha20-Poly1305 ciphertext. */
    const val SEALED = "404142434445464748494a4b4c4d4e4f505152535455565783c4585fe22687485cc049086f85bd776ab70dca45170d30ddae2a29dc602ff6fbfac6c8a4d0bf0d572977c484c9e59481d5fda83cb2236f790ae12bb9cd623b9caae94076b5ff935aa0e26a1cfc0e2f88595408bb9dc706e031b0050d876e3b62383eb1ec1e3d37e3cf4734e2839ece6ab1756bbe3da43d239f5e637f6a8051443e220d52b785fc037a3307b3abb1"

    /** The ed25519 signature over BEP 44's "4:salt13:ptt-server/v13:seqi<SEQ>e1:v<len>:<SEALED>". */
    const val SIG = "04d1e323707d86af8afee6237bc07b59cfc5aa247965442359fa845a7ca6e535a7dd95262568418a9dcab807700bc9e82e926355161fac1da56ed4094c971c0b"

    /** A whole KRPC `get` reply holding the item, bencoded, as a DHT node sends it. */
    const val REPLY = "64313a7264323a696432303a0000000000000000000000000000000000000000313a6b33323ad72c51a2123f3273e7cb48745fd4d8c5044feb1be4b583c1d82538d894c8cbab333a736571693137393532383736303065333a73696736343a04d1e323707d86af8afee6237bc07b59cfc5aa247965442359fa845a7ca6e535a7dd95262568418a9dcab807700bc9e82e926355161fac1da56ed4094c971c0b353a746f6b656e333a746f6b313a763136373a404142434445464748494a4b4c4d4e4f505152535455565783c4585fe22687485cc049086f85bd776ab70dca45170d30ddae2a29dc602ff6fbfac6c8a4d0bf0d572977c484c9e59481d5fda83cb2236f790ae12bb9cd623b9caae94076b5ff935aa0e26a1cfc0e2f88595408bb9dc706e031b0050d876e3b62383eb1ec1e3d37e3cf4734e2839ece6ab1756bbe3da43d239f5e637f6a8051443e220d52b785fc037a3307b3abb165313a74323a6161313a79313a7265"

    /** What the sealed record opens to. */
    val ADDRESSES = listOf("https://203.0.113.7:56400", "https://[2001:db8::7]:56400", "https://192.168.1.20:56400")
    const val AT = "2026-11-21T19:00:00Z"
}
