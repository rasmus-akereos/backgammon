package dk.rlunde.backgammon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.ui.screens.GameScreen
import dk.rlunde.backgammon.ui.screens.SetupScreen
import dk.rlunde.backgammon.ui.theme.BackgammonTheme
import dk.rlunde.backgammon.viewmodel.GameViewModel

private enum class Screen { SETUP, GAME }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BackgammonTheme {
                val vm: GameViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf(Screen.SETUP) }
                when (screen) {
                    Screen.SETUP -> SetupScreen(onStart = { config: GameConfig ->
                        vm.startGame(config)
                        screen = Screen.GAME
                    })
                    Screen.GAME -> GameScreen(vm = vm, onNewGame = {
                        vm.onNewGame()
                        screen = Screen.SETUP
                    })
                }
            }
        }
    }
}
