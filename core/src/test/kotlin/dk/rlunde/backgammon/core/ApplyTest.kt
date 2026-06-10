package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ApplyTest {
    private fun board(
        points: IntArray,
        whiteBar: Int = 0, blackBar: Int = 0,
        whiteOff: Int = 0, blackOff: Int = 0,
        toMove: Player = Player.WHITE,
    ) = BoardState(
        points,
        bar = mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
        off = mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff),
        toMove,
    )

    @Test fun `simple white move relocates a checker and flips toMove`() {
        val p = IntArray(26); p[13] = 5
        val s = board(p)
        val out = MoveGenerator.apply(s, Move(listOf(SubMove(13, 10, 3, isHit = false))))
        assertEquals(4, out.count(Player.WHITE, 13))
        assertEquals(1, out.count(Player.WHITE, 10))
        assertEquals(Player.BLACK, out.toMove)
    }

    @Test fun `hit sends the opponent blot to the bar`() {
        val p = IntArray(26); p[13] = 1; p[10] = -1
        val s = board(p)
        val out = MoveGenerator.apply(s, Move(listOf(SubMove(13, 10, 3, isHit = true))))
        assertEquals(1, out.count(Player.WHITE, 10))
        assertEquals(0, out.count(Player.BLACK, 10))
        assertEquals(1, out.barCount(Player.BLACK))
    }

    @Test fun `white enters from the bar`() {
        val p = IntArray(26)
        val s = board(p, whiteBar = 1)
        val out = MoveGenerator.apply(s, Move(listOf(SubMove(from = 25, to = 19, die = 6, isHit = false))))
        assertEquals(0, out.barCount(Player.WHITE))
        assertEquals(1, out.count(Player.WHITE, 19))
    }

    @Test fun `white bears off`() {
        val p = IntArray(26); p[3] = 1
        val s = board(p, whiteOff = 14)
        val out = MoveGenerator.apply(s, Move(listOf(SubMove(from = 3, to = 0, die = 3, isHit = false))))
        assertEquals(15, out.offCount(Player.WHITE))
        assertEquals(0, out.count(Player.WHITE, 3))
    }

    @Test fun `apply does not mutate or alias the input`() {
        val p = IntArray(26); p[13] = 5
        val s = board(p)
        val before = s.points.toList()
        val out = MoveGenerator.apply(s, Move(listOf(SubMove(13, 10, 3, isHit = false))))
        assertEquals(before, s.points.toList())
        assertNotSame(s.points, out.points)
    }

    @Test fun `pass flips toMove and changes nothing else`() {
        val s = startingPosition()
        val out = MoveGenerator.pass(s)
        assertEquals(Player.BLACK, out.toMove)
        assertEquals(s.points.toList(), out.points.toList())
        assertTrue(s.bar == out.bar && s.off == out.off)
    }
}
