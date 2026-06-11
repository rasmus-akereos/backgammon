package dk.rlunde.backgammon.ui.board

import dk.rlunde.backgammon.core.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
