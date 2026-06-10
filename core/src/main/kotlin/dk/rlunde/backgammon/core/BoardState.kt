package dk.rlunde.backgammon.core

/**
 * The full game state. [points] is size 26 (indices 1..24 are points; 0 and 25 are sentinels).
 * Positive = WHITE checkers, negative = BLACK. [bar]/[off] are ALWAYS total over Player
 * (both keys present). The [points] array is treated as deeply immutable — never mutated in
 * place — which is what makes BoardState safe as a HashSet/HashMap key.
 */
data class BoardState(
    val points: IntArray,
    val bar: Map<Player, Int>,
    val off: Map<Player, Int>,
    val toMove: Player,
) {
    fun barCount(player: Player): Int = bar.getValue(player)
    fun offCount(player: Player): Int = off.getValue(player)

    /** Number of [player]'s checkers on board point [index] (always >= 0). */
    fun count(player: Player, index: Int): Int {
        val v = points[index]
        return if (player == Player.WHITE) maxOf(v, 0) else maxOf(-v, 0)
    }

    /** A point is blocked for [player] if the opponent has >= 2 checkers there. */
    fun isBlockedFor(player: Player, index: Int): Boolean = count(player.opponent, index) >= 2

    /** A point is a blot for [player] to hit if the opponent has exactly 1 checker there. */
    fun isBlotFor(player: Player, index: Int): Boolean = count(player.opponent, index) == 1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoardState) return false
        return points.contentEquals(other.points) &&
            bar == other.bar &&
            off == other.off &&
            toMove == other.toMove
    }

    override fun hashCode(): Int {
        var result = points.contentHashCode()
        result = 31 * result + bar.hashCode()
        result = 31 * result + off.hashCode()
        result = 31 * result + toMove.hashCode()
        return result
    }
}
