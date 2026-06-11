package dk.rlunde.backgammon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dk.rlunde.backgammon.ui.screens.GameScreen
import dk.rlunde.backgammon.ui.theme.BackgammonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BackgammonTheme { GameScreen() } }
    }
}
