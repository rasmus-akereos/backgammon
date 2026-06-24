package dk.rlunde.backgammon.ui

import dk.rlunde.backgammon.ai.CubeEquities
import dk.rlunde.backgammon.ai.OfferVerdict
import dk.rlunde.backgammon.ui.screens.cubeHintLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CubeHintFormatTest {
    private fun eq(winProb: Double, cashPoint: Double) = CubeEquities(
        winProb = winProb, meanWin = 1.5, meanLoss = 1.2, cubeEfficiency = 0.65,
        cubelessEquity = 0.5, takePoint = 0.22, cashPoint = cashPoint,
        holdEquity = 0.4, doubleTake = 0.3,
    )

    @Test fun `double take when below the cash point`() {
        assertTrue(cubeHintLine(OfferVerdict.DOUBLE, eq(0.70, 0.79)).startsWith("Double / take"))
    }

    @Test fun `double pass when above the cash point`() {
        assertTrue(cubeHintLine(OfferVerdict.DOUBLE, eq(0.85, 0.79)).startsWith("Double / pass"))
    }

    @Test fun `too good label`() {
        assertTrue(cubeHintLine(OfferVerdict.TOO_GOOD, eq(0.90, 0.60)).startsWith("Too good to double"))
    }

    @Test fun `no double label`() {
        assertTrue(cubeHintLine(OfferVerdict.NO_DOUBLE, eq(0.55, 0.79)).startsWith("No double"))
    }

    @Test fun `includes take and cash points`() {
        val line = cubeHintLine(OfferVerdict.NO_DOUBLE, eq(0.55, 0.79))
        assertTrue(line.contains("TP 22%") && line.contains("CP 79%"))
    }

    @Test fun `full line format is exact`() {
        val eq = CubeEquities(
            winProb = 0.55, meanWin = 1.5, meanLoss = 1.2, cubeEfficiency = 0.65,
            cubelessEquity = 0.50, takePoint = 0.22, cashPoint = 0.79,
            holdEquity = 0.40, doubleTake = 0.30,
        )
        assertEquals(
            "No double · TP 22% · CP 79% · Cube +0.40 (cubeless +0.50)",
            cubeHintLine(OfferVerdict.NO_DOUBLE, eq),
        )
    }
}
