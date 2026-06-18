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
        val s = state(Phase.NEED_ROLL, Player.WHITE, aiSide = null)
        assertEquals(Phase.NEED_ROLL, s.phase)
        assertEquals(167, s.whitePip)
    }

    @Test fun `it is the ai's turn while the ai side is to move mid-game`() {
        assertEquals(true, state(Phase.MOVING, Player.BLACK, aiSide = Player.BLACK).isAiTurn)
    }

    @Test fun `it is not the ai's turn once the game is over`() {
        // Human (WHITE) bears off last checker: the committing flips toMove to the AI side and the
        // phase becomes GAME_OVER. The panel must show "New game", not a stuck "AI thinking…".
        assertEquals(false, state(Phase.GAME_OVER, Player.BLACK, aiSide = Player.BLACK).isAiTurn)
    }

    @Test fun `canDouble is true at need-roll with a centred cube in hot-seat`() {
        assertEquals(true, state(Phase.NEED_ROLL, Player.WHITE, aiSide = null).canDouble)
    }

    @Test fun `canDouble is true on the human's turn vs computer`() {
        assertEquals(true, state(Phase.NEED_ROLL, Player.WHITE, aiSide = Player.BLACK).canDouble)
    }
    @Test fun `canDouble is false on the AI's turn`() {
        assertEquals(false, state(Phase.NEED_ROLL, Player.BLACK, aiSide = Player.BLACK).canDouble)
    }

    @Test fun `canDouble is false when not at need-roll`() {
        assertEquals(false, state(Phase.MOVING, Player.WHITE, aiSide = null).canDouble)
    }

    @Test fun `canDouble is false when the opponent owns the cube`() {
        val s = state(Phase.NEED_ROLL, Player.WHITE, aiSide = null).copy(cube = CubeState(2, Player.BLACK))
        assertEquals(false, s.canDouble)
    }

    private fun state(phase: Phase, toMove: Player, aiSide: Player?, cube: CubeState = CubeState()) = GameUiState(
        board = startingPosition(),
        toMove = toMove,
        dice = null,
        remainingDice = emptyList(),
        phase = phase,
        selectedOrigin = null,
        destinations = emptySet(),
        stagedMoves = emptyList(),
        whitePip = 167,
        blackPip = 167,
        winner = null,
        winValue = 0,
        aiSide = aiSide,
        cube = cube,
    )
}
