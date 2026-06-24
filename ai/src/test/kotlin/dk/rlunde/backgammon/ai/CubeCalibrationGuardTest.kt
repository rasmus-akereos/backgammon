package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.Scoring
import dk.rlunde.backgammon.core.SeededDiceRoller
import dk.rlunde.backgammon.core.startingPosition
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Non-gated guard: across deterministic self-play positions, the Janowski take point stays in the
 * known money-game band for both phase cube-efficiency constants, and TOO_GOOD only fires when the
 * cubeless equity actually exceeds the +1 cash value (spec §8).
 *
 * Uses the same fixed-seed pattern as [GammonCalibrationGuardTest] (60 games, same seed formula
 * and WHITE_SALT / BLACK_SALT decorrelation). States are captured inline because
 * [CalibrationSample] carries only the post-move eval, not the raw [BoardState].
 */
class CubeCalibrationGuardTest {

    private fun ref(seed: Long) = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(seed))

    /** Runs one game and returns all intermediate [BoardState]s (one per non-pass move). */
    private fun collectStates(seed: Long): List<BoardState> {
        val white = ref(seed xor WHITE_SALT)
        val black = ref(seed xor BLACK_SALT)
        val roller = SeededDiceRoller(seed)
        var state = startingPosition()
        var turns = 0
        val states = ArrayList<BoardState>()
        while (!Scoring.isGameOver(state) && turns < 2000) {
            val dice = roller.roll()
            val legal = MoveGenerator.legalMoves(state, dice)
            if (legal.isEmpty()) { state = MoveGenerator.pass(state); turns++; continue }
            val mover = if (state.toMove == Player.WHITE) white else black
            state = MoveGenerator.apply(state, mover.chooseMove(state, dice, legal))
            states.add(state)
            turns++
        }
        return states
    }

    @Test fun `take point is structurally sane and matches the gammonless band when near-gammonless`() {
        var gammonlessChecked = 0
        for (g in 0 until 60) {
            val seed = (g + 1).toLong() * 1_099_511_628_211L
            for (state in collectStates(seed)) {
                val d = EquityModel.distribution(state, Player.WHITE, Weights.FULL)
                for (phase in listOf(GamePhase.CONTACT, GamePhase.RACE)) {
                    val eq = CubeEquity.of(d, phase, CubeOwner.ME)
                    // Structural invariant: 0 < takePoint < cashPoint < 1, always.
                    assertTrue(
                        eq.takePoint > 0.0 && eq.takePoint < eq.cashPoint && eq.cashPoint < 1.0,
                        "tp/cp out of order in $phase: takePoint=${eq.takePoint} cashPoint=${eq.cashPoint}",
                    )
                    // Reference band: only applies to near-gammonless positions (meanWin ≈ meanLoss ≈ 1).
                    // Gammon skew legitimately moves the take point outside [0.18, 0.27].
                    if (eq.meanWin < 1.05 && eq.meanLoss < 1.05) {
                        assertTrue(
                            eq.takePoint in 0.18..0.27,
                            "near-gammonless take point ${eq.takePoint} out of band in $phase " +
                                "(meanWin=${eq.meanWin} meanLoss=${eq.meanLoss})",
                        )
                        gammonlessChecked++
                    }
                }
            }
        }
        assertTrue(gammonlessChecked > 0, "no near-gammonless positions sampled")
    }

    @Test fun `too good never fires unless cubeless equity exceeds one`() {
        for (g in 0 until 60) {
            val seed = (g + 1).toLong() * 1_099_511_628_211L
            for (state in collectStates(seed)) {
                val eq = CubeAdvisor.equities(state, Player.WHITE, CubeOwner.CENTERED)
                if (CubeDecision.offer(eq, CubeOwner.CENTERED) == OfferVerdict.TOO_GOOD) {
                    assertTrue(
                        eq.cubelessEquity > 1.0,
                        "TOO_GOOD fired with cubelessEquity=${eq.cubelessEquity}",
                    )
                }
            }
        }
    }
}
