package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.ai.Difficulty
import dk.rlunde.backgammon.core.Player

enum class Opponent { COMPUTER, HOT_SEAT }

/** Choices from the setup screen. humanColor == null means "Random" (resolved in the VM). */
data class GameConfig(
    val difficulty: Difficulty = Difficulty.INTERMEDIATE,
    val humanColor: Player? = Player.WHITE,
    val opponent: Opponent = Opponent.COMPUTER,
)
