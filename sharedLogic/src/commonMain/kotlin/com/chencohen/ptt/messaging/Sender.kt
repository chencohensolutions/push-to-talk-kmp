package com.chencohen.ptt.messaging

import com.chencohen.ptt.crypto.CryptoEngine
import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.crypto.UntrustedIdentityException
import com.chencohen.ptt.relay.Envelope
import com.chencohen.ptt.relay.Send
import com.chencohen.ptt.relay.SendResult
import com.chencohen.ptt.relay.SendStatus
import com.chencohen.ptt.relay.UserDevices
import com.chencohen.ptt.settings.EnrolledDevice

/** The server refused a Send: [status] is one of `SendStatus`. */
class SendRefusedException(val status: Int, detail: String) : Exception(detail.ifEmpty { "Refused ($status)" })

/** The user has no device with keys: nothing can reach them yet. */
class NoDevicesException(val user: String) : Exception("$user has no phone with keys yet")

/**
 * Sends one message to a user (docs/messaging.md on the server, "The rules of a Send"): encrypted once for each of the
 * recipient's devices and each of this user's other devices, in one Send. When the server says the devices changed
 * (STALE_DEVICES), sessions with devices that are gone (or whose registration id changed) are dropped, the new
 * devices' prekey bundles fetched, and it is sent again, up to [attempts] times. Bundles are only used when their
 * identity key is the one trusted for the user (from the QR code), or this phone's own for its user.
 */
internal class Sender(
    private val engine: CryptoEngine,
    private val api: MessagingApi,
    private val send: suspend (Send) -> SendResult,
    private val me: () -> EnrolledDevice?,
    private val attempts: Int = 3,
) {
    /** Sends [content] to [to]; [routingToken]: their pairing token, for a pair request. Answers the envelope ids. */
    suspend fun send(to: String, content: Content, routingToken: String? = null): List<String> {
        val self = me() ?: throw IllegalStateException("This phone isn't registered")
        if (engine.sessionDevices(to).isEmpty()) openSessions(self, to, routingToken, only = null)
        repeat(attempts) {
            val envelopes = envelopes(self, to, content, routingToken)
            val result = send(Send(envelopes = envelopes))
            when (result.status) {
                SendStatus.ACCEPTED -> return result.envelopeIds
                SendStatus.STALE_DEVICES -> reconcile(self, to, result.devices, routingToken)
                else -> throw SendRefusedException(result.status, result.detail)
            }
        }
        throw SendRefusedException(SendStatus.STALE_DEVICES, "The devices kept changing")
    }

    private fun envelopes(self: EnrolledDevice, to: String, content: Content, routingToken: String?): List<Envelope> {
        val theirs = engine.sessionDevices(to).sorted().map { DeviceAddress(to, it) }
        if (theirs.isEmpty()) throw NoDevicesException(to)
        val mine = if (to == self.user) emptyList() else engine.sessionDevices(self.user).filter { it != self.deviceNumber }.sorted().map { DeviceAddress(self.user, it) }
        val plain = ContentCodec.encode(content)
        val copy = if (mine.isEmpty()) plain else ContentCodec.encode(content.copy(destination = to))
        return (theirs + mine).map { address ->
            val ciphertext = engine.encrypt(address, if (address.user == to) plain else copy)
            Envelope(
                recipientUser = address.user,
                recipientDevice = address.device,
                type = ciphertext.type,
                ciphertext = ciphertext.bytes,
                routingToken = if (address.user == to) routingToken.orEmpty() else "",
                recipientRegistrationId = engine.remoteRegistrationId(address) ?: 0,
            )
        }
    }

    /** Brings the sessions in line with the active devices the server listed. */
    private suspend fun reconcile(self: EnrolledDevice, to: String, devices: List<UserDevices>, routingToken: String?) {
        for (listed in devices) {
            val active = listed.devices.associate { it.deviceNumber to it.registrationId }.toMutableMap()
            if (listed.user == self.user) active.remove(self.deviceNumber)
            for (device in engine.sessionDevices(listed.user)) {
                val address = DeviceAddress(listed.user, device)
                val registration = active[device]
                if (registration == null || (registration != 0 && engine.remoteRegistrationId(address) != registration)) {
                    engine.deleteSession(address)
                }
            }
            val missing = active.keys - engine.sessionDevices(listed.user).toSet()
            if (missing.isNotEmpty()) openSessions(self, listed.user, routingToken.takeIf { listed.user == to }, missing)
        }
    }

    private suspend fun openSessions(self: EnrolledDevice, user: String, routingToken: String?, only: Set<Int>?) {
        val bundles = api.prekeyBundles(user, routingToken)
        val trusted = if (user == self.user) engine.identityKey() else engine.trustedIdentity(user)
        if (trusted == null || !trusted.contentEquals(bundles.identityKey)) throw UntrustedIdentityException(user)
        for (bundle in bundles.devices) {
            if (only != null && bundle.device !in only) continue
            if (user == self.user && bundle.device == self.deviceNumber) continue
            engine.startSession(DeviceAddress(user, bundle.device), bundles.identityKey, bundle)
        }
    }
}
