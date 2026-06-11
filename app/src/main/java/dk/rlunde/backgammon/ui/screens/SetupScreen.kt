package dk.rlunde.backgammon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                    ), difficulty) { difficulty = it }
                }

                ChoiceRow("You play", listOf(
                    "White" to Player.WHITE, "Black" to Player.BLACK, "Random" to null,
                ), colorChoice) { colorChoice = it }
            }

            Button(
                onClick = { onStart(GameConfig(difficulty, colorChoice, opponent)) },
                modifier = Modifier.fillMaxWidth(0.5f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC62828), contentColor = Color.White,
                ),
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (text, value) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}
