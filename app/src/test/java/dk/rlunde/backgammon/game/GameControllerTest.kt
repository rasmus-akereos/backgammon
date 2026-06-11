package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.*
import dk.rlunde.backgammon.ui.board.BoardTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameControllerTest {
    private fun controller(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                           whiteOff: Int = 0, blackOff: Int = 0,
                           toMove: Player = Player.WHITE, seed: Long = 1L): GameController {
        val board = BoardState(
            points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff),
            toMove,
        )
        return GameController(initial = board, roller = SeededDiceRoller(seed))
    }

    @Test fun `fresh controller needs a roll`() {
        val c = GameController(roller = SeededDiceRoller(1))
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
        assertEquals(167, c.uiState.whitePip)
    }

    @Test fun `roll then move then commit flips the turn`() {
        val p = IntArray(26); p[13] = 2; p[24] = 2
        p[1] = -2
        val c = controller(p)
        c.roll()
        assertEquals(Phase.MOVING, c.uiState.phase)
        c.tap(BoardTarget.Point(13))
        assertTrue(c.uiState.destinations.isNotEmpty())
        val dest = c.uiState.destinations.first()
        c.tap(dest)
        while (c.uiState.phase == Phase.MOVING) {
            val tgt = c.uiState.destinations.firstOrNull()
            if (tgt != null) c.tap(tgt) else c.tapAnyLegalOrigin()
        }
        assertEquals(Phase.COMMITTABLE, c.uiState.phase)
        c.commit()
        assertEquals(Player.BLACK, c.uiState.toMove)
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    }

    @Test fun `auto-pass emits event when no legal moves`() {
        val p = IntArray(26)
        p[24] = -2; p[23] = -2; p[22] = -2; p[21] = -2; p[20] = -2; p[19] = -2
        val c = controller(p, whiteBar = 1, toMove = Player.WHITE, seed = 1)
        val events = c.roll()
        assertTrue(events.contains(UiEvent.NoLegalMoves))
        c.acknowledgePass()
        assertEquals(Player.BLACK, c.uiState.toMove)
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    }

    @Test fun `game over reports winner and value from a near-terminal position`() {
        // Sole WHITE checker on point 1: ANY die bears it off, so the test is seed-independent.
        val p = IntArray(26); p[1] = 1; p[19] = -15
        val c = controller(p, whiteOff = 14, toMove = Player.WHITE, seed = 3)
        c.roll()
        while (c.uiState.phase == Phase.MOVING) { c.tapAnyLegalOrigin(); c.tapFirstDestination() }
        if (c.uiState.phase == Phase.COMMITTABLE) c.commit()
        assertEquals(Phase.GAME_OVER, c.uiState.phase)
        assertEquals(Player.WHITE, c.uiState.winner)
        assertEquals(2, c.uiState.winValue)
    }

    @Test fun `undo with nothing staged is a no-op`() {
        val c = GameController(roller = SeededDiceRoller(1))
        c.roll()
        val before = c.uiState
        c.undo()
        assertEquals(before.phase, c.uiState.phase)
    }

    @Test fun `undo after staging pops the sub-move and restores a die`() {
        val p = IntArray(26); p[13] = 2; p[1] = -2
        val c = controller(p)
        c.roll()
        assertEquals(Phase.MOVING, c.uiState.phase)
        c.tap(BoardTarget.Point(13))
        val remainingBeforeStage = c.uiState.remainingDice.size
        c.tap(c.uiState.destinations.first()) // stage one sub-move
        assertEquals(remainingBeforeStage - 1, c.uiState.remainingDice.size)
        c.undo()
        assertEquals(remainingBeforeStage, c.uiState.remainingDice.size) // die restored
        assertEquals(Phase.MOVING, c.uiState.phase)
    }

    @Test fun `tap after game over is a no-op`() {
        val p = IntArray(26); p[1] = 1; p[19] = -15
        val c = controller(p, whiteOff = 14, toMove = Player.WHITE, seed = 3)
        c.roll()
        while (c.uiState.phase == Phase.MOVING) { c.tapAnyLegalOrigin(); c.tapFirstDestination() }
        if (c.uiState.phase == Phase.COMMITTABLE) c.commit()
        assertEquals(Phase.GAME_OVER, c.uiState.phase)
        val before = c.uiState
        c.tap(BoardTarget.Point(13))
        assertEquals(before, c.uiState)
    }

    @Test fun `tap during NEED_ROLL is a no-op`() {
        val c = GameController(roller = SeededDiceRoller(1))
        val before = c.uiState
        c.tap(BoardTarget.Point(13))
        assertEquals(before, c.uiState)
    }

    @Test fun `new game resets to the initial board`() {
        val c = GameController(roller = SeededDiceRoller(1))
        c.roll()
        c.newGame()
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
        assertEquals(startingPosition().points.toList(), c.uiState.board.points.toList())
    }
}

private fun GameController.tapAnyLegalOrigin() {
    (1..24).map { BoardTarget.Point(it) }.firstOrNull { tgtCandidate ->
        tap(tgtCandidate); uiState.destinations.isNotEmpty()
    }
    if (uiState.destinations.isEmpty()) tap(BoardTarget.Bar)
    // Fail fast rather than spin forever in a `while (phase == MOVING)` loop with no legal origin.
    check(uiState.destinations.isNotEmpty() || uiState.phase != Phase.MOVING) {
        "tapAnyLegalOrigin: no legal origin found while still MOVING"
    }
}
private fun GameController.tapFirstDestination() {
    uiState.destinations.firstOrNull()?.let { tap(it) }
}
