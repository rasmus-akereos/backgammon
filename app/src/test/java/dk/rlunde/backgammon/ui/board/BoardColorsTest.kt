package dk.rlunde.backgammon.ui.board

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class BoardColorsTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05; val lb = b.luminance() + 0.05
        return (if (la > lb) la / lb else lb / la).toDouble()
    }

    @Test fun `point light family is clearly lighter than the dark family`() {
        val lightMin = minOf(BoardColors.pointLightTop.luminance(), BoardColors.pointLightBase.luminance())
        val darkMax = maxOf(BoardColors.pointDarkTop.luminance(), BoardColors.pointDarkBase.luminance())
        assertTrue(lightMin > darkMax, "light family must stay lighter than dark family")
    }

    @Test fun `point alternation keeps at least 3 to 1 contrast`() {
        assertTrue(contrast(BoardColors.pointLightBase, BoardColors.pointDarkTop) >= 3.0)
    }

    @Test fun `checker ridge rim is lighter than its groove`() {
        assertTrue(BoardColors.whiteRim.luminance() > BoardColors.whiteGroove.luminance())
        assertTrue(BoardColors.blackRim.luminance() > BoardColors.blackGroove.luminance())
    }
}
