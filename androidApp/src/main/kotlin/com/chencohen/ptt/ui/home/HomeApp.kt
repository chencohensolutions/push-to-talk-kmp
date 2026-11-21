package com.chencohen.ptt.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import com.chencohen.ptt.ui.contacts.InviteActions
import com.chencohen.ptt.ui.contacts.InviteScreen
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

/** The home tabs, Signal's two (docs/screens/chats.md). */
enum class HomeTab { CHATS, CALLS }

/** What the home screens render: the server's health, who this is (the profile circle's letter), and messaging's. */
data class HomeState(val health: ServerHealth, val user: String?, val messaging: MessagingState = MessagingState())

class HomeActions(
    val openSettings: () -> Unit = {},
    val registerAgain: () -> Unit = {},
    val newChat: () -> Unit = {},
    val reviewRequest: (String) -> Unit = {},
    val openContact: (String) -> Unit = {},
    val replaceIdentityKey: () -> Unit = {},
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
) {
    val state by connection.state.collectAsStateWithLifecycle()
    val messagingState by messaging.state.collectAsStateWithLifecycle()
    var saved by rememberSaveable { mutableStateOf("") }
    val stack = pages(saved)
    fun push(page: String) {
        saved = (stack + page).joinToString("\n")
    }
    fun pop() {
        saved = stack.dropLast(1).joinToString("\n")
    }
    fun home() {
        saved = ""
    }
    var tab by rememberSaveable { mutableStateOf(HomeTab.CHATS) }
    val messages = remember { Messages() }
    val context = LocalContext.current
    val resources = LocalResources.current
    BackHandler(enabled = stack.isNotEmpty()) { pop() }
    // An invite link: Add contact asks "Pair with them?".
    LaunchedEffect(openedInvite) {
        if (openedInvite != null && stack.lastOrNull() != "join") push("join")
    }
    CompositionLocalProvider(LocalMessages provides messages) {
        Box(Modifier.fillMaxSize()) {
            val page = stack.lastOrNull()
            val name = page.orEmpty()
            val pageUser = page?.substringAfter('/', "")?.takeIf { it.isNotEmpty() }
            when {
                page == null -> HomeScreen(
                    state = HomeState(state.health, state.device?.user, messagingState),
                    tab = tab,
                    selectTab = { tab = it },
                    actions = HomeActions(
                        openSettings = { push("settings") },
                        registerAgain = onRegisterAgain,
                        newChat = { push("new") },
                        reviewRequest = { push("review/$it") },
                        openContact = { push("contact/$it") },
                        replaceIdentityKey = {
                            messaging.replaceIdentityKey { result ->
                                if (result is ContactResult.Failed) messages.post(resources.getString(R.string.contact_failed, result.message))
                            }
                        },
                    ),
                )
                name == "settings" -> {
                    val settingsState by settings.state.collectAsStateWithLifecycle()
                    SettingsScreen(
                        state = settingsState,
                        back = ::pop,
                        refresh = settings::refresh,
                        registerAgain = onRegisterAgain,
                    )
                }
                name == "new" -> NewChatScreen(
                    contacts = messagingState.contacts,
                    back = ::pop,
                    addContact = { push("add") },
                    openContact = { push("contact/$it") },
                )
                name == "invite" -> {
                    val pairingState by pairing.state.collectAsStateWithLifecycle()
                    DisposableEffect(page) { onDispose { pairing.clearInvite() } }
                    InviteScreen(
                        state = pairingState,
                        actions = InviteActions(
                            back = ::pop,
                            make = pairing::makeInvite,
                            share = { invite ->
                                val text = resources.getString(R.string.invite_share_text, invite.inviter, invite.link)
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                                context.startActivity(Intent.createChooser(send, resources.getString(R.string.invite_share)))
                            },
                            copy = { invite ->
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.invite_title), invite.link))
                                messages.post(resources.getString(R.string.invite_copied))
                            },
                            another = pairing::clearInvite,
                        ),
                    )
                }
                name == "add" || name == "join" || name.startsWith("review/") -> {
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
                name.startsWith("contact/") && pageUser != null -> {
                    var sending by remember(pageUser) { mutableStateOf(false) }
                    val user = pageUser
                    ContactScreen(
                        user = user,
                        contact = messagingState.contacts.firstOrNull { it.user == user },
                        last = messagingState.lastMessages[user],
                        sending = sending,
                        actions = ContactActions(
                            back = ::pop,
                            acceptKeyChange = { messaging.acceptKeyChange(user) },
                            openSafetyNumber = { push("safety/$user") },
                            sendTest = { body ->
                                sending = true
                                messaging.sendTest(user, body) { result ->
                                    sending = false
                                    messages.post(
                                        when (result) {
                                            ContactResult.Done -> resources.getString(R.string.contact_test_sent, user)
                                            is ContactResult.Failed -> resources.getString(R.string.contact_failed, result.message)
                                        },
                                    )
                                }
                            },
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
                        ),
                    )
                }
                name.startsWith("safety/") && pageUser != null -> {
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
            MessageHost(messages, Modifier.align(Alignment.BottomCenter).navigationBarRoom().imePadding().padding(bottom = if (page == null) 88.dp else 16.dp))
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
        Column(
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
            when (tab) {
                HomeTab.CHATS -> {
                    if (chats.keys is KeysState.OtherIdentity) {
                        HomeCard(
                            title = stringResource(R.string.chats_other_identity_title),
                            body = stringResource(R.string.chats_other_identity_body),
                            action = stringResource(R.string.chats_other_identity_action),
                            onAction = actions.replaceIdentityKey,
                            warning = true,
                        )
                    }
                    (chats.keys as? KeysState.Failed)?.let { PageNote(stringResource(R.string.chats_keys_failed, it.message), top = 4, bottom = 4) }
                    chats.requests.forEach { request ->
                        HomeCard(
                            title = stringResource(R.string.chats_request_title, request.user),
                            body = stringResource(R.string.chats_request_body),
                            action = stringResource(R.string.chats_request_action),
                            onAction = { actions.reviewRequest(request.user) },
                        )
                    }
                    if (chats.contacts.isEmpty()) {
                        Spacer(Modifier.height(96.dp))
                        EmptyTab(LibraryIcons.Chat, stringResource(R.string.chats_empty))
                    } else {
                        // The latest text first, then the newest contacts.
                        chats.contacts
                            .sortedWith(compareByDescending<Contact> { chats.lastMessages[it.user]?.at ?: 0L }.thenByDescending { it.addedAt })
                            .forEach { contact -> ContactRow(contact, chats.lastMessages[contact.user]) { actions.openContact(contact.user) } }
                    }
                }
                HomeTab.CALLS -> {
                    Spacer(Modifier.height(96.dp))
                    EmptyTab(LibraryIcons.Call, stringResource(R.string.calls_empty))
                }
            }
        }
        TopBar(state, tab, actions.openSettings, Modifier.align(Alignment.TopCenter))
        if (tab == HomeTab.CHATS) {
            NewChatButton(actions.newChat, Modifier.align(Alignment.BottomEnd).navigationBarRoom().padding(end = 20.dp, bottom = 84.dp))
        }
        SegmentedBar(
            options = listOf(HomeTab.CHATS to stringResource(R.string.chats_title), HomeTab.CALLS to stringResource(R.string.calls_title)),
            selected = tab,
            onSelect = selectTab,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarRoom().padding(bottom = 16.dp),
        )
    }
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
            stringResource(if (tab == HomeTab.CHATS) R.string.chats_title else R.string.calls_title),
            color = LibraryColors.OnBackground,
            style = LibraryType.ScreenTitle,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (state.health == ServerHealth.OFFLINE) OfflinePill()
    }
}

/** The user's first letter in an ink circle (no pictures yet); opens Settings. */
@Composable
private fun ProfileCircle(user: String?, onClick: () -> Unit) {
    val label = stringResource(R.string.chats_profile)
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
