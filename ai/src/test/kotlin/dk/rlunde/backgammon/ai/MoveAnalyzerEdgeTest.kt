package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoveAnalyzerEdgeTest {
    private val s = startingPosition()
    private val dice = Dice(3, 1)
    private val legal = MoveGenerator.legalMoves(s, dice)

    @Test fun `winProbDrop is non-negative and tracks evalLoss sign`() {
        val ranking = Expectimax.rankMoves(s, dice, legal, Weights.FULL, 1, NodeBudget(Int.MAX_VALUE))
        val worst = MoveAnalyzer.analyze(s, dice, ranking.last().move, legal)
        val best = MoveAnalyzer.analyze(s, dice, ranking.first().move, legal)
        assertTrue(worst.winProbDrop!! >= 0.0)
        assertEquals(0.0, best.winProbDrop!!, 1e-12)
    }

    @Test fun `game-ending move is terminal with no breakdown and no winProbDrop`() {
        // WHITE: 14 already off, 1 checker on the 1-point. BLACK: checkers far away on the 20-point.
        // A roll that bears off the last WHITE checker ends the game.
        val p = IntArray(26); p[1] = 1; p[20] = -2
        val board = BoardState(p,
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 14, Player.BLACK to 0), Player.WHITE)
        val d = Dice(1, 2)
        val lg = MoveGenerator.legalMoves(board, d)
        assertTrue(lg.isNotEmpty(), "expected at least one legal play")
        val winning = lg.firstOrNull { MoveGenerator.apply(board, it).offCount(Player.WHITE) == 15 }
        assertTrue(winning != null, "expected a game-ending bear-off play")
        val a = MoveAnalyzer.analyze(board, d, winning, lg)
        assertTrue(a.terminal)
        assertTrue(a.featureDeltas.isEmpty())
        assertNull(a.winProbDrop)
    }

    @Test fun `band never softens as evalLoss rises across the opening plays`() {
        val ranking = Expectimax.rankMoves(s, dice, legal, Weights.FULL, 1, NodeBudget(Int.MAX_VALUE))
        val analyses = ranking.map { MoveAnalyzer.analyze(s, dice, it.move, legal) }
        val order = listOf(Band.BEST, Band.GOOD, Band.INACCURACY, Band.MISTAKE, Band.BLUNDER)
        for (i in 1 until analyses.size) {
            if (analyses[i].evalLoss > analyses[i - 1].evalLoss) {
                assertTrue(order.indexOf(analyses[i].band) >= order.indexOf(analyses[i - 1].band),
                    "band softened as evalLoss rose at index $i")
            }
        }
    }
}
