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
