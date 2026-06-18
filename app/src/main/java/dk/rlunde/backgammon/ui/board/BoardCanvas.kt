package dk.rlunde.backgammon.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.CubeState
import dk.rlunde.backgammon.game.GameUiState

private val feltBrush = Brush.radialGradient(
    listOf(BoardColors.feltCenter, BoardColors.feltEdge))
private val pointLightBrush = Brush.verticalGradient(
    listOf(BoardColors.pointLightTop, BoardColors.pointLightBase))
private val pointDarkBrush = Brush.verticalGradient(
    listOf(BoardColors.pointDarkTop, BoardColors.pointDarkBase))
private val cubePaint = android.graphics.Paint().apply {
    color = android.graphics.Color.BLACK
    textAlign = android.graphics.Paint.Align.CENTER
    isAntiAlias = true
}

@Composable
fun BoardCanvas(
    state: GameUiState,
    onTap: (BoardTarget) -> Unit,
    modifier: Modifier = Modifier,
    onCubeTap: () -> Unit = {},
) {
    // Sizing is the caller's responsibility (see GameScreen): the geometry is proportional and
    // adapts to whatever w×h the Canvas is given.
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val g = BoardGeometry(size.width.toFloat(), size.height.toFloat())
                    // A tap on the cube square offers a double (the handler gates on canDouble).
                    if (g.cubeRect(state.cube.owner).contains(offset.x, offset.y)) onCubeTap()
                    else g.hitTest(offset.x, offset.y)?.let(onTap)
                }
            }
    ) {
        val g = BoardGeometry(size.width, size.height)
        drawBoard(g, state)
    }
}

private fun DrawScope.drawBoard(g: BoardGeometry, state: GameUiState) {
    drawRect(feltBrush)
    for (i in 1..24) {
        val r = g.pointRect(i)
        drawTriangle(r, if (i % 2 == 0) pointLightBrush else pointDarkBrush, pointingUp = i in 1..12)
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
    for (p in listOf(Player.WHITE, Player.BLACK)) {
        val tr = g.bearOffRect(p)
        drawRect(BoardColors.tray, topLeft = Offset(tr.l, tr.t), size = Size(tr.r - tr.l, tr.b - tr.t))
    }
    drawTray(g.bearOffRect(Player.WHITE), state.board.offCount(Player.WHITE), Player.WHITE)
    drawTray(g.bearOffRect(Player.BLACK), state.board.offCount(Player.BLACK), Player.BLACK)
    state.selectedOrigin?.let { highlightTarget(g, it) }
    state.destinations.forEach { highlightTarget(g, it) }
    // Doubling cube: a square in the tray column showing the face value. Shown in both modes
    // (read-only vs computer in 6b-i); highlighted when the player on roll may double (tap to offer).
    drawCube(g, state.cube, highlight = state.canDouble)
    drawFrame()
    // Dice are rendered in the side panel (see GameScreen), not on the board.
}

private fun DrawScope.drawCube(g: BoardGeometry, cube: CubeState, highlight: Boolean) {
    val r = g.cubeRect(cube.owner)
    val tl = Offset(r.l, r.t)
    val sz = Size(r.r - r.l, r.b - r.t)
    // Soft drop shadow.
    drawRect(BoardColors.checkerShadow, topLeft = Offset(r.l + sz.width * 0.06f, r.t + sz.height * 0.08f), size = sz)
    // Cream face + a subtle top highlight band (cheap bevel).
    drawRect(BoardColors.whiteChecker, topLeft = tl, size = sz)
    drawRect(BoardColors.whiteHighlight, topLeft = tl, size = Size(sz.width, sz.height * 0.4f))
    // Border: gold when the player on roll may double, else neutral.
    val border = if (highlight) BoardColors.highlight else BoardColors.bar
    drawRect(border, topLeft = tl, size = sz,
        style = Stroke(width = sz.width * (if (highlight) 0.12f else 0.06f)))
    // Value label (hoisted paint; text size set per draw — a field write, not an allocation).
    cubePaint.textSize = sz.height * 0.55f
    drawContext.canvas.nativeCanvas.drawText(cube.value.toString(), r.cx, r.cy + cubePaint.textSize * 0.35f, cubePaint)
}

private fun DrawScope.drawTriangle(r: BoardRect, brush: Brush, pointingUp: Boolean) {
    val path = Path().apply {
        if (pointingUp) { moveTo(r.l, r.b); lineTo(r.r, r.b); lineTo((r.l + r.r) / 2, r.t) }
        else { moveTo(r.l, r.t); lineTo(r.r, r.t); lineTo((r.l + r.r) / 2, r.b) }
        close()
    }
    drawPath(path, brush)
}

private fun DrawScope.drawFrame() {
    val frameW = minOf(size.width, size.height) * 0.025f
    drawRect(BoardColors.frameDark, topLeft = Offset(frameW / 2f, frameW / 2f),
        size = Size(size.width - frameW, size.height - frameW), style = Stroke(width = frameW))
    val inset = frameW
    drawRect(BoardColors.pinstripe, topLeft = Offset(inset, inset),
        size = Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(width = frameW * 0.14f))
}

private const val MAX_STACK_SHOWN = 5

private fun DrawScope.drawStack(r: BoardRect, n: Int, player: Player, fromBottom: Boolean) {
    val byWidth = (r.r - r.l) / 2f * 0.9f
    val byHeight = (r.b - r.t) / (2f * MAX_STACK_SHOWN) * 0.98f
    val radius = minOf(byWidth, byHeight)
    val shown = minOf(n, MAX_STACK_SHOWN)
    for (k in 0 until shown) {
        val cy = if (fromBottom) r.b - radius - k * radius * 2 else r.t + radius + k * radius * 2
        drawChecker(Offset(r.cx, cy), radius, player)
    }
}

private fun DrawScope.drawChecker(center: Offset, radius: Float, player: Player) {
    val face = if (player == Player.WHITE) BoardColors.whiteChecker else BoardColors.blackChecker
    val ring = if (player == Player.WHITE) BoardColors.whiteRing else BoardColors.blackRing
    val highlight = if (player == Player.WHITE) BoardColors.whiteHighlight else BoardColors.blackHighlight
    val groove = if (player == Player.WHITE) BoardColors.whiteGroove else BoardColors.blackGroove
    val rim = if (player == Player.WHITE) BoardColors.whiteRim else BoardColors.blackRim
    // Soft drop shadow (offset translucent circle — no blur).
    drawCircle(BoardColors.checkerShadow, radius, center + Offset(radius * 0.10f, radius * 0.14f))
    // Outer ring (keeps black checkers legible on dark felt) + face.
    drawCircle(ring, radius, center)
    drawCircle(face, radius * 0.92f, center)
    // Glossy highlight spot, up-left.
    drawCircle(highlight, radius * 0.42f, center + Offset(-radius * 0.28f, -radius * 0.30f))
    // Ridge — only when the checker is large enough to read it.
    if (radius >= 18.dp.toPx()) {
        drawCircle(groove, radius * 0.86f, center, style = Stroke(width = radius * 0.08f))
        drawCircle(rim, radius * 0.74f, center, style = Stroke(width = radius * 0.06f))
    }
}

private fun DrawScope.drawBarCheckers(g: BoardGeometry, n: Int, player: Player) {
    if (n == 0) return
    val bar = g.barRect()
    val radius = (bar.r - bar.l) / 2f * 0.8f
    val baseY = if (player == Player.WHITE) bar.b - radius - bar.b * 0.1f else bar.t + radius + bar.b * 0.1f
    drawChecker(Offset(bar.cx, baseY), radius, player)
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
    drawRoundRect(
        BoardColors.highlight.copy(alpha = 0.35f),
        topLeft = Offset(r.l, r.t),
        size = Size(r.r - r.l, r.b - r.t),
        cornerRadius = CornerRadius((r.r - r.l) * 0.18f),
    )
}
