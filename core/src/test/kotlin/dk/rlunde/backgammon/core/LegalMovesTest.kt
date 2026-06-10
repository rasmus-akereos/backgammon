package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegalMovesTest {
    private fun board(
        points: IntArray, whiteBar: Int = 0, blackBar: Int = 0, toMove: Player = Player.WHITE,
    ) = BoardState(
        points,
        bar = mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
        off = mapOf(Player.WHITE to 0, Player.BLACK to 0),
        toMove,
    )

    @Test fun `must use both dice when possible`() {
        val p = IntArray(26); p[13] = 2
        val moves = MoveGenerator.legalMoves(board(p), Dice(3, 5))
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.subMoves.size == 2 })
    }

    @Test fun `forfeit when on the bar and both entry points blocked`() {
        val p = IntArray(26); p[24] = -2; p[23] = -2
        val s = board(p, whiteBar = 1)
        assertEquals(emptyList(), MoveGenerator.legalMoves(s, Dice(1, 2)))
    }

    @Test fun `must play the larger die when only one die is playable`() {
        val p = IntArray(26)
        p[13] = 1
        p[8] = -2  // blocks the 5 (13->8)
        p[2] = -2  // blocks 7->2 so the 5 cannot follow the 6
        val moves = MoveGenerator.legalMoves(board(p), Dice(6, 5))
        assertEquals(1, moves.size)
        val only = moves.single().subMoves.single()
        assertEquals(6, only.die)
        assertEquals(SubMove(13, 7, 6, isHit = false), only)
    }

    @Test fun `dedup returns distinct resulting states only`() {
        val p = IntArray(26); p[13] = 2
        val moves = MoveGenerator.legalMoves(board(p), Dice(3, 5))
        val outcomes = moves.map { MoveGenerator.apply(board(p), it) }
        assertEquals(outcomes.size, outcomes.toSet().size)
    }

    @Test fun `doubles give up to four moves`() {
        val p = IntArray(26); p[13] = 4
        val moves = MoveGenerator.legalMoves(board(p), Dice(2, 2))
        assertTrue(moves.any { it.subMoves.size == 4 })
        assertTrue(moves.all { it.subMoves.size == 4 })
    }
}
