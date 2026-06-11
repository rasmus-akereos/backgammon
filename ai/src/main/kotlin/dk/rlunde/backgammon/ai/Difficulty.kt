package dk.rlunde.backgammon.ai

/** Difficulty = a small data table (weights + noise). No ply field in Phase 2 (1-ply only). */
enum class Difficulty(internal val weights: Weights, internal val noise: Double) {
    BEGINNER(Weights.SIMPLIFIED, noise = 0.25),
    INTERMEDIATE(Weights.FULL, noise = 0.0),
}
