package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.DiceRoller
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GameControllerCommitTest {
    private class FixedRoller(private val d: Dice) : DiceRoller { override fun roll() = d }

    @Test fun `commit records the played turn and pre-move context`() {
        val c = GameController(initial = startingPosition(), roller = FixedRoller(Dice(3, 1)))
        assertNull(c.lastCommitted)
        c.roll()
        val pre = c.uiState.board
        val dice = c.uiState.dice!!
        val move = MoveGenerator.legalMoves(pre, dice).first()
        c.stageForTest(move)
        c.commit()
        val lc = c.lastCommitted
        assertNotNull(lc)
        assertEquals(pre, lc.preBoard)
        assertEquals(dice, lc.dice)
        assertEquals(move, lc.move)
    }
}
