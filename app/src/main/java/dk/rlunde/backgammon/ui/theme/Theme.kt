package dk.rlunde.backgammon.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

// Fixed navy/gold identity. Passing an explicit colorScheme already suppresses Material You —
// do not add dynamicDarkColorScheme(). Requires Material3 >= 1.2 for the surfaceContainer* roles.
private val BackgammonColors = darkColorScheme(
    primary = Color(0xFFF0B830), onPrimary = Color(0xFF1A1205),     // bright gold primary action
    secondary = Color(0xFFC9A227), onSecondary = Color(0xFF1A1205),
    background = Color(0xFF0F1D33), onBackground = Color(0xFFEEF2F8),
    surface = Color(0xFF13233F), onSurface = Color(0xFFEEF2F8),
    surfaceVariant = Color(0xFF1E3258), onSurfaceVariant = Color(0xFFC9D2E0),
    surfaceContainerLowest = Color(0xFF0E1A31), surfaceContainerLow = Color(0xFF152744),
    surfaceContainer = Color(0xFF1A2C4C), surfaceContainerHigh = Color(0xFF21345A),
    surfaceContainerHighest = Color(0xFF273B63),
    outline = Color(0xFF8A93A6),
    error = Color(0xFFCF6679), onError = Color(0xFF1A1205),         // muted rose, legible on navy
    scrim = Color(0xFF0A1526),                                       // navy-tinted dialog/sheet scrim
)

private val BaseTypography = Typography()
private val BackgammonTypography = BaseTypography.copy(
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun BackgammonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BackgammonColors, typography = BackgammonTypography, content = content)
}
