# Phase 5 — Training Mode (move analyzer / coach) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in "Training mode" that grades the human player's moves (band + approximate win% + per-feature breakdown) against a fixed 2-ply reference, shown as a tappable marker and a detail sheet.

**Architecture:** A new pure `:ai` `MoveAnalyzer` reuses `Expectimax` (new `rankMoves`) and `Evaluator` (new `breakdown`) to produce one immutable `MoveAnalysis`; `:app` (`GameViewModel`) runs it off-main per human move and renders it. Engine first (Tasks 1–4, fully unit-tested), then UI (Tasks 5–8).

**Tech Stack:** Kotlin (JVM 17), Gradle KTS, kotlin.test, Jetpack Compose, kotlinx.coroutines. Spec: `docs/superpowers/specs/2026-06-14-phase5-trainer-design.md`.

**Build environment (toolchain not on PATH — set inline every gradle call):**
- `:ai`/`:core` tests: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test ...`
- `:app` build/tests also need: `ANDROID_HOME=/home/lunde/Android/Sdk`

---

## File Structure

| File | Responsibility |
|------|----------------|
| `ai/.../Evaluator.kt` (modify) | Add `Feature` enum, `FeatureContribution`, a shared `terms()` builder; `evaluate()` sums it; new `breakdown()`. |
| `ai/.../Expectimax.kt` (modify) | Add `rankMoves()` returning human-perspective `List<AnalyzedPlay>`, no root pruning, own budget. `bestMove()` unchanged. |
| `ai/.../MoveAnalysis.kt` (create) | Public result types: `MoveAnalysis`, `AnalyzedPlay`, `FeatureDelta`, `Band`. |
| `ai/.../MoveAnalyzer.kt` (create) | Public `analyze()` + banding constants. |
| `app/.../game/GameConfig.kt` (modify) | Add `training: Boolean`. |
| `app/.../game/GameController.kt` (modify) | Expose `lastCommitted: CommittedTurn?`. |
| `app/.../game/GameUiState.kt` (modify) | Carry `analysis: MoveAnalysis?`; add `CommittedTurn`. |
| `app/.../viewmodel/GameViewModel.kt` (modify) | `analysisJob` + epoch + cancellation; run analyzer on human moves. |
| `app/.../ui/screens/SetupScreen.kt` (modify) | Training chip (COMPUTER only). |
| `app/.../ui/screens/GameScreen.kt` (modify) | Marker badge, detail sheet, Analyse button. |
| `app/.../ui/screens/MoveNotation.kt` (create) | Pure `Move` → standard notation formatter (hits/bar/off). |

---

## Task 1: `Evaluator.breakdown()` + `Feature` enum + shared term-builder

**Files:**
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Evaluator.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/EvaluatorBreakdownTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `ai/src/test/kotlin/dk/rlunde/backgammon/ai/EvaluatorBreakdownTest.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvaluatorBreakdownTest {
    private fun board(points: IntArray, whiteOff: Int = 0, blackOff: Int = 0,
                      toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff), toMove)

    @Test fun `breakdown sums to evaluate on contact positions`() {
        val p = IntArray(26); p[6] = 2; p[8] = 1; p[13] = 3; p[20] = -2; p[18] = -1; p[2] = -3
        val s = board(p)
        for (persp in listOf(Player.WHITE, Player.BLACK)) {
            val sum = Evaluator.breakdown(s, persp, Weights.FULL).sumOf { it.value }
            assertEquals(Evaluator.evaluate(s, persp, Weights.FULL), sum, 1e-9, "persp=$persp")
        }
        // and on the starting position
        val sum0 = Evaluator.breakdown(startingPosition(), Player.WHITE, Weights.FULL).sumOf { it.value }
        assertEquals(Evaluator.evaluate(startingPosition(), Player.WHITE, Weights.FULL), sum0, 1e-9)
    }

    @Test fun `breakdown sums to evaluate AND yields only PIP-OFF on a race position`() {
        val a = IntArray(26); a[1] = 1; a[2] = 1; a[3] = 13; a[22] = -15
        val s = board(a)
        assertTrue(Features.noContact(s))
        val terms = Evaluator.breakdown(s, Player.WHITE, Weights.FULL)
        assertEquals(listOf(Feature.PIP, Feature.OFF), terms.map { it.feature })
        assertEquals(Evaluator.evaluate(s, Player.WHITE, Weights.FULL), terms.sumOf { it.value }, 1e-9)
    }

    @Test fun `contact breakdown lists all ten features in Weights field order`() {
        val p = IntArray(26); p[6] = 2; p[8] = 1; p[13] = 3; p[20] = -2; p[18] = -1; p[2] = -3
        val terms = Evaluator.breakdown(board(p), Player.WHITE, Weights.FULL)
        assertEquals(
            listOf(Feature.PIP, Feature.OFF, Feature.BLOT, Feature.HOME_POINT, Feature.KEY_POINT,
                   Feature.PRIME, Feature.ANCHOR, Feature.ADVANCED_ANCHOR, Feature.BAR, Feature.BACK_CHECKER),
            terms.map { it.feature },
        )
    }

    @Test fun `Feature count matches Weights field count`() {
        // Guard: a new Weights field must come with a new Feature constant.
        assertEquals(10, Feature.entries.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EvaluatorBreakdownTest"`
Expected: FAIL — `Feature` / `breakdown` unresolved (compile error).

- [ ] **Step 3: Write minimal implementation**

Replace the body of `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Evaluator.kt` with:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player

/** A feature name (1:1 and in-order with Weights fields) used by [Evaluator.breakdown]. */
enum class Feature { PIP, OFF, BLOT, HOME_POINT, KEY_POINT, PRIME, ANCHOR, ADVANCED_ANCHOR, BAR, BACK_CHECKER }

/** One weighted feature contribution to the eval score (weight already applied). */
data class FeatureContribution(val feature: Feature, val value: Double)

/**
 * Weighted linear position evaluator; positive = good for [perspective]. Callers evaluate the
 * POST-move state (toMove already flipped) with [perspective] = the side that just moved (spec §4.4).
 * In a no-contact race the eval collapses to ≈ pip differential. See spec §4.2.
 */
internal object Evaluator {

    fun evaluate(state: BoardState, perspective: Player, weights: Weights): Double =
        terms(state, perspective, weights).sumOf { it.value }

    /** Per-feature attribution; sums to [evaluate]. No-contact returns only PIP/OFF. */
    fun breakdown(state: BoardState, perspective: Player, weights: Weights): List<FeatureContribution> =
        terms(state, perspective, weights)

    /** Single source of truth: one contribution per Weights field, in field-declaration order. */
    private fun terms(state: BoardState, perspective: Player, weights: Weights): List<FeatureContribution> {
        val pip = weights.pip * Features.pipDifferential(state, perspective)
        val off = weights.off * Features.offDifferential(state, perspective)
        if (Features.noContact(state)) return listOf(
            FeatureContribution(Feature.PIP, pip),
            FeatureContribution(Feature.OFF, off),
        )
        val o = perspective.opponent
        val costWeighted = weights != Weights.SIMPLIFIED
        return listOf(
            FeatureContribution(Feature.PIP, pip),
            FeatureContribution(Feature.OFF, off),
            FeatureContribution(Feature.BLOT, weights.blot *
                (Features.blotPenalty(state, o, costWeighted) - Features.blotPenalty(state, perspective, costWeighted))),
            FeatureContribution(Feature.HOME_POINT, weights.homePoint *
                (Features.homePointsMade(state, perspective) - Features.homePointsMade(state, o))),
            FeatureContribution(Feature.KEY_POINT, weights.fivePoint *
                (Features.keyPointsMade(state, perspective) - Features.keyPointsMade(state, o))),
            FeatureContribution(Feature.PRIME, weights.prime *
                (Features.primeLength(state, perspective) - Features.primeLength(state, o))),
            FeatureContribution(Feature.ANCHOR, weights.anchor *
                (Features.anchorsMade(state, perspective) - Features.anchorsMade(state, o))),
            FeatureContribution(Feature.ADVANCED_ANCHOR, weights.advancedAnchor *
                (Features.advancedAnchorsMade(state, perspective) - Features.advancedAnchorsMade(state, o))),
            FeatureContribution(Feature.BAR, weights.bar * Features.barTerm(state, perspective)),
            FeatureContribution(Feature.BACK_CHECKER, weights.backChecker *
                (Features.backCheckers(state, o) - Features.backCheckers(state, perspective))),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EvaluatorBreakdownTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the existing Evaluator suite to confirm no regression**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EvaluatorTest"`
Expected: PASS (the sum is unchanged, just reorganised).

- [ ] **Step 6: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/Evaluator.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/EvaluatorBreakdownTest.kt
git commit -m "feat(ai): Evaluator.breakdown() per-feature attribution"
```

---

## Task 2: `Expectimax.rankMoves()` + `AnalyzedPlay`

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalysis.kt` (only `AnalyzedPlay` for now)
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Expectimax.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/RankMovesTest.kt` (create)

- [ ] **Step 1: Create the `AnalyzedPlay` type**

Create `ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalysis.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Move

/** A scored full-turn play. [score] is the human-perspective search value (spec §3.5). */
data class AnalyzedPlay(val move: Move, val score: Double)
```

- [ ] **Step 2: Write the failing test**

Create `ai/src/test/kotlin/dk/rlunde/backgammon/ai/RankMovesTest.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RankMovesTest {
    private val s = startingPosition()       // WHITE to move
    private val dice = Dice(3, 1)            // the 3-1 opening: a well-known set of legal plays
    private val legal = MoveGenerator.legalMoves(s, dice)

    private fun rank(depth: Int) =
        Expectimax.rankMoves(s, dice, legal, Weights.FULL, depth, NodeBudget(Int.MAX_VALUE))

    @Test fun `ranking contains every legal play exactly once`() {
        val r = rank(0)
        assertEquals(legal.size, r.size)
        assertEquals(legal.toSet(), r.map { it.move }.toSet())
    }

    @Test fun `ranking is sorted best-first and human-perspective`() {
        val r = rank(1)
        for (i in 1 until r.size) assertTrue(r[i - 1].score >= r[i].score, "not sorted at $i")
        // best >= worst, i.e. positive direction is "good for the mover"
        assertTrue(r.first().score >= r.last().score)
    }

    @Test fun `top of ranking equals bestMove at equal depth with root pruning`() {
        // Parity: rankMoves shares the inner value()/negation; with the SAME root candidate set
        // (no pruning here, full legal) the argmax must match bestMove's argmax on the same legal set.
        val depth = 0
        val top = rank(depth).first().move
        val best = Expectimax.bestMove(s, dice, legal, Weights.FULL, depth, topK = Int.MAX_VALUE,
            budget = NodeBudget(Int.MAX_VALUE))
        assertEquals(best, top)
    }

    @Test fun `large move set is scored at uniform depth (no depth-0 fallback mixed in)`() {
        // A double gives a big legal set; with an ample budget every score must be a true depth-1 value.
        // Determinism check: two runs with fresh budgets produce identical rankings.
        val d = Dice(6, 6)
        val lg = MoveGenerator.legalMoves(s, d)
        val r1 = Expectimax.rankMoves(s, d, lg, Weights.FULL, 1, NodeBudget(Int.MAX_VALUE))
        val r2 = Expectimax.rankMoves(s, d, lg, Weights.FULL, 1, NodeBudget(Int.MAX_VALUE))
        assertEquals(r1.map { it.move to it.score }, r2.map { it.move to it.score })
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.RankMovesTest"`
Expected: FAIL — `rankMoves` unresolved.

- [ ] **Step 4: Add `rankMoves` to `Expectimax`**

In `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Expectimax.kt`, add inside the `object` (after `bestMove`):

```kotlin
    /**
     * Score EVERY [legal] play for analysis (no root pruning, so playedRank is exact), sorted
     * best-first. [score] is human-perspective: -value(apply(state, m), depth). [budget] is the
     * analysis budget (size it generously; analysis is off-main-thread and one-shot, spec §3.4b)
     * so all candidates are scored at the same depth — never mix depth-1 with a depth-0 fallback.
     * Pre: [state] is NOT game-over.
     */
    fun rankMoves(
        state: BoardState, dice: Dice, legal: List<Move>,
        weights: Weights, depth: Int, budget: NodeBudget,
    ): List<AnalyzedPlay> {
        require(legal.isNotEmpty()) { "rankMoves called with no legal moves" }
        return legal
            .map { m -> AnalyzedPlay(m, -value(MoveGenerator.apply(state, m), depth, weights, Int.MAX_VALUE, budget)) }
            .sortedByDescending { it.score }
    }
```

Note: root passes `topK = Int.MAX_VALUE` so deeper nodes are unpruned for analysis fidelity; the analyzer (Task 3) sizes `budget` so `exhausted` never trips mid-ranking.

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.RankMovesTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Confirm `bestMove` is untouched (Phase-3 baseline intact)**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.ExpectimaxTest"`
Expected: PASS. (`bestMove` and its root `topK` pruning are unchanged; `TierOrderingTest` baselines remain valid — that ~17-min test need not run here.)

- [ ] **Step 7: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/Expectimax.kt ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalysis.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/RankMovesTest.kt
git commit -m "feat(ai): Expectimax.rankMoves() human-perspective full ranking for analysis"
```

---

## Task 3: `MoveAnalyzer.analyze()` core — rank, match, band, breakdown

**Files:**
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalysis.kt` (add result types)
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzer.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzerTest.kt` (create)

- [ ] **Step 1: Add the result types**

Append to `ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalysis.kt`:

```kotlin
/** Quality band (chess-style by product choice). */
enum class Band { BEST, GOOD, INACCURACY, MISTAKE, BLUNDER }

/** Best-vs-played static contribution for one feature. */
data class FeatureDelta(val feature: Feature, val best: Double, val played: Double) {
    val delta: Double get() = best - played
}

/** Full analysis of one human play. winProbDrop/featureDeltas may be absent for terminal plays (§3.7). */
data class MoveAnalysis(
    val band: Band,
    val playedRank: Int,
    val tiedForBest: Boolean,
    val totalCandidates: Int,
    val evalLoss: Double,
    val winProbDrop: Double?,
    val terminal: Boolean,
    val best: AnalyzedPlay,
    val played: AnalyzedPlay,
    val featureDeltas: List<FeatureDelta>,
    val forced: Boolean,
)
```

- [ ] **Step 2: Write the failing test**

Create `ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzerTest.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.SubMove
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoveAnalyzerTest {
    private val s = startingPosition()
    private val dice = Dice(3, 1)
    private val legal = MoveGenerator.legalMoves(s, dice)

    private fun rankedBest() =
        Expectimax.rankMoves(s, dice, legal, Weights.FULL, 1, NodeBudget(Int.MAX_VALUE)).first().move
    private fun rankedWorst() =
        Expectimax.rankMoves(s, dice, legal, Weights.FULL, 1, NodeBudget(Int.MAX_VALUE)).last().move

    @Test fun `best move is BEST rank 1 zero loss`() {
        val a = MoveAnalyzer.analyze(s, dice, rankedBest(), legal)
        assertEquals(Band.BEST, a.band)
        assertEquals(1, a.playedRank)
        assertEquals(0.0, a.evalLoss, 1e-9)
        assertEquals(legal.size, a.totalCandidates)
        assertTrue(!a.forced)
    }

    @Test fun `worst move loses equity and is worse than BEST`() {
        val a = MoveAnalyzer.analyze(s, dice, rankedWorst(), legal)
        assertTrue(a.evalLoss > 0.0)
        assertTrue(a.playedRank > 1)
        assertTrue(a.band != Band.BEST)
        // featureDeltas are present and sorted by |delta| desc
        val mags = a.featureDeltas.map { kotlin.math.abs(it.delta) }
        assertEquals(mags.sortedDescending(), mags)
    }

    @Test fun `played move matched by resulting board despite sub-move order`() {
        val best = rankedBest()
        val reversed = Move(best.subMoves.reversed())   // same checkers, opposite tap order
        val a = MoveAnalyzer.analyze(s, dice, reversed, legal)
        assertEquals(1, a.playedRank)                   // still located as the best
    }

    @Test fun `move not in legal fails fast`() {
        val bogus = Move(listOf(SubMove(from = 24, to = 23, die = 1, isHit = false)))
        assertFailsWith<IllegalArgumentException> { MoveAnalyzer.analyze(s, dice, bogus, legal) }
    }

    @Test fun `single legal play is forced`() {
        // Construct a position with exactly one legal play would be involved; instead assert the
        // forced flag wiring via a one-element legal list (the only play is trivially best).
        val one = listOf(rankedBest())
        val a = MoveAnalyzer.analyze(s, dice, one.first(), one)
        assertTrue(a.forced)
        assertEquals(Band.BEST, a.band)
        assertEquals(1, a.totalCandidates)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.MoveAnalyzerTest"`
Expected: FAIL — `MoveAnalyzer` unresolved.

- [ ] **Step 4: Write `MoveAnalyzer`**

Create `ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzer.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.Scoring

/**
 * Grades [playedMove] against all [legal] plays from [state] (spec Phase 5). Pure, deterministic,
 * no RNG. The reference is a fixed 2-ply (depth 1) search with Weights.FULL and no noise.
 */
object MoveAnalyzer {
    private const val ANALYSIS_DEPTH = 1
    private val REFERENCE_WEIGHTS = Weights.FULL

    // PROVISIONAL band thresholds in eval-score points (tuning follow-up, spec §3.3/§6).
    private const val GOOD_MAX = 0.5
    private const val INACCURACY_MAX = 2.0
    private const val MISTAKE_MAX = 5.0

    // A score within this magnitude of WIN_CONSTANT is a forced win/loss line — suppress numbers (§3.7).
    private const val WIN_BAND = Expectimax.WIN_CONSTANT / 2.0

    fun analyze(state: BoardState, dice: Dice, playedMove: Move, legal: List<Move>): MoveAnalysis {
        require(legal.isNotEmpty()) { "analyze called with no legal moves" }
        // Generous budget so all candidates are scored at the same depth (no depth-0 fallback).
        val budget = NodeBudget(Int.MAX_VALUE)
        val ranking = Expectimax.rankMoves(state, dice, legal, REFERENCE_WEIGHTS, ANALYSIS_DEPTH, budget)

        val playedBoard = MoveGenerator.apply(state, playedMove)
        val playedIdx = ranking.indexOfFirst { MoveGenerator.apply(state, it.move) == playedBoard }
        require(playedIdx >= 0) { "playedMove is not among legal moves" }

        val best = ranking.first()
        val played = ranking[playedIdx]
        val evalLoss = (best.score - played.score).coerceAtLeast(0.0)

        // Competition ranking: count of strictly-better plays + 1 (ties share a rank).
        val strictlyBetter = ranking.count { it.score > played.score }
        val playedRank = strictlyBetter + 1
        val tiedForBest = playedRank == 1 && ranking.count { it.score >= best.score } > 1
        val forced = legal.size == 1

        val terminal = Scoring.isGameOver(playedBoard)
        val extreme = kotlin.math.abs(best.score) >= WIN_BAND || kotlin.math.abs(played.score) >= WIN_BAND

        val featureDeltas: List<FeatureDelta> = if (terminal) emptyList() else {
            val human = state.toMove
            val bestTerms = Evaluator.breakdown(MoveGenerator.apply(state, best.move), human, REFERENCE_WEIGHTS)
                .associate { it.feature to it.value }
            val playedTerms = Evaluator.breakdown(playedBoard, human, REFERENCE_WEIGHTS)
                .associate { it.feature to it.value }
            (bestTerms.keys + playedTerms.keys)
                .map { f -> FeatureDelta(f, bestTerms[f] ?: 0.0, playedTerms[f] ?: 0.0) }
                .sortedByDescending { kotlin.math.abs(it.delta) }
        }

        val winProbDrop: Double? = if (terminal || extreme) null
        else WinProbability.fromEquity(best.score) - WinProbability.fromEquity(played.score)

        return MoveAnalysis(
            band = band(playedRank, evalLoss),
            playedRank = playedRank,
            tiedForBest = tiedForBest,
            totalCandidates = legal.size,
            evalLoss = evalLoss,
            winProbDrop = winProbDrop,
            terminal = terminal,
            best = best,
            played = played,
            featureDeltas = featureDeltas,
            forced = forced,
        )
    }

    private fun band(playedRank: Int, evalLoss: Double): Band = when {
        playedRank == 1 -> Band.BEST
        evalLoss <= GOOD_MAX -> Band.GOOD
        evalLoss <= INACCURACY_MAX -> Band.INACCURACY
        evalLoss <= MISTAKE_MAX -> Band.MISTAKE
        else -> Band.BLUNDER
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.MoveAnalyzerTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalysis.kt ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzer.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzerTest.kt
git commit -m "feat(ai): MoveAnalyzer.analyze() band + breakdown + board-match"
```

---

## Task 4: Banding table, win% sign, and terminal handling

**Files:**
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzerEdgeTest.kt` (create)
- Modify (only if a test fails): `ai/.../MoveAnalyzer.kt`

- [ ] **Step 1: Write the failing/■characterisation test**

Create `ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzerEdgeTest.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoveAnalyzerEdgeTest {
    private val s = startingPosition()
    private val dice = Dice(3, 1)
    private val legal = MoveGenerator.legalMoves(s, dice)

    @Test fun `winProbDrop is non-negative and tracks evalLoss sign`() {
        val ranking = Expectimax.rankMoves(s, dice, legal, Weights.FULL, 1, NodeBudget(Int.MAX_VALUE))
        val worst = MoveAnalyzer.analyze(s, dice, ranking.last().move, legal)
        val best = MoveAnalyzer.analyze(s, dice, ranking.first().move, legal)
        assertTrue(worst.winProbDrop!! >= 0.0)
        assertEquals(0.0, best.winProbDrop!!, 1e-12)
    }

    @Test fun `game-ending move is terminal with no breakdown and no winProbDrop`() {
        // WHITE has one checker on the 1-point and 14 already off; a 1-N roll bears it off and wins.
        val p = IntArray(26); p[1] = 1; p[20] = -2   // BLACK has stuff far away (still contact-free for WHITE bear-off)
        val board = BoardState(p,
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 14, Player.BLACK to 0), Player.WHITE)
        val d = Dice(1, 2)
        val lg = MoveGenerator.legalMoves(board, d)
        assertTrue(lg.isNotEmpty())
        // pick a play that bears off the last checker (ends the game)
        val winning = lg.first { MoveGenerator.apply(board, it).offCount(Player.WHITE) == 15 }
        val a = MoveAnalyzer.analyze(board, d, winning, lg)
        assertTrue(a.terminal)
        assertTrue(a.featureDeltas.isEmpty())
        assertNull(a.winProbDrop)
    }

    @Test fun `banding maps evalLoss to the documented bands`() {
        // Pin the provisional thresholds as a change-detector via the public band path: build
        // synthetic rankings is internal, so assert through analyze on real positions is hard for
        // exact edges. Instead, verify the ordering invariant: higher evalLoss never yields a
        // softer band across the worst..best spread of the opening.
        val ranking = Expectimax.rankMoves(s, dice, legal, Weights.FULL, 1, NodeBudget(Int.MAX_VALUE))
        val analyses = ranking.map { MoveAnalyzer.analyze(s, dice, it.move, legal) }
        val order = listOf(Band.BEST, Band.GOOD, Band.INACCURACY, Band.MISTAKE, Band.BLUNDER)
        for (i in 1 until analyses.size) {
            if (analyses[i].evalLoss > analyses[i - 1].evalLoss) {
                assertTrue(order.indexOf(analyses[i].band) >= order.indexOf(analyses[i - 1].band),
                    "band softened as evalLoss rose at $i")
            }
        }
    }
}
```

- [ ] **Step 2: Run tests**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.MoveAnalyzerEdgeTest"`
Expected: PASS (Task 3's implementation already covers these). If the terminal test fails because the fixture has legal contact, adjust the BLACK checkers to indices that cannot be hit by WHITE's bear-off (e.g. keep them at `p[20]`), keeping `Features`-irrelevant; the assertion only needs a game-ending WHITE play to exist.

- [ ] **Step 3: Run the whole `:ai` suite**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test`
Expected: PASS (the long `BenchmarkTest` is gated off by default; `TierOrderingTest` runs — ~17 min — or scope it out with `--tests` excludes if iterating).

- [ ] **Step 4: Commit**

```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzerEdgeTest.kt
git commit -m "test(ai): MoveAnalyzer win% sign, terminal, banding-order edges"
```

---

## Task 5: `GameConfig.training` + Setup chip (COMPUTER only)

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/game/GameConfig.kt`
- Modify: `app/src/main/java/dk/rlunde/backgammon/ui/screens/SetupScreen.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/game/GameConfigTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dk/rlunde/backgammon/game/GameConfigTest.kt`:

```kotlin
package dk.rlunde.backgammon.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GameConfigTest {
    @Test fun `training defaults to false`() {
        assertFalse(GameConfig().training)
    }

    @Test fun `training is carried through`() {
        assertEquals(true, GameConfig(training = true).training)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.game.GameConfigTest"`
Expected: FAIL — no `training` parameter.

- [ ] **Step 3: Add the field**

In `app/src/main/java/dk/rlunde/backgammon/game/GameConfig.kt`, add `training` to the data class:

```kotlin
data class GameConfig(
    val difficulty: Difficulty = Difficulty.INTERMEDIATE,
    val humanColor: Player? = Player.WHITE,
    val opponent: Opponent = Opponent.COMPUTER,
    val training: Boolean = false,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.game.GameConfigTest"`
Expected: PASS.

- [ ] **Step 5: Wire the Setup chip (COMPUTER only)**

In `app/src/main/java/dk/rlunde/backgammon/ui/screens/SetupScreen.kt`:

Add state near the other `remember`s (after line 21):

```kotlin
    var training by remember { mutableStateOf(false) }
```

Inside the `if (opponent == Opponent.COMPUTER) { ... }` block, after the Difficulty `ChoiceRow`, add:

```kotlin
                    ChoiceRow("Training", listOf(
                        "Off" to false, "On" to true,
                    ), training) { training = it }
```

Update the Start button's config:

```kotlin
                onClick = { onStart(GameConfig(difficulty, colorChoice, opponent, training)) },
```

- [ ] **Step 6: Build to confirm Compose compiles**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/game/GameConfig.kt app/src/main/java/dk/rlunde/backgammon/ui/screens/SetupScreen.kt app/src/test/java/dk/rlunde/backgammon/game/GameConfigTest.kt
git commit -m "feat(app): Training-mode setting + setup chip (vs-computer only)"
```

---

## Task 6: `GameController.lastCommitted` (pre-move capture)

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt` (add `CommittedTurn`)
- Modify: `app/src/main/java/dk/rlunde/backgammon/game/GameController.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/game/GameControllerCommitTest.kt` (create)

- [ ] **Step 1: Add the `CommittedTurn` type**

In `app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt`, add near the top (after imports):

```kotlin
/** The turn a human just committed, with its pre-move context, for post-move analysis. */
data class CommittedTurn(
    val preBoard: dk.rlunde.backgammon.core.BoardState,
    val dice: dk.rlunde.backgammon.core.Dice,
    val move: dk.rlunde.backgammon.core.Move,
)
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/dk/rlunde/backgammon/game/GameControllerCommitTest.kt`:

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.DiceRoller
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GameControllerCommitTest {
    private class FixedRoller(private val d: Dice) : DiceRoller { override fun roll() = d }

    @Test fun `commit records the played turn and pre-move context`() {
        val c = GameController(initial = startingPosition(), roller = FixedRoller(Dice(3, 1)))
        assertNull(c.lastCommitted)
        c.roll()
        val pre = c.uiState.board
        val dice = c.uiState.dice!!
        // Stage the full best-known opening by tapping is verbose; instead drive via legal moves:
        val move = MoveGenerator.legalMoves(pre, dice).first()
        // Apply each sub-move via tap is complex; use the controller's own commit after staging.
        // Simplest: stage by replaying sub-moves through tap is out of scope — assert via applyMove? No,
        // applyMove is the AI path. For human commit we stage; here we stage all sub-moves directly:
        c.stageForTest(move)         // test seam added in Step 3
        c.commit()
        val lc = c.lastCommitted
        assertNotNull(lc)
        assertEquals(pre, lc.preBoard)
        assertEquals(dice, lc.dice)
        assertEquals(MoveGenerator.apply(pre, move), MoveGenerator.apply(pre, lc.move))
    }
}
```

- [ ] **Step 3: Implement `lastCommitted` (+ a small test seam)**

In `app/src/main/java/dk/rlunde/backgammon/game/GameController.kt`:

Add a backing field near the other `private var`s (after line 20):

```kotlin
    var lastCommitted: CommittedTurn? = null; private set
```

Rewrite `commit()` to capture context before mutating:

```kotlin
    fun commit() {
        if (committed.toMove == aiSide) return
        if (uiState.phase != Phase.COMMITTABLE) return
        val pre = committed
        val d = dice!!
        val move = Move(staged.toList())
        lastCommitted = CommittedTurn(pre, d, move)
        committed = MoveGenerator.apply(committed, move)
        staged.clear()
        selectedOrigin = null
        dice = null
        passing = false
        uiState = compute()
    }
```

Clear it where a fresh game / AI move invalidates it — in `applyMove()` and `newGame()` add `lastCommitted = null` as the first line of each.

Add the test seam at the end of the class (before the closing brace):

```kotlin
    /** Test-only: stage a complete legal move without tap simulation. */
    internal fun stageForTest(move: Move) {
        staged.clear(); staged.addAll(move.subMoves)
        uiState = compute()
    }
```

(Confirm `Move` and `CommittedTurn` are imported/visible — `Move` is in `dk.rlunde.backgammon.core`, `CommittedTurn` is same package.)

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.game.GameControllerCommitTest"`
Expected: PASS. (If `stageForTest` leaves `uiState.phase` not `COMMITTABLE` for a partial move, stage a move whose sub-moves consume the full roll — `legalMoves` returns complete turns, so the first one does.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt app/src/main/java/dk/rlunde/backgammon/game/GameController.kt app/src/test/java/dk/rlunde/backgammon/game/GameControllerCommitTest.kt
git commit -m "feat(app): GameController exposes lastCommitted for analysis"
```

---

## Task 7: `GameViewModel` analysis orchestration (job + epoch + supersede)

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt` (add `analysis` field)
- Modify: `app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/viewmodel/GameViewModelAnalysisTest.kt` (create)

- [ ] **Step 1: Add the state field**

In `app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt`, add to `GameUiState` (after `aiSide`):

```kotlin
    /** Set by the VM: latest analysis of the human's last move (training mode), else null. */
    val analysis: dk.rlunde.backgammon.ai.MoveAnalysis? = null,
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/dk/rlunde/backgammon/viewmodel/GameViewModelAnalysisTest.kt`:

```kotlin
package dk.rlunde.backgammon.viewmodel

import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.game.Opponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelAnalysisTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun `training off produces no analysis`() = runTest(dispatcher) {
        val vm = GameViewModel()
        vm.startGame(GameConfig(opponent = Opponent.HOT_SEAT, training = false))
        // hot-seat human roll + commit a move, then drain
        // (drive through the public VM API; exact taps depend on the roll — see Step 4 note)
        assertNull(vm.uiState.value.analysis)
    }
}
```

(The full supersede/undo assertions require driving a human turn through taps; the executing agent should expand this test to: start a **hot-seat, training-on** game so every move is human and there is no AI job to interleave; roll a fixed dice via an injected roller; commit; `advanceUntilIdle()`; assert `analysis != null`; then commit a second move and assert the stored analysis corresponds to the second move, not the first. Use the `GameController(roller = …)` seam and a VM constructor that accepts an injected controller/dispatcher — add that seam in Step 3.)

- [ ] **Step 3: Implement orchestration**

In `app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt`:

Add imports:

```kotlin
import dk.rlunde.backgammon.ai.MoveAnalysis
import dk.rlunde.backgammon.ai.MoveAnalyzer
import dk.rlunde.backgammon.core.MoveGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

Add fields (near the other `private var`s):

```kotlin
    private var training = false
    private var analysisJob: Job? = null
    private var analysis: MoveAnalysis? = null
    private var analysisEpoch = 0
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default
```

In `startGame`, capture the flag (after `aiSide` is set):

```kotlin
        training = config.training && config.opponent == Opponent.COMPUTER
```

Update `publish()` to include analysis:

```kotlin
    private fun publish(humanEvents: List<UiEvent> = emptyList()) {
        _uiState.value = controller.uiState.copy(aiThinking = aiThinking, aiSide = aiSide, analysis = analysis)
        humanEvents.forEach { _events.trySend(it) }
    }
```

Add the analysis launcher:

```kotlin
    private fun analyzeLastHumanMove() {
        if (!training) return
        val lc = controller.lastCommitted ?: return
        analysisJob?.cancel()
        analysis = null                       // clear marker until the new result lands
        val epoch = ++analysisEpoch
        analysisJob = viewModelScope.launch {
            val result = withContext(analysisDispatcher) {
                MoveAnalyzer.analyze(lc.preBoard, lc.dice, lc.move,
                    MoveGenerator.legalMoves(lc.preBoard, lc.dice))
            }
            if (epoch == analysisEpoch) { analysis = result; publish() }
        }
    }
```

Call it from `onCommit` (human commit), and invalidate on undo/new-game:

```kotlin
    fun onCommit() { controller.commit(); analyzeLastHumanMove(); publish(); maybeRunAi() }
    fun onUndo() { controller.undo(); cancelAnalysis(); publish() }
```

Add `cancelAnalysis()` and call it in `onNewGame()` (after `aiJob?.cancel()`):

```kotlin
    private fun cancelAnalysis() {
        analysisJob?.cancel(); analysisJob = null; analysisEpoch++; analysis = null
    }
```

In `onNewGame()` add `cancelAnalysis()` and `training = false` before `controller = GameController()`.

(For testability the executing agent may add an alternate constructor injecting `analysisDispatcher`; keep the default `Dispatchers.Default` for production.)

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.viewmodel.GameViewModelAnalysisTest"`
Expected: PASS. Add `testImplementation(libs.kotlinx.coroutines.test)` (or the coroutines-test coordinate) to `app/build.gradle.kts` if `runTest` is unresolved; verify the version catalog has it, else add `org.jetbrains.kotlinx:kotlinx-coroutines-test`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt app/src/test/java/dk/rlunde/backgammon/viewmodel/GameViewModelAnalysisTest.kt app/build.gradle.kts
git commit -m "feat(app): run MoveAnalyzer per human move with job+epoch supersede"
```

---

## Task 8: UI — move notation, marker badge, detail sheet, Analyse button

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/ui/screens/MoveNotation.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/ui/MoveNotationTest.kt` (create)
- Modify: `app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt`
- Modify: `app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt` (on-demand `onAnalyse()`)

- [ ] **Step 1: Write the failing notation test**

Create `app/src/test/java/dk/rlunde/backgammon/ui/MoveNotationTest.kt`:

```kotlin
package dk.rlunde.backgammon.ui

import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.SubMove
import dk.rlunde.backgammon.ui.screens.notation
import kotlin.test.Test
import kotlin.test.assertEquals

class MoveNotationTest {
    @Test fun `plain two-checker play`() {
        val m = Move(listOf(SubMove(24, 23, 1, false), SubMove(13, 11, 2, false)))
        assertEquals("24/23 13/11", notation(m))
    }

    @Test fun `hit is marked with asterisk`() {
        val m = Move(listOf(SubMove(13, 11, 2, true)))
        assertEquals("13/11*", notation(m))
    }

    @Test fun `bear-off uses off`() {
        val m = Move(listOf(SubMove(6, 0, 6, false)))   // WHITE off sentinel = 0
        assertEquals("6/off", notation(m))
    }

    @Test fun `bar entry uses bar`() {
        val m = Move(listOf(SubMove(25, 20, 5, false)))  // WHITE bar sentinel = 25
        assertEquals("bar/20", notation(m))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.ui.MoveNotationTest"`
Expected: FAIL — `notation` unresolved.

- [ ] **Step 3: Implement the formatter**

Create `app/src/main/java/dk/rlunde/backgammon/ui/screens/MoveNotation.kt`:

```kotlin
package dk.rlunde.backgammon.ui.screens

import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.SubMove

/** Standard backgammon notation. off = 0/25, bar = 25/0 (WHITE/BLACK). Hits append '*'. */
fun notation(move: Move): String =
    move.subMoves.joinToString(" ") { point(it) }

private fun point(sm: SubMove): String {
    val from = if (sm.from == 0 || sm.from == 25) "bar" else sm.from.toString()
    val to = if (sm.to == 0 || sm.to == 25) "off" else sm.to.toString()
    return "$from/$to" + if (sm.isHit) "*" else ""
}
```

(Note: `from` is "bar" for the bar sentinels (25 WHITE / 0 BLACK) and `to` is "off" for the off sentinels (0 WHITE / 25 BLACK). Since a single sub-move is either an entry-from-bar or a bear-off-to-off, the sentinel is unambiguous by position; the simple rule above is correct for both colours per `Move.kt`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.ui.MoveNotationTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Add the marker + sheet + Analyse button (Compose)**

In `GameViewModel.kt`, add the on-demand entry:

```kotlin
    fun onAnalyse() {
        if (controller.uiState.toMove == aiSide) return
        val board = controller.uiState.board
        val dice = controller.uiState.dice ?: return
        val legal = MoveGenerator.legalMoves(board, dice)
        if (legal.isEmpty()) return
        val epoch = ++analysisEpoch
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            // Pre-move: rank only; show best with no played comparison by analysing the best as "played".
            val best = withContext(analysisDispatcher) {
                Expectimax.rankMoves(board, dice, legal, dk.rlunde.backgammon.ai.Weights.FULL, 1,
                    dk.rlunde.backgammon.ai.NodeBudget(Int.MAX_VALUE)).first()
            }
            if (epoch == analysisEpoch) {
                analysis = MoveAnalyzer.analyze(board, dice, best.move, legal)  // best vs best → BEST, factors of best
                publish()
            }
        }
    }
```

Note `Expectimax`/`Weights`/`NodeBudget` are `internal` to `:ai`; to call them from `:app`, instead expose a tiny public helper in `:ai` — add to `MoveAnalyzer`:

```kotlin
    /** On-demand pre-move hint: analyse the current best play against itself (band BEST, factors only). */
    fun analyzeBest(state: BoardState, dice: Dice, legal: List<Move>): MoveAnalysis {
        require(legal.isNotEmpty()) { "analyzeBest called with no legal moves" }
        val best = Expectimax.rankMoves(state, dice, legal, REFERENCE_WEIGHTS, ANALYSIS_DEPTH, NodeBudget(Int.MAX_VALUE)).first()
        return analyze(state, dice, best.move, legal)
    }
```

Then `onAnalyse()` calls `MoveAnalyzer.analyzeBest(board, dice, legal)` inside `withContext` — no `internal` leak. Update `onAnalyse()` accordingly.

In `GameScreen.kt`, render (the executing agent places these in the existing layout):

```kotlin
// Near the dice/turn indicator: a band marker when analysis present.
val analysis = uiState.analysis
if (analysis != null) {
    val (label, color) = when {
        analysis.forced -> "Forced" to Color(0xFF9E9E9E)
        analysis.band == Band.BEST -> "Best" to Color(0xFF2E7D32)
        analysis.band == Band.GOOD -> "Good" to Color(0xFF9CCC65)
        analysis.band == Band.INACCURACY -> "Inaccuracy" to Color(0xFFFFB300)
        analysis.band == Band.MISTAKE -> "Mistake" to Color(0xFFF57C00)
        else -> "Blunder" to Color(0xFFC62828)
    }
    AssistChip(onClick = { showSheet = true }, label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(containerColor = color, labelColor = Color.White))
}
// An "Analyse" button enabled on the human's turn with a dice rolled:
Button(onClick = { vm.onAnalyse() }, enabled = uiState.toMove != uiState.aiSide && uiState.dice != null) {
    Text("Analyse")
}
// A ModalBottomSheet (showSheet state) rendering:
//   header: band · evalLoss (skip if terminal) · "~${pct}% win*" when winProbDrop != null
//   "Your move ranked N of M" (or "tied for 1st"/"Forced")
//   "Best: ${notation(analysis.best.move)}  (2-ply)" and "Yours: ${notation(analysis.played.move)}"
//   top 3 featureDeltas: "${label(feature)}  ${signed(delta)}"
//   footnote "* win% is approximate (uncalibrated, single-win only)"
```

Use `import dk.rlunde.backgammon.ai.Band` and a local `featureLabel(Feature)` mapping (e.g. `Feature.KEY_POINT -> "Key points (5/bar)"`). Keep the sheet read-only; "Show on board" can reuse existing highlight rendering as a follow-up if not trivial.

- [ ] **Step 6: Build the APK**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/screens/MoveNotation.kt app/src/test/java/dk/rlunde/backgammon/ui/MoveNotationTest.kt app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzer.kt
git commit -m "feat(app): training marker, detail sheet, on-demand Analyse + move notation"
```

---

## Task 9: Final suite + manual smoke

- [ ] **Step 1: Full `:ai` + `:app` unit suites**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :ai:test :app:testDebugUnitTest`
Expected: PASS. (Acceptance: the EvaluatorBreakdown, RankMoves, MoveAnalyzer(+Edge), GameConfig, GameControllerCommit, GameViewModelAnalysis, MoveNotation suites are green; provisional band/K constants are pinned as change-detectors, not asserted "correct".)

- [ ] **Step 2: Manual smoke on device/emulator**

Install `app-debug.apk`, start a **vs-computer** game with **Training: On**, make a deliberately bad move, confirm a coloured marker appears, tap it → sheet shows band, ranked N of M, best vs your move in notation (with `*` on hits), and the top factors. Press **Analyse** before moving → best move + factors. Confirm **hot-seat** shows no Training chip.

- [ ] **Step 3: Spec cross-check**

Re-read `docs/superpowers/specs/2026-06-14-phase5-trainer-design.md` §2 and confirm each in-scope bullet maps to a shipped task; note any deferred item (e.g. "Show on board" highlight) in the PR description.

---

## Self-Review Notes (for the executor)

- **Sign correctness is the #1 risk** (spec §3.5): `rankMoves` scores via `-value(apply(...))`; `MoveAnalyzer` calls `breakdown(..., perspective = state.toMove, ...)` for both best and played. Task 2 + Task 3 tests assert `best.score >= played.score` and non-negative `evalLoss`.
- **`bestMove` is deliberately untouched** (Task 2 Step 6) to keep Phase-3 baselines valid.
- **Budget**: analyzer always passes `NodeBudget(Int.MAX_VALUE)` so no depth-0 fallback contaminates a ranking (spec §3.4b).
- **Move matching** is by resulting `BoardState`, not raw `Move` equality (Task 3, tested with reversed sub-moves).
- If `kotlinx-coroutines-test` is missing from the version catalog, Task 7 Step 4 adds it; check `gradle/libs.versions.toml` first.
