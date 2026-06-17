package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.Player

/** A response to a double: take continues the game (cube doubles), drop ends it. */
enum class CubeResponse { TAKE, DROP }

/** Doubling-cube state. [owner] == null means centred (either side may double). */
data class CubeState(val value: Int = 1, val owner: Player? = null) {
    val isCentred: Boolean get() = owner == null

    /** The player whose turn it is (pre-roll) may double if the cube is centred or they own it, below the cap. */
    fun mayDouble(player: Player): Boolean = (owner == null || owner == player) && value < CUBE_CAP

    /** After a take: value doubles and the cube passes to the taker. */
    fun afterTake(taker: Player): CubeState = CubeState(value * 2, taker)

    companion object { const val CUBE_CAP = 64 } // 2^6, conventional money-game cap
}
