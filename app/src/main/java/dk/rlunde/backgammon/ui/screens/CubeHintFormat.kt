package dk.rlunde.backgammon.ui.screens

import dk.rlunde.backgammon.ai.CubeEquities
import dk.rlunde.backgammon.ai.OfferVerdict
import kotlin.math.roundToInt

/** Compact one-line cube readout for the trainer (spec §6.3). Pure; unit-tested. */
fun cubeHintLine(verdict: OfferVerdict, eq: CubeEquities): String {
    val action = when (verdict) {
        OfferVerdict.NO_DOUBLE -> "No double"
        OfferVerdict.DOUBLE -> if (eq.winProb <= eq.cashPoint) "Double / take" else "Double / pass"
        OfferVerdict.TOO_GOOD -> "Too good to double"
    }
    val tp = (eq.takePoint * 100).roundToInt()
    val cp = (eq.cashPoint * 100).roundToInt()
    return "%s · TP %d%% · CP %d%% · Cube %+.2f (cubeless %+.2f)"
        .format(action, tp, cp, eq.holdEquity, eq.cubelessEquity)
}
