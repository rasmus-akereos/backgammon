package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FilterDedupTest {
    @Test fun `filterMaxPips keeps only the longest-pip sequences`() {
        val a = listOf(SubMove(13, 10, 3, false))
        val b = listOf(SubMove(13, 10, 3, false), SubMove(13, 8, 5, false))
        val kept = MoveGenerator.filterMaxPipsForTest(listOf(a, b, emptyList()))
        assertEquals(listOf(b), kept)
    }

    @Test fun `dedup collapses sequences that reach the same board`() {
        val p = IntArray(26); p[13] = 2
        val s = BoardState(
            p,
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            Player.WHITE,
        )
        val seq1 = listOf(SubMove(13, 10, 3, false), SubMove(13, 8, 5, false))
        val seq2 = listOf(SubMove(13, 8, 5, false), SubMove(13, 10, 3, false))
        val moves = MoveGenerator.dedupByOutcomeForTest(s, listOf(seq1, seq2))
        assertEquals(1, moves.size)
    }
}
