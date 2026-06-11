package dk.rlunde.backgammon.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dk.rlunde.backgammon.ai.HeuristicAiPlayer
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import dk.rlunde.backgammon.game.AiTurnDriver
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.game.GameController
import dk.rlunde.backgammon.game.GameUiState
import dk.rlunde.backgammon.game.Opponent
import dk.rlunde.backgammon.game.UiEvent
import dk.rlunde.backgammon.ui.board.BoardTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class GameViewModel : ViewModel() {
    private var controller = GameController()
    private var aiPlayer: HeuristicAiPlayer? = null
    private var aiSide: Player? = null
    private var driver: AiTurnDriver? = null
    private var aiJob: Job? = null
    private var aiThinking = false
    private var started = false
    private val rng = Random.Default

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
        controller = GameController(initial = startingPosition(), aiSide = aiSide)
        publish()
        maybeRunAi()
    }

    private fun publish(humanEvents: List<UiEvent> = emptyList()) {
        _uiState.value = controller.uiState.copy(aiThinking = aiThinking, aiSide = aiSide)
        humanEvents.forEach { _events.trySend(it) }
    }

    fun onRoll() { val e = controller.roll(); publish(e); maybeRunAi() }
    fun onTap(target: BoardTarget) { controller.tap(target); publish() }
    fun onUndo() { controller.undo(); publish() }
    fun onCommit() { controller.commit(); publish(); maybeRunAi() }
    fun onAcknowledgePass() { controller.acknowledgePass(); publish(); maybeRunAi() }

    fun onNewGame() {
        aiJob?.cancel()
        aiJob = null
        aiThinking = false
        started = false
        controller = GameController()
        publish()
    }

    private fun maybeRunAi() {
        val d = driver ?: return
        if (aiSide == null || aiPlayer == null) return
        if (aiJob?.isActive == true) return
        aiJob = viewModelScope.launch {
            d.maybeRunTurn(
                controller,
                onThinking = { aiThinking = it },
                publish = { publish() },
            )
        }
    }
}
