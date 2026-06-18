package dk.rlunde.backgammon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dk.rlunde.backgammon.ai.Difficulty
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.game.Opponent

@Composable
fun SetupScreen(onStart: (GameConfig) -> Unit) {
    var difficulty by remember { mutableStateOf(Difficulty.INTERMEDIATE) }
    var colorChoice by remember { mutableStateOf<Player?>(Player.WHITE) }
    var opponent by remember { mutableStateOf(Opponent.COMPUTER) }
    var training by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        // Landscape: title on top, the choice groups side by side, Start clearly below — all visible
        // without scrolling. verticalScroll stays as a safety net for very short screens.
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text("New game", style = MaterialTheme.typography.headlineSmall)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                ChoiceRow("Opponent", listOf(
                    "Computer" to Opponent.COMPUTER, "Hot-seat" to Opponent.HOT_SEAT,
                ), opponent) { opponent = it }

                if (opponent == Opponent.COMPUTER) {
                    ChoiceRow("Difficulty", listOf(
                        "Beginner" to Difficulty.BEGINNER, "Intermediate" to Difficulty.INTERMEDIATE,
                        "Advanced" to Difficulty.ADVANCED, "Expert" to Difficulty.EXPERT,
                    ), difficulty) { difficulty = it }
                    ChoiceRow("Training", listOf(
                        "Off" to false, "On" to true,
                    ), training) { training = it }
                }

                ChoiceRow("You play", listOf(
                    "White" to Player.WHITE, "Black" to Player.BLACK, "Random" to null,
                ), colorChoice) { colorChoice = it }
            }

            Button(
                onClick = { onStart(GameConfig(difficulty, colorChoice, opponent, training)) },
                modifier = Modifier.fillMaxWidth(0.5f).height(56.dp),
            ) {
                Text("Start game", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        // Chips stacked vertically: keeps each group narrow so the three groups sit side by side in
        // landscape, and scales to 4 difficulty options without horizontal clipping.
        Column(
            Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            options.forEach { (text, value) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}
