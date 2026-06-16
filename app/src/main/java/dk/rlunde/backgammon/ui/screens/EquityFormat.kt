package dk.rlunde.backgammon.ui.screens

import dk.rlunde.backgammon.ai.OutcomeDistribution
import kotlin.math.roundToInt

/** Compact one-line equity readout for the trainer. "G" includes backgammons; "BG" is the subset. */
fun equityLine(d: OutcomeDistribution): String {
    val win = (d.winProb * 100).roundToInt()
    val g = ((d.winGammon + d.winBackgammon) * 100).roundToInt()
    val bg = (d.winBackgammon * 100).roundToInt()
    return "Win %d%% · G %d%% · BG %d%% · Eq %+.2f".format(win, g, bg, d.cubelessEquity)
}
