package dk.rlunde.backgammon.core

enum class Player {
    WHITE, BLACK;

    val opponent: Player get() = if (this == WHITE) BLACK else WHITE

    /** +1 = WHITE checkers stored as positive; -1 = BLACK stored as negative. */
    val sign: Int get() = if (this == WHITE) 1 else -1
}
