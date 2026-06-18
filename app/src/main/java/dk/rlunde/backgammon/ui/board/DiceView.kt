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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
        drawDieFace(0f, 0f, this.size.minDimension, face, used)
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

/** A single die face: rounded white tile with the standard pip layout. A consumed die is dimmed. */
fun DrawScope.drawDieFace(left: Float, top: Float, side: Float, face: Int, used: Boolean) {
    val body = if (used) BoardColors.whiteChecker.copy(alpha = 0.4f) else BoardColors.whiteChecker
    val pip = if (used) BoardColors.blackChecker.copy(alpha = 0.45f) else BoardColors.blackChecker
    drawRoundRect(
        color = body,
        topLeft = Offset(left, top),
        size = Size(side, side),
        cornerRadius = CornerRadius(side * 0.18f, side * 0.18f),
    )
    val pipR = side * 0.085f
    fun pip(cxFrac: Float, cyFrac: Float) =
        drawCircle(pip, pipR, Offset(left + cxFrac * side, top + cyFrac * side))
    val lo = 0.28f; val mid = 0.5f; val hi = 0.72f
    when (face) {
        1 -> pip(mid, mid)
        2 -> { pip(lo, lo); pip(hi, hi) }
        3 -> { pip(lo, lo); pip(mid, mid); pip(hi, hi) }
        4 -> { pip(lo, lo); pip(hi, lo); pip(lo, hi); pip(hi, hi) }
        5 -> { pip(lo, lo); pip(hi, lo); pip(mid, mid); pip(lo, hi); pip(hi, hi) }
        6 -> { pip(lo, lo); pip(hi, lo); pip(lo, mid); pip(hi, mid); pip(lo, hi); pip(hi, hi) }
    }
}
