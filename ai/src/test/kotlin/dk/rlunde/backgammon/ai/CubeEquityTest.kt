package dk.rlunde.backgammon.ai

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CubeEquityTest {
    /** Gammonless distribution with the given win probability (W=L=1). */
    private fun gammonless(p: Double) = OutcomeDistribution(
        winSingle = p, winGammon = 0.0, winBackgammon = 0.0,
        loseSingle = 1.0 - p, loseGammon = 0.0, loseBackgammon = 0.0,
    )

    @Test fun `take and cash points hit the gammonless dead-cube anchors at x=0`() {
        val eq = CubeEquity.ofX(gammonless(0.5), x = 0.0, owner = CubeOwner.CENTERED)
        assertEquals(0.25, eq.takePoint, 1e-9)
        assertEquals(0.75, eq.cashPoint, 1e-9)
    }

    @Test fun `take and cash points hit the gammonless live-cube anchors at x=1`() {
        val eq = CubeEquity.ofX(gammonless(0.5), x = 1.0, owner = CubeOwner.CENTERED)
        assertEquals(0.20, eq.takePoint, 1e-9)
        assertEquals(0.80, eq.cashPoint, 1e-9)
    }

    @Test fun `all three cubeful equities reduce to cubeless at x=0`() {
        val d = gammonless(0.65)
        val cubeless = d.cubelessEquity  // = 2*0.65 - 1 = 0.30
        for (owner in CubeOwner.values()) {
            val eq = CubeEquity.ofX(d, x = 0.0, owner = owner)
            assertEquals(cubeless, eq.holdEquity, 1e-9, "holdEquity for $owner")
        }
        assertEquals(2 * cubeless, CubeEquity.ofX(d, 0.0, CubeOwner.CENTERED).doubleTake, 1e-9)
    }

    @Test fun `meanWin and meanLoss are clamped into one-to-three`() {
        val certainWin = OutcomeDistribution(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val eq = CubeEquity.ofX(certainWin, 0.65, CubeOwner.CENTERED)
        assertTrue(eq.meanWin in 1.0..3.0, "meanWin=${eq.meanWin}")
        assertTrue(eq.meanLoss in 1.0..3.0, "meanLoss=${eq.meanLoss}")
        assertTrue(eq.holdEquity.isFinite())
    }

    @Test fun `higher loss-gammon risk raises the take point (pinned)`() {
        val tpLow = CubeEquity.ofX(gammonless(0.5), 0.65, CubeOwner.CENTERED).takePoint
        val highLoss = OutcomeDistribution(
            winSingle = 0.5, winGammon = 0.0, winBackgammon = 0.0,
            loseSingle = 0.25, loseGammon = 0.25, loseBackgammon = 0.0,
        )
        val tpHigh = CubeEquity.ofX(highLoss, 0.65, CubeOwner.CENTERED).takePoint
        assertEquals(0.2150537, tpLow, 1e-4)
        assertEquals(0.3539823, tpHigh, 1e-4)
        assertTrue(tpHigh > tpLow, "gammon danger must raise the take point")
    }

    @Test fun `higher win-gammon upside lowers the cash point`() {
        val cpLow = CubeEquity.ofX(gammonless(0.5), 0.65, CubeOwner.CENTERED).cashPoint
        val highWin = OutcomeDistribution(
            winSingle = 0.25, winGammon = 0.25, winBackgammon = 0.0,
            loseSingle = 0.5, loseGammon = 0.0, loseBackgammon = 0.0,
        )
        val cpHigh = CubeEquity.ofX(highWin, 0.65, CubeOwner.CENTERED).cashPoint
        assertTrue(cpHigh < cpLow, "win-gammon upside must lower the cash point")
    }

    @Test fun `cube efficiency constants are in range and RACE exceeds CONTACT`() {
        val contact = CubeEquity.CUBE_EFFICIENCY.getValue(GamePhase.CONTACT)
        val race = CubeEquity.CUBE_EFFICIENCY.getValue(GamePhase.RACE)
        assertTrue(contact in 0.0..1.0 && race in 0.0..1.0)
        assertTrue(race > contact, "a race cube is more live than a contact cube")
    }

    @Test fun `out-of-range cube efficiency is rejected`() {
        try {
            CubeEquity.ofX(gammonless(0.5), x = 4.0, owner = CubeOwner.CENTERED)
            assertTrue(false, "expected require() to reject x=4.0")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }
}
