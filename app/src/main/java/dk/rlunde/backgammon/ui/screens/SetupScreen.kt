package dk.rlunde.backgammon.ui.screens

import androidx.compose.foundation.layout.*
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

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("New game", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))

            ChoiceRow("Opponent", listOf(
                "Computer" to Opponent.COMPUTER, "Hot-seat" to Opponent.HOT_SEAT,
            ), opponent) { opponent = it }

            if (opponent == Opponent.COMPUTER) {
                ChoiceRow("Difficulty", listOf(
                    "Beginner" to Difficulty.BEGINNER, "Intermediate" to Difficulty.INTERMEDIATE,
                ), difficulty) { difficulty = it }
            }

            ChoiceRow("You play", listOf(
                "White" to Player.WHITE, "Black" to Player.BLACK, "Random" to null,
            ), colorChoice) { colorChoice = it }

            Spacer(Modifier.height(24.dp))
            Button(onClick = { onStart(GameConfig(difficulty, colorChoice, opponent)) }) {
                Text("Start")
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (text, value) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}
