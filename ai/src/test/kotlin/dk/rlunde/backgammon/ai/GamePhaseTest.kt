package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import dk.rlunde.backgammon.core.BoardState
import kotlin.test.Test
import kotlin.test.assertEquals

class GamePhaseTest {
    @Test fun `starting position is contact`() {
        assertEquals(GamePhase.CONTACT, GamePhases.of(startingPosition()))
    }

    @Test fun `fully separated position is race`() {
        // points[index] holds +WHITE / −BLACK counts. WHITE on 1, BLACK on 24 → no contact.
        val p = IntArray(26).also { it[1] = 15; it[24] = -15 }
        val s = BoardState(p, mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 0, Player.BLACK to 0), Player.WHITE)
        assertEquals(GamePhase.RACE, GamePhases.of(s))
    }
}
