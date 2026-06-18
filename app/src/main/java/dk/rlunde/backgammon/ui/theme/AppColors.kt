package dk.rlunde.backgammon.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Non-Material-role UI colors (chrome only). Pure Color constants — NO Brush, NO Compose-draw
 * imports, NO :ai/Band import. Composables reference AppColors; board pigments stay in BoardColors.
 */
object AppColors {
    // Move-quality band colors, tuned to read as text on a 0.22-alpha chip over the navy surface.
    val bandBest = Color(0xFF66BB6A)
    val bandGood = Color(0xFF9CCC65)
    val bandInaccuracy = Color(0xFFFFB74D)
    val bandMistake = Color(0xFFFF8A65)
    val bandBlunder = Color(0xFFEF5350)
    val bandForced = Color(0xFFB0BEC5)

    // Dice — decoupled from BoardColors so Slice B checker changes don't alter dice.
    val diceFace = Color(0xFFFBF7EE)
    val diceFaceShade = Color(0xFFE7E0D0)
    val dicePip = Color(0xFF23262B)
}
