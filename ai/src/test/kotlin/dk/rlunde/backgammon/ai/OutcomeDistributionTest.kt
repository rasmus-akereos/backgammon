package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutcomeDistributionTest {
    @Test fun `win and lose probabilities partition to 1`() {
        val d = OutcomeDistribution(0.40, 0.15, 0.05, 0.25, 0.10, 0.05)
        assertEquals(0.60, d.winProb, 1e-9)
        assertEquals(0.40, d.loseProb, 1e-9)
    }

    @Test fun `cubeless equity weights gammon x2 and backgammon x3`() {
        assertEquals(1.0, OutcomeDistribution(1.0, 0.0, 0.0, 0.0, 0.0, 0.0).cubelessEquity, 1e-9)
        assertEquals(-1.0, OutcomeDistribution(0.0, 0.0, 0.0, 1.0, 0.0, 0.0).cubelessEquity, 1e-9)
        assertEquals(0.5 - 1.5, OutcomeDistribution(0.5, 0.0, 0.0, 0.0, 0.0, 0.5).cubelessEquity, 1e-9)
    }

    @Test fun `equity stays within plus minus three`() {
        val d = OutcomeDistribution(0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        assertTrue(d.cubelessEquity in -3.0..3.0)
        assertEquals(3.0, d.cubelessEquity, 1e-9)
    }
}
