package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Move

/** A scored full-turn play. [score] is the human-perspective search value (spec §3.5). */
data class AnalyzedPlay(val move: Move, val score: Double)
