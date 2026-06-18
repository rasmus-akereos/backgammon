package dk.rlunde.backgammon.ui

import dk.rlunde.backgammon.ai.Band
import dk.rlunde.backgammon.ui.screens.bandColor
import dk.rlunde.backgammon.ui.screens.bandLabel
import dk.rlunde.backgammon.ui.theme.AppColors
import kotlin.test.Test
import kotlin.test.assertEquals

class BandColorTest {
    @Test fun `each band maps to its AppColors constant`() {
        assertEquals(AppColors.bandBest, bandColor(Band.BEST))
        assertEquals(AppColors.bandGood, bandColor(Band.GOOD))
        assertEquals(AppColors.bandInaccuracy, bandColor(Band.INACCURACY))
        assertEquals(AppColors.bandMistake, bandColor(Band.MISTAKE))
        assertEquals(AppColors.bandBlunder, bandColor(Band.BLUNDER))
    }
    @Test fun `each band has its label`() {
        assertEquals("Best", bandLabel(Band.BEST))
        assertEquals("Good", bandLabel(Band.GOOD))
        assertEquals("Inaccuracy", bandLabel(Band.INACCURACY))
        assertEquals("Mistake", bandLabel(Band.MISTAKE))
        assertEquals("Blunder", bandLabel(Band.BLUNDER))
    }
}
