package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiceRollerTest {
    @Test fun `same seed produces identical sequence`() {
        val a = SeededDiceRoller(42)
        val b = SeededDiceRoller(42)
        val seqA = List(20) { a.roll() }
        val seqB = List(20) { b.roll() }
        assertEquals(seqA, seqB)
    }

    @Test fun `different seeds usually differ`() {
        val seqA = SeededDiceRoller(1).let { r -> List(20) { r.roll() } }
        val seqB = SeededDiceRoller(2).let { r -> List(20) { r.roll() } }
        assertTrue(seqA != seqB)
    }

    @Test fun `all rolled die values are in 1-6`() {
        val r = SeededDiceRoller(7)
        repeat(200) {
            val d = r.roll()
            assertTrue(d.a in 1..6 && d.b in 1..6)
        }
        repeat(200) {
            val d = RandomDiceRoller().roll()
            assertTrue(d.a in 1..6 && d.b in 1..6)
        }
    }
}
