package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnumerationTest {
    private fun board(
        points: IntArray, whiteBar: Int = 0, blackBar: Int = 0, toMove: Player = Player.WHITE,
    ) = BoardState(
        points,
        bar = mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
        off = mapOf(Player.WHITE to 0, Player.BLACK to 0),
        toMove,
    )

    @Test fun `single-die normal moves for white`() {
        val p = IntArray(26); p[13] = 2
        val subs = MoveGenerator.legalSubMovesForTest(board(p), 3)
        assertEquals(1, subs.size)
        assertEquals(SubMove(13, 10, 3, isHit = false), subs.single())
    }

    @Test fun `blocked destination yields no sub-move`() {
        val p = IntArray(26); p[13] = 1; p[10] = -2
        val subs = MoveGenerator.legalSubMovesForTest(board(p), 3)
        assertTrue(subs.isEmpty())
    }

    @Test fun `landing on a blot is flagged as a hit`() {
        val p = IntArray(26); p[13] = 1; p[10] = -1
        val subs = MoveGenerator.legalSubMovesForTest(board(p), 3)
        assertEquals(SubMove(13, 10, 3, isHit = true), subs.single())
    }

    @Test fun `bar-first - only entry sub-moves while on the bar`() {
        val p = IntArray(26); p[13] = 2
        val s = board(p, whiteBar = 1)
        val subs = MoveGenerator.legalSubMovesForTest(s, 6)
        assertEquals(SubMove(from = 25, to = 19, die = 6, isHit = false), subs.single())
    }

    @Test fun `bar entry blocked - no sub-moves`() {
        val p = IntArray(26); p[19] = -2
        val s = board(p, whiteBar = 1)
        assertTrue(MoveGenerator.legalSubMovesForTest(s, 6).isEmpty())
    }

    @Test fun `exact bear-off for white`() {
        val p = IntArray(26); p[3] = 1; p[1] = 1
        val subs = MoveGenerator.legalSubMovesForTest(board(p), 3)
        assertTrue(subs.contains(SubMove(from = 3, to = 0, die = 3, isHit = false)))
    }

    @Test fun `overflow bear-off only from the farthest point`() {
        val p = IntArray(26); p[4] = 1; p[3] = 1
        val subs = MoveGenerator.legalSubMovesForTest(board(p), 5)
        assertTrue(subs.contains(SubMove(from = 4, to = 0, die = 5, isHit = false)))
        assertTrue(subs.none { it.from == 3 && it.to == 0 })
    }

    @Test fun `enumerate produces full turns using both dice`() {
        val p = IntArray(26); p[13] = 2
        val seqs = MoveGenerator.enumerateSequencesForTest(board(p), listOf(3, 5))
        assertTrue(seqs.all { it.size == 2 })
        assertTrue(seqs.isNotEmpty())
    }
}
