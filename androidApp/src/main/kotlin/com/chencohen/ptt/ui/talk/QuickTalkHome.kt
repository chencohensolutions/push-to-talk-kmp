package com.chencohen.ptt.ui.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Group
import com.chencohen.ptt.talk.TalkPhase
import com.chencohen.ptt.talk.TalkUiState
import com.chencohen.ptt.ui.contacts.Avatar
import com.chencohen.ptt.ui.groups.GroupAvatar
import com.chencohen.ptt.ui.style.EmptyState
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType

/** One page of the Talk tab: a Quick talk chat, with its name and picture. */
internal data class QuickTalkPage(val chat: String, val name: String, val groupPicture: String? = null)

/**
 * The Talk tab (docs/screens/quick-talk.md): one page per Quick talk chat, swiped sideways, each with the chat's
 * picture and name (a tap opens the conversation), what the floor is doing, and the hold-to-talk circle; dots under
 * it say which page. The page shown last is where it opens again ([pageShown]). With none, how to add one.
 */
@Composable
internal fun QuickTalkHome(
    pages: List<QuickTalkPage>,
    startPage: Int,
    me: String,
    talk: TalkUiState,
    nameOf: (String) -> String,
    press: (chat: String) -> Unit,
    release: () -> Unit,
    openChat: (String) -> Unit,
    openChats: () -> Unit,
    pageShown: (String) -> Unit,
    micDenied: () -> Unit,
    modifier: Modifier = Modifier,
    /** The microphone kept open while the Talk tab shows a page (MessagingController.warmMicrophone). */
    warmMicrophone: (Boolean) -> Unit = {},
    /** A voice or video call in a page's chat. */
    call: (chat: String, video: Boolean) -> Unit = { _, _ -> },
    /** Push-to-video: on, switched here; the camera kept warm for the page shown. */
    pushVideo: Boolean = false,
    setPushVideo: (Boolean) -> Unit = {},
    warmCamera: (String?) -> Unit = {},
) {
    if (pages.isEmpty()) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            EmptyState(stringResource(R.string.quick_talk_empty), actionLabel = stringResource(R.string.quick_talk_open_chats), onAction = openChats)
        }
        return
    }
    WarmMicrophone(warmMicrophone)
    val microphone = rememberMicrophone(micDenied)
    val pager = rememberPagerState(initialPage = startPage.coerceIn(0, pages.size - 1)) { pages.size }
    // Talking keeps the page: a swipe while the button is held would leave the floor in another chat.
    val holding = talk.own?.let { it.phase == TalkPhase.REQUESTING || it.phase == TalkPhase.TALKING } == true
    LaunchedEffect(pager, pages) {
        snapshotFlow { pager.currentPage }.collect { i -> pages.getOrNull(i)?.let { pageShown(it.chat) } }
    }
    Column(modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !holding,
            contentPadding = PaddingValues(horizontal = 0.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            key = { pages[it].chat },
        ) { i ->
            val page = pages[i]
            val own = talk.own?.takeIf { it.chat == page.chat }
            val live = own?.phase == TalkPhase.TALKING
            val someone = talk.talkers[page.chat]?.takeIf { it.user != me }
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier
                        .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                        .clickable(role = Role.Button) { openChat(page.chat) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (Group.idOf(page.chat) != null) GroupAvatar(page.name, page.groupPicture, 48.dp) else Avatar(page.chat, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        page.name,
                        color = LibraryColors.OnBackground,
                        style = LibraryType.PageTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                // Only while someone talks (no hint text otherwise).
                val line = talkLine(page.chat, talk, nameOf, me)
                if (line.isNotEmpty()) {
                    Text(
                        line,
                        color = if (live || someone != null) LibraryColors.Accent else LibraryColors.Muted,
                        style = LibraryType.RowTitle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                // The page on screen keeps the camera warm for its chat.
                if (pager.currentPage == i) WarmCamera(page.chat, pushVideo, warmCamera)
                // Centred above the button: someone's video burst, or this phone's camera while Video is on.
                val video = talk.playing?.takeIf { it.chat == page.chat && it.video } != null
                TalkStage(
                    video, pushVideo && pager.currentPage == i,
                    circle = { size ->
                        TalkCircle(
                            live = live,
                            someoneTalking = someone != null,
                            press = { microphone { press(page.chat) } },
                            release = release,
                            diameter = size,
                        )
                    },
                    actions = {
                        TalkChatActions(
                            openChat = { openChat(page.chat) }, call = { v -> call(page.chat, v) },
                            pushVideo = pushVideo, setPushVideo = setPushVideo,
                        )
                    },
                )
            }
        }
        if (pages.size > 1) PageDots(pages.size, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
    }
}

/** Which page: a dot each, the current one in ink. */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.quick_talk_page, current + 1, count)
    Row(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            Box(Modifier.size(8.dp).background(if (i == current) LibraryColors.Inverse else LibraryColors.Muted.copy(alpha = 0.35f), CircleShape))
        }
    }
}
