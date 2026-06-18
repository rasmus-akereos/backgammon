package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CubePolicyTest {
    @Test fun `doubles at or above the double threshold`() {
        assertTrue(CubePolicy.shouldDouble(0.70))
        assertTrue(CubePolicy.shouldDouble(0.85))
        assertFalse(CubePolicy.shouldDouble(0.69))
    }
    @Test fun `takes at or above the take point, drops below`() {
        assertTrue(CubePolicy.shouldTake(0.21))
        assertTrue(CubePolicy.shouldTake(0.40))
        assertFalse(CubePolicy.shouldTake(0.20))
    }
}
