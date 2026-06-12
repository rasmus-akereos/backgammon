package dk.rlunde.backgammon.ai

/** Difficulty = a small data table (weights + noise + search shape). No separate code paths per tier. */
enum class Difficulty(
    internal val weights: Weights,
    internal val noise: Double,
    internal val searchDepth: Int,   // chance-node depth below the root; 0 == greedy 1-ply
    internal val topK: Int,          // candidates kept per decision node; sentinel MAX_VALUE = no pruning
) {
    BEGINNER(Weights.SIMPLIFIED, noise = 0.25, searchDepth = 0, topK = Int.MAX_VALUE),
    INTERMEDIATE(Weights.FULL, noise = 0.0, searchDepth = 0, topK = Int.MAX_VALUE),
    ADVANCED(Weights.FULL, noise = 0.0, searchDepth = 1, topK = 8),
    EXPERT(Weights.FULL_TUNED, noise = 0.0, searchDepth = 2, topK = 8),
}
