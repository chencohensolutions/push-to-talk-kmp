package com.chencohen.ptt.talk

import kotlin.test.Test
import kotlin.test.assertEquals

class TalkWidgetStateTest {
    private val empty = TalkUiState()

    @Test
    fun idleUntilSomethingHappensInItsChat() {
        assertEquals(TalkWidgetView.Idle, TalkWidgetView.of("dana", empty, "hen", 0))
        val elsewhere = empty.copy(own = OwnTalk("avi", "c1", TalkPhase.TALKING, 0))
        assertEquals(TalkWidgetView.Idle, TalkWidgetView.of("dana", elsewhere, "hen", 5_000))
    }

    @Test
    fun talkingCountsSecondsAndBusyNamesTheTalker() {
        val talking = empty.copy(own = OwnTalk("dana", "c1", TalkPhase.TALKING, 1_000))
        assertEquals(TalkWidgetView.Talking(3), TalkWidgetView.of("dana", talking, "hen", 4_200))
        val asking = empty.copy(own = OwnTalk("dana", "c1", TalkPhase.REQUESTING, 1_000))
        assertEquals(TalkWidgetView.Talking(0), TalkWidgetView.of("dana", asking, "hen", 1_100))
        val busy = empty.copy(own = OwnTalk("dana", "c1", TalkPhase.BUSY, 0, busyWith = "dana"))
        assertEquals(TalkWidgetView.Busy("dana"), TalkWidgetView.of("dana", busy, "hen", 0))
    }

    @Test
    fun incomingFromTheFloorOrWhatPlays() {
        val floor = empty.copy(talkers = mapOf("g:family" to Talker("avi", 1, "c1", 0)))
        assertEquals(TalkWidgetView.Incoming("avi"), TalkWidgetView.of("g:family", floor, "hen", 0))
        val mine = empty.copy(talkers = mapOf("dana" to Talker("hen", 2, "c1", 0)))
        assertEquals(TalkWidgetView.Idle, TalkWidgetView.of("dana", mine, "hen", 0))
        val playing = empty.copy(playing = NowPlaying("dana", "c1", "dana", live = true))
        assertEquals(TalkWidgetView.Incoming("dana"), TalkWidgetView.of("dana", playing, "hen", 0))
    }

    @Test
    fun quickTalkChatsFirst() {
        val all = listOf("dana" to "Dana", "g:f" to "Family", "avi" to "Avi", "noa" to "Noa")
        assertEquals(
            listOf("g:f" to "Family", "dana" to "Dana", "avi" to "Avi", "noa" to "Noa"),
            TalkWidgetChoices.order(all, listOf("g:f", "dana", "gone")),
        )
    }
}
