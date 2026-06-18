package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.ai.AiPlayer
import dk.rlunde.backgammon.core.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiTurnDriverTest {
    private fun controller(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                           toMove: Player = Player.WHITE, aiSide: Player?, seed: Long = 1) =
        GameController(
            initial = BoardState(points, mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
                mapOf(Player.WHITE to 0, Player.BLACK to 0), toMove),
            roller = SeededDiceRoller(seed), aiSide = aiSide)

    private class FakeAi : AiPlayer {
        var calls = 0
        override fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move {
            calls++; return legal.first()
        }
    }

    @Test fun `normal turn rolls, applies a move and flips to the human`() = runTest {
        val p = IntArray(26); p[13] = 2; p[12] = -2  // balanced (win-prob ~0.5): AI won't pre-roll double
        val c = controller(p, aiSide = Player.WHITE)
        val ai = FakeAi()
        val driver = AiTurnDriver(Player.WHITE, ai, StandardTestDispatcher(testScheduler),
            diceVisibleMs = 0, paceMs = 0)
        var publishes = 0
        driver.maybeRunTurn(c, onThinking = {}, publish = { publishes++ })
        testScheduler.advanceUntilIdle()
        assertEquals(Player.BLACK, c.uiState.toMove)
        assertTrue(ai.calls == 1)
        assertTrue(publishes >= 2)
    }

    @Test fun `no-legal-move turn passes without consulting the AI`() = runTest {
        val p = IntArray(26)
        for (i in 19..24) p[i] = -2
        val c = controller(p, whiteBar = 1, aiSide = Player.WHITE, seed = 1)
        val ai = FakeAi()
        val driver = AiTurnDriver(Player.WHITE, ai, StandardTestDispatcher(testScheduler), 0, 0)
        driver.maybeRunTurn(c, onThinking = {}, publish = {})
        testScheduler.advanceUntilIdle()
        assertEquals(0, ai.calls)
        assertEquals(Player.BLACK, c.uiState.toMove)
    }

    @Test fun `does nothing when it is not the AI's turn`() = runTest {
        val p = IntArray(26); p[13] = 2; p[12] = -2  // balanced (win-prob ~0.5): AI won't pre-roll double
        val c = controller(p, aiSide = Player.BLACK) // WHITE to move, AI is BLACK
        val ai = FakeAi()
        val driver = AiTurnDriver(Player.BLACK, ai, StandardTestDispatcher(testScheduler), 0, 0)
        driver.maybeRunTurn(c, onThinking = {}, publish = {})
        testScheduler.advanceUntilIdle()
        assertEquals(0, ai.calls)
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    }

    @Test fun `thinking flag is set then always cleared`() = runTest {
        val p = IntArray(26); p[13] = 2; p[12] = -2  // balanced (win-prob ~0.5): AI won't pre-roll double
        val c = controller(p, aiSide = Player.WHITE)
        val states = mutableListOf<Boolean>()
        val driver = AiTurnDriver(Player.WHITE, FakeAi(), StandardTestDispatcher(testScheduler), 0, 0)
        driver.maybeRunTurn(c, onThinking = { states.add(it) }, publish = {})
        testScheduler.advanceUntilIdle()
        assertEquals(true, states.first())
        assertEquals(false, states.last())
    }
}
