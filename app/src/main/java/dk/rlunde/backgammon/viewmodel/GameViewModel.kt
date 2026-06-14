package dk.rlunde.backgammon.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dk.rlunde.backgammon.ai.HeuristicAiPlayer
import dk.rlunde.backgammon.ai.MoveAnalysis
import dk.rlunde.backgammon.ai.MoveAnalyzer
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import dk.rlunde.backgammon.game.AiTurnDriver
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.game.GameController
import dk.rlunde.backgammon.game.GameUiState
import dk.rlunde.backgammon.game.Opponent
import dk.rlunde.backgammon.game.UiEvent
import dk.rlunde.backgammon.ui.board.BoardTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class GameViewModel internal constructor(
    /**
     * Dispatcher used for off-main-thread analysis work.
     * Default: [Dispatchers.Default]. Inject [kotlinx.coroutines.test.StandardTestDispatcher]
     * in tests to make analysis deterministic.
     */
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Factory used to create a [GameController] for each new game. Receives the aiSide
     * (null for hot-seat). The default creates a real controller with a random roller and
     * the standard starting position.
     */
    private val controllerFactory: (Player?) -> GameController = { side ->
        GameController(initial = startingPosition(), aiSide = side)
    },
    /**
     * Coroutine scope for launching background work. Defaults to [viewModelScope] (lazy) so
     * production is unaffected. Tests inject a [TestScope] to avoid Android's Looper.
     */
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    /** The scope actually used — either the injected override or the real viewModelScope. */
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private var controller = GameController()
    private var aiPlayer: HeuristicAiPlayer? = null
    private var aiSide: Player? = null
    private var driver: AiTurnDriver? = null
    private var aiJob: Job? = null
    private var aiThinking = false
    private var started = false
    private val rng = Random.Default

    private var training = false
    private var analysisJob: Job? = null
    private var analysis: MoveAnalysis? = null
    private var analysisEpoch = 0

    private val _uiState = MutableStateFlow(controller.uiState)
    val uiState: StateFlow<GameUiState> = _uiState

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Idempotent: only configures on the first call (SETUP -> GAME), never on recomposition. */
    fun startGame(config: GameConfig) {
        if (started) return
        started = true
        val humanColor = config.humanColor ?: if (rng.nextBoolean()) Player.WHITE else Player.BLACK
        aiSide = if (config.opponent == Opponent.COMPUTER) humanColor.opponent else null
        aiPlayer = aiSide?.let { HeuristicAiPlayer(config.difficulty) }
        driver = aiSide?.let { AiTurnDriver(it, aiPlayer) }
        training = config.training && config.opponent == Opponent.COMPUTER
        controller = controllerFactory(aiSide)
        publish()
        maybeRunAi()
    }

    private fun publish(humanEvents: List<UiEvent> = emptyList()) {
        _uiState.value = controller.uiState.copy(aiThinking = aiThinking, aiSide = aiSide, analysis = analysis)
        humanEvents.forEach { _events.trySend(it) }
    }

    fun onRoll() { val e = controller.roll(); publish(e); maybeRunAi() }
    fun onTap(target: BoardTarget) { controller.tap(target); publish() }
    fun onUndo() { controller.undo(); cancelAnalysis(); publish() }
    fun onCommit() { controller.commit(); analyzeLastHumanMove(); publish(); maybeRunAi() }
    fun onAcknowledgePass() { controller.acknowledgePass(); publish(); maybeRunAi() }

    fun onNewGame() {
        cancelAnalysis()
        training = false
        aiJob?.cancel()
        aiJob = null
        aiThinking = false
        started = false
        // Fully drop the previous game's AI so startGame rebuilds a clean driver (no stuck guard).
        driver = null
        aiPlayer = null
        aiSide = null
        controller = GameController()
        publish()
    }

    /**
     * Launches off-thread analysis of the human's just-committed move (training mode only).
     * Clears [analysis] synchronously and re-publishes on completion via the epoch guard.
     * Caller MUST call [publish] afterwards — this function does not publish on its synchronous
     * path (the non-training early-return relies on the caller's publish to refresh the UI).
     */
    private fun analyzeLastHumanMove() {
        if (!training) return
        val lc = controller.lastCommitted ?: return
        analysisJob?.cancel()
        analysis = null
        val epoch = ++analysisEpoch
        analysisJob = scope.launch {
            val result = withContext(analysisDispatcher) {
                MoveAnalyzer.analyze(lc.preBoard, lc.dice, lc.move,
                    MoveGenerator.legalMoves(lc.preBoard, lc.dice))
            }
            if (epoch == analysisEpoch) { analysis = result; publish() }
        }
    }

    fun onAnalyse() {
        if (controller.uiState.toMove == aiSide) return
        val board = controller.uiState.board
        val dice = controller.uiState.dice ?: return
        val legal = MoveGenerator.legalMoves(board, dice)
        if (legal.isEmpty()) return
        analysisJob?.cancel()
        analysis = null
        val epoch = ++analysisEpoch
        analysisJob = scope.launch {
            val result = withContext(analysisDispatcher) { MoveAnalyzer.analyzeBest(board, dice, legal) }
            if (epoch == analysisEpoch) { analysis = result; publish() }
        }
        publish()
    }

    private fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        analysisEpoch++
        analysis = null
    }

    private fun maybeRunAi() {
        val d = driver ?: return
        if (aiSide == null || aiPlayer == null) return
        if (aiJob?.isActive == true) return
        aiJob = scope.launch {
            d.maybeRunTurn(
                controller,
                onThinking = { aiThinking = it },
                publish = { publish() },
            )
        }
    }

    /** Test-only: expose the current controller so tests can stage moves. */
    internal fun controllerForTest(): GameController = controller
}
