package com.chencohen.ptt.ui.home

import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.edit
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohen.ptt.R
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.chencohen.ptt.messaging.Contact
import com.chencohen.ptt.messaging.ContactResult
import com.chencohen.ptt.messaging.KeysState
import com.chencohen.ptt.messaging.MessagingController
import com.chencohen.ptt.messaging.MessagingState
import com.chencohen.ptt.messaging.PairRequest
import com.chencohen.ptt.messaging.PairingController
import com.chencohen.ptt.messaging.TextMessage
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.ui.contacts.GroupChat
import com.chencohen.ptt.ui.groups.AddMembersScreen
import com.chencohen.ptt.ui.groups.GroupActions
import com.chencohen.ptt.ui.groups.GroupRow
import com.chencohen.ptt.ui.groups.GroupScreen
import com.chencohen.ptt.ui.groups.NewGroupScreen
import com.chencohen.ptt.ui.groups.groupName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import com.chencohen.ptt.ui.contacts.InviteActions
import com.chencohen.ptt.ui.contacts.InviteScreen
import com.chencohen.ptt.ui.contacts.ConversationActions
import com.chencohen.ptt.ui.contacts.ConversationHeader
import com.chencohen.ptt.ui.contacts.ConversationScreen
import com.chencohen.ptt.ui.settings.DevicesScreen
import com.chencohen.ptt.ui.settings.DevicesView
import com.chencohen.ptt.ui.settings.LinkPhoneScreen
import com.chencohen.ptt.ui.settings.LinkPhoneView
import com.chencohen.ptt.ui.contacts.AddContactActions
import com.chencohen.ptt.ui.contacts.AddContactScreen
import com.chencohen.ptt.ui.contacts.ContactActions
import com.chencohen.ptt.ui.contacts.ContactRow
import com.chencohen.ptt.ui.contacts.ContactScreen
import com.chencohen.ptt.ui.contacts.NewChatScreen
import com.chencohen.ptt.ui.contacts.SafetyNumberActions
import com.chencohen.ptt.ui.contacts.SafetyNumberLoad
import com.chencohen.ptt.ui.contacts.SafetyNumberScreen
import com.chencohen.ptt.ui.scanQrCode
import com.chencohen.ptt.ui.style.PageNote
import com.chencohen.ptt.connection.ConnectionController
import com.chencohen.ptt.connection.ServerHealth
import com.chencohen.ptt.settings.SettingsController
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.settings.SettingsScreen
import com.chencohen.ptt.ui.style.EmptyState
import com.chencohen.ptt.ui.style.HomeCard
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryType
import com.chencohen.ptt.ui.style.LocalMessages
import com.chencohen.ptt.ui.style.MessageHost
import com.chencohen.ptt.ui.style.Messages
import com.chencohen.ptt.ui.style.OfflinePill
import com.chencohen.ptt.ui.style.SegmentedBar
import com.chencohen.ptt.ui.style.barsRoom
import com.chencohen.ptt.ui.style.navigationBarRoom
import com.chencohen.ptt.ui.style.statusBarRoom

/** The home tabs: Talk (the Quick talk chats, docs/screens/quick-talk.md) first, then Signal's two (docs/screens/chats.md). */
enum class HomeTab { TALK, CHATS, CALLS }

/** What the home screens render: the server's health, who this is (the profile circle's letter), and messaging's. */
data class HomeState(
    val health: ServerHealth,
    val user: String?,
    val messaging: MessagingState = MessagingState(),
    /** A newer release of the app to update to, for the home card. */
    val update: com.chencohen.ptt.update.AppRelease? = null,
)

class HomeActions(
    val openSettings: () -> Unit = {},
    val registerAgain: () -> Unit = {},
    val newChat: () -> Unit = {},
    val reviewRequest: (String) -> Unit = {},
    val openContact: (String) -> Unit = {},
    /** A Chats row's shield (not verified): their safety number. */
    val openSafety: (String) -> Unit = {},
    /** A group invitation's card: the group's page, to accept or decline. */
    val openGroup: (String) -> Unit = {},
    val replaceIdentityKey: () -> Unit = {},
    /** Scan the other phone's Link a phone code (KeysState.OtherIdentity). */
    val linkHere: () -> Unit = {},
    /** The Calls tab: call [chat] again (phase 6). */
    val call: (chat: String, video: Boolean) -> Unit = { _, _ -> },
    /** A chat's name: a contact's, or a group's. */
    val chatName: (String) -> String = { it },
    /** Share a saved call's file. */
    val shareCall: (String) -> Unit = {},
    /** A call the server kept: play it. */
    val playKept: (com.chencohen.ptt.messaging.TextMessage) -> Unit = {},
    /** Push-to-video switched on a Talk screen; the camera warm for a chat (null: none). */
    val setPushVideo: (chat: String, video: Boolean) -> Unit = { _, _ -> },
    /** How the app refers to this user (the card on Chats). */
    val setMyGender: (Int) -> Unit = {},
    val warmCamera: (String?) -> Unit = {},
    /** A Talk page open ("here" to its user). */
    val setHere: (String?) -> Unit = {},
    /** A call the server kept: save it as one file in Downloads. */
    val saveKept: (com.chencohen.ptt.messaging.TextMessage) -> Unit = {},
    /** The Talk tab: hold to talk in a Quick talk chat, and let go. */
    val pressTalk: (String) -> Unit = {},
    val releaseTalk: () -> Unit = {},
    /** The Talk tab's page now on screen, remembered for next time. */
    val quickTalkShown: (String) -> Unit = {},
    /** The Talk tab's picked chat: the chat's own screen, with the faces row as its header and the stage for messages. */
    val talkChat: @Composable (chat: String, header: @Composable () -> Unit, stage: @Composable (live: Boolean, overlay: @Composable () -> Unit) -> Unit) -> Unit = { _, _, _ -> },
    val playClip: (String) -> Unit = {},
    val stopClip: () -> Unit = {},
    /** A text from the Talk tab's open row (it reads the chat too, as answering does). */
    val sendText: (chat: String, body: String) -> Unit = { _, _ -> },
    /** The Talk tab's "+": a chat on it or off (Quick talk). */
    val setQuickTalk: (chat: String, on: Boolean) -> Unit = { _, _ -> },
    val micDenied: () -> Unit = {},
    val warmMicrophone: (Boolean) -> Unit = {},
    /** The home card's Update (docs/design.md, "App updates"). */
    val update: (com.chencohen.ptt.update.AppRelease) -> Unit = {},
)

/**
 * Where home is: the pages over it, last on top. Kept as one string ("settings", "new", "add", "review/<user>",
 * "contact/<user>", "safety/<user>", one per line) so it survives a rotation; user names have no line breaks.
 */
private fun pages(saved: String): List<String> = saved.split('\n').filter { it.isNotEmpty() }

/** Home and the pages over it: Settings, New chat, Add contact, a contact and their safety number. */
@Composable
fun HomeApp(
    connection: ConnectionController,
    settings: SettingsController,
    messaging: MessagingController,
    pairing: PairingController,
    onRegisterAgain: () -> Unit,
    /** An invite link opened on this enrolled phone (MainActivity), until Add contact took it. */
    openedInvite: String? = null,
    onInviteTaken: () -> Unit = {},
    /** A conversation a notification opened (MainActivity), until home showed it. */
    openedChat: String? = null,
    /** Back from this chat goes back to the floating chat it was opened from (MainActivity); false: Back as always. */
    leaveToWindow: (String) -> Boolean = { false },
    onChatTaken: () -> Unit = {},
    /** A call asked from Android Contacts (the user, video). */
    openedCall: Pair<String, Boolean>? = null,
    onCallTaken: () -> Unit = {},
    /** The activity is the small floating call window (picture-in-picture): the call screen shows its videos only. */
    inPip: Boolean = false,
    /** Opened over the lock screen for a burst: a video burst fills the Talk screen. */
    wokeForTalk: Boolean = false,
    /** Settings › Talk changed: kept, and Stay connected's service started or stopped (MainActivity). */
    onTalkSettings: (com.chencohen.ptt.messaging.TalkSettings) -> Unit = { messaging.setTalkSettings(it) },
    /** A talk notification's Play: the clip to play once home shows its chat. */
    openedClip: String? = null,
    /** Pictures, videos or files shared to Talk from another app (MainActivity), until Send to took them. */
    openedShare: List<String>? = null,
    onShareTaken: () -> Unit = {},
) {
    val state by connection.state.collectAsStateWithLifecycle()
    val messagingState by messaging.state.collectAsStateWithLifecycle()
    val updates by com.chencohen.ptt.AppUpdates.state.collectAsStateWithLifecycle()
    val automaticUpdates by com.chencohen.ptt.AppUpdates.automatic.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as com.chencohen.ptt.TalkApp
    var saved by rememberSaveable { mutableStateOf("") }
    val stack = pages(saved)
    fun push(page: String) {
        saved = (stack + page).joinToString("\n")
    }
    fun pop() {
        // Back from a chat opened from the floating chat over another app: the floating chat again, there.
        val only = stack.singleOrNull()
        if (only != null && only.startsWith("chat/") && leaveToWindow(only.removePrefix("chat/"))) return
        saved = stack.dropLast(1).joinToString("\n")
    }
    fun home() {
        saved = ""
    }
    var tab by rememberSaveable { mutableStateOf(HomeTab.TALK) }
    // A kept call being played (its page is "kept/<call>").
    val keptCalls = remember { mutableMapOf<String, com.chencohen.ptt.messaging.TextMessage>() }
    val keptSaveAsked = remember { mutableSetOf<String>() }
    fun playKept(message: com.chencohen.ptt.messaging.TextMessage) {
        keptCalls[message.id] = message
        push("kept/${message.id}")
    }
    fun saveKept(message: com.chencohen.ptt.messaging.TextMessage) {
        keptSaveAsked += message.id
        playKept(message)
    }
    val messages = remember { Messages() }
    val context = LocalContext.current
    val resources = LocalResources.current
    BackHandler(enabled = stack.isNotEmpty()) { pop() }
    // A notification tapped: its conversation, over home.
    LaunchedEffect(openedChat) {
        if (openedChat != null) {
            saved = "chat/$openedChat"
            onChatTaken()
        }
    }
    // Open Talk when someone talks to me, with the app in front: someone's burst switches to that Talk screen.
    val incoming = messagingState.talk.playing?.takeIf { it.live && it.from != state.device?.user }
    LaunchedEffect(incoming?.clip) {
        val chat = incoming?.chat ?: return@LaunchedEffect
        if (messagingState.talkSettings.openOnTalk && stack.lastOrNull() != "chat/$chat") saved = "chat/$chat"
    }
    // Over the lock screen a press asks to unlock first (then hold again to talk).
    fun pressOrUnlock(chat: String) {
        val activity = context as? android.app.Activity
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        if (activity != null && keyguard.isKeyguardLocked) keyguard.requestDismissKeyguard(activity, null) else messaging.pressTalk(chat)
    }
    // Shared to Talk from another app's Share menu (2026-11-28, hen): Send to picks the chat.
    var sharing by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(openedShare) {
        val shared = openedShare ?: return@LaunchedEffect
        onShareTaken()
        if (shared.isEmpty()) return@LaunchedEffect
        sharing = shared
        saved = "share"
    }
    // Pictures picked for a chat, not sent yet: in its writing bubble, the caption written under them (2026-11-28, hen).
    val mediaDrafts = remember { androidx.compose.runtime.mutableStateMapOf<String, List<com.chencohen.ptt.messaging.TextMessage>>() }
    // Crop and focus open (2026-11-28, hen), over everything: the pictures, the first shown, and what Done does.
    var cropping by remember { mutableStateOf<CropSession?>(null) }
    // Bumped when a sent picture's composition changed, so an open viewer reads its message again.
    var recropped by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    fun cropDraft(chat: String, messageId: String, itemId: String) {
        val drafts = mediaDrafts[chat].orEmpty()
        val targets = drafts.flatMap { m -> m.media?.items.orEmpty().filter { it.kind == com.chencohen.ptt.messaging.MediaKind.IMAGE }.map { com.chencohen.ptt.ui.media.CropTarget(m.id, it, messaging.mediaSource(m.id, it.id)) } }
        cropping = CropSession(targets, targets.indexOfFirst { it.messageId == messageId && it.item.id == itemId }.coerceAtLeast(0)) { crops ->
            messaging.recrop(crops) { now ->
                // The draft as it is now: each changed picture with its new previews.
                mediaDrafts[chat]?.let { list ->
                    mediaDrafts[chat] = list.map { m ->
                        val mine = now.filter { it.first == m.id }.associate { it.second.id to it.second }
                        if (mine.isEmpty()) m else m.copy(media = m.media?.copy(items = m.media!!.items.map { mine[it.id] ?: it }))
                    }
                }
            }
        }
    }
    fun cropSent(message: com.chencohen.ptt.messaging.TextMessage, itemId: String) {
        val targets = message.media?.items.orEmpty().filter { it.kind == com.chencohen.ptt.messaging.MediaKind.IMAGE }.map { com.chencohen.ptt.ui.media.CropTarget(message.id, it, messaging.mediaSource(message.id, it.id)) }
        cropping = CropSession(targets, targets.indexOfFirst { it.item.id == itemId }.coerceAtLeast(0)) { crops ->
            messaging.recrop(crops) { now ->
                recropped++
                if (now.size < crops.size) messages.post(resources.getString(R.string.crop_failed))
            }
        }
    }
    // Pictures sent before their caption, by chat: the message whose caption is being written.
    val mediaCaptions = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    fun sendShared(chat: String, uris: List<String>) {
        messaging.draftMedia(chat, uris) { drafts ->
            if (drafts.isEmpty()) messages.post(resources.getString(R.string.share_failed))
            else mediaDrafts[chat] = mediaDrafts[chat].orEmpty() + drafts
        }
    }
    // Held, not read here: each progress step rebuilt the whole app (2026-11-28); the pictures read them as they draw.
    val uploads = messaging.uploads.collectAsStateWithLifecycle()
    val transfers = messaging.transfers.collectAsStateWithLifecycle()
    val encoding = messaging.encoding.collectAsStateWithLifecycle()
    // The phone's pictures and videos, with their place: the sender keeps no copy, so the original is read again from
    // there when someone opens it (asked once, the first time something is shared; it goes either way).
    var askedFor by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    val askMedia = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        askedFor?.let { (chat, uris) -> sendShared(chat, uris) }
        askedFor = null
    }
    fun shareIn(chat: String, uris: List<String>) {
        // Access to all photos (2026-11-28, hen): with only the selected ones, the gallery's pass to a picked picture ends
        // when Talk restarts and its original can't be read again. Asked again at most once a day while it isn't given.
        val missing = com.chencohen.ptt.ui.media.ALL_PHOTOS.filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        val prefs = context.getSharedPreferences("media", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (missing.isEmpty() || now - prefs.getLong("askedAt", 0L) < 24L * 60 * 60 * 1000) return sendShared(chat, uris)
        prefs.edit { putLong("askedAt", now) }
        askedFor = chat to uris
        askMedia.launch(missing.toTypedArray())
    }
    // A talk notification's Play: the burst plays from the chat's history.
    LaunchedEffect(openedClip) { if (openedClip != null) messaging.playClip(openedClip) }
    // Your picture, once after enrolling (or after the update that brought pictures; 2026-11-24, hen), unless an
    // invite or a chat is being opened.
    LaunchedEffect(Unit) {
        if (!com.chencohen.ptt.ui.face.PictureAsked.asked(context) && openedInvite == null && openedChat == null && openedCall == null && stack.isEmpty()) push("welcomepicture")
    }
    // An invite link: Add contact asks "Pair with them?".
    LaunchedEffect(openedInvite) {
        if (openedInvite != null && stack.lastOrNull() != "join") push("join")
    }
    // A contact's name as the user sees it: the name they gave, or their user name.
    fun setNotifyOnly(chat: String, on: Boolean) {
        val talk = messagingState.talkSettings
        messaging.setTalkSettings(talk.copy(notifyOnly = if (on) talk.notifyOnly + chat else talk.notifyOnly - chat))
    }
    fun setLiveTyping(chat: String, on: Boolean) {
        val talk = messagingState.talkSettings
        messaging.setTalkSettings(talk.copy(liveTypingFor = talk.liveTypingFor + (chat to on)))
    }
    fun setQuickTalk(chat: String, on: Boolean) {
        val talk = messagingState.talkSettings
        messaging.setTalkSettings(talk.copy(quickTalk = com.chencohen.ptt.talk.QuickTalk.set(talk.quickTalk, chat, on)))
    }
    val nameOf: (String) -> String = { u -> messagingState.contacts.firstOrNull { it.user == u }?.name ?: u }
    val unnamedGroup = stringResource(R.string.group_unnamed)
    val chatName: (String) -> String = { chat ->
        Group.idOf(chat)?.let { id -> messagingState.groups.firstOrNull { it.id == id }?.name?.ifBlank { unnamedGroup } ?: unnamedGroup } ?: nameOf(chat)
    }
    // Calls (phase 6): the microphone, and the camera for video, are asked for first.
    val callPermissions = com.chencohen.ptt.ui.calls.rememberCallPermissions { messages.post(resources.getString(R.string.call_mic_denied)) }
    fun call(chat: String, video: Boolean) = callPermissions(video) { v -> messaging.startCall(chat, v) }
    // Both live in a chat (2026-11-24, hen): the chat whose call shows inside it, and the chat where both are live now
    // (a call coming from there is taken at once, inside the chat).
    var inChatCallFor by rememberSaveable { mutableStateOf<String?>(null) }
    var bothLiveIn by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openedCall) {
        val (user, video) = openedCall ?: return@LaunchedEffect
        onCallTaken()
        call(user, video)
    }
    // Who can hear now (2026-11-25, hen): each contact's Do Not Disturb, silent or call, from their presence.
    // As shown: a change only once it held 2 s (hearShown), so a phone reconnecting doesn't flash the badges.
    val presence = messagingState.presence
    val hearShown = messagingState.hearShown
    val hearOf = remember(presence, hearShown) { { u: String -> hearShown[u] ?: presence[u]?.let { com.chencohen.ptt.talk.Hear.of(it.online, it.hear) } ?: com.chencohen.ptt.talk.Hear.UNKNOWN } }
    // A chat's own screen: the chat page, and the Talk tab's stage (2026-11-26, hen) with its faces row as [header].
    @Composable
    fun ChatScreenFor(user: String, header: (@Composable () -> Unit)? = null, stage: (@Composable (live: Boolean, overlay: @Composable () -> Unit) -> Unit)? = null, pushTo: String? = null, writeHint: String? = null, writeOpens: (() -> Unit)? = null) {
        // "typing…" ends by itself when its time is up.
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        val group = Group.idOf(user)?.let { id -> messagingState.groups.firstOrNull { it.id == id } }
        // The composer's + (2026-11-28, hen): what is picked is shared here.
        val writable = if (Group.idOf(user) != null) group != null && !group.gone && group.isReader(state.device?.user.orEmpty())
            else messagingState.contacts.firstOrNull { it.user == user }?.let { it.paired && !it.blocked } == true
        val attachPicker = com.chencohen.ptt.ui.media.rememberMediaPicker { uris -> shareIn(user, uris.map { it.toString() }) }
        val camera = com.chencohen.ptt.ui.media.rememberCamera { uris -> sendShared(user, uris.map { it.toString() }) }
        val groupTyping = messagingState.typing.filterKeys { it.startsWith("$user/") }
        val typingUntil = if (Group.idOf(user) != null) groupTyping.values.maxOrNull() else messagingState.typing[user]
        LaunchedEffect(typingUntil) {
            now = System.currentTimeMillis()
            if (typingUntil != null && typingUntil > now) {
                kotlinx.coroutines.delay(typingUntil - now)
                now = System.currentTimeMillis()
            }
        }
        ConversationScreen(
            user = user,
            embeddedHeader = header,
            stage = stage,
            pushTo = pushTo,
            writeHint = writeHint,
            writeOpens = writeOpens,
            colors = messagingState.talkSettings.colorsOf(user),
            bright = messagingState.talkSettings.brightOf(user),
            // In a group, each member's own colour (2026-11-27, hen: "in group chat there should be color for every contact").
            memberHues = Group.idOf(user)?.let { id ->
                val g = messagingState.groups.firstOrNull { it.id == id }
                val me = state.device?.user.orEmpty()
                g?.readers?.map { it.user }?.filter { it != me }?.let { messagingState.talkSettings.memberHues(user, it) }
            }.orEmpty(),
            contact = messagingState.contacts.firstOrNull { it.user == user },
            messages = messagingState.conversations[user].orEmpty(),
            header = ConversationHeader(typing = (typingUntil ?: 0) > now, presence = messagingState.presence[user]),
            drafts = liveDrafts(messagingState.drafts, user, now, Group.idOf(user) != null),
            draftCarets = liveCarets(messagingState.drafts, user, Group.idOf(user) != null),
            pushVideo = messagingState.talkSettings.pushVideoFor(user),
            presences = messagingState.presence,
            liveEdits = messagingState.liveEdits,
            viewing = viewingIn(messagingState.viewing, user, System.currentTimeMillis(), Group.idOf(user) != null),
            direction = messagingState.talkSettings.directionOf(user),
            live = com.chencohen.ptt.ui.contacts.ChatLive(
                cameraReady = messagingState.talkSettings.cameraReady,
                keeps = { m -> m.clip?.let { messagingState.talkSettings.keeps(user, it) } ?: true },
                liveWaiting = viewingIn(messagingState.liveWaiting, user, System.currentTimeMillis(), Group.idOf(user) != null),
                autoCall = user in messagingState.talkSettings.bothLiveCall,
                roomCallCamera = messagingState.roomCall?.takeIf { it.chat == user }?.camera == true,
                // A push turned into a call opens the call screen (2026-11-26, hen), not a call inside the chat.
                call = messagingState.call?.takeIf { it.chat == user && inChatCallFor == user && it.phase != com.chencohen.ptt.calls.CallPhase.ENDED && !inPip }?.let { c ->
                    val transport = remember { runCatching { com.chencohen.ptt.callTransport(context) }.getOrNull() }
                    com.chencohen.ptt.ui.contacts.InChatCall(
                        top = { com.chencohen.ptt.ui.calls.ChatCallTop(c, chatName(user), transport) },
                        bottom = {
                            com.chencohen.ptt.ui.calls.ChatCallBottom(
                                c,
                                com.chencohen.ptt.ui.calls.CallActions(
                                    hangUp = messaging::hangUp,
                                    setMicrophone = messaging::setCallMicrophone,
                                    setCamera = { on -> if (on) callPermissions(true) { if (it) messaging.setCallCamera(true) } else messaging.setCallCamera(false) },
                                    switchCamera = messaging::switchCallCamera,
                                ),
                                fullScreen = { inChatCallFor = null },
                            )
                        },
                    )
                },
                onBothLive = { on -> bothLiveIn = if (on) user else bothLiveIn?.takeIf { it != user } },
                anyCall = messagingState.call?.let { it.phase != com.chencohen.ptt.calls.CallPhase.ENDED } == true,
            ),
            warmCamera = messaging::warmCamera,
            group = Group.idOf(user)?.let { id ->
                val me = state.device?.user.orEmpty()
                val typers = groupTyping.filterValues { it > now }.keys.map { it.substringAfter('/') }
                GroupChat(
                    name = group?.let { groupName(it) } ?: resources.getString(R.string.group_unnamed),
                    members = group?.readers?.map { it.user }?.filter { it != me }.orEmpty(),
                    picture = group?.picture,
                    line = when {
                        typers.isNotEmpty() -> com.chencohen.ptt.Gendered.stringFor(context, R.string.group_typing, typers, typers.joinToString(", "))
                        else -> resources.getQuantityString(R.plurals.group_members, group?.readers?.size ?: 0, group?.readers?.size ?: 0)
                    },
                    note = when {
                        group == null -> resources.getString(R.string.group_missing)
                        group.gone -> resources.getString(R.string.group_gone)
                        group.isInvitation(me) -> resources.getString(R.string.group_invitation_note)
                        !group.isReader(me) -> resources.getString(R.string.group_waiting_owner)
                        else -> null
                    },
                )
            },
            actions = ConversationActions(
                back = ::pop,
                openContact = { Group.idOf(user)?.let { push("group/$it") } ?: push("contact/$user") },
                openSafetyNumber = { push("safety/$user") },
                sendFace = { code, fallback -> com.chencohen.ptt.sendFace(context, user, code, fallback) },
                react = { id, code -> com.chencohen.ptt.react(context, id, code) },
                send = { body ->
                    messaging.sendText(user, body) { result ->
                        if (result is ContactResult.Failed) messages.post(resources.getString(R.string.contact_failed, result.message))
                    }
                },
                retry = messaging::retry,
                typing = { messaging.typing(user, it) },
                draft = { text, s, e -> if (text.isEmpty()) messaging.draftDone(user) else messaging.draft(user, text, s, e) },
                playClip = messaging::playClip,
                saveClip = { id -> saveBurst(context, id, nameOf(user), messages) },
                stopClip = messaging::stopPlaying,
                // The chat's microphone is push-to-talk (2026-11-22, hen), not a voice message.
                startVoice = { pressOrUnlock(user) },
                startVoiceMessage = { messaging.startVoice(user) },
                markUrgent = { messaging.markUrgent() },
                finishVoice = { messaging.releaseTalk(it) },
                micDenied = { messages.post(resources.getString(R.string.talk_mic_denied)) },
                call = { video -> call(user, video) },
                sendLiveWaiting = { waiting -> messaging.sendLiveWaiting(user, waiting) },
                fullScreen = { mic, cam -> com.chencohen.ptt.ui.calls.LiveFull.open(user, fromFloating = false, mic = mic, camera = cam) },
                handOver = { video, urgent -> com.chencohen.ptt.TalkBubble.takeOver(context, user, chatName(user), video, urgent) },
                takeBack = { com.chencohen.ptt.TalkBubble.handBack(user) },
                callInChat = {
                    // In the talk room already: it becomes the call at once. Without a room to turn, a
                    // short word, never a ringing call (2026-11-25, hen: "it's calling the recipient
                    // instead of opening the call seamlessly").
                    messaging.startRoomCall(user, video = messagingState.talkSettings.pushVideoFor(user)) {
                        messages.post(resources.getString(R.string.talk_call_no_room))
                    }
                },
                shareCall = { file -> com.chencohen.ptt.ui.calls.shareCall(context, file) },
                playKept = ::playKept,
                setPushVideo = { on -> onTalkSettings(messagingState.talkSettings.withVideoFor(user, on)) },
                edit = { id, body -> messaging.editText(id, body) },
                editLive = { id, text, s, e -> messaging.editLive(id, text, s, e) },
                delete = { ids, everyone -> messaging.deleteMessages(ids, everyone) },
                mediaPreview = { id, item -> messaging.mediaPreview(id, item) },
                mediaClip = { id, item -> messaging.mediaClip(id, item) },
                mediaText = { id, item, text -> messaging.setMediaText(id, item, text) },
                openMedia = { id, item -> push("view/$id/$item") },
                mediaDraft = mediaDrafts[user]?.let { drafts ->
                    com.chencohen.ptt.ui.media.MediaDraft(
                        drafts,
                        send = { caption, removed, texts ->
                            mediaDrafts.remove(user)
                            messaging.sendDraft(drafts, caption, removed, texts)
                        },
                        sendFirst = { removed, texts ->
                            mediaDrafts.remove(user)
                            messaging.sendPicturesFirst(drafts, removed, texts) { id -> if (id != null) mediaCaptions[user] = id }
                        },
                        drop = {
                            mediaDrafts.remove(user)
                            messaging.dropDraft(drafts)
                        },
                        crop = { m, i -> cropDraft(user, m, i) },
                    )
                },
                mediaCaption = mediaCaptions[user]?.let { id ->
                    com.chencohen.ptt.ui.media.MediaCaption(
                        id,
                        live = { text, s, e -> messaging.editLive(id, text, s, e) },
                        commit = { caption ->
                            mediaCaptions.remove(user)
                            messaging.commitCaption(id, caption)
                        },
                        abort = {
                            mediaCaptions.remove(user)
                            messaging.deleteMessages(listOf(id), true)
                        },
                    )
                },
                uploads = uploads,
                transfers = transfers,
                encoding = encoding,
                attach = if (writable) attachPicker else null,
                camera = if (writable) camera else null,
            ),
            talk = messagingState.talk,
            talkLine = com.chencohen.ptt.ui.talk.talkLine(user, messagingState.talk, nameOf, state.device?.user.orEmpty()),
        )
    }
    // The bubbles' strength in each theme (Settings › chat colours).
    androidx.compose.runtime.SideEffect { com.chencohen.ptt.ui.style.ChatShade.take(messagingState.talkSettings.colorsFor[com.chencohen.ptt.ui.style.ChatShade.KEY]) }
    CompositionLocalProvider(LocalMessages provides messages, com.chencohen.ptt.ui.talk.LocalHear provides hearOf) {
        Box(Modifier.fillMaxSize()) {
            @Composable
            fun Viewer(name: String) {
                    val (id, item) = name.removePrefix("view/").split('/').let { it[0] to it.getOrElse(1) { "" } }
                    val message = remember(id, recropped) { messaging.mediaMessage(id) }
                    val originals by messaging.originals.collectAsStateWithLifecycle()
                    if (message == null) {
                        LaunchedEffect(Unit) { pop() }
                    } else {
                        androidx.compose.runtime.CompositionLocalProvider(com.chencohen.ptt.ui.media.LocalNames provides { u -> if (u == state.device?.user) resources.getString(R.string.group_you) else nameOf(u) }) {
                        com.chencohen.ptt.ui.media.MediaViewer(
                            message = message,
                            startItem = item,
                            sender = if (message.outgoing) resources.getString(R.string.group_you) else nameOf(message.author ?: message.user),
                            originals = originals,
                            preview = { m, i -> messaging.mediaPreview(m, i) },
                            open = messaging::openOriginal,
                            cancel = messaging::cancelOriginal,
                            stream = messaging::streamOriginal,
                            keeping = { m, i -> messaging.askHoldings(m, i); messaging.mediaKeeping(m, i) },
                            back = ::pop,
                            crop = if (message.outgoing && messaging.mediaSource(message.id, item) != null) { i -> cropSent(message, i) } else null,
                        )
                        }
                    }
                }
            val top = stack.lastOrNull()
            // A picture opened from a chat is drawn over it (the chat stays under), so it grows out of its place in
            // the chat and shrinks back into it (2026-11-28, hen: "seamless transition between chat and the image").
            val viewing = top?.takeIf { it.startsWith("view/") && stack.size >= 2 && stack[stack.size - 2].startsWith("chat/") }
            val page = if (viewing != null) stack[stack.size - 2] else top
            val name = page.orEmpty()
            val pageUser = page?.substringAfter('/', "")?.takeIf { it.isNotEmpty() }
            when {
                page == null -> HomeScreen(
                    state = HomeState(state.health, state.device?.user, messagingState, (updates as? com.chencohen.ptt.UpdateState.Available)?.release),
                    tab = tab,
                    selectTab = { tab = it },
                    actions = HomeActions(
                        openSettings = { push("settings") },
                        registerAgain = onRegisterAgain,
                        newChat = { push("new") },
                        reviewRequest = { push("review/$it") },
                        openContact = { push("chat/$it") },
                        openSafety = { push("safety/$it") },
                        openGroup = { push("group/$it") },
                        replaceIdentityKey = {
                            messaging.replaceIdentityKey { result ->
                                if (result is ContactResult.Failed) messages.post(resources.getString(R.string.contact_failed, result.message))
                            }
                        },
                        linkHere = {
                            scanQrCode(context, { text ->
                                messaging.linkWithScanned(text) { ok -> if (!ok) messages.post(resources.getString(R.string.chats_link_wrong_code)) }
                            }) { messages.post(resources.getString(R.string.add_scan_failed)) }
                        },
                        call = ::call,
                        chatName = chatName,
                        shareCall = { file -> com.chencohen.ptt.ui.calls.shareCall(context, file) },
                        playKept = ::playKept,
                        saveKept = ::saveKept,
                        setPushVideo = { chat, on -> onTalkSettings(messagingState.talkSettings.withVideoFor(chat, on)) },
                        setMyGender = { g -> messaging.setMyGender(g) },
                        warmCamera = messaging::warmCamera,
                        setHere = messaging::setHere,
                        pressTalk = { chat -> pressOrUnlock(chat) },
                        releaseTalk = { messaging.releaseTalk(true) },
                        quickTalkShown = { chat ->
                            val talk = messaging.state.value.talkSettings
                            if (talk.quickTalkPage != chat) messaging.setTalkSettings(talk.copy(quickTalkPage = chat))
                        },
                        micDenied = { messages.post(resources.getString(R.string.talk_mic_denied)) },
                        warmMicrophone = messaging::warmMicrophone,
                        talkChat = { c, h, s ->
                            // The Talk tab: the chat's own bottom, its writing bubble naming the chat and opening it to write.
                            ChatScreenFor(c, h, s, writeHint = resources.getString(R.string.talk_reply_to, chatName(c))) {
                                com.chencohen.ptt.ui.contacts.ChatHandover.landing.value = com.chencohen.ptt.ui.contacts.ChatHandover.Landing(c, "", System.currentTimeMillis(), write = true)
                                com.chencohen.ptt.ui.contacts.TalkTabMove.go(c)
                                push("chat/$c")
                            }
                        },
                        setQuickTalk = { chat, on -> setQuickTalk(chat, on) },
                        playClip = messaging::playClip,
                        stopClip = messaging::stopPlaying,
                        sendText = { chat, body ->
                            messaging.sendText(chat, body) { result ->
                                if (result is ContactResult.Failed) messages.post(resources.getString(R.string.contact_failed, result.message))
                            }
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { runCatching { com.chencohen.ptt.markReadFromNotification(context, chat) } }
                        },
                        update = { release -> com.chencohen.ptt.AppUpdates.update(app, release) },
                    ),
                )
                name == "settings" -> com.chencohen.ptt.ui.contacts.Isolated {
                    var presenceVisible by remember { mutableStateOf<Boolean?>(messaging.presenceVisible) }
                    var hearVisible by remember { mutableStateOf<Boolean?>(messaging.hearVisible) }
                    val settingsState by settings.state.collectAsStateWithLifecycle()
                    SettingsScreen(
                        state = settingsState,
                        back = ::pop,
                        refresh = settings::refresh,
                        registerAgain = onRegisterAgain,
                        linkPhone = { push("devices") },
                        openPicture = { push("picture") },
                        openTalk = { push("talksettings") },
                        chatColors = { com.chencohen.ptt.ui.talk.ChatColorsCard(com.chencohen.ptt.messaging.GLOBAL_COLORS, messagingState.talkSettings, onTalkSettings) },
                        openCalls = { push("callsettings") },
                        openStorage = { push("storagesettings") },
                        updates = updates,
                        checkUpdates = { com.chencohen.ptt.AppUpdates.check(app) },
                        update = { release -> com.chencohen.ptt.AppUpdates.update(app, release) },
                        automaticUpdates = automaticUpdates,
                        setAutomaticUpdates = { on -> com.chencohen.ptt.AppUpdates.setAutomatic(app, on) },
                        contacts = messagingState.contacts,
                        myEmail = messaging.myEmail,
                        setMyEmail = { email -> messaging.setMyEmail(email) },
                        myGender = messaging.myGender,
                        setMyGender = { g ->
                            messaging.setMyGender(g)
                            com.chencohen.ptt.ui.settings.GenderAsked.mark(context)
                            com.chencohen.ptt.Gendered.applyMine(context, g)
                        },
                        softMessage = messagingState.talkSettings.softMessage,
                        setSoftMessage = { soft -> onTalkSettings(messagingState.talkSettings.copy(softMessage = soft)) },
                        presenceVisible = presenceVisible,
                        hearVisible = hearVisible,
                        setHearVisible = { visible ->
                            hearVisible = null
                            messaging.setHearVisible(visible) { shown ->
                                hearVisible = shown ?: messaging.hearVisible
                                if (shown == null) messages.post(resources.getString(R.string.settings_hear_failed))
                            }
                        },
                        liveTyping = messagingState.talkSettings.liveTyping,
                        setLiveTyping = { on -> onTalkSettings(messagingState.talkSettings.copy(liveTyping = on)) },
                        setPresenceVisible = { visible ->
                            presenceVisible = null
                            messaging.setPresenceVisible(visible) { shown ->
                                presenceVisible = shown ?: messaging.presenceVisible
                                if (shown == null) messages.post(resources.getString(R.string.settings_presence_failed))
                            }
                        },
                    )
                }
                name == "talksettings" -> com.chencohen.ptt.ui.contacts.Isolated {
                    val chats = messagingState.contacts.filter { it.paired && !it.blocked }.map { it.user to nameOf(it.user) } +
                        messagingState.groups.filter { !it.gone && it.isReader(state.device?.user.orEmpty()) }.map { it.chat to groupName(it) }
                    com.chencohen.ptt.ui.settings.TalkSettingsScreen(
                        settings = messagingState.talkSettings,
                        chats = chats,
                        back = ::pop,
                        change = { new -> onTalkSettings(new) },
                    )
                }
                name == "callsettings" -> com.chencohen.ptt.ui.settings.CallSettingsScreen(
                    settings = messagingState.talkSettings,
                    back = ::pop,
                    change = { new -> onTalkSettings(new) },
                    openSaving = { push("savingcalls") },
                    openLayout = { push("videolayout") },
                )
                name == "videolayout" -> com.chencohen.ptt.ui.settings.VideoLayoutScreen(back = ::pop)
                name == "storagesettings" -> com.chencohen.ptt.ui.settings.StorageSettingsScreen(
                    settings = messagingState.talkSettings,
                    used = messaging::cacheBytes,
                    back = ::pop,
                    change = { new -> onTalkSettings(new) },
                )
                name == "savingcalls" -> com.chencohen.ptt.ui.settings.SavingCallsScreen(
                    settings = messagingState.talkSettings,
                    back = ::pop,
                    change = { new -> onTalkSettings(new) },
                )
                name == "devices" -> com.chencohen.ptt.ui.contacts.Isolated {
                    // Read each time the page opens; a removed phone leaves the list once the server says so.
                    var view by remember { mutableStateOf<DevicesView>(DevicesView.Loading) }
                    fun load() {
                        messaging.loadDevices { devices, error ->
                            view = devices?.let { DevicesView.Showing(it) } ?: DevicesView.Failed(error.orEmpty())
                        }
                    }
                    LaunchedEffect(Unit) { load() }
                    DevicesScreen(
                        view = view,
                        now = System.currentTimeMillis(),
                        back = ::pop,
                        again = {
                            view = DevicesView.Loading
                            load()
                        },
                        revoke = { device ->
                            (view as? DevicesView.Showing)?.let { view = it.copy(revoking = device.deviceNumber) }
                            messaging.revokeDevice(device.deviceNumber) { error ->
                                messages.post(
                                    if (error == null) resources.getString(R.string.devices_removed, device.name)
                                    else resources.getString(R.string.devices_remove_failed, error),
                                )
                                load()
                            }
                        },
                        linkPhone = { push("link") },
                    )
                }
                name == "link" -> com.chencohen.ptt.ui.contacts.Isolated {
                    // A new code each time the page opens; the phone linked with it shows once its answer went out.
                    var view by remember { mutableStateOf<LinkPhoneView>(LinkPhoneView.Loading) }
                    val linkedBefore = remember { messagingState.linkedDevice }
                    fun make() {
                        view = LinkPhoneView.Loading
                        messaging.makeLinkCode { code, error ->
                            view = code?.let { LinkPhoneView.Showing(it) } ?: LinkPhoneView.Failed(error.orEmpty())
                        }
                    }
                    LaunchedEffect(Unit) { make() }
                    LaunchedEffect(messagingState.linkedDevice) {
                        val device = messagingState.linkedDevice
                        if (device != null && device != linkedBefore) view = LinkPhoneView.Linked(device)
                    }
                    LinkPhoneScreen(view, back = ::pop, again = ::make)
                }
                name.startsWith("view/") -> Viewer(name)
                name == "share" -> com.chencohen.ptt.ui.media.ShareToScreen(
                    count = sharing.size,
                    contacts = messagingState.contacts,
                    groups = messagingState.groups,
                    me = state.device?.user.orEmpty(),
                    conversations = messagingState.conversations,
                    back = {
                        sharing = emptyList()
                        pop()
                    },
                    pick = { chat ->
                        shareIn(chat, sharing)
                        sharing = emptyList()
                        saved = "chat/$chat"
                    },
                )
                name == "new" -> NewChatScreen(
                    contacts = messagingState.contacts,
                    back = ::pop,
                    addContact = { push("add") },
                    openContact = { push("chat/$it") },
                    newGroup = { push("newgroup") },
                )
                name == "newgroup" -> com.chencohen.ptt.ui.contacts.Isolated {
                    var busy by remember { mutableStateOf(false) }
                    NewGroupScreen(
                        contacts = messagingState.contacts,
                        back = ::pop,
                        busy = busy,
                        create = { groupName, picture, members ->
                            busy = true
                            messaging.createGroup(groupName, picture, members) { chat, error ->
                                busy = false
                                if (chat != null) saved = "chat/$chat" else messages.post(resources.getString(R.string.contact_failed, error.orEmpty()))
                            }
                        },
                    )
                }
                name == "picture" -> com.chencohen.ptt.ui.face.FacePickerScreen(onboarding = false, done = ::pop)
                name == "welcomepicture" -> com.chencohen.ptt.ui.face.FacePickerScreen(onboarding = true, done = ::pop)
                name.startsWith("groupadd/") && pageUser != null -> com.chencohen.ptt.ui.contacts.Isolated {
                    val group = messagingState.groups.firstOrNull { it.id == pageUser }
                    var busy by remember { mutableStateOf(false) }
                    AddMembersScreen(
                        contacts = messagingState.contacts,
                        inGroup = group?.let { g -> (g.members + g.invited).map { it.user }.toSet() }.orEmpty(),
                        back = ::pop,
                        busy = busy,
                        add = { users ->
                            busy = true
                            messaging.inviteToGroup(pageUser, users) { result ->
                                busy = false
                                when (result) {
                                    ContactResult.Done -> pop()
                                    is ContactResult.Failed -> messages.post(resources.getString(R.string.contact_failed, result.message))
                                }
                            }
                        },
                    )
                }
                name.startsWith("group/") && pageUser != null -> com.chencohen.ptt.ui.contacts.Isolated {
                    val id = pageUser
                    val group = messagingState.groups.firstOrNull { it.id == id }
                    LaunchedEffect(id) { messaging.refreshGroups() }
                    fun report(result: ContactResult, then: () -> Unit = {}) {
                        when (result) {
                            ContactResult.Done -> then()
                            is ContactResult.Failed -> messages.post(resources.getString(R.string.contact_failed, result.message))
                        }
                    }
                    GroupScreen(
                        me = state.device?.user.orEmpty(),
                        group = group,
                        contacts = messagingState.contacts,
                        actions = GroupActions(
                            back = ::pop,
                            accept = { messaging.acceptGroup(id) { report(it) { saved = "chat/${Group.chatOf(id)}" } } },
                            decline = { messaging.leaveGroup(id) { report(it, ::home) } },
                            rename = { newName, picture -> messaging.renameGroup(id, newName, picture) { report(it) } },
                            addMembers = { push("groupadd/$id") },
                            remove = { user ->
                                messages.post(resources.getString(R.string.group_remove_confirm, user), resources.getString(R.string.group_remove)) {
                                    messaging.removeFromGroup(id, user) { report(it) }
                                }
                            },
                            openMember = { user ->
                                if (messagingState.contacts.any { it.user == user }) push("contact/$user") else push("add")
                            },
                            leave = {
                                messages.post(resources.getString(R.string.group_leave_confirm), resources.getString(R.string.group_leave)) {
                                    messaging.leaveGroup(id) { report(it) }
                                }
                            },
                            delete = {
                                messages.post(resources.getString(R.string.group_delete_confirm), resources.getString(R.string.group_delete)) {
                                    messaging.deleteGroup(id) { report(it, ::home) }
                                }
                            },
                            setNotifyOnly = { on -> setNotifyOnly(Group.chatOf(id), on) },
                            setLiveTyping = { on -> setLiveTyping(Group.chatOf(id), on) },
                            setQuickTalk = { on -> setQuickTalk(Group.chatOf(id), on) },
                            setDirection = { d -> onTalkSettings(messagingState.talkSettings.withDirection(Group.chatOf(id), d)) },
                        ),
                        notifyOnly = Group.chatOf(id) in messagingState.talkSettings.notifyOnly,
                        inChat = {
                            com.chencohen.ptt.ui.talk.KeepInChatCard(Group.chatOf(id), messagingState.talkSettings, onTalkSettings)
                            com.chencohen.ptt.ui.talk.ChatColorsCard(Group.chatOf(id), messagingState.talkSettings, onTalkSettings)
                            com.chencohen.ptt.ui.talk.VideosAtOnceCard(Group.chatOf(id), messagingState.talkSettings, onTalkSettings)
                            com.chencohen.ptt.ui.media.ChatStorageCard(Group.chatOf(id), messaging::chatStorage, messaging::clearChatStorage, messaging::originalRequests, nameOf)
                        },
                        liveTyping = messagingState.talkSettings.liveTypingTo(Group.chatOf(id)),
                        direction = messagingState.talkSettings.directionOf(Group.chatOf(id)),
                        quickTalk = Group.chatOf(id) in messagingState.talkSettings.quickTalk,
                    )
                }
                name == "invite" -> com.chencohen.ptt.ui.contacts.Isolated {
                    val pairingState by pairing.state.collectAsStateWithLifecycle()
                    DisposableEffect(page) { onDispose { pairing.clearInvite() } }
                    LaunchedEffect(page) { pairing.refreshWaiting() }
                    InviteScreen(
                        state = pairingState,
                        actions = InviteActions(
                            back = ::pop,
                            make = pairing::makeInvite,
                            // The invite's message (sharedLogic InviteMessage): the steps, the link and its code. This
                            // phone sends it; the server sends no mail.
                            share = { invite ->
                                val message = invite.message(inviteDate(invite.expiresAt))
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, message.subject)
                                    .putExtra(Intent.EXTRA_TEXT, message.body)
                                context.startActivity(Intent.createChooser(send, resources.getString(R.string.invite_share)))
                            },
                            email = { invite ->
                                val to = invite.email.orEmpty()
                                val message = invite.message(inviteDate(invite.expiresAt))
                                // ACTION_SENDTO with mailto: only mail apps answer it, and the user's own app sends it
                                // from their address. The URI carries To, subject and body; the extras too, for apps
                                // that read only those.
                                val mail = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse(message.mailto(to)))
                                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                                    .putExtra(Intent.EXTRA_SUBJECT, message.subject)
                                    .putExtra(Intent.EXTRA_TEXT, message.body)
                                try {
                                    context.startActivity(mail)
                                } catch (_: android.content.ActivityNotFoundException) {
                                    messages.post(resources.getString(R.string.invite_no_mail_app))
                                }
                            },
                            copy = { invite ->
                                val message = invite.message(inviteDate(invite.expiresAt))
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText(message.subject, message.body))
                                messages.post(resources.getString(R.string.invite_copied))
                            },
                            cancel = { id -> pairing.cancelInvite(id) },
                            another = pairing::clearInvite,
                        ),
                    )
                }
                name == "add" || name == "join" || name.startsWith("review/") -> com.chencohen.ptt.ui.contacts.Isolated {
                    val pairingState by pairing.state.collectAsStateWithLifecycle()
                    DisposableEffect(page) {
                        pairing.open()
                        if (pageUser != null) pairing.review(pageUser)
                        if (name == "join") {
                            openedInvite?.let(pairing::openInvite)
                            onInviteTaken()
                        }
                        onDispose { pairing.leave() }
                    }
                    AddContactScreen(
                        state = pairingState,
                        user = state.device?.user,
                        actions = AddContactActions(
                            back = ::pop,
                            retryCode = pairing::open,
                            scan = { scanQrCode(context, pairing::scanned) { messages.post(resources.getString(R.string.add_scan_failed)) } },
                            invite = { push("invite") },
                            pair = pairing::pair,
                            accept = pairing::accept,
                            decline = {
                                pairing.decline()
                                pop()
                            },
                            backToCode = pairing::back,
                            done = ::home,
                        ),
                    )
                }
                name.startsWith("chat/") && pageUser != null -> com.chencohen.ptt.ui.contacts.Isolated {
                    val user = pageUser
                    // On screen: what comes in is read at once; gone, notifications again.
                    // On screen by the user's doing (opened, or the app brought back on it): the floating chat, if it was
                    // dismissed for this conversation, may come again.
                    androidx.lifecycle.compose.LifecycleResumeEffect(user) {
                        com.chencohen.ptt.QuickReplyWindow.chatOpened(user)
                        onPauseOrDispose { }
                    }
                    val app = LocalContext.current.applicationContext as? com.chencohen.ptt.TalkApp
                    DisposableEffect(user) {
                        messaging.openChat(user)
                        messaging.refreshPresence()
                        // Its own buttons are here: the floating bar for it goes; leaving it while someone is live in
                        // it, the floating bar shows them (2026-11-27, hen).
                        com.chencohen.ptt.TalkBubble.chatOpened(user)
                        onDispose {
                            messaging.openChat(null)
                            app?.let { com.chencohen.ptt.TalkPlaying.leftChat(it, user) }
                        }
                    }
                    LaunchedEffect(messagingState.unread[user]) { if ((messagingState.unread[user] ?: 0) > 0) messaging.openChat(user) }
                    ChatScreenFor(user)
                }
                name.startsWith("contact/") && pageUser != null -> com.chencohen.ptt.ui.contacts.Isolated {
                    val user = pageUser
                    ContactScreen(
                        user = user,
                        contact = messagingState.contacts.firstOrNull { it.user == user },
                        actions = ContactActions(
                            back = ::pop,
                            rename = { name -> messaging.renameContact(user, name) },
                            setEmail = { email -> messaging.setContactEmail(user, email) },
                            acceptKeyChange = { messaging.acceptKeyChange(user) },
                            openSafetyNumber = { push("safety/$user") },
                            setBlocked = { blocked ->
                                messaging.setBlocked(user, blocked) { result ->
                                    messages.post(
                                        when (result) {
                                            ContactResult.Done -> resources.getString(if (blocked) R.string.contact_done_blocked else R.string.contact_done_unblocked, user)
                                            is ContactResult.Failed -> resources.getString(R.string.contact_failed, result.message)
                                        },
                                    )
                                }
                            },
                            // Removing is asked for in the message bar, Journal's way instead of a dialog.
                            unpair = {
                                messages.post(resources.getString(R.string.contact_unpair_confirm, user), resources.getString(R.string.contact_unpair_action)) {
                                    messaging.unpair(user) { result ->
                                        when (result) {
                                            ContactResult.Done -> {
                                                messages.post(resources.getString(R.string.contact_done_unpaired, user))
                                                home()
                                            }
                                            is ContactResult.Failed -> messages.post(resources.getString(R.string.contact_failed, result.message))
                                        }
                                    }
                                }
                            },
                            setNotifyOnly = { on -> setNotifyOnly(user, on) },
                            setUrgentThroughDnd = { on ->
                                val talk = messagingState.talkSettings
                                messaging.setTalkSettings(talk.copy(urgentThroughDnd = if (on) talk.urgentThroughDnd + user else talk.urgentThroughDnd - user))
                            },
                            setQuickTalk = { on -> setQuickTalk(user, on) },
                            setLiveTyping = { on -> setLiveTyping(user, on) },
                            setDirection = { d -> onTalkSettings(messagingState.talkSettings.withDirection(user, d)) },
                        ),
                        notifyOnly = user in messagingState.talkSettings.notifyOnly,
                        urgentThroughDnd = user in messagingState.talkSettings.urgentThroughDnd,
                        inChat = {
                            com.chencohen.ptt.ui.talk.KeepInChatCard(user, messagingState.talkSettings, onTalkSettings)
                            com.chencohen.ptt.ui.talk.ChatColorsCard(user, messagingState.talkSettings, onTalkSettings)
                            com.chencohen.ptt.ui.talk.VideosAtOnceCard(user, messagingState.talkSettings, onTalkSettings)
                            com.chencohen.ptt.ui.media.ChatStorageCard(user, messaging::chatStorage, messaging::clearChatStorage, messaging::originalRequests, nameOf)
                        },
                        versions = messagingState.versions[user].orEmpty(),
                        quickTalk = user in messagingState.talkSettings.quickTalk,
                        liveTyping = messagingState.talkSettings.liveTypingTo(user),
                        direction = messagingState.talkSettings.directionOf(user),
                    )
                }
                name.startsWith("kept/") && pageUser != null && keptCalls[pageUser] != null -> com.chencohen.ptt.ui.contacts.Isolated {
                    val message = keptCalls.getValue(pageUser)
                    var progress by remember(pageUser) { mutableStateOf(0f) }
                    var load by remember(pageUser) { mutableStateOf<com.chencohen.ptt.ui.calls.KeptLoad?>(null) }
                    var saving by remember(pageUser) { mutableStateOf(false) }
                    val keptScope = androidx.compose.runtime.rememberCoroutineScope()
                    LaunchedEffect(pageUser) {
                        load = com.chencohen.ptt.ui.calls.loadKeptCall(context, message, messaging::keptCall, messaging::readBlob, nameOf) { progress = it }
                    }
                    suspend fun saveNow(ready: com.chencohen.ptt.ui.calls.KeptLoad.Ready) {
                        saving = true
                        val result = com.chencohen.ptt.ui.calls.saveKeptCall(context, ready.file, chatName(message.user), message.at)
                        saving = false
                        when (result) {
                            is com.chencohen.ptt.ui.calls.KeptSave.Saved -> {
                                messaging.markCallDownloaded(message.id)
                                messages.post(resources.getString(R.string.kept_saved, result.where), resources.getString(R.string.call_share)) {
                                    com.chencohen.ptt.ui.calls.shareKeptCall(context, result.uri)
                                }
                            }
                            is com.chencohen.ptt.ui.calls.KeptSave.FolderGone -> messages.post(resources.getString(R.string.kept_folder_gone, result.folder), resources.getString(R.string.calls_folder_pick)) {
                                push("callsettings")
                            }
                            com.chencohen.ptt.ui.calls.KeptSave.Failed -> messages.post(resources.getString(R.string.kept_save_failed))
                        }
                    }
                    com.chencohen.ptt.ui.calls.KeptCallScreen(
                        title = chatName(message.user),
                        load = load ?: com.chencohen.ptt.ui.calls.KeptLoad.Getting(progress),
                        back = ::pop,
                        save = {
                            val ready = load as? com.chencohen.ptt.ui.calls.KeptLoad.Ready
                            if (ready != null && !saving) keptScope.launch { saveNow(ready) }
                        },
                        saving = saving,
                        downloaded = message.id in messagingState.talkSettings.downloadedCalls,
                    )
                    // From the call log's Save: once it's ready, save it.
                    LaunchedEffect(load) {
                        val ready = load as? com.chencohen.ptt.ui.calls.KeptLoad.Ready
                        if (ready != null && keptSaveAsked.remove(pageUser)) saveNow(ready)
                    }
                }
                name.startsWith("safety/") && pageUser != null -> com.chencohen.ptt.ui.contacts.Isolated {
                    val user = pageUser
                    val contact = messagingState.contacts.firstOrNull { it.user == user }
                    var load by remember(user) { mutableStateOf<SafetyNumberLoad>(SafetyNumberLoad.Loading) }
                    LaunchedEffect(user, contact?.identityKey) {
                        messaging.safetyNumber(user) { number -> load = number?.let { SafetyNumberLoad.Shown(it) } ?: SafetyNumberLoad.Missing }
                    }
                    SafetyNumberScreen(
                        user = user,
                        load = load,
                        verified = contact?.verified == true,
                        actions = SafetyNumberActions(
                            back = ::pop,
                            scan = {
                                scanQrCode(
                                    context,
                                    { text ->
                                        messaging.verifyScanned(user, text) { same ->
                                            messages.post(resources.getString(if (same) R.string.safety_match else R.string.safety_mismatch, user))
                                        }
                                    },
                                    { messages.post(resources.getString(R.string.add_scan_failed)) },
                                )
                            },
                            setVerified = { messaging.setVerified(user, it) },
                        ),
                    )
                }
                else -> LaunchedEffect(page) { home() }
            }
            if (viewing != null) Viewer(viewing)
            cropping?.let { session ->
                com.chencohen.ptt.ui.media.CropEditor(
                    session.targets, session.start,
                    preview = { m, i -> messaging.mediaPreview(m, i) },
                    done = { crops ->
                        cropping = null
                        if (crops.isNotEmpty()) session.apply(crops)
                    },
                    close = { cropping = null },
                )
            }
            MessageHost(messages, Modifier.align(Alignment.BottomCenter).navigationBarRoom().imePadding().padding(bottom = if (page == null) 88.dp else 16.dp))
            // The floating window is on by default (2026-11-22, hen) but needs "Display over other apps": asked once.
            OverlayAskOnce(messagingState.talkSettings.replyBubble)
            FullScreenAskOnce(messagingState.talkSettings.openOnTalk)
            // Woken for a burst over the lock screen (2026-11-23, hen): its video fills the screen while it plays, black
            // otherwise, never the app's own screen (the Talk screen used to carry this; it is gone).
            if (wokeForTalk) {
                val woken = messagingState.talk.playing?.takeIf { it.video }
                // A burst heard through the talk room never "plays" (2026-11-25, hen: "my screen is off and testbot
                // pushes video, I see a blank screen"): its pictures come through the room, so the room's picture
                // shows, else the clip's.
                val arriving by com.chencohen.ptt.talk.LiveTalkVideo.arriving.collectAsStateWithLifecycle()
                val me = state.device?.user
                val roomPicture = messagingState.talk.talkers.values.firstOrNull { it.user != me }
                    ?.let { t -> arriving["${t.user}.${t.device}"] } ?: arriving.values.firstOrNull { me == null || !it.identity.startsWith("$me.") }
                // Kept while their burst goes on: a moment without pictures (the room moving to a sharper layer) takes
                // their picture out of "arriving", and the screen went black for a second (2026-11-25, hen: "the
                // screen blinks"); the same view stays and the pictures go on in it.
                val heldPicture = remember { arrayOfNulls<com.chencohen.ptt.talk.LiveTalkVideo.Live>(1) }
                if (roomPicture != null) heldPicture[0] = roomPicture
                else if (messagingState.talk.talkers.values.none { it.user != me }) heldPicture[0] = null
                val shownPicture = roomPicture?.let { r -> heldPicture[0]?.takeIf { it.track === r.track } ?: r } ?: heldPicture[0]
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                    when {
                        shownPicture != null -> com.chencohen.ptt.ui.talk.RoomVideoView(shownPicture, Modifier.fillMaxSize())
                        woken != null -> com.chencohen.ptt.ui.talk.TalkVideoView(Modifier.fillMaxSize(), cover = true)
                    }
                }
            }
            // A call, over everything (phase 6).
            // A call that shows inside its chat (both live): not over everything while that chat is open; once it
            // ends, its "Call ended" goes at once.
            val callHere = messagingState.call
            LaunchedEffect(callHere?.id, callHere?.phase) {
                val c = callHere ?: return@LaunchedEffect
                if (c.incoming && c.video && c.phase == com.chencohen.ptt.calls.CallPhase.RINGING && c.chat == bothLiveIn) {
                    // Taken at once, on the call screen (2026-11-26, hen: a push turned into a call opens the call screen).
                    callPermissions(true) { messaging.acceptCall() }
                }
                if (c.phase == com.chencohen.ptt.calls.CallPhase.ENDED && inChatCallFor == c.chat) {
                    inChatCallFor = null
                    messaging.dismissEndedCall()
                }
            }
            // The live talk full screen (2026-11-27, hen): the same room, I start muted; the chevron or Back goes back to
            // the floating layout (the app to the back) or the chat it came from.
            com.chencohen.ptt.ui.calls.LiveFull.chat.value?.let { live ->
                // Muted, unless I was already sending (then it stays on, the same press: 2026-11-27, hen).
                var open by remember(live) { mutableStateOf(com.chencohen.ptt.ui.calls.LiveFull.startMic) }
                var camera by remember(live) { mutableStateOf(com.chencohen.ptt.ui.calls.LiveFull.startCamera) }
                var adopting by remember(live) { mutableStateOf(com.chencohen.ptt.ui.calls.LiveFull.startMic) }
                val ownTalk by androidx.compose.runtime.rememberUpdatedState(messagingState.talk.own)
                val since = remember(live) { System.currentTimeMillis() }
                val minimize = {
                    if (open) messaging.releaseTalk(true)
                    open = false
                    val back = com.chencohen.ptt.ui.calls.LiveFull.fromFloating
                    com.chencohen.ptt.ui.calls.LiveFull.chat.value = null
                    if (back) (context as? android.app.Activity)?.moveTaskToBack(true)
                }
                BackHandler { minimize() }
                // Open: a press held for me, renewed before the server's 60 s floor ends; muted: let go (sent).
                LaunchedEffect(live, open, camera) {
                    if (!open) return@LaunchedEffect
                    fun going() = ownTalk?.takeIf {
                        it.chat == live && (it.phase == com.chencohen.ptt.talk.TalkPhase.TALKING || it.phase == com.chencohen.ptt.talk.TalkPhase.REQUESTING)
                    }
                    // My press as it opened goes on as it is (its camera never let go); renewed when its 55 s are up.
                    val kept = going().takeIf { adopting }
                    adopting = false
                    if (kept == null) {
                        // A press while the last is still letting go would be ignored: it waits for that first.
                        var waited = 0
                        while (ownTalk?.chat == live && waited < 2_000) {
                            kotlinx.coroutines.delay(50)
                            waited += 50
                        }
                        messaging.pressTalkWith(live, camera)
                    }
                    try {
                        var wait = kept?.let { (it.startedAt + 55_000 - System.currentTimeMillis()).coerceIn(1_000L, 55_000L) } ?: 55_000L
                        while (true) {
                            kotlinx.coroutines.delay(wait)
                            wait = 55_000L
                            messaging.releaseTalk(true)
                            kotlinx.coroutines.delay(300)
                            messaging.pressTalkWith(live, camera)
                        }
                    } finally {
                        messaging.releaseTalk(true)
                    }
                }
                com.chencohen.ptt.ui.calls.LiveFullScreen(
                    chat = live,
                    title = chatName(live),
                    me = state.device?.user.orEmpty(),
                    open = open,
                    camera = camera,
                    since = since,
                    nameOf = nameOf,
                    statsAllowed = messagingState.talkSettings.videoStats,
                    setOpen = { mic, cam ->
                        if (mic) {
                            callPermissions(cam) { allowed ->
                                camera = cam && allowed
                                open = true
                            }
                        } else {
                            open = false
                            camera = false
                        }
                    },
                    minimize = minimize,
                    // Hang up: my press let go, and the chat (the floating window closes as its chat opens).
                    end = {
                        if (open) messaging.releaseTalk(true)
                        open = false
                        com.chencohen.ptt.ui.calls.LiveFull.chat.value = null
                        if (stack.lastOrNull() != "chat/$live") push("chat/$live")
                    },
                )
            }
            // A push turned into a call (the talk room's own), over everything like any call.
            messagingState.roomCall?.let { rc ->
                BackHandler { }
                com.chencohen.ptt.ui.calls.RoomCallScreen(
                    rc, chatName(rc.chat), state.device?.user.orEmpty(),
                    end = { messaging.endRoomCall() },
                    media = { mic, cam -> messaging.setRoomCallMedia(mic, cam) },
                )
            }
            messagingState.call?.takeIf { !(it.chat == inChatCallFor && stack.lastOrNull() == "chat/" + it.chat && it.phase != com.chencohen.ptt.calls.CallPhase.ENDED && !inPip) }?.let { current ->
                // During a call Back does nothing (the call goes on); once it has ended, Back closes "Call ended".
                BackHandler { if (current.phase == com.chencohen.ptt.calls.CallPhase.ENDED) messaging.dismissEndedCall() }
                // Opening the app on an ended call: it goes at once.
                LaunchedEffect(Unit) { if (current.phase == com.chencohen.ptt.calls.CallPhase.ENDED) messaging.dismissEndedCall() }
                com.chencohen.ptt.ui.calls.CallScreen(
                    call = current,
                    title = chatName(current.chat),
                    nameOf = nameOf,
                    transport = remember { runCatching { com.chencohen.ptt.callTransport(context) }.getOrNull() },
                    actions = com.chencohen.ptt.ui.calls.CallActions(
                        accept = {
                            callPermissions(current.video) { video ->
                                if (current.video && !video) messaging.setCallCamera(false)
                                messaging.acceptCall()
                            }
                        },
                        decline = messaging::declineCall,
                        hangUp = messaging::hangUp,
                        setMicrophone = messaging::setCallMicrophone,
                        setCamera = { on -> if (on) callPermissions(true) { if (it) messaging.setCallCamera(true) } else messaging.setCallCamera(false) },
                        switchCamera = messaging::switchCallCamera,
                        setSaving = messaging::setCallSaving,
                        dismissAsk = messaging::dismissSaveAsk,
                        setView = messaging::setCallView,
                    ),
                    compact = inPip,
                    canSave = current.serverKeeps || messagingState.talkSettings.saveCalls != com.chencohen.ptt.calls.SaveCalls.OFF,
                    statsAllowed = messagingState.talkSettings.videoStats,
                )
            }
        }
    }
}

/**
 * Chats or Calls: the top bar (the profile circle that opens Settings, the tab's title, Offline while the server
 * can't be reached), the cards (Register again, a pair request, another phone holding the keys), the tab's list, the
 * pencil, and the tabs at the bottom.
 */
@Composable
internal fun HomeScreen(state: HomeState, tab: HomeTab, selectTab: (HomeTab) -> Unit, actions: HomeActions) {
    val insets = barsRoom()
    val chats = state.messaging
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        if (tab == HomeTab.TALK) {
            val me = state.user.orEmpty()
            val unnamed = stringResource(R.string.group_unnamed)
            val contactNames = chats.contacts.filter { it.paired && !it.blocked }.associate { c -> c.user to c.name }
            val groups = chats.groups.filter { !it.gone && it.isReader(me) }.associateBy { it.chat }
            // Only the Quick talk chats, in the order they were marked (2026-11-26, hen: "only chats marked as quick chat
            // should appear there, its not many").
            val quick = chats.talkSettings.quickTalk
            val items = com.chencohen.ptt.talk.QuickTalk.pages(quick, contactNames.keys + groups.keys).map { chat ->
                val g = groups[chat]
                com.chencohen.ptt.ui.talk.TalkListItem(
                    chat = chat,
                    name = g?.name?.ifBlank { unnamed } ?: contactNames.getValue(chat),
                    group = g != null,
                    groupPicture = g?.picture,
                    last = chats.lastMessages[chat],
                    unread = chats.unread[chat] ?: 0,
                    members = g?.readers?.map { it.user }?.filter { it != me }.orEmpty(),
                )
            }
            val choices = (contactNames.map { (u, n) -> com.chencohen.ptt.ui.talk.TalkChoice(u, n, false, null, u in quick) } +
                groups.values.map { g -> com.chencohen.ptt.ui.talk.TalkChoice(g.chat, g.name.ifBlank { unnamed }, true, g.picture, g.chat in quick) })
                .sortedBy { it.name.lowercase() }
            // "Empty" only once the chats have had time to come (it flashed at start).
            var waited by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2_000)
                waited = true
            }
            // No top bar on Talk (2026-11-22, hen): the profile circle sits small in the corner instead.
            val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            Column(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding() + 8.dp, bottom = insets.calculateBottomPadding() + if (landscape) 60.dp else 80.dp)) {
            state.update?.let { release -> UpdateCard(release, actions.update) }
            // Once, for those who never said how the app should refer to them (2026-11-23, hen).
            val homeContext = androidx.compose.ui.platform.LocalContext.current
            var genderAsked by remember { mutableStateOf(com.chencohen.ptt.ui.settings.GenderAsked.asked(homeContext)) }
            if (!genderAsked && tab == HomeTab.CHATS) {
                com.chencohen.ptt.ui.settings.GenderCard(
                    choose = { g ->
                        actions.setMyGender(g)
                        com.chencohen.ptt.ui.settings.GenderAsked.mark(homeContext)
                        com.chencohen.ptt.Gendered.applyMine(homeContext, g)
                        genderAsked = true
                    },
                    dismiss = {
                        com.chencohen.ptt.ui.settings.GenderAsked.mark(homeContext)
                        genderAsked = true
                    },
                )
            }
            val nowMs = System.currentTimeMillis()
            com.chencohen.ptt.ui.talk.QuickTalkHome(
                items = items,
                startChat = chats.talkSettings.quickTalkPage,
                me = me,
                talk = chats.talk,
                nameOf = { u -> contactNames[u] ?: u },
                write = { c -> actions.openContact(c) },
                pageShown = actions.quickTalkShown,
                choices = choices,
                setOn = actions.setQuickTalk,
                loaded = waited || contactNames.isNotEmpty() || groups.isNotEmpty(),
                // The chat's own insets are the tab's here (it already keeps clear of the bars and the tabs).
                modifier = Modifier.weight(1f).consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.Companion.systemBars),
                warmMicrophone = actions.warmMicrophone,
                setHere = actions.setHere,
                viewing = { c -> viewingIn(chats.viewing, c, nowMs, Group.idOf(c) != null).isNotEmpty() },
                pushVideoFor = chats.talkSettings::pushVideoFor,
                chat = actions.talkChat,
            )
            }
        } else Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 72.dp, bottom = insets.calculateBottomPadding() + 96.dp),
        ) {
            if (state.health == ServerHealth.REJECTED) {
                HomeCard(
                    title = stringResource(R.string.home_rejected_title),
                    body = stringResource(R.string.home_rejected_body),
                    action = stringResource(R.string.home_rejected_action),
                    onAction = actions.registerAgain,
                    warning = true,
                )
            }
            state.update?.let { release -> UpdateCard(release, actions.update) }
            when (tab) {
                HomeTab.TALK -> {}
                HomeTab.CHATS -> {
                    (chats.keys as? KeysState.Linking)?.let { linking ->
                        HomeCard(
                            title = stringResource(R.string.chats_linking_title),
                            body = stringResource(R.string.chats_linking_body),
                            action = "",
                            onAction = {},
                        )
                    }
                    if (chats.keys is KeysState.OtherIdentity) {
                        HomeCard(
                            title = stringResource(R.string.chats_link_here_title),
                            body = stringResource(R.string.chats_link_here_body),
                            action = stringResource(R.string.chats_link_here_action),
                            onAction = actions.linkHere,
                        )
                        HomeCard(
                            title = stringResource(R.string.chats_other_identity_title),
                            body = stringResource(R.string.chats_other_identity_body),
                            action = stringResource(R.string.chats_other_identity_action),
                            onAction = actions.replaceIdentityKey,
                            warning = true,
                        )
                    }
                    (chats.keys as? KeysState.Failed)?.let { PageNote(stringResource(R.string.chats_keys_failed, it.message), top = 4, bottom = 4) }
                    chats.groups.filter { it.isInvitation(state.user.orEmpty()) }.forEach { group ->
                        HomeCard(
                            title = stringResource(R.string.chats_group_invite_title, group.owner, groupName(group)),
                            body = stringResource(R.string.chats_group_invite_body),
                            action = stringResource(R.string.chats_group_invite_action),
                            onAction = { actions.openGroup(group.id) },
                        )
                    }
                    chats.requests.forEach { request ->
                        HomeCard(
                            title = stringResource(R.string.chats_request_title, request.user),
                            body = stringResource(R.string.chats_request_body),
                            action = stringResource(R.string.chats_request_action),
                            onAction = { actions.reviewRequest(request.user) },
                        )
                    }
                    val groups = chats.groups.filter { !it.isInvitation(state.user.orEmpty()) }
                    if (chats.contacts.isEmpty() && groups.isEmpty()) {
                        Spacer(Modifier.height(96.dp))
                        EmptyTab(LibraryIcons.Chat, stringResource(R.string.chats_empty))
                    } else {
                        // The latest text first, then the newest contacts and groups; a group's row by its chat key.
                        val rows = chats.contacts.map { it.user to (chats.lastMessages[it.user]?.at ?: 0L) to it.addedAt } +
                            groups.map { it.chat to (chats.lastMessages[it.chat]?.at ?: 0L) to it.createdAt }
                        rows.sortedWith(compareByDescending<Pair<Pair<String, Long>, Long>> { it.first.second }.thenByDescending { it.second }).forEach { row ->
                            val key = row.first.first
                            val group = groups.firstOrNull { it.chat == key }
                            if (group != null) {
                                GroupRow(group, chats.lastMessages[key], chats.unread[key] ?: 0) { actions.openContact(key) }
                            } else {
                                chats.contacts.firstOrNull { it.user == key }?.let { contact ->
                                    ContactRow(contact, chats.lastMessages[key], chats.unread[key] ?: 0, openSafety = { actions.openSafety(contact.user) }) { actions.openContact(key) }
                                }
                            }
                        }
                    }
                }
                HomeTab.CALLS -> {
                    val calls = chats.conversations.values.flatten().filter { it.call != null }.sortedByDescending { it.at }
                    if (calls.isEmpty()) {
                        Spacer(Modifier.height(96.dp))
                        EmptyTab(LibraryIcons.Call, stringResource(R.string.calls_empty))
                    } else {
                        com.chencohen.ptt.ui.calls.CallsList(calls, actions.chatName, actions.call, actions.openContact, actions.shareCall, actions.playKept, actions.saveKept, state.messaging.talkSettings.downloadedCalls)
                    }
                }
            }
        }
        if (tab == HomeTab.TALK) {
            Row(Modifier.align(Alignment.TopStart).statusBarRoom().padding(start = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileCircle(state.user, actions.openSettings)
                if (state.health == ServerHealth.OFFLINE) {
                    Spacer(Modifier.width(8.dp))
                    OfflinePill()
                }
            }
        } else {
            TopBar(state, tab, actions.openSettings, Modifier.align(Alignment.TopCenter))
        }
        if (tab == HomeTab.CHATS) {
            NewChatButton(actions.newChat, Modifier.align(Alignment.BottomEnd).navigationBarRoom().padding(end = 20.dp, bottom = 84.dp))
        }
        SegmentedBar(
            options = listOf(
                HomeTab.TALK to stringResource(R.string.quick_talk_title),
                HomeTab.CHATS to stringResource(R.string.chats_title),
                HomeTab.CALLS to stringResource(R.string.calls_title),
            ),
            selected = tab,
            onSelect = selectTab,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarRoom().padding(bottom = if (androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4.dp else 16.dp),
        )
    }
}

/** "Talk 1.2.0 is available", with Update (docs/design.md, "App updates"). */
@Composable
private fun UpdateCard(release: com.chencohen.ptt.update.AppRelease, update: (com.chencohen.ptt.update.AppRelease) -> Unit) {
    HomeCard(
        title = stringResource(R.string.update_available, release.version),
        body = stringResource(R.string.update_card_body),
        action = stringResource(R.string.update_action),
        onAction = { update(release) },
    )
}

@Composable
private fun TopBar(state: HomeState, tab: HomeTab, openSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(LibraryColors.TopFade)
            .statusBarRoom()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileCircle(state.user, openSettings)
        Spacer(Modifier.width(14.dp))
        Text(
            stringResource(
                when (tab) {
                    HomeTab.TALK -> R.string.quick_talk_title
                    HomeTab.CHATS -> R.string.chats_title
                    HomeTab.CALLS -> R.string.calls_title
                },
            ),
            color = LibraryColors.OnBackground,
            style = LibraryType.ScreenTitle,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (state.health == ServerHealth.OFFLINE) OfflinePill()
    }
}

/** The user's own face (2026-11-24, hen), else their first letter in an ink circle; opens Settings. */
@Composable
private fun ProfileCircle(user: String?, onClick: () -> Unit) {
    val label = stringResource(R.string.chats_profile)
    val face = com.chencohen.ptt.ui.face.faceOf("")
    if (face != null) {
        androidx.compose.foundation.Image(
            face, label, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.size(44.dp).clickable(role = Role.Button, onClick = onClick),
        )
        return
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(LibraryColors.Inverse)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val letter = user?.firstOrNull()?.uppercaseChar()
        if (letter != null) {
            Text(letter.toString(), color = LibraryColors.OnInverse, style = LibraryType.RowTitle, fontWeight = FontWeight.Bold)
        } else {
            Icon(LibraryIcons.Person, null, tint = LibraryColors.OnInverse, modifier = Modifier.size(22.dp))
        }
    }
}

/** The pencil for a new chat, in ink: the screen's one main action. */
@Composable
private fun NewChatButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.chats_new)
    Box(
        modifier
            .size(56.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(LibraryColors.Inverse)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(LibraryIcons.Edit, null, tint = LibraryColors.OnInverse, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun EmptyTab(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).background(LibraryColors.Raised, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LibraryColors.Muted, modifier = Modifier.size(32.dp))
        }
        EmptyState(text)
    }
}

@Preview
@Composable
private fun ChatsEmptyPreview() {
    TalkTheme { HomeScreen(HomeState(ServerHealth.OK, "hen"), HomeTab.CHATS, {}, HomeActions()) }
}

@Preview
@Composable
private fun ChatsOfflinePreview() {
    TalkTheme { HomeScreen(HomeState(ServerHealth.OFFLINE, "hen"), HomeTab.CHATS, {}, HomeActions()) }
}

@Preview
@Composable
private fun ChatsRejectedPreview() {
    TalkTheme { HomeScreen(HomeState(ServerHealth.REJECTED, null), HomeTab.CALLS, {}, HomeActions()) }
}

@Preview
@Composable
private fun ChatsListPreview() {
    TalkTheme {
        HomeScreen(
            HomeState(
                ServerHealth.OK,
                "hen",
                MessagingState(
                    contacts = listOf(Contact("dana", "", paired = true, addedAt = 2), Contact("avi", "", paired = false, addedAt = 1)),
                    requests = listOf(PairRequest("noa", "", 0)),
                    lastMessages = mapOf("dana" to TextMessage("1", "dana", outgoing = true, body = "Testing the way there", at = 5)),
                ),
            ),
            HomeTab.CHATS,
            {},
            HomeActions(),
        )
    }
}

/** An invite's last day, as the phone writes a date ("Nov 29, 2026"). */
private fun inviteDate(millis: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(millis))

/** Asks once, with a word on why, for "Display over other apps" while the floating window is on without it. */
@Composable
private fun OverlayAskOnce(on: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("asked", android.content.Context.MODE_PRIVATE) }
    var asking by remember { mutableStateOf(on && !android.provider.Settings.canDrawOverlays(context) && !prefs.getBoolean("overlay", false)) }
    if (!asking) return
    fun done() {
        prefs.edit().putBoolean("overlay", true).apply()
        asking = false
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = ::done,
        title = { androidx.compose.material3.Text(stringResource(R.string.overlay_ask_title)) },
        text = { androidx.compose.material3.Text(stringResource(R.string.overlay_ask_body)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                done()
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")),
                    )
                }
            }) { androidx.compose.material3.Text(stringResource(R.string.overlay_ask_allow)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = ::done) { androidx.compose.material3.Text(stringResource(R.string.overlay_ask_later)) } },
    )
}

/**
 * Asks once for full-screen notifications while Open Talk when someone talks to me is on without them: on Android 14+
 * they are what surely turns the screen on over the lock screen for a burst (2026-11-23, hen).
 */
@Composable
private fun FullScreenAskOnce(on: Boolean) {
    if (android.os.Build.VERSION.SDK_INT < 34) return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("asked", android.content.Context.MODE_PRIVATE) }
    val allowed = remember { context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent() }
    var asking by remember { mutableStateOf(on && !allowed && !prefs.getBoolean("full_screen", false)) }
    if (!asking) return
    fun done() {
        prefs.edit().putBoolean("full_screen", true).apply()
        asking = false
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = ::done,
        title = { androidx.compose.material3.Text(stringResource(R.string.full_screen_ask_title)) },
        text = { androidx.compose.material3.Text(stringResource(R.string.full_screen_ask_body)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                done()
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:${context.packageName}")),
                    )
                }
            }) { androidx.compose.material3.Text(stringResource(R.string.overlay_ask_allow)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = ::done) { androidx.compose.material3.Text(stringResource(R.string.overlay_ask_later)) } },
    )
}

/** Save as file (2026-11-23, hen): the burst opened on the phone, written as .mkv where saved calls go, with Share. */
private fun saveBurst(context: android.content.Context, id: String, title: String, messages: com.chencohen.ptt.ui.style.Messages) {
    val app = context.applicationContext
    messages.post(app.getString(R.string.burst_saving))
    (app as com.chencohen.ptt.TalkApp).scope.launch {
        val contents = com.chencohen.ptt.clipContents(app, id)
        if (contents == null || contents.audio.isEmpty()) {
            messages.post(app.getString(R.string.burst_save_failed))
            return@launch
        }
        val made = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val scratch = java.io.File(app.cacheDir, "burst-$id.body")
            val file = java.io.File(app.cacheDir, "burst-$id.mkv")
            runCatching {
                file.outputStream().use { out -> com.chencohen.ptt.talk.BurstFile.make(contents, scratch, out) }
                file
            }.also { scratch.delete() }.getOrNull()
        }
        if (made == null) {
            messages.post(app.getString(R.string.burst_save_failed))
            return@launch
        }
        when (val saved = com.chencohen.ptt.ui.calls.saveKeptCall(app, made, title, contents.at)) {
            is com.chencohen.ptt.ui.calls.KeptSave.Saved -> messages.post(
                app.getString(R.string.burst_saved, saved.where), app.getString(R.string.burst_share),
            ) { com.chencohen.ptt.ui.calls.shareKeptCall(context, saved.uri) }
            else -> messages.post(app.getString(R.string.burst_save_failed))
        }
        made.delete()
    }
}

/** Live drafts in [chat] still fresh at [now]: (who, text); who is null in a one-to-one chat. */
/** Who has [chat] in view now: the contact, or a group's members (MessagingState.viewing). */
internal fun viewingIn(viewing: Map<String, Long>, chat: String, now: Long, group: Boolean): Set<String> =
    if (group) viewing.filter { (k, until) -> k.startsWith("$chat/") && until > now }.keys.map { it.substringAfter('/') }.toSet()
    else setOfNotNull(chat.takeIf { (viewing[chat] ?: 0L) > now })

/** Where each live writer's caret is in [chat]: by who ("" one-to-one), start to end; only those that said. */
internal fun liveCarets(drafts: Map<String, com.chencohen.ptt.messaging.Draft>, chat: String, group: Boolean): Map<String, Pair<Int, Int>> =
    if (group) {
        drafts.filter { (k, d) -> k.startsWith("$chat/") && d.selEnd >= 0 }.map { (k, d) -> k.substringAfter('/') to (d.selStart to d.selEnd) }.toMap()
    } else {
        drafts[chat]?.takeIf { it.selEnd >= 0 }?.let { mapOf("" to (it.selStart to it.selEnd)) } ?: emptyMap()
    }

internal fun liveDrafts(drafts: Map<String, com.chencohen.ptt.messaging.Draft>, chat: String, now: Long, group: Boolean): List<Pair<String?, String>> =
    if (group) {
        drafts.filter { (k, d) -> k.startsWith("$chat/") && now - d.at < com.chencohen.ptt.messaging.Draft.FRESH_MS }
            .map { (k, d) -> k.substringAfter('/') to d.text }
    } else {
        listOfNotNull(drafts[chat]?.takeIf { now - it.at < com.chencohen.ptt.messaging.Draft.FRESH_MS }?.let { null as String? to it.text })
    }

/** Crop and focus, open: [targets], the one shown first, and what Done does with the crops that changed. */
private class CropSession(
    val targets: List<com.chencohen.ptt.ui.media.CropTarget>,
    val start: Int,
    val apply: (Map<Pair<String, String>, com.chencohen.ptt.messaging.CropContent?>) -> Unit,
)
