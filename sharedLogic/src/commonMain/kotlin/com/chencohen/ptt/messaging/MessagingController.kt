package com.chencohen.ptt.messaging

import com.chencohen.ptt.connection.ObservationHandle
import com.chencohen.ptt.network.describe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** What a contact's page shows of the safety number: its digits in 12 groups of 5 and the code to scan. */
data class SafetyNumberView(val groups: List<String>, val code: String)

/** A Link a phone code to show: the QR code's text, and when it expires (milliseconds since 1970). */
data class ShownLinkCode(val code: String, val expiresAt: Long)

/** What became of an action on a contact; the screens post it as a message. */
sealed interface ContactResult {
    data object Done : ContactResult
    data class Failed(val message: String) : ContactResult
}

/**
 * Keys, /ws, contacts and pair requests for the screens (docs/screens/contacts.md): [start] when the app comes to the
 * front (uploads the keys if needed, opens /ws), [stop] when it goes to the back. The pairing itself is
 * [PairingController]'s.
 */
class MessagingController internal constructor(
    private val messaging: Messaging,
    /** Messaging's own: one thing at a time (the crypto engine and the store aren't thread-safe). */
    private val work: CoroutineDispatcher,
    /** Where the callbacks run (the UI's thread). */
    main: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    val state: StateFlow<MessagingState> = messaging.state

    /** Same as `state.value`, typed for Swift. */
    val currentState: MessagingState get() = state.value

    private var startJob: Job? = null

    fun start() {
        if (startJob?.isActive == true) return
        startJob = scope.launch { withContext(work) { messaging.start() } }
    }

    fun stop() {
        startJob?.cancel()
        scope.launch { withContext(work) { messaging.stop() } }
    }

    fun refreshContacts() {
        scope.launch { withContext(work) { messaging.refreshContacts() } }
    }

    fun accept(user: String, done: (ContactResult) -> Unit) = act(done) { messaging.accept(user) }

    fun decline(user: String) {
        scope.launch { withContext(work) { messaging.decline(user) } }
    }

    fun unpair(user: String, done: (ContactResult) -> Unit) = act(done) { messaging.unpair(user) }

    fun setBlocked(user: String, blocked: Boolean, done: (ContactResult) -> Unit) = act(done) { messaging.setBlocked(user, blocked) }

    fun acceptKeyChange(user: String) {
        scope.launch { withContext(work) { messaging.acceptKeyChange(user) } }
    }

    fun setVerified(user: String, verified: Boolean) {
        scope.launch { withContext(work) { messaging.setVerified(user, verified) } }
    }

    /** The safety number with [user], made on the work thread; null when there is no such contact. */
    fun safetyNumber(user: String, done: (SafetyNumberView?) -> Unit) {
        scope.launch {
            val number = withContext(work) { runCatching { messaging.safetyNumber(user) }.getOrNull() }
            done(number?.let { SafetyNumberView(it.digits.chunked(5), SafetyCode.format(it.scannable)) })
        }
    }

    /** What the camera read on their safety number screen: true when it is the same safety number (now verified). */
    fun verifyScanned(user: String, text: String, done: (Boolean) -> Unit) {
        scope.launch {
            val scanned = SafetyCode.parse(text)
            done(scanned != null && withContext(work) { messaging.verifyScanned(user, scanned) })
        }
    }

    /** Sends [body] to [user] end to end; offline, it waits in the outbox. [done] says Failed only for an error before it was kept. */
    fun sendText(user: String, body: String, done: (ContactResult) -> Unit) = act(done) { messaging.sendText(user, body) }

    /** Tries a text that failed again. */
    fun retry(id: String) {
        scope.launch { withContext(work) { messaging.retry(id) } }
    }

    /** The conversation with [user] is on screen ([user] null: none is): its texts are read as they come. */
    fun openChat(user: String?) {
        scope.launch {
            withContext(work) {
                messaging.openChat = user
                if (user != null) messaging.markRead(user)
            }
        }
    }

    /** Typing to [user] started or stopped (the screen throttles it). */
    fun typing(user: String, typing: Boolean) {
        scope.launch { withContext(work) { messaging.sendTyping(user, typing) } }
    }

    fun refreshPresence() {
        scope.launch { withContext(work) { messaging.refreshPresence() } }
    }

    /** Whether this user shows contacts when they're online (Settings). */
    val presenceVisible: Boolean get() = messaging.presenceVisible

    /** Shows or hides this user's presence; [done] gets the setting now, or null when the server couldn't be told. */
    fun setPresenceVisible(visible: Boolean, done: (Boolean?) -> Unit) {
        scope.launch {
            val shown = withContext(work) { runCatching { messaging.setPresenceVisible(visible) }.getOrNull() }
            done(shown)
        }
    }

    /**
     * A new Link a phone code (docs/screens/link-phone.md); null with the error's message when it couldn't be made.
     * [MessagingState.linkedDevice] says when a phone was linked with it.
     */
    fun makeLinkCode(done: (ShownLinkCode?, String?) -> Unit) {
        scope.launch {
            val result = try {
                val (code, expires) = withContext(work) { messaging.newLinkCode() }
                ShownLinkCode(code.format(), expires) to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to e.describe()
            }
            done(result.first, result.second)
        }
    }

    /** A Link a phone code scanned on this phone, enrolled already (KeysState.OtherIdentity): false when it can't use it. */
    fun linkWithScanned(text: String, done: (Boolean) -> Unit) {
        scope.launch { done(withContext(work) { messaging.linkWithScanned(text) }) }
    }

    /** Starts over with a new identity key, when every other phone of the user is revoked (KeysState.OtherIdentity). */
    fun replaceIdentityKey(done: (ContactResult) -> Unit) = act(done) { messaging.replaceIdentityKey() }

    // --- Groups (docs/screens/groups.md) ------------------------------------------------------------------------

    /**
     * A new group named [name] with [picture] (a small JPEG, or null), owned by this user, with [members] (paired
     * contacts) invited. [done] gets the group's chat key ([Group.chat]), or the error's message.
     */
    fun createGroup(name: String, picture: ByteArray?, members: List<String>, done: (String?, String?) -> Unit) {
        scope.launch {
            val result = try {
                val chat = withContext(work) {
                    val group = messaging.groups.create(name, picture)
                    if (members.isNotEmpty()) messaging.groups.invite(group.id, members)
                    group.chat
                }
                chat to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to e.describe()
            }
            done(result.first, result.second)
        }
    }

    fun inviteToGroup(id: String, users: List<String>, done: (ContactResult) -> Unit) = act(done) { messaging.groups.invite(id, users) }

    fun removeFromGroup(id: String, user: String, done: (ContactResult) -> Unit) = act(done) { messaging.groups.remove(id, user) }

    /** Renames the group; [picture] null keeps the picture, an empty one takes it away. */
    fun renameGroup(id: String, name: String, picture: ByteArray?, done: (ContactResult) -> Unit) = act(done) { messaging.groups.rename(id, name, picture) }

    fun deleteGroup(id: String, done: (ContactResult) -> Unit) = act(done) { messaging.groups.delete(id) }

    fun acceptGroup(id: String, done: (ContactResult) -> Unit) = act(done) { messaging.groups.accept(id) }

    /** Declines an invitation, or leaves the group. */
    fun leaveGroup(id: String, done: (ContactResult) -> Unit) = act(done) { messaging.groups.leave(id) }

    fun refreshGroups() {
        scope.launch { withContext(work) { messaging.groups.refresh() } }
    }

    private fun act(done: (ContactResult) -> Unit, block: suspend () -> Unit) {
        scope.launch {
            val result = try {
                withContext(work) { block() }
                ContactResult.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ContactResult.Failed(e.describe())
            }
            done(result)
        }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (MessagingState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    /** The screens are gone; the hub stays for the push service, with its socket closed. */
    fun close() {
        scope.cancel()
        messaging.stop()
    }
}
