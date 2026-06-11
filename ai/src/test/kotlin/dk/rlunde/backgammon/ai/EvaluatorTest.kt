package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvaluatorTest {
    private fun board(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                      whiteOff: Int = 0, blackOff: Int = 0, toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff), toMove)

    @Test fun `zero-sum identity holds on the starting position`() {
        val s = startingPosition()
        val w = Evaluator.evaluate(s, Player.WHITE, Weights.FULL)
        val b = Evaluator.evaluate(s, Player.BLACK, Weights.FULL)
        assertEquals(w, -b, 1e-6)
    }

    @Test fun `zero-sum identity holds on an asymmetric contact position`() {
        val p = IntArray(26); p[6] = 2; p[8] = 1; p[13] = 3; p[20] = -2; p[18] = -1; p[2] = -3
        val s = board(p)
        assertEquals(Evaluator.evaluate(s, Player.WHITE, Weights.FULL),
                     -Evaluator.evaluate(s, Player.BLACK, Weights.FULL), 1e-6)
    }

    @Test fun `more checkers off scores higher`() {
        val a = board(IntArray(26).also { it[2] = 2 }, whiteOff = 13)
        val b = board(IntArray(26).also { it[2] = 4 }, whiteOff = 11)
        assertTrue(Evaluator.evaluate(a, Player.WHITE, Weights.FULL) >
                   Evaluator.evaluate(b, Player.WHITE, Weights.FULL))
    }

    @Test fun `no-contact position is scored by the race only`() {
        val a = IntArray(26); a[1] = 1; a[2] = 1; a[3] = 13; a[22] = -15
        val s = board(a)
        assertTrue(Features.noContact(s))
        val expected = Weights.FULL.pip * Features.pipDifferential(s, Player.WHITE) +
                       Weights.FULL.off * Features.offDifferential(s, Player.WHITE)
        assertEquals(expected, Evaluator.evaluate(s, Player.WHITE, Weights.FULL), 1e-6)
    }
}
