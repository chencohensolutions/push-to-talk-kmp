package com.chencohen.ptt.ui.contacts

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** The chat's transitions in Hebrew (2026-11-24, hen's phone is right-to-left). */
class RtlTransitionsTest {
    @Test
    fun theirBubblesGrowFromTheRightInHebrew() {
        assertEquals(1f, sideOrigin(mine = false, rtl = true, y = 1f).pivotFractionX)
        assertEquals(0f, sideOrigin(mine = false, rtl = false, y = 1f).pivotFractionX)
    }

    @Test
    fun myNewDraftPopsUpFromTheLeftInHebrew() {
        assertEquals(0f, sideOrigin(mine = true, rtl = true, y = 0.5f).pivotFractionX)
        assertEquals(1f, sideOrigin(mine = true, rtl = false, y = 0.5f).pivotFractionX)
    }

    @Test
    fun myWritingBubbleIsAnchoredOnMySide() {
        // In Hebrew my side is the left: the bubble starts at the left edge and grows toward the middle, never off
        // the screen; in English it's the right.
        val bubble = androidx.compose.ui.unit.IntSize(600, 120)
        val row = androidx.compose.ui.unit.IntSize(1080, 120)
        assertEquals(0, MySide.align(bubble, row, androidx.compose.ui.unit.LayoutDirection.Rtl).x)
        assertEquals(480, MySide.align(bubble, row, androidx.compose.ui.unit.LayoutDirection.Ltr).x)
        assertEquals(480, TheirSide.align(bubble, row, androidx.compose.ui.unit.LayoutDirection.Rtl).x)
    }

    @Test
    fun theFlightLandsWhereTheSlotIsInEitherDirection() {
        // Window positions are left-based: a slot on the left (mine, in Hebrew) is placed on the left, not mirrored.
        val chat = Offset(0f, 200f)
        val slotOnTheLeft = Rect(24f, 900f, 400f, 980f)
        assertEquals(IntOffset(24, 700), flightOffset(slotOnTheLeft, chat))
    }
}
