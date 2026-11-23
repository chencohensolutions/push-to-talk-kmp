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

    /** This phone's Talk screen or reply bubble for [chat] is open (null: closed); the other user's phone is told. */
    fun setHere(chat: String?) = onWork { messaging.setHere(chat) }

    /** Their email as this user knows it (blank: none), for Android Contacts. */
    fun setContactEmail(user: String, email: String) = onWork { messaging.setEmail(user, email) }

    val myEmail: String? get() = messaging.myEmail

    /** This user's own email, shared with their paired contacts (blank: none). */
    fun setMyEmail(email: String) = onWork { messaging.setMyEmail(email) }

    /** How the app refers to this user (Gender), shared with contacts. */
    val myGender: Int get() = messaging.myGender

    fun setMyGender(gender: Int) = onWork { messaging.setMyGender(gender) }

    /** Rename: this user's own name for [user] (blank: back to theirs). */
    fun renameContact(user: String, name: String) {
        scope.launch { withContext(work) { messaging.rename(user, name) } }
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
                // Its talk room is joined while it is on screen (phase 5b).
                if (LIVE_TALK_AUDIO) messaging.live.show(user)
                if (user != null) messaging.markRead(user)
            }
        }
    }

    /** Live typing: the line to [user] is [text] now (sent only when the setting is on; throttled there). */
    fun draft(user: String, text: String) = onWork { messaging.draft(user, text) }

    /** The line to [user] was sent: no more drafts for it. */
    fun draftDone(user: String) = onWork { messaging.draftDone(user) }

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

    /** This user's phones (Settings › Your devices); null with the error's message when they couldn't be read. */
    fun loadDevices(done: (List<OwnDevice>?, String?) -> Unit) {
        scope.launch {
            val result = try {
                withContext(work) { messaging.myDevices() } to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to e.describe()
            }
            done(result.first, result.second)
        }
    }

    /** Revokes another phone of this user; [done] gets null when it is, else why not. */
    fun revokeDevice(deviceNumber: Int, done: (String?) -> Unit) {
        scope.launch {
            val error = try {
                if (withContext(work) { messaging.revokeDevice(deviceNumber) }) null else "This phone can't revoke itself"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.describe()
            }
            done(error)
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

    // --- Talk (docs/design.md, "Phase 5a") -----------------------------------------------------------------------

    private fun onWork(block: suspend () -> Unit) {
        scope.launch { withContext(work) { block() } }
    }

    /** Calls [chat] (a user, or a group's chat); [video] with the camera on (phase 6). */
    fun startCall(chat: String, video: Boolean) = onWork { messaging.calls.start(chat, video) }

    fun acceptCall() = onWork { messaging.calls.accept() }

    fun declineCall() = onWork { messaging.calls.decline() }

    fun hangUp() = onWork { messaging.calls.hangUp() }

    fun setCallMicrophone(on: Boolean) = onWork { messaging.calls.setMicrophone(on) }

    fun setCallCamera(on: Boolean) = onWork { messaging.calls.setCamera(on) }

    /** The call screen shows [user]'s video cropped ([fill]) or whole, in a tile [aspect] wide for 1 high. */
    fun setCallView(user: String, fill: Boolean, aspect: Float) = onWork { messaging.calls.setView(user, fill, aspect) }

    /** A kept call's tracks (callRecording); only the server is asked, so it runs off Messaging's own thread. */
    suspend fun keptCall(message: TextMessage): KeptCall = messaging.keptCall(message)

    /** Reads a kept call's track from the server. */
    suspend fun readBlob(id: String, onBytes: suspend (ByteArray) -> Unit) = messaging.readBlob(id, onBytes)

    fun switchCallCamera() = onWork { messaging.calls.switchCamera() }

    /** Saving this call starts or stops (Save calls, or the call screen's Save). */
    fun setCallSaving(on: Boolean) = onWork { messaging.calls.setRecording(on) }

    /** "Save this call?" answered Not now. */
    fun dismissSaveAsk() = onWork { messaging.calls.dismissAsk() }

    /** "Call ended" goes now (Back, or the app opening on it). */
    fun dismissEndedCall() = onWork { messaging.calls.dismissEnded() }

    /** The Talk button went down in [chat] (a user, or a group's chat). */
    fun pressTalk(chat: String) = onWork { messaging.talk.press(chat) }

    /** A Talk screen shown ([on]) or left: the microphone open ahead of a press, or closed. */
    fun warmMicrophone(on: Boolean) = onWork { messaging.talk.warmMicrophone(on) }

    /**
     * The Talk screen of [chat] shows (null: it left): with push-to-video on, the camera opens ahead of a press (in
     * that chat's format), else it closes.
     */
    fun warmCamera(chat: String?) = onWork { messaging.warmCamera(chat) }

    /** Push-to-video's codec for [chat] (H.265 only when chosen and every phone there plays it). */
    fun videoH265For(chat: String?): Boolean = messaging.videoH265For(chat)

    /** The Talk button, or the composer's microphone, came up; [send] false cancels a voice message. */
    fun releaseTalk(send: Boolean) = onWork { messaging.talk.release(send) }

    /** The composer's microphone went down: a voice message to [chat]. */
    fun startVoice(chat: String) = onWork { messaging.talk.startVoice(chat) }

    /** Plays a burst or voice message from the chat. */
    fun playClip(id: String) = onWork { messaging.talk.play(id) }

    fun stopPlaying() = onWork { messaging.talk.stopPlaying() }

    /** The Talk settings and each chat's When I'm away, as kept on this phone. */
    val talkSettings: TalkSettings get() = messaging.talkSettings()

    fun setTalkSettings(settings: TalkSettings) = onWork { messaging.setTalkSettings(settings) }

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
