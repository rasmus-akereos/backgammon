# Phase 3 — Strong AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 1-ply greedy AI with a depth-parameterised expectimax (Advanced 2-ply, Expert 3-ply) with top-K pruning and an eval budget, prove the tiers are ordered via a self-play harness, add blocking-aware shot counting, and ship a provisional win-probability helper.

**Architecture:** All work is in the pure-Kotlin `:ai` module (no `:core`/`:app` changes). A new `Expectimax` object holds a negamax-form recursive `value()` over the 21 distinct dice rolls; `Difficulty` becomes a data table with `searchDepth`/`topK`; `Evaluator`'s blot feature becomes blocking-aware; a `WinProbability` logistic squash and a `selfPlay` test harness round it out. `MoveSearch` is deleted (its depth-0 behaviour is subsumed).

**Tech Stack:** Kotlin (JVM-17, pure module), `kotlin-test`, JUnit `@Tag` for the heavy benchmark, seeded `kotlin.random.Random` + `SeededDiceRoller` for determinism.

**Spec:** `docs/superpowers/specs/2026-06-12-phase3-strong-ai-design.md`. **Build prereq:** `./gradlew :ai:test` needs `JAVA_HOME` = Android Studio JBR + Android SDK present (per project setup).

**Key existing APIs (verified):**
- `MoveGenerator.legalMoves(state, dice): List<Move>` (empty = forced pass), `.apply(state, move): BoardState` (flips `toMove`), `.pass(state): BoardState` (flips `toMove`).
- `Scoring.isGameOver(state): Boolean`, `Scoring.winnerAndValue(state): Pair<Player, Int>?` (`.second` ∈ {1,2,3}).
- `Evaluator.evaluate(state, perspective, weights): Double` (positive = good for `perspective`; zero-sum: `evaluate(s, WHITE) == -evaluate(s, BLACK)`).
- `Player.opponent`, `Player.sign` (WHITE +1, BLACK −1).
- `BoardState.count(player, point)`, `.barCount(player)`, `.offCount(player)`; `Dice(a, b)`, `Dice.pips()`; `SeededDiceRoller(seed)`; `startingPosition()`.
- Test board helper pattern (from `MoveSearchTest`): `BoardState(points, mapOf(WHITE to wBar, BLACK to bBar), mapOf(WHITE to 0, BLACK to 0), toMove)`.

---

## File Structure

```
ai/src/main/kotlin/dk/rlunde/backgammon/ai/
├── Expectimax.kt        CREATE  negamax value(), DISTINCT_ROLLS, MAX_EVALS, WIN_CONSTANT, test seam
├── NodeBudget.kt        CREATE  internal mutable per-call eval counter
├── WinProbability.kt    CREATE  fromEquity + provisional const K
├── MoveSearch.kt        DELETE  (subsumed by Expectimax depth 0)
├── Difficulty.kt        MODIFY  + searchDepth, topK; add ADVANCED, EXPERT
├── Weights.kt           MODIFY  + FULL_TUNED
├── ShotTable.kt         (unchanged — kept for the bar-shooter path & open-path baseline)
├── Features.kt          MODIFY  blocking-aware blotPenalty (+ internal blockedHitProb)
└── HeuristicAiPlayer.kt MODIFY  route "best" path through Expectimax.bestMove
ai/src/test/kotlin/dk/rlunde/backgammon/ai/
├── NodeBudgetTest.kt    CREATE
├── ExpectimaxTest.kt    CREATE  (absorbs the 3 MoveSearchTest cases)
├── WinProbabilityTest.kt CREATE
├── FeaturesBlockingTest.kt CREATE  blocking-aware blot cases
├── SelfPlay.kt          CREATE  driver + Outcome/GameResult (test util)
├── SelfPlayTest.kt      CREATE  determinism test
├── TierOrderingTest.kt  CREATE  deterministic change-detector
├── BenchmarkTest.kt     CREATE  @Tag("benchmark") ordering proof
└── MoveSearchTest.kt    DELETE
```

---

## Task 1: `NodeBudget` — per-call eval counter

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/NodeBudget.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/NodeBudgetTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeBudgetTest {
    @Test fun `not exhausted until spend reaches max`() {
        val b = NodeBudget(10)
        assertFalse(b.exhausted)
        b.spend(9)
        assertFalse(b.exhausted)
        b.spend(1)
        assertTrue(b.exhausted)        // 10 >= 10
    }

    @Test fun `overshoot stays exhausted`() {
        val b = NodeBudget(5)
        b.spend(100)
        assertTrue(b.exhausted)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.NodeBudgetTest"`
Expected: FAIL — `NodeBudget` is unresolved (does not compile).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dk.rlunde.backgammon.ai

/**
 * A single-use, mutable eval-count budget for one [Expectimax.bestMove] call. NOT thread-safe:
 * the VM runs exactly one search at a time on Dispatchers.Default, and a fresh instance is created
 * per search, so it is never shared. Counts candidate static-evaluations — the dominant search cost.
 */
internal class NodeBudget(private val max: Int) {
    private var spent = 0
    fun spend(n: Int) { spent += n }
    val exhausted: Boolean get() = spent >= max
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.NodeBudgetTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/NodeBudget.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/NodeBudgetTest.kt
git commit -m "feat(ai): add NodeBudget eval counter for expectimax search"
```

---

## Task 2: `Expectimax` skeleton — `DISTINCT_ROLLS` + constants + depth-0 greedy

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Expectimax.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/ExpectimaxTest.kt`

- [ ] **Step 1: Write the failing test** (the 21-roll table + depth-0 == greedy; absorbs `MoveSearchTest`)

```kotlin
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.ExpectimaxTest"`
Expected: FAIL — `Expectimax` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.Scoring

/**
 * Depth-parameterised negamax-expectimax. value(state, depth) returns the eval score from the
 * perspective of state.toMove; parents negate on recursion (apply() flips the mover). depth counts
 * chance nodes below the root decision (GNU/XG "n-ply": depth 0 = 1-ply, depth 1 = 2-ply, depth 2 = 3-ply).
 * Replaces the Phase-2 MoveSearch (depth 0 == greedy 1-ply argmax).
 */
internal object Expectimax {

    /** Tunable. Far above any heuristic eval magnitude so a forced win/loss dominates. */
    internal const val WIN_CONSTANT = 1_000_000.0

    /** Tunable cap on candidate static-evaluations per bestMove call (Expert wall-clock budget). */
    internal const val MAX_EVALS = 200_000

    /** The 21 distinct rolls with probabilities: 15 non-doubles at 2/36, 6 doubles at 1/36. */
    internal val DISTINCT_ROLLS: List<Pair<Dice, Double>> = buildList {
        for (a in 1..6) for (b in a..6) {
            val w = if (a == b) 1.0 / 36.0 else 2.0 / 36.0
            add(Dice(a, b) to w)
        }
    }

    /**
     * Best full turn for [state].toMove given the known [dice]. [legal] is the canonical non-empty
     * MoveGenerator.legalMoves(state, dice). Pre: [state] is NOT game-over.
     */
    fun bestMove(
        state: BoardState, dice: Dice, legal: List<Move>,
        weights: Weights, depth: Int, topK: Int,
        budget: NodeBudget = NodeBudget(MAX_EVALS),
    ): Move {
        require(legal.isNotEmpty()) { "bestMove called with no legal moves" }
        budget.spend(legal.size)
        // The root's `legal` is the caller's known-roll set; deeper nodes regenerate their own.
        return legal.prunedTo(topK, state, weights)
            .maxByOrNull { m -> -value(MoveGenerator.apply(state, m), depth, weights, topK, budget) }!!
    }

    private fun value(
        state: BoardState, depth: Int, weights: Weights, topK: Int, budget: NodeBudget,
    ): Double {
        if (Scoring.isGameOver(state)) return -terminalEquity(state)
        if (depth == 0 || budget.exhausted) return Evaluator.evaluate(state, state.toMove, weights)
        var ev = 0.0
        for ((roll, w) in DISTINCT_ROLLS) {
            if (budget.exhausted) return Evaluator.evaluate(state, state.toMove, weights)
            val rollLegal = MoveGenerator.legalMoves(state, roll)
            val best = if (rollLegal.isEmpty()) {
                -value(MoveGenerator.pass(state), depth - 1, weights, topK, budget)
            } else {
                budget.spend(rollLegal.size)
                rollLegal.prunedTo(topK, state, weights)
                    .maxOf { m -> -value(MoveGenerator.apply(state, m), depth - 1, weights, topK, budget) }
            }
            ev += w * best
        }
        return ev
    }

    /** Winner just moved, so winnerAndValue's winner == state.toMove.opponent; we read only the 1/2/3 multiplier. */
    private fun terminalEquity(state: BoardState): Double =
        WIN_CONSTANT * Scoring.winnerAndValue(state)!!.second

    /** Rank by the cheap 1-ply static eval (from the perspective of [from].toMove) and keep the top [topK].
     *  sortedByDescending is stable, so the first-max tie-break matches the Phase-2 MoveSearch. */
    private fun List<Move>.prunedTo(topK: Int, from: BoardState, weights: Weights): List<Move> =
        sortedByDescending { Evaluator.evaluate(MoveGenerator.apply(from, it), from.toMove, weights) }
            .take(topK)

    /** Test seam: full expectimax node value with no pruning and an effectively unlimited budget. */
    internal fun expectedValueForTest(state: BoardState, depth: Int, weights: Weights): Double =
        value(state, depth, weights, Int.MAX_VALUE, NodeBudget(Int.MAX_VALUE))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.ExpectimaxTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/Expectimax.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/ExpectimaxTest.kt
git commit -m "feat(ai): add Expectimax negamax search (depth-0 == greedy)"
```

---

## Task 3: Terminal-node sign — winning move chosen, losing line valued as a loss

**Files:**
- Modify: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/ExpectimaxTest.kt`

- [ ] **Step 1: Write the failing test** (append to `ExpectimaxTest`)

```kotlin
    // WHITE has one checker left on point 2, all 14 others borne off; a 2 or higher bears it off and wins.
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

    @Test fun `a position one move from losing is valued far below zero`() {
        // BLACK to move, BLACK one checker from bearing off & winning: from WHITE's root the value is a big loss.
        val p = IntArray(26); p[23] = 1
        val blackAboutToWin = BoardState(p,
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 0, Player.BLACK to 14), Player.BLACK)
        // From BLACK's perspective this is ~ +win; value() returns BLACK-perspective, so it is strongly positive.
        val v = Expectimax.expectedValueForTest(blackAboutToWin, depth = 1, Weights.FULL)
        assertTrue(v > Expectimax.WIN_CONSTANT / 4, "near-certain win should score near +WIN_CONSTANT, was $v")
    }
```

- [ ] **Step 2: Run test to verify it fails or passes**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.ExpectimaxTest"`
Expected: PASS (the Task-2 implementation already handles terminals correctly). If either FAILS, the negamax sign is wrong — fix `terminalEquity`/the negation in `value`/`bestMove` before continuing. These tests are the regression guard for that bug class.

- [ ] **Step 3: Commit**

```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/ExpectimaxTest.kt
git commit -m "test(ai): pin expectimax terminal-node sign (no play-to-lose bug)"
```

---

## Task 4: Chance-node weighting verified against an independent 36-roll brute force

**Files:**
- Modify: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/ExpectimaxTest.kt`

- [ ] **Step 1: Write the failing test** (append; brute force enumerates all 6×6 ordered rolls independently)

```kotlin
    /** Independent depth-1 chance-node value over all 36 ORDERED rolls — must equal value(state, 1). */
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
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.ExpectimaxTest"`
Expected: PASS. The 21-distinct-weighted sum equals the 36-ordered average because `Dice(a,b)` and `Dice(b,a)` produce identical legal moves (order-independent pips), so each off-diagonal pair's weight `2/36` matches its two ordered occurrences. If FAIL, the weights or the negamax recursion are wrong.

- [ ] **Step 3: Commit**

```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/ExpectimaxTest.kt
git commit -m "test(ai): verify expectimax chance weighting vs independent 36-roll brute force"
```

---

## Task 5: Forced-pass recursion, top-K leaf-exactness, eval budget, move-in-legal

**Files:**
- Modify: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/ExpectimaxTest.kt`

- [ ] **Step 1: Write the failing tests** (append)

```kotlin
    @Test fun `top-K pruning is leaf-exact at depth 1`() {
        val p = IntArray(26); p[6] = 2; p[8] = 1; p[13] = 3; p[19] = -2; p[17] = -1
        val s = board(p); val dice = Dice(6, 3)
        val legal = MoveGenerator.legalMoves(s, dice)
        val pruned = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 1, topK = 3)
        val full = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 1, topK = Int.MAX_VALUE)
        assertEquals(full, pruned, "pruning must not change the chosen move when the key is the depth-0 value")
    }

    @Test fun `tiny budget still returns a legal move and degrades to static argmax`() {
        val p = IntArray(26); p[13] = 3; p[8] = 2; p[6] = 2; p[19] = -2; p[17] = -1
        val s = board(p); val dice = Dice(6, 5)
        val legal = MoveGenerator.legalMoves(s, dice)
        val tiny = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 2, topK = 8, budget = NodeBudget(1))
        assertTrue(tiny in legal, "budget-exhausted search must return a move from legal")
        // With the budget exhausted immediately, the root ranks by static eval and recursion returns leaves:
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
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.ExpectimaxTest"`
Expected: PASS. If `tiny budget` FAILS, ensure `bestMove` itself does NOT early-return on exhaustion (it always ranks `legal` and recurses; `value` returns the static leaf once exhausted, so the root effectively becomes a static argmax = greedy).

- [ ] **Step 3: Commit**

```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/ExpectimaxTest.kt
git commit -m "test(ai): expectimax forced-pass, pruning exactness, budget degradation, legality"
```

---

## Task 6: Wire tiers — `Weights.FULL_TUNED`, `Difficulty` fields + Advanced/Expert, route `HeuristicAiPlayer`, delete `MoveSearch`

**Files:**
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Weights.kt`
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Difficulty.kt`
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/HeuristicAiPlayer.kt`
- Delete: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveSearch.kt`
- Delete: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveSearchTest.kt`
- Modify: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/HeuristicAiPlayerTest.kt`

- [ ] **Step 1: Write the failing test** (new file `ai/src/test/kotlin/dk/rlunde/backgammon/ai/DifficultyTest.kt`)

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DifficultyTest {
    @Test fun `four tiers with expected depths`() {
        assertEquals(0, Difficulty.BEGINNER.searchDepth)
        assertEquals(0, Difficulty.INTERMEDIATE.searchDepth)
        assertEquals(1, Difficulty.ADVANCED.searchDepth)
        assertEquals(2, Difficulty.EXPERT.searchDepth)
    }

    @Test fun `intermediate is unchanged greedy 1-ply`() {
        val ai = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(1))
        val s = startingPosition(); val dice = Dice(3, 1)
        val legal = MoveGenerator.legalMoves(s, dice)
        val expected = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = Int.MAX_VALUE)
        repeat(10) { assertEquals(expected, ai.chooseMove(s, dice, legal)) }
    }

    @Test fun `advanced returns a legal move on the opening`() {
        val ai = HeuristicAiPlayer(Difficulty.ADVANCED)
        val s = startingPosition(); val dice = Dice(3, 1)
        val legal = MoveGenerator.legalMoves(s, dice)
        assertTrue(ai.chooseMove(s, dice, legal) in legal)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.DifficultyTest"`
Expected: FAIL — `searchDepth`/`ADVANCED`/`EXPERT` unresolved.

- [ ] **Step 3: Add `Weights.FULL_TUNED`** (in `Weights.kt`, inside `companion object`, after `FULL`)

```kotlin
        // Expert tier. Starts as a copy of FULL; hand re-tuning happens iteratively, validated by the
        // self-play benchmark (Task 12). Kept separate so tuning Expert never disturbs Advanced/Intermediate.
        val FULL_TUNED = FULL.copy()
```

- [ ] **Step 4: Rewrite `Difficulty.kt`**

```kotlin
package dk.rlunde.backgammon.ai

/** Difficulty = a small data table (weights + noise + search shape). */
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
```

- [ ] **Step 5: Rewrite `HeuristicAiPlayer.chooseMove`** (replace the `MoveSearch.bestMove(...)` else-branch)

```kotlin
    override fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move {
        require(legal.isNotEmpty()) { "chooseMove called with no legal moves" }
        return if (difficulty.noise > 0.0 && rng.nextDouble() < difficulty.noise)
            sampleWeak(state, dice, legal)
        else
            Expectimax.bestMove(state, dice, legal, difficulty.weights, difficulty.searchDepth, difficulty.topK)
    }
```

- [ ] **Step 6: Delete `MoveSearch.kt` and `MoveSearchTest.kt`**

```bash
git rm ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveSearch.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveSearchTest.kt
```

- [ ] **Step 7: Fix the dangling `MoveSearch` reference in `HeuristicAiPlayerTest`** (line ~14)

Replace:
```kotlin
        val best = MoveSearch.bestMove(s, dice, legal, Weights.FULL)
```
with:
```kotlin
        val best = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth = 0, topK = Int.MAX_VALUE)
```

- [ ] **Step 8: Run the full `:ai` suite**

Run: `./gradlew :ai:test`
Expected: PASS — all tiers wired, no dangling `MoveSearch`, Phase-2 behaviour preserved (Beginner/Intermediate unchanged).

- [ ] **Step 9: Commit**

```bash
git add -A ai/
git commit -m "feat(ai): add Advanced/Expert tiers; route HeuristicAiPlayer through Expectimax; drop MoveSearch"
```

---

## Task 7: `WinProbability` — provisional logistic squash

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/WinProbability.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/WinProbabilityTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WinProbabilityTest {
    @Test fun `midpoint is one half`() {
        assertEquals(0.5, WinProbability.fromEquity(0.0), 1e-12)
    }

    @Test fun `monotonic increasing in equity`() {
        assertTrue(WinProbability.fromEquity(-5.0) < WinProbability.fromEquity(0.0))
        assertTrue(WinProbability.fromEquity(0.0) < WinProbability.fromEquity(5.0))
    }

    @Test fun `symmetric about zero`() {
        for (x in listOf(0.3, 1.0, 7.5, 30.0)) {
            assertEquals(1.0, WinProbability.fromEquity(x) + WinProbability.fromEquity(-x), 1e-9)
        }
    }

    @Test fun `stays within zero and one`() {
        for (x in listOf(-1000.0, -10.0, 0.0, 10.0, 1000.0)) {
            val p = WinProbability.fromEquity(x)
            assertTrue(p in 0.0..1.0, "win-prob out of range for $x: $p")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.WinProbabilityTest"`
Expected: FAIL — `WinProbability` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.math.exp

/**
 * Logistic squash of an eval score into a single-win probability in [0,1]. Doubling-cube groundwork
 * (Phase 6 / coach Phase 5 consume it). K is PROVISIONAL and NOT self-play-calibrated yet — calibration
 * is deferred to the phase that first consumes win-prob (against a named reference weight set, with
 * per-phase buckets). Models single-win only; no gammon rates. See spec §4.7.
 */
internal object WinProbability {
    const val K: Double = 0.1
    /** [equity] is the evaluator's eval score, measured from the side whose win-prob we want. */
    fun fromEquity(equity: Double): Double = 1.0 / (1.0 + exp(-K * equity))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.WinProbabilityTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/WinProbability.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/WinProbabilityTest.kt
git commit -m "feat(ai): add provisional WinProbability logistic squash (cube groundwork)"
```

---

## Task 8: Blocking-aware shot counting

**Files:**
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Features.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/FeaturesBlockingTest.kt`

**Context:** Today `Features.blotPenalty` sums, over each of `p`'s blots, the max single-shooter hit probability from the open-path `ShotTable`, times a cost. We replace the per-on-board-shooter probability with a board-aware count over the 36 ordered rolls that **excludes combination shots whose intermediate landing point is blocked** by one of `p`'s made points (≥2). Direct shots (one die == distance) are never blocked. Doubles can hit via 1–4 equal hops (all intermediates must be open). The bar shooter keeps the open-path `ShotTable` value (entry mechanics differ; rare).

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeaturesBlockingTest {
    private fun board(points: IntArray, toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 0, Player.BLACK to 0), toMove)

    // BLACK shooter at 10 (moves low->high), WHITE blot at 17 -> distance 7 (combination only: 6+1,5+2,4+3,...).
    // Intermediate points for a 7 are 11..16. WHITE makes 13,14,15,16 (>=2) to block most combos.
    @Test fun `blocking intermediate points lowers the combination-shot blot penalty`() {
        val open = IntArray(26).also { it[17] = 1; it[10] = -1 }                 // lone WHITE blot, lone BLACK shooter
        val blocked = open.copyOf().also { it[13] = 2; it[14] = 2; it[15] = 2; it[16] = 2 } // WHITE made points
        val openPen = Features.blotPenalty(board(open), Player.WHITE, costWeighted = true)
        val blockedPen = Features.blotPenalty(board(blocked), Player.WHITE, costWeighted = true)
        assertTrue(blockedPen < openPen, "blocked combination path must reduce the hit penalty ($blockedPen vs $openPen)")
    }

    // A pure DIRECT shot (distance <=6) is never blocked by intermediates (there are none).
    @Test fun `direct shot penalty is unaffected by made points behind it`() {
        val open = IntArray(26).also { it[17] = 1; it[14] = -1 }                  // distance 3, direct
        val withPoints = open.copyOf().also { it[20] = 2 }                        // irrelevant made point
        val a = Features.blotPenalty(board(open), Player.WHITE, costWeighted = true)
        val b = Features.blotPenalty(board(withPoints), Player.WHITE, costWeighted = true)
        assertEquals(a, b, 1e-12)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.FeaturesBlockingTest"`
Expected: FAIL — the current open-path penalty ignores the made points, so `blockedPen == openPen`.

- [ ] **Step 3: Replace `Features.blotPenalty` and add `blockedHitProb`** (in `Features.kt`)

```kotlin
    fun blotPenalty(s: BoardState, p: Player, costWeighted: Boolean): Double {
        val o = p.opponent
        var penalty = 0.0
        for (i in 1..24) {
            if (s.count(p, i) != 1) continue // not a blot of p's
            var bestProb = 0.0
            for (j in 1..24) {
                if (s.count(o, j) == 0) continue
                bestProb = maxOf(bestProb, blockedHitProb(s, p, o, shooter = j, target = i))
            }
            // Bar shooter keeps the open-path approximation (entry mechanics differ).
            if (s.barCount(o) > 0) {
                val barFrom = if (o == Player.WHITE) 25 else 0
                val d = if (o == Player.WHITE) barFrom - i else i - barFrom
                if (d in 1..12) bestProb = maxOf(bestProb, ShotTable.hitProbability(d))
            }
            penalty += if (costWeighted) bestProb * hitCost(p, i) else bestProb
        }
        return penalty
    }

    /** One step of [o] toward its bear-off (WHITE high->low, BLACK low->high). */
    private fun step(o: Player, point: Int, k: Int): Int = if (o == Player.WHITE) point - k else point + k

    /** True if [o] may LAND on [point] (on board and not blocked by p's made point). */
    private fun landable(s: BoardState, p: Player, point: Int): Boolean =
        point in 1..24 && s.count(p, point) < 2

    /**
     * Fraction of the 36 ordered rolls by which shooter [o] at [shooter] hits [p]'s blot at [target],
     * EXCLUDING combination shots whose intermediate landing point is blocked. Direct shots (one die ==
     * distance) are never blocked; doubles may hit via 1..4 equal hops with all intermediates landable.
     */
    internal fun blockedHitProb(s: BoardState, p: Player, o: Player, shooter: Int, target: Int): Double {
        val d = if (o == Player.WHITE) shooter - target else target - shooter
        if (d !in 1..12) return 0.0   // open-path table's domain; doubles beyond 12 ignored (documented v1 approximation)
        var hits = 0
        for (d1 in 1..6) for (d2 in 1..6) {
            if (canHit(s, p, o, shooter, target, d, d1, d2)) hits++
        }
        return hits / 36.0
    }

    private fun canHit(
        s: BoardState, p: Player, o: Player, shooter: Int, target: Int, d: Int, d1: Int, d2: Int,
    ): Boolean {
        // Direct: a single die equals the distance (one hop, no intermediate).
        if (d <= 6 && (d1 == d || d2 == d)) return true
        if (d1 == d2) {
            // Doubles value k: reachable via n hops if d == n*k, 2..4 hops; all n-1 intermediates landable.
            val k = d1
            if (d % k != 0) return false
            val n = d / k
            if (n !in 2..4) return false
            var pt = shooter
            for (hop in 1 until n) { pt = step(o, pt, k); if (!landable(s, p, pt)) return false }
            return true
        }
        // Non-double combination: both dice, either order; the first hop must land on an open point.
        if (d1 + d2 != d) return false
        return landable(s, p, step(o, shooter, d1)) || landable(s, p, step(o, shooter, d2))
    }
```

(Delete the old `blotPenalty` body and its inline `shooters`/`ShotTable` loop; keep `hitCost` and the rest of `Features` unchanged. `ShotTable` stays for the bar-shooter path.)

- [ ] **Step 4: Run the test to verify it passes, then the whole suite**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.FeaturesBlockingTest"`
Expected: PASS.
Run: `./gradlew :ai:test`
Expected: PASS — confirm the existing `EvaluatorTest`/`FeaturesTest`/`ShotTableTest` still pass (the open-path direction is preserved; blocked positions now score lower, which is the intended directional change — update any existing assertion that hard-codes an exact open-path blot number with the blocking-aware expectation if one breaks).

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/Features.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/FeaturesBlockingTest.kt
git commit -m "feat(ai): blocking-aware shot counting in blot penalty"
```

---

## Task 9: `selfPlay` driver + `Outcome`/`GameResult`

**Files:**
- Create: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/SelfPlay.kt`
- Create: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/SelfPlayTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelfPlayTest {
    private fun intermediate(seed: Long) = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(seed))

    @Test fun `same seeds give identical results`() {
        fun run(): GameResult =
            selfPlay(intermediate(7 xor WHITE_SALT), intermediate(7 xor BLACK_SALT), SeededDiceRoller(7))
        assertEquals(run(), run())
    }

    @Test fun `a normal game finishes with a winner`() {
        val r = selfPlay(intermediate(3 xor WHITE_SALT), intermediate(3 xor BLACK_SALT), SeededDiceRoller(3))
        assertTrue(r.outcome == Outcome.WHITE_WIN || r.outcome == Outcome.BLACK_WIN)
        assertTrue(r.value in 1..3)
    }

    @Test fun `a tiny maxTurns yields TIMEOUT, not a fake winner`() {
        val r = selfPlay(intermediate(1 xor WHITE_SALT), intermediate(1 xor BLACK_SALT), SeededDiceRoller(1), maxTurns = 3)
        assertEquals(Outcome.TIMEOUT, r.outcome)
        assertEquals(0, r.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.SelfPlayTest"`
Expected: FAIL — `selfPlay`/`Outcome`/`GameResult`/`WHITE_SALT` unresolved.

- [ ] **Step 3: Write the driver**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*

/** Salts to derive each player's RNG seed from one game seed, decorrelated from the dice roller. */
internal const val WHITE_SALT = 0x5DEECE66DL
internal const val BLACK_SALT = 0x1A2B3C4D5L

enum class Outcome { WHITE_WIN, BLACK_WIN, TIMEOUT }

/** [value] = 1/2/3 win multiplier, or 0 on TIMEOUT. */
data class GameResult(val outcome: Outcome, val value: Int, val turns: Int)

/**
 * Plays one full AI-vs-AI game from the standard start. Deterministic given the [roller] and the RNGs
 * inside each AiPlayer. A game exceeding [maxTurns] returns TIMEOUT (never a fabricated winner).
 * Mirrors core's GameDriver.kt/playGame; this is a test util, not a *Test class.
 */
fun selfPlay(white: AiPlayer, black: AiPlayer, roller: DiceRoller, maxTurns: Int = 2000): GameResult {
    var state = startingPosition()
    var turns = 0
    while (!Scoring.isGameOver(state) && turns < maxTurns) {
        val dice = roller.roll()
        val legal = MoveGenerator.legalMoves(state, dice)
        state = if (legal.isEmpty()) {
            MoveGenerator.pass(state)
        } else {
            val mover = if (state.toMove == Player.WHITE) white else black
            MoveGenerator.apply(state, mover.chooseMove(state, dice, legal))
        }
        turns++
    }
    val wv = Scoring.winnerAndValue(state) ?: return GameResult(Outcome.TIMEOUT, 0, turns)
    val outcome = if (wv.first == Player.WHITE) Outcome.WHITE_WIN else Outcome.BLACK_WIN
    return GameResult(outcome, wv.second, turns)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.SelfPlayTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/SelfPlay.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/SelfPlayTest.kt
git commit -m "test(ai): add deterministic self-play driver with TIMEOUT outcome"
```

---

## Task 10: Tier-ordering change-detector (fast, per-commit)

**Files:**
- Create: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/TierOrderingTest.kt`

**Context:** Deterministic. Each pairing plays `N` games over seeds `0 until N`, alternating which colour the stronger tier plays to cancel first-move advantage; `TIMEOUT` games are excluded and asserted to be zero. This is a **change-detector** (a strength regression flips the count), not the formal proof — that is Task 11. Keep `N` small enough that 3-ply Expert finishes within a unit-test budget. Run it once to read off the actual win counts, then **pin them** as exact-equality baselines (replace the `>=` lower bounds below with the observed numbers in the commit).

- [ ] **Step 1: Write the test** (initial form uses conservative lower bounds; tighten to exact counts after first run)

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.SeededDiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierOrderingTest {
    private val N = 30   // small: Expert is 3-ply. Raise if the margin is not robust.

    private fun ai(d: Difficulty, seed: Long) = HeuristicAiPlayer(d, Random(seed))

    /** Wins for [strong] vs [weak] over N seeded games, alternating colours; asserts zero TIMEOUTs. */
    private fun strongWins(strong: Difficulty, weak: Difficulty): Int {
        var wins = 0; var timeouts = 0
        for (seed in 0L until N) {
            val strongIsWhite = (seed % 2 == 0L)
            val white = ai(if (strongIsWhite) strong else weak, seed xor WHITE_SALT)
            val black = ai(if (strongIsWhite) weak else strong, seed xor BLACK_SALT)
            val r = selfPlay(white, black, SeededDiceRoller(seed))
            when (r.outcome) {
                Outcome.TIMEOUT -> timeouts++
                Outcome.WHITE_WIN -> if (strongIsWhite) wins++
                Outcome.BLACK_WIN -> if (!strongIsWhite) wins++
            }
        }
        assertEquals(0, timeouts, "unexpected TIMEOUTs ($strong vs $weak) — raise maxTurns or check seeds")
        return wins
    }

    @Test fun `tiers are ordered`() {
        val expertVsAdvanced = strongWins(Difficulty.EXPERT, Difficulty.ADVANCED)
        val advancedVsInter = strongWins(Difficulty.ADVANCED, Difficulty.INTERMEDIATE)
        val interVsBeginner = strongWins(Difficulty.INTERMEDIATE, Difficulty.BEGINNER)
        // First run: print, then replace each assertion below with assertEquals(<observed>, ...).
        assertTrue(expertVsAdvanced >= N / 2, "Expert !> Advanced: $expertVsAdvanced/$N")
        assertTrue(advancedVsInter >= N / 2, "Advanced !> Intermediate: $advancedVsInter/$N")
        assertTrue(interVsBeginner >= (N * 6) / 10, "Intermediate !> Beginner: $interVsBeginner/$N")
    }
}
```

- [ ] **Step 2: Run it and read the actual counts**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.TierOrderingTest"`
Expected: PASS with the lower bounds. Note the printed win counts. **If any pairing is at/below 50%**, that tier is not actually stronger — STOP and investigate (likely a search/pruning/eval bug) before proceeding; do not weaken the test to make it pass.

- [ ] **Step 3: Pin the baselines**

Replace each `assertTrue(... >= ...)` with `assertEquals(<observed count>, <var>, "...")` using the numbers from Step 2, so the test becomes an exact deterministic change-detector. Keep a comment noting `N` and that raising `N` or changing the eval requires re-pinning.

- [ ] **Step 4: Re-run to confirm the exact baselines hold**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.TierOrderingTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/TierOrderingTest.kt
git commit -m "test(ai): deterministic tier-ordering change-detector"
```

---

## Task 11: Benchmark — the ordering proof (gated by `-Dbackgammon.benchmark=true`)

**Files:**
- Create: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/BenchmarkTest.kt`

The module uses JUnit5 (`useJUnitPlatform`) but this benchmark gates itself at **runtime** via the `backgammon.benchmark` system property (the test body returns early when it's absent), so a normal `./gradlew :ai:test` runs it as a trivial no-op and no `build.gradle.kts` change is needed.

- [ ] **Step 1: Write the benchmark**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.SeededDiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Heavy self-play harness (hundreds of games, independent dice per game) — the RIGOROUS proof of tier
 * ordering and a release gate. Not run per-commit: guarded by -Dbackgammon.benchmark=true.
 * Run: ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.BenchmarkTest" -Dbackgammon.benchmark=true
 */
class BenchmarkTest {
    private val enabled = System.getProperty("backgammon.benchmark") == "true"
    private val games = 200

    private fun ai(d: Difficulty, seed: Long) = HeuristicAiPlayer(d, Random(seed))

    private fun winRate(strong: Difficulty, weak: Difficulty): Double {
        var wins = 0; var played = 0
        for (g in 0 until games) {
            val seed = (g + 1).toLong() * 2_654_435_761L     // distinct, well-spread per-game seeds
            val strongIsWhite = (g % 2 == 0)
            val white = ai(if (strongIsWhite) strong else weak, seed xor WHITE_SALT)
            val black = ai(if (strongIsWhite) weak else strong, seed xor BLACK_SALT)
            when (selfPlay(white, black, SeededDiceRoller(seed)).outcome) {
                Outcome.TIMEOUT -> {}                        // excluded from the denominator
                Outcome.WHITE_WIN -> { played++; if (strongIsWhite) wins++ }
                Outcome.BLACK_WIN -> { played++; if (!strongIsWhite) wins++ }
            }
        }
        return wins.toDouble() / played
    }

    @Test fun `pairwise ordering holds by a clear margin`() {
        if (!enabled) return                                  // skip silently unless explicitly enabled
        val ea = winRate(Difficulty.EXPERT, Difficulty.ADVANCED)
        val ai_ = winRate(Difficulty.ADVANCED, Difficulty.INTERMEDIATE)
        val ib = winRate(Difficulty.INTERMEDIATE, Difficulty.BEGINNER)
        println("BENCHMARK win-rates: Expert>Adv=$ea  Adv>Inter=$ai_  Inter>Beg=$ib  (n=$games each)")
        assertTrue(ea > 0.55, "Expert should beat Advanced > 55%: $ea")
        assertTrue(ai_ > 0.55, "Advanced should beat Intermediate > 55%: $ai_")
        assertTrue(ib > 0.65, "Intermediate should beat Beginner > 65%: $ib")
    }
}
```

- [ ] **Step 2: Run the benchmark explicitly**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.BenchmarkTest" -Dbackgammon.benchmark=true`
Expected: PASS, prints the win-rate table. **Record the printed table in the commit message / PR** (release-gate evidence). If a margin is below threshold, the tiers aren't genuinely separated — investigate eval/search before tuning thresholds. (A normal per-commit `./gradlew :ai:test` skips the body — confirm it does.)

- [ ] **Step 3: Commit**

```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/BenchmarkTest.kt
git commit -m "test(ai): add @benchmark self-play ordering proof (release gate)"
```

---

## Task 12: Tune `FULL_TUNED` (Expert) and confirm blocking-aware helps

**Files:**
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Weights.kt`

**Context:** Now that the harness exists, do the hand-tuning the spec calls for, using self-play as the arbiter. This task is iterative; keep each change behind a green suite + an improved benchmark.

- [ ] **Step 1: Establish the blocking-aware baseline (decision gate from spec §4.6)**

Run the benchmark with EXPERT vs a temporary Advanced-without-blocking is out of scope to wire as a tier; instead confirm via the existing benchmark that current Advanced/Expert beat the weaker tiers by the Task-11 margins. Record the numbers. (If blocking-aware made Advanced *worse* than the pre-change baseline you noted in Task 8, revert the Task-8 change and raise Expert `topK` instead, per spec §4.6 — but the expected outcome is improvement.)

- [ ] **Step 2: Adjust `FULL_TUNED` constants**

Edit the `FULL_TUNED` values in `Weights.kt` (start from the `FULL.copy()` and nudge, e.g. raise `blot`, `fivePoint`, or `prime`), one change at a time.

- [ ] **Step 3: After each change, run the suite and the benchmark**

Run: `./gradlew :ai:test`  → Expected: PASS (the TierOrderingTest baselines may shift — re-pin them, Task 10 Step 3, if Expert's play changed).
Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.BenchmarkTest" -Dbackgammon.benchmark=true`  → keep only changes where `Expert > Advanced` win-rate does not regress.

- [ ] **Step 4: Commit the chosen weights**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/Weights.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/TierOrderingTest.kt
git commit -m "tune(ai): hand-tune Expert FULL_TUNED weights via self-play"
```

---

## Task 13: Expert performance smoke (eval-budget on a bushy doubles position)

**Files:**
- Modify: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/ExpectimaxTest.kt`

- [ ] **Step 1: Write the test** (append)

```kotlin
    @Test fun `expert search on a bushy doubles position returns quickly within the eval budget`() {
        // A heavy mid-game contact position; double 6s maximises legal-move fan-out at every node.
        val p = IntArray(26)
        p[6] = 2; p[8] = 3; p[13] = 4; p[24] = 2
        p[1] = -2; p[12] = -3; p[17] = -4; p[19] = -2
        val s = board(p); val dice = Dice(6, 6)
        val legal = MoveGenerator.legalMoves(s, dice)
        val budget = NodeBudget(Expectimax.MAX_EVALS)
        val start = System.nanoTime()
        val move = Expectimax.bestMove(s, dice, legal, Weights.FULL_TUNED,
            depth = Difficulty.EXPERT.searchDepth, topK = Difficulty.EXPERT.topK, budget = budget)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(move in legal)
        // Hard assertion = the budget held; wall-clock is a generous soft sanity bound (JVM-noisy).
        assertTrue(elapsedMs < 5_000, "Expert search took ${elapsedMs}ms — tune MAX_EVALS/topK")
    }
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.ExpectimaxTest"`
Expected: PASS. If the wall-clock bound is exceeded on the dev machine, lower `Expectimax.MAX_EVALS` (or Expert `topK`) until Expert meets the ~1 s device target (spec §4.4), then re-run Task 11 to confirm strength held.

- [ ] **Step 3: Commit**

```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/ExpectimaxTest.kt
git commit -m "test(ai): Expert eval-budget perf smoke on a bushy doubles position"
```

---

## Task 14: Final suite + spec cross-check

- [ ] **Step 1: Run the entire project test suite**

Run: `./gradlew test`
Expected: PASS — `:core`, `:ai`, and `:app` all green (Phase 3 touched only `:ai`; `:app`/`:core` tests must be unaffected).

- [ ] **Step 2: Confirm the benchmark ordering proof once more and record it**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.BenchmarkTest" -Dbackgammon.benchmark=true`
Expected: PASS; paste the printed win-rate table into the PR description (release-gate evidence per spec §8 "Done").

- [ ] **Step 3: Spec coverage check** (no code; verify each spec section maps to a task)

Confirm: §4.2 Expectimax (Tasks 2–5) · §4.3 pruning (Task 5) · §4.4 NodeBudget/MAX_EVALS (Tasks 1, 13) · §4.5 Difficulty tiers (Task 6) · §4.6 blocking-aware shots (Task 8) · §4.7 WinProbability (Task 7) · §6.1 selfPlay/TIMEOUT (Task 9) · §6.2 change-detector (Task 10) · §6.3 benchmark proof (Task 11) · §8 tests (Tasks 2–13) · MoveSearch deleted (Task 6). No `:core`/`:app` changes (correct).

- [ ] **Step 4: Commit any final cleanup, then hand off to finishing-a-development-branch**

```bash
git add -A && git commit -m "chore(ai): Phase 3 strong-AI complete (expectimax tiers + harness + win-prob)" || echo "nothing to commit"
```

---

## Notes for the implementer

- **TDD discipline:** every behavioural change has its test written and watched fail first. Steps that say "Expected: PASS" right after writing a test (Tasks 3, 4, 5) are deliberate — those assert properties the prior task's implementation already satisfies; they are regression guards. If one unexpectedly fails, you found a real bug — fix the implementation, not the test.
- **Determinism:** all self-play uses `SeededDiceRoller(seed)` + `Random(seed xor SALT)`. Never introduce an unseeded `Random` in a test.
- **Do not weaken a failing ordering/strength assertion to make it green.** A tier that doesn't out-play a weaker one signals a search or eval bug.
- **Build env:** `./gradlew :ai:test` needs `JAVA_HOME` = Android Studio JBR and the Android SDK installed; the `:ai` module is pure Kotlin but the build still runs through the Android Gradle plugin.
```
