package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EquityModelTest {
    private val w = Weights.FULL

    @Test fun `components are non-negative and sum to one`() {
        val d = EquityModel.distribution(startingPosition(), Player.WHITE, w)
        val sum = d.winSingle + d.winGammon + d.winBackgammon +
                  d.loseSingle + d.loseGammon + d.loseBackgammon
        assertEquals(1.0, sum, 1e-6)
        listOf(d.winSingle, d.winGammon, d.winBackgammon, d.loseSingle, d.loseGammon, d.loseBackgammon)
            .forEach { assertTrue(it >= 0.0, "component negative: $it") }
    }

    @Test fun `symmetric start is roughly even`() {
        val d = EquityModel.distribution(startingPosition(), Player.WHITE, w)
        assertEquals(0.5, d.winProb, 1e-6)         // eval differential is 0 at the start
        assertEquals(0.0, d.cubelessEquity, 1e-6)
    }

    @Test fun `swapping perspective negates equity`() {
        val white = EquityModel.distribution(startingPosition(), Player.WHITE, w)
        val black = EquityModel.distribution(startingPosition(), Player.BLACK, w)
        assertEquals(white.cubelessEquity, -black.cubelessEquity, 1e-6)
    }
}
