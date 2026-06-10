package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BoardStateTest {
    private fun emptyBoard(toMove: Player = Player.WHITE) = BoardState(
        points = IntArray(26),
        bar = mapOf(Player.WHITE to 0, Player.BLACK to 0),
        off = mapOf(Player.WHITE to 0, Player.BLACK to 0),
        toMove = toMove,
    )

    @Test fun `equality is by content, not array reference`() {
        val a = emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 5 }) }
        val b = emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 5 }) }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun `differing points compare unequal`() {
        val a = emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 5 }) }
        val b = emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 4 }) }
        assertNotEquals(a, b)
    }

    @Test fun `differing toMove compare unequal`() {
        assertNotEquals(emptyBoard(Player.WHITE), emptyBoard(Player.BLACK))
    }

    @Test fun `usable as a HashSet key`() {
        val set = hashSetOf(
            emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 5 }) },
        )
        val same = emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 5 }) }
        assertTrue(set.contains(same))
    }

    @Test fun `read helpers expose bar and off as non-null ints`() {
        val s = emptyBoard().copy(bar = mapOf(Player.WHITE to 2, Player.BLACK to 0))
        assertEquals(2, s.barCount(Player.WHITE))
        assertEquals(0, s.offCount(Player.BLACK))
        assertEquals(5, s.copy(points = s.points.copyOf().also { it[6] = 5 }).count(Player.WHITE, 6))
        assertFalse(s.isBlockedFor(Player.WHITE, 6)) // empty point not blocked
    }
}
