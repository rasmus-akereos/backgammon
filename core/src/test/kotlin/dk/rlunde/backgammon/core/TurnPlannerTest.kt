package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnPlannerTest {
    private fun board(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0, toMove: Player = Player.WHITE) =
        BoardState(
            points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            toMove,
        )

    @Test fun `maxUsablePips is full roll when both dice playable`() {
        val p = IntArray(26); p[13] = 2
        assertEquals(8, TurnPlanner.maxUsablePips(board(p), listOf(3, 5)))
    }

    @Test fun `maxUsablePips is zero when no die playable`() {
        val p = IntArray(26); p[13] = 1; p[10] = -2; p[8] = -2 // both 3 and 5 dests blocked
        assertEquals(0, TurnPlanner.maxUsablePips(board(p), listOf(3, 5)))
    }

    @Test fun `legalNextSubMoves groups by origin and only offers max-pips moves`() {
        val p = IntArray(26); p[13] = 2
        val next = TurnPlanner.legalNextSubMoves(board(p), listOf(3, 5))
        assertTrue(13 in next.keys)
        val dests = next.getValue(13).map { it.to }.toSet()
        assertEquals(setOf(10, 8), dests) // 13->10 (die3), 13->8 (die5)
    }

    @Test fun `doubles - legalNextSubMoves stays non-empty across all four staged dice`() {
        val committed = board(IntArray(26).also { it[13] = 4 })
        var partial = committed
        var remaining = listOf(2, 2, 2, 2)
        repeat(4) { k ->
            val next = TurnPlanner.legalNextSubMoves(partial, remaining)
            assertTrue(next.isNotEmpty(), "stranded after $k staged dice")
            val sm = next.values.first().first()
            partial = MoveGenerator.applyPartial(partial, listOf(sm))
            remaining = remaining.toMutableList().apply { remove(sm.die) }
        }
        assertEquals(0, TurnPlanner.maxUsablePips(partial, remaining))
    }

    @Test fun `must play larger - only the larger single die is offered when both cannot be played`() {
        val p = IntArray(26); p[13] = 1; p[8] = -2; p[2] = -2
        val next = TurnPlanner.legalNextSubMoves(board(p), listOf(6, 5))
        val all = next.values.flatten()
        assertEquals(1, all.size)
        assertEquals(SubMove(13, 7, 6, isHit = false), all.single())
    }
}
