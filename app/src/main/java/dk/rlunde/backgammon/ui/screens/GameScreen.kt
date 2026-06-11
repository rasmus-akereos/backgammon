package dk.rlunde.backgammon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.Phase
import dk.rlunde.backgammon.game.UiEvent
import dk.rlunde.backgammon.ui.board.BoardCanvas
import dk.rlunde.backgammon.viewmodel.GameViewModel

@Composable
fun GameScreen(vm: GameViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var showPass by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { if (it is UiEvent.NoLegalMoves) showPass = true }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val turnLabel = if (state.toMove == Player.WHITE) "WHITE to move" else "BLACK to move"
        Text(text = if (state.phase == Phase.GAME_OVER) "Game over" else turnLabel, style = MaterialTheme.typography.titleLarge)
        Text("White pip ${state.whitePip}   •   Black pip ${state.blackPip}", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(8.dp))
        BoardCanvas(state = state, onTap = vm::onTap, modifier = Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.phase) {
                Phase.NEED_ROLL -> Button(onClick = vm::onRoll) { Text("Roll") }
                Phase.MOVING -> Button(onClick = vm::onUndo, enabled = true) { Text("Undo") }
                Phase.COMMITTABLE -> {
                    Button(onClick = vm::onUndo) { Text("Undo") }
                    Button(onClick = vm::onCommit) { Text("Commit") }
                }
                Phase.GAME_OVER -> Button(onClick = vm::onNewGame) { Text("New game") }
            }
        }

        if (state.phase == Phase.GAME_OVER && state.winner != null) {
            val v = when (state.winValue) { 3 -> "backgammon"; 2 -> "gammon"; else -> "single" }
            Text("${state.winner} wins ($v)", style = MaterialTheme.typography.titleMedium)
        }
    }

    if (showPass) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { TextButton(onClick = { showPass = false; vm.onAcknowledgePass() }) { Text("OK") } },
            title = { Text("No legal moves") },
            text = { Text("No legal moves for this roll — passing to the other player.") },
        )
    }
}
