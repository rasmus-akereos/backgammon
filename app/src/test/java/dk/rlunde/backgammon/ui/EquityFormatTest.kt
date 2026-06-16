package dk.rlunde.backgammon.ui

import dk.rlunde.backgammon.ai.OutcomeDistribution
import dk.rlunde.backgammon.ui.screens.equityLine
import kotlin.test.Test
import kotlin.test.assertEquals

class EquityFormatTest {
    @Test fun `formats win gammon bg and equity`() {
        val d = OutcomeDistribution(0.50, 0.18, 0.02, 0.20, 0.08, 0.02)
        // winProb = .70, gammon (incl bg) = .20, bg = .02; equity = 0.92 − 0.42 = +0.50
        assertEquals("Win 70% · G 20% · BG 2% · Eq +0.50", equityLine(d))
    }
}
