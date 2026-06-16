package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState

/** Coarse phase bucket for per-phase equity calibration (spec §4.1). BEAROFF intentionally deferred. */
enum class GamePhase { CONTACT, RACE }

internal object GamePhases {
    fun of(state: BoardState): GamePhase =
        if (Features.noContact(state)) GamePhase.RACE else GamePhase.CONTACT
}
