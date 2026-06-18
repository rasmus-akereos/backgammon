package dk.rlunde.backgammon.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dk.rlunde.backgammon.ui.theme.AppColors

/**
 * Renders the current roll as dice tiles. A die whose face is no longer in [remaining]
 * (i.e. already played this turn) is dimmed. For doubles, [faces] has four entries.
 */
@Composable
fun DiceRow(faces: List<Int>, remaining: List<Int>, modifier: Modifier = Modifier, dieSize: Dp = 44.dp) {
    val rem = remaining.toMutableList()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        faces.forEach { face ->
            val stillAvailable = rem.remove(face)
            DieFace(face = face, used = !stillAvailable, size = dieSize)
        }
    }
}

@Composable
private fun DieFace(face: Int, used: Boolean, size: Dp) {
    Canvas(Modifier.size(size)) {
        drawDieFace(this.size.minDimension, face, used)
    }
}

/** Canonical Western die pip positions, as fractions of the face (0..1). Pure — unit-tested. */
internal fun pipOffsets(face: Int): List<Offset> {
    val lo = 0.28f; val mid = 0.5f; val hi = 0.72f
    return when (face) {
        1 -> listOf(Offset(mid, mid))
        2 -> listOf(Offset(lo, lo), Offset(hi, hi))
        3 -> listOf(Offset(lo, lo), Offset(mid, mid), Offset(hi, hi))
        4 -> listOf(Offset(lo, lo), Offset(hi, lo), Offset(lo, hi), Offset(hi, hi))
        5 -> listOf(Offset(lo, lo), Offset(hi, lo), Offset(mid, mid), Offset(lo, hi), Offset(hi, hi))
        6 -> listOf(Offset(lo, lo), Offset(hi, lo), Offset(lo, mid), Offset(hi, mid), Offset(lo, hi), Offset(hi, hi))
        else -> emptyList()
    }
}

private val diceFaceBrush = Brush.verticalGradient(listOf(AppColors.diceFace, AppColors.diceFaceShade))
private val diceShadowColor = Color(0x4D000000)

/** A single die face: rounded shaded tile + canonical pips. A consumed die is dimmed via alpha. */
private fun DrawScope.drawDieFace(side: Float, face: Int, used: Boolean) {
    val a = if (used) 0.4f else 1f
    val corner = CornerRadius(side * 0.18f, side * 0.18f)
    // Simple offset shadow (no blur, no native Paint).
    drawRoundRect(color = diceShadowColor, topLeft = Offset(side * 0.05f, side * 0.07f),
        size = Size(side, side), cornerRadius = corner, alpha = a)
    drawRoundRect(brush = diceFaceBrush, topLeft = Offset.Zero, size = Size(side, side),
        cornerRadius = corner, alpha = a)
    val pipR = side * 0.085f
    pipOffsets(face).forEach { o ->
        drawCircle(AppColors.dicePip, pipR, Offset(o.x * side, o.y * side), alpha = a)
    }
}
