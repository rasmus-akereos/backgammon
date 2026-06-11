package dk.rlunde.backgammon.core

/**
 * Drives incremental (one sub-move at a time) turn building for the UI by only ever offering
 * sub-moves that keep the player on a MAXIMUM-pips path — so a complete legal turn is always
 * reachable and a die is never stranded. Pure; reuses MoveGenerator's enumeration.
 */
object TurnPlanner {
    /** Most pips playable from [state] using the dice still in hand. 0 => the turn is complete. */
    fun maxUsablePips(state: BoardState, remainingDice: List<Int>): Int =
        MoveGenerator.enumerateSequences(state, remainingDice)
            .maxOfOrNull { seq -> seq.sumOf { it.die } } ?: 0

    /**
     * Legal next single-die sub-moves that PRESERVE maxUsablePips, grouped by origin point
     * (bar uses its from-sentinel as the key). Recompute after every staging change; never cache.
     */
    fun legalNextSubMoves(state: BoardState, remainingDice: List<Int>): Map<Int, List<SubMove>> {
        val best = maxUsablePips(state, remainingDice)
        if (best == 0) return emptyMap()
        val memo = HashMap<BoardState, Int>()
        fun maxFrom(s: BoardState, dice: List<Int>): Int =
            memo.getOrPut(s) {
                MoveGenerator.enumerateSequences(s, dice).maxOfOrNull { seq -> seq.sumOf { it.die } } ?: 0
            }
        val result = LinkedHashMap<Int, MutableList<SubMove>>()
        for (die in remainingDice.toSet()) {
            val rest = remainingDice.toMutableList().apply { remove(die) }
            for (sm in MoveGenerator.legalSubMovesFor(state, die)) {
                val after = MoveGenerator.applySubMove(state, sm)
                if (sm.die + maxFrom(after, rest) == best) {
                    result.getOrPut(sm.from) { mutableListOf() }.add(sm)
                }
            }
        }
        return result
    }
}
