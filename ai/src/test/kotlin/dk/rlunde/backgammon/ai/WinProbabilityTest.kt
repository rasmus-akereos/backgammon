package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WinProbabilityTest {
    @Test fun `midpoint is one half`() {
        assertEquals(0.5, WinProbability.fromEquity(0.0, GamePhase.CONTACT), 1e-12)
    }

    @Test fun `monotonic increasing in equity`() {
        assertTrue(WinProbability.fromEquity(-5.0, GamePhase.CONTACT) < WinProbability.fromEquity(0.0, GamePhase.CONTACT))
        assertTrue(WinProbability.fromEquity(0.0, GamePhase.CONTACT) < WinProbability.fromEquity(5.0, GamePhase.CONTACT))
    }

    @Test fun `symmetric about zero`() {
        for (x in listOf(0.3, 1.0, 7.5, 30.0)) {
            assertEquals(1.0, WinProbability.fromEquity(x, GamePhase.RACE) + WinProbability.fromEquity(-x, GamePhase.RACE), 1e-9)
        }
    }

    @Test fun `stays within zero and one`() {
        for (x in listOf(-1000.0, -10.0, 0.0, 10.0, 1000.0)) {
            val p = WinProbability.fromEquity(x, GamePhase.CONTACT)
            assertTrue(p in 0.0..1.0, "win-prob out of range for $x: $p")
        }
    }

    @Test fun `even position is 50 percent in every phase`() {
        for (phase in GamePhase.entries) {
            assertEquals(0.5, WinProbability.fromEquity(0.0, phase), 1e-9)
        }
    }

    @Test fun `win prob is monotonic in equity within a phase`() {
        val lo = WinProbability.fromEquity(-5.0, GamePhase.CONTACT)
        val mid = WinProbability.fromEquity(0.0, GamePhase.CONTACT)
        val hi = WinProbability.fromEquity(5.0, GamePhase.CONTACT)
        assertTrue(lo < mid && mid < hi)
    }

    @Test fun `symmetric about half`() {
        val p = WinProbability.fromEquity(3.0, GamePhase.RACE)
        val q = WinProbability.fromEquity(-3.0, GamePhase.RACE)
        assertEquals(1.0, p + q, 1e-9)
    }
}
