package com.chencohen.ptt.calls

import com.chencohen.ptt.messaging.CallContent
import com.chencohen.ptt.messaging.CallEntry
import com.chencohen.ptt.messaging.CallOutcome
import com.chencohen.ptt.messaging.Content
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.messaging.LiveToken
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.relay.PushKind
import com.chencohen.ptt.talk.LiveTalk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Settings › Calls › Save calls: the plan's Off, Ask each time, Always. */
enum class SaveCalls { OFF, ASK, ALWAYS }

/** Where the call on this phone stands. */
enum class CallPhase {
    /** This phone called: ringing on the other side. */
    CALLING,

    /** Someone calls this phone: it rings. */
    RINGING,

    /** Answered here: joining the call's room. */
    CONNECTING,

    /** Someone else is in the call with this phone. */
    ACTIVE,

    /** Over: shown a moment, then gone. */
    ENDED,
}

/** The codec a phone sends its camera in (Settings › Calls › Video). */
enum class CallVideo { VP8, H265 }

/** The call on this phone, for the call screen and the platform's call session. */
data class CallUi(
    val id: String,
    /** The conversation: a user, or a group's chat. */
    val chat: String,
    /** Who called. */
    val caller: String,
    val incoming: Boolean,
    val video: Boolean,
    val phase: CallPhase,
    /** When it started (the invite), and when someone first joined (0 before). */
    val startedAt: Long,
    val connectedAt: Long = 0,
    /** Others in the call now, by user. */
    val others: List<String> = emptyList(),
    /** Users saving the call (the Recording badge); this phone's own user too while it saves. */
    val recordingBy: Set<String> = emptySet(),
    /** The user whose device has the server keeping the call now (the room's metadata); null: not kept. */
    val serverRecordingBy: String? = null,
    /** The server can keep calls (serverInfo.keepsCalls): Record shows even with Save calls off. */
    val serverKeeps: Boolean = false,
    val microphone: Boolean = true,
    val camera: Boolean = video,
    /** Set once [phase] is ENDED. */
    val outcome: CallOutcome? = null,
    /** Save calls is Ask each time: "Save this call?" shows. */
    val askToSave: Boolean = false,
    /** This phone saves the call now. */
    val saving: Boolean = false,
    /** How each other user shows this phone's video (VIEW): their crop is outlined on this phone's own preview. */
    val viewers: Map<String, CallView> = emptyMap(),
    /** This phone's camera picture upright, width for 1 high (0 before the first frame). */
    val ownAspect: Float = 0f,
)

/** The call room's carrier: LiveKit on Android, the call's media encrypted with its one key. */
interface CallTransport {
    /** Joins the call's room; [video] turns the camera on from the start. False when it can't. */
    suspend fun join(url: String, token: String, key: ByteArray, video: Boolean): Boolean

    fun leave()

    suspend fun setMicrophone(on: Boolean)

    suspend fun setCamera(on: Boolean)

    /** Front or back camera. */
    fun switchCamera()

    /** Starts saving what this phone shows and plays (Save calls); false when it can't. */
    fun startRecording(): Boolean

    /** Stops saving: the saved file's name, or null when nothing was saved. */
    fun stopRecording(): String?

    var listener: CallTransportListener?
}

interface CallTransportListener {
    /** Who is in the room besides this phone, by "user.device", whenever it changes (also right after joining). */
    fun others(identities: List<String>)

    /** The room was lost. */
    fun disconnected()

    /** The room's metadata: the device ("user.device") the server keeps the call for, or null when it doesn't. */
    fun serverRecording(by: String?) {}

    /** This phone's camera frames: [rotation] clockwise degrees to turn them upright, [width] × [height] as captured. */
    fun cameraFormat(rotation: Int, width: Int, height: Int) {}
}

/** The platform's part: ringing, the ongoing call session, missed-call notifications. */
interface CallHost {
    /** [call] rings here (Telecom's incoming call and its full-screen notification). */
    fun ring(call: CallUi)

    /** This phone's call is placed or answered: the ongoing call session. */
    fun ongoing(call: CallUi)

    /** The call is over here (stops ringing, ends the session). */
    fun ended(call: CallUi)

    /** A call wasn't answered on this phone. */
    fun missed(chat: String, caller: String, video: Boolean)
}

/**
 * Calls (docs/design.md, "Phase 6"; the server's docs/messaging.md, "Calls"). The signalling is end to end
 * ([CallContent] in an ordinary message, PUSH_CALL for the invite); the server only adds the room (`callToken`). The
 * caller's phone makes the call's id and its media key, sends the invite and waits in the room. A phone that answers
 * joins the room and sends ACCEPT to the conversation, whose copy makes the user's other phones stop ringing. A
 * one-to-one call ends when either side hangs up or leaves; a group call ends for a phone when it hangs up or is the
 * last in the room. Each call is kept as an entry in its chat ([TextMessage.call]), which the Calls tab lists.
 */
internal class Calls(
    private val scope: CoroutineScope,
    private val me: () -> String?,
    private val tokens: suspend (call: String, peer: String?, group: String?) -> LiveToken,
    private val serverUrl: () -> String,
    private val sendContent: suspend (chat: String, content: Content, pushKind: Int) -> Unit,
    private val putEntry: (TextMessage) -> Unit,
    private val entry: (id: String) -> TextMessage?,
    /** A call takes the audio: the talk room is left while it runs. */
    private val onCall: () -> Unit,
    private val saveCalls: () -> SaveCalls = { SaveCalls.OFF },
    /**
     * The server keeps the call, encrypted end to end, from this user's Record ([on]: recordCall) until anyone's Stop
     * (stopRecordingCall), or until the recording device leaves (2026-11-22, hen: only when someone records).
     * Failures don't stop the phone's own saving.
     */
    private val keepOnServer: suspend (call: String, peer: String?, group: String?, on: Boolean) -> Unit = { _, _, _, _ -> },
    private val serverKeepsCalls: () -> Boolean = { false },
    /** logCallEvent: an event (sealed with the call's key, base64) into the kept call. */
    private val logEvent: suspend (call: String, peer: String?, group: String?, event: String) -> Unit = { _, _, _, _ -> },
    private val now: () -> Long,
    private val random: Random,
) {
    var transport: CallTransport? = null
        set(value) {
            field = value
            value?.listener = events
        }
    var host: CallHost? = null

    private val _state = MutableStateFlow<CallUi?>(null)
    val state: StateFlow<CallUi?> = _state.asStateFlow()

    private var key: ByteArray? = null
    private var timer: Job? = null
    private var clearing: Job? = null
    private var joined = false
    private var savedAs: String? = null
    private var askedToSave = false

    /** The call's key while the server kept the call at some point: it goes on the call's entry, for playing it later. */
    private var keptKey: ByteArray? = null

    /** This phone's own recorder runs (Save calls, with a server that doesn't keep calls). */
    private var savingHere = false

    /** What the kept call's event log has from this phone: the camera's format and how each video shows here. */
    private var camera: CallEvent? = null
    private val views = mutableMapOf<String, CallEvent>()
    private var serverKeeping = false

    private val current: CallUi? get() = _state.value?.takeIf { it.phase != CallPhase.ENDED }

    /** Calls [chat]: false when a call is already on. */
    fun start(chat: String, video: Boolean): Boolean {
        if (current != null) return false
        val self = me() ?: return false
        val id = random.nextBytes(16).toHex()
        val callKey = random.nextBytes(KEY_BYTES)
        key = callKey
        joined = false
        askedToSave = false
        keptKey = null
        camera = null
        views.clear()
        serverKeeping = false
        savedAs = null
        val call = CallUi(id, chat, self, incoming = false, video = video, phase = CallPhase.CALLING, startedAt = now())
        show(call)
        onCall()
        host?.ongoing(call)
        scope.launch {
            send(chat, CallContent(CallContent.INVITE, id, callKey, video, now()), PushKind.CALL)
            if (!join(call)) end(CallOutcome.FAILED, tell = true)
        }
        timer = scope.launch {
            delay(RING_FOR)
            if (current?.id == id && current?.phase == CallPhase.CALLING) end(CallOutcome.NO_ANSWER, tell = true)
        }
        return true
    }

    /** Answers the call that rings. */
    fun accept() {
        val call = current?.takeIf { it.phase == CallPhase.RINGING } ?: return
        timer?.cancel()
        val connecting = call.copy(phase = CallPhase.CONNECTING)
        show(connecting)
        onCall()
        host?.ongoing(connecting)
        scope.launch {
            // ACCEPT's copy stops this user's other phones ringing.
            send(call.chat, CallContent(CallContent.ACCEPT, call.id), PushKind.NONE)
            if (!join(connecting)) end(CallOutcome.FAILED, tell = true)
        }
    }

    fun decline() {
        val call = current?.takeIf { it.phase == CallPhase.RINGING } ?: return
        scope.launch { send(call.chat, CallContent(CallContent.DECLINE, call.id), PushKind.NONE) }
        end(CallOutcome.DECLINED, tell = false)
    }

    fun hangUp() {
        val call = current ?: return
        if (call.phase == CallPhase.RINGING) return decline()
        end(if (call.connectedAt > 0) CallOutcome.ANSWERED else CallOutcome.CANCELLED, tell = true)
    }

    fun setMicrophone(on: Boolean) {
        current ?: return
        _state.update { it?.copy(microphone = on) }
        scope.launch { runCatching { transport?.setMicrophone(on) } }
    }

    fun setCamera(on: Boolean) {
        current ?: return
        _state.update { it?.copy(camera = on) }
        scope.launch { runCatching { transport?.setCamera(on) } }
    }

    fun switchCamera() = transport?.switchCamera()

    /** Saving starts or stops on this phone (Settings › Calls › Save calls): the others see the Recording badge. */
    fun setRecording(on: Boolean) {
        val call = current?.takeIf { it.phase == CallPhase.ACTIVE } ?: return
        val self = me() ?: return
        if (!on && !call.saving && call.serverRecordingBy != null) {
            // Someone else has the server keeping it: anyone in the call may stop that.
            scope.launch { keepOnServer(call, false) }
            return
        }
        if (on == call.saving) return
        if (on) {
            // With a server that keeps calls, Save is only the server's: this phone doesn't record or re-encode anything
            // (2026-11-22, hen: the on-phone recorder froze this phone's own video).
            savingHere = !serverKeepsCalls()
            if (savingHere && transport?.startRecording() != true) return
        } else {
            if (savingHere) transport?.stopRecording()?.let { savedAs = it }
            savingHere = false
        }
        _state.update { it?.copy(saving = on, askToSave = false, recordingBy = if (on) it.recordingBy + self else it.recordingBy - self) }
        scope.launch { send(call.chat, CallContent(CallContent.RECORDING, call.id, recording = on), PushKind.NONE) }
        scope.launch { keepOnServer(call, on) }
    }

    private suspend fun keepOnServer(call: CallUi, on: Boolean) {
        val group = com.chencohen.ptt.messaging.Group.idOf(call.chat)
        runCatching { keepOnServer(call.id, if (group == null) call.chat else null, group, on) }
    }

    /**
     * This phone shows [user]'s video cropped to fill ([fill]) or whole, in a tile [aspect] wide for 1 high: they are
     * told (their preview outlines it), and it goes into the call's log while the server keeps it.
     */
    fun setView(user: String, fill: Boolean, aspect: Float) {
        val call = current?.takeIf { it.phase == CallPhase.ACTIVE } ?: return
        if (aspect <= 0f || aspect.isNaN()) return
        val event = CallEvent(CallEvent.VIEW, of = user, fill = fill, aspect = (aspect * 1000).toInt() / 1000f)
        if (views[user] == event) return
        views[user] = event
        scope.launch { send(call.chat, CallContent(CallContent.VIEW, call.id, fill = fill, aspect = event.aspect!!, of = user), PushKind.NONE) }
        if (serverKeeping) log(call, event)
    }

    private fun log(call: CallUi, event: CallEvent) {
        val k = key ?: return
        val sealed = kotlin.io.encoding.Base64.encode(CallEvent.seal(k, event, random))
        val group = com.chencohen.ptt.messaging.Group.idOf(call.chat)
        scope.launch { runCatching { logEvent(call.id, if (group == null) call.chat else null, group, sealed) } }
    }

    /** "Save this call?" answered Not now. */
    fun dismissAsk() = _state.update { it?.copy(askToSave = false) }

    /**
     * Call signalling in [chat] from [from] (a user; [ownCopy]: another phone of this user), at the server's [at].
     */
    fun receive(chat: String, from: String, call: CallContent, at: Long, ownCopy: Boolean) {
        val here = current?.takeIf { it.id == call.id }
        when (call.kind) {
            CallContent.INVITE -> {
                if (ownCopy || call.key.size != KEY_BYTES || call.id.length != 32) return
                if (entry(call.id) != null) return
                val ringing = CallUi(call.id, chat, from, incoming = true, video = call.video, phase = CallPhase.RINGING, startedAt = at)
                when {
                    now() - at > RING_IF_NEWER -> missed(ringing)
                    current != null -> {
                        scope.launch { send(chat, CallContent(CallContent.BUSY, call.id), PushKind.NONE) }
                        missed(ringing)
                    }
                    else -> {
                        key = call.key
                        joined = false
                        askedToSave = false
                        keptKey = null
                        camera = null
                        views.clear()
                        serverKeeping = false
                        savedAs = null
                        show(ringing)
                        host?.ring(ringing)
                        timer = scope.launch {
                            delay(RING_FOR)
                            if (current?.id == call.id && current?.phase == CallPhase.RINGING) end(CallOutcome.MISSED, tell = false)
                        }
                    }
                }
            }
            CallContent.ACCEPT, CallContent.DECLINE, CallContent.ANSWERED_ELSEWHERE -> {
                here ?: return
                if (ownCopy && here.phase == CallPhase.RINGING) {
                    end(CallOutcome.ELSEWHERE, tell = false)
                } else if (!ownCopy && call.kind == CallContent.DECLINE && here.phase == CallPhase.CALLING && Group.idOf(chat) == null) {
                    end(CallOutcome.DECLINED, tell = false)
                } else if (!ownCopy && call.kind == CallContent.ACCEPT) {
                    timer?.cancel()
                }
            }
            CallContent.BUSY -> {
                if (here?.phase == CallPhase.CALLING && Group.idOf(chat) == null) end(CallOutcome.BUSY, tell = false)
            }
            CallContent.HANG_UP -> {
                here ?: return
                when {
                    ownCopy -> {}
                    here.phase == CallPhase.RINGING && from == here.caller -> end(CallOutcome.MISSED, tell = false)
                    Group.idOf(chat) == null -> end(if (here.connectedAt > 0) CallOutcome.ANSWERED else CallOutcome.CANCELLED, tell = false)
                }
            }
            CallContent.RECORDING -> {
                here ?: return
                _state.update { it?.copy(recordingBy = if (call.recording) it.recordingBy + from else it.recordingBy - from) }
            }
            CallContent.VIEW -> {
                here ?: return
                if (ownCopy || call.of != me()) return
                _state.update { it?.copy(viewers = it.viewers + (from to CallView(call.fill, call.aspect))) }
            }
        }
    }

    private suspend fun join(call: CallUi): Boolean {
        val t = transport ?: return false
        val callKey = key ?: return false
        return try {
            val group = Group.idOf(call.chat)
            val token = tokens(call.id, if (group == null) call.chat else null, group)
            if (current?.id != call.id) return true
            t.join(LiveTalk.origin(serverUrl()), token.token, callKey, call.video).also { joined = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun send(chat: String, content: CallContent, pushKind: Int) {
        try {
            sendContent(chat, Content(call = content), pushKind)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline: the other side's ringing times out by itself.
        }
    }

    private fun show(call: CallUi) {
        clearing?.cancel()
        _state.value = call
    }

    private fun missed(call: CallUi) {
        record(call, CallOutcome.MISSED)
        host?.missed(call.chat, call.caller, call.video)
    }

    private fun end(outcome: CallOutcome, tell: Boolean) {
        val call = current ?: return
        timer?.cancel()
        if (tell) scope.launch { send(call.chat, CallContent(CallContent.HANG_UP, call.id), PushKind.NONE) }
        if (joined) runCatching { transport?.leave() }
        joined = false
        key = null
        if (call.saving && savingHere) transport?.stopRecording()?.let { savedAs = it }
        savingHere = false
        val ended = call.copy(phase = CallPhase.ENDED, outcome = outcome, saving = false, askToSave = false)
        _state.value = ended
        host?.ended(ended)
        record(call, outcome, savedAs, keptKey)
        savedAs = null
        keptKey = null
        if (outcome == CallOutcome.MISSED) host?.missed(call.chat, call.caller, call.video)
        clearing = scope.launch {
            delay(SHOW_ENDED)
            if (_state.value === ended) _state.value = null
        }
    }

    private fun record(call: CallUi, outcome: CallOutcome, saved: String? = null, kept: ByteArray? = null) {
        val duration = if (call.connectedAt > 0) now() - call.connectedAt else 0
        val missed = outcome == CallOutcome.MISSED
        putEntry(
            TextMessage(
                id = call.id, user = call.chat, outgoing = !call.incoming, body = "", at = call.startedAt,
                read = !missed, author = call.caller.takeIf { Group.idOf(call.chat) != null },
                call = CallEntry(call.video, outcome, duration, savedAs = saved, keptKey = kept?.let { kotlin.io.encoding.Base64.encode(it) }),
            ),
        )
    }

    private val events = object : CallTransportListener {
        override fun others(identities: List<String>) {
            scope.launch {
                val call = current ?: return@launch
                val users = identities.map { it.substringBeforeLast('.') }.filter { it != me() }.distinct()
                if (users.isNotEmpty()) {
                    timer?.cancel()
                    val first = call.phase != CallPhase.ACTIVE && !askedToSave
                    val mode = saveCalls()
                    _state.value = call.copy(
                        phase = CallPhase.ACTIVE, others = users, serverKeeps = serverKeepsCalls(),
                        connectedAt = call.connectedAt.takeIf { it > 0 } ?: now(),
                        askToSave = call.askToSave || (first && mode == SaveCalls.ASK),
                    )
                    if (first) {
                        askedToSave = true
                        if (mode == SaveCalls.ALWAYS) setRecording(true)
                    }
                } else if (call.phase == CallPhase.ACTIVE) {
                    // Everyone else left: over for this phone too.
                    end(CallOutcome.ANSWERED, tell = Group.idOf(call.chat) == null)
                }
            }
        }

        override fun serverRecording(by: String?) {
            scope.launch {
                if (by != null && current != null) keptKey = key
                val call = current
                val starts = by != null && !serverKeeping
                serverKeeping = by != null
                // Keeping began: the log gets how things are now, then every change.
                if (starts && call != null) {
                    camera?.let { log(call, it) }
                    views.values.forEach { log(call, it) }
                }
                _state.update { it?.copy(serverRecordingBy = by?.substringBeforeLast('.')) }
            }
        }

        override fun cameraFormat(rotation: Int, width: Int, height: Int) {
            scope.launch {
                val event = CallEvent(CallEvent.CAMERA, rot = rotation, w = width, h = height)
                if (camera == event) return@launch
                camera = event
                val turned = rotation == 90 || rotation == 270
                if (height > 0 && width > 0) _state.update { it?.copy(ownAspect = if (turned) height.toFloat() / width else width.toFloat() / height) }
                val call = current ?: return@launch
                if (serverKeeping) log(call, event)
            }
        }

        override fun disconnected() {
            scope.launch {
                val call = current ?: return@launch
                joined = false
                end(if (call.connectedAt > 0) CallOutcome.ANSWERED else CallOutcome.FAILED, tell = true)
            }
        }
    }

    companion object {
        const val KEY_BYTES = 32

        /** How long a call rings (both sides) before it is missed or not answered. */
        const val RING_FOR = 45_000L

        /** An invite older than this when it arrives doesn't ring (the server keeps PUSH_CALL 30 s). */
        const val RING_IF_NEWER = 30_000L

        /** How long the call screen shows how it ended. */
        const val SHOW_ENDED = 2_000L

        private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
