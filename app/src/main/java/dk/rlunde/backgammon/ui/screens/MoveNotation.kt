package dk.rlunde.backgammon.ui.screens

import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.SubMove

/** Standard backgammon notation. off = 0/25, bar = 25/0 (WHITE/BLACK). Hits append '*'. */
fun notation(move: Move): String = move.subMoves.joinToString(" ") { point(it) }

private fun point(sm: SubMove): String {
    val from = if (sm.from == 0 || sm.from == 25) "bar" else sm.from.toString()
    val to = if (sm.to == 0 || sm.to == 25) "off" else sm.to.toString()
    return "$from/$to" + if (sm.isHit) "*" else ""
}
