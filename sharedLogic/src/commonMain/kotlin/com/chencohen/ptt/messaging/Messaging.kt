package com.chencohen.ptt.messaging

import com.chencohen.ptt.crypto.CryptoEngine
import com.chencohen.ptt.crypto.CryptoException
import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.crypto.SafetyNumber
import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.network.describe
import com.chencohen.ptt.relay.Envelope
import com.chencohen.ptt.relay.EnvelopeType
import com.chencohen.ptt.relay.FrameConnector
import com.chencohen.ptt.relay.PushKind
import com.chencohen.ptt.relay.RelaySocket
import com.chencohen.ptt.relay.SocketState
import com.chencohen.ptt.settings.EnrolledDevice
import com.chencohen.ptt.settings.PttSettings
import com.chencohen.ptt.talk.BlobApi
import com.chencohen.ptt.talk.BlobException
import com.chencohen.ptt.talk.CatchUpMode
import com.chencohen.ptt.talk.ClipFiles
import com.chencohen.ptt.talk.MemoryClipFiles
import com.chencohen.ptt.talk.Talk
import com.chencohen.ptt.talk.TalkPrefs
import com.chencohen.ptt.talk.TalkUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Duration.Companion.days

/** What the messaging screens render: the keys, the connection, the contacts, the pair requests, the last texts. */
data class MessagingState(
    val keys: KeysState = KeysState.Unknown,
    val socket: SocketState = SocketState.Stopped,
    /** Paired contacts and the ones waiting to confirm, by name. */
    val contacts: List<Contact> = emptyList(),
    /** Pair requests waiting for the user, oldest first. */
    val requests: List<PairRequest> = emptyList(),
    /** The last text of each contact, by user (phase 2's test of the transport). */
    val lastMessages: Map<String, TextMessage> = emptyMap(),
    /** The device number of the last phone this one linked (it sent it the identity key); null when none yet. */
    val linkedDevice: Int? = null,
    /** Contacts online now, or when last seen, by user (presence; only contacts who show theirs). */
    val presence: Map<String, UserPresence> = emptyMap(),
    /** Contacts typing to this user now: until when, milliseconds since 1970. */
    val typing: Map<String, Long> = emptyMap(),
    /**
     * Live typing from others (their setting): what each is writing now, by the same keys as [typing]; gone when the
     * message comes, the line is emptied, or after [Messaging.DRAFT_FOR] without a word.
     */
    val drafts: Map<String, Draft> = emptyMap(),
    /**
     * Texts being edited by their author right now (2026-11-24, hen), by message id: the text as it stands ([Draft.text]
     * null when the author keeps it hidden: only that it is being edited). Gone at the edit, its cancel, or after
     * [Draft.FRESH_MS] without a word.
     */
    val liveEdits: Map<String, LiveEdit> = emptyMap(),
    /** Contacts whose Talk screen or reply bubble for this chat is open now (HereContent): until when. */
    val here: Map<String, Long> = emptyMap(),
    /**
     * Contacts (a group's members: "chat/author") who have the chat with this user open and in view now, so what this
     * user sends is read at once (ViewingContent; 2026-11-24, hen: the read circle): until when.
     */
    val viewing: Map<String, Long> = emptyMap(),
    /** Contacts (a group's members: "chat/author") live and waiting for this user's floor in the chat now: until when. */
    val liveWaiting: Map<String, Long> = emptyMap(),
    /** A call in a chat's talk room (both live became one; 2026-11-25, hen): which chat, since when, with video. */
    val roomCall: RoomCall? = null,
    /** How many texts from each contact aren't read yet. */
    val unread: Map<String, Int> = emptyMap(),
    /** Each conversation's texts, oldest first, by user (a group's by [Group.chat]). */
    val conversations: Map<String, List<TextMessage>> = emptyMap(),
    /** The groups this user is in, invited to, or was in (gone), oldest first. */
    val groups: List<Group> = emptyList(),
    /** Push-to-talk: who talks where, this phone's own talking, what plays. */
    val talk: TalkUiState = TalkUiState(),
    val talkSettings: TalkSettings = TalkSettings(),
    /** The live path: the room this phone is in, the last press-to-hear. */
    val live: com.chencohen.ptt.talk.LiveState = com.chencohen.ptt.talk.LiveState(),
    /** The call on this phone (phase 6); null when none. */
    val call: com.chencohen.ptt.calls.CallUi? = null,
    /**
     * Who has a picture (2026-11-24, hen), with its id, by user; this user's own under "". The picture itself:
     * [Messaging.picture].
     */
    val pictures: Map<String, String> = emptyMap(),
)

/** Settings › Talk, and each chat's When I'm away. */
data class TalkSettings(
    val catchUp: CatchUpMode = CatchUpMode.SHORTEN_PAUSES_AND_SPEED_UP,
    val stayConnected: Boolean = false,
    /** The conversation hardware buttons talk in; null: none. */
    val defaultChat: String? = null,
    val hardwareButtons: Boolean = false,
    /** Chats set to Notify only. */
    val notifyOnly: Set<String> = emptySet(),
    /** Kept calls already downloaded from the server (by id): their Download button is gone. */
    val downloadedCalls: Set<String> = emptySet(),
    /** Settings › Calls › Save calls (phase 6). */
    val saveCalls: com.chencohen.ptt.calls.SaveCalls = com.chencohen.ptt.calls.SaveCalls.OFF,
    /** Settings › Calls › Save calls › Download saved calls: a call this phone saved is fetched to the phone on its end. */
    val autoDownloadCalls: Boolean = false,
    /** Download saved calls only over Wi-Fi (default on). */
    val autoDownloadWifiOnly: Boolean = true,
    /** Download saved calls only while charging (default off). */
    val autoDownloadChargingOnly: Boolean = false,
    /** The chats marked Quick talk, in the order marked: the Talk tab's pages (QuickTalk). */
    val quickTalk: List<String> = emptyList(),
    /** The Talk tab's page shown last; null: the first. */
    val quickTalkPage: String? = null,
    /** Settings › Talk › Open Talk when someone talks to me: the screen turns on with that Talk screen (off by default). */
    val openOnTalk: Boolean = false,
    /**
     * Settings › Talk › Reply bubble over other apps (off by default): while the phone is in use, a burst shows a bar at
     * the bottom of the screen with the sender and a Talk button, for the burst and the reply window after it.
     */
    val replyBubble: Boolean = true,
    /** Settings › Talk › Get camera ready while talking (on by default; 2026-11-24, hen). */
    val cameraReady: Boolean = true,
    /**
     * Settings › Calls › Video: VP8 (standard) or H.265 (smaller and sharper; 2026-11-22, hen), offered only on a phone
     * with a hardware H.265 encoder. Phones that can't take H.265 get VP8 from the same sender (a backup codec).
     */
    val callVideo: com.chencohen.ptt.calls.CallVideo = com.chencohen.ptt.calls.CallVideo.H265,
    /** Push-to-video (2026-11-22, hen; off by default): a press on a Talk screen sends the camera too. */
    val pushVideo: Boolean = false,
    /** The reply bubble shows a burst's video (on by default; its Video switch turns it off). */
    val bubbleVideo: Boolean = true,
    /** Settings › Talk › Unmute for incoming talk (on by default; TalkUnmute). */
    val unmuteForTalk: Boolean = true,
    /** Settings › Calls › Ringtone: Talk's soft chime (the default; 2026-11-22, hen), or the phone's own ringtone. */
    val softRing: Boolean = true,
    /** Settings › Notifications › Message sound: Talk's soft chime (the default; 2026-11-22, hen), or the phone's sound. */
    val softMessage: Boolean = true,
    /** Settings › Calls › Advanced › 60 fps video (off by default; 2026-11-22, hen): calls and push-to-video. */
    val video60: Boolean = false,
    /** Call settings › Advanced › Show video stats (off by default; 2026-11-22, hen): a Stats button in video calls. */
    val videoStats: Boolean = false,
    /** Settings › Talk › Quick reply window (on by default; 2026-11-23, hen): a message shows its chat over other apps. */
    val quickReply: Boolean = true,
    /** Settings › Talk › Don't turn the screen on when battery is low (on by default; 2026-11-23, hen): under 20%, not charging. */
    val lowBatteryNoWake: Boolean = true,
    /**
     * Settings › Privacy › Live typing (2026-11-23, hen; off by default): what is written in a chat goes to the
     * contact as it is written, deletions too, before Send.
     */
    val liveTyping: Boolean = false,
    /** Live typing to each contact or group, its own switch (2026-11-23, hen; off unless turned on there). */
    val liveTypingFor: Map<String, Boolean> = emptyMap(),
    /** The chats there were when [liveTyping] (the default for new ones) was turned on: they keep their own choice. */
    val liveTypingKnown: Set<String> = emptySet(),
    /**
     * Voice or Video per contact or group (2026-11-23, hen: "remember that setting for that contact"): what the Talk
     * screen's and the reply bubble's Voice | Video chose last for it; [pushVideo] for one never chosen.
     */
    val videoFor: Map<String, Boolean> = emptyMap(),
    /**
     * Chat direction per contact or group (2026-11-24, hen): [ChatDirection.FOLLOW] (the phone's; the default),
     * [ChatDirection.RTL] or [ChatDirection.LTR]. This phone's only; never sent.
     */
    val directionFor: Map<String, Int> = emptyMap(),
    /**
     * Push-to-talk kept in the chat as messages (2026-11-24, hen): voice and video each per chat, off by default; off,
     * a burst leaves only a muted line once it ends. Voice messages from the mic button always stay.
     */
    val keepVoice: Set<String> = emptySet(),
    val keepVideo: Set<String> = emptySet(),
    /** Both sides hands-free in the chat becomes a video call after 10 s, without asking (off by default). */
    val bothLiveCall: Set<String> = emptySet(),
) {
    /** Whether [clip]'s burst in [chat] stays as a message (voice messages always do). */
    fun keeps(chat: String, clip: Clip): Boolean = clip.kind == ClipKind.VOICE || (if (clip.video) chat in keepVideo else chat in keepVoice)

    /** [chat]'s direction (ChatDirection). */
    fun directionOf(chat: String): Int = directionFor[chat] ?: ChatDirection.FOLLOW

    /** With [chat]'s direction set ([ChatDirection.FOLLOW] takes it out). */
    fun withDirection(chat: String, direction: Int): TalkSettings =
        copy(directionFor = if (direction == ChatDirection.FOLLOW) directionFor - chat else directionFor + (chat to direction))

    /**
     * Whether what is written to [chat] goes live: its own switch; one never set follows Settings › Privacy › Live
     * typing only if it came after that was turned on (turning it on never switches on the chats there were).
     */
    fun liveTypingTo(chat: String): Boolean = liveTypingFor[chat] ?: (liveTyping && chat !in liveTypingKnown)

    /** Whether a press to [chat] sends the camera too. */
    fun pushVideoFor(chat: String?): Boolean = chat?.let { videoFor[it] } ?: pushVideo

    /** [chat]'s Voice | Video set to [video]. */
    fun withVideoFor(chat: String, video: Boolean): TalkSettings = copy(videoFor = videoFor + (chat to video))
}

/** A draft written live by someone ([MessagingState.drafts]): its text and when it last changed. */
/** A chat's layout direction (TalkSettings.directionFor). */
object ChatDirection {
    const val FOLLOW = 0
    const val RTL = 1
    const val LTR = 2
}

/** A text being edited live: [text] as it stands (null: hidden), as of [at]; the editor's caret/selection (-1: none). */
data class LiveEdit(val text: String?, val at: Long, val selStart: Int = -1, val selEnd: Int = -1)

/** What is written live and where the caret is in it (-1: not said). */
internal data class LiveText(val text: String, val start: Int = -1, val end: Int = -1)

data class Draft(val text: String, val at: Long, val selStart: Int = -1, val selEnd: Int = -1) {
    companion object {
        /** A live draft fades this long after its last word. */
        const val FRESH_MS = 20_000L
    }
}

/**
 * What the app shows outside its screens (Android's notifications): a text came in, or a conversation was read
 * (here or on another phone of the user), so its notification goes.
 */
interface MessageNotifier {
    fun incoming(contact: Contact, unread: List<TextMessage>)

    fun read(user: String)

    /** A group message came in; [unread] are the group chat's unread ones. */
    fun incomingGroup(group: Group, unread: List<TextMessage>) {}
}

/**
 * The phone's side of keys, pairing and messages (docs/design.md, phase 2), under [MessagingController] and
 * [PairingController]. Everything runs on [scope], whose dispatcher runs one thing at a time: the crypto engine and the
 * store aren't thread-safe.
 */
internal class Messaging(
    private val settings: PttSettings,
    private val engine: CryptoEngine,
    val store: MessagingStore,
    private val api: MessagingApi,
    connector: FrameConnector,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val random: Random = Random.Default,
    private val onRevoked: () -> Unit = {},
    clipFiles: ClipFiles = MemoryClipFiles(),
    blobApi: BlobApi? = null,
) {
    /** Android's notifications; set by the app. */
    var notifier: MessageNotifier? = null

    /** The app is on screen ([start] to [stop]): a background wake-up then leaves the socket alone. */
    private var foreground = false

    /** The conversation on screen, whose texts are read as they come in; null when none is. */
    var openChat: String? = null
        set(value) {
            val was = field
            field = value
            updateViewing()
            if (was != value) {
                was?.let { sendWarm(it, false) }
                value?.let { sendWarm(it, true) }
            }
        }

    /** The chat this phone said it opened (Content.warm), and its refresh while it stays open. */
    private var warmSent: String? = null
    private var warmRefresh: Job? = null

    /**
     * Tells [chat]'s other phones this user opened it ([open]: they join its talk room quietly; again every 5 min while
     * it stays open and the app is in front) or left it (2026-11-24, hen: the silent warm-up). Pushed as PUSH_WARM, so
     * a sleeping phone wakes for it without showing anything; not to a blocked or unpaired contact.
     */
    private fun sendWarm(chat: String, open: Boolean) {
        // Only with a room to warm (the app's LiveKit; not in tests).
        if ((!LIVE_TALK_VIDEO && !LIVE_TALK_AUDIO) || live.transport == null) return
        if (open) {
            warmSent = chat
            warmRefresh?.cancel()
            warmRefresh = scope.launch {
                while (true) {
                    sendWarmNow(chat, true)
                    delay(WARM_REFRESH)
                    if (!foreground || openChat != chat) break
                }
            }
        } else if (warmSent == chat) {
            warmSent = null
            warmRefresh?.cancel()
            scope.launch { sendWarmNow(chat, false) }
        }
    }

    private suspend fun sendWarmNow(chat: String, open: Boolean) {
        try {
            kotlinx.coroutines.withTimeoutOrNull(30_000) { socket.state.first { it is SocketState.Live } } ?: return
            val content = Content(warm = ViewingContent(open))
            Group.idOf(chat)?.let { id ->
                groups.send(id, content, if (open) PushKind.WARM else PushKind.NONE)
                return
            }
            val contact = store.contact(chat)?.takeIf { it.paired && !it.blocked } ?: return
            sender.send(contact.user, content, pushKind = if (open) PushKind.WARM else PushKind.NONE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Someone opened ([open]) or left [chat] (Content.warm): its room is joined quietly, or let go as usual. */
    internal fun takeWarm(chat: String, open: Boolean) {
        if (!LIVE_TALK_VIDEO && !LIVE_TALK_AUDIO) return
        if (open) {
            if (live.warm(chat)) host?.warm(chat, true)
        } else {
            live.cool(chat)
            host?.warm(chat, false)
        }
    }

    /**
     * "Make it a call" while both are live (2026-11-25, hen: instant, no reconnect): the chat's talk room becomes the call.
     * Floor bursts stop, both microphones (and cameras, [video]) stay open in the room, the other phones are told
     * (Content.roomCall) and do the same. False when this phone isn't in that room (the usual call starts instead).
     */
    suspend fun startRoomCall(chat: String, video: Boolean, tell: Boolean = true): Boolean {
        if (!LIVE_TALK_AUDIO || _state.value.roomCall?.chat == chat) return _state.value.roomCall?.chat == chat
        talk.release(send = true)
        if (!live.call(chat, true, video)) return false
        _state.update { it.copy(roomCall = RoomCall(chat, now(), video, microphone = true, camera = video)) }
        if (tell) sendRoomCall(chat, true)
        return true
    }

    /** The room call ends ([tell]: the others are told): the media mute, and the call log gets its line. */
    suspend fun endRoomCall(tell: Boolean = true) {
        val c = _state.value.roomCall ?: return
        _state.update { it.copy(roomCall = null) }
        live.call(c.chat, false, false)
        if (tell) sendRoomCall(c.chat, false)
        store.putMessage(
            TextMessage(
                id = "room-" + c.chat + "-" + c.since, user = c.chat, outgoing = true, body = "", at = c.since, read = true,
                call = CallEntry(c.video, CallOutcome.ANSWERED, now() - c.since),
            ),
        )
        publish()
    }

    /** In a room call: this phone's microphone and camera on or off. */
    suspend fun setRoomCallMedia(microphone: Boolean, camera: Boolean) {
        val c = _state.value.roomCall ?: return
        live.transport?.let { t ->
            runCatching { t.setTalking(microphone) }
            runCatching { t.setVideo(camera) }
        }
        _state.update { it.copy(roomCall = c.copy(microphone = microphone, camera = camera)) }
    }

    private suspend fun sendRoomCall(chat: String, open: Boolean) {
        try {
            val content = Content(roomCall = ViewingContent(open))
            Group.idOf(chat)?.let { id ->
                groups.send(id, content, PushKind.NONE)
                return
            }
            val contact = store.contact(chat)?.takeIf { it.paired && !it.blocked } ?: return
            sender.send(contact.user, content, pushKind = PushKind.NONE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** The other side made it a call ([open]) or ended it. */
    internal fun takeRoomCall(chat: String, open: Boolean) {
        scope.launch {
            if (open) {
                startRoomCall(chat, video = talk.sendVideo(chat), tell = false)
            } else if (_state.value.roomCall?.chat == chat) {
                endRoomCall(tell = false)
            }
        }
    }

    /** The Android side: a quiet foreground service keeps the process while warm (TalkReadyService). */
    var host: WarmHost? = null

    /**
     * The quick-reply window's chat once its user has answered there (2026-11-24, hen): from then on what comes in is
     * read at once, as on the chat's screen, and they count as viewing it. Null: none.
     */
    var windowChat: String? = null
        set(value) {
            field = value
            updateViewing()
        }

    /** What comes in to [chat] is read at once: on screen in front, or answered in the quick-reply window. */
    private fun readsAtOnce(chat: String): Boolean = (openChat == chat && foreground) || windowChat == chat

    private var viewingChat: String? = null
    private var viewingJob: kotlinx.coroutines.Job? = null

    /** Tells the chat in view that it is (and the one left that it isn't); not when this user hides being online. */
    private fun updateViewing() {
        val target = ((openChat?.takeIf { foreground }) ?: windowChat)?.takeIf { settings.presenceVisible }
        if (target == viewingChat) return
        val old = viewingChat
        viewingChat = target
        viewingJob?.cancel()
        if (old != null) scope.launch { sendViewing(old, false) }
        if (target != null) {
            viewingJob = scope.launch {
                while (true) {
                    sendViewing(target, true)
                    kotlinx.coroutines.delay(HERE_EVERY)
                }
            }
        }
    }

    private suspend fun sendViewing(chat: String, open: Boolean) {
        if (socket.state.value !is SocketState.Live) return
        val content = Content(viewing = ViewingContent(open))
        try {
            Group.idOf(chat)?.let { id ->
                groups.send(id, content, PushKind.NONE, ephemeral = true)
                return
            }
            val contact = store.contact(chat)?.takeIf { it.paired && !it.blocked } ?: return
            sender.send(contact.user, content, pushKind = PushKind.NONE, ephemeral = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Tells [chat] this user is live and waiting for the floor there ([waiting]), or not any more. */
    fun sendLiveWaiting(chat: String, waiting: Boolean) {
        scope.launch {
            if (socket.state.value !is SocketState.Live) return@launch
            val content = Content(liveWaiting = ViewingContent(waiting))
            try {
                Group.idOf(chat)?.let { id ->
                    groups.send(id, content, PushKind.NONE, ephemeral = true)
                    return@launch
                }
                val contact = store.contact(chat)?.takeIf { it.paired && !it.blocked } ?: return@launch
                sender.send(contact.user, content, pushKind = PushKind.NONE, ephemeral = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun takeLiveWaiting(key: String, waiting: Boolean) {
        val until = now() + HERE_FOR
        _state.update { it.copy(liveWaiting = if (waiting) it.liveWaiting + (key to until) else it.liveWaiting - key) }
    }

    /** [key] (a contact, or a group's "chat/author") viewing the chat with this user, or not. */
    private fun takeViewing(key: String, open: Boolean) {
        val until = now() + HERE_FOR
        _state.update { it.copy(viewing = if (open) it.viewing + (key to until) else it.viewing - key) }
    }

    /** The conversation open on the app's screen while the app is in front; null otherwise (QuickReply). */
    val chatInFront: String? get() = openChat?.takeIf { foreground }
    private val _state = MutableStateFlow(MessagingState())
    val state: StateFlow<MessagingState> = _state.asStateFlow()

    private val keys = KeyUploader(engine, api, store, now)

    val socket: RelaySocket = RelaySocket(
        scope = scope,
        connector = connector,
        url = { wsUrl(settings.serverUrl) },
        onEnvelope = ::receive,
        // A phone waiting to be linked has no keys to check: uploading would make an identity of its own.
        beforeConnect = {
            if (store.linkJoin == null && engine.identityKey() != null) {
                deviceKey()?.let { keys.check(it) }?.let { count -> _state.update { it.copy(keys = KeysState.Ready(count)) } }
            }
        },
        onRevoked = onRevoked,
        onPresence = { p -> updatePresence(UserPresence(p.user, p.online, p.lastSeen.takeIf { it > 0 })) },
        onTalk = { talk.onFrame(it) },
        onLive = {
            scope.launch {
                // The groups first: a message waiting for a group needs its devices and readers.
                groups.refresh()
                flushOutbox()
                sendPendingReads()
            }
            scope.launch { refreshPresence() }
            // Not awaited either: the request's SendResult comes through the same read loop.
            if (store.linkJoin != null) scope.launch { sendLinkRequest() }
            refreshContacts()
            // Not awaited here: the pair request's SendResult comes through the very read loop onLive runs in.
            scope.launch { pairPendingInvite() }
        },
    )

    private val sender: Sender = Sender(engine, api, socket::send, { settings.device })

    val groups: Groups = Groups(
        engine = engine, store = store, api = api, sender = sender, send = socket::send, scope = scope, now = now, random = random,
        me = ::device, publish = ::publish,
        onText = { group, message ->
            val read = message.outgoing || readsAtOnce(group.chat)
            store.putMessage(message.copy(read = read))
            _state.update { it.copy(typing = it.typing - typingKey(group.chat, message.author.orEmpty()), drafts = it.drafts - typingKey(group.chat, message.author.orEmpty())) }
            if (!read) notifier?.incomingGroup(group, store.messagesWith(group.chat).filter { !it.outgoing && !it.read })
        },
        onClip = { group, author, clip, at ->
            val mine = author == device()?.user
            val fresh = store.message(clip.id) == null
            talk.receiveClip(group.chat, author, clip, at, outgoingCopy = mine)
            if (fresh && !mine) {
                if (readsAtOnce(group.chat)) store.message(clip.id)?.let { store.putMessage(it.copy(read = true)) }
                else if (clip.kind == ClipContent.VOICE) notifier?.incomingGroup(group, store.messagesWith(group.chat).filter { !it.outgoing && !it.read })
            }
        },
        onTalkKey = { identity, key -> live.takeKey(identity, key) },
        onCall = { group, author, call, at -> calls.receive(group.chat, author, call, at, ownCopy = author == device()?.user) },
        onRead = { group, author, ids ->
            var changed = false
            val at = now()
            for (id in ids) {
                val m = store.message(id)?.takeIf { it.outgoing && it.user == group.chat && author !in it.readBy } ?: continue
                store.putMessage(m.copy(readBy = m.readBy + (author to at)))
                changed = true
            }
            if (changed) publish()
        },
        onEdit = { group, author, edit -> takeEdit(group.chat, author, mine = author == device()?.user, edit) },
        onDelete = { group, author, delete -> takeDelete(group.chat, author, mine = author == device()?.user, delete) },
        onViewing = { group, author, open -> if (author != device()?.user) takeViewing(typingKey(group.chat, author), open) },
        onLiveWaiting = { group, author, waiting -> if (author != device()?.user) takeLiveWaiting(typingKey(group.chat, author), waiting) },
        onWarm = { group, author, open -> if (author != device()?.user) takeWarm(group.chat, open) },
        onReaction = { group, author, reaction -> takeReaction(group.chat, if (author == device()?.user) null else author, reaction) },
        onProfile = { _, author, profile -> if (author != device()?.user) takePicture(author, profile, contact = store.contact(author)?.paired == true) },
        onTyping = { group, author, typing ->
            val key = typingKey(group.chat, author)
            _state.update { it.copy(typing = if (typing.typing) it.typing + (key to now() + TYPING_FOR) else it.typing - key, drafts = withDraft(it.drafts, key, typing.typing, typing.draft, typing.selStart, typing.selEnd)) }
        },
    )

    /** Stay connected's service runs: bursts play without the app on screen. */
    var stayConnected = false

    private val blobs: BlobApi = blobApi ?: object : BlobApi {
        override suspend fun read(id: String, onBytes: suspend (ByteArray) -> Unit) = throw BlobException(0, "No server")
        override suspend fun put(id: String, peer: String?, group: String?, bytes: ByteArray) = throw BlobException(0, "No server")
    }

    /** The tracks the server kept of the call [message] (its id is the call's), that this user may read. */
    suspend fun keptCall(message: TextMessage): KeptCall {
        val group = Group.idOf(message.user)
        return api.callRecording(message.id, if (group == null) message.user else null, group)
    }

    /** Reads blob [id] (a kept call's track) from the server, as it arrives. */
    suspend fun readBlob(id: String, onBytes: suspend (ByteArray) -> Unit) = blobs.read(id, onBytes)

    val talk: Talk = Talk(
        scope = scope, store = store, clips = clipFiles,
        blobs = blobs,
        sendFrame = socket::sendFrame,
        connected = { socket.state.value is SocketState.Live || socket.state.value is SocketState.Syncing },
        sendContent = { chat, content, pushKind ->
            val group = Group.idOf(chat)
            if (group != null) groups.send(group, content, pushKind) else sender.send(chat, content, pushKind = pushKind)
        },
        me = ::device, now = now, random = random,
        prefs = object : TalkPrefs {
            override val catchUp: CatchUpMode
                get() = settings.catchUp?.let { name -> CatchUpMode.entries.firstOrNull { it.name == name } } ?: CatchUpMode.SHORTEN_PAUSES_AND_SPEED_UP

            override fun notifyOnly(chat: String): Boolean = chat in settings.notifyOnlyChats

            override val unmute: Boolean get() = settings.unmuteForTalk
        },
        publish = ::publish,
        awake = { foreground || stayConnected },
    )

    val live: com.chencohen.ptt.talk.LiveTalk = com.chencohen.ptt.talk.LiveTalk(
        scope = scope,
        tokens = { peer, group -> api.talkToken(peer, group) },
        available = { api.liveTalk() },
        serverUrl = { settings.serverUrl },
        sendContent = { chat, content, pushKind ->
            val group = Group.idOf(chat)
            if (group != null) groups.send(group, content, pushKind) else sender.send(chat, content, pushKind = pushKind)
        },
        now = now, random = random,
        audio = LIVE_TALK_AUDIO,
        busy = { talk.busy },
    )

    val calls: com.chencohen.ptt.calls.Calls = com.chencohen.ptt.calls.Calls(
        scope = scope,
        me = { device()?.user },
        tokens = { call, peer, group -> api.callToken(call, peer, group) },
        serverUrl = { settings.serverUrl },
        sendContent = { chat, content, pushKind ->
            val group = Group.idOf(chat)
            if (group != null) groups.send(group, content, pushKind) else sender.send(chat, content, pushKind = pushKind)
        },
        putEntry = { entry ->
            store.putMessage(entry)
            publish()
        },
        entry = { id -> store.message(id) },
        saveCalls = { talkSettings().saveCalls },
        keepOnServer = { call, peer, group, on -> api.recordCall(call, peer, group, on) },
        serverKeepsCalls = { settings.serverKeepsCalls },
        logEvent = { call, peer, group, event -> api.logCallEvent(call, peer, group, event) },
        onCall = {
            talk.release(send = true)
            live.leaveNow()
        },
        now = now, random = random,
    )

    /**
     * Push-to-video in [chat] may go as H.265: chosen in Settings › Calls › Video, and every other phone there said
     * it decodes H.265 (ContentCodec.caps); otherwise VP8. One format per burst, no second copy.
     */
    fun videoH265For(chat: String?): Boolean {
        if (chat == null || talkSettings().callVideo != com.chencohen.ptt.calls.CallVideo.H265) return false
        val me = device()?.user
        val users = Group.idOf(chat)?.let { id -> store.group(id)?.readers?.map { it.user }?.filter { it != me } } ?: listOf(chat)
        return users.isNotEmpty() && users.all { ContentCodec.decodesH265(it) }
    }

    init {
        if (LIVE_TALK_AUDIO || LIVE_TALK_VIDEO) talk.live = live
        talk.sendVideo = { chat -> talkSettings().pushVideoFor(chat) }
        scope.launchCollect(calls.state) { c ->
            val was = _state.value.call != null
            _state.update { it.copy(call = c) }
            if (was != (c != null)) applyWarmCamera()
        }
        scope.launchCollect(live.state) { l -> _state.update { it.copy(live = l) } }
        _state.update { it.copy(talkSettings = talkSettings()) }
        scope.launchCollect(socket.state) { socket -> _state.update { it.copy(socket = socket) } }
        scope.launchCollect(talk.state) { talk -> _state.update { it.copy(talk = talk) } }
    }

    private fun device(): EnrolledDevice? = settings.device?.takeIf { it.deviceNumber > 0 }

    /** This device as the server knows it; null before enrolling. */
    fun self(): EnrolledDevice? = device()

    /** The server part of this phone's codes (PairCode.server). */
    fun serverPart(): String? = PairCode.serverOf(settings.serverFingerprint)

    private fun deviceKey(): String? = device()?.let { "${it.user}/${it.deviceNumber}" }

    /** The app came to the front: keys, /ws, and the push registration. */
    suspend fun start() {
        foreground = true
        updateViewing()
        // Back in front on a chat: its room again, and the others warmed again.
        openChat?.let { chat ->
            if (LIVE_TALK_AUDIO || LIVE_TALK_VIDEO) live.show(chat)
            if (warmSent != chat) sendWarm(chat, true)
        }
        open()
        // Once a run: this user's email to contacts (some paired since it was last said).
        if (!profileShared && (settings.myEmail != null || settings.myGender != Gender.NOT_SET || store.pictureId("") != null)) {
            profileShared = true
            scope.launch {
                socket.state.first { it is SocketState.Live }
                shareProfile()
            }
        }
    }

    private var profileShared = false

    /** The app went to the back. */
    fun stop() {
        foreground = false
        updateViewing()
        // The app went to the back with a chat open (2026-11-25, hen: "when going to background, leave the room"): as
        // leaving the chat; a room this phone is warm in for someone else's open chat stays.
        live.background()
        // The app left: the warmed phones are told (then the connection closes, unless something keeps it).
        val warm = warmSent
        if (warm != null) {
            sendWarm(warm, false)
            scope.launch {
                delay(1_500)
                if (!foreground && !stayConnected && !talk.busy && live.warmFor() <= now()) socket.stop()
            }
            return
        }
        if (!stayConnected && !talk.busy && live.warmFor() <= now()) socket.stop()
    }

    /**
     * A push woke the phone (FCM's onMessageReceived): opens /ws if the app isn't on screen, takes the queue (texts,
     * receipts, notifications through [notifier]), sends what the outbox holds, and closes again. Returns once done,
     * or after [limit] milliseconds.
     */
    suspend fun wake(limit: Long = 20_000) {
        if (foreground || device() == null) return
        open()
        kotlinx.coroutines.withTimeoutOrNull(limit) {
            socket.state.first { it is SocketState.Live || it is SocketState.Revoked || it is SocketState.Replaced }
            // The receipts and the outbox go out after the queue: give them a moment.
            kotlinx.coroutines.delay(1_500)
        }
        // A burst that plays (or is about to), and the reply time after it: the connection stays for them.
        kotlinx.coroutines.withTimeoutOrNull(TALK_WAKE_LIMIT) {
            var heard = false
            var quietSince = now()
            while (true) {
                if (talk.busy) {
                    heard = true
                    quietSince = now()
                } else if (!heard || now() - quietSince >= TALK_REPLY_WAIT) {
                    break
                }
                kotlinx.coroutines.delay(500)
            }
        }
        // Warmed (someone has this chat open): the connection and the room stay, quietly, until they leave it, its
        // refresh stops coming, or nobody talks for 10 minutes.
        while (!foreground && live.warmFor() > now()) kotlinx.coroutines.delay(1_000)
        host?.warm(null, false)
        if (!foreground && !stayConnected) socket.stop()
    }

    fun talkSettings(): TalkSettings = TalkSettings(
        catchUp = settings.catchUp?.let { name -> CatchUpMode.entries.firstOrNull { it.name == name } } ?: CatchUpMode.SHORTEN_PAUSES_AND_SPEED_UP,
        stayConnected = settings.stayConnected,
        defaultChat = settings.defaultTalkChat,
        hardwareButtons = settings.hardwareButtons,
        notifyOnly = settings.notifyOnlyChats,
        downloadedCalls = settings.downloadedCalls,
        saveCalls = settings.saveCalls?.let { name -> com.chencohen.ptt.calls.SaveCalls.entries.firstOrNull { it.name == name } } ?: com.chencohen.ptt.calls.SaveCalls.OFF,
        autoDownloadCalls = settings.autoDownloadCalls,
        autoDownloadWifiOnly = settings.autoDownloadWifiOnly,
        autoDownloadChargingOnly = settings.autoDownloadChargingOnly,
        quickTalk = settings.quickTalkChats,
        quickTalkPage = settings.quickTalkPage,
        openOnTalk = settings.openOnTalk,
        replyBubble = settings.replyBubble,
        cameraReady = settings.cameraReady,
        callVideo = com.chencohen.ptt.calls.CallVideo.entries.firstOrNull { it.name == settings.callVideo } ?: com.chencohen.ptt.calls.CallVideo.H265,
        pushVideo = settings.pushVideo,
        bubbleVideo = settings.bubbleVideo,
        unmuteForTalk = settings.unmuteForTalk,
        softRing = settings.softRing,
        softMessage = settings.softMessage,
        video60 = settings.video60,
        videoStats = settings.videoStats,
        quickReply = settings.quickReply,
        lowBatteryNoWake = settings.lowBatteryNoWake,
        videoFor = settings.videoFor,
        directionFor = settings.directionFor,
        keepVoice = settings.keepVoiceChats,
        keepVideo = settings.keepVideoChats,
        bothLiveCall = settings.bothLiveCallChats,
        liveTyping = settings.liveTyping,
        liveTypingFor = settings.liveTypingFor,
        liveTypingKnown = settings.liveTypingKnown,
    )

    fun setTalkSettings(talk: TalkSettings) {
        settings.catchUp = talk.catchUp.name
        settings.stayConnected = talk.stayConnected
        settings.defaultTalkChat = talk.defaultChat
        settings.hardwareButtons = talk.hardwareButtons
        settings.notifyOnlyChats = talk.notifyOnly
        settings.downloadedCalls = talk.downloadedCalls
        settings.saveCalls = talk.saveCalls.name
        settings.autoDownloadCalls = talk.autoDownloadCalls
        settings.autoDownloadWifiOnly = talk.autoDownloadWifiOnly
        settings.autoDownloadChargingOnly = talk.autoDownloadChargingOnly
        settings.quickTalkChats = talk.quickTalk
        settings.quickTalkPage = talk.quickTalkPage
        settings.openOnTalk = talk.openOnTalk
        // Written only when changed, so one never chosen keeps following the default.
        val before = _state.value.talkSettings
        if (talk.replyBubble != before.replyBubble) settings.replyBubble = talk.replyBubble
        if (talk.cameraReady != before.cameraReady) settings.cameraReady = talk.cameraReady
        if (talk.callVideo != before.callVideo) settings.callVideo = talk.callVideo.name
        settings.pushVideo = talk.pushVideo
        settings.bubbleVideo = talk.bubbleVideo
        settings.unmuteForTalk = talk.unmuteForTalk
        settings.softRing = talk.softRing
        settings.softMessage = talk.softMessage
        settings.video60 = talk.video60
        settings.videoStats = talk.videoStats
        settings.quickReply = talk.quickReply
        settings.lowBatteryNoWake = talk.lowBatteryNoWake
        if (talk.videoFor != before.videoFor) settings.videoFor = talk.videoFor
        if (talk.directionFor != before.directionFor) settings.directionFor = talk.directionFor
        settings.keepVoiceChats = talk.keepVoice
        settings.keepVideoChats = talk.keepVideo
        settings.bothLiveCallChats = talk.bothLiveCall
        settings.liveTyping = talk.liveTyping
        if (talk.liveTypingFor != before.liveTypingFor) settings.liveTypingFor = talk.liveTypingFor
        // The default turned on: the chats there are now keep theirs (off unless set), only new ones follow it.
        val known = if (talk.liveTyping && !before.liveTyping) knownChats() else talk.liveTypingKnown
        if (known != before.liveTypingKnown) settings.liveTypingKnown = known
        val talk = talk.copy(liveTypingKnown = known)
        _state.update { it.copy(talkSettings = talk) }
    }

    /** Records that a kept call was downloaded from the server, so its Download button is gone (idempotent). */
    fun markCallDownloaded(id: String) {
        val talk = _state.value.talkSettings
        if (id in talk.downloadedCalls) return
        setTalkSettings(talk.copy(downloadedCalls = talk.downloadedCalls + id))
    }

    /** Stay connected's service started ([on]) or stopped: /ws stays open in the background while it runs. */
    suspend fun keepConnected(on: Boolean) {
        stayConnected = on
        if (on) open() else if (!foreground) socket.stop()
    }

    /** Uploads the keys if needed and opens /ws. */
    private suspend fun open() {
        val self = device() ?: return
        claim(self)
        takePendingLink(self)
        publish()
        store.linkJoin?.let { join ->
            // Enrolled by a Link a phone code: no keys of its own until that phone sends the identity key.
            if (engine.identityKey() == null) {
                _state.update { it.copy(keys = KeysState.Linking(join.user, join.device)) }
                socket.start()
                return
            }
            store.linkJoin = null
        }
        _state.update { it.copy(keys = if (it.keys is KeysState.Ready) it.keys else KeysState.Uploading) }
        val result = try {
            keys.ensure(deviceKey()!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KeysState.Failed(e.describe())
        }
        _state.update { it.copy(keys = result) }
        // Without keys nobody can open a session with this phone, but what is queued for it can still be read.
        if (result !is KeysState.OtherIdentity) socket.start()
    }

    /** Stops for good (the app's process ends). */
    fun close() {
        socket.stop()
        scope.cancel()
    }

    /**
     * The store belongs to one user on one server: enrolled as another (Register again), everything of the old one
     * (keys, sessions, contacts, messages) is forgotten.
     */
    private fun claim(self: EnrolledDevice) {
        val owner = "${settings.serverFingerprint.orEmpty()}/${self.user}"
        val meta = store.meta
        if (meta.owner == owner) return
        if (meta.owner != null) {
            engine.wipe()
            store.store.clearAll()
        }
        store.meta = Meta(owner = owner)
    }

    /** Starts over with a new identity key (every other phone of the user revoked), then uploads the keys. */
    suspend fun replaceIdentityKey() {
        val self = device() ?: return
        val identity = engine.createIdentity()
        store.meta = store.meta.copy(uploadedFor = null)
        api.replaceIdentityKey(identity)
        // Every session was opened with the old identity: they start again (the contacts' trusted keys stay).
        for (contact in store.contacts()) {
            engine.forget(contact.user)
            engine.trustIdentity(contact.user, Base64.decode(contact.identityKey))
        }
        val result = keys.ensure("${self.user}/${self.deviceNumber}")
        _state.update { it.copy(keys = result) }
        socket.start()
    }

    // --- Push and presence --------------------------------------------------------------------------------------

    /**
     * Tells the server this phone's FCM [token] (null: none) and whether it shows notifications, when that changed
     * since it was last told (registerPush). Network errors are thrown; the app tries again at the next start.
     */
    suspend fun registerPush(token: String?, notificationsEnabled: Boolean) {
        val key = deviceKey() ?: return
        if (token != null) settings.pushToken = token
        val wanted = "$key|${token ?: settings.pushToken.orEmpty()}|$notificationsEnabled"
        if (settings.pushRegistered == wanted) return
        api.registerPush(token ?: settings.pushToken, notificationsEnabled)
        settings.pushRegistered = wanted
    }

    /** Whether this user shows contacts when they're online, as last set here. */
    val presenceVisible: Boolean get() = settings.presenceVisible

    suspend fun setPresenceVisible(visible: Boolean): Boolean {
        val shown = api.setPresenceVisible(visible)
        settings.presenceVisible = shown
        refreshPresence()
        return shown
    }

    /** Asks the server who of the contacts is online (a phone that was offline missed the Presence frames). */
    suspend fun refreshPresence() {
        val users = store.contacts().filter { it.paired }.map { it.user }
        if (users.isEmpty()) return
        val answer = try {
            api.presence(users)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        _state.update { it.copy(presence = answer.associateBy { p -> p.user }) }
    }

    private fun updatePresence(p: UserPresence) {
        _state.update { it.copy(presence = it.presence + (p.user to p)) }
    }

    // --- Push-to-video's camera --------------------------------------------------------------------------------

    /** The chat whose Talk screen wants the camera warm (push-to-video on); null: none. */
    private var warmWanted: String? = null

    /**
     * Keeps push-to-video's camera open for [chat]'s Talk screen (null: lets it go). Never during a call: the call's
     * camera is the same one, and opening it for the Talk screen under the call (it opened again when a rotation laid
     * the screen out anew) took it from the call, whose video stopped until its camera was turned off and on
     * (2026-11-22, hen). It warms again once the call ends, if the screen still wants it.
     */
    fun warmCamera(chat: String?) {
        warmWanted = chat
        applyWarmCamera()
    }

    private fun applyWarmCamera() {
        val camera = talk.videoCapture ?: return
        // The screen asks for it (pressing Push with Get camera ready on, sliding up, live video): no Voice | Video
        // setting to check any more (2026-11-24, hen).
        val chat = warmWanted?.takeIf { _state.value.call == null }
        if (chat != null) {
            if (talk.videoChat != chat) camera.cool()
            talk.videoChat = chat
            camera.warm()
        } else {
            talk.videoChat = null
            camera.cool()
        }
        // The talk room's camera track reads the camera too while it's warm (muted until a video burst), so the
        // picture goes live there at once.
        live.readyVideo(chat)
    }

    // --- Your devices (docs/screens/devices.md) ----------------------------------------------------------------

    /** This user's phones that aren't revoked, this one first, then by number. */
    suspend fun myDevices(): List<OwnDevice> = api.myDevices().sortedWith(compareBy({ !it.current }, { it.deviceNumber }))

    /** Revokes another phone of this user: true when it is revoked (or was already gone), false for this phone. */
    suspend fun revokeDevice(deviceNumber: Int): Boolean {
        if (device()?.deviceNumber == deviceNumber) return false
        return api.revokeMyDevice(deviceNumber) != "CURRENT_DEVICE"
    }

    // --- Linking a phone ------------------------------------------------------------------------------------------

    /**
     * A new Link a phone code (docs/design.md, "Linking a phone"): an enrollment code for this user from the server,
     * where the server is, and a secret and an X25519 key kept to answer the new phone's request once.
     */
    suspend fun newLinkCode(): Pair<LinkCode, Long> {
        val self = device() ?: throw IllegalStateException("This phone isn't registered")
        if (engine.exportIdentity() == null) throw IllegalStateException("No identity key yet")
        val discovery = settings.serverDiscovery ?: throw IllegalStateException("The server hasn't said who it is yet")
        val (code, expiresAt) = api.createLinkCode()
        val secret = random.nextBytes(LinkCode.SECRET_BYTES)
        val (private, public) = engine.newAgreementKey()
        val expires = parseTime(expiresAt) ?: (now() + 10 * 60_000)
        store.linkOffers().filter { now() > it.expiresAt + LINK_GRACE }.forEach(store::removeLinkOffer)
        store.putLinkOffer(LinkOffer(PairCode.base64Url.encode(secret), Base64.encode(private), Base64.encode(public), expires))
        val link = LinkCode(
            code = code,
            server = KeptDiscovery.originOf(settings.serverUrl),
            fingerprint = discovery.fingerprint,
            key = discovery.key,
            addresses = discovery.candidates(),
            user = self.user,
            device = self.deviceNumber,
            secret = secret,
        )
        return link to expires
    }

    /**
     * A phone enrolled already, whose account's key is on another phone (KeysState.OtherIdentity), scanned that phone's
     * Link a phone code: it asks for the key as if it had enrolled with the code. False for a code it can't use (another
     * account, or itself).
     */
    suspend fun linkWithScanned(text: String): Boolean {
        val self = device() ?: return false
        val code = LinkCode.find(text) ?: return false
        if (code.user != self.user || code.device == self.deviceNumber || code.fingerprint != settings.serverFingerprint) return false
        store.linkJoin = null
        // Its own key (unused: the server holds another) goes; the one from the other phone takes its place.
        if (engine.identityKey() != null) engine.wipe()
        settings.pendingLink = code.format()
        socket.stop()
        start()
        return true
    }

    /** The Link a phone code this phone enrolled with (settings.pendingLink) becomes a [LinkJoin] with its own key. */
    private fun takePendingLink(self: EnrolledDevice) {
        val text = settings.pendingLink ?: return
        settings.pendingLink = null
        val code = LinkCode.parse(text) ?: return
        if (code.user != self.user || code.device == self.deviceNumber || engine.identityKey() != null) return
        if (code.fingerprint != settings.serverFingerprint) return
        val (private, public) = engine.newAgreementKey()
        store.linkJoin = LinkJoin(code.user, code.device, PairCode.base64Url.encode(code.secret), Base64.encode(private), Base64.encode(public), now())
    }

    /** The new phone's side: asks the phone that showed the code for the identity key, proving it saw the code. */
    private suspend fun sendLinkRequest() {
        val self = device() ?: return
        val join = store.linkJoin ?: return
        val public = Base64.decode(join.publicKey)
        val mac = LinkCrypto.requestMac(PairCode.base64Url.decode(join.secret), public, self.user, self.deviceNumber)
        try {
            sendProvisioning(join.device, Provisioning(request = LinkRequest(public, mac)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Asked again on the next connection.
        }
    }

    private suspend fun sendProvisioning(device: Int, content: Provisioning) {
        val self = device() ?: return
        val result = socket.send(
            com.chencohen.ptt.relay.Send(
                envelopes = listOf(Envelope(recipientUser = self.user, recipientDevice = device, type = EnvelopeType.PROVISIONING, ciphertext = LinkCrypto.encode(content))),
            )
        )
        if (result.status != com.chencohen.ptt.relay.SendStatus.ACCEPTED) throw SendRefusedException(result.status, result.detail)
    }

    /** A PROVISIONING envelope, from another phone of this user only: a request to answer, or the answer awaited. */
    private fun receiveProvisioning(envelope: Envelope) {
        val self = device() ?: return
        if (envelope.senderUser != self.user || envelope.senderDevice == self.deviceNumber) return
        val content = LinkCrypto.decode(envelope.ciphertext) ?: return
        content.request?.let { answerLinkRequest(self, envelope.senderDevice, it) }
        content.answer?.let { takeLinkAnswer(envelope.senderDevice, it) }
    }

    /**
     * The phone that showed the code: when the request's MAC is one of its codes' (whoever sent it saw the screen), it
     * seals the identity key pair and the contacts to the new phone's key and sends them. Each code answers once.
     */
    private fun answerLinkRequest(self: EnrolledDevice, from: Int, request: LinkRequest) {
        val pair = engine.exportIdentity() ?: return
        val offer = store.linkOffers().firstOrNull { offer ->
            now() <= offer.expiresAt + LINK_GRACE &&
                LinkCrypto.macMatches(LinkCrypto.requestMac(PairCode.base64Url.decode(offer.secret), request.publicKey, self.user, from), request.mac)
        } ?: return
        store.removeLinkOffer(offer)
        val key = try {
            LinkCrypto.sealKey(engine.agree(Base64.decode(offer.privateKey), request.publicKey), PairCode.base64Url.decode(offer.secret))
        } catch (_: CryptoException) {
            return
        }
        val contacts = store.contacts().filter { it.paired }.map {
            LinkedContact(it.user, Base64.decode(it.identityKey), it.verified, it.displayName.orEmpty())
        }
        val nonce = random.nextBytes(com.chencohen.ptt.discovery.Seal.NONCE_SIZE)
        val answer = LinkAnswer(Base64.decode(offer.publicKey), nonce, LinkCrypto.seal(key, nonce, LinkData(pair, contacts)))
        scope.launch {
            try {
                sendProvisioning(from, Provisioning(answer = answer))
                _state.update { it.copy(linkedDevice = from) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The code is used up: the new phone shows a new one's QR code again.
            }
        }
    }

    /** The new phone: the answer opens with this linking's key, so it is the identity key; then the keys go up. */
    private fun takeLinkAnswer(from: Int, answer: LinkAnswer) {
        val join = store.linkJoin ?: return
        if (from != join.device) return
        val data = try {
            val key = LinkCrypto.sealKey(engine.agree(Base64.decode(join.privateKey), answer.publicKey), PairCode.base64Url.decode(join.secret))
            LinkCrypto.open(key, answer.nonce, answer.sealed)
        } catch (_: CryptoException) {
            null
        } ?: return
        try {
            engine.importIdentity(data.identityKeyPair)
        } catch (_: CryptoException) {
            return
        }
        for (linked in data.contacts) {
            val identity = linked.identityKey
            engine.trustIdentity(linked.user, identity)
            val old = store.contact(linked.user)
            store.putContact(
                Contact(
                    user = linked.user, identityKey = Base64.encode(identity), paired = true, since = old?.since,
                    verified = linked.verified, displayName = linked.displayName.ifEmpty { null } ?: old?.displayName,
                    addedAt = old?.addedAt ?: now(),
                )
            )
        }
        store.linkJoin = null
        store.meta = store.meta.copy(uploadedFor = null)
        publish()
        scope.launch {
            val result = try {
                keys.ensure(deviceKey()!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KeysState.Failed(e.describe())
            }
            _state.update { it.copy(keys = result) }
            refreshContacts()
        }
    }

    // --- Your own phones ----------------------------------------------------------------------------------------

    /**
     * Tells this user's other phones what this one now knows of [user] (paired, its key, verified), so a contact
     * paired or verified on one phone is the same on all of them. Nothing to do without other phones.
     */
    private fun syncContact(user: String) {
        val contact = store.contact(user)?.takeIf { it.paired } ?: return
        val self = device() ?: return
        scope.launch {
            try {
                sender.send(
                    self.user,
                    Content(
                        contact = ContactSyncContent(
                            contact.user, Base64.decode(contact.identityKey), contact.verified, contact.displayName.orEmpty(),
                            contact.nickname.orEmpty(), nicknameSet = true, email = contact.email.orEmpty(), emailSet = true,
                        ),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No other phone with keys, or not reachable now: the next change or the server's contact list catches up.
            }
        }
    }

    /** What another phone of this user said of a contact: its key and verified flag win over the server's list. */
    private fun takeContactSync(sync: ContactSyncContent) {
        if (sync.user.isEmpty() || sync.identityKey.size != 33) return
        val key = Base64.encode(sync.identityKey)
        val old = store.contact(sync.user)
        if (old == null || old.identityKey != key) {
            engine.forget(sync.user)
            engine.trustIdentity(sync.user, sync.identityKey)
        }
        store.putContact(
            (old ?: Contact(user = sync.user, identityKey = key, paired = true, addedAt = now())).copy(
                identityKey = key,
                paired = true,
                verified = sync.verified,
                changedKey = old?.changedKey?.takeIf { it != key },
                displayName = sync.displayName.ifEmpty { null } ?: old?.displayName,
                nickname = if (sync.nicknameSet) sync.nickname.ifBlank { null } else old?.nickname,
                email = if (sync.emailSet) sync.email.ifBlank { null } else old?.email,
            )
        )
    }

    // --- Pairing -------------------------------------------------------------------------------------------------

    /** A new Add contact code for this phone: a pairing token and a secret kept to recognise the pair request. */
    suspend fun newPairCode(): Pair<PairCode, Long> {
        val self = device() ?: throw IllegalStateException("This phone isn't registered")
        val identity = engine.identityKey() ?: throw IllegalStateException("No identity key yet")
        val (token, expiresAt) = api.pairingToken()
        val secret = random.nextBytes(PairCode.SECRET_BYTES)
        val expires = parseTime(expiresAt) ?: (now() + 10 * 60_000)
        store.putInvite(Invite(PairCode.base64Url.encode(secret), token, expires, now()))
        dropOldInvites()
        return PairCode(self.user, identity, token, secret, PairCode.serverOf(settings.serverFingerprint)) to expires
    }

    /** Old secrets go after the week a pairing may wait on the server. */
    private fun dropOldInvites() {
        store.invites().filter { now() > it.keptUntil() }.forEach(store::removeInvite)
    }

    /**
     * A new Invite a friend link from this phone (for [email] when not null): the server's invite code, where the
     * server is, and this phone's pairing part, with a secret kept to recognise the friend's pair request. Gives the
     * link, when it expires, and the invite's passcode, which goes beside the link (not in it).
     */
    /** This user's invites waiting to be used (Invite a friend's list). */
    suspend fun waitingInvites(): List<WaitingInvite> = api.waitingInvites()

    /** Stops invite [id]; true when it was waiting. */
    suspend fun cancelInvite(id: String): Boolean = api.cancelInvite(id)

    suspend fun newInvite(email: String?): NewInvite {
        val self = device() ?: throw IllegalStateException("This phone isn't registered")
        val identity = engine.identityKey() ?: throw IllegalStateException("No identity key yet")
        val discovery = settings.serverDiscovery ?: throw IllegalStateException("The server hasn't said who it is yet")
        val wanted = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val created = api.createInvite(wanted)
        val code = created.code
        val secret = random.nextBytes(PairCode.SECRET_BYTES)
        val expires = parseTime(created.expiresAt) ?: (now() + 7.days.inWholeMilliseconds)
        store.putInvite(Invite(PairCode.base64Url.encode(secret), code, expires, now(), viaLink = true))
        dropOldInvites()
        val link = InviteLink(
            code = code,
            server = KeptDiscovery.originOf(settings.serverUrl),
            fingerprint = discovery.fingerprint,
            key = discovery.key,
            addresses = discovery.candidates(),
            inviter = self.user,
            identityKey = identity,
            secret = secret,
            email = wanted,
            passcode = null,
        )
        return NewInvite(link, expires, created.passcode, created.id)
    }

    /** Redeems [link] on this enrolled phone and pairs with the inviter. True when the pair is made. */
    suspend fun acceptInvite(link: InviteLink): Boolean {
        val pairing = api.acceptInvite(link.code)
        val token = pairing.token ?: throw ApiException(ApiCodes.RATE_LIMITED, "The server couldn't make a pairing token")
        return pairWith(link.pairCode(token), verified = false)
    }

    /**
     * After enrolling with an invite link: pairs with the inviter (settings.pendingInvitePair) once the socket is live,
     * and forgets it when that worked or can't work any more. Not verified: the link may have been forwarded.
     */
    private suspend fun pairPendingInvite() {
        val text = settings.pendingInvitePair ?: return
        val code = (PairCode.parse(text) as? PairCodeParse.Code)?.code
        if (code == null || device() == null) {
            settings.pendingInvitePair = null
            return
        }
        if (_state.value.keys !is KeysState.Ready) return
        try {
            pairWith(code, verified = false)
            settings.pendingInvitePair = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kept for the next connection when the server or the socket wasn't there; else it can't work any more.
            if (e.toPairingError() != PairingError.UNREACHABLE) settings.pendingInvitePair = null
        }
    }

    /**
     * Pairs with whoever showed [code]: trusts the identity key from it, sends the pair request (with the code's
     * secret) to their devices through the server, and confirms. True when the pair is made, false while they haven't
     * confirmed.
     */
    suspend fun pairWith(code: PairCode, verified: Boolean): Boolean {
        val self = device() ?: throw IllegalStateException("This phone isn't registered")
        val identity = engine.identityKey() ?: throw IllegalStateException("No identity key yet")
        // A new start: the key from the code replaces whatever was trusted for them.
        engine.forget(code.user)
        engine.trustIdentity(code.user, code.identityKey)
        val key = Base64.encode(code.identityKey)
        val old = store.contact(code.user)
        store.putContact(
            Contact(
                user = code.user,
                identityKey = key,
                paired = old?.paired == true && old.identityKey == key,
                since = old?.since,
                // Their code scanned in person; a link (an invite) may have been forwarded.
                verified = verified || (old?.verified == true && old.identityKey == key),
                addedAt = old?.addedAt ?: now(),
            )
        )
        publish()
        try {
            sender.send(code.user, Content(pairRequest = PairRequestContent(code.secret, self.user, identity)), code.token)
        } catch (e: Exception) {
            // Nothing reached them: back to what was there before.
            engine.forget(code.user)
            if (old == null) {
                store.removeContact(code.user)
            } else {
                store.putContact(old)
                engine.trustIdentity(old.user, Base64.decode(old.identityKey))
            }
            publish()
            throw e
        }
        val paired = api.confirmPair(code.user)
        if (paired) markPaired(code.user)
        return paired
    }

    /** Confirms [user]'s pair request. True when the pair is made. */
    suspend fun accept(user: String): Boolean {
        val request = store.requests().firstOrNull { it.user == user } ?: return false
        val paired = api.confirmPair(user)
        val old = store.contact(user)
        store.putContact(
            Contact(
                user = user,
                identityKey = request.identityKey,
                paired = paired,
                since = old?.since,
                // The request carried the secret of this phone's code: whoever sent it saw the screen. An invite
                // link's secret may have been forwarded, so that one isn't verified.
                verified = !request.viaLink || (old?.verified == true && old.identityKey == request.identityKey),
                addedAt = old?.addedAt ?: now(),
            )
        )
        store.removeRequest(user)
        publish()
        if (paired) {
            refreshContacts()
            syncContact(user)
        }
        return paired
    }

    fun decline(user: String) {
        store.removeRequest(user)
        if (store.contact(user) == null && !groups.knows(user)) engine.forget(user)
        publish()
    }

    private fun markPaired(user: String) {
        store.contact(user)?.let { store.putContact(it.copy(paired = true)) }
        publish()
        syncContact(user)
    }

    // --- Contacts ------------------------------------------------------------------------------------------------

    /**
     * Brings the contacts in line with the server's `contacts`: who is paired, blocked, since when, with how many
     * devices, and whether the server lists another identity key than the one trusted.
     */
    suspend fun refreshContacts() {
        if (device() == null) return
        val answer = try {
            api.contacts()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val listed = answer.contacts.associateBy { it.user }
        for (contact in store.contacts()) {
            val server = listed[contact.user]
            if (server == null) {
                // Unpaired by the other side (or removed); one still waiting stays for the week a pairing may wait.
                if (contact.paired || now() - contact.addedAt > 7.days.inWholeMilliseconds) forgetContact(contact.user)
                continue
            }
            val serverKey = server.identityKey?.let(Base64::encode)
            store.putContact(
                contact.copy(
                    displayName = server.displayName ?: contact.displayName,
                    paired = true,
                    since = server.since,
                    blocked = server.blocked,
                    devices = server.devices.size,
                    changedKey = serverKey?.takeIf { it != contact.identityKey } ?: contact.changedKey?.takeIf { serverKey == null },
                )
            )
        }
        // Paired elsewhere (or this phone's store is new): trusted from the server until the safety number is compared.
        for (server in answer.contacts) {
            if (store.contact(server.user) != null) continue
            val key = server.identityKey ?: continue
            engine.trustIdentity(server.user, key)
            store.putContact(
                Contact(
                    user = server.user, displayName = server.displayName, identityKey = Base64.encode(key), paired = true, since = server.since,
                    blocked = server.blocked, devices = server.devices.size, verified = false, addedAt = now(),
                )
            )
        }
        publish()
    }

    suspend fun unpair(user: String) {
        api.unpair(user)
        forgetContact(user)
        publish()
    }

    suspend fun setBlocked(user: String, blocked: Boolean) {
        if (blocked) api.block(user) else api.unblock(user)
        store.contact(user)?.let { store.putContact(it.copy(blocked = blocked)) }
        publish()
    }

    /** Trusts the new identity key the server announced for [user]; the safety number must be compared again. */
    fun acceptKeyChange(user: String) {
        val contact = store.contact(user) ?: return
        val key = contact.changedKey ?: return
        engine.forget(user)
        engine.trustIdentity(user, Base64.decode(key))
        store.putContact(contact.copy(identityKey = key, changedKey = null, verified = false))
        publish()
        syncContact(user)
    }

    fun safetyNumber(user: String): SafetyNumber? {
        val self = device() ?: return null
        val contact = store.contact(user) ?: return null
        return engine.safetyNumber(self.user, user, Base64.decode(contact.identityKey))
    }

    /** Compares their safety number code with ours; marks the contact verified when they match. */
    fun verifyScanned(user: String, scanned: ByteArray): Boolean {
        val self = device() ?: return false
        val contact = store.contact(user) ?: return false
        val matches = try {
            engine.matchesScanned(self.user, user, Base64.decode(contact.identityKey), scanned)
        } catch (_: Exception) {
            false
        }
        if (matches) setVerified(user, true)
        return matches
    }

    /** Their email as this user knows it ([email] blank: none), on all of this user's phones. */
    fun setEmail(user: String, email: String) {
        store.contact(user)?.let { store.putContact(it.copy(email = email.trim().take(254).ifBlank { null })) }
        publish()
        syncContact(user)
    }

    val myEmail: String? get() = settings.myEmail

    /** How the app refers to this user (Gender), shared with contacts. */
    val myGender: Int get() = settings.myGender

    fun setMyGender(gender: Int) {
        if (gender == settings.myGender) return
        settings.myGender = gender
        shareProfile()
    }

    /** This user's own email ([email] blank: none), shared with every paired contact (ProfileContent). */
    fun setMyEmail(email: String) {
        settings.myEmail = email.trim().take(254).ifBlank { null }
        shareProfile()
    }

    /**
     * Tells each paired contact this user's email, gender and picture (also when the app opens, for contacts paired
     * since); each group gets the picture. The picture itself goes only where its id wasn't sent yet.
     */
    fun shareProfile() {
        val email = settings.myEmail.orEmpty()
        val contacts = store.contacts().filter { it.paired && !it.blocked }
        val inGroups = store.groups().filter { !it.gone && it.members.isNotEmpty() }
        if (contacts.isEmpty() && inGroups.isEmpty()) return
        scope.launch {
            val id = store.pictureId("") ?: ProfileContent.NO_PICTURE
            val bytes = store.picture("")
            fun picture(chat: String) = if (bytes != null && id != ProfileContent.NO_PICTURE && store.pictureSent(chat) != id) bytes else ByteArray(0)
            val faces = store.faceCodes("").mapNotNull { code -> store.face("", code)?.let { FaceImage(code, it) } }
            fun faces(chat: String) = if (picture(chat).isNotEmpty()) faces else emptyList()
            for (c in contacts) {
                try {
                    sender.send(c.user, Content(profile = ProfileContent(email, settings.myGender, id, picture(c.user), faces = faces(c.user), faceCount = faces.size)), pushKind = PushKind.NONE)
                    store.putPictureSent(c.user, id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            for (g in inGroups) {
                if (store.pictureSent(g.chat) == id) continue
                try {
                    groups.send(g.id, Content(profile = ProfileContent(pictureId = id, picture = picture(g.chat), faces = faces(g.chat), faceCount = faces.size)), PushKind.NONE)
                    store.putPictureSent(g.chat, id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    /** [user]'s picture (this user's own: ""), a small WebP with a clear background; null: none. */
    fun picture(user: String): ByteArray? = store.picture(user)

    /** This user's picture ([image] null: none), shown on their contacts' and groups' phones too, end to end. */
    fun setMyPicture(image: ByteArray?, faces: Map<Int, ByteArray> = emptyMap()) {
        if (image == null || image.isEmpty()) store.removePicture("")
        else store.putPicture("", random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }, image.copyOf(), faces)
        publish()
        shareProfile()
    }

    /** [user]'s face in expression [code] (this user's own: ""); null: none. */
    fun face(user: String, code: Int): ByteArray? = store.face(user, code)

    /** The expressions [user] has faces for (this user's own: ""). */
    fun faceCodes(user: String): List<Int> = store.faceCodes(user)

    /** Sends this user's face [code] (an Expression code) to [user] as a message; older apps see [fallback]. */
    suspend fun sendFace(user: String, code: Int, fallback: String): TextMessage {
        val message = TextMessage(newId(), user, outgoing = true, body = fallback, at = now(), status = MessageStatus.PENDING, face = code + 1)
        store.putMessage(message)
        publish()
        return deliver(message)
    }

    /** Reacts to message [id] with this user's face [code] (an Expression code); null takes the reaction back. */
    suspend fun react(id: String, code: Int?) {
        val message = store.message(id) ?: return
        val face = code?.plus(1) ?: 0
        store.putMessage(message.copy(reactions = if (face == 0) message.reactions - "" else message.reactions + ("" to face)))
        publish()
        val content = Content(reaction = ReactionContent(id, face))
        try {
            Group.idOf(message.user)?.let { groups.send(it, content, PushKind.SYNC) } ?: sender.send(message.user, content, pushKind = PushKind.SYNC)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** A reaction from [author] (null: this user, from another of their phones) to a message in [chat]. */
    private fun takeReaction(chat: String, author: String?, reaction: ReactionContent) {
        val message = store.message(reaction.id)?.takeIf { it.user == chat } ?: return
        val who = author ?: ""
        store.putMessage(message.copy(reactions = if (reaction.face == 0) message.reactions - who else message.reactions + (who to reaction.face)))
        publish()
    }

    /**
     * [user]'s picture as their phone said ([ProfileContent]): kept, gone, or unchanged. [contact]: they are a paired
     * contact, so a picture named without its bytes that this phone doesn't have is asked for again.
     */
    private fun takePicture(user: String, profile: ProfileContent, contact: Boolean) {
        val id = profile.pictureId
        when {
            id.isEmpty() -> return
            id == ProfileContent.NO_PICTURE -> if (store.pictureId(user) != null) store.removePicture(user) else return
            profile.picture.isNotEmpty() && profile.picture.size <= MAX_PICTURE_BYTES -> if (store.pictureId(user) != id || store.faceCodes(user).size < profile.faces.size) {
                val faces = profile.faces.filter { it.image.isNotEmpty() && it.image.size <= MAX_PICTURE_BYTES && com.chencohen.ptt.face.Expression.of(it.code) != null }.take(12)
                store.putPicture(user, id, profile.picture, faces.associate { it.code to it.image })
            } else return
            (store.pictureId(user) != id || store.faceCodes(user).size < profile.faceCount) && contact -> {
                scope.launch {
                    try {
                        sender.send(user, Content(profile = ProfileContent(needPicture = true)), pushKind = PushKind.NONE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                return
            }
            else -> return
        }
        publish()
    }

    /** This user's own name for [user] ([name] blank: back to theirs), on all of this user's phones. */
    fun rename(user: String, name: String) {
        store.contact(user)?.let { store.putContact(it.copy(nickname = name.trim().take(60).ifBlank { null })) }
        publish()
        syncContact(user)
    }

    fun setVerified(user: String, verified: Boolean) {
        store.contact(user)?.let { store.putContact(it.copy(verified = verified)) }
        publish()
        syncContact(user)
    }

    private fun forgetContact(user: String) {
        store.removeContact(user)
        store.removeRequest(user)
        store.removeMessagesOf(user)
        engine.forget(user)
        // Still in a group with them: the key the owner named stays trusted, for the sender keys.
        store.groups().firstNotNullOfOrNull { g -> g.member(user)?.takeIf { !g.gone } }?.let { engine.trustIdentity(user, Base64.decode(it.identityKey)) }
    }

    // --- Messages ------------------------------------------------------------------------------------------------

    /**
     * Sends [body] to [user], encrypted end to end. It is kept first ([MessageStatus.PENDING]): when the phone isn't
     * connected it waits in the outbox and goes when it is ([flushOutbox]).
     */
    suspend fun sendText(user: String, body: String): TextMessage {
        val message = TextMessage(newId(), user, outgoing = true, body = body, at = now(), status = MessageStatus.PENDING)
        store.putMessage(message)
        publish()
        return deliver(message)
    }

    /** Sends one kept text; answers it with its new status. */
    private suspend fun deliver(message: TextMessage): TextMessage {
        if (message.clip != null) {
            talk.deliverVoice(message.id)
            return store.message(message.id) ?: message
        }
        val status = try {
            val text = Content(text = TextContent(message.body, message.at, message.id, face = message.face))
            val group = Group.idOf(message.user)
            if (group != null) groups.send(group, text, PushKind.MESSAGE) else sender.send(message.user, text, pushKind = PushKind.MESSAGE)
            MessageStatus.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.chencohen.ptt.relay.SocketUnavailableException) {
            MessageStatus.PENDING
        } catch (e: GroupUnavailableException) {
            MessageStatus.FAILED
        } catch (e: SendRefusedException) {
            if (e.status == com.chencohen.ptt.relay.SendStatus.NOT_MEMBER || e.toPairingError() != PairingError.UNREACHABLE) MessageStatus.FAILED else MessageStatus.PENDING
        } catch (e: Exception) {
            // Refused (blocked, unpaired), or no session can be made (their key changed): not tried again by itself.
            if (e.toPairingError() == PairingError.UNREACHABLE) MessageStatus.PENDING else MessageStatus.FAILED
        }
        // A receipt may have come first.
        val now = store.message(message.id) ?: message
        val updated = now.copy(status = if (now.status.ordinal > status.ordinal && status != MessageStatus.FAILED) now.status else status)
        store.putMessage(updated)
        publish()
        return updated
    }

    /** Sends what waits in the outbox, oldest first; once connected. */
    suspend fun flushOutbox() {
        for (message in store.messages().filter { it.outgoing && it.status == MessageStatus.PENDING }) {
            if (deliver(message).status == MessageStatus.PENDING) return
        }
    }

    /** Tries a text that failed again (the user's Retry). */
    suspend fun retry(id: String) {
        val message = store.message(id)?.takeIf { it.outgoing && it.status == MessageStatus.FAILED } ?: return
        deliver(message.copy(status = MessageStatus.PENDING))
    }

    /**
     * The conversation with [user] is on screen: its texts are read. A read receipt goes to them (and, as a copy, to
     * this user's other phones, which take their notifications down).
     */
    suspend fun markRead(user: String) {
        val unread = store.messagesWith(user).filter { !it.outgoing && !it.read }
        if (unread.isEmpty()) return
        unread.forEach { store.putMessage(it.copy(read = true)) }
        publish()
        notifier?.read(user)
        // In a group: one receipt to the members, end to end, no push (their circles; 2026-11-23, hen).
        Group.idOf(user)?.let { id ->
            try {
                groups.send(id, Content(receipt = ReceiptContent(true, unread.map { it.id })), PushKind.NONE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            return
        }
        sendReceipt(user, read = true, unread.map { it.id })
    }

    /** Read receipts that couldn't go yet (not connected), by user; sent once connected. */
    private val pendingReads = mutableMapOf<String, MutableSet<String>>()

    private suspend fun sendReceipt(user: String, read: Boolean, ids: List<String>) {
        try {
            sender.send(user, Content(receipt = ReceiptContent(read, ids)), pushKind = PushKind.SYNC)
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.chencohen.ptt.relay.SocketUnavailableException) {
            // A read receipt waits for the connection (a notification's Mark as read); a delivered one is moot by then.
            if (read) pendingReads.getOrPut(user) { mutableSetOf() } += ids
        } catch (_: Exception) {
            // Refused: they see "sent" instead of "delivered" or "read".
        }
    }

    private suspend fun sendPendingReads() {
        val waiting = pendingReads.toMap()
        pendingReads.clear()
        waiting.forEach { (user, ids) -> sendReceipt(user, read = true, ids.toList()) }
    }

    /** This user is typing to [user] (or stopped): sent with no wake-up, and only when connected. */
    private var hereChat: String? = null
    private val hereFrom = mutableMapOf<String, String>()
    private var hereJob: kotlinx.coroutines.Job? = null

    /**
     * This phone's Talk screen or reply bubble for [chat] is open (null: closed): the other user is told, end to end,
     * again every 20 s while it stays open, so their phone can offer a quick video call (2026-11-22, hen). One-to-one
     * chats only.
     */
    fun setHere(chat: String?, from: String = HERE_SCREEN) {
        // A Talk screen and the bubble each say what they show; the screen wins while both do.
        if (chat == null) hereFrom.remove(from) else hereFrom[from] = chat
        val target = (hereFrom[HERE_SCREEN] ?: hereFrom[HERE_BUBBLE])?.takeIf { Group.idOf(it) == null }
        if (target == hereChat) return
        val old = hereChat
        hereChat = target
        hereJob?.cancel()
        if (old != null) scope.launch { sendHere(old, false) }
        if (target != null) {
            hereJob = scope.launch {
                while (true) {
                    sendHere(target, true)
                    kotlinx.coroutines.delay(HERE_EVERY)
                }
            }
        }
    }

    private suspend fun sendHere(user: String, open: Boolean) {
        if (socket.state.value !is SocketState.Live) return
        val contact = store.contact(user)?.takeIf { it.paired && !it.blocked } ?: return
        try {
            sender.send(contact.user, Content(here = HereContent(open)), pushKind = PushKind.NONE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun sendTyping(user: String, typing: Boolean) {
        // Writing to them means what they wrote was read (2026-11-24, hen): the receipt goes first, so their read
        // circle moves to the end before "typing…" or the draft shows.
        if (typing) markRead(user)
        if (socket.state.value !is SocketState.Live) return
        Group.idOf(user)?.let { id ->
            try {
                groups.send(id, Content(typing = TypingContent(typing)), PushKind.NONE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            return
        }
        val contact = store.contact(user)?.takeIf { it.paired && !it.blocked } ?: return
        try {
            sender.send(contact.user, Content(typing = TypingContent(typing)), pushKind = PushKind.NONE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun withDraft(drafts: Map<String, Draft>, key: String, typing: Boolean, draft: String?, selStart: Int = -1, selEnd: Int = -1): Map<String, Draft> = when {
        !typing || draft?.isEmpty() == true -> drafts - key
        draft != null -> drafts + (key to Draft(draft.take(4_000), now(), selStart, selEnd))
        else -> drafts
    }

    private val draftWanted = HashMap<String, LiveText>()
    private val draftSending = HashSet<String>()

    /**
     * Live typing (when on): [text] is what is in the line to [user] now. Sent end to end, live only (an ephemeral
     * Send: never stored, never pushed), at most every [DRAFT_EVERY] with the latest text; again every few seconds
     * while it stays, so it doesn't fade on the other phone.
     */
    fun draft(user: String, text: String, selStart: Int = -1, selEnd: Int = -1) {
        if (!talkSettings().liveTypingTo(user)) return
        draftWanted[user] = LiveText(text, selStart, selEnd)
        if (!draftSending.add(user)) return
        scope.launch {
            var sent: LiveText? = null
            var sentAt = 0L
            // Read before the draft shows (as for typing).
            if (text.isNotEmpty()) markRead(user)
            try {
                while (true) {
                    val want = draftWanted[user] ?: break
                    if (want != sent || (want.text.isNotEmpty() && now() - sentAt > DRAFT_FOR / 2)) {
                        sendDraft(user, want)
                        sent = want
                        sentAt = now()
                    }
                    if (want.text.isEmpty()) break
                    kotlinx.coroutines.delay(DRAFT_EVERY)
                    if (draftWanted[user] == sent && now() - sentAt < DRAFT_FOR / 2) {
                        // Nothing new: wait for a change (or the keep-alive).
                        kotlinx.coroutines.delay(DRAFT_EVERY)
                    }
                }
            } finally {
                draftSending.remove(user)
                draftWanted.remove(user)
            }
        }
    }

    /** Every contact and group chat this phone has now. */
    private fun knownChats(): Set<String> = (store.contacts().map { it.user } + store.groups().map { it.chat }).toSet()

    /** The line to [user] was sent or left: no more drafts. */
    fun draftDone(user: String) {
        draftWanted.remove(user)
    }

    private suspend fun sendDraft(user: String, live: LiveText) {
        if (socket.state.value !is SocketState.Live) return
        val text = live.text
        val content = Content(typing = TypingContent(typing = text.isNotEmpty(), draft = text, selStart = live.start, selEnd = live.end))
        try {
            Group.idOf(user)?.let { id ->
                groups.send(id, content, PushKind.NONE, ephemeral = true)
                return
            }
            val contact = store.contact(user)?.takeIf { it.paired && !it.blocked } ?: return
            sender.send(contact.user, content, pushKind = PushKind.NONE, ephemeral = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Edits this user's own text [id] to [body] (2026-11-24, hen): here at once ("edited"), then end to end to the chat
     * (its people and this user's other phones), kept and pushed silently like a text. Only texts (no clip, no call).
     */
    suspend fun editText(id: String, body: String): Boolean {
        val message = store.message(id)?.takeIf { it.outgoing && it.clip == null && it.call == null } ?: return false
        val text = body.trim().take(4_000)
        if (text.isEmpty() || text == message.body) {
            editLive(id, null)
            return false
        }
        val at = now()
        store.putMessage(message.copy(body = text, editedAt = at))
        publish()
        editWanted.remove(id)
        val content = Content(edit = EditContent(id, text, at, EditContent.FINAL))
        try {
            val group = Group.idOf(message.user)
            if (group != null) groups.send(group, content, PushKind.SYNC) else sender.send(message.user, content, pushKind = PushKind.SYNC)
            println("TalkEdit: edit of $id sent")
            // And the live view's end (2026-11-24, hen: "Editing…" stayed on their phone after the edit).
            sendEdit(message.user, EditContent(id, editedAt = at, stage = EditContent.CANCEL))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("TalkEdit: edit of $id not sent: $e")
            return false
        }
        return true
    }

    /**
     * Deletes this user's own message [id] for everyone (2026-11-24, hen): gone here (its clip's file too), then end
     * to end to the chat (its people and this user's other phones), kept and pushed silently like a text.
     */
    suspend fun deleteMessage(id: String): Boolean {
        val message = store.message(id)?.takeIf { it.outgoing && !it.deleted } ?: return false
        editWanted.remove(id)
        store.removeMessage(message)
        if (message.clip != null) talk.forgetClip(id)
        publish()
        val content = Content(delete = DeleteContent(id))
        try {
            Group.idOf(message.user)?.let { group -> groups.send(group, content, PushKind.SYNC) } ?: sender.send(message.user, content, pushKind = PushKind.SYNC)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        return true
    }

    /** Deletes [ids] for everyone: only this user's own (each as [deleteMessage]); how many went. */
    suspend fun deleteMessages(ids: List<String>): Int = ids.count { deleteMessage(it) }

    /** Deletes [ids] from this phone only (2026-11-24, hen: "Delete for me"): anyone's, nothing is sent. */
    fun deleteForMe(ids: List<String>): Int {
        var gone = 0
        for (id in ids) {
            val message = store.message(id) ?: continue
            editWanted.remove(id)
            store.removeMessage(message)
            if (message.clip != null) talk.forgetClip(id)
            gone++
        }
        if (gone > 0) publish()
        return gone
    }

    /** A delete from [author] in [chat] (as [takeEdit]): this user's own on another phone goes; theirs leaves a mark. */
    private fun takeDelete(chat: String, author: String?, mine: Boolean, delete: DeleteContent) {
        val message = store.message(delete.id)?.takeIf {
            it.user == chat && it.outgoing == mine && !it.deleted && (author == null || mine || it.author == author)
        } ?: return
        if (message.clip != null) talk.forgetClip(message.id)
        if (mine) store.removeMessage(message) else store.putMessage(message.copy(body = "", clip = null, deleted = true, editedAt = null))
        _state.update { it.copy(liveEdits = it.liveEdits - delete.id) }
        publish()
    }

    private val editWanted = HashMap<String, LiveText?>()
    private val editSending = HashSet<String>()

    /**
     * Editing [id] live: [text] as it stands now, null when the edit ended unchanged (cancel). Live only, as live
     * typing: the letters go when Live typing is on for the chat, else only that it is being edited.
     */
    fun editLive(id: String, text: String?, selStart: Int = -1, selEnd: Int = -1) {
        val message = store.message(id)?.takeIf { it.outgoing && it.clip == null && it.call == null } ?: return
        if (text == null) {
            editWanted.remove(id)
            scope.launch { sendEdit(message.user, EditContent(id, editedAt = now(), stage = EditContent.CANCEL)) }
            return
        }
        val shown = talkSettings().liveTypingTo(message.user)
        // The caret goes with the letters only (Live typing on).
        editWanted[id] = if (shown) LiveText(text, selStart, selEnd) else LiveText("")
        if (!editSending.add(id)) return
        scope.launch {
            var sent: LiveText? = null
            var sentAt = 0L
            try {
                while (true) {
                    val want = editWanted[id] ?: break
                    if (want != sent || now() - sentAt > DRAFT_FOR / 2) {
                        // Stamped with when it was written: one that arrives after the saved edit is too old to show.
                        sendEdit(message.user, EditContent(id, want.text, now(), stage = EditContent.LIVE, hidden = !shown, selStart = want.start, selEnd = want.end))
                        sent = want
                        sentAt = now()
                    }
                    kotlinx.coroutines.delay(DRAFT_EVERY)
                    if (editWanted[id] == sent && now() - sentAt < DRAFT_FOR / 2) kotlinx.coroutines.delay(DRAFT_EVERY)
                }
            } finally {
                editSending.remove(id)
            }
        }
    }

    private suspend fun sendEdit(user: String, edit: EditContent) {
        if (socket.state.value !is SocketState.Live) {
            println("TalkEdit: live edit of ${edit.id} not sent: not connected")
            return
        }
        val content = Content(edit = edit)
        try {
            Group.idOf(user)?.let { id ->
                groups.send(id, content, PushKind.NONE, ephemeral = true)
                return
            }
            val contact = store.contact(user)?.takeIf { it.paired && !it.blocked } ?: return
            sender.send(contact.user, content, pushKind = PushKind.NONE, ephemeral = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("TalkEdit: live edit of ${edit.id} not sent: $e")
        }
    }

    /**
     * An edit came from [author] (in [chat]): applied only to a text of theirs ([mine]: this user's own, from another
     * of their phones) that this phone has; any other id is ignored.
     */
    /** For each message, when the last saved edit or live view's end was written (the author's clock). */
    private val editEnded = HashMap<String, Long>()

    internal fun takeEdit(chat: String, author: String?, mine: Boolean, edit: EditContent) {
        val found = store.message(edit.id)
        val message = found?.takeIf {
            it.user == chat && it.outgoing == mine && it.clip == null && it.call == null && (author == null || mine || it.author == author)
        }
        println("TalkEdit: stage ${edit.stage} for ${edit.id} from $chat: ${if (message != null) "applied" else if (found == null) "no such message" else "not theirs"}")
        if (message == null) return
        when (edit.stage) {
            EditContent.FINAL -> {
                if (edit.body.isBlank()) return
                editEnded[edit.id] = maxOf(editEnded[edit.id] ?: 0L, edit.editedAt)
                if ((message.editedAt ?: 0L) <= edit.editedAt) store.putMessage(message.copy(body = edit.body.take(4_000), editedAt = edit.editedAt))
                _state.update { it.copy(liveEdits = it.liveEdits - edit.id) }
                publish()
            }
            EditContent.LIVE -> {
                if (mine) return
                // Written before the edit was saved or ended (it came late): nothing to show.
                if (edit.editedAt != 0L && edit.editedAt < (editEnded[edit.id] ?: 0L)) return
                val at = now()
                _state.update { it.copy(liveEdits = it.liveEdits + (edit.id to LiveEdit(if (edit.hidden) null else edit.body.take(4_000), at, if (edit.hidden) -1 else edit.selStart, if (edit.hidden) -1 else edit.selEnd))) }
                scope.launch {
                    kotlinx.coroutines.delay(Draft.FRESH_MS)
                    _state.update { s -> if (s.liveEdits[edit.id]?.at == at) s.copy(liveEdits = s.liveEdits - edit.id) else s }
                }
            }
            else -> {
                if (edit.editedAt != 0L) editEnded[edit.id] = maxOf(editEnded[edit.id] ?: 0L, edit.editedAt)
                _state.update { it.copy(liveEdits = it.liveEdits - edit.id) }
            }
        }
    }

    private fun newId(): String = random.nextBytes(12).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** One envelope from /ws; true once it is handled (stored, or dropped for good), so it is acknowledged. */
    suspend fun receive(envelope: Envelope): Boolean {
        when (envelope.type) {
            EnvelopeType.PREKEY_MESSAGE, EnvelopeType.MESSAGE -> receiveMessage(envelope)
            EnvelopeType.IDENTITY_KEY_CHANGED -> {
                val contact = store.contact(envelope.senderUser)
                val key = Base64.encode(envelope.ciphertext)
                if (contact != null && key != contact.identityKey) {
                    store.putContact(contact.copy(changedKey = key, verified = false))
                }
            }
            EnvelopeType.PAIRED -> {
                markPaired(envelope.senderUser)
                refreshContacts()
            }
            EnvelopeType.PROVISIONING -> receiveProvisioning(envelope)
            EnvelopeType.SENDER_KEY_MESSAGE -> groups.receiveMessage(envelope)
            // Not awaited: refreshing may send the owner's changes, whose SendResults come through this read loop.
            EnvelopeType.GROUP_CHANGED -> scope.launch { groups.refresh() }
            else -> Unit
        }
        publish()
        return true
    }

    private fun receiveMessage(envelope: Envelope) {
        val self = device() ?: return
        val from = DeviceAddress(envelope.senderUser, envelope.senderDevice)
        val decrypted = try {
            engine.decrypt(from, envelope.type, envelope.ciphertext)
        } catch (_: CryptoException) {
            // Can't be opened now or ever (corrupt, replayed, an untrusted key): dropped, so it isn't sent again.
            return
        }
        val content = ContentCodec.decode(decrypted.plaintext) ?: return
        ContentCodec.note(envelope.senderUser, content)
        content.groupChange?.let { return groups.receiveChange(envelope.senderUser, it) }
        content.senderKey?.let { return groups.receiveSenderKey(from, decrypted.senderIdentityKey, it) }
        if (envelope.senderUser == self.user) {
            content.contact?.let { return takeContactSync(it) }
            // A copy of what another phone of this user sent.
            val to = content.destination.takeIf { it.isNotEmpty() } ?: return
            content.text?.let { text ->
                val id = text.id.ifEmpty { envelope.id }
                if (store.message(id) == null) store.putMessage(TextMessage(id, to, outgoing = true, body = text.body, at = text.sentAt, face = text.face))
            }
            content.reaction?.let { takeReaction(to, null, it) }
            content.edit?.let { takeEdit(to, null, mine = true, it) }
            content.delete?.let { takeDelete(to, null, mine = true, it) }
            content.clip?.let { talk.receiveClip(to, self.user, it, envelope.serverTimestamp, outgoingCopy = true) }
            content.talkKey?.let { live.takeKey("${self.user}.${envelope.senderDevice}", it) }
            content.call?.let { calls.receive(to, self.user, it, envelope.serverTimestamp, ownCopy = true) }
            // Read on another phone of this user: read here too, and its notification goes.
            content.receipt?.takeIf { it.read }?.let { receipt ->
                receipt.ids.mapNotNull(store::message).filter { !it.outgoing && !it.read }.forEach { store.putMessage(it.copy(read = true)) }
                notifier?.read(to)
            }
            return
        }
        content.pairRequest?.let { return pairRequest(envelope.senderUser, it, decrypted.senderIdentityKey) }
        val contact = store.contact(envelope.senderUser)?.takeIf { it.paired && !it.blocked } ?: return
        content.text?.let { text ->
            val id = text.id.ifEmpty { envelope.id }
            if (store.message(id) != null) return
            val read = readsAtOnce(contact.user)
            store.putMessage(TextMessage(id, contact.user, outgoing = false, body = text.body, at = envelope.serverTimestamp, read = read, face = text.face))
            _state.update { it.copy(typing = it.typing - contact.user, drafts = it.drafts - contact.user) }
            scope.launch {
                sendReceipt(contact.user, read = false, listOf(id))
                if (read) sendReceipt(contact.user, read = true, listOf(id))
            }
            if (!read) notifier?.incoming(contact, store.messagesWith(contact.user).filter { !it.outgoing && !it.read })
        }
        content.edit?.let { takeEdit(contact.user, null, mine = false, it) }
        content.delete?.let { takeDelete(contact.user, null, mine = false, it) }
        content.talkKey?.let { live.takeKey("${contact.user}.${envelope.senderDevice}", it) }
        content.call?.let { calls.receive(contact.user, contact.user, it, envelope.serverTimestamp, ownCopy = false) }
        content.clip?.let { clip ->
            val fresh = store.message(clip.id) == null
            talk.receiveClip(contact.user, contact.user, clip, envelope.serverTimestamp, outgoingCopy = false)
            if (fresh) {
                scope.launch { sendReceipt(contact.user, read = false, listOf(clip.id)) }
                val read = readsAtOnce(contact.user)
                if (read) store.message(clip.id)?.let { store.putMessage(it.copy(read = true)) }
                else if (clip.kind == ClipContent.VOICE) notifier?.incoming(contact, store.messagesWith(contact.user).filter { !it.outgoing && !it.read })
            }
        }
        content.receipt?.let { receipt ->
            val status = if (receipt.read) MessageStatus.READ else MessageStatus.DELIVERED
            for (id in receipt.ids) {
                val message = store.message(id)?.takeIf { it.outgoing && it.user == contact.user } ?: continue
                if (message.status.ordinal < status.ordinal || message.status == MessageStatus.PENDING) {
                    store.putMessage(
                        message.copy(
                            status = status,
                            readAt = if (status == MessageStatus.READ) now() else message.readAt,
                            deliveredAt = message.deliveredAt ?: now(),
                        ),
                    )
                }
            }
        }
        content.typing?.let { typing ->
            _state.update {
                it.copy(
                    typing = if (typing.typing) it.typing + (contact.user to now() + TYPING_FOR) else it.typing - contact.user,
                    drafts = withDraft(it.drafts, contact.user, typing.typing, typing.draft, typing.selStart, typing.selEnd),
                )
            }
        }
        content.reaction?.let { takeReaction(contact.user, contact.user, it) }
        content.profile?.let { profile ->
            if (profile.needPicture) {
                // They don't have this user's picture after all: it goes again.
                store.putPictureSent(contact.user, "")
                shareProfile()
                return@let
            }
            takePicture(contact.user, profile, contact = true)
            store.contact(contact.user)?.let { c ->
                val email = profile.email.trim().take(254).ifBlank { null }
                val gender = profile.gender.takeIf { it == Gender.MALE || it == Gender.FEMALE } ?: Gender.NOT_SET
                if (c.theirEmail != email || c.theirGender != gender) {
                    store.putContact(c.copy(theirEmail = email, theirGender = gender))
                    publish()
                }
            }
        }
        content.viewing?.let { takeViewing(contact.user, it.open) }
        content.liveWaiting?.let { takeLiveWaiting(contact.user, it.open) }
        if (!contact.blocked) content.warm?.let { takeWarm(contact.user, it.open) }
        if (!contact.blocked) content.roomCall?.let { takeRoomCall(contact.user, it.open) }
        content.here?.let { here ->
            // From when the server took it: one that waited (this phone was away) is already over.
            val until = minOf(now(), envelope.serverTimestamp.takeIf { it > 0 } ?: now()) + HERE_FOR
            _state.update { it.copy(here = if (here.open && until > now()) it.here + (contact.user to until) else it.here - contact.user) }
        }
    }

    /**
     * A pair request: kept for the user to confirm when it carries the secret of one of this phone's codes, names its
     * sender, and holds the identity key its session was opened with. Anything else is dropped.
     */
    private fun pairRequest(sender: String, request: PairRequestContent, sessionKey: ByteArray) {
        val secret = PairCode.base64Url.encode(request.secret)
        val invite = store.invites().firstOrNull { it.secret == secret && now() <= it.keptUntil() }
        if (invite == null || request.user != sender || !request.identityKey.contentEquals(sessionKey)) {
            if (store.contact(sender) == null && !groups.knows(sender)) engine.forget(sender)
            return
        }
        store.removeInvite(invite)
        engine.trustIdentity(sender, sessionKey)
        store.putRequest(PairRequest(sender, Base64.encode(sessionKey), now(), viaLink = invite.viaLink))
        // Inviting them was the decision: an invite's request is confirmed without asking again.
        if (invite.viaLink) {
            scope.launch {
                try {
                    accept(sender)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Left as a request: the user confirms it by hand.
                }
            }
        }
    }

    /** Publishes what the store holds now. */
    fun publish() {
        val messages = store.messages()
        _state.update { state ->
            state.copy(
                contacts = store.contacts().sortedBy { it.user.lowercase() },
                requests = store.requests().sortedBy { it.receivedAt },
                lastMessages = messages.groupBy { it.user }.mapValues { (_, list) -> list.last() },
                conversations = messages.groupBy { it.user },
                unread = messages.filter { !it.outgoing && !it.read }.groupingBy { it.user }.eachCount(),
                groups = store.groups().sortedBy { it.createdAt },
                pictures = store.pictureIds(),
            )
        }
    }

    companion object {
        /** A woken phone stays connected this long after the last burst it played, so a reply can follow. */
        const val TALK_REPLY_WAIT = 20_000L

        /** How often an open chat says so again (Content.warm), while the app is in front. */
        const val WARM_REFRESH = 5 * 60_000L

        /** The longest a talk wake-up keeps the connection. */
        const val TALK_WAKE_LIMIT = 120_000L

        /** How long "typing…" shows after the last typing message, unless a text or a stop comes first. */
        const val TYPING_FOR = 8_000L

        /** The largest picture taken from a contact or group member (pictures are made at about 20 KB). */
        const val MAX_PICTURE_BYTES = 96_000

        /** Live typing: at most one draft this often (about ten a second), the newest text. */
        const val DRAFT_EVERY = 100L

        /** A live draft fades this long after its last word. */
        const val DRAFT_FOR = Draft.FRESH_MS

        /** The key in [MessagingState.typing] of [author] typing in a group's chat. */
        /** "Here" lasts this long after the last word of it; it is said again every HERE_EVERY. */
        const val HERE_FOR = 30_000L
        const val HERE_SCREEN = "screen"
        const val HERE_BUBBLE = "bubble"
        const val HERE_EVERY = 20_000L

        fun typingKey(chat: String, author: String): String = "$chat/$author"

        /** How long past its end a Link a phone code still answers: the new phone may connect a while after enrolling. */
        private const val LINK_GRACE = 60 * 60_000L

        /** `wss://host:port/ws` for the GraphQL endpoint `https://host:port/query`; null before enrolling. */
        fun wsUrl(serverUrl: String): String? {
            if (!serverUrl.startsWith("https://")) return null
            val origin = serverUrl.removePrefix("https://").substringBefore('/')
            return "wss://$origin/ws"
        }
    }
}

/** [Messaging.newInvite]'s answer. */
internal class NewInvite(val link: InviteLink, val expiresAt: Long, val passcode: String, val id: String = "")

@OptIn(kotlin.time.ExperimentalTime::class)
internal fun parseTime(rfc3339: String): Long? = runCatching { kotlin.time.Instant.parse(rfc3339).toEpochMilliseconds() }.getOrNull()

private fun <T> CoroutineScope.launchCollect(flow: StateFlow<T>, block: (T) -> Unit) {
    launch { flow.collect { block(it) } }
}

/**
 * Talk audio through LiveKit rooms (phase 5b), off from 1.4.0 (2026-11-22, hen: bursts started late and ended early),
 * on again since 2026-11-25 (hen) the other way: the microphone's track is published once, muted, while the chat is
 * on screen, unmuted at the press and muted 300 ms after the release; the clip keeps recording the same samples (with
 * the 300 ms before the press) and stays the record; the listener plays the room's audio only once it is unmuted and
 * decrypting, else the clip. What follows is the 1.4.0 note.
 * In a room a press published a new microphone track, which took a moment to start, so the first words were lost,
 * and the release took it down at once, so the listener lost the end still on its way; the listener, hearing it
 * live, didn't play the clip. Now every burst goes by the clip path: the app's own microphone, kept open on the Talk
 * screens with the last 300 ms before the press, recorded from the press and sent once the floor is granted, and
 * the listener plays all of it to the end.
 */
internal const val LIVE_TALK_AUDIO = true

/**
 * Push-to-video's pictures through the conversation's LiveKit room (2026-11-24, hen: "do it"): the room is joined as
 * for live talk, but carries only the camera; the voice stays on the clip path (LIVE_TALK_AUDIO).
 */
internal const val LIVE_TALK_VIDEO = true

/** The silent warm-up's Android side (2026-11-24, hen): a quiet foreground service keeps the process while warm. */
interface WarmHost {
    /** [chat] warmed this phone ([on]), or the warm-up ended ([chat] null: all). Shows nothing, lights nothing. */
    fun warm(chat: String?, on: Boolean)
}

/** A call in a chat's talk room (both live became one): [since] when, [video] whether cameras were live. */
data class RoomCall(val chat: String, val since: Long, val video: Boolean, val microphone: Boolean = true, val camera: Boolean = video)
