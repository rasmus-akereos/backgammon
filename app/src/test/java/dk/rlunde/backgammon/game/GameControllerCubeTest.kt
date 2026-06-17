package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals

class GameControllerCubeTest {
    private fun hotSeat() = GameController(initial = startingPosition(), aiSide = null)

    // --- T3: offerDouble + cube state ----------------------------------------------------
    @Test fun `cube starts centred at one`() {
        val c = hotSeat()
        assertEquals(1, c.uiState.cube.value)
        assertEquals(null, c.uiState.cube.owner)
        assertEquals(true, c.uiState.canDouble)
    }

    @Test fun `offerDouble moves to CUBE_OFFERED without changing the cube value`() {
        val c = hotSeat()
        c.offerDouble()
        assertEquals(Phase.CUBE_OFFERED, c.uiState.phase)
        assertEquals(1, c.uiState.cube.value)
    }

    @Test fun `offerDouble is a no-op outside NEED_ROLL`() {
        val c = hotSeat()
        c.roll()
        val before = c.uiState.phase
        c.offerDouble()
        assertEquals(before, c.uiState.phase)
    }

    @Test fun `offerDouble is a no-op in a vs-computer game`() {
        val c = GameController(initial = startingPosition(), aiSide = Player.BLACK)
        c.offerDouble()
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    }

    // --- T4: respondDouble ---------------------------------------------------------------
    @Test fun `take doubles the cube, transfers ownership, returns to the same player on roll`() {
        val c = hotSeat()
        val doublerSide = c.uiState.toMove
        c.offerDouble()
        c.respondDouble(CubeResponse.TAKE)
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
        assertEquals(doublerSide, c.uiState.toMove)
        assertEquals(2, c.uiState.cube.value)
        assertEquals(doublerSide.opponent, c.uiState.cube.owner)
    }

    @Test fun `after a take only the taker may redouble`() {
        val c = hotSeat()
        c.offerDouble()
        c.respondDouble(CubeResponse.TAKE)
        assertEquals(false, c.uiState.canDouble)
        c.offerDouble()
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
        assertEquals(2, c.uiState.cube.value)
    }

    @Test fun `drop ends the game, doubler wins the pre-double cube value`() {
        val c = hotSeat()
        val doublerSide = c.uiState.toMove
        c.offerDouble()
        c.respondDouble(CubeResponse.DROP)
        assertEquals(Phase.GAME_OVER, c.uiState.phase)
        assertEquals(doublerSide, c.uiState.winner)
        assertEquals(1, c.uiState.winValue)
        assertEquals(1, c.uiState.cube.value)
        assertEquals(EndReason.DROP, c.uiState.endReason)
    }

    @Test fun `respondDouble is a no-op outside CUBE_OFFERED`() {
        val c = hotSeat()
        c.respondDouble(CubeResponse.TAKE)
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
        assertEquals(1, c.uiState.cube.value)
    }

    // --- T5: resign ----------------------------------------------------------------------
    @Test fun `resign ends the game, opponent wins one times the cube`() {
        val c = hotSeat()
        c.resign(Player.WHITE)
        assertEquals(Phase.GAME_OVER, c.uiState.phase)
        assertEquals(Player.BLACK, c.uiState.winner)
        assertEquals(1, c.uiState.winValue)
        assertEquals(EndReason.RESIGN, c.uiState.endReason)
    }

    @Test fun `resign carries the live cube value into the stake`() {
        val c = hotSeat()
        c.offerDouble()
        c.respondDouble(CubeResponse.TAKE) // cube now 2
        c.resign(c.uiState.toMove)
        assertEquals(2, c.uiState.cube.value)
        assertEquals(1, c.uiState.winValue)
    }

    @Test fun `resign is a no-op once the game is over`() {
        val c = hotSeat()
        c.resign(Player.WHITE)
        c.resign(Player.BLACK)
        assertEquals(Player.BLACK, c.uiState.winner)
    }

    // --- T6: full loop -------------------------------------------------------------------
    @Test fun `full loop - double, take, play on, cube survives and scales the stake`() {
        val c = hotSeat()
        c.offerDouble()
        c.respondDouble(CubeResponse.TAKE)                 // cube = 2, doubler on roll
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
        c.roll()                                           // doubler rolls a normal turn
        assertEquals(2, c.uiState.cube.value)              // cube survives the take→roll transition
        // The board-win × cube stake arithmetic itself is covered by ResultTextTest.formatResult.
    }
}
