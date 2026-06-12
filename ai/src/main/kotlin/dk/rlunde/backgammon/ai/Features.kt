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
     * false (Beginner), cost is 1 (probability-only). Blocking-aware: a combination (indirect) shot is
     * counted only if at least one ordering has an OPEN intermediate landing point; direct shots
     * (one die == distance) are never blocked. The opponent's bar shooter keeps the open-path
     * approximation (entry mechanics differ; rare). Unblocked positions reproduce the open-path counts.
     */
    fun blotPenalty(s: BoardState, p: Player, costWeighted: Boolean): Double {
        val o = p.opponent
        var penalty = 0.0
        for (i in 1..24) {
            if (s.count(p, i) != 1) continue // not a blot of p's
            var bestProb = 0.0
            for (j in 1..24) {
                if (s.count(o, j) == 0) continue
                bestProb = maxOf(bestProb, blockedHitProb(s, p, o, shooter = j, target = i))
            }
            if (s.barCount(o) > 0) {
                val barFrom = if (o == Player.WHITE) 25 else 0
                val d = if (o == Player.WHITE) barFrom - i else i - barFrom
                if (d in 1..12) bestProb = maxOf(bestProb, ShotTable.hitProbability(d))
            }
            penalty += if (costWeighted) bestProb * hitCost(p, i) else bestProb
        }
        return penalty
    }

    /** One step of [o] toward its bear-off (WHITE high->low, BLACK low->high). */
    private fun step(o: Player, point: Int, k: Int): Int = if (o == Player.WHITE) point - k else point + k

    /** True if [o] may LAND on [point] (on board and not blocked by one of [p]'s made points, >=2). */
    private fun landable(s: BoardState, p: Player, point: Int): Boolean =
        point in 1..24 && s.count(p, point) < 2

    /**
     * Fraction of the 36 ordered rolls by which shooter [o] at [shooter] hits [p]'s blot at [target],
     * EXCLUDING combination shots whose intermediate landing point is blocked. Direct shots (one die ==
     * distance) are never blocked; doubles may hit via 1..4 equal hops with all intermediates landable.
     * Restricted to distances 1..12 (the open-path table's domain; far doubles ignored — a v1 approximation).
     */
    internal fun blockedHitProb(s: BoardState, p: Player, o: Player, shooter: Int, target: Int): Double {
        val d = if (o == Player.WHITE) shooter - target else target - shooter
        if (d !in 1..12) return 0.0
        var hits = 0
        for (d1 in 1..6) for (d2 in 1..6) {
            if (canHit(s, p, o, shooter, d, d1, d2)) hits++
        }
        return hits / 36.0
    }

    private fun canHit(s: BoardState, p: Player, o: Player, shooter: Int, d: Int, d1: Int, d2: Int): Boolean {
        // Direct: a single die equals the distance (one hop, no intermediate point to block).
        if (d <= 6 && (d1 == d || d2 == d)) return true
        if (d1 == d2) {
            // Doubles value k: reachable via n hops if d == n*k for n in 2..4; all n-1 intermediates landable.
            val k = d1
            if (d % k != 0) return false
            val n = d / k
            if (n !in 2..4) return false
            var pt = shooter
            for (hop in 1 until n) { pt = step(o, pt, k); if (!landable(s, p, pt)) return false }
            return true
        }
        // Non-double combination: both dice, either order; the first hop must land on an open point.
        if (d1 + d2 != d) return false
        return landable(s, p, step(o, shooter, d1)) || landable(s, p, step(o, shooter, d2))
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
