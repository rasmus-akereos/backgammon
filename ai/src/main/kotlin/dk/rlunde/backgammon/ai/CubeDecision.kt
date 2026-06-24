package dk.rlunde.backgammon.ai

/** What the player on roll should do with the cube (spec §4.2). Crosses to :app. */
enum class OfferVerdict { NO_DOUBLE, DOUBLE, TOO_GOOD }

/** How a receiver should answer a double (spec §4.1). Crosses to :app. */
enum class ResponseVerdict { TAKE, DROP }

/** Turns [CubeEquities] into cube verdicts (spec §4). */
internal object CubeDecision {
    /**
     * Offer decision for the player on roll. [owner] must reflect [eq] (it packs holdEquity);
     * an OPPONENT-owned cube cannot be doubled, so it returns NO_DOUBLE.
     */
    fun offer(eq: CubeEquities, owner: CubeOwner): OfferVerdict = when {
        owner == CubeOwner.OPPONENT -> OfferVerdict.NO_DOUBLE
        // Opponent would pass: cash for +1, unless playing the gammon out is worth more.
        eq.winProb > eq.cashPoint ->
            if (eq.cubelessEquity > 1.0) OfferVerdict.TOO_GOOD else OfferVerdict.DOUBLE
        // Opponent would take: double iff being-taken beats holding the cube.
        else ->
            if (eq.doubleTake > eq.holdEquity) OfferVerdict.DOUBLE else OfferVerdict.NO_DOUBLE
    }

    /** Take iff the receiver's win prob clears their take point (owner-independent — see spec §4.1). */
    fun response(eq: CubeEquities): ResponseVerdict =
        if (eq.winProb >= eq.takePoint) ResponseVerdict.TAKE else ResponseVerdict.DROP
}
