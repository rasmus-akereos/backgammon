package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CubeDecisionTest {
    private fun gammonless(p: Double) = OutcomeDistribution(
        winSingle = p, winGammon = 0.0, winBackgammon = 0.0,
        loseSingle = 1.0 - p, loseGammon = 0.0, loseBackgammon = 0.0,
    )
    private fun offer(d: OutcomeDistribution, x: Double, owner: CubeOwner) =
        CubeDecision.offer(CubeEquity.ofX(d, x, owner), owner)
    private fun response(d: OutcomeDistribution, x: Double) =
        CubeDecision.response(CubeEquity.ofX(d, x, CubeOwner.ME))

    @Test fun `clear take above the take point`() {
        assertEquals(ResponseVerdict.TAKE, response(gammonless(0.40), 0.65))
    }

    @Test fun `clear drop below the take point`() {
        assertEquals(ResponseVerdict.DROP, response(gammonless(0.10), 0.65))
    }

    @Test fun `marginal take straddling the take point`() {
        assertEquals(ResponseVerdict.DROP, response(gammonless(0.214), 0.65))
        assertEquals(ResponseVerdict.TAKE, response(gammonless(0.216), 0.65))
    }

    @Test fun `centred double window opens at the analytic minimum double point`() {
        assertEquals(OfferVerdict.NO_DOUBLE, offer(gammonless(0.673), 0.65, CubeOwner.CENTERED))
        assertEquals(OfferVerdict.DOUBLE, offer(gammonless(0.675), 0.65, CubeOwner.CENTERED))
    }

    @Test fun `owned redouble window opens later than the centred window`() {
        assertEquals(OfferVerdict.NO_DOUBLE, offer(gammonless(0.709), 0.65, CubeOwner.ME))
        assertEquals(OfferVerdict.DOUBLE, offer(gammonless(0.711), 0.65, CubeOwner.ME))
    }

    @Test fun `too good fires only when cubeless equity exceeds one (pinned cause)`() {
        val tooGood = OutcomeDistribution(0.20, 0.62, 0.03, 0.13, 0.02, 0.0)
        val eq = CubeEquity.ofX(tooGood, 0.65, CubeOwner.CENTERED)
        assertTrue(eq.winProb > eq.cashPoint, "precondition: past cash point")
        assertTrue(eq.cubelessEquity > 1.0, "precondition: cubeless > 1")
        assertEquals(OfferVerdict.TOO_GOOD, CubeDecision.offer(eq, CubeOwner.CENTERED))
    }

    @Test fun `past the cash point but not too good is a normal cash`() {
        val cash = OutcomeDistribution(0.78, 0.04, 0.0, 0.18, 0.0, 0.0)
        val eq = CubeEquity.ofX(cash, 0.65, CubeOwner.CENTERED)
        assertTrue(eq.winProb > eq.cashPoint, "precondition: past cash point")
        assertEquals(OfferVerdict.DOUBLE, CubeDecision.offer(eq, CubeOwner.CENTERED))
    }

    @Test fun `centred cube-access inflation does not spuriously trigger too good`() {
        val moderate = OutcomeDistribution(0.55, 0.25, 0.0, 0.20, 0.0, 0.0)
        val eq = CubeEquity.ofX(moderate, 0.65, CubeOwner.CENTERED)
        assertTrue(eq.holdEquity > 1.0, "precondition: centred equity inflated")
        assertTrue(eq.cubelessEquity < 1.0, "precondition: cubeless < 1")
        assertEquals(OfferVerdict.DOUBLE, CubeDecision.offer(eq, CubeOwner.CENTERED))
    }

    @Test fun `opponent-owned cube can never be doubled`() {
        assertEquals(OfferVerdict.NO_DOUBLE, offer(gammonless(0.99), 0.65, CubeOwner.OPPONENT))
    }

    @Test fun `certain single win cashes rather than playing on`() {
        val certain = OutcomeDistribution(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(OfferVerdict.DOUBLE, offer(certain, 0.65, CubeOwner.CENTERED))
    }

    @Test fun `certain loss is a drop`() {
        assertEquals(ResponseVerdict.DROP, response(OutcomeDistribution(0.0, 0.0, 0.0, 1.0, 0.0, 0.0), 0.65))
    }
}
