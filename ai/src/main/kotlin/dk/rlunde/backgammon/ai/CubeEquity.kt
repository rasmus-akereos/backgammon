package dk.rlunde.backgammon.ai

/** Who owns the doubling cube, from the perspective of the player on roll. Crosses to :app. */
enum class CubeOwner { ME, OPPONENT, CENTERED }

/**
 * Janowski cubeful quantities for the player on roll, normalised to cube value 1 (spec §3).
 * Field names follow the OutcomeDistribution convention; §3's p/W/L/x are math aliases.
 * Public because it crosses to :app (like OutcomeDistribution); the trainer hint reads
 * winProb/cubelessEquity/takePoint/cashPoint/holdEquity, the rest are diagnostic.
 */
data class CubeEquities(
    val winProb: Double,        // p
    val meanWin: Double,        // W ∈ [1,3]
    val meanLoss: Double,       // L ∈ [1,3]
    val cubeEfficiency: Double, // x
    val cubelessEquity: Double, // E₀ = p·W − (1−p)·L  (drives the TOO_GOOD test, §4.2)
    val takePoint: Double,      // TP = (L−0.5)/S
    val cashPoint: Double,      // CP = (L+0.5+0.5x)/S
    val holdEquity: Double,     // E_center or E_own per current ownership
    val doubleTake: Double,     // 2 · E_opp
)

/** Closed-form Janowski cubeful equities from a cubeless [OutcomeDistribution] (spec §3). */
internal object CubeEquity {
    /** Cube-life index per phase. RACE > CONTACT: a race cube is more live/efficient (spec §3.2). */
    internal val CUBE_EFFICIENCY: Map<GamePhase, Double> = mapOf(
        GamePhase.CONTACT to 0.65,
        GamePhase.RACE to 0.75,
    )

    fun of(d: OutcomeDistribution, phase: GamePhase, owner: CubeOwner): CubeEquities =
        ofX(d, CUBE_EFFICIENCY.getValue(phase), owner)

    /** Test seam: explicit cube efficiency [x] (the public [of] selects it by phase). */
    internal fun ofX(d: OutcomeDistribution, x: Double, owner: CubeOwner): CubeEquities {
        require(x in 0.0..1.0) { "cube efficiency out of range: $x" }
        val p = d.winProb
        val w = (if (p < 1e-6) 1.0 else (d.winSingle + 2 * d.winGammon + 3 * d.winBackgammon) / p)
            .coerceIn(1.0, 3.0)
        val l = (if (d.loseProb < 1e-6) 1.0 else (d.loseSingle + 2 * d.loseGammon + 3 * d.loseBackgammon) / d.loseProb)
            .coerceIn(1.0, 3.0)
        val s = w + l + 0.5 * x
        val eOwn = p * s - l
        val eOpp = p * s - l - 0.5 * x
        val eCenter = (4.0 / (4.0 - x)) * (p * s - l - 0.25 * x)
        return CubeEquities(
            winProb = p, meanWin = w, meanLoss = l, cubeEfficiency = x,
            cubelessEquity = d.cubelessEquity,
            takePoint = (l - 0.5) / s,
            cashPoint = (l + 0.5 + 0.5 * x) / s,
            holdEquity = when (owner) {
                CubeOwner.CENTERED -> eCenter
                CubeOwner.ME -> eOwn
                CubeOwner.OPPONENT -> eOpp
            },
            doubleTake = 2 * eOpp,
        )
    }
}
