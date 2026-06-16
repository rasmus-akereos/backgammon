package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.Scoring
import kotlin.test.Test
import kotlin.test.assertEquals

class GammonFeaturesOfTest {
    @Test fun `loser back-contact counts bar plus checkers in winner home`() {
        // +WHITE / −BLACK counts. Winner = WHITE (home 1..6). BLACK: 1 on bar, 2 on point 3, 12 on 13.
        val p = IntArray(26).also { it[3] = -2; it[13] = -12 }
        val s = BoardState(p, mapOf(Player.WHITE to 0, Player.BLACK to 1),
            mapOf(Player.WHITE to 0, Player.BLACK to 0), Player.WHITE)
        val f = gammonFeaturesOf(s, loser = Player.BLACK)
        assertEquals(3, f.backContact)        // 1 on bar + 2 on point 3
        assertEquals(0, f.borneOff)
        assertEquals(Scoring.pipCount(s, Player.BLACK), f.pip)
    }
}
