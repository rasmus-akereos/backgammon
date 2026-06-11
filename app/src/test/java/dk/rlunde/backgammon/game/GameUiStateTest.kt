package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import dk.rlunde.backgammon.ui.board.BoardTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GameUiStateTest {
    @Test fun `point target rejects out-of-range index`() {
        assertFailsWith<IllegalArgumentException> { BoardTarget.Point(0) }
        assertFailsWith<IllegalArgumentException> { BoardTarget.Point(25) }
    }

    @Test fun `ui state carries the board and phase`() {
        val s = GameUiState(
            board = startingPosition(),
            toMove = Player.WHITE,
            dice = null,
            remainingDice = emptyList(),
            phase = Phase.NEED_ROLL,
            selectedOrigin = null,
            destinations = emptySet(),
            stagedMoves = emptyList(),
            whitePip = 167,
            blackPip = 167,
            winner = null,
            winValue = 0,
        )
        assertEquals(Phase.NEED_ROLL, s.phase)
        assertEquals(167, s.whitePip)
    }
}
