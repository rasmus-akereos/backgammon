# Phase 2 — Basic AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A single-player mode where a hand-tuned 1-ply AI opponent (Beginner + Intermediate) plays one colour against the human, with a lightweight setup screen and an off-main-thread "thinking" turn — validating the engine against a real opponent.

**Architecture:** New pure-Kotlin `:ai` module (depends on `:core` only): an `Evaluator` (weighted linear features) + `MoveSearch` (1-ply greedy argmax) behind an `AiPlayer` interface, with two `Difficulty` tiers. `:core`/`GameController` stay pure and synchronous (gain `aiSide` + `applyMove`); a unit-testable `AiTurnDriver` runs the AI turn and `GameViewModel` owns the coroutine/lifecycle. `aiThinking`/`aiSide` ride inside the single published `GameUiState` snapshot.

**Tech Stack:** Kotlin 2.0.21, JUnit5 + kotlin.test (pure modules), kotlinx-coroutines + coroutines-test (`:app` only), Jetpack Compose (Material3), AGP 8.7.3, JDK 21 (Android Studio JBR). No new third-party deps.

**Spec:** `docs/superpowers/specs/2026-06-11-phase2-basic-ai-design.md` (revised after multi-lens review).

---

## Conventions (read once — every task assumes these)

- **Run all Gradle commands in the Bash tool with `JAVA_HOME` set:**
  ```
  export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
  ./gradlew <task> --console=plain 2>&1 | tail -40
  ```
- Packages: `:core` = `dk.rlunde.backgammon.core`; `:ai` = `dk.rlunde.backgammon.ai`; `:app` = `dk.rlunde.backgammon`.
- `:core` API used here: `BoardState(points: IntArray[26], bar: Map<Player,Int>, off: Map<Player,Int>, toMove: Player)` with `.count(player,i)`, `.barCount(player)`, `.offCount(player)`; `Scoring.pipCount(state,player)`, `Scoring.isGameOver`, `Scoring.winnerAndValue`; `MoveGenerator.legalMoves(state,dice): List<Move>`, `.apply(state,move): BoardState`, `.pass(state)`; `Player.WHITE`(home 1–6, off 0, bar 25, moves high→low)/`BLACK`(home 19–24, off 25, bar 0, moves low→high), `.opponent`; `Dice(a,b).pips()`; `Move(subMoves)`, `SubMove(from,to,die,isHit)`; `SeededDiceRoller(Long)`, `RandomDiceRoller`; `startingPosition()`.
- `:ai` is **coroutine-free** and its internals are `internal` (public surface: `AiPlayer`, `HeuristicAiPlayer`, `Difficulty`).
- TDD per task: write failing test → run & SEE it fail → implement → run & SEE it pass → run the module's full suite (regression gate) → commit. **Never push.** End each commit body with:
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

## Task 0: Scaffold the `:ai` module (gated)

**Files:**
- Modify: `settings.gradle.kts`
- Create: `ai/build.gradle.kts`, `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Placeholder.kt`

- [ ] **Step 1: Add `:ai` to `settings.gradle.kts`**

Append after `include(":app")`:
```kotlin
include(":ai")
```

- [ ] **Step 2: Create `ai/build.gradle.kts`** (mirror `:core` — kotlin-jvm, JVM-17, depends on `:core`)

Read `core/build.gradle.kts` first and copy its shape. It should look like:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom ?: libs.junit.jupiter)) // match :core's test setup
    testImplementation(libs.junit.jupiter)
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(21) }
```
IMPORTANT: open `core/build.gradle.kts` and replicate its exact `plugins`, test deps, `useJUnitPlatform()`, and toolchain lines so `:ai` matches `:core` (the snippet above is approximate). Do **not** add coroutines.

- [ ] **Step 3: Create a placeholder so the module compiles**

`ai/src/main/kotlin/dk/rlunde/backgammon/ai/Placeholder.kt`:
```kotlin
package dk.rlunde.backgammon.ai

internal const val AI_MODULE_PLACEHOLDER = true
```

- [ ] **Step 4: Gate — the project configures and `:ai` builds**

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :ai:compileKotlin :core:test --console=plain 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL; `:core` 60 tests still pass. **Do not proceed until this builds.**

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts ai/build.gradle.kts ai/src/main/kotlin/dk/rlunde/backgammon/ai/Placeholder.kt
git commit -m "chore(ai): scaffold pure-Kotlin :ai module depending on :core"
```

---

## Task 1: `ShotTable` (hit-probability lookup)

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/ShotTable.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/ShotTableTest.kt`

- [ ] **Step 1: Write the failing test** (counts derived independently — see spec §4.3)

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShotTableTest {
    @Test fun `direct shot distances are 11 over 36`() {
        // distance 1 is direct-only (no combination reaches 1)
        assertEquals(11.0 / 36.0, ShotTable.hitProbability(1), 1e-9)
    }

    @Test fun `distance six is 17 over 36 (11 direct + 6 combination)`() {
        // 11 direct sixes + (1,5)(5,1)(2,4)(4,2) + (3,3) + (2,2) = 11 + 4 + 1 + 1 = 17
        assertEquals(17.0 / 36.0, ShotTable.hitProbability(6), 1e-9)
    }

    @Test fun `combination-only distances`() {
        assertEquals(6.0 / 36.0, ShotTable.hitProbability(8), 1e-9)   // (2,6)(6,2)(3,5)(5,3)(4,4)(2,2)
        assertEquals(2.0 / 36.0, ShotTable.hitProbability(11), 1e-9)  // (5,6)(6,5)
        assertEquals(3.0 / 36.0, ShotTable.hitProbability(12), 1e-9)  // (6,6)(4,4)(3,3)
    }

    @Test fun `out of range distances are zero`() {
        assertEquals(0.0, ShotTable.hitProbability(0), 1e-9)
        assertEquals(0.0, ShotTable.hitProbability(13), 1e-9)
        assertEquals(0.0, ShotTable.hitProbability(99), 1e-9)
    }

    @Test fun `direct distances dominate any indirect-only distance`() {
        for (d in 1..6) assertTrue(ShotTable.hitProbability(d) >= ShotTable.hitProbability(8))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

`./gradlew :ai:test --tests "*ShotTableTest" 2>&1 | tail -20` → FAIL (`ShotTable` unresolved).

- [ ] **Step 3: Implement `ShotTable.kt`**

```kotlin
package dk.rlunde.backgammon.ai

/**
 * Probability that a single opposing checker at a given shot [distance] hits next roll, out of 36
 * ordered dice rolls. Counts direct shots (1–6) plus combination/doubles shots (7–12), ASSUMING THE
 * INTERMEDIATE PATH IS OPEN — i.e. blocking by made points is ignored (a v1 approximation; the table
 * mildly over-counts hits behind a prime — the safe, over-cautious direction). See spec §4.3.
 */
internal object ShotTable {
    // index = distance 1..12; value = number of the 36 rolls that cover it (path open).
    private val COUNTS = intArrayOf(
        /* 0 */ 0,
        /* 1 */ 11, /* 2 */ 12, /* 3 */ 14, /* 4 */ 15, /* 5 */ 15, /* 6 */ 17,
        /* 7 */ 6,  /* 8 */ 6,  /* 9 */ 5,  /* 10 */ 3, /* 11 */ 2, /* 12 */ 3,
    )

    fun hitProbability(distance: Int): Double =
        if (distance in 1..12) COUNTS[distance] / 36.0 else 0.0
}
```

- [ ] **Step 4: Run to verify it passes**

`./gradlew :ai:test --tests "*ShotTableTest" 2>&1 | tail -10` → PASS.

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/ShotTable.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/ShotTableTest.kt
git commit -m "feat(ai): add ShotTable hit-probability lookup (path-open approximation)"
```

---

## Task 2: `Weights` + `Features` (the feature functions)

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Weights.kt`
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Features.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/FeaturesTest.kt`

Feature reference (perspective `p`, opponent `o = p.opponent`; positive = good for `p`). Home/back ranges and key points:
- `p` home: WHITE `1..6`, BLACK `19..24`. `o` home (for anchors / back checkers): WHITE→`19..24`, BLACK→`1..6`.
- WHITE moves high→low (bar sentinel 25); BLACK moves low→high (bar sentinel 0).
- 5-point: WHITE `5`, BLACK `20`. Bar-point: WHITE `7`, BLACK `18`. Advanced (golden) anchor: WHITE `20`, BLACK `5`.

- [ ] **Step 1: Write the failing test** (per-feature directional checks)

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeaturesTest {
    private fun board(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                      whiteOff: Int = 0, blackOff: Int = 0, toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff), toMove)

    @Test fun `pip differential is positive when perspective is ahead`() {
        val p = IntArray(26); p[1] = 2; p[24] = -2 // WHITE pip 2, BLACK pip 2 -> even
        assertEquals(0, Features.pipDifferential(board(p), Player.WHITE))
        val p2 = IntArray(26); p2[1] = 2; p2[2] = -2 // WHITE pip 2, BLACK pip 23
        assertTrue(Features.pipDifferential(board(p2), Player.WHITE) > 0)
    }

    @Test fun `off differential favours the side with more borne off`() {
        val p = IntArray(26)
        assertTrue(Features.offDifferential(board(p, whiteOff = 3), Player.WHITE) > 0)
    }

    @Test fun `bar term penalises own checkers on the bar`() {
        val p = IntArray(26)
        assertTrue(Features.barTerm(board(p, whiteBar = 1), Player.WHITE) < 0)
        assertTrue(Features.barTerm(board(p, blackBar = 1), Player.WHITE) > 0)
    }

    @Test fun `home points made counts made points in own home`() {
        val p = IntArray(26); p[3] = 2; p[5] = 2 // WHITE made 3-pt and 5-pt
        assertEquals(2, Features.homePointsMade(board(p), Player.WHITE))
        assertEquals(0, Features.homePointsMade(board(p), Player.BLACK))
    }

    @Test fun `prime length finds the longest consecutive run`() {
        val p = IntArray(26); p[4] = 2; p[5] = 2; p[6] = 2; p[8] = 2 // run 4-5-6 = 3
        assertEquals(3, Features.primeLength(board(p), Player.WHITE))
    }

    @Test fun `back checkers counts perspective checkers deep in opponent home`() {
        val p = IntArray(26); p[23] = 2 // WHITE checkers at 23 (in BLACK home 19-24)
        assertEquals(2, Features.backCheckers(board(p), Player.WHITE))
    }

    @Test fun `blot exposure penalty grows with a nearer opposing shooter`() {
        // WHITE blot at 12; BLACK shooter (moves low->high) at 6 => distance 6 (17/36)
        val near = IntArray(26); near[12] = 1; near[6] = -1
        // BLACK shooter at 1 => distance 11 (2/36) -- much smaller
        val far = IntArray(26); far[12] = 1; far[1] = -1
        val pen = Features.blotPenalty(board(near), Player.WHITE, costWeighted = true)
        val penFar = Features.blotPenalty(board(far), Player.WHITE, costWeighted = true)
        assertTrue(pen > penFar, "nearer shooter must penalise more ($pen vs $penFar)")
        assertTrue(pen > 0)
    }

    @Test fun `no contact is detected when armies have passed`() {
        val race = IntArray(26); race[3] = 2; race[22] = -2 // white low, black high, passed
        assertTrue(Features.noContact(board(race)))
        val contact = IntArray(26); contact[24] = 2; contact[1] = -2 // opening-like overlap
        assertTrue(!Features.noContact(board(contact)))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

`./gradlew :ai:test --tests "*FeaturesTest" 2>&1 | tail -20` → FAIL.

- [ ] **Step 3: Implement `Weights.kt`**

```kotlin
package dk.rlunde.backgammon.ai

/** Hand-tuned linear weights. Phase 2: reasonable constants; precise tuning is Phase 3. */
internal data class Weights(
    val pip: Double,
    val off: Double,
    val blot: Double,
    val homePoint: Double,
    val fivePoint: Double,     // extra for the 5-point / bar-point
    val prime: Double,
    val anchor: Double,
    val advancedAnchor: Double, // extra for the golden/bar-point anchor
    val bar: Double,
    val backChecker: Double,
) {
    companion object {
        val FULL = Weights(
            pip = 1.0, off = 12.0, blot = 1.0, homePoint = 4.0, fivePoint = 3.0,
            prime = 4.0, anchor = 3.0, advancedAnchor = 4.0, bar = 8.0, backChecker = 2.0,
        )
        // Beginner: race + crude (probability-only) blot avoidance; everything positional off.
        val SIMPLIFIED = Weights(
            pip = 1.0, off = 12.0, blot = 1.0, homePoint = 0.0, fivePoint = 0.0,
            prime = 0.0, anchor = 0.0, advancedAnchor = 0.0, bar = 8.0, backChecker = 0.0,
        )
    }
}
```

- [ ] **Step 4: Implement `Features.kt`**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.Scoring

/**
 * Pure board-feature computations for the evaluator, each from [p]'s perspective. WHITE moves
 * high→low (bar sentinel 25), BLACK low→high (bar sentinel 0). See spec §4.2.
 */
internal object Features {

    fun pipDifferential(s: BoardState, p: Player): Int =
        Scoring.pipCount(s, p.opponent) - Scoring.pipCount(s, p)

    fun offDifferential(s: BoardState, p: Player): Int =
        s.offCount(p) - s.offCount(p.opponent)

    /** Negative when [p] has checkers on the bar; positive when the opponent does. */
    fun barTerm(s: BoardState, p: Player): Int =
        s.barCount(p.opponent) - s.barCount(p)

    private fun homeRange(p: Player): IntRange = if (p == Player.WHITE) 1..6 else 19..24
    private fun fivePoint(p: Player): Int = if (p == Player.WHITE) 5 else 20
    private fun barPoint(p: Player): Int = if (p == Player.WHITE) 7 else 18
    private fun advancedAnchor(p: Player): Int = if (p == Player.WHITE) 20 else 5

    fun homePointsMade(s: BoardState, p: Player): Int =
        homeRange(p).count { s.count(p, it) >= 2 }

    /** Bonus count for the high-value made points (5-point, bar-point). */
    fun keyPointsMade(s: BoardState, p: Player): Int =
        listOf(fivePoint(p), barPoint(p)).count { s.count(p, it) >= 2 }

    fun primeLength(s: BoardState, p: Player): Int {
        var best = 0; var run = 0
        for (i in 1..24) {
            if (s.count(p, i) >= 2) { run++; if (run > best) best = run } else run = 0
        }
        return best
    }

    /** Points [p] holds (≥2) inside the opponent's home board. */
    fun anchorsMade(s: BoardState, p: Player): Int {
        val oppHome = homeRange(p.opponent)
        return oppHome.count { s.count(p, it) >= 2 }
    }

    fun advancedAnchorsMade(s: BoardState, p: Player): Int =
        if (s.count(p, advancedAnchor(p)) >= 2) 1 else 0

    /** [p] checkers still deep in the opponent's home quadrant (must escape). */
    fun backCheckers(s: BoardState, p: Player): Int =
        homeRange(p.opponent).sumOf { s.count(p, it) }

    /** Pips the [p] checker at [i] loses if hit (sent to the bar, pip 25). */
    private fun hitCost(p: Player, i: Int): Int = if (p == Player.WHITE) 25 - i else i

    /**
     * Sum over [p]'s blots of (max single-shooter hit probability) × cost. When [costWeighted] is
     * false (Beginner), cost is 1 (probability-only). A shooter is an opponent checker — or the
     * opponent's bar — that lies "behind" the blot in the opponent's direction of travel.
     */
    fun blotPenalty(s: BoardState, p: Player, costWeighted: Boolean): Double {
        val o = p.opponent
        var penalty = 0.0
        for (i in 1..24) {
            if (s.count(p, i) != 1) continue // not a blot of p's
            var bestProb = 0.0
            // shooter points: opponent checkers + opponent bar sentinel
            val shooters = ArrayList<Int>(15)
            for (j in 1..24) if (s.count(o, j) > 0) shooters.add(j)
            if (s.barCount(o) > 0) shooters.add(if (o == Player.WHITE) 25 else 0)
            for (j in shooters) {
                val d = if (o == Player.WHITE) j - i else i - j // o moves toward its bear-off
                if (d in 1..12) bestProb = maxOf(bestProb, ShotTable.hitProbability(d))
            }
            penalty += if (costWeighted) bestProb * hitCost(p, i) else bestProb
        }
        return penalty
    }

    /** True when no hit is possible: every [WHITE] checker is at a lower point than every BLACK one. */
    fun noContact(s: BoardState): Boolean {
        val whiteBack = if (s.barCount(Player.WHITE) > 0) 25
            else (1..24).lastOrNull { s.count(Player.WHITE, it) > 0 } ?: 0
        val blackBack = if (s.barCount(Player.BLACK) > 0) 0
            else (1..24).firstOrNull { s.count(Player.BLACK, it) > 0 } ?: 25
        return whiteBack < blackBack
    }
}
```

- [ ] **Step 5: Run to verify it passes**

`./gradlew :ai:test --tests "*FeaturesTest" 2>&1 | tail -15` → PASS. If `pipDifferential` direction or `noContact` fails, re-check the WHITE/BLACK travel direction against the cases (do not weaken the test).

- [ ] **Step 6: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/Weights.kt ai/src/main/kotlin/dk/rlunde/backgammon/ai/Features.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/FeaturesTest.kt
git commit -m "feat(ai): add Weights + Features (pure board-feature functions)"
```

---

## Task 3: `Evaluator` (weighted sum + race/contact phase switch)

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Evaluator.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/EvaluatorTest.kt`

- [ ] **Step 1: Write the failing test** (zero-sum identity + phase switch + monotonic sanity)

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvaluatorTest {
    private fun board(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                      whiteOff: Int = 0, blackOff: Int = 0, toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff), toMove)

    @Test fun `zero-sum identity holds on the starting position`() {
        val s = startingPosition()
        val w = Evaluator.evaluate(s, Player.WHITE, Weights.FULL)
        val b = Evaluator.evaluate(s, Player.BLACK, Weights.FULL)
        assertEquals(w, -b, 1e-6)
    }

    @Test fun `zero-sum identity holds on an asymmetric contact position`() {
        val p = IntArray(26); p[6] = 2; p[8] = 1; p[13] = 3; p[20] = -2; p[18] = -1; p[2] = -3
        val s = board(p)
        assertEquals(Evaluator.evaluate(s, Player.WHITE, Weights.FULL),
                     -Evaluator.evaluate(s, Player.BLACK, Weights.FULL), 1e-6)
    }

    @Test fun `more checkers off scores higher`() {
        val a = board(IntArray(26).also { it[2] = 2 }, whiteOff = 13)
        val b = board(IntArray(26).also { it[2] = 4 }, whiteOff = 11)
        assertTrue(Evaluator.evaluate(a, Player.WHITE, Weights.FULL) >
                   Evaluator.evaluate(b, Player.WHITE, Weights.FULL))
    }

    @Test fun `no-contact position is scored by the race only`() {
        // WHITE all low, BLACK all high (passed). Two positions equal in pips but different in
        // a contact feature (a fake "blot") must score identically because contact features are off.
        val a = IntArray(26); a[1] = 1; a[2] = 1; a[3] = 13; a[22] = -15
        val s = board(a)
        assertTrue(Features.noContact(s))
        // race score == pip*pipDiff + off*offDiff only
        val expected = Weights.FULL.pip * Features.pipDifferential(s, Player.WHITE) +
                       Weights.FULL.off * Features.offDifferential(s, Player.WHITE)
        assertEquals(expected, Evaluator.evaluate(s, Player.WHITE, Weights.FULL), 1e-6)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

`./gradlew :ai:test --tests "*EvaluatorTest" 2>&1 | tail -20` → FAIL.

- [ ] **Step 3: Implement `Evaluator.kt`**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player

/**
 * Weighted linear position evaluator; positive = good for [perspective]. Callers evaluate the
 * POST-move state (toMove already flipped) with [perspective] = the side that just moved (spec §4.4).
 * In a no-contact race the eval collapses to ≈ pip differential. See spec §4.2.
 */
internal object Evaluator {

    fun evaluate(state: BoardState, perspective: Player, weights: Weights): Double {
        val race = weights.pip * Features.pipDifferential(state, perspective) +
                   weights.off * Features.offDifferential(state, perspective)
        if (Features.noContact(state)) return race

        val costWeighted = weights.blot > 0.0 && weights != Weights.SIMPLIFIED
        return race +
            weights.bar * Features.barTerm(state, perspective) +
            weights.backChecker * -Features.backCheckers(state, perspective) +
            weights.homePoint * Features.homePointsMade(state, perspective) +
            weights.fivePoint * Features.keyPointsMade(state, perspective) +
            weights.prime * Features.primeLength(state, perspective) +
            weights.anchor * Features.anchorsMade(state, perspective) +
            weights.advancedAnchor * Features.advancedAnchorsMade(state, perspective) -
            weights.blot * Features.blotPenalty(state, perspective, costWeighted) +
            // opponent's blots are good for us: subtract the opponent's exposure as a bonus
            weights.blot * Features.blotPenalty(state, perspective.opponent, costWeighted)
    }
}
```
Note: evaluating `perspective` minus opponent on the symmetric contact features is what makes the zero-sum identity hold — `pipDifferential`/`offDifferential`/`barTerm` are already differences, and the blot term subtracts our exposure and adds the opponent's. `homePointsMade`/`prime`/`anchor`/`backCheckers` are **not** differenced above, which breaks zero-sum. **Fix in this step:** difference every contact feature. Replace the non-differenced lines so each is `feature(state, perspective) - feature(state, perspective.opponent)`:
```kotlin
        return race +
            weights.bar * Features.barTerm(state, perspective) +
            weights.backChecker * (Features.backCheckers(state, perspective.opponent) - Features.backCheckers(state, perspective)) +
            weights.homePoint * (Features.homePointsMade(state, perspective) - Features.homePointsMade(state, perspective.opponent)) +
            weights.fivePoint * (Features.keyPointsMade(state, perspective) - Features.keyPointsMade(state, perspective.opponent)) +
            weights.prime * (Features.primeLength(state, perspective) - Features.primeLength(state, perspective.opponent)) +
            weights.anchor * (Features.anchorsMade(state, perspective) - Features.anchorsMade(state, perspective.opponent)) +
            weights.advancedAnchor * (Features.advancedAnchorsMade(state, perspective) - Features.advancedAnchorsMade(state, perspective.opponent)) +
            weights.blot * (Features.blotPenalty(state, perspective.opponent, costWeighted) - Features.blotPenalty(state, perspective, costWeighted))
```
(Use this differenced form; delete the first `return race + …` block. `costWeighted` = `weights != Weights.SIMPLIFIED`.)

- [ ] **Step 4: Run to verify it passes**

`./gradlew :ai:test --tests "*EvaluatorTest" 2>&1 | tail -15` → PASS (the zero-sum tests prove every contact feature is correctly differenced). Then run the whole `:ai` suite: `./gradlew :ai:test 2>&1 | tail -10` → all green.

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/Evaluator.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/EvaluatorTest.kt
git commit -m "feat(ai): add Evaluator (differenced linear features + race phase switch)"
```

---

## Task 4: `MoveSearch` (1-ply greedy argmax)

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveSearch.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveSearchTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoveSearchTest {
    private fun board(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                      toMove: Player = Player.WHITE) =
        BoardState(points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to 0, Player.BLACK to 0), toMove)

    @Test fun `picks the move that maximises post-move eval`() {
        val p = IntArray(26); p[13] = 2; p[24] = 2; p[1] = -2
        val s = board(p)
        val dice = Dice(3, 5)
        val legal = MoveGenerator.legalMoves(s, dice)
        assertTrue(legal.isNotEmpty())
        val best = MoveSearch.bestMove(s, dice, legal, Weights.FULL)
        val bestScore = Evaluator.evaluate(MoveGenerator.apply(s, best), s.toMove, Weights.FULL)
        // No legal move scores higher than the chosen one.
        for (m in legal) {
            val score = Evaluator.evaluate(MoveGenerator.apply(s, m), s.toMove, Weights.FULL)
            assertTrue(score <= bestScore + 1e-9, "found a better move than bestMove returned")
        }
    }

    @Test fun `prefers hitting an opponent blot over an equal non-hitting move`() {
        // Construct a position where one die can hit a lone BLACK checker; the AI should take it.
        val p = IntArray(26); p[8] = 1 /*WHITE*/; p[5] = -1 /*BLACK blot, hit by a 3 from 8*/
        p[13] = 2
        val s = board(p)
        val dice = Dice(3, 4)
        val legal = MoveGenerator.legalMoves(s, dice)
        val best = MoveSearch.bestMove(s, dice, legal, Weights.FULL)
        // The chosen turn hits (some sub-move lands on 5 and is a hit).
        assertTrue(best.subMoves.any { it.isHit }, "AI should choose the hitting turn")
    }

    @Test fun `tie-break is deterministic`() {
        val p = IntArray(26); p[13] = 2; p[1] = -2
        val s = board(p); val dice = Dice(2, 2)
        val legal = MoveGenerator.legalMoves(s, dice)
        val a = MoveSearch.bestMove(s, dice, legal, Weights.FULL)
        val b = MoveSearch.bestMove(s, dice, legal, Weights.FULL)
        assertEquals(a, b)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

`./gradlew :ai:test --tests "*MoveSearchTest" 2>&1 | tail -20` → FAIL.

- [ ] **Step 3: Implement `MoveSearch.kt`**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.MoveGenerator

/**
 * 1-ply move selection. The roll is already known, so this is a greedy argmax over the post-move
 * static eval (NOT expectimax — no chance node at depth 1). Deterministic tie-break: the first
 * maximum encountered. The genuine chance-node expectimax (≥2-ply) arrives in Phase 3. Spec §4.4.
 */
internal object MoveSearch {
    fun bestMove(state: BoardState, dice: Dice, legal: List<Move>, weights: Weights): Move {
        require(legal.isNotEmpty()) { "bestMove called with no legal moves" }
        val mover = state.toMove
        var best = legal.first()
        var bestScore = Evaluator.evaluate(MoveGenerator.apply(state, best), mover, weights)
        for (i in 1 until legal.size) {
            val m = legal[i]
            val score = Evaluator.evaluate(MoveGenerator.apply(state, m), mover, weights)
            if (score > bestScore) { bestScore = score; best = m }
        }
        return best
    }
}
```

- [ ] **Step 4: Run to verify it passes**

`./gradlew :ai:test --tests "*MoveSearchTest" 2>&1 | tail -15` → PASS. (If the hit-preference test fails, the eval's blot/hit handling has a sign error — fix the evaluator, not the test.)

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveSearch.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveSearchTest.kt
git commit -m "feat(ai): add MoveSearch (1-ply greedy argmax over post-move eval)"
```

---

## Task 5: `AiPlayer` + `Difficulty` + `HeuristicAiPlayer` (+ strength smoke)

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/AiPlayer.kt`
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Difficulty.kt`
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/HeuristicAiPlayer.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/HeuristicAiPlayerTest.kt`
- Delete: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/Placeholder.kt`

- [ ] **Step 1: Write the failing test** (noise determinism + strength smoke)

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeuristicAiPlayerTest {
    @Test fun `intermediate never takes the weak branch`() {
        val ai = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(1))
        val s = startingPosition(); val dice = Dice(3, 1)
        val legal = MoveGenerator.legalMoves(s, dice)
        val best = MoveSearch.bestMove(s, dice, legal, Weights.FULL)
        repeat(20) { assertEquals(best, ai.chooseMove(s, dice, legal)) } // always the search best
    }

    @Test fun `beginner weak branch only ever returns a top-K move`() {
        // Over many seeds, every Beginner pick must be within the top-3 simplified-eval moves.
        val s = startingPosition(); val dice = Dice(6, 5)
        val legal = MoveGenerator.legalMoves(s, dice)
        val topK = legal.sortedByDescending {
            Evaluator.evaluate(MoveGenerator.apply(s, it), s.toMove, Weights.SIMPLIFIED)
        }.take(3).toSet()
        for (seed in 0L until 50L) {
            val pick = HeuristicAiPlayer(Difficulty.BEGINNER, Random(seed)).chooseMove(s, dice, legal)
            assertTrue(pick in topK, "Beginner picked outside top-3 (seed $seed)")
        }
    }

    @Test fun `intermediate beats a uniform-random player by a wide margin`() {
        val seeds = (1L..40L).toList()           // fixed seed set -> deterministic result
        var aiWins = 0
        for (seed in seeds) {
            if (playGame(seed)) aiWins++
        }
        // Deterministic over the fixed seed set; Intermediate should dominate random.
        assertTrue(aiWins >= 32, "Intermediate won only $aiWins/${seeds.size} vs random")
    }

    /** Returns true if the Intermediate AI (WHITE) beats a uniform-random BLACK player. */
    private fun playGame(seed: Long): Boolean {
        val rng = Random(seed)
        val roller = SeededDiceRoller(seed)
        var state = startingPosition() // WHITE to move
        val ai = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(seed xor 0x5DEECE66DL))
        var guard = 0
        while (!Scoring.isGameOver(state) && guard++ < 1000) {
            val dice = roller.roll()
            val legal = MoveGenerator.legalMoves(state, dice)
            state = if (legal.isEmpty()) {
                MoveGenerator.pass(state)
            } else {
                val move = if (state.toMove == Player.WHITE) ai.chooseMove(state, dice, legal)
                           else legal[rng.nextInt(legal.size)]
                MoveGenerator.apply(state, move)
            }
        }
        return Scoring.winnerAndValue(state)?.first == Player.WHITE
    }
}
```

- [ ] **Step 2: Run to verify it fails**

`./gradlew :ai:test --tests "*HeuristicAiPlayerTest" 2>&1 | tail -20` → FAIL (types unresolved).

- [ ] **Step 3: Implement `AiPlayer.kt`**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move

/**
 * Chooses a full turn. Pure + synchronous; the caller (the VM) runs it off the UI thread via
 * withContext(Dispatchers.Default). Perspective is [state].toMove (the AI's own side).
 * @param legal MUST be MoveGenerator.legalMoves(state, dice) for the same state/dice, non-empty.
 */
interface AiPlayer {
    fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move
}
```

- [ ] **Step 4: Implement `Difficulty.kt`**

```kotlin
package dk.rlunde.backgammon.ai

/** Difficulty = a small data table (weights + noise). No ply field in Phase 2 (1-ply only). */
enum class Difficulty(internal val weights: Weights, internal val noise: Double) {
    BEGINNER(Weights.SIMPLIFIED, noise = 0.25),
    INTERMEDIATE(Weights.FULL, noise = 0.0),
}
```

- [ ] **Step 5: Implement `HeuristicAiPlayer.kt`**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Move
import dk.rlunde.backgammon.core.MoveGenerator
import kotlin.random.Random

/**
 * The Phase-2 AI. With probability [Difficulty.noise] it plays a *plausible* weak move (a uniform
 * pick among the top-3 moves by the simplified eval — human-fallible, not absurd); otherwise it
 * plays the 1-ply best. [rng] is injected for deterministic tests.
 */
class HeuristicAiPlayer(
    private val difficulty: Difficulty,
    private val rng: Random = Random.Default,
) : AiPlayer {

    override fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move {
        require(legal.isNotEmpty()) { "chooseMove called with no legal moves" }
        return if (difficulty.noise > 0.0 && rng.nextDouble() < difficulty.noise)
            sampleWeak(state, dice, legal)
        else
            MoveSearch.bestMove(state, dice, legal, difficulty.weights)
    }

    private fun sampleWeak(state: BoardState, dice: Dice, legal: List<Move>): Move {
        val topK = legal.sortedByDescending {
            Evaluator.evaluate(MoveGenerator.apply(state, it), state.toMove, Weights.SIMPLIFIED)
        }.take(3)
        return topK[rng.nextInt(topK.size)]
    }
}
```

- [ ] **Step 6: Delete the placeholder and verify**

```bash
rm ai/src/main/kotlin/dk/rlunde/backgammon/ai/Placeholder.kt
```
`./gradlew :ai:test 2>&1 | tail -15` → all `:ai` tests PASS (incl. the strength smoke). If the smoke runtime is slow, reduce the seed range toward `1L..25L` but keep the win-rate threshold proportional; record the final numbers. If `aiWins` is below threshold, the eval is too weak — investigate weights/sign (do not lower the bar blindly).

- [ ] **Step 7: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/AiPlayer.kt ai/src/main/kotlin/dk/rlunde/backgammon/ai/Difficulty.kt ai/src/main/kotlin/dk/rlunde/backgammon/ai/HeuristicAiPlayer.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/HeuristicAiPlayerTest.kt
git rm ai/src/main/kotlin/dk/rlunde/backgammon/ai/Placeholder.kt
git commit -m "feat(ai): add AiPlayer/Difficulty/HeuristicAiPlayer + strength smoke"
```

---

## Task 6: `GameUiState` + `GameController` AI changes

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt`
- Modify: `app/src/main/java/dk/rlunde/backgammon/game/GameController.kt`
- Modify: `app/src/test/java/dk/rlunde/backgammon/game/GameControllerTest.kt`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add the `:ai` dependency to `app/build.gradle.kts`**

In the `dependencies { }` block, after `implementation(project(":core"))`:
```kotlin
    implementation(project(":ai"))
```

- [ ] **Step 2: Add VM-populated fields to `GameUiState`** (defaulted so existing construction still compiles)

In `GameUiState.kt`, add two fields at the end of the `data class GameUiState(...)` constructor (after `winValue`):
```kotlin
    val winValue: Int,
    /** Set by the VM, not the pure controller: true while the AI is choosing its move. */
    val aiThinking: Boolean = false,
    /** Set by the VM: the colour the computer plays (null = hot-seat). */
    val aiSide: Player? = null,
```

- [ ] **Step 3: Write the failing controller tests** (append to `GameControllerTest.kt`)

```kotlin
    @Test fun `applyMove applies a full move and flips the turn`() {
        val p = IntArray(26); p[13] = 2; p[1] = -2
        val c = GameController(
            initial = BoardState(p, mapOf(Player.WHITE to 0, Player.BLACK to 0),
                mapOf(Player.WHITE to 0, Player.BLACK to 0), Player.WHITE),
            roller = SeededDiceRoller(1), aiSide = Player.WHITE)
        c.roll()
        val dice = c.uiState.dice!!
        val legal = MoveGenerator.legalMoves(c.uiState.board, dice)
        c.applyMove(legal.first())
        assertEquals(Player.BLACK, c.uiState.toMove)
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    }

    @Test fun `applyMove that bears off the last checker ends the game`() {
        val p = IntArray(26); p[1] = 1; p[19] = -15
        val c = GameController(
            initial = BoardState(p, mapOf(Player.WHITE to 0, Player.BLACK to 0),
                mapOf(Player.WHITE to 14, Player.BLACK to 0), Player.WHITE),
            roller = SeededDiceRoller(3), aiSide = Player.WHITE)
        c.roll()
        val dice = c.uiState.dice!!
        val legal = MoveGenerator.legalMoves(c.uiState.board, dice)
        c.applyMove(legal.first())
        assertEquals(Phase.GAME_OVER, c.uiState.phase)
        assertEquals(Player.WHITE, c.uiState.winner)
    }

    @Test fun `human methods are no-ops on the AI's turn but roll still works`() {
        val p = IntArray(26); p[13] = 2; p[1] = -2
        val c = GameController(
            initial = BoardState(p, mapOf(Player.WHITE to 0, Player.BLACK to 0),
                mapOf(Player.WHITE to 0, Player.BLACK to 0), Player.WHITE),
            roller = SeededDiceRoller(1), aiSide = Player.WHITE) // WHITE is the AI
        val before = c.uiState
        c.tap(BoardTarget.Point(13))
        assertEquals(before, c.uiState)            // tap ignored on AI's turn
        assertTrue(c.roll().let { c.uiState.phase != Phase.NEED_ROLL }) // roll still works for AI
    }

    @Test fun `hot-seat (aiSide null) keeps human methods active for both colours`() {
        val c = GameController(roller = SeededDiceRoller(1)) // aiSide defaults to null
        c.roll()
        c.tap(BoardTarget.Point(13))
        assertTrue(c.uiState.selectedOrigin != null || c.uiState.destinations.isEmpty())
    }
```

- [ ] **Step 4: Run to verify they fail**

`./gradlew :app:testDebugUnitTest --tests "*GameControllerTest" 2>&1 | tail -20` → FAIL (`aiSide`/`applyMove` unresolved).

- [ ] **Step 5: Implement the `GameController` changes**

Add `aiSide` to the constructor:
```kotlin
class GameController(
    private val initial: BoardState = startingPosition(),
    private val roller: DiceRoller = RandomDiceRoller(),
    private val aiSide: Player? = null,
) {
```
Add a guard at the top of `tap`, `undo`, and `commit` (the human move-building methods) — first line of each:
```kotlin
        if (committed.toMove == aiSide) return
```
(Leave `roll()` and `acknowledgePass()` ungated.) Add the `applyMove` method (next to `commit`):
```kotlin
    /** Apply a complete AI-chosen move (no tap-staging). Pre: move ∈ legalMoves(committed, dice). */
    fun applyMove(move: Move) {
        committed = MoveGenerator.apply(committed, move)
        staged.clear()
        selectedOrigin = null
        dice = null
        passing = false
        uiState = compute()
    }
```

- [ ] **Step 6: Run to verify they pass**

`./gradlew :app:testDebugUnitTest --tests "*GameControllerTest" 2>&1 | tail -15` → PASS. Then the full `:app` suite: `./gradlew :app:testDebugUnitTest 2>&1 | tail -10` → all green (existing GameUiStateTest still compiles — the new fields are defaulted).

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt app/src/main/java/dk/rlunde/backgammon/game/GameController.kt app/src/test/java/dk/rlunde/backgammon/game/GameControllerTest.kt
git commit -m "feat(app): GameController aiSide + applyMove; GameUiState aiThinking/aiSide"
```

---

## Task 7: `GameConfig`

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/game/GameConfig.kt`

- [ ] **Step 1: Create `GameConfig.kt`** (pure domain types in `game/`)

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.ai.Difficulty
import dk.rlunde.backgammon.core.Player

enum class Opponent { COMPUTER, HOT_SEAT }

/** Choices from the setup screen. humanColor == null means "Random" (resolved in the VM). */
data class GameConfig(
    val difficulty: Difficulty = Difficulty.INTERMEDIATE,
    val humanColor: Player? = Player.WHITE,
    val opponent: Opponent = Opponent.COMPUTER,
)
```

- [ ] **Step 2: Verify compile**

`./gradlew :app:compileDebugKotlin 2>&1 | tail -8` → SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/game/GameConfig.kt
git commit -m "feat(app): add GameConfig + Opponent"
```

---

## Task 8: `AiTurnDriver` (testable AI-turn loop)

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/game/AiTurnDriver.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/game/AiTurnDriverTest.kt`
- Modify: `app/build.gradle.kts` (add coroutines + coroutines-test)

- [ ] **Step 1: Add coroutines deps to `app/build.gradle.kts`**

The catalog already has `kotlinx-coroutines-core`/`-test` (from Phase 1). Ensure these are present in `dependencies { }`:
```kotlin
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 2: Write the failing test**

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.ai.AiPlayer
import dk.rlunde.backgammon.core.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiTurnDriverTest {
    private fun controller(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                           toMove: Player = Player.WHITE, aiSide: Player?, seed: Long = 1) =
        GameController(
            initial = BoardState(points, mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
                mapOf(Player.WHITE to 0, Player.BLACK to 0), toMove),
            roller = SeededDiceRoller(seed), aiSide = aiSide)

    /** Fake AI that always plays the first legal move and records that it was consulted. */
    private class FakeAi : AiPlayer {
        var calls = 0
        override fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move {
            calls++; return legal.first()
        }
    }

    @Test fun `normal turn rolls, applies a move and flips to the human`() = runTest {
        val p = IntArray(26); p[13] = 2; p[1] = -2
        val c = controller(p, aiSide = Player.WHITE)
        val ai = FakeAi()
        val driver = AiTurnDriver(Player.WHITE, ai, StandardTestDispatcher(testScheduler),
            diceVisibleMs = 0, paceMs = 0)
        var publishes = 0
        driver.maybeRunTurn(c, onThinking = {}, publish = { publishes++ })
        testScheduler.advanceUntilIdle()
        assertEquals(Player.BLACK, c.uiState.toMove)
        assertTrue(ai.calls == 1)
        assertTrue(publishes >= 2)
    }

    @Test fun `no-legal-move turn passes without consulting the AI`() = runTest {
        // WHITE on the bar, BLACK closes its home 19-24 -> no entry.
        val p = IntArray(26)
        for (i in 19..24) p[i] = -2
        val c = controller(p, whiteBar = 1, aiSide = Player.WHITE, seed = 1)
        val ai = FakeAi()
        val driver = AiTurnDriver(Player.WHITE, ai, StandardTestDispatcher(testScheduler), 0, 0)
        driver.maybeRunTurn(c, onThinking = {}, publish = {})
        testScheduler.advanceUntilIdle()
        assertEquals(0, ai.calls)                 // AI never asked
        assertEquals(Player.BLACK, c.uiState.toMove) // auto-passed
    }

    @Test fun `does nothing when it is not the AI's turn`() = runTest {
        val p = IntArray(26); p[13] = 2; p[1] = -2
        val c = controller(p, aiSide = Player.BLACK) // WHITE to move, AI is BLACK
        val ai = FakeAi()
        val driver = AiTurnDriver(Player.BLACK, ai, StandardTestDispatcher(testScheduler), 0, 0)
        driver.maybeRunTurn(c, onThinking = {}, publish = {})
        testScheduler.advanceUntilIdle()
        assertEquals(0, ai.calls)
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    }

    @Test fun `thinking flag is set then always cleared`() = runTest {
        val p = IntArray(26); p[13] = 2; p[1] = -2
        val c = controller(p, aiSide = Player.WHITE)
        val states = mutableListOf<Boolean>()
        val driver = AiTurnDriver(Player.WHITE, FakeAi(), StandardTestDispatcher(testScheduler), 0, 0)
        driver.maybeRunTurn(c, onThinking = { states.add(it) }, publish = {})
        testScheduler.advanceUntilIdle()
        assertEquals(true, states.first())
        assertEquals(false, states.last())
    }
}
```

- [ ] **Step 3: Run to verify it fails**

`./gradlew :app:testDebugUnitTest --tests "*AiTurnDriverTest" 2>&1 | tail -20` → FAIL (`AiTurnDriver` unresolved).

- [ ] **Step 4: Implement `AiTurnDriver.kt`**

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.ai.AiPlayer
import dk.rlunde.backgammon.core.MoveGenerator
import dk.rlunde.backgammon.core.Player
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs ONE AI turn on a [GameController]: roll → (auto-pass | choose+apply), paced for readability.
 * Pure of Android; the dispatcher and delays are injected so it is unit-testable with a TestDispatcher.
 * The VM owns the coroutine/lifecycle and calls this from viewModelScope. See spec §5.2.
 */
class AiTurnDriver(
    private val aiSide: Player?,
    private val ai: AiPlayer?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val diceVisibleMs: Long = 600,
    private val paceMs: Long = 800,
) {
    private var running = false

    /** No-op unless it is the AI's turn and not already running. [onThinking]/[publish] drive the UI. */
    suspend fun maybeRunTurn(
        controller: GameController,
        onThinking: (Boolean) -> Unit,
        publish: () -> Unit,
    ) {
        if (ai == null || aiSide == null || running) return
        if (controller.uiState.toMove != aiSide) return
        if (controller.uiState.phase == Phase.GAME_OVER) return

        running = true
        onThinking(true); publish()
        try {
            val events = controller.roll()
            publish()
            if (diceVisibleMs > 0) delay(diceVisibleMs)
            if (UiEvent.NoLegalMoves in events) {
                controller.acknowledgePass(); publish()
            } else {
                val board = controller.uiState.board
                val dice = controller.uiState.dice!!
                val move = withContext(dispatcher) {
                    ai.chooseMove(board, dice, MoveGenerator.legalMoves(board, dice))
                }
                if (paceMs > 0) delay(paceMs)
                controller.applyMove(move); publish()
            }
        } finally {
            running = false
            onThinking(false); publish()
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

`./gradlew :app:testDebugUnitTest --tests "*AiTurnDriverTest" 2>&1 | tail -15` → PASS. Then full `:app` suite green.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/dk/rlunde/backgammon/game/AiTurnDriver.kt app/src/test/java/dk/rlunde/backgammon/game/AiTurnDriverTest.kt
git commit -m "feat(app): add unit-tested AiTurnDriver (roll/pass/choose+apply, paced)"
```

---

## Task 9: `GameViewModel` (owns the AI lifecycle)

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt`

- [ ] **Step 1: Rewrite `GameViewModel.kt`** (no unit test — thin androidx wiring, verified by emulator smoke; the logic lives in `AiTurnDriver`/`GameController`, already tested)

```kotlin
package dk.rlunde.backgammon.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dk.rlunde.backgammon.ai.HeuristicAiPlayer
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import dk.rlunde.backgammon.game.AiTurnDriver
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.game.GameController
import dk.rlunde.backgammon.game.GameUiState
import dk.rlunde.backgammon.game.Opponent
import dk.rlunde.backgammon.game.UiEvent
import dk.rlunde.backgammon.ui.board.BoardTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class GameViewModel : ViewModel() {
    private var controller = GameController()       // replaced by startGame
    private var aiPlayer: HeuristicAiPlayer? = null
    private var aiSide: Player? = null
    private var driver: AiTurnDriver? = null
    private var aiJob: Job? = null
    private var aiThinking = false
    private var started = false
    private val rng = Random.Default

    private val _uiState = MutableStateFlow(controller.uiState)
    val uiState: StateFlow<GameUiState> = _uiState

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Idempotent: only configures on the first call (SETUP -> GAME), never on recomposition. */
    fun startGame(config: GameConfig) {
        if (started) return
        started = true
        val humanColor = config.humanColor ?: if (rng.nextBoolean()) Player.WHITE else Player.BLACK
        aiSide = if (config.opponent == Opponent.COMPUTER) humanColor.opponent else null
        aiPlayer = aiSide?.let { HeuristicAiPlayer(config.difficulty) }
        driver = aiSide?.let { AiTurnDriver(it, aiPlayer) }
        controller = GameController(initial = startingPosition(), aiSide = aiSide)
        publish()
        maybeRunAi()
    }

    private fun publish(humanEvents: List<UiEvent> = emptyList()) {
        _uiState.value = controller.uiState.copy(aiThinking = aiThinking, aiSide = aiSide)
        humanEvents.forEach { _events.trySend(it) }
    }

    // Human-driven actions. Only forward NoLegalMoves to the dialog when it is the HUMAN's turn.
    fun onRoll() { val e = controller.roll(); publishHumanRoll(e); maybeRunAi() }
    fun onTap(target: BoardTarget) { controller.tap(target); publish() }
    fun onUndo() { controller.undo(); publish() }
    fun onCommit() { controller.commit(); publish(); maybeRunAi() }
    fun onAcknowledgePass() { controller.acknowledgePass(); publish(); maybeRunAi() }

    fun onNewGame() {
        aiJob?.cancel()
        aiJob = null
        aiThinking = false
        started = false
        controller = GameController()
        publish()
        // The screen returns to SETUP (MainActivity observes `started` via a callback or the host).
    }

    private fun publishHumanRoll(events: List<UiEvent>) {
        // human turn: surface the auto-pass dialog
        publish(events)
    }

    private fun maybeRunAi() {
        val d = driver ?: return
        if (aiSide == null || aiPlayer == null) return
        if (aiJob?.isActive == true) return
        aiJob = viewModelScope.launch {
            d.maybeRunTurn(
                controller,
                onThinking = { aiThinking = it },
                publish = { publish() },   // state only — never forwards NoLegalMoves to the human dialog
            )
        }
    }
}
```
Note: `onNewGame` resets `started=false` so the host (MainActivity) can switch back to SETUP. If MainActivity drives the SETUP/GAME switch by its own `rememberSaveable` enum, `onNewGame` just needs to cancel the AI and reset; the screen change is the Activity's concern (Task 10).

- [ ] **Step 2: Verify compile**

`./gradlew :app:compileDebugKotlin 2>&1 | tail -10` → SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt
git commit -m "feat(app): GameViewModel owns AI lifecycle (startGame, maybeRunAi, job cancel)"
```

---

## Task 10: `SetupScreen` + `MainActivity` (SETUP ↔ GAME)

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/ui/screens/SetupScreen.kt`
- Modify: `app/src/main/java/dk/rlunde/backgammon/MainActivity.kt`

- [ ] **Step 1: Create `SetupScreen.kt`**

```kotlin
package dk.rlunde.backgammon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dk.rlunde.backgammon.ai.Difficulty
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.game.Opponent

@Composable
fun SetupScreen(onStart: (GameConfig) -> Unit) {
    var difficulty by remember { mutableStateOf(Difficulty.INTERMEDIATE) }
    var colorChoice by remember { mutableStateOf<Player?>(Player.WHITE) } // null = random
    var opponent by remember { mutableStateOf(Opponent.COMPUTER) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("New game", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))

            ChoiceRow("Opponent", listOf(
                "Computer" to Opponent.COMPUTER, "Hot-seat" to Opponent.HOT_SEAT,
            ), opponent) { opponent = it }

            if (opponent == Opponent.COMPUTER) {
                ChoiceRow("Difficulty", listOf(
                    "Beginner" to Difficulty.BEGINNER, "Intermediate" to Difficulty.INTERMEDIATE,
                ), difficulty) { difficulty = it }
            }

            ChoiceRow("You play", listOf(
                "White" to Player.WHITE, "Black" to Player.BLACK, "Random" to null,
            ), colorChoice) { colorChoice = it }

            Spacer(Modifier.height(24.dp))
            Button(onClick = { onStart(GameConfig(difficulty, colorChoice, opponent)) }) {
                Text("Start")
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (text, value) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}
```

- [ ] **Step 2: Rewrite `MainActivity.kt`** to host SETUP ↔ GAME

```kotlin
package dk.rlunde.backgammon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.ui.screens.GameScreen
import dk.rlunde.backgammon.ui.screens.SetupScreen
import dk.rlunde.backgammon.ui.theme.BackgammonTheme
import dk.rlunde.backgammon.viewmodel.GameViewModel

private enum class Screen { SETUP, GAME }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BackgammonTheme {
                val vm: GameViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf(Screen.SETUP) }
                when (screen) {
                    Screen.SETUP -> SetupScreen(onStart = { config: GameConfig ->
                        vm.startGame(config)
                        screen = Screen.GAME
                    })
                    Screen.GAME -> GameScreen(vm = vm, onNewGame = {
                        vm.onNewGame()
                        screen = Screen.SETUP
                    })
                }
            }
        }
    }
}
```
Note: `GameScreen` gains an `onNewGame` callback param (Task 11) so the GAME_OVER "New game" button returns to SETUP.

- [ ] **Step 3: Verify compile** (will fail until Task 11 adds `onNewGame` to `GameScreen` — that's expected; do Task 11 next, then compile both together)

- [ ] **Step 4: Commit** (after Task 11 compiles — or commit now and let Task 11 fix the signature; prefer committing together)

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/screens/SetupScreen.kt app/src/main/java/dk/rlunde/backgammon/MainActivity.kt
git commit -m "feat(app): add SetupScreen + MainActivity SETUP/GAME navigation"
```

---

## Task 11: `GameScreen` AI-turn presentation

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt`

- [ ] **Step 1: Add an `onNewGame` param and AI-turn handling**

Change the signature:
```kotlin
@Composable
fun GameScreen(vm: GameViewModel = viewModel(), onNewGame: () -> Unit = {}) {
```
Compute whether it is the AI's turn from the snapshot (near the top, after `val state by vm.uiState.collectAsState()`):
```kotlin
    val isAiTurn = state.aiSide != null && state.toMove == state.aiSide
```
**Suppress the auto-pass dialog on the AI's turn** — change the events collector:
```kotlin
    LaunchedEffect(Unit) {
        vm.events.collect { if (it is UiEvent.NoLegalMoves && !isAiTurn) showPass = true }
    }
```
(Capture `isAiTurn` inside the collect via `state` if the lint complains — read `vm.uiState.value.let { it.aiSide != null && it.toMove == it.aiSide }` at collect time.)

In the `TrackingPanel` controls area, when `isAiTurn`, render the thinking state instead of the buttons:
```kotlin
        when {
            isAiTurn -> Text("AI thinking…", style = MaterialTheme.typography.titleMedium)
            else -> when (state.phase) {
                Phase.NEED_ROLL -> Button(onClick = onRoll, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White)) { Text("Roll") }
                Phase.MOVING -> Button(onClick = onUndo, modifier = Modifier.fillMaxWidth()) { Text("Undo") }
                Phase.COMMITTABLE -> {
                    Button(onClick = onCommit, modifier = Modifier.fillMaxWidth()) { Text("Commit") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onUndo, modifier = Modifier.fillMaxWidth()) { Text("Undo") }
                }
                Phase.GAME_OVER -> Button(onClick = onNewGame, modifier = Modifier.fillMaxWidth()) { Text("New game") }
            }
        }
```
Thread `isAiTurn` and `onNewGame` into `TrackingPanel` (add them as params; the GAME_OVER button now calls `onNewGame` instead of the old `vm::onNewGame`). The dice (`DiceRow`) still render above the controls, so the AI's dice show while "AI thinking…" is displayed. Pass `isAiTurn` from `GameScreen` into `TrackingPanel`.

- [ ] **Step 2: Verify compile + the whole debug build**

```
./gradlew :app:assembleDebug --console=plain 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt
git commit -m "feat(app): GameScreen AI-turn panel (thinking state, pass-dialog suppressed)"
```

---

## Task 12: Full verification + emulator smoke (acceptance)

**Files:** none (verification only).

- [ ] **Step 1: Run all JVM suites**

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :core:test :ai:test :app:testDebugUnitTest --console=plain 2>&1 | tail -15
```
Expected: all green — `:core` (60), `:ai` (ShotTable/Features/Evaluator/MoveSearch/HeuristicAiPlayer incl. strength smoke), `:app` (GameUiState, GameController +AI tests, AiTurnDriver, BoardGeometry).

- [ ] **Step 2: Confirm module purity + offline**

```
./gradlew :ai:dependencies --configuration compileClasspath 2>&1 | grep -iE "android|coroutines" || echo ":ai is pure (no android/coroutines) — good"
./gradlew :core:dependencies --configuration compileClasspath 2>&1 | grep -i android || echo ":core android-free — good"
grep -i "INTERNET" app/src/main/AndroidManifest.xml || echo "no INTERNET permission — good"
```

- [ ] **Step 3: Install + emulator smoke** (boot Pixel_9_Pro if needed; allow generous time on the software GPU)

```
SDK="/c/Users/RasmusLundgaardHanse/AppData/Local/Android/Sdk"
./gradlew :app:installDebug --console=plain 2>&1 | tail -5
"$SDK/platform-tools/adb.exe" shell am start -n dk.rlunde.backgammon/.MainActivity
```
Verify each (spec §7 acceptance):
- **(a) Setup** shows; choose **Computer / Intermediate / White / Start** → board appears, "WHITE to move", red Roll.
- **(b) AI turn:** roll + commit your move → "AI thinking…" shows in the panel, the AI's dice appear (~0.6s), its move resolves, turn returns to you. **No "No legal moves" dialog ever appears on the AI's turn.**
- **(c) Beginner:** New game → Beginner → confirm it still plays (occasionally a visibly weaker move).
- **(d) Play as Black:** New game → Black → the AI (White) moves first.
- **(e) Hot-seat:** New game → Hot-seat → plays exactly as Phase 1 (no AI affordances), both colours.
- **(f) New game mid-think:** during "AI thinking…", tap New game → returns to setup cleanly, no stuck thinking.
- **(g) Win:** play a game to completion vs the AI → correct win banner, no lingering "thinking".

- [ ] **Step 4: Commit any tuning** made during smoke (e.g. weight tweaks, pacing)

```bash
git add -A
git commit -m "polish(ai): tune weights/pacing after emulator smoke"
```

---

## Self-review (completed by plan author)

**Spec coverage:** §3 module/deps + purity → Task 0, 6, 12. §4.1 AiPlayer (non-suspend, legal/perspective contract) → Task 5. §4.2 Evaluator (9 differenced features, blot cost-weighting, anchors, phase switch, pip reuse) → Tasks 2–3. §4.3 ShotTable (corrected counts, independent test, approximation note) → Task 1. §4.4 MoveSearch (1-ply greedy, deterministic, hit-preference) → Task 4. §4.5 Difficulty (no plies) + HeuristicAiPlayer (top-K weak branch, seeded) → Task 5. §5.1 GameController (aiSide, applyMove, side-gating, hot-seat invariant) + GameUiState fields → Task 6. §5.2 VM (idempotent startGame, try/finally via driver, job cancel, legalMoves off-main, publish overlay, no dialog on AI turn) → Tasks 8–9. §5.3 GameConfig (random in VM) → Tasks 7, 9. §6 SetupScreen + MainActivity + AI-turn panel + pass-dialog suppression → Tasks 10–11. §7 tests: zero-sum/hit-pref/per-feature/phase/shot-table/MoveSearch/noise/strength → Tasks 1–5; controller applyMove/win/roll-not-gated/hot-seat → Task 6; AiTurnDriver branches incl. finally/no-AI-call-on-pass/not-AI-turn → Task 8; emulator acceptance → Task 12. No gaps.

**Placeholder scan:** Task 0 carries an explicit "open core/build.gradle.kts and replicate" instruction (toolchain specifics can't be guessed blind) — not a TBD. Task 3 shows the corrected differenced `return` explicitly. All code blocks are complete; no TODO/"handle later". Compose snippet edits in Task 11 are diffs against the existing file with exact replacement code.

**Type consistency:** `Evaluator.evaluate(state, perspective, weights)`, `MoveSearch.bestMove(state, dice, legal, weights)`, `Features.*(state, perspective)`, `ShotTable.hitProbability(distance)`, `Weights.FULL/SIMPLIFIED`, `Difficulty.{BEGINNER,INTERMEDIATE}` (weights/noise, no plies), `HeuristicAiPlayer(difficulty, rng)`, `AiPlayer.chooseMove` (non-suspend), `GameController(initial, roller, aiSide)` + `applyMove`, `GameUiState.{aiThinking, aiSide}`, `GameConfig(difficulty, humanColor?, opponent)`, `AiTurnDriver(aiSide, ai, dispatcher, diceVisibleMs, paceMs).maybeRunTurn(controller, onThinking, publish)`, `GameViewModel.startGame/onNewGame`, `GameScreen(vm, onNewGame)` are used consistently across tasks.
