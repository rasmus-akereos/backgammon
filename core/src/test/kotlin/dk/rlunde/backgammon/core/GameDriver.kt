package dk.rlunde.backgammon.core

import kotlin.random.Random

/**
 * Plays one full game: roll -> pick a legal move (or pass) -> apply, until game over or the
 * safety cap. [choiceRandom] selects an index into the legal-move list. Calls [onState] for
 * the start state and after every committed turn. Returns the final state.
 */
fun playGame(
    start: BoardState,
    roller: DiceRoller,
    choiceRandom: Random,
    maxTurns: Int = 2000,
    onState: (BoardState) -> Unit = {},
): BoardState {
    var state = start
    var turns = 0
    onState(state)
    while (!Scoring.isGameOver(state) && turns < maxTurns) {
        val moves = MoveGenerator.legalMoves(state, roller.roll())
        state = if (moves.isEmpty()) MoveGenerator.pass(state)
        else MoveGenerator.apply(state, moves[choiceRandom.nextInt(moves.size)])
        onState(state)
        turns++
    }
    return state
}
