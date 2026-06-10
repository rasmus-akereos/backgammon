package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WinScoringTest {
    private fun board(
        points: IntArray,
        whiteOff: Int = 0, blackOff: Int = 0,
        whiteBar: Int = 0, blackBar: Int = 0,
        toMove: Player = Player.WHITE,
    ) = BoardState(
        points,
        bar = mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
        off = mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff),
        toMove,
    )

    @Test fun `not over while both have checkers in play`() {
        assertFalse(Scoring.isGameOver(startingPosition()))
        assertNull(Scoring.winnerAndValue(startingPosition()))
    }

    @Test fun `single win - loser has borne off at least one`() {
        val p = IntArray(26); p[19] = -12
        val s = board(p, whiteOff = 15, blackOff = 3)
        assertTrue(Scoring.isGameOver(s))
        assertEquals(Player.WHITE to 1, Scoring.winnerAndValue(s))
    }

    @Test fun `gammon - loser borne off none, no checker on bar or in winner home`() {
        val p = IntArray(26); p[19] = -15
        val s = board(p, whiteOff = 15)
        assertEquals(Player.WHITE to 2, Scoring.winnerAndValue(s))
    }

    @Test fun `backgammon - loser borne off none with a checker in winner home (white wins)`() {
        val p = IntArray(26); p[3] = -1; p[19] = -14
        val s = board(p, whiteOff = 15)
        assertEquals(Player.WHITE to 3, Scoring.winnerAndValue(s))
    }

    @Test fun `backgammon - loser borne off none with a checker on the bar`() {
        val p = IntArray(26); p[19] = -14
        val s = board(p, whiteOff = 15, blackBar = 1)
        assertEquals(Player.WHITE to 3, Scoring.winnerAndValue(s))
    }

    @Test fun `backgammon - black wins, white checker in black home 19 to 24`() {
        val p = IntArray(26); p[22] = 1; p[6] = 14
        val s = board(p, blackOff = 15, toMove = Player.BLACK)
        assertEquals(Player.BLACK to 3, Scoring.winnerAndValue(s))
    }

    @Test fun `near-miss - loser in winner home but borne off one is only single`() {
        val p = IntArray(26); p[3] = -1; p[19] = -13
        val s = board(p, whiteOff = 15, blackOff = 1)
        assertEquals(Player.WHITE to 1, Scoring.winnerAndValue(s))
    }
}
