package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShotTableTest {
    @Test fun `direct shot distances are 11 over 36`() {
        assertEquals(11.0 / 36.0, ShotTable.hitProbability(1), 1e-9)
    }

    @Test fun `distance six is 17 over 36 (11 direct + 6 combination)`() {
        assertEquals(17.0 / 36.0, ShotTable.hitProbability(6), 1e-9)
    }

    @Test fun `combination-only distances`() {
        assertEquals(6.0 / 36.0, ShotTable.hitProbability(8), 1e-9)
        assertEquals(2.0 / 36.0, ShotTable.hitProbability(11), 1e-9)
        assertEquals(3.0 / 36.0, ShotTable.hitProbability(12), 1e-9)
    }

    @Test fun `out of range distances are zero`() {
        assertEquals(0.0, ShotTable.hitProbability(0), 1e-9)
        assertEquals(0.0, ShotTable.hitProbability(13), 1e-9)
        assertEquals(0.0, ShotTable.hitProbability(99), 1e-9)
    }

    @Test fun `direct distances dominate any indirect-only distance`() {
        for (d in 1..6) assertTrue(ShotTable.hitProbability(d) >= ShotTable.hitProbability(8))
    }
}
