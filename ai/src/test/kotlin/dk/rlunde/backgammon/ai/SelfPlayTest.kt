package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelfPlayTest {
    private fun intermediate(seed: Long) = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(seed))

    @Test fun `same seeds give identical results`() {
        fun run(): GameResult =
            selfPlay(intermediate(7L xor WHITE_SALT), intermediate(7L xor BLACK_SALT), SeededDiceRoller(7L))
        assertEquals(run(), run())
    }

    @Test fun `a normal game finishes with a winner`() {
        val r = selfPlay(intermediate(3L xor WHITE_SALT), intermediate(3L xor BLACK_SALT), SeededDiceRoller(3L))
        assertTrue(r.outcome == Outcome.WHITE_WIN || r.outcome == Outcome.BLACK_WIN)
        assertTrue(r.value in 1..3)
    }

    @Test fun `a tiny maxTurns yields TIMEOUT, not a fake winner`() {
        val r = selfPlay(intermediate(1L xor WHITE_SALT), intermediate(1L xor BLACK_SALT), SeededDiceRoller(1L), maxTurns = 3)
        assertEquals(Outcome.TIMEOUT, r.outcome)
        assertEquals(0, r.value)
    }
}
