package dk.rlunde.backgammon.ui

import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.SubMove
import dk.rlunde.backgammon.ui.screens.notation
import kotlin.test.Test
import kotlin.test.assertEquals

class MoveNotationTest {
    @Test fun `plain two-checker play`() {
        assertEquals("24/23 13/11", notation(Move(listOf(
            SubMove(24, 23, 1, false), SubMove(13, 11, 2, false)))))
    }
    @Test fun `hit is marked with asterisk`() {
        assertEquals("13/11*", notation(Move(listOf(SubMove(13, 11, 2, true)))))
    }
    @Test fun `bear-off uses off`() {
        assertEquals("6/off", notation(Move(listOf(SubMove(6, 0, 6, false)))))
    }
    @Test fun `bar entry uses bar`() {
        assertEquals("bar/20", notation(Move(listOf(SubMove(25, 20, 5, false)))))
    }
}
