package dk.rlunde.backgammon.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GameConfigTest {
    @Test fun `training defaults to false`() {
        assertFalse(GameConfig().training)
    }

    @Test fun `training is carried through`() {
        assertEquals(true, GameConfig(training = true).training)
    }
}
