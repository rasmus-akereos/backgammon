package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.SeededDiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Fast guard: the committed per-phase K must beat the flat 50/50 baseline on a fixed sample. */
class EquityCalibrationGuardTest {
    private fun ref(seed: Long) = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(seed))

    @Test fun `committed win-prob beats the coin-flip baseline`() {
        val rows = ArrayList<Pair<Double, Boolean>>()
        for (g in 0 until 30) {
            val seed = (g + 1).toLong() * 1_099_511_628_211L
            val cg = selfPlayTrajectory(ref(seed xor WHITE_SALT), ref(seed xor BLACK_SALT), SeededDiceRoller(seed), Weights.FULL)
            if (cg.result.outcome == Outcome.TIMEOUT) continue
            val winner = if (cg.result.outcome == Outcome.WHITE_WIN) Player.WHITE else Player.BLACK
            cg.samples.forEach { rows.add(it.eval to (it.mover == winner)) }
        }
        // Average committed-model log-loss vs the flat baseline (k=0 → 0.5 everywhere).
        val committed = rows.map { (x, y) ->
            val p = WinProbability.fromEquity(x, GamePhase.CONTACT) // contact slope as the representative
            if (y) -Math.log(p.coerceIn(1e-12, 1.0)) else -Math.log((1 - p).coerceIn(1e-12, 1.0))
        }.average()
        val baseline = -Math.log(0.5)
        assertTrue(committed < baseline, "committed=$committed should beat 50/50 baseline=$baseline")
    }
}
