package com.chencohen.ptt.ios

import com.chencohen.ptt.tls.ServerPin
import platform.Foundation.NSData

/**
 * A LiveKit room, as Swift gives it to the shared code (LiveKit's Swift SDK; docs/ios.md, "Live talk"). Only what the
 * SDK does is here: joining, the keys of its frame encryption, the microphone's track and its mute, the others'
 * volume, data messages. When each of them happens is the shared code's (IosTalkTransport, then LiveTalk and Talk).
 *
 * Calls come from any thread, one at a time; [events] are called from any thread too. Keys are the 32 random bytes in
 * base64, the string LiveKit derives its AES key from (as Android's client gets them).
 */
interface IosTalkRoom {
    var events: IosTalkRoomEvents?

    /**
     * Joins the room at [url] (`wss://host:port`, the server's own address: the SDK adds `/rtc`) with [token], listening
     * only: no microphone or camera track. This device's frames are encrypted with [ownKey] (index [ownIndex] of a ring
     * of [keyRing]) as [identity]; a frame whose key isn't there is dropped, never played or sent in the clear. The
     * room's audio plays on the loudspeaker as media, mixed with what else plays. [done]: whether it is joined; the
     * ones already in the room are told by [IosTalkRoomEvents.joined] before it.
     */
    fun join(url: String, token: String, identity: String, ownKey: String, ownIndex: Int, keyRing: Int, done: (Boolean) -> Unit)

    /** Leaves the room and lets go of the microphone and the audio session. Nothing happens when in none. */
    fun leave()

    /** [identity]'s ("user.device") key for its frames. It may come before [join]: kept for the rooms joined after it. */
    fun setKey(identity: String, key: String, index: Int)

    /**
     * The microphone's track, published muted and sending nothing (so a press only unmutes it), with echo cancelling
     * and noise suppression. [done]: whether it is there now.
     */
    fun publishMic(done: (Boolean) -> Unit)

    /** The microphone's track goes (unpublished and closed): the system's microphone indicator goes out. */
    fun unpublishMic(done: () -> Unit)

    /** The published microphone goes out to the room ([on]) or is muted again. [done]: whether it did. */
    fun setMicLive(on: Boolean, done: (Boolean) -> Unit)

    /** The microphone is someone else's now (a phone call, another app's recording): not to be taken. */
    fun micBusy(): Boolean

    /** How loud [identity]'s audio plays here: 0 silenced, 1 as it is. */
    fun setVolume(identity: String, volume: Double)

    /** [identity] has an audio track in the room that isn't muted (they are talking). */
    fun audioLive(identity: String): Boolean

    /** A data message to the room's others, reliable, on [topic]. */
    fun sendData(bytes: NSData, topic: String)
}

/** What happens in the room ([IosTalkRoom.events]). */
interface IosTalkRoomEvents {
    /** A device is in the room (it joined, or was there when this one did). */
    fun joined(identity: String)

    /** [identity] left the room. */
    fun gone(identity: String)

    /** [identity]'s audio track decrypts with its key ([ok]) or stopped to (a missing or wrong key, the track gone). */
    fun audioDecrypts(identity: String, ok: Boolean)

    /** [identity]'s microphone went quiet: muted or unpublished. */
    fun audioStopped(identity: String)

    /** A data message of [identity] on [topic]. */
    fun data(identity: String, topic: String, bytes: NSData)

    /** Someone subscribed this device's microphone track. */
    fun micSubscribed()

    /** The room was lost (the network, the server): Swift has let go of everything of it already. */
    fun lost()
}

/**
 * How the server is known and who this phone is, for a connection Swift makes itself (the pipe LiveKit's signalling
 * goes through to `/rtc`, which needs this phone's certificate as `/ws` does): the same rules as the shared code's own
 * client (IosMtlsHttpClientFactory).
 */
class IosServerCheck internal constructor(
    /** The Keychain alias of this phone's key and certificate. */
    val identityAlias: String,
    private val pin: String?,
    /** The server's own CA certificates (DER): a chain that ends at one, for a server, is the server's. */
    val cas: List<NSData>,
) {
    /** Neither a key nor a CA is kept: the system's roots and the host name decide. */
    val systemDecides: Boolean get() = pin == null && cas.isEmpty()

    /** The server's certificate ([certificateDer]) has the key this phone pinned. */
    fun keyMatches(certificateDer: NSData): Boolean = pin != null && ServerPin.matches(certificateDer.toByteArray(), pin)
}
