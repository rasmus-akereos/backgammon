package dk.rlunde.backgammon.ui.board

import androidx.compose.ui.graphics.Color

object BoardColors {
    val felt = Color(0xFF14254E)   // royal navy deep blue
    val pointLight = Color(0xFFE8DCC0)
    val pointDark = Color(0xFF9C6B3F)
    val whiteChecker = Color(0xFFF4F1EA)
    val whiteRing = Color(0xFFC9C2B0)
    val blackChecker = Color(0xFF23262B)
    val blackRing = Color(0xFF3A3F47)
    val highlight = Color(0xFFE0A526)
    // Slate "trough" for the central bar and bear-off trays — distinct from the green felt so
    // checkers parked there (hit / borne off) stand out against the background.
    val bar = Color(0xFF4A515A)
    val tray = Color(0xFF3A4048)
}
