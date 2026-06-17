package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CubeStateTest {
    @Test fun `centred cube may be doubled by either player`() {
        val c = CubeState()
        assertTrue(c.isCentred)
        assertEquals(1, c.value)
        assertTrue(c.mayDouble(Player.WHITE))
        assertTrue(c.mayDouble(Player.BLACK))
    }

    @Test fun `owned cube may be doubled only by the owner`() {
        val c = CubeState(value = 2, owner = Player.WHITE)
        assertTrue(c.mayDouble(Player.WHITE))
        assertFalse(c.mayDouble(Player.BLACK))
    }

    @Test fun `cube at the cap may not be doubled`() {
        assertFalse(CubeState(value = 64, owner = Player.WHITE).mayDouble(Player.WHITE))
    }

    @Test fun `afterTake doubles the value and transfers ownership to the taker`() {
        val c = CubeState(value = 2, owner = Player.WHITE).afterTake(Player.BLACK)
        assertEquals(4, c.value)
        assertEquals(Player.BLACK, c.owner)
    }
}
