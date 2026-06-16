package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.SeededDiceRoller
import kotlin.random.Random
import kotlin.test.Test

/**
 * OFFLINE equity calibration (spec §5). Gated: run only with -Dbackgammon.calibrate=true.
 *   ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EquityCalibrationTest" -Dbackgammon.calibrate=true
 * Prints fitted per-phase K and gammon/bg coefficients + diagnostics; copy them into
 * WinProbability.K and GammonModel.GAMMON_COEFFS/BG_COEFFS.
 */
class EquityCalibrationTest {
    private val enabled = System.getProperty("backgammon.calibrate") == "true"
    private val games = 400
    private val weights = Weights.FULL
    private fun ref(seed: Long) = HeuristicAiPlayer(Difficulty.ADVANCED, Random(seed))

    @Test fun `fit and print equity model constants`() {
        if (!enabled) return

        // Per-phase (eval, didWin) and conditional gammon rows.
        val winByPhase = GamePhase.entries.associateWith { ArrayList<Pair<Double, Boolean>>() }
        val winGam = ArrayList<Pair<DoubleArray, Boolean>>(); val winBg = ArrayList<Pair<DoubleArray, Boolean>>()
        val loseGam = ArrayList<Pair<DoubleArray, Boolean>>(); val loseBg = ArrayList<Pair<DoubleArray, Boolean>>()

        for (g in 0 until games) {
            val seed = (g + 1).toLong() * 2_654_435_761L
            val cg = selfPlayTrajectory(ref(seed xor WHITE_SALT), ref(seed xor BLACK_SALT), SeededDiceRoller(seed), weights)
            if (cg.result.outcome == Outcome.TIMEOUT) continue
            val winner = if (cg.result.outcome == Outcome.WHITE_WIN) Player.WHITE else Player.BLACK
            val value = cg.result.value // 1/2/3
            for (s in cg.samples) {
                val moverWon = s.mover == winner
                winByPhase.getValue(s.phase).add(s.eval to moverWon)
                fun row(f: GammonFeatures) = doubleArrayOf(f.borneOff.toDouble(), f.pip.toDouble(), f.backContact.toDouble())
                if (moverWon) {            // opponent is the loser -> winnerFeatures describe the loser
                    winGam.add(row(s.winnerFeatures) to (value >= 2))
                    winBg.add(row(s.winnerFeatures) to (value == 3))
                } else {                   // mover is the loser
                    loseGam.add(row(s.loserFeatures) to (value >= 2))
                    loseBg.add(row(s.loserFeatures) to (value == 3))
                }
            }
        }

        val kByPhase = GamePhase.entries.associateWith { CalibrationFit.fitK(winByPhase.getValue(it)) }
        // Gammon/bg coefficients pool win- and lose-side rows (a loss-of-side is a gammon symmetrically).
        val gam = CalibrationFit.fitLogistic(winGam + loseGam, 3)
        val bg = CalibrationFit.fitLogistic(winBg + loseBg, 3)

        println("=== EQUITY CALIBRATION (n=$games games, ref=ADVANCED/FULL) ===")
        kByPhase.forEach { (p, k) ->
            val ll = CalibrationFit.logLoss(winByPhase.getValue(p), k)
            val base = CalibrationFit.logLoss(winByPhase.getValue(p), 0.1)
            println("K[$p] = $k   logLoss=$ll   baseline(K=0.1)=$base")
        }
        println("GAMMON_COEFFS = doubleArrayOf(${gam.joinToString()})")
        println("BG_COEFFS = doubleArrayOf(${bg.joinToString()})")
    }
}
