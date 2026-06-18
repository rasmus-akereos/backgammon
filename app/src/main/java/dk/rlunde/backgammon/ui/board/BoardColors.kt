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

    // --- Depth (Visual Polish B) ---
    // Felt radial gradient (lighter centre → darker edge doubles as the vignette).
    val feltCenter = Color(0xFF1D3A63)
    val feltEdge = Color(0xFF0E1A31)
    // Frame + gold pinstripe.
    val frameDark = Color(0xFF0D1830)
    val pinstripe = Color(0xFFE0A526)
    // Point gradients — cream pair / brown pair. Light family stays clearly lighter than dark.
    val pointLightTop = Color(0xFFEFE3C6)
    val pointLightBase = Color(0xFFD8C8A4)
    val pointDarkTop = Color(0xFF8A5A2E)
    val pointDarkBase = Color(0xFF5E3C1E)
    // Checker bevel + ridge. Face/ring reuse whiteChecker/whiteRing/blackChecker/blackRing.
    val checkerShadow = Color(0x59000000)   // soft drop shadow under any checker
    val whiteHighlight = Color(0x80FFFFFF)  // glossy spot
    val whiteGroove = Color(0x33000000)     // ridge dark groove
    val whiteRim = Color(0x80FFFFFF)        // ridge light rim
    val blackHighlight = Color(0x4DFFFFFF)
    val blackGroove = Color(0x66000000)
    val blackRim = Color(0x33FFFFFF)
}
