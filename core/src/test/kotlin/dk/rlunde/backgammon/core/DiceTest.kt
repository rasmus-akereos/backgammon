package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiceTest {
    @Test fun `non-double yields two pips`() {
        assertEquals(listOf(3, 5), Dice(3, 5).pips())
        assertFalse(Dice(3, 5).isDouble)
    }

    @Test fun `double yields four pips`() {
        assertEquals(listOf(4, 4, 4, 4), Dice(4, 4).pips())
        assertTrue(Dice(4, 4).isDouble)
    }

    @Test fun `die values outside 1-6 are rejected`() {
        assertFailsWith<IllegalArgumentException> { Dice(0, 3) }
        assertFailsWith<IllegalArgumentException> { Dice(3, 7) }
    }
}
