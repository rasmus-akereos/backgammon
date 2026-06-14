package dk.rlunde.backgammon.viewmodel

import dk.rlunde.backgammon.ai.Difficulty
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.DiceRoller
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.game.GameController
import dk.rlunde.backgammon.game.Opponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for GameViewModel analysis orchestration (Task 7).
 *
 * Determinism strategy:
 * - [scopeOverride] is a [TestScope] backed by the test's [StandardTestDispatcher] — no Android
 *   Looper needed (avoids the `getMainLooper not mocked` crash).
 * - [analysisDispatcher] is a [StandardTestDispatcher] sharing the same [testScheduler], so
 *   [advanceUntilIdle] drains both the launch coroutine AND the withContext work in one call.
 * - [controllerFactory] injects a [FixedRoller] (known dice) with WHITE as the first mover
 *   and BLACK as the AI side, so the human (WHITE) always has the first turn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelAnalysisTest {

    /** A fixed roller that always returns the given dice. */
    private class FixedRoller(private val d: Dice) : DiceRoller {
        override fun roll() = d
    }

    /**
     * Build a VM wired for deterministic testing.
     * [testScope] is passed as [scopeOverride] so [scope.launch] doesn't touch [Dispatchers.Main].
     */
    private fun buildVm(testScope: TestScope, training: Boolean): GameViewModel {
        val dice = Dice(3, 1)
        val initial = startingPosition()
        return GameViewModel(
            analysisDispatcher = StandardTestDispatcher(testScope.testScheduler),
            controllerFactory = { aiSide ->
                GameController(
                    initial = initial,
                    roller = FixedRoller(dice),
                    aiSide = aiSide,
                )
            },
            scopeOverride = testScope,
        ).also { vm ->
            val config = GameConfig(
                difficulty = Difficulty.BEGINNER,
                humanColor = Player.WHITE,   // WHITE is the human; AI is BLACK
                opponent = Opponent.COMPUTER,
                training = training,
            )
            vm.startGame(config)
        }
    }

    /**
     * Stage + commit the first legal move for the given [dice] from the VM's current board.
     * Returns true if a move was available and committed.
     */
    private fun stageAndCommit(vm: GameViewModel, dice: Dice): Boolean {
        vm.onRoll()
        val board = vm.uiState.value.board
        val legal = MoveGenerator.legalMoves(board, dice)
        if (legal.isEmpty()) return false
        vm.controllerForTest().stageForTest(legal.first())
        vm.onCommit()
        return true
    }

    // -----------------------------------------------------------------------
    // (a) Training OFF — no analysis ever appears after a human commit
    // -----------------------------------------------------------------------

    @Test
    fun `training off - no analysis after human commit`() = runTest {
        val vm = buildVm(this, training = false)
        val committed = stageAndCommit(vm, Dice(3, 1))
        check(committed) { "No legal moves available for test setup" }

        advanceUntilIdle()

        assertNull(vm.uiState.value.analysis, "Analysis should be null when training is off")
    }

    // -----------------------------------------------------------------------
    // (b) Training ON, vs computer, human commits → analysis appears
    // -----------------------------------------------------------------------

    @Test
    fun `training on - analysis appears after human commit`() = runTest {
        val vm = buildVm(this, training = true)
        val committed = stageAndCommit(vm, Dice(3, 1))
        check(committed) { "No legal moves available for test setup" }

        advanceUntilIdle()

        assertNotNull(
            vm.uiState.value.analysis,
            "Analysis should be non-null after human commit with training on",
        )
    }

    // -----------------------------------------------------------------------
    // (c) Training ON — analysis cleared on new game
    // -----------------------------------------------------------------------

    @Test
    fun `training on - analysis cleared on new game`() = runTest {
        val vm = buildVm(this, training = true)
        val committed = stageAndCommit(vm, Dice(3, 1))
        check(committed) { "No legal moves available for test setup" }

        // Let the analysis complete
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.analysis, "pre-condition: analysis should be set")

        vm.onNewGame()
        advanceUntilIdle()

        assertNull(vm.uiState.value.analysis, "Analysis should be null after new game")
    }
}
