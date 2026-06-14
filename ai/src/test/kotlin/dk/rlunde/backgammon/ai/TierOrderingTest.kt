package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.SeededDiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic tier-ordering CHANGE-DETECTOR (not the formal proof — that is BenchmarkTest, which
 * runs at higher N with independent dice). Each pairing plays N seeded games (seeds 0 until N),
 * alternating which colour the stronger tier plays to cancel first-move advantage; TIMEOUT games are
 * excluded and asserted to be zero. A strength regression flips a pinned count.
 */
class TierOrderingTest {
    private val N = 30   // small: Expert is 3-ply. Raise if a margin is not robust.

    private fun ai(d: Difficulty, seed: Long) = HeuristicAiPlayer(d, Random(seed))

    /** Wins for [strong] vs [weak] over N seeded games, alternating colours; asserts zero TIMEOUTs. */
    private fun strongWins(strong: Difficulty, weak: Difficulty): Int {
        var wins = 0; var timeouts = 0
        for (seed in 0L until N) {
            val strongIsWhite = (seed % 2 == 0L)
            val white = ai(if (strongIsWhite) strong else weak, seed xor WHITE_SALT)
            val black = ai(if (strongIsWhite) weak else strong, seed xor BLACK_SALT)
            val r = selfPlay(white, black, SeededDiceRoller(seed))
            when (r.outcome) {
                Outcome.TIMEOUT -> timeouts++
                Outcome.WHITE_WIN -> if (strongIsWhite) wins++
                Outcome.BLACK_WIN -> if (!strongIsWhite) wins++
            }
        }
        assertEquals(0, timeouts, "unexpected TIMEOUTs ($strong vs $weak) — raise maxTurns or check seeds")
        return wins
    }

    @Test fun `tiers are ordered`() {
        val expertVsAdvanced = strongWins(Difficulty.EXPERT, Difficulty.ADVANCED)
        val advancedVsInter = strongWins(Difficulty.ADVANCED, Difficulty.INTERMEDIATE)
        val interVsBeginner = strongWins(Difficulty.INTERMEDIATE, Difficulty.BEGINNER)
        println("TIER-ORDERING (n=$N): Expert>Adv=$expertVsAdvanced  Adv>Inter=$advancedVsInter  Inter>Beg=$interVsBeginner")
        // Deterministic regression baselines pinned from the first run (search is deterministic;
        // Beginner's noise is seeded). These are a CHANGE-DETECTOR — any eval/search change re-pins
        // them. The quality margins are proven separately by BenchmarkTest (n=200, independent dice).
        // Ordering sanity (all > 50%) is implied by the pinned values.
        // Single triple comparison so a failure prints ALL THREE actual counts at once
        // (a halting per-line assert would hide the 2nd/3rd count behind the 1st mismatch).
        assertEquals(
            Triple(16, 16, 24),
            Triple(expertVsAdvanced, advancedVsInter, interVsBeginner),
            "Tier-ordering baselines changed (n=$N) — re-pin (Expert>Adv, Adv>Inter, Inter>Beg) if intended",
        )
    }
}
