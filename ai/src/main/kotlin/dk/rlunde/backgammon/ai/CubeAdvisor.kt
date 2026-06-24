package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player

/**
 * Public facade for cube decisions (spec §5.3). Builds the cubeless [OutcomeDistribution] from the
 * static eval (6a §4.3) and applies the Janowski cubeful model. The distribution is always built
 * with the perspective of the player whose decision it is.
 */
object CubeAdvisor {
    /** Calibrated single-win probability for [perspective] (single source: the outcome distribution). */
    fun winProb(state: BoardState, perspective: Player): Double =
        dist(state, perspective).winProb

    /** Cube-offer verdict for the on-roll [perspective] given who owns the cube. */
    fun offerVerdict(state: BoardState, perspective: Player, owner: CubeOwner): OfferVerdict =
        CubeDecision.offer(equities(state, perspective, owner), owner)

    /** Offer verdict from already-computed [eq] (avoids a second distribution build). */
    fun offerVerdict(eq: CubeEquities, owner: CubeOwner): OfferVerdict =
        CubeDecision.offer(eq, owner)

    /** Take/drop verdict for the [receiver] of a double (built from the receiver's side; owner
     *  is irrelevant to a take decision, so any value works). */
    fun responseVerdict(state: BoardState, receiver: Player): ResponseVerdict =
        CubeDecision.response(equities(state, receiver, CubeOwner.ME))

    /** Full equities for the trainer hint. */
    fun equities(state: BoardState, perspective: Player, owner: CubeOwner): CubeEquities =
        CubeEquity.of(dist(state, perspective), GamePhases.of(state), owner)

    private fun dist(state: BoardState, perspective: Player) =
        EquityModel.distribution(state, perspective, Weights.FULL)
}
