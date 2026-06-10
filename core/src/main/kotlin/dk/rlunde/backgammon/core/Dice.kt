package dk.rlunde.backgammon.core

data class Dice(val a: Int, val b: Int) {
    init { require(a in 1..6 && b in 1..6) { "die values must be 1..6, got ($a,$b)" } }

    val isDouble: Boolean get() = a == b

    /** The pips available this turn: two for a normal roll, four for a double. */
    fun pips(): List<Int> = if (isDouble) List(4) { a } else listOf(a, b)
}
