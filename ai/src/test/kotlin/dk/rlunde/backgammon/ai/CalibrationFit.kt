package dk.rlunde.backgammon.ai

import kotlin.math.exp
import kotlin.math.ln

/** Pure, deterministic fitters for offline equity calibration (spec §5). No RNG, no I/O. */
internal object CalibrationFit {
    private const val EPS = 1e-12
    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))

    /** Mean negative log-likelihood of the intercept-free 1-feature logistic with slope [k]. */
    fun logLoss(samples: List<Pair<Double, Boolean>>, k: Double): Double {
        if (samples.isEmpty()) return 0.0
        var s = 0.0
        for ((x, y) in samples) {
            val p = sigmoid(k * x).coerceIn(EPS, 1 - EPS)
            s += if (y) -ln(p) else -ln(1 - p)
        }
        return s / samples.size
    }

    /** 1-D gradient descent for the intercept-free slope (win-prob calibration). */
    fun fitK(samples: List<Pair<Double, Boolean>>, iters: Int = 2000, lr: Double = 0.01): Double {
        var k = 0.0
        if (samples.isEmpty()) return k
        for (i in 0 until iters) {
            var grad = 0.0
            for ((x, y) in samples) {
                val p = sigmoid(k * x)
                grad += (p - if (y) 1.0 else 0.0) * x
            }
            k -= lr * grad / samples.size
        }
        return k
    }

    /** Batch gradient descent for a logistic with intercept over [featuresPerSample] inputs. */
    fun fitLogistic(
        rows: List<Pair<DoubleArray, Boolean>>,
        featuresPerSample: Int,
        iters: Int = 4000,
        lr: Double = 0.05,
    ): DoubleArray {
        val w = DoubleArray(featuresPerSample + 1) // [0] = intercept
        if (rows.isEmpty()) return w
        for (i in 0 until iters) {
            val grad = DoubleArray(w.size)
            for ((f, y) in rows) {
                var z = w[0]
                for (j in 0 until featuresPerSample) z += w[j + 1] * f[j]
                val d = sigmoid(z) - if (y) 1.0 else 0.0
                grad[0] += d
                for (j in 0 until featuresPerSample) grad[j + 1] += d * f[j]
            }
            for (j in w.indices) w[j] -= lr * grad[j] / rows.size
        }
        return w
    }
}
