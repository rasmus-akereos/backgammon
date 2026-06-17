package dk.rlunde.backgammon.ui.board

import dk.rlunde.backgammon.core.Player

/** A screen rectangle in pixels. Pure data — no Compose/Android types (so geometry is JVM-tested). */
data class BoardRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun contains(x: Float, y: Float): Boolean = x >= l && x < r && y >= t && y < b
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f
}

/** A tappable region of the board. */
sealed interface BoardTarget {
    data class Point(val index: Int) : BoardTarget { init { require(index in 1..24) { "point $index" } } }
    data object Bar : BoardTarget
    data class BearOff(val player: Player) : BoardTarget
    data object Dice : BoardTarget
}

/**
 * Pure layout math: maps a board element to a screen [BoardRect] and a tap (x,y) to a [BoardTarget].
 * Fixed White's-perspective portrait layout. Right edge holds the two bear-off trays + dice column.
 * Bottom-right = White home 1..6 (1 nearest the right edge), bottom-left = 7..12, top-left = 13..18,
 * top-right = Black home 19..24 (24 nearest the right edge). Central bar between the halves.
 */
class BoardGeometry(private val w: Float, private val h: Float) {
    private val trayW = w * 0.06f                 // slim right-edge bear-off tray column
    private val playW = w - trayW
    private val barW = playW * 0.08f
    private val halfW = (playW - barW) / 2f        // each 6-point half
    private val colW = halfW / 6f
    private val rowH = h / 2f

    private fun colRect(leftEdge: Float, colFromLeft: Int, top: Float): BoardRect {
        val l = leftEdge + colFromLeft * colW
        return BoardRect(l, top, l + colW, top + rowH)
    }

    /** Screen rect for point [index] (1..24). */
    fun pointRect(index: Int): BoardRect = when (index) {
        in 1..6 -> {
            val colFromBar = 6 - index
            val leftEdge = halfW + barW
            colRect(leftEdge, colFromBar, rowH)
        }
        in 7..12 -> {
            val colFromLeft = 12 - index
            colRect(0f, colFromLeft, rowH)
        }
        in 13..18 -> {
            val colFromLeft = index - 13
            colRect(0f, colFromLeft, 0f)
        }
        else -> {
            val colFromBar = index - 19
            val leftEdge = halfW + barW
            colRect(leftEdge, colFromBar, 0f)
        }
    }

    fun barRect(): BoardRect = BoardRect(halfW, 0f, halfW + barW, h)

    fun bearOffRect(player: Player): BoardRect =
        if (player == Player.WHITE) BoardRect(playW, rowH, w, h)
        else BoardRect(playW, 0f, w, rowH)

    fun diceRect(): BoardRect = BoardRect(0f, rowH - rowH * 0.15f, halfW, rowH + rowH * 0.15f)

    /** Square cube indicator in the bear-off tray column. y depends on the cube owner. */
    fun cubeRect(owner: Player?): BoardRect {
        val size = trayW * 0.8f
        val cx = playW + trayW / 2f
        val cy = when (owner) {
            null -> h / 2f
            Player.WHITE -> h * 0.75f   // White home is the bottom half
            Player.BLACK -> h * 0.25f
        }
        return BoardRect(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
    }

    fun hitTest(x: Float, y: Float): BoardTarget? {
        if (x < 0f || y < 0f || x > w || y > h) return null
        if (diceRect().contains(x, y)) return BoardTarget.Dice
        if (barRect().contains(x, y)) return BoardTarget.Bar
        if (bearOffRect(Player.WHITE).contains(x, y)) return BoardTarget.BearOff(Player.WHITE)
        if (bearOffRect(Player.BLACK).contains(x, y)) return BoardTarget.BearOff(Player.BLACK)
        for (i in 1..24) if (pointRect(i).contains(x, y)) return BoardTarget.Point(i)
        return null
    }
}
