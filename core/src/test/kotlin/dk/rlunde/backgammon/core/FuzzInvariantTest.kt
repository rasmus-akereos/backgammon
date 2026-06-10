package dk.rlunde.backgammon.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FuzzInvariantTest {
    private fun conserves15(s: BoardState): Boolean = Player.entries.all { pl ->
        (1..24).sumOf { s.count(pl, it) } + s.barCount(pl) + s.offCount(pl) == 15
    }

    @Test fun `15 checkers per side hold after every committed turn`() {
        for (seed in listOf(1L, 42L, 7L)) {
            var turns = 0
            playGame(startingPosition(), SeededDiceRoller(seed), Random(seed), maxTurns = 1000) { st ->
                assertTrue(conserves15(st), "invariant broke (seed=$seed)")
                turns++
            }
            assertTrue(turns >= 50, "game with seed=$seed was suspiciously short ($turns states)")
        }
    }

    @Test fun `same seed replays identically`() {
        val statesA = mutableListOf<BoardState>()
        val statesB = mutableListOf<BoardState>()
        playGame(startingPosition(), SeededDiceRoller(123), Random(123)) { statesA.add(it) }
        playGame(startingPosition(), SeededDiceRoller(123), Random(123)) { statesB.add(it) }
        assertEquals(statesA, statesB)
    }
}
