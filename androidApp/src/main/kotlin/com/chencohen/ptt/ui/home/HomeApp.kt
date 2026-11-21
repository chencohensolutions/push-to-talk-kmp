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

/** What the home screens render: the server's health and who this is (the profile circle's letter). */
data class HomeState(val health: ServerHealth, val user: String?)

class HomeActions(
    val openSettings: () -> Unit = {},
    val registerAgain: () -> Unit = {},
    val newChat: () -> Unit = {},
)

/** Home and Settings: the routing after enrollment. */
@Composable
fun HomeApp(connection: ConnectionController, settings: SettingsController, onRegisterAgain: () -> Unit) {
    val state by connection.state.collectAsStateWithLifecycle()
    var inSettings by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(HomeTab.CHATS) }
    val messages = remember { Messages() }
    BackHandler(enabled = inSettings) { inSettings = false }
    CompositionLocalProvider(LocalMessages provides messages) {
        Box(Modifier.fillMaxSize()) {
            if (inSettings) {
                val settingsState by settings.state.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = settingsState,
                    back = { inSettings = false },
                    refresh = settings::refresh,
                    registerAgain = onRegisterAgain,
                )
            } else {
                val note = stringResource(R.string.chats_new_soon)
                HomeScreen(
                    state = HomeState(state.health, state.device?.user),
                    tab = tab,
                    selectTab = { tab = it },
                    actions = HomeActions(
                        openSettings = { inSettings = true },
                        registerAgain = onRegisterAgain,
                        newChat = { messages.post(note) },
                    ),
                )
            }
            MessageHost(messages, Modifier.align(Alignment.BottomCenter).navigationBarRoom().padding(bottom = 88.dp))
        }
    }
}

/**
 * Chats or Calls: the top bar (the profile circle that opens Settings, the tab's title, Offline while the server
 * can't be reached), Register again when the server refuses this phone, the tab's list (empty in phase 1), the
 * pencil, and the tabs at the bottom.
 */
@Composable
internal fun HomeScreen(state: HomeState, tab: HomeTab, selectTab: (HomeTab) -> Unit, actions: HomeActions) {
    val insets = barsRoom()
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
            Spacer(Modifier.height(96.dp))
            when (tab) {
                HomeTab.CHATS -> EmptyTab(LibraryIcons.Chat, stringResource(R.string.chats_empty))
                HomeTab.CALLS -> EmptyTab(LibraryIcons.Call, stringResource(R.string.calls_empty))
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

/**
 * The pencil for a new chat. Pairing comes in phase 2, so it looks disabled (Muted on Raised) and a tap only says
 * that it comes next.
 */
@Composable
private fun NewChatButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.chats_new)
    Box(
        modifier
            .size(56.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(LibraryColors.Raised)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(LibraryIcons.Edit, null, tint = LibraryColors.Muted, modifier = Modifier.size(24.dp))
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
