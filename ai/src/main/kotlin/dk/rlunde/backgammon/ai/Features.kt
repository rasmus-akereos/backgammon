package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.Scoring

/**
 * Pure board-feature computations for the evaluator, each from [p]'s perspective. WHITE moves
 * high→low (bar sentinel 25), BLACK low→high (bar sentinel 0). See spec §4.2.
 */
internal object Features {

    fun pipDifferential(s: BoardState, p: Player): Int =
        Scoring.pipCount(s, p.opponent) - Scoring.pipCount(s, p)

    fun offDifferential(s: BoardState, p: Player): Int =
        s.offCount(p) - s.offCount(p.opponent)

    /** Negative when [p] has checkers on the bar; positive when the opponent does. */
    fun barTerm(s: BoardState, p: Player): Int =
        s.barCount(p.opponent) - s.barCount(p)

    private fun homeRange(p: Player): IntRange = if (p == Player.WHITE) 1..6 else 19..24
    private fun fivePoint(p: Player): Int = if (p == Player.WHITE) 5 else 20
    private fun barPoint(p: Player): Int = if (p == Player.WHITE) 7 else 18
    private fun advancedAnchor(p: Player): Int = if (p == Player.WHITE) 20 else 5

    fun homePointsMade(s: BoardState, p: Player): Int =
        homeRange(p).count { s.count(p, it) >= 2 }

    /** Bonus count for the high-value made points (5-point, bar-point). */
    fun keyPointsMade(s: BoardState, p: Player): Int =
        listOf(fivePoint(p), barPoint(p)).count { s.count(p, it) >= 2 }

    fun primeLength(s: BoardState, p: Player): Int {
        var best = 0; var run = 0
        for (i in 1..24) {
            if (s.count(p, i) >= 2) { run++; if (run > best) best = run } else run = 0
        }
        return best
    }

    /** Points [p] holds (≥2) inside the opponent's home board. */
    fun anchorsMade(s: BoardState, p: Player): Int {
        val oppHome = homeRange(p.opponent)
        return oppHome.count { s.count(p, it) >= 2 }
    }

    fun advancedAnchorsMade(s: BoardState, p: Player): Int =
        if (s.count(p, advancedAnchor(p)) >= 2) 1 else 0

    /** [p] checkers still deep in the opponent's home quadrant (must escape). */
    fun backCheckers(s: BoardState, p: Player): Int =
        homeRange(p.opponent).sumOf { s.count(p, it) }

    /** Pips the [p] checker at [i] loses if hit (sent to the bar, pip 25). */
    private fun hitCost(p: Player, i: Int): Int = if (p == Player.WHITE) 25 - i else i

    /**
     * Sum over [p]'s blots of (max single-shooter hit probability) × cost. When [costWeighted] is
     * false (Beginner), cost is 1 (probability-only). A shooter is an opponent checker — or the
     * opponent's bar — that lies "behind" the blot in the opponent's direction of travel.
     */
    fun blotPenalty(s: BoardState, p: Player, costWeighted: Boolean): Double {
        val o = p.opponent
        var penalty = 0.0
        for (i in 1..24) {
            if (s.count(p, i) != 1) continue // not a blot of p's
            var bestProb = 0.0
            val shooters = ArrayList<Int>(15)
            for (j in 1..24) if (s.count(o, j) > 0) shooters.add(j)
            if (s.barCount(o) > 0) shooters.add(if (o == Player.WHITE) 25 else 0)
            for (j in shooters) {
                val d = if (o == Player.WHITE) j - i else i - j // o moves toward its bear-off
                if (d in 1..12) bestProb = maxOf(bestProb, ShotTable.hitProbability(d))
            }
            penalty += if (costWeighted) bestProb * hitCost(p, i) else bestProb
        }
        return penalty
    }

    /** True when no hit is possible: every WHITE checker is at a lower point than every BLACK one. */
    fun noContact(s: BoardState): Boolean {
        val whiteBack = if (s.barCount(Player.WHITE) > 0) 25
            else (1..24).lastOrNull { s.count(Player.WHITE, it) > 0 } ?: 0
        val blackBack = if (s.barCount(Player.BLACK) > 0) 0
            else (1..24).firstOrNull { s.count(Player.BLACK, it) > 0 } ?: 25
        return whiteBack < blackBack
    }
}
