package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvaluatorBreakdownTest {
    private fun board(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                      whiteOff: Int = 0, blackOff: Int = 0, toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff), toMove)

    @Test fun `breakdown sums to evaluate on contact positions`() {
        val p = IntArray(26); p[6] = 2; p[8] = 1; p[13] = 3; p[20] = -2; p[18] = -1; p[2] = -3
        val s = board(p)
        for (persp in listOf(Player.WHITE, Player.BLACK)) {
            val sum = Evaluator.breakdown(s, persp, Weights.FULL).sumOf { it.value }
            assertEquals(Evaluator.evaluate(s, persp, Weights.FULL), sum, 1e-9, "persp=$persp")
        }
        val sum0 = Evaluator.breakdown(startingPosition(), Player.WHITE, Weights.FULL).sumOf { it.value }
        assertEquals(Evaluator.evaluate(startingPosition(), Player.WHITE, Weights.FULL), sum0, 1e-9)
    }

    @Test fun `breakdown sums to evaluate AND yields only PIP-OFF on a race position`() {
        val a = IntArray(26); a[1] = 1; a[2] = 1; a[3] = 13; a[22] = -15
        val s = board(a)
        assertTrue(Features.noContact(s))
        val terms = Evaluator.breakdown(s, Player.WHITE, Weights.FULL)
        assertEquals(listOf(Feature.PIP, Feature.OFF), terms.map { it.feature })
        assertEquals(Evaluator.evaluate(s, Player.WHITE, Weights.FULL), terms.sumOf { it.value }, 1e-9)
    }

    @Test fun `contact breakdown lists all ten features in Weights field order`() {
        val p = IntArray(26); p[6] = 2; p[8] = 1; p[13] = 3; p[20] = -2; p[18] = -1; p[2] = -3
        val terms = Evaluator.breakdown(board(p), Player.WHITE, Weights.FULL)
        assertEquals(
            listOf(Feature.PIP, Feature.OFF, Feature.BLOT, Feature.HOME_POINT, Feature.KEY_POINT,
                   Feature.PRIME, Feature.ANCHOR, Feature.ADVANCED_ANCHOR, Feature.BAR, Feature.BACK_CHECKER),
            terms.map { it.feature },
        )
    }

    @Test fun `Feature count matches Weights field count`() {
        assertEquals(10, Feature.entries.size)
    }

    @Test fun `BAR contribution is non-zero with a checker on the bar`() {
        val s = board(IntArray(26).also { it[6] = 2; it[20] = -3 }, whiteBar = 1)
        val bar = Evaluator.breakdown(s, Player.WHITE, Weights.FULL).first { it.feature == Feature.BAR }
        assertTrue(bar.value != 0.0, "own checker on the bar should move the BAR term")
    }
}
