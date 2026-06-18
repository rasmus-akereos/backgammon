package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.ai.AiPlayer
import dk.rlunde.backgammon.ai.CubeAdvisor
import dk.rlunde.backgammon.ai.CubePolicy
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.Player
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs ONE AI turn on a [GameController]: roll → (auto-pass | choose+apply), paced for readability.
 * Pure of Android; the dispatcher and delays are injected so it is unit-testable with a TestDispatcher.
 * The VM owns the coroutine/lifecycle and calls this from viewModelScope. See spec §5.2.
 */
class AiTurnDriver(
    private val aiSide: Player?,
    private val ai: AiPlayer?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val diceVisibleMs: Long = 600,
    private val paceMs: Long = 800,
) {
    private var running = false

    /** No-op unless it is the AI's turn and not already running. [onThinking]/[publish] drive the UI. */
    suspend fun maybeRunTurn(
        controller: GameController,
        onThinking: (Boolean) -> Unit,
        publish: () -> Unit,
    ) {
        if (ai == null || aiSide == null || running) return
        if (controller.uiState.toMove != aiSide) return
        if (controller.uiState.phase == Phase.GAME_OVER) return
        if (controller.uiState.phase == Phase.CUBE_OFFERED) return  // a double is pending — not our action

        // Pre-roll: offer a double when clearly ahead and the cube is available.
        if (controller.uiState.cube.mayDouble(aiSide) &&
            CubePolicy.shouldDouble(CubeAdvisor.winProb(controller.uiState.board, aiSide))) {
            controller.offerDouble(); publish()
            return
        }

        running = true
        onThinking(true); publish()
        try {
            val events = controller.roll()
            publish()
            if (diceVisibleMs > 0) delay(diceVisibleMs)
            if (UiEvent.NoLegalMoves in events) {
                controller.acknowledgePass(); publish()
            } else {
                val board = controller.uiState.board
                val dice = controller.uiState.dice!!
                val move = withContext(dispatcher) {
                    ai.chooseMove(board, dice, MoveGenerator.legalMoves(board, dice))
                }
                if (paceMs > 0) delay(paceMs)
                controller.applyMove(move); publish()
            }
        } finally {
            running = false
            onThinking(false); publish()
        }
    }
}
