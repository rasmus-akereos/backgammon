package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeBudgetTest {
    @Test fun `not exhausted until spend reaches max`() {
        val b = NodeBudget(10)
        assertFalse(b.exhausted)
        b.spend(9)
        assertFalse(b.exhausted)
        b.spend(1)
        assertTrue(b.exhausted)        // 10 >= 10
    }

    @Test fun `overshoot stays exhausted`() {
        val b = NodeBudget(5)
        b.spend(100)
        assertTrue(b.exhausted)
    }
}
