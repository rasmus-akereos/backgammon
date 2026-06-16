package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.math.abs

class CalibrationFitTest {
    /** Deterministic data whose positive-label proportion at each x is logistic([trueK]·x). */
    private fun proportional(trueK: Double, n: Int = 200): List<Pair<Double, Boolean>> {
        val out = ArrayList<Pair<Double, Boolean>>()
        for (xi in -50..50) {
            val x = xi / 10.0
            val pos = Math.round(n * (1.0 / (1.0 + Math.exp(-trueK * x)))).toInt()
            repeat(n) { i -> out.add(x to (i < pos)) }
        }
        return out
    }

    @Test fun `fitK recovers a known slope on proportionally-labelled data`() {
        val trueK = 0.3
        val k = CalibrationFit.fitK(proportional(trueK), iters = 5000)
        assertTrue(abs(k - trueK) < 0.1, "fitK off: $k vs $trueK")
    }

    @Test fun `fitK is steeper for steeper data`() {
        assertTrue(CalibrationFit.fitK(proportional(0.5)) > CalibrationFit.fitK(proportional(0.2)))
    }

    @Test fun `logLoss rewards a better slope`() {
        val xs = (-50..50).map { it / 10.0 }
        val samples = xs.map { x -> x to (x >= 0.0) }
        val good = CalibrationFit.logLoss(samples, 0.4)
        val flat = CalibrationFit.logLoss(samples, 0.0) // 0.5 everywhere
        assertTrue(good < flat, "a real slope should beat the 50/50 baseline")
    }
}
