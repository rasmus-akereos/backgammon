package dk.rlunde.backgammon.viewmodel

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.DiceRoller
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.CubeResponse
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.game.GameController
import dk.rlunde.backgammon.game.Opponent
import dk.rlunde.backgammon.game.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelCubeAiTest {
    private class FixedRoller(private val d: Dice) : DiceRoller { override fun roll() = d }

    /**
     * BLACK comfortably ahead in a pure no-contact race; both sides have borne off exactly 1 checker
     * (so gammon is impossible → cubelessEquity stays below 1). BLACK (6 on pt22, 8 on pt21; pip≈50)
     * vs WHITE (6 on pt10, 8 on pt11; pip≈148). Satisfies: offerVerdict(BLACK,CENTERED)==DOUBLE and
     * responseVerdict(WHITE-on-roll,BLACK)==TAKE.
     */
    private fun blackLeads(toMove: Player) = BoardState(
        IntArray(26).also { it[22] = -6; it[21] = -8; it[10] = 6; it[11] = 8 },
        mapOf(Player.WHITE to 0, Player.BLACK to 0),
        mapOf(Player.WHITE to 1, Player.BLACK to 1),
        toMove,
    )

    private fun vm(scope: TestScope, initial: BoardState): GameViewModel {
        val v = GameViewModel(
            analysisDispatcher = StandardTestDispatcher(scope.testScheduler),
            controllerFactory = { aiSide -> GameController(initial = initial, roller = FixedRoller(Dice(3, 1)), aiSide = aiSide) },
            scopeOverride = scope,
        )
        v.startGame(GameConfig(opponent = Opponent.COMPUTER, humanColor = Player.WHITE))
        return v
    }

    /** WHITE far ahead (13 borne off); BLACK all on the board — the AI (BLACK) is hopeless. */
    private fun whiteLeads(toMove: Player) = BoardState(
        // WHITE 2 on point 1 (+13 off); BLACK 15 stuck on 19/21 (negative = BLACK). WHITE dominating.
        IntArray(26).also { it[1] = 2; it[19] = -8; it[21] = -7 },
        mapOf(Player.WHITE to 0, Player.BLACK to 0),
        mapOf(Player.WHITE to 13, Player.BLACK to 0),
        toMove,
    )

    @Test fun `AI takes a human double when not hopeless`() = runTest {
        val v = vm(this, blackLeads(Player.WHITE))  // human (WHITE) on roll; BLACK (AI) is ahead → takes
        v.onOfferDouble()
        advanceUntilIdle()
        assertEquals(2, v.uiState.value.cube.value)
        assertEquals(Player.BLACK, v.uiState.value.cube.owner)
        assertNotEquals(Phase.CUBE_OFFERED, v.uiState.value.phase)
    }

    @Test fun `AI drops a human double when hopeless`() = runTest {
        val v = vm(this, whiteLeads(Player.WHITE))  // human (WHITE) dominating → AI (BLACK) drops
        v.onOfferDouble()
        advanceUntilIdle()
        assertEquals(Phase.GAME_OVER, v.uiState.value.phase)
        assertEquals(Player.WHITE, v.uiState.value.winner)
        assertEquals(1, v.uiState.value.cube.value)
    }

    @Test fun `AI offers a double when well ahead`() = runTest {
        val v = vm(this, blackLeads(Player.BLACK))
        advanceUntilIdle()
        assertEquals(Phase.CUBE_OFFERED, v.uiState.value.phase)
    }

    @Test fun `human takes the AI double, then the AI resumes and plays`() = runTest {
        val v = vm(this, blackLeads(Player.BLACK))
        advanceUntilIdle()
        assertEquals(Phase.CUBE_OFFERED, v.uiState.value.phase)
        v.onRespondDouble(CubeResponse.TAKE)
        assertEquals(2, v.uiState.value.cube.value)
        assertEquals(Player.WHITE, v.uiState.value.cube.owner)
        advanceUntilIdle()                      // AI resumes its turn (rolls + plays)
        val s = v.uiState.value
        assertTrue(
            s.phase == Phase.GAME_OVER || (s.phase == Phase.NEED_ROLL && s.toMove == Player.WHITE),
            "after taking, the AI should resume and play, handing back to the human (or end the game); was ${s.phase}/${s.toMove}",
        )
    }

}
