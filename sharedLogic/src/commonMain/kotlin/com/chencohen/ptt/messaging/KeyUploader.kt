package com.chencohen.ptt.messaging

import com.chencohen.ptt.crypto.CryptoEngine
import com.chencohen.ptt.crypto.nextKeyId
import com.chencohen.ptt.crypto.SignedPublicPrekey
import kotlin.time.Duration.Companion.days

/** Where this phone's keys stand with the server. */
sealed interface KeysState {
    /** Not checked yet (not registered, or the server not reached). */
    data object Unknown : KeysState

    data object Uploading : KeysState

    /** The server holds this phone's keys; [count] one-time prekeys are left. */
    data class Ready(val count: PrekeyCount) : KeysState

    /**
     * The account has an identity key this phone doesn't hold: another phone of the user made it. Linking this phone
     * from that one comes later; until then the user can start over with a new key ([MessagingController.replaceIdentityKey])
     * once the other phones are revoked.
     */
    data object OtherIdentity : KeysState

    data class Failed(val message: String) : KeysState
}

/**
 * Uploads this phone's public keys (docs/design.md, "Keys and sessions"): on the first connection as a device the
 * identity key, the signed prekey, the last-resort Kyber prekey and [batch] one-time prekeys of each kind; a new
 * signed prekey every [rotateEvery]; and [batch] more of a kind when the server holds fewer than its `refillBelow`.
 */
internal class KeyUploader(
    private val engine: CryptoEngine,
    private val api: MessagingApi,
    private val store: MessagingStore,
    private val now: () -> Long,
    private val batch: Int = 100,
    private val rotateEvery: kotlin.time.Duration = 7.days,
) {
    /** Uploads what is missing for [device] ("user/number"); answers where the keys stand. Throws on network errors. */
    suspend fun ensure(device: String): KeysState {
        val serverKey = api.myIdentityKey()
        var identity = engine.identityKey()
        if (identity == null) {
            if (serverKey != null) return KeysState.OtherIdentity
            identity = engine.createIdentity()
        } else if (serverKey != null && !serverKey.contentEquals(identity)) {
            return KeysState.OtherIdentity
        }
        val meta = store.meta
        val first = meta.uploadedFor != device
        val rotate = first || now() - meta.signedAt >= rotateEvery.inWholeMilliseconds
        var count = try {
            if (rotate) upload(device, full = first, signed = true, prekeys = first, kyber = first) else api.prekeyCount()
        } catch (e: ApiException) {
            when (e.code) {
                // The server lost this device's keys (revoked and enrolled again with the same number, or replaced).
                ApiCodes.MISSING_KEYS -> upload(device, full = true, signed = true, prekeys = true, kyber = true)
                ApiCodes.IDENTITY_KEY_MISMATCH -> return KeysState.OtherIdentity
                else -> throw e
            }
        }
        count = refill(device, count)
        return KeysState.Ready(count)
    }

    /** Uploads more one-time prekeys of each kind the server holds too few of. */
    suspend fun refill(device: String, count: PrekeyCount): PrekeyCount {
        val ec = count.oneTimePrekeys < count.refillBelow
        val kyber = count.oneTimeKyberPrekeys < count.refillBelow
        if (!ec && !kyber) return count
        return try {
            upload(device, full = false, signed = false, prekeys = ec, kyber = kyber)
        } catch (e: ApiException) {
            if (e.code != ApiCodes.MISSING_KEYS) throw e
            upload(device, full = true, signed = true, prekeys = true, kyber = true)
        }
    }

    /** Asks the server how many prekeys it holds and refills; for each reconnection of /ws. */
    suspend fun check(device: String): PrekeyCount = refill(device, api.prekeyCount())

    private suspend fun upload(device: String, full: Boolean, signed: Boolean, prekeys: Boolean, kyber: Boolean): PrekeyCount {
        val identity = engine.identityKey() ?: engine.createIdentity()
        var meta = store.meta
        val at = now()
        var signedKey: SignedPublicPrekey? = null
        if (signed) {
            signedKey = engine.generateSignedPrekey(meta.nextSignedId, at)
            meta = meta.copy(nextSignedId = nextKeyId(meta.nextSignedId))
        }
        var lastResort: SignedPublicPrekey? = null
        if (full) {
            lastResort = engine.generateKyberPrekey(meta.nextKyberId, lastResort = true, nowMillis = at)
            meta = meta.copy(nextKyberId = nextKeyId(meta.nextKyberId))
        }
        val oneTime = if (prekeys) engine.generatePrekeys(meta.nextPrekeyId, batch) else emptyList()
        if (prekeys) meta = meta.copy(nextPrekeyId = (1..batch).fold(meta.nextPrekeyId) { id, _ -> nextKeyId(id) })
        val oneTimeKyber = if (kyber) {
            (0 until batch).map {
                engine.generateKyberPrekey(meta.nextKyberId, lastResort = false, nowMillis = at).also {
                    meta = meta.copy(nextKyberId = nextKeyId(meta.nextKyberId))
                }
            }
        } else {
            emptyList()
        }
        // The ids are taken before the upload, so a failed one never makes the next reuse them.
        store.meta = meta
        val count = api.uploadPrekeys(PrekeyUpload(identity, engine.registrationId(), signedKey, lastResort, oneTime, oneTimeKyber))
        var done = store.meta
        if (full) done = done.copy(uploadedFor = device)
        if (signedKey != null) {
            done = done.copy(signedId = signedKey.keyId, signedAt = at)
            // Old signed prekeys are kept a month for messages still on their way.
            engine.removeSignedPrekeys(keep = signedKey.keyId, beforeMillis = at - 30.days.inWholeMilliseconds)
        }
        store.meta = done
        return count
    }
}
