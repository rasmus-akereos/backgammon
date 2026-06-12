package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpectimaxTest {
    private fun board(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                      toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to 0, Player.BLACK to 0), toMove)

    @Test fun `distinct rolls are 21 entries weighted to one`() {
        assertEquals(21, Expectimax.DISTINCT_ROLLS.size)
        val doubles = Expectimax.DISTINCT_ROLLS.filter { it.first.isDouble }
        val nonDoubles = Expectimax.DISTINCT_ROLLS.filter { !it.first.isDouble }
        assertEquals(6, doubles.size)
        assertEquals(15, nonDoubles.size)
        doubles.forEach { assertEquals(1.0 / 36.0, it.second, 1e-12) }
        nonDoubles.forEach { assertEquals(2.0 / 36.0, it.second, 1e-12) }
        assertEquals(1.0, Expectimax.DISTINCT_ROLLS.sumOf { it.second }, 1e-9)
    }

    @Test fun `depth-0 picks the move that maximises post-move eval`() {
        val p = IntArray(26); p[13] = 2; p[24] = 2; p[1] = -2
        val s = board(p); val dice = Dice(3, 5)
        val legal = MoveGenerator.legalMoves(s, dice)
        assertTrue(legal.isNotEmpty())
        val best = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = Int.MAX_VALUE)
        val bestScore = Evaluator.evaluate(MoveGenerator.apply(s, best), s.toMove, Weights.FULL)
        for (m in legal) {
            val score = Evaluator.evaluate(MoveGenerator.apply(s, m), s.toMove, Weights.FULL)
            assertTrue(score <= bestScore + 1e-9, "found a better move than bestMove returned")
        }
    }

    @Test fun `depth-0 prefers hitting an opponent blot`() {
        val p = IntArray(26); p[8] = 1; p[5] = -1; p[13] = 2
        val s = board(p); val dice = Dice(3, 4)
        val legal = MoveGenerator.legalMoves(s, dice)
        val best = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = Int.MAX_VALUE)
        assertTrue(best.subMoves.any { it.isHit }, "AI should choose the hitting turn")
    }

    @Test fun `tie-break is deterministic`() {
        val p = IntArray(26); p[13] = 2; p[1] = -2
        val s = board(p); val dice = Dice(2, 2)
        val legal = MoveGenerator.legalMoves(s, dice)
        val a = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = 8)
        val b = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = 8)
        assertEquals(a, b)
    }

    // ---- Task 3: terminal-node sign (the no-play-to-lose guard) ----
    private fun whiteAboutToWin(): BoardState {
        val p = IntArray(26); p[2] = 1
        return BoardState(p,
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 14, Player.BLACK to 0), Player.WHITE)
    }

    @Test fun `a winning bear-off is always chosen`() {
        val s = whiteAboutToWin(); val dice = Dice(2, 4)   // bears the last checker off -> WHITE wins
        val legal = MoveGenerator.legalMoves(s, dice)
        val best = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = 8)
        val after = MoveGenerator.apply(s, best)
        assertTrue(Scoring.isGameOver(after) && after.offCount(Player.WHITE) == 15,
            "must play the move that wins the game")
    }

    @Test fun `a near-certain win is valued near plus WIN_CONSTANT`() {
        // BLACK to move, one checker (on 23) from bearing off & winning; WHITE's 15 sit deep on point 1.
        val p = IntArray(26); p[23] = -1; p[1] = 15
        val blackAboutToWin = BoardState(p,
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 0, Player.BLACK to 14), Player.BLACK)
        val v = Expectimax.expectedValueForTest(blackAboutToWin, depth = 1, Weights.FULL)
        assertTrue(v > Expectimax.WIN_CONSTANT / 4, "near-certain win should score near +WIN_CONSTANT, was $v")
    }

    // ---- Task 4: chance weighting vs an independent 36-roll brute force ----
    private fun bruteForceDepth1(state: BoardState, weights: Weights): Double {
        fun leaf(s: BoardState): Double =
            if (Scoring.isGameOver(s)) -(Expectimax.WIN_CONSTANT * Scoring.winnerAndValue(s)!!.second)
            else Evaluator.evaluate(s, s.toMove, weights)
        var sum = 0.0
        for (d1 in 1..6) for (d2 in 1..6) {
            val legal = MoveGenerator.legalMoves(state, Dice(d1, d2))
            sum += if (legal.isEmpty()) -leaf(MoveGenerator.pass(state))
                   else legal.maxOf { m -> -leaf(MoveGenerator.apply(state, m)) }
        }
        return sum / 36.0
    }

    @Test fun `depth-1 value equals brute force over 36 ordered rolls`() {
        val p = IntArray(26); p[6] = 2; p[8] = 1; p[13] = 2; p[19] = -2; p[17] = -1; p[12] = -2
        val s = board(p, toMove = Player.BLACK)
        val expected = bruteForceDepth1(s, Weights.FULL)
        val actual = Expectimax.expectedValueForTest(s, depth = 1, Weights.FULL)
        assertEquals(expected, actual, 1e-9)
    }

    // ---- Task 5: pass recursion, pruning invariance, budget degradation, legality ----
    @Test fun `depth-0 best move is invariant to topK (static-best is never pruned)`() {
        val p = IntArray(26); p[6] = 2; p[8] = 1; p[13] = 3; p[19] = -2; p[17] = -1
        val s = board(p); val dice = Dice(6, 3)
        val legal = MoveGenerator.legalMoves(s, dice)
        val k1 = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = 1)
        val kAll = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = Int.MAX_VALUE)
        assertEquals(kAll, k1, "depth-0 pruning must never drop the static-best move")
    }

    @Test fun `tiny budget still returns a legal move and degrades to static argmax`() {
        val p = IntArray(26); p[13] = 3; p[8] = 2; p[6] = 2; p[19] = -2; p[17] = -1
        val s = board(p); val dice = Dice(6, 5)
        val legal = MoveGenerator.legalMoves(s, dice)
        val tiny = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 2, topK = 8, budget = NodeBudget(1))
        assertTrue(tiny in legal, "budget-exhausted search must return a move from legal")
        val greedy = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = 8)
        assertEquals(greedy, tiny, "exhausted Expert must degrade to greedy, not an arbitrary move")
    }

    @Test fun `bestMove always returns a move from legal across tricky positions`() {
        data class Case(val s: BoardState, val d: Dice)
        val onBar = board(IntArray(26).also { it[1] = -2; it[2] = -2; it[3] = -2 }, whiteBar = 1)
        val cases = listOf(
            Case(board(IntArray(26).also { it[13] = 2; it[1] = -2 }), Dice(6, 6)),   // doubles fan-out
            Case(onBar, Dice(4, 2)),                                                  // bar re-entry
            Case(board(IntArray(26).also { it[24] = 1; it[20] = -2; it[18] = -2 }), Dice(6, 5)),
        )
        for (depth in 0..2) for (c in cases) {
            val legal = MoveGenerator.legalMoves(c.s, c.d)
            if (legal.isEmpty()) continue
            val m = Expectimax.bestMove(c.s, c.d, legal, Weights.FULL, depth = depth, topK = 8)
            assertTrue(m in legal, "depth $depth returned a move not in legal")
        }
    }
}
