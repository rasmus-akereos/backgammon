package dk.rlunde.backgammon.core

/** Stateless rules engine: legal-move generation and state transitions. Thread-safe by statelessness. */
object MoveGenerator {

    // ---- sentinels & per-player geometry -------------------------------------------------
    private fun offSentinel(player: Player) = if (player == Player.WHITE) 0 else 25
    private fun barFrom(player: Player) = if (player == Player.WHITE) 25 else 0
    private fun entryPoint(player: Player, die: Int) = if (player == Player.WHITE) 25 - die else die
    private fun normalTo(player: Player, from: Int, die: Int) =
        if (player == Player.WHITE) from - die else from + die
    private fun homeRange(player: Player) = if (player == Player.WHITE) 1..6 else 19..24
    private fun distToOff(player: Player, point: Int) = if (player == Player.WHITE) point else 25 - point

    // ---- state transitions ----------------------------------------------------------------

    /** Flip the side to move; used to advance the turn on a forced pass (empty legal-move set). */
    fun pass(state: BoardState): BoardState = state.copy(toMove = state.toMove.opponent)

    /** Apply a complete legal turn, returning a NEW state. Input is never mutated or aliased. */
    fun apply(state: BoardState, move: Move): BoardState {
        var current = state
        for (sm in move.subMoves) current = applySubMove(current, sm)
        val flipped = current.copy(toMove = current.toMove.opponent)
        check(conservesCheckers(state, flipped)) { "checker count invariant violated by $move" }
        return flipped
    }

    /** Apply ONE sub-move for state.toMove. Allocates a fresh points array (copy-on-write). */
    private fun applySubMove(state: BoardState, sm: SubMove): BoardState {
        val mover = state.toMove
        val points = state.points.copyOf()
        val bar = state.bar.toMutableMap()
        val off = state.off.toMutableMap()

        // remove the moving checker from its origin
        if (sm.from == barFrom(mover) && sm.from !in 1..24) {
            bar[mover] = bar.getValue(mover) - 1
        } else {
            points[sm.from] -= mover.sign
        }

        // place it at its destination
        if (sm.to == offSentinel(mover) && sm.to !in 1..24) {
            off[mover] = off.getValue(mover) + 1
        } else {
            if (sm.isHit) {
                points[sm.to] = 0
                bar[mover.opponent] = bar.getValue(mover.opponent) + 1
            }
            points[sm.to] += mover.sign
        }

        return BoardState(points, bar, off, mover) // toMove unchanged here; apply() flips once at end
    }

    // ---- single-die legality ---------------------------------------------------------------

    /** All legal single-die sub-moves for state.toMove using [die], honouring bar-first. */
    private fun legalSubMovesFor(state: BoardState, die: Int): List<SubMove> {
        val mover = state.toMove

        // Bar-first: if any checker is on the bar, the ONLY legal sub-move is an entry.
        if (state.barCount(mover) > 0) {
            val to = entryPoint(mover, die)
            return if (state.isBlockedFor(mover, to)) {
                emptyList()
            } else {
                listOf(SubMove(barFrom(mover), to, die, isHit = state.isBlotFor(mover, to)))
            }
        }

        val subs = mutableListOf<SubMove>()

        // Normal moves: any of the mover's checkers to an on-board destination.
        for (from in 1..24) {
            if (state.count(mover, from) == 0) continue
            val to = normalTo(mover, from, die)
            if (to in 1..24 && !state.isBlockedFor(mover, to)) {
                subs.add(SubMove(from, to, die, isHit = state.isBlotFor(mover, to)))
            }
        }

        // Bear-off: only when all 15 are home.
        if (allHome(state, mover)) {
            val occupied = homeRange(mover).filter { state.count(mover, it) > 0 }
            val maxDist = occupied.maxOfOrNull { distToOff(mover, it) } ?: 0
            for (from in occupied) {
                val dist = distToOff(mover, from)
                val exact = dist == die
                val overflow = dist == maxDist && die > dist
                if (exact || overflow) {
                    subs.add(SubMove(from, offSentinel(mover), die, isHit = false))
                }
            }
        }
        return subs
    }

    private fun allHome(state: BoardState, player: Player): Boolean {
        if (state.barCount(player) > 0) return false
        val outside = if (player == Player.WHITE) 7..24 else 1..18
        return outside.all { state.count(player, it) == 0 }
    }

    // ---- Stage 1: enumerate every complete dice sequence ----------------------------------

    private fun enumerateSequences(state: BoardState, remainingDice: List<Int>): List<List<SubMove>> {
        if (remainingDice.isEmpty()) return listOf(emptyList())
        val results = mutableListOf<List<SubMove>>()
        var anyPlayable = false
        for (die in remainingDice.toSet()) { // distinct values; doubles collapse naturally
            for (sm in legalSubMovesFor(state, die)) {
                anyPlayable = true
                val next = applySubMove(state, sm)
                val rest = remainingDice.toMutableList().apply { remove(die) }
                for (tail in enumerateSequences(next, rest)) {
                    results.add(listOf(sm) + tail)
                }
            }
        }
        if (!anyPlayable) results.add(emptyList()) // no die playable from here -> stop
        return results
    }

    // ---- test-only entry points (replaced by legalMoves wiring in Task 12) ----------------
    internal fun legalSubMovesForTest(state: BoardState, die: Int) = legalSubMovesFor(state, die)
    internal fun enumerateSequencesForTest(state: BoardState, dice: List<Int>) =
        enumerateSequences(state, dice)

    private fun totalCheckers(state: BoardState, player: Player): Int {
        val onBoard = (1..24).sumOf { state.count(player, it) }
        return onBoard + state.barCount(player) + state.offCount(player)
    }

    private fun conservesCheckers(before: BoardState, after: BoardState): Boolean =
        Player.entries.all { player -> totalCheckers(before, player) == totalCheckers(after, player) }
}
