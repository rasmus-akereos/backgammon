package dk.rlunde.backgammon.viewmodel

import androidx.lifecycle.ViewModel
import dk.rlunde.backgammon.game.GameController
import dk.rlunde.backgammon.game.GameUiState
import dk.rlunde.backgammon.game.UiEvent
import dk.rlunde.backgammon.ui.board.BoardTarget
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class GameViewModel : ViewModel() {
    private val controller = GameController()
    private val _uiState = MutableStateFlow(controller.uiState)
    val uiState: StateFlow<GameUiState> = _uiState

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private fun publish(events: List<UiEvent> = emptyList()) {
        _uiState.value = controller.uiState
        events.forEach { _events.trySend(it) }
    }

    fun onRoll() = publish(controller.roll())
    fun onTap(target: BoardTarget) { controller.tap(target); publish() }
    fun onUndo() { controller.undo(); publish() }
    fun onCommit() { controller.commit(); publish() }
    fun onNewGame() { controller.newGame(); publish() }
    fun onAcknowledgePass() { controller.acknowledgePass(); publish() }
}
