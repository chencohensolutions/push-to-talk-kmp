package com.chencohen.ptt.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GenderTest {
    @Test
    fun oneOrManyPicksTheVariant() {
        assertEquals("_m", Gender.suffix(Gender.MALE))
        assertEquals("_f", Gender.suffix(Gender.FEMALE))
        assertNull(Gender.suffix(Gender.NOT_SET), "not set: the slash form")
        assertNull(Gender.suffix(7), "an unknown value from a newer app: the slash form")
        assertEquals("_many", Gender.suffixFor(listOf(Gender.FEMALE, Gender.MALE)))
        assertEquals("_f", Gender.suffixFor(listOf(Gender.FEMALE)))
        assertNull(Gender.suffixFor(emptyList()))
    }
}
