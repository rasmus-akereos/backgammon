package dk.rlunde.backgammon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.SubMove
import dk.rlunde.backgammon.game.GameUiState
import dk.rlunde.backgammon.game.Phase
import dk.rlunde.backgammon.game.UiEvent
import dk.rlunde.backgammon.ui.board.BoardCanvas
import dk.rlunde.backgammon.ui.board.DiceRow
import dk.rlunde.backgammon.viewmodel.GameViewModel

@Composable
fun GameScreen(vm: GameViewModel = viewModel(), onNewGame: () -> Unit = {}) {
    val state by vm.uiState.collectAsState()
    val isAiTurn = state.aiSide != null && state.toMove == state.aiSide
    var showPass by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect {
            if (it is UiEvent.NoLegalMoves) {
                val s = vm.uiState.value
                if (s.aiSide == null || s.toMove != s.aiSide) showPass = true
            }
        }
    }

    // Surface gives the screen the dark theme background AND a light content colour, so the
    // panel text reads white instead of falling back to black-on-dark.
    Surface(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().systemBarsPadding().padding(8.dp)) {
            // Board fills ~80% of the width; checker size is capped by point height so it never overflows.
            BoardCanvas(
                state = state,
                onTap = vm::onTap,
                modifier = Modifier.weight(0.8f).fillMaxHeight(),
            )

            Spacer(Modifier.width(12.dp))

            TrackingPanel(
                state = state,
                modifier = Modifier.weight(0.2f).fillMaxHeight(),
                isAiTurn = isAiTurn,
                onRoll = vm::onRoll,
                onUndo = vm::onUndo,
                onCommit = vm::onCommit,
                onNewGame = onNewGame,
            )
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

@Composable
private fun TrackingPanel(
    state: GameUiState,
    modifier: Modifier = Modifier,
    isAiTurn: Boolean = false,
    onRoll: () -> Unit,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
    onNewGame: () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // --- Stats (top) -----------------------------------------------------------------
        val turnLabel = if (state.toMove == Player.WHITE) "WHITE to move" else "BLACK to move"
        Text(
            text = if (state.phase == Phase.GAME_OVER) "Game over" else turnLabel,
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.height(12.dp))

        // Live race metrics (computed from the partial board, so they update as moves are staged).
        Text("Pips:  W ${state.whitePip}  B ${state.blackPip}", style = MaterialTheme.typography.bodyMedium)
        Text("Lead:  ${leadText(state.whitePip, state.blackPip)}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Blots:  W ${blots(state.board, Player.WHITE)}  B ${blots(state.board, Player.BLACK)}",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(16.dp))

        // Move tracker: the sub-moves staged so far this turn.
        Text("This turn", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        if (state.stagedMoves.isEmpty()) {
            Text("—", style = MaterialTheme.typography.bodyMedium)
        } else {
            state.stagedMoves.forEach { sm ->
                Text(formatMove(sm), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (state.phase == Phase.GAME_OVER && state.winner != null) {
            Spacer(Modifier.height(16.dp))
            val v = when (state.winValue) { 3 -> "backgammon"; 2 -> "gammon"; else -> "single" }
            Text("${state.winner} wins ($v)", style = MaterialTheme.typography.titleMedium)
        }

        // Push the dice + controls to the bottom of the panel.
        Spacer(Modifier.weight(1f))

        // --- Dice, just above the controls -----------------------------------------------
        if (state.dice != null) {
            DiceRow(faces = state.dice.pips(), remaining = state.remainingDice)
            Spacer(Modifier.height(12.dp))
        }

        // --- Controls (bottom) -----------------------------------------------------------
        when {
            isAiTurn -> Text("AI thinking…", style = MaterialTheme.typography.titleMedium)
            else -> when (state.phase) {
                Phase.NEED_ROLL -> Button(
                    onClick = onRoll,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828),
                        contentColor = Color.White,
                    ),
                ) { Text("Roll") }
                Phase.MOVING -> Button(onClick = onUndo, modifier = Modifier.fillMaxWidth()) { Text("Undo") }
                Phase.COMMITTABLE -> {
                    Button(onClick = onCommit, modifier = Modifier.fillMaxWidth()) { Text("Commit") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onUndo, modifier = Modifier.fillMaxWidth()) { Text("Undo") }
                }
                Phase.GAME_OVER -> Button(onClick = onNewGame, modifier = Modifier.fillMaxWidth()) { Text("New game") }
            }
        }
    }
}

/** Lower pip count is ahead; show who leads and by how much. */
private fun leadText(whitePip: Int, blackPip: Int): String = when {
    whitePip < blackPip -> "White +${blackPip - whitePip}"
    blackPip < whitePip -> "Black +${whitePip - blackPip}"
    else -> "even"
}

/** Number of points where [player] has a lone (hittable) checker. */
private fun blots(board: BoardState, player: Player): Int =
    (1..24).count { board.count(player, it) == 1 }

/** "13 → 10", "bar → 22", "3 → off" (with a * suffix on a hit). */
private fun formatMove(sm: SubMove): String {
    val from = if (sm.from == 0 || sm.from == 25) "bar" else sm.from.toString()
    val to = if (sm.to == 0 || sm.to == 25) "off" else sm.to.toString()
    return "$from → $to" + if (sm.isHit) " *" else ""
}
