package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.ai.CubeOwner
import dk.rlunde.backgammon.core.Player

/** Maps :app's CubeState ownership to :ai's CubeOwner, from [side]'s perspective (spec §6). */
fun ownerFor(cube: CubeState, side: Player): CubeOwner = when {
    cube.isCentred -> CubeOwner.CENTERED
    cube.owner == side -> CubeOwner.ME
    else -> CubeOwner.OPPONENT
}
