package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.ai.CubeOwner
import dk.rlunde.backgammon.core.Player
import kotlin.test.Test
import kotlin.test.assertEquals

class CubeOwnershipTest {
    @Test fun `centred cube maps to CENTERED`() {
        assertEquals(CubeOwner.CENTERED, ownerFor(CubeState(value = 1, owner = null), Player.WHITE))
    }

    @Test fun `cube owned by the side maps to ME`() {
        assertEquals(CubeOwner.ME, ownerFor(CubeState(value = 2, owner = Player.WHITE), Player.WHITE))
    }

    @Test fun `cube owned by the other side maps to OPPONENT`() {
        assertEquals(CubeOwner.OPPONENT, ownerFor(CubeState(value = 2, owner = Player.BLACK), Player.WHITE))
    }
}
