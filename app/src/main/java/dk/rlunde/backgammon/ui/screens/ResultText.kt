package dk.rlunde.backgammon.ui.screens

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.CubeState
import dk.rlunde.backgammon.game.EndReason

/** Game-over text, e.g. "BLACK wins 4 (gammon, 2-cube)". Pure — unit-tested. */
fun formatResult(winner: Player, winValue: Int, cube: CubeState, reason: EndReason): String {
    val points = cube.value * winValue
    val tag = when (reason) {
        EndReason.DROP -> "drop"
        EndReason.RESIGN -> "resign"
        EndReason.BORNE_OFF -> when (winValue) { 3 -> "backgammon"; 2 -> "gammon"; else -> "single" }
    }
    val cubeNote = if (cube.value > 1) ", ${cube.value}-cube" else ""
    return "$winner wins $points ($tag$cubeNote)"
}
