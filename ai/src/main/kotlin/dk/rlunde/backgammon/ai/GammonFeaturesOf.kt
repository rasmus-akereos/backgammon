package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.Scoring

/** Winner's home board: where a trapped loser checker risks a backgammon. */
private fun winnerHome(winner: Player): IntRange = if (winner == Player.WHITE) 1..6 else 19..24

/** Build [GammonFeatures] for the side assumed to LOSE (spec §4.4). */
internal fun gammonFeaturesOf(s: BoardState, loser: Player): GammonFeatures {
    val winner = loser.opponent
    val backContact = s.barCount(loser) + winnerHome(winner).sumOf { s.count(loser, it) }
    return GammonFeatures(
        borneOff = s.offCount(loser),
        pip = Scoring.pipCount(s, loser),
        backContact = backContact,
    )
}
