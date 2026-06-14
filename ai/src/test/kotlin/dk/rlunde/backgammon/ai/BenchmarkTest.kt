package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.SeededDiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Heavy self-play harness (hundreds of games, independent dice per game) — the RIGOROUS proof of tier
 * ordering and a release gate. Not run per-commit: guarded by -Dbackgammon.benchmark=true.
 * Run: ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.BenchmarkTest" -Dbackgammon.benchmark=true
 */
class BenchmarkTest {
    private val enabled = System.getProperty("backgammon.benchmark") == "true"
    private val games = 200

    private fun ai(d: Difficulty, seed: Long) = HeuristicAiPlayer(d, Random(seed))

    private fun winRate(strong: Difficulty, weak: Difficulty): Double {
        var wins = 0; var played = 0
        for (g in 0 until games) {
            val seed = (g + 1).toLong() * 2_654_435_761L     // distinct, well-spread per-game seeds
            val strongIsWhite = (g % 2 == 0)
            val white = ai(if (strongIsWhite) strong else weak, seed xor WHITE_SALT)
            val black = ai(if (strongIsWhite) weak else strong, seed xor BLACK_SALT)
            when (selfPlay(white, black, SeededDiceRoller(seed)).outcome) {
                Outcome.TIMEOUT -> {}                        // excluded from the denominator
                Outcome.WHITE_WIN -> { played++; if (strongIsWhite) wins++ }
                Outcome.BLACK_WIN -> { played++; if (!strongIsWhite) wins++ }
            }
        }
        return wins.toDouble() / played
    }

    @Test fun `pairwise ordering holds by a clear margin`() {
        if (!enabled) return                                  // skip silently unless explicitly enabled
        val ea = winRate(Difficulty.EXPERT, Difficulty.ADVANCED)
        val ai_ = winRate(Difficulty.ADVANCED, Difficulty.INTERMEDIATE)
        val ib = winRate(Difficulty.INTERMEDIATE, Difficulty.BEGINNER)
        println("BENCHMARK win-rates: Expert>Adv=$ea  Adv>Inter=$ai_  Inter>Beg=$ib  (n=$games each)")
        assertTrue(ea > 0.55, "Expert should beat Advanced > 55%: $ea")
        assertTrue(ai_ > 0.55, "Advanced should beat Intermediate > 55%: $ai_")
        assertTrue(ib > 0.65, "Intermediate should beat Beginner > 65%: $ib")
    }
}
