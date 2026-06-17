package dk.rlunde.backgammon.ui.board

import dk.rlunde.backgammon.core.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardGeometryTest {
    private val g = BoardGeometry(1080f, 1920f)

    @Test fun `every point center hit-tests to that point`() {
        for (i in 1..24) {
            val r = g.pointRect(i)
            assertEquals(BoardTarget.Point(i), g.hitTest(r.cx, r.cy), "point $i")
        }
    }

    @Test fun `bar center hits Bar`() {
        val r = g.barRect()
        assertEquals(BoardTarget.Bar, g.hitTest(r.cx, r.cy))
    }

    @Test fun `tray centers hit the right player`() {
        assertEquals(BoardTarget.BearOff(Player.WHITE), g.hitTest(g.bearOffRect(Player.WHITE).cx, g.bearOffRect(Player.WHITE).cy))
        assertEquals(BoardTarget.BearOff(Player.BLACK), g.hitTest(g.bearOffRect(Player.BLACK).cx, g.bearOffRect(Player.BLACK).cy))
    }

    @Test fun `tap outside any region returns null`() {
        assertNull(g.hitTest(-5f, -5f))
    }

    @Test fun `centred cube sits at mid-height in the tray column`() {
        val centred = g.cubeRect(owner = null)
        val tray = g.bearOffRect(Player.WHITE) // tray x-band on the right edge
        assertTrue(centred.cx in tray.l..1080f, "cube x in tray column: ${centred.cx}")
        assertEquals(960f, centred.cy, 1f) // mid-height of 1920
    }

    @Test fun `owned cube shifts toward the owner's half`() {
        val white = g.cubeRect(owner = Player.WHITE) // White home = bottom
        val black = g.cubeRect(owner = Player.BLACK) // top
        assertTrue(white.cy > 960f, "white-owned cube in lower half")
        assertTrue(black.cy < 960f, "black-owned cube in upper half")
    }
}
