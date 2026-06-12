package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WinProbabilityTest {
    @Test fun `midpoint is one half`() {
        assertEquals(0.5, WinProbability.fromEquity(0.0), 1e-12)
    }

    @Test fun `monotonic increasing in equity`() {
        assertTrue(WinProbability.fromEquity(-5.0) < WinProbability.fromEquity(0.0))
        assertTrue(WinProbability.fromEquity(0.0) < WinProbability.fromEquity(5.0))
    }

    @Test fun `symmetric about zero`() {
        for (x in listOf(0.3, 1.0, 7.5, 30.0)) {
            assertEquals(1.0, WinProbability.fromEquity(x) + WinProbability.fromEquity(-x), 1e-9)
        }
    }

    @Test fun `stays within zero and one`() {
        for (x in listOf(-1000.0, -10.0, 0.0, 10.0, 1000.0)) {
            val p = WinProbability.fromEquity(x)
            assertTrue(p in 0.0..1.0, "win-prob out of range for $x: $p")
        }
    }
}
