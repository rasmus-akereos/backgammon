package dk.rlunde.backgammon.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.GameUiState

@Composable
fun BoardCanvas(state: GameUiState, onTap: (BoardTarget) -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            // Fill the whole available area (full screen width in landscape). The geometry is
            // proportional, so it adapts to whatever w×h the slot provides.
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val g = BoardGeometry(size.width.toFloat(), size.height.toFloat())
                    g.hitTest(offset.x, offset.y)?.let(onTap)
                }
            }
    ) {
        val g = BoardGeometry(size.width, size.height)
        drawBoard(g, state)
    }
}

private fun DrawScope.drawBoard(g: BoardGeometry, state: GameUiState) {
    drawRect(BoardColors.felt)
    for (i in 1..24) {
        val r = g.pointRect(i)
        val light = (i % 2 == 0)
        drawTriangle(r, if (light) BoardColors.pointLight else BoardColors.pointDark, pointingUp = i in 1..12)
    }
    val bar = g.barRect()
    drawRect(BoardColors.bar, topLeft = Offset(bar.l, bar.t), size = Size(bar.r - bar.l, bar.b - bar.t))
    for (i in 1..24) {
        val w = state.board.count(Player.WHITE, i)
        val b = state.board.count(Player.BLACK, i)
        if (w > 0) drawStack(g.pointRect(i), w, Player.WHITE, i in 1..12)
        if (b > 0) drawStack(g.pointRect(i), b, Player.BLACK, i in 1..12)
    }
    drawBarCheckers(g, state.board.barCount(Player.WHITE), Player.WHITE)
    drawBarCheckers(g, state.board.barCount(Player.BLACK), Player.BLACK)
    drawTray(g.bearOffRect(Player.WHITE), state.board.offCount(Player.WHITE), Player.WHITE)
    drawTray(g.bearOffRect(Player.BLACK), state.board.offCount(Player.BLACK), Player.BLACK)
    state.selectedOrigin?.let { highlightTarget(g, it) }
    state.destinations.forEach { highlightTarget(g, it) }
    drawDice(g.diceRect(), state)
}

private fun DrawScope.drawTriangle(r: BoardRect, color: Color, pointingUp: Boolean) {
    val path = Path().apply {
        if (pointingUp) { moveTo(r.l, r.b); lineTo(r.r, r.b); lineTo((r.l + r.r) / 2, r.t) }
        else { moveTo(r.l, r.t); lineTo(r.r, r.t); lineTo((r.l + r.r) / 2, r.b) }
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawStack(r: BoardRect, n: Int, player: Player, fromBottom: Boolean) {
    val radius = (r.r - r.l) / 2f * 0.9f
    val color = if (player == Player.WHITE) BoardColors.whiteChecker else BoardColors.blackChecker
    val ring = if (player == Player.WHITE) BoardColors.whiteRing else BoardColors.blackRing
    val shown = minOf(n, 5)
    for (k in 0 until shown) {
        val cy = if (fromBottom) r.b - radius - k * radius * 2 else r.t + radius + k * radius * 2
        drawCircle(ring, radius, Offset(r.cx, cy))
        drawCircle(color, radius * 0.82f, Offset(r.cx, cy))
    }
}

private fun DrawScope.drawBarCheckers(g: BoardGeometry, n: Int, player: Player) {
    if (n == 0) return
    val bar = g.barRect()
    val radius = (bar.r - bar.l) / 2f * 0.8f
    val baseY = if (player == Player.WHITE) bar.b - radius - bar.b * 0.1f else bar.t + radius + bar.b * 0.1f
    val color = if (player == Player.WHITE) BoardColors.whiteChecker else BoardColors.blackChecker
    drawCircle(color, radius, Offset(bar.cx, baseY))
}

private fun DrawScope.drawTray(r: BoardRect, n: Int, player: Player) {
    if (n == 0) return
    val color = if (player == Player.WHITE) BoardColors.whiteChecker else BoardColors.blackChecker
    val h = (r.b - r.t)
    for (k in 0 until n) {
        val y = r.t + 6f + k * (h / 16f)
        drawRect(color, topLeft = Offset(r.l + 6f, y), size = Size(r.r - r.l - 12f, h / 18f))
    }
}

private fun DrawScope.highlightTarget(g: BoardGeometry, t: BoardTarget) {
    val r = when (t) {
        is BoardTarget.Point -> g.pointRect(t.index)
        BoardTarget.Bar -> g.barRect()
        is BoardTarget.BearOff -> g.bearOffRect(t.player)
        BoardTarget.Dice -> g.diceRect()
    }
    drawRect(BoardColors.highlight.copy(alpha = 0.35f), topLeft = Offset(r.l, r.t), size = Size(r.r - r.l, r.b - r.t))
}

private fun DrawScope.drawDice(r: BoardRect, state: GameUiState) {
    val d = state.dice ?: return
    val faces = d.pips()
    val remaining = state.remainingDice.toMutableList()
    val side = (r.b - r.t) * 0.8f
    val gap = side * 0.18f
    faces.forEachIndexed { idx, face ->
        val used = !remaining.remove(face)
        val left = r.l + gap + idx * (side + gap)
        drawDie(left, r.cy - side / 2f, side, face, used)
    }
}

/** A single die face: rounded white tile with the standard pip layout. A consumed die is dimmed. */
private fun DrawScope.drawDie(left: Float, top: Float, side: Float, face: Int, used: Boolean) {
    val body = if (used) Color.White.copy(alpha = 0.4f) else Color.White
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
