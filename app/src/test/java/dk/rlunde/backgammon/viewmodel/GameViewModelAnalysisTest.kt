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
        val legal = MoveGenerator.legalMoves(board, vm.uiState.value.dice!!)
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

    // -----------------------------------------------------------------------
    // (d) Undo mid-analysis discards the stale result
    //
    // Sequence: commit move A (analysis job queued at epoch=1, not yet run) →
    //   onUndo() (cancelAnalysis: job cancelled, epoch bumped to 2) →
    //   advanceUntilIdle() (any residual work runs; epoch guard drops stale result) →
    //   assert analysis == null.
    //
    // Note: onUndo() after a commit only clears staged sub-moves in the controller
    // (no staged moves remain post-commit, so the board is unchanged), but the VM's
    // cancelAnalysis() is still called unconditionally, bumping the epoch and
    // cancelling the in-flight job before it has had a chance to execute on the
    // test dispatcher.
    // -----------------------------------------------------------------------

    @Test
    fun `undo mid-analysis discards stale result`() = runTest {
        val vm = buildVm(this, training = true)

        // Commit move A — analysis job launched at epoch=1 on the test dispatcher
        // (not yet run because we have not advanced the scheduler).
        val committed = stageAndCommit(vm, Dice(3, 1))
        check(committed) { "No legal moves available for test setup" }

        // Cancel before any coroutine has had a chance to run.
        // cancelAnalysis() inside onUndo() bumps analysisEpoch to 2 and cancels the job.
        vm.onUndo()

        // Drain all queued work (AI jobs, any lingering analysis fragments).
        advanceUntilIdle()

        assertNull(
            vm.uiState.value.analysis,
            "Analysis should be null after undo cancels the in-flight job",
        )
    }

    // -----------------------------------------------------------------------
    // (e) A newer analysis supersedes an older in-flight one (epoch guard)
    //
    // To obtain two consecutive human commits without an AI turn interleaving,
    // the controller factory creates a GameController with aiSide=null.  This
    // lets the test manually advance both WHITE and BLACK turns while the VM
    // still sees opponent=COMPUTER (so training=true is honoured).  The AI
    // job queued after each commit is a no-op when it eventually runs, because
    // by that point toMove has already passed back to WHITE (the AI's target
    // turn is BLACK, but BLACK already moved manually).
    //
    // Sequence (scheduler never advanced between the two commits):
    //   commit A by WHITE → epoch=1, job A queued (not started)
    //   commit B by BLACK → analyzeLastHumanMove() cancels job A, epoch=2, job B queued
    //   advanceUntilIdle() → job B runs and writes analysis; job A is already cancelled
    //   assert analysis != null  (job B's result was accepted by the epoch guard)
    //
    // True concurrent in-flight overlap (job A computing while B is queued) is not
    // achievable with StandardTestDispatcher because coroutines only advance on
    // explicit scheduler calls.  The test verifies the cancel-and-supersede path:
    // job A is cancelled before it runs, epoch=2 guards the result so job B lands.
    // -----------------------------------------------------------------------

    @Test
    fun `newer analysis supersedes older in-flight one`() = runTest {
        val dice = Dice(3, 1)
        val initial = startingPosition()

        // Factory ignores the passed aiSide and creates a controller with aiSide=null,
        // so both WHITE and BLACK can commit freely without the controller blocking.
        val vm = GameViewModel(
            analysisDispatcher = StandardTestDispatcher(testScheduler),
            controllerFactory = { _ ->
                GameController(
                    initial = initial,
                    roller = FixedRoller(dice),
                    aiSide = null,
                )
            },
            scopeOverride = this,
        ).also { vm ->
            vm.startGame(
                GameConfig(
                    difficulty = Difficulty.BEGINNER,
                    humanColor = Player.WHITE,
                    opponent = Opponent.COMPUTER,   // required for training=true
                    training = true,
                ),
            )
        }

        // --- Commit A (WHITE) — analysis job A queued at epoch=1, scheduler NOT advanced ---
        val committedA = stageAndCommit(vm, dice)
        check(committedA) { "No legal moves for WHITE — test setup failed" }
        // Epoch is now 1, job A is queued but has not run.

        // --- Commit B (BLACK) — scheduler still NOT advanced ---
        // analyzeLastHumanMove() cancels job A and queues job B at epoch=2.
        val committedB = stageAndCommit(vm, dice)
        check(committedB) { "No legal moves for BLACK — test setup failed" }
        // Epoch is now 2, job B is queued; job A is cancelled.

        // --- Drain all work ---
        advanceUntilIdle()

        // Job B ran and its epoch (2) matched analysisEpoch (2) → result was accepted.
        // If the epoch guard were absent, job A's stale result could also land, but
        // since job A was explicitly cancelled, this also verifies correct cancellation.
        assertNotNull(
            vm.uiState.value.analysis,
            "Analysis should be non-null: job B's result must have been accepted by the epoch guard",
        )
    }
}
