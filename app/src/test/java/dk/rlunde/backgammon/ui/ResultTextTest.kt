package dk.rlunde.backgammon.ui

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.CubeState
import dk.rlunde.backgammon.game.EndReason
import dk.rlunde.backgammon.ui.screens.formatResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultTextTest {
    @Test fun `board win shows the gammon level and cube`() {
        assertEquals("BLACK wins 4 (gammon, 2-cube)",
            formatResult(Player.BLACK, winValue = 2, cube = CubeState(2, Player.BLACK), reason = EndReason.BORNE_OFF))
    }

    @Test fun `single board win on a centred cube omits the cube note`() {
        assertEquals("WHITE wins 1 (single)",
            formatResult(Player.WHITE, winValue = 1, cube = CubeState(), reason = EndReason.BORNE_OFF))
    }

    @Test fun `drop shows the drop reason and the pre-double stake`() {
        assertEquals("WHITE wins 2 (drop, 2-cube)",
            formatResult(Player.WHITE, winValue = 1, cube = CubeState(2, Player.WHITE), reason = EndReason.DROP))
    }

    @Test fun `resign shows the resign reason`() {
        assertEquals("BLACK wins 1 (resign)",
            formatResult(Player.BLACK, winValue = 1, cube = CubeState(), reason = EndReason.RESIGN))
    }
}
