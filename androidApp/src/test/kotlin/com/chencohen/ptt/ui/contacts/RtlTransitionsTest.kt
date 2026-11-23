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
    fun theFlightLandsWhereTheSlotIsInEitherDirection() {
        // Window positions are left-based: a slot on the left (mine, in Hebrew) is placed on the left, not mirrored.
        val chat = Offset(0f, 200f)
        val slotOnTheLeft = Rect(24f, 900f, 400f, 980f)
        assertEquals(IntOffset(24, 700), flightOffset(slotOnTheLeft, chat))
    }
}
