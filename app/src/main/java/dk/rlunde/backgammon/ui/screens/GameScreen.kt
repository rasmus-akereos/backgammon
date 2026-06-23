package dk.rlunde.backgammon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.rlunde.backgammon.ai.Band
import dk.rlunde.backgammon.ai.Feature
import dk.rlunde.backgammon.ai.MoveAnalysis
import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.SubMove
import dk.rlunde.backgammon.game.CubeResponse
import dk.rlunde.backgammon.game.EndReason
import dk.rlunde.backgammon.game.GameUiState
import dk.rlunde.backgammon.game.Phase
import dk.rlunde.backgammon.game.UiEvent
import dk.rlunde.backgammon.ui.board.BoardCanvas
import dk.rlunde.backgammon.ui.board.DiceRow
import dk.rlunde.backgammon.ui.theme.AppColors
import dk.rlunde.backgammon.viewmodel.GameViewModel

@Composable
fun GameScreen(vm: GameViewModel = viewModel(), onNewGame: () -> Unit = {}) {
    val state by vm.uiState.collectAsState()
    val isAiTurn = state.isAiTurn
    var showPass by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var showResign by remember { mutableStateOf(false) }

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
            // Board fills most of the width; checker size is capped by point height so it never overflows.
            // Tapping the cube offers a double; a take/drop prompt overlays the board when one is pending.
            Box(Modifier.weight(0.78f).fillMaxHeight()) {
                BoardCanvas(
                    state = state,
                    onTap = vm::onTap,
                    modifier = Modifier.fillMaxSize(),
                    onCubeTap = { if (state.canDouble) vm.onOfferDouble() },
                )
                if (state.phase == Phase.CUBE_OFFERED) {
                    val responder = if (state.toMove == Player.WHITE) "BLACK" else "WHITE"
                    CubePrompt(
                        responder = responder,
                        toValue = state.cube.value * 2,
                        onTake = { vm.onRespondDouble(CubeResponse.TAKE) },
                        onDrop = { vm.onRespondDouble(CubeResponse.DROP) },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            TrackingPanel(
                state = state,
                modifier = Modifier.weight(0.22f).fillMaxHeight(),
                isAiTurn = isAiTurn,
                onRoll = vm::onRoll,
                onUndo = vm::onUndo,
                onCommit = vm::onCommit,
                onNewGame = onNewGame,
                onAnalyse = vm::onAnalyse,
                onBandClick = { showSheet = true },
                onResign = { showResign = true },
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

    if (showSheet && state.analysis != null) {
        AnalysisSheet(
            analysis = state.analysis!!,
            onDismiss = { showSheet = false },
        )
    }

    if (showResign) {
        AlertDialog(
            onDismissRequest = { showResign = false },
            confirmButton = {
                TextButton(onClick = {
                    showResign = false
                    // vs-computer: the human resigns (aiSide.opponent). Hot-seat: the player on roll.
                    val loser = state.aiSide?.opponent ?: state.toMove
                    vm.onResign(loser)
                }) { Text("Resign") }
            },
            dismissButton = { TextButton(onClick = { showResign = false }) { Text("Cancel") } },
            title = { Text("Resign this game?") },
            text = { Text("The opponent wins the current stake.") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisSheet(analysis: MoveAnalysis, onDismiss: () -> Unit) {
    // True when the move shown IS the best — on-demand hint, or player happened to play best.
    val isBestPlay = analysis.best.move == analysis.played.move

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // Header: band name + colour (always shown)
            val (bandLabel, bandColor) = bandDisplay(analysis)
            Text(
                text = bandLabel,
                style = MaterialTheme.typography.titleLarge,
                color = bandColor,
            )

            Spacer(Modifier.height(8.dp))

            if (isBestPlay) {
                // Clean "best play" view — no zero-valued noise
                Text(
                    text = "Best play: ${notation(analysis.best.move)}  (2-ply)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                analysis.positionEquity?.let { eq ->
                    Spacer(Modifier.height(4.dp))
                    Text(text = equityLine(eq), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                // Full post-move comparison view
                val winProbDrop = analysis.winProbDrop
                if (winProbDrop != null) {
                    Text(
                        text = "−%.1f eval  ~%.0f%% win drop".format(analysis.evalLoss, winProbDrop * 100),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    val qualitative = if (analysis.terminal) "Game-ending move" else "Forced win/loss line"
                    Text(text = qualitative, style = MaterialTheme.typography.bodyMedium)
                }

                analysis.positionEquity?.let { eq ->
                    Spacer(Modifier.height(4.dp))
                    Text(text = equityLine(eq), style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(8.dp))

                // Rank info
                if (!analysis.forced) {
                    val rankText = if (analysis.tiedForBest)
                        "Tied for best"
                    else
                        "Your move ranked ${analysis.playedRank} of ${analysis.totalCandidates}"
                    Text(text = rankText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }

                // Best move notation
                Text(
                    text = "Best: ${notation(analysis.best.move)}  (2-ply)",
                    style = MaterialTheme.typography.bodyMedium,
                )

                // Played move notation (always different from best in this branch)
                Text(
                    text = "Yours: ${notation(analysis.played.move)}",
                    style = MaterialTheme.typography.bodyMedium,
                )

                // "Why" feature deltas section
                if (analysis.featureDeltas.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Why", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    analysis.featureDeltas.take(3).forEach { d ->
                        Text(
                            text = "${featureLabel(d.feature)}  ${"%+.1f".format(d.delta)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // Footnote
                if (winProbDrop != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "* win% and gammon rates are self-play-calibrated (no rollouts)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Overlaid on the board when a double is pending: take continues at [toValue], drop ends the game. */
@Composable
private fun CubePrompt(
    responder: String,
    toValue: Int,
    onTake: () -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Double to $toValue", style = MaterialTheme.typography.titleMedium)
            Text("$responder: take or drop?", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = onTake) { Text("Take") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onDrop) { Text("Drop") }
            }
        }
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
    onAnalyse: () -> Unit,
    onBandClick: () -> Unit,
    onResign: () -> Unit = {},
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // --- Stats (top) -----------------------------------------------------------------
        val turnLabel = if (state.toMove == Player.WHITE) "WHITE to move" else "BLACK to move"
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = if (state.phase == Phase.GAME_OVER) "Game over" else turnLabel,
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(Modifier.height(12.dp))

                // Live race metric (updates as moves are staged). Lead is folded into the pip line.
                Text(
                    "Pips:  W ${state.whitePip}  B ${state.blackPip}   (${leadText(state.whitePip, state.blackPip)})",
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
            }
        }

        if (state.phase == Phase.GAME_OVER && state.winner != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                formatResult(state.winner!!, state.winValue, state.cube, state.endReason ?: EndReason.BORNE_OFF),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // Push the dice + controls to the bottom of the panel.
        Spacer(Modifier.weight(1f))

        // Band marker chip — shown when training is on and analysis is available
        if (state.training && state.analysis != null) {
            val (bandLabel, bandColor) = bandDisplay(state.analysis)
            Surface(
                shape = MaterialTheme.shapes.small,
                color = bandColor.copy(alpha = 0.22f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBandClick),
            ) {
                Text(
                    text = bandLabel,
                    color = bandColor,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // --- Dice, just above the controls -----------------------------------------------
        if (state.dice != null) {
            DiceRow(faces = state.dice.pips(), remaining = state.remainingDice)
            Spacer(Modifier.height(12.dp))
        }

        // --- Controls (bottom) -----------------------------------------------------------
        when {
            isAiTurn -> Text("AI thinking…", style = MaterialTheme.typography.titleMedium)
            else -> when (state.phase) {
                Phase.NEED_ROLL -> Button(onClick = onRoll, modifier = Modifier.fillMaxWidth()) { Text("Roll") }
                Phase.MOVING -> {
                    // Analyse button: enabled when training is on, it's the human's turn, and dice are available
                    val canAnalyse = state.training && state.aiSide != null && state.toMove != state.aiSide && state.dice != null
                    if (canAnalyse) {
                        OutlinedButton(onClick = onAnalyse, modifier = Modifier.fillMaxWidth()) { Text("Analyse") }
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(onClick = onUndo, modifier = Modifier.fillMaxWidth()) { Text("Undo") }
                }
                Phase.COMMITTABLE -> {
                    Button(onClick = onCommit, modifier = Modifier.fillMaxWidth()) { Text("Commit") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onUndo, modifier = Modifier.fillMaxWidth()) { Text("Undo") }
                }
                Phase.CUBE_OFFERED ->
                    Text("Respond on the board", style = MaterialTheme.typography.bodyMedium)
                Phase.GAME_OVER -> Button(onClick = onNewGame, modifier = Modifier.fillMaxWidth()) { Text("New game") }
            }
        }
        // Resign: a small text link during play, so it doesn't compete with the primary controls.
        if (!isAiTurn && (state.phase == Phase.NEED_ROLL || state.phase == Phase.MOVING || state.phase == Phase.COMMITTABLE)) {
            TextButton(onClick = onResign) { Text("Resign", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** Returns label + color for the given analysis, taking forced flag into account. */
private fun bandDisplay(analysis: MoveAnalysis): Pair<String, Color> =
    if (analysis.forced) "Forced" to AppColors.bandForced
    else bandLabel(analysis.band) to bandColor(analysis.band)

internal fun bandLabel(band: Band): String = when (band) {
    Band.BEST -> "Best"
    Band.GOOD -> "Good"
    Band.INACCURACY -> "Inaccuracy"
    Band.MISTAKE -> "Mistake"
    Band.BLUNDER -> "Blunder"
}

internal fun bandColor(band: Band): Color = when (band) {
    Band.BEST -> AppColors.bandBest
    Band.GOOD -> AppColors.bandGood
    Band.INACCURACY -> AppColors.bandInaccuracy
    Band.MISTAKE -> AppColors.bandMistake
    Band.BLUNDER -> AppColors.bandBlunder
}

/** Lower pip count is ahead; show who leads and by how much. */
private fun leadText(whitePip: Int, blackPip: Int): String = when {
    whitePip < blackPip -> "White +${blackPip - whitePip}"
    blackPip < whitePip -> "Black +${whitePip - blackPip}"
    else -> "even"
}

/** "13 → 10", "bar → 22", "3 → off" (with a * suffix on a hit). */
private fun formatMove(sm: SubMove): String {
    val from = if (sm.from == 0 || sm.from == 25) "bar" else sm.from.toString()
    val to = if (sm.to == 0 || sm.to == 25) "off" else sm.to.toString()
    return "$from → $to" + if (sm.isHit) " *" else ""
}

private fun featureLabel(feature: Feature): String = when (feature) {
    Feature.PIP -> "Pip count"
    Feature.OFF -> "Checkers off"
    Feature.BLOT -> "Blot exposure"
    Feature.HOME_POINT -> "Home points"
    Feature.KEY_POINT -> "Key points (5/bar)"
    Feature.PRIME -> "Prime"
    Feature.ANCHOR -> "Anchor"
    Feature.ADVANCED_ANCHOR -> "Advanced anchor"
    Feature.BAR -> "On the bar"
    Feature.BACK_CHECKER -> "Back checkers"
}
