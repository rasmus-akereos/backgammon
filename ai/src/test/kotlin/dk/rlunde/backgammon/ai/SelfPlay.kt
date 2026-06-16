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

/** One labelled ply: the mover's post-move static eval, phase, and gammon features for each side. */
internal data class CalibrationSample(
    val mover: Player,
    val eval: Double,
    val phase: GamePhase,
    val winnerFeatures: GammonFeatures, // opponent-as-loser features (if mover wins)
    val loserFeatures: GammonFeatures,  // mover-as-loser features (if mover loses)
)

internal data class CalibrationGame(val samples: List<CalibrationSample>, val result: GameResult)

/** Like [selfPlay] but records a sample after each non-pass move, for offline calibration. */
internal fun selfPlayTrajectory(
    white: AiPlayer, black: AiPlayer, roller: DiceRoller,
    weights: Weights, maxTurns: Int = 2000,
): CalibrationGame {
    var state = startingPosition()
    var turns = 0
    val samples = ArrayList<CalibrationSample>()
    while (!Scoring.isGameOver(state) && turns < maxTurns) {
        val dice = roller.roll()
        val legal = MoveGenerator.legalMoves(state, dice)
        if (legal.isEmpty()) { state = MoveGenerator.pass(state); turns++; continue }
        val mover = state.toMove
        val ai = if (mover == Player.WHITE) white else black
        state = MoveGenerator.apply(state, ai.chooseMove(state, dice, legal))
        samples.add(
            CalibrationSample(
                mover = mover,
                eval = Evaluator.evaluate(state, mover, weights),
                phase = GamePhases.of(state),
                winnerFeatures = gammonFeaturesOf(state, loser = mover.opponent),
                loserFeatures = gammonFeaturesOf(state, loser = mover),
            )
        )
        turns++
    }
    val wv = Scoring.winnerAndValue(state)
    val result = if (wv == null) GameResult(Outcome.TIMEOUT, 0, turns)
        else GameResult(if (wv.first == Player.WHITE) Outcome.WHITE_WIN else Outcome.BLACK_WIN, wv.second, turns)
    return CalibrationGame(samples, result)
}
