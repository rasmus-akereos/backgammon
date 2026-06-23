package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.SeededDiceRoller
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Fast, non-gated guard: the committed gammon model is roughly calibrated to self-play, and
 *  gammon risk decays as the loser bears off. Guards against constant-rot / re-calibration drift. */
class GammonCalibrationGuardTest {
    private fun ref(seed: Long) = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(seed))

    @Test fun `committed gammon model is roughly calibrated on a fixed sample`() {
        var predSum = 0.0; var realized = 0; var n = 0
        for (g in 0 until 60) {
            val seed = (g + 1).toLong() * 1_099_511_628_211L
            val cg = selfPlayTrajectory(ref(seed xor WHITE_SALT), ref(seed xor BLACK_SALT), SeededDiceRoller(seed), Weights.FULL)
            if (cg.result.outcome == Outcome.TIMEOUT) continue
            val winner = if (cg.result.outcome == Outcome.WHITE_WIN) Player.WHITE else Player.BLACK
            val loserGammoned = cg.result.value >= 2
            for (s in cg.samples) if (s.decisive && s.mover != winner) {
                predSum += GammonModel.loseRates(s.loserFeatures).gammon
                if (loserGammoned) realized++
                n++
            }
        }
        assertTrue(n > 0, "expected decisive loser positions")
        val meanPred = predSum / n
        val realizedRate = realized.toDouble() / n
        assertTrue(abs(meanPred - realizedRate) < 0.15,
            "gammon model miscalibrated: predicted=$meanPred realized=$realizedRate")
    }

    @Test fun `gammon risk decays as the loser bears off`() {
        val early = GammonModel.loseRates(GammonFeatures(borneOff = 0, pip = 120, backContact = 0)).gammon
        val late = GammonModel.loseRates(GammonFeatures(borneOff = 5, pip = 60, backContact = 0)).gammon
        assertTrue(late < early, "gammon risk should fall once the loser bears off: early=$early late=$late")
    }
}
