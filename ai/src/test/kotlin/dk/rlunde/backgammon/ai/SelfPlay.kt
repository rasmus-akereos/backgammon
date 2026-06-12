package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*

/** Salts to derive each player's RNG seed from one game seed, decorrelated from the dice roller. */
internal const val WHITE_SALT = 0x5DEECE66DL
internal const val BLACK_SALT = 0x1A2B3C4D5L

enum class Outcome { WHITE_WIN, BLACK_WIN, TIMEOUT }

/** [value] = 1/2/3 win multiplier, or 0 on TIMEOUT. */
data class GameResult(val outcome: Outcome, val value: Int, val turns: Int)

/**
 * Plays one full AI-vs-AI game from the standard start. Deterministic given the [roller] and the RNGs
 * inside each AiPlayer. A game exceeding [maxTurns] returns TIMEOUT (never a fabricated winner).
 * Mirrors core's GameDriver.kt/playGame; this is a test util, not a *Test class.
 */
fun selfPlay(white: AiPlayer, black: AiPlayer, roller: DiceRoller, maxTurns: Int = 2000): GameResult {
    var state = startingPosition()
    var turns = 0
    while (!Scoring.isGameOver(state) && turns < maxTurns) {
        val dice = roller.roll()
        val legal = MoveGenerator.legalMoves(state, dice)
        state = if (legal.isEmpty()) {
            MoveGenerator.pass(state)
        } else {
            val mover = if (state.toMove == Player.WHITE) white else black
            MoveGenerator.apply(state, mover.chooseMove(state, dice, legal))
        }
        turns++
    }
    val wv = Scoring.winnerAndValue(state) ?: return GameResult(Outcome.TIMEOUT, 0, turns)
    val outcome = if (wv.first == Player.WHITE) Outcome.WHITE_WIN else Outcome.BLACK_WIN
    return GameResult(outcome, wv.second, turns)
}
