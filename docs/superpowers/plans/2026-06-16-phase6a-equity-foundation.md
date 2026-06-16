# Phase 6a — Equity Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compute a calibrated, cubeless money equity (with gammon/backgammon rates) for any position and surface it read-only in the trainer.

**Architecture:** Pure `:ai` logic — a `GamePhase` classifier, an `OutcomeDistribution` value type, per-phase calibrated win-probability, and a feature-based gammon/backgammon model assembled by `EquityModel`. Model constants are fit **offline** by a gated self-play harness and committed as constants. The trainer's existing `MoveAnalysis` carries the position's distribution, so the detail sheet renders it with no new ViewModel plumbing.

**Tech Stack:** Kotlin, Gradle multi-module (`:core`, `:ai`, `:app`), kotlin.test/JUnit, Jetpack Compose (trainer UI).

**Spec:** `docs/superpowers/specs/2026-06-16-phase6a-equity-foundation-design.md`

**Build/test commands** (see memory `build-environment.md` for JDK/SDK env):
- `:ai` unit tests: `./gradlew :ai:test`
- single test class: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.OutcomeDistributionTest"`
- `:app` tests: `./gradlew :app:testDebugUnitTest`
- gated calibration harness: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EquityCalibrationTest" -Dbackgammon.calibrate=true`

---

## Design notes locked during planning

- **Equity rides on `MoveAnalysis`.** The spec said "VM computes equity"; while planning we found the trainer already plumbs `MoveAnalysis` through `publish()`/`uiState.copy(...)` with the epoch/staleness guard. Adding the position's `OutcomeDistribution` to `MoveAnalysis` (computed in `MoveAnalyzer`, which already has the boards) satisfies the spec's intent — read-only, no `GameController` involvement, stale-guarded like `analysis` — with strictly less code than a parallel VM field. This is the only deviation from the spec's wording.
- **Two win-prob inputs, same scale.** `winProbDrop` is derived from the *searched* ranking scores (per-phase win-prob); `positionEquity` is derived from the *static* eval of the played board via `EquityModel`. Both are eval-score-scaled; `K` is fit on static eval and applied to searched scores as the existing "2-ply opinion" approximation already does. Acceptable for a read-only display.
- **Reference players for calibration:** `Difficulty.ADVANCED` (`Weights.FULL`, depth 1, noise 0 → deterministic), with the per-game seed scheme from `BenchmarkTest`. `EquityModel` evaluates with `Weights.FULL` to match.
- **Perspective convention:** distribution is taken from a given `perspective`; swapping perspective mirrors win↔lose components and negates `cubelessEquity` (matches `Evaluator` perspective, phase3 spec §4.4).

---

## Task 1: `GamePhase` classifier

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/GamePhase.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/GamePhaseTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import dk.rlunde.backgammon.core.BoardState
import kotlin.test.Test
import kotlin.test.assertEquals

class GamePhaseTest {
    @Test fun `starting position is contact`() {
        assertEquals(GamePhase.CONTACT, GamePhases.of(startingPosition()))
    }

    @Test fun `fully separated position is race`() {
        // WHITE all on point 1, BLACK all on point 24 -> whiteBack(1) < blackBack(24): no contact.
        var s = BoardState.empty()
        s = s.withCount(Player.WHITE, 1, 15)
        s = s.withCount(Player.BLACK, 24, 15)
        assertEquals(GamePhase.RACE, GamePhases.of(s))
    }
}
```

> If `BoardState.empty()` / `withCount(...)` are not the exact constructors in `core/BoardState.kt`, replace with whatever the existing tests (e.g. `BoardStateTest.kt`, `PipCountTest.kt`) use to build a board. Check `core/src/test/kotlin/dk/rlunde/backgammon/core/` for the established builder before writing this test.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.GamePhaseTest"`
Expected: FAIL — `GamePhase` / `GamePhases` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState

/** Coarse phase bucket for per-phase equity calibration (spec §4.1). BEAROFF intentionally deferred. */
enum class GamePhase { CONTACT, RACE }

internal object GamePhases {
    fun of(state: BoardState): GamePhase =
        if (Features.noContact(state)) GamePhase.RACE else GamePhase.CONTACT
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.GamePhaseTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/GamePhase.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/GamePhaseTest.kt
git commit -m "feat(ai): GamePhase classifier (contact/race) for equity calibration"
```

---

## Task 2: `OutcomeDistribution` value type

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/OutcomeDistribution.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/OutcomeDistributionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutcomeDistributionTest {
    @Test fun `win and lose probabilities partition to 1`() {
        val d = OutcomeDistribution(0.40, 0.15, 0.05, 0.25, 0.10, 0.05)
        assertEquals(0.60, d.winProb, 1e-9)
        assertEquals(0.40, d.loseProb, 1e-9)
    }

    @Test fun `cubeless equity weights gammon x2 and backgammon x3`() {
        // pure single win
        assertEquals(1.0, OutcomeDistribution(1.0, 0.0, 0.0, 0.0, 0.0, 0.0).cubelessEquity, 1e-9)
        // pure single loss
        assertEquals(-1.0, OutcomeDistribution(0.0, 0.0, 0.0, 1.0, 0.0, 0.0).cubelessEquity, 1e-9)
        // 50% single win, 50% backgammon loss => 1*0.5 - 3*0.5
        assertEquals(0.5 - 1.5, OutcomeDistribution(0.5, 0.0, 0.0, 0.0, 0.0, 0.5).cubelessEquity, 1e-9)
    }

    @Test fun `equity stays within plus minus three`() {
        val d = OutcomeDistribution(0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        assertTrue(d.cubelessEquity in -3.0..3.0)
        assertEquals(3.0, d.cubelessEquity, 1e-9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.OutcomeDistributionTest"`
Expected: FAIL — `OutcomeDistribution` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dk.rlunde.backgammon.ai

/**
 * Full 6-way outcome distribution from one side's perspective; components are probabilities that
 * sum to 1 (within epsilon). [cubelessEquity] is money equity in points, ∈ [−3, +3]. See spec §4.2.
 */
data class OutcomeDistribution(
    val winSingle: Double,
    val winGammon: Double,
    val winBackgammon: Double,
    val loseSingle: Double,
    val loseGammon: Double,
    val loseBackgammon: Double,
) {
    val winProb: Double get() = winSingle + winGammon + winBackgammon
    val loseProb: Double get() = loseSingle + loseGammon + loseBackgammon

    val cubelessEquity: Double get() =
        (winSingle + 2 * winGammon + 3 * winBackgammon) -
        (loseSingle + 2 * loseGammon + 3 * loseBackgammon)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.OutcomeDistributionTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/OutcomeDistribution.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/OutcomeDistributionTest.kt
git commit -m "feat(ai): OutcomeDistribution type with cubeless equity"
```

---

## Task 3: Per-phase calibrated win probability

Rework `WinProbability` to take a `GamePhase` (per-phase slope `K`), keeping it intercept-free (symmetric about 0.5 at eval 0). Update the single call site in `MoveAnalyzer` so the build stays green and `winProbDrop` becomes per-phase.

**Files:**
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/WinProbability.kt`
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzer.kt:72-73`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/WinProbabilityTest.kt` (exists — extend it)

- [ ] **Step 1: Write/replace the failing test**

Open `ai/src/test/kotlin/dk/rlunde/backgammon/ai/WinProbabilityTest.kt`. Replace any single-arg `fromEquity` calls and add:

```kotlin
@Test fun `even position is 50 percent in every phase`() {
    for (phase in GamePhase.entries) {
        assertEquals(0.5, WinProbability.fromEquity(0.0, phase), 1e-9)
    }
}

@Test fun `win prob is monotonic in equity within a phase`() {
    val lo = WinProbability.fromEquity(-5.0, GamePhase.CONTACT)
    val mid = WinProbability.fromEquity(0.0, GamePhase.CONTACT)
    val hi = WinProbability.fromEquity(5.0, GamePhase.CONTACT)
    assertTrue(lo < mid && mid < hi)
}

@Test fun `symmetric about half`() {
    val p = WinProbability.fromEquity(3.0, GamePhase.RACE)
    val q = WinProbability.fromEquity(-3.0, GamePhase.RACE)
    assertEquals(1.0, p + q, 1e-9)
}
```

Ensure imports include `kotlin.test.assertTrue`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.WinProbabilityTest"`
Expected: FAIL — `fromEquity(Double, GamePhase)` does not exist.

- [ ] **Step 3: Implement**

Replace `WinProbability.kt` body:

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.math.exp

/**
 * Per-phase logistic squash of an eval score into a single-win probability in [0,1], measured from the
 * side whose eval is taken, symmetric about 0.5 (intercept-free). [K] is fitted OFFLINE by
 * EquityCalibrationTest (spec §5) and committed below. See spec §4.3.
 */
internal object WinProbability {
    // Fitted offline (Task 9). Until then these are the provisional Phase-3 value for both phases.
    internal val K: Map<GamePhase, Double> = mapOf(
        GamePhase.CONTACT to 0.1,
        GamePhase.RACE to 0.1,
    )

    fun fromEquity(equity: Double, phase: GamePhase): Double =
        1.0 / (1.0 + exp(-K.getValue(phase) * equity))
}
```

In `MoveAnalyzer.kt`, the played board is already `playedBoard` (line ~45). Add the best board and pass phases. Replace the `winProbDrop` computation (lines 72-73):

```kotlin
val bestBoard = MoveGenerator.apply(state, best.move)
val winProbDrop: Double? = if (suppressed) null
    else WinProbability.fromEquity(best.score, GamePhases.of(bestBoard)) -
         WinProbability.fromEquity(played.score, GamePhases.of(playedBoard))
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.WinProbabilityTest" --tests "dk.rlunde.backgammon.ai.MoveAnalyzer*"`
Expected: PASS (existing MoveAnalyzer tests still green — `winProbDrop` is still non-null/zero where it was, just per-phase).

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/WinProbability.kt ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzer.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/WinProbabilityTest.kt
git commit -m "feat(ai): per-phase win probability; trainer uses phase-aware win% drop"
```

---

## Task 4: Gammon/backgammon conditional-rate model (pure function)

A feature-based logistic giving, for a side that **loses**, P(it is gammoned) and P(it is backgammoned), with `P(bg) ≤ P(gammon)` enforced. Coefficients are arguments now (committed defaults filled in Task 9).

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/GammonModel.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/GammonModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class GammonModelTest {
    // features: loserBorneOff, loserPip, loserBackContact
    @Test fun `borne-off loser has near-zero gammon risk`() {
        val r = GammonModel.loseRates(GammonFeatures(borneOff = 3, pip = 40, backContact = 0))
        assertTrue(r.gammon < 0.05, "borne-off >0 must crush gammon risk: ${r.gammon}")
        assertTrue(r.backgammon <= r.gammon)
    }

    @Test fun `backgammon never exceeds gammon`() {
        val r = GammonModel.loseRates(GammonFeatures(borneOff = 0, pip = 160, backContact = 2))
        assertTrue(r.backgammon <= r.gammon)
        assertTrue(r.gammon in 0.0..1.0 && r.backgammon in 0.0..1.0)
    }

    @Test fun `more trapped checkers raises backgammon risk`() {
        val none = GammonModel.loseRates(GammonFeatures(0, 150, 0))
        val some = GammonModel.loseRates(GammonFeatures(0, 150, 3))
        assertTrue(some.backgammon >= none.backgammon)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.GammonModelTest"`
Expected: FAIL — `GammonModel` / `GammonFeatures` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.math.exp
import kotlin.math.min

/** Inputs to the gammon model, describing the side that is assumed to LOSE. */
data class GammonFeatures(val borneOff: Int, val pip: Int, val backContact: Int)

/** Conditional rates given that side loses: P(gammoned), P(backgammoned). */
data class GammonRates(val gammon: Double, val backgammon: Double)

/**
 * Feature-based logistic for gammon/backgammon rates, conditioned on a side losing (spec §4.4).
 * Coefficients are fitted OFFLINE (Task 9) and committed in [GAMMON_COEFFS] / [BG_COEFFS].
 * A borne-off checker makes a gammon impossible — hard-gated to 0 rather than trusted to the logistic.
 */
internal object GammonModel {
    // {intercept, wBorneOff, wPip, wBackContact}. Provisional until Task 9 replaces them.
    internal var GAMMON_COEFFS = doubleArrayOf(-2.0, -3.0, 0.02, 0.1)
    internal var BG_COEFFS = doubleArrayOf(-5.0, -3.0, 0.01, 0.6)

    private fun logistic(c: DoubleArray, f: GammonFeatures): Double =
        1.0 / (1.0 + exp(-(c[0] + c[1] * f.borneOff + c[2] * f.pip + c[3] * f.backContact)))

    fun loseRates(f: GammonFeatures): GammonRates {
        if (f.borneOff > 0) return GammonRates(0.0, 0.0) // gammon impossible once a checker is off
        val g = logistic(GAMMON_COEFFS, f).coerceIn(0.0, 1.0)
        val bg = logistic(BG_COEFFS, f).coerceIn(0.0, 1.0)
        return GammonRates(g, min(bg, g)) // backgammon is a stricter gammon
    }
}
```

> The provisional coefficients only need to satisfy the *shape* invariants the tests assert (monotone in back-contact, bg ≤ gammon, borne-off gate). Real values land in Task 9. If a sign makes a test fail, flip it — the test encodes the intended monotonicity.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.GammonModelTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/GammonModel.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/GammonModelTest.kt
git commit -m "feat(ai): feature-based gammon/backgammon conditional-rate model"
```

---

## Task 5: Extract gammon features from a board

Given a board and the side assumed to lose, build `GammonFeatures`. Reuses `Scoring.pipCount`. Back-contact = loser checkers on the bar plus loser checkers inside the winner's home board.

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/GammonFeaturesOf.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/GammonFeaturesOfTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.Scoring
import kotlin.test.Test
import kotlin.test.assertEquals

class GammonFeaturesOfTest {
    @Test fun `loser back-contact counts bar plus checkers in winner home`() {
        // Winner = WHITE (home 1..6). Loser = BLACK with a checker on the bar and two on point 3.
        var s = BoardState.empty()
        s = s.withBar(Player.BLACK, 1)
        s = s.withCount(Player.BLACK, 3, 2)   // inside WHITE's home (1..6)
        s = s.withCount(Player.BLACK, 13, 12)
        val f = gammonFeaturesOf(s, loser = Player.BLACK)
        assertEquals(3, f.backContact)        // 1 on bar + 2 on point 3
        assertEquals(0, f.borneOff)
        assertEquals(Scoring.pipCount(s, Player.BLACK), f.pip)
    }
}
```

> Adjust `withBar` / `withCount` / `empty` to the real `core/BoardState.kt` API (same builder Task 1 used).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.GammonFeaturesOfTest"`
Expected: FAIL — `gammonFeaturesOf` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.Scoring

/** Winner's home board: where a trapped loser checker risks a backgammon. */
private fun winnerHome(winner: Player): IntRange = if (winner == Player.WHITE) 1..6 else 19..24

/** Build [GammonFeatures] for the side assumed to LOSE (spec §4.4). */
internal fun gammonFeaturesOf(s: BoardState, loser: Player): GammonFeatures {
    val winner = loser.opponent
    val backContact = s.barCount(loser) + winnerHome(winner).sumOf { s.count(loser, it) }
    return GammonFeatures(
        borneOff = s.offCount(loser),
        pip = Scoring.pipCount(s, loser),
        backContact = backContact,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.GammonFeaturesOfTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/GammonFeaturesOf.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/GammonFeaturesOfTest.kt
git commit -m "feat(ai): extract gammon features (borne-off, pip, back-contact) from a board"
```

---

## Task 6: `EquityModel.distribution` assembly

Compose win-prob (Task 3), gammon rates for each side (Tasks 4–5), and the eval score into a normalized `OutcomeDistribution`.

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/EquityModel.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/EquityModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EquityModelTest {
    private val w = Weights.FULL

    @Test fun `components are non-negative and sum to one`() {
        val d = EquityModel.distribution(startingPosition(), Player.WHITE, w)
        val sum = d.winSingle + d.winGammon + d.winBackgammon +
                  d.loseSingle + d.loseGammon + d.loseBackgammon
        assertEquals(1.0, sum, 1e-6)
        listOf(d.winSingle, d.winGammon, d.winBackgammon, d.loseSingle, d.loseGammon, d.loseBackgammon)
            .forEach { assertTrue(it >= 0.0, "component negative: $it") }
    }

    @Test fun `symmetric start is roughly even`() {
        val d = EquityModel.distribution(startingPosition(), Player.WHITE, w)
        assertEquals(0.5, d.winProb, 1e-6)         // eval differential is 0 at the start
        assertEquals(0.0, d.cubelessEquity, 1e-6)
    }

    @Test fun `swapping perspective negates equity`() {
        val white = EquityModel.distribution(startingPosition(), Player.WHITE, w)
        val black = EquityModel.distribution(startingPosition(), Player.BLACK, w)
        assertEquals(white.cubelessEquity, -black.cubelessEquity, 1e-6)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EquityModelTest"`
Expected: FAIL — `EquityModel` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player

/**
 * Assembles the full outcome distribution for [perspective] from the static eval score: per-phase
 * win probability (§4.3) split by the gammon/backgammon conditional rates of each prospective loser
 * (§4.4–§4.5). Allocation-light; safe to call per move on the UI path.
 */
object EquityModel {
    fun distribution(state: BoardState, perspective: Player, weights: Weights): OutcomeDistribution {
        val eval = Evaluator.evaluate(state, perspective, weights)
        val phase = GamePhases.of(state)
        val winProb = WinProbability.fromEquity(eval, phase)
        val loseProb = 1.0 - winProb

        // If WE win, the opponent loses; if WE lose, we lose.
        val winRates = GammonModel.loseRates(gammonFeaturesOf(state, loser = perspective.opponent))
        val loseRates = GammonModel.loseRates(gammonFeaturesOf(state, loser = perspective))

        return OutcomeDistribution(
            winSingle = winProb * (1.0 - winRates.gammon),
            winGammon = winProb * (winRates.gammon - winRates.backgammon),
            winBackgammon = winProb * winRates.backgammon,
            loseSingle = loseProb * (1.0 - loseRates.gammon),
            loseGammon = loseProb * (loseRates.gammon - loseRates.backgammon),
            loseBackgammon = loseProb * loseRates.backgammon,
        )
    }
}
```

> `GammonRates` guarantees `backgammon ≤ gammon ≤ 1`, so each component is ≥ 0 and the six sum to `winProb + loseProb = 1`. No extra normalization needed.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EquityModelTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/EquityModel.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/EquityModelTest.kt
git commit -m "feat(ai): EquityModel assembles calibrated outcome distribution"
```

---

## Task 7: Carry the position's equity on `MoveAnalysis`

Add `positionEquity: OutcomeDistribution?` (the played position from the human's perspective, suppressed exactly like `winProbDrop`). This is what the detail sheet renders.

**Files:**
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalysis.kt`
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzer.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzerTest.kt` (exists — extend)

- [ ] **Step 1: Write the failing test**

Add to `MoveAnalyzerTest.kt`:

```kotlin
@Test fun `analysis carries position equity from the human perspective`() {
    // Use any existing non-terminal fixture in this test file (board + dice + a legal played move).
    // Reuse the setup of an existing test; assert equity is present and win-prob is in range.
    val analysis = MoveAnalyzer.analyze(preBoard, dice, playedMove, legal)
    val eq = analysis.positionEquity
    assertNotNull(eq)
    assertTrue(eq!!.winProb in 0.0..1.0)
}
```

> Reuse the exact `preBoard`/`dice`/`playedMove`/`legal` construction from a neighbouring non-terminal test in this file rather than inventing a new fixture. Add `kotlin.test.assertNotNull` import.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.MoveAnalyzerTest"`
Expected: FAIL — `MoveAnalysis` has no `positionEquity`.

- [ ] **Step 3: Implement**

In `MoveAnalysis.kt`, add the field (keep the KDoc note about suppression):

```kotlin
data class MoveAnalysis(
    val band: Band,
    val playedRank: Int,
    val tiedForBest: Boolean,
    val totalCandidates: Int,
    val evalLoss: Double,
    val winProbDrop: Double?,
    /** Calibrated outcome distribution of the played position (human perspective); null when suppressed. */
    val positionEquity: OutcomeDistribution?,
    val terminal: Boolean,
    val best: AnalyzedPlay,
    val played: AnalyzedPlay,
    val featureDeltas: List<FeatureDelta>,
    val forced: Boolean,
)
```

In `MoveAnalyzer.analyzeRanked`, compute it next to `winProbDrop` (the human side is `state.toMove`; `playedBoard` already exists):

```kotlin
val positionEquity: OutcomeDistribution? = if (suppressed) null
    else EquityModel.distribution(playedBoard, state.toMove, REFERENCE_WEIGHTS)
```

Add `positionEquity = positionEquity,` to the `MoveAnalysis(...)` constructor call.

- [ ] **Step 4: Run tests**

Run: `./gradlew :ai:test`
Expected: PASS (all `:ai` tests, including the extended MoveAnalyzer test).

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalysis.kt ai/src/main/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzer.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/MoveAnalyzerTest.kt
git commit -m "feat(ai): MoveAnalysis carries calibrated position equity"
```

---

## Task 8: Offline calibration harness (gated)

A gated test that (1) plays self-play games recording per-ply samples, (2) labels each by the mover's eventual outcome, (3) fits per-phase `K` and the gammon/bg coefficients, (4) prints them plus diagnostics. **Not run per-commit.**

**Files:**
- Modify: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/SelfPlay.kt` (add a trajectory-recording variant)
- Create: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CalibrationFit.kt` (pure fitters)
- Create: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CalibrationFitTest.kt`
- Create: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/EquityCalibrationTest.kt`

- [ ] **Step 1: Write the failing test for the fitters (these are unit-testable without self-play)**

`CalibrationFitTest.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.math.abs

class CalibrationFitTest {
    @Test fun `fitK recovers a known slope on separable data`() {
        // Generate label = 1 when logistic(0.3*x) > 0.5-ish; fitK should land near 0.3.
        val xs = (-50..50).map { it / 10.0 }
        val trueK = 0.3
        val samples = xs.map { x -> x to (1.0 / (1.0 + Math.exp(-trueK * x)) >= 0.5) }
        val k = CalibrationFit.fitK(samples)
        assertTrue(abs(k - trueK) < 0.25, "fitK off: $k vs $trueK")
    }

    @Test fun `logLoss rewards a better slope`() {
        val xs = (-50..50).map { it / 10.0 }
        val samples = xs.map { x -> x to (x >= 0.0) }
        val good = CalibrationFit.logLoss(samples, 0.4)
        val flat = CalibrationFit.logLoss(samples, 0.0) // 0.5 everywhere
        assertTrue(good < flat, "a real slope should beat the 50/50 baseline")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.CalibrationFitTest"`
Expected: FAIL — `CalibrationFit` unresolved.

- [ ] **Step 3: Implement the fitters**

`CalibrationFit.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.math.exp
import kotlin.math.ln

/** Pure, deterministic fitters for offline equity calibration (spec §5). No RNG, no I/O. */
internal object CalibrationFit {
    private const val EPS = 1e-12
    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))

    /** Mean negative log-likelihood of the intercept-free 1-feature logistic with slope [k]. */
    fun logLoss(samples: List<Pair<Double, Boolean>>, k: Double): Double {
        if (samples.isEmpty()) return 0.0
        var s = 0.0
        for ((x, y) in samples) {
            val p = sigmoid(k * x).coerceIn(EPS, 1 - EPS)
            s += if (y) -ln(p) else -ln(1 - p)
        }
        return s / samples.size
    }

    /** 1-D gradient descent for the intercept-free slope (win-prob calibration). */
    fun fitK(samples: List<Pair<Double, Boolean>>, iters: Int = 2000, lr: Double = 0.01): Double {
        var k = 0.0
        for (i in 0 until iters) {
            var grad = 0.0
            for ((x, y) in samples) {
                val p = sigmoid(k * x)
                grad += (p - if (y) 1.0 else 0.0) * x
            }
            k -= lr * grad / samples.size
        }
        return k
    }

    /** Batch gradient descent for a logistic with intercept over [featuresPerSample] inputs. */
    fun fitLogistic(
        rows: List<Pair<DoubleArray, Boolean>>,
        featuresPerSample: Int,
        iters: Int = 4000,
        lr: Double = 0.05,
    ): DoubleArray {
        val w = DoubleArray(featuresPerSample + 1) // [0] = intercept
        if (rows.isEmpty()) return w
        for (i in 0 until iters) {
            val grad = DoubleArray(w.size)
            for ((f, y) in rows) {
                var z = w[0]
                for (j in 0 until featuresPerSample) z += w[j + 1] * f[j]
                val d = sigmoid(z) - if (y) 1.0 else 0.0
                grad[0] += d
                for (j in 0 until featuresPerSample) grad[j + 1] += d * f[j]
            }
            for (j in w.indices) w[j] -= lr * grad[j] / rows.size
        }
        return w
    }
}
```

- [ ] **Step 4: Run to verify the fitters pass**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.CalibrationFitTest"`
Expected: PASS

- [ ] **Step 5: Add the trajectory recorder to `SelfPlay.kt`**

Append to `ai/src/test/kotlin/dk/rlunde/backgammon/ai/SelfPlay.kt`:

```kotlin
import dk.rlunde.backgammon.core.Player

/** One labelled ply: the mover's post-move static eval, phase, and gammon features for each side. */
data class CalibrationSample(
    val mover: Player,
    val eval: Double,
    val phase: GamePhase,
    val winnerFeatures: GammonFeatures, // opponent-as-loser features (if mover wins)
    val loserFeatures: GammonFeatures,  // mover-as-loser features (if mover loses)
)

data class CalibrationGame(val samples: List<CalibrationSample>, val result: GameResult)

/** Like [selfPlay] but records a sample after each non-pass move, for offline calibration. */
fun selfPlayTrajectory(
    white: AiPlayer, black: AiPlayer, roller: DiceRoller,
    weights: Weights, maxTurns: Int = 2000,
): CalibrationGame {
    var state = startingPosition()
    var turns = 0
    val samples = ArrayList<CalibrationSample>()
    while (!Scoring.isGameOver(state) && turns < maxTurns) {
        val dice = roller.roll()
        val legal = MoveGenerator.legalMoves(state, dice)
        if (legal.isEmpty()) { state = MoveGenerator.pass(state); turns++; continue }
        val mover = state.toMove
        val ai = if (mover == Player.WHITE) white else black
        state = MoveGenerator.apply(state, ai.chooseMove(state, dice, legal))
        samples.add(
            CalibrationSample(
                mover = mover,
                eval = Evaluator.evaluate(state, mover, weights),
                phase = GamePhases.of(state),
                winnerFeatures = gammonFeaturesOf(state, loser = mover.opponent),
                loserFeatures = gammonFeaturesOf(state, loser = mover),
            )
        )
        turns++
    }
    val wv = Scoring.winnerAndValue(state)
    val result = if (wv == null) GameResult(Outcome.TIMEOUT, 0, turns)
        else GameResult(if (wv.first == Player.WHITE) Outcome.WHITE_WIN else Outcome.BLACK_WIN, wv.second, turns)
    return CalibrationGame(samples, result)
}
```

- [ ] **Step 6: Write the gated harness**

`EquityCalibrationTest.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.SeededDiceRoller
import kotlin.random.Random
import kotlin.test.Test

/**
 * OFFLINE equity calibration (spec §5). Gated: run only with -Dbackgammon.calibrate=true.
 *   ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EquityCalibrationTest" -Dbackgammon.calibrate=true
 * Prints fitted per-phase K and gammon/bg coefficients + diagnostics; copy them into
 * WinProbability.K and GammonModel.GAMMON_COEFFS/BG_COEFFS (Task 9).
 */
class EquityCalibrationTest {
    private val enabled = System.getProperty("backgammon.calibrate") == "true"
    private val games = 400
    private val weights = Weights.FULL
    private fun ref(seed: Long) = HeuristicAiPlayer(Difficulty.ADVANCED, Random(seed))

    @Test fun `fit and print equity model constants`() {
        if (!enabled) return

        // Per-phase (eval, didWin) and conditional gammon rows.
        val winByPhase = GamePhase.entries.associateWith { ArrayList<Pair<Double, Boolean>>() }
        val winGam = ArrayList<Pair<DoubleArray, Boolean>>(); val winBg = ArrayList<Pair<DoubleArray, Boolean>>()
        val loseGam = ArrayList<Pair<DoubleArray, Boolean>>(); val loseBg = ArrayList<Pair<DoubleArray, Boolean>>()

        for (g in 0 until games) {
            val seed = (g + 1).toLong() * 2_654_435_761L
            val cg = selfPlayTrajectory(ref(seed xor WHITE_SALT), ref(seed xor BLACK_SALT), SeededDiceRoller(seed), weights)
            if (cg.result.outcome == Outcome.TIMEOUT) continue
            val winner = if (cg.result.outcome == Outcome.WHITE_WIN) Player.WHITE else Player.BLACK
            val value = cg.result.value // 1/2/3
            for (s in cg.samples) {
                val moverWon = s.mover == winner
                winByPhase.getValue(s.phase).add(s.eval to moverWon)
                fun row(f: GammonFeatures) = doubleArrayOf(f.borneOff.toDouble(), f.pip.toDouble(), f.backContact.toDouble())
                if (moverWon) {            // opponent is the loser -> winnerFeatures describe the loser
                    winGam.add(row(s.winnerFeatures) to (value >= 2))
                    winBg.add(row(s.winnerFeatures) to (value == 3))
                } else {                   // mover is the loser
                    loseGam.add(row(s.loserFeatures) to (value >= 2))
                    loseBg.add(row(s.loserFeatures) to (value == 3))
                }
            }
        }

        val kByPhase = GamePhase.entries.associateWith { CalibrationFit.fitK(winByPhase.getValue(it)) }
        // Gammon/bg coefficients pool win- and lose-side rows (a loss-of-side is a gammon symmetrically).
        val gam = CalibrationFit.fitLogistic(winGam + loseGam, 3)
        val bg = CalibrationFit.fitLogistic(winBg + loseBg, 3)

        println("=== EQUITY CALIBRATION (n=$games games, ref=ADVANCED/FULL) ===")
        kByPhase.forEach { (p, k) ->
            val ll = CalibrationFit.logLoss(winByPhase.getValue(p), k)
            val base = CalibrationFit.logLoss(winByPhase.getValue(p), 0.1)
            println("K[$p] = $k   logLoss=$ll   baseline(K=0.1)=$base")
        }
        println("GAMMON_COEFFS = doubleArrayOf(${gam.joinToString()})")
        println("BG_COEFFS = doubleArrayOf(${bg.joinToString()})")
    }
}
```

- [ ] **Step 7: Verify the harness compiles and is a silent no-op by default**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EquityCalibrationTest"`
Expected: PASS (no-op — `enabled` is false without the flag).

- [ ] **Step 8: Commit**

```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/SelfPlay.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/CalibrationFit.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/CalibrationFitTest.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/EquityCalibrationTest.kt
git commit -m "test(ai): gated offline equity-calibration harness + pure fitters"
```

---

## Task 9: Run calibration, commit fitted constants, add a regression guard

- [ ] **Step 1: Run the gated harness and capture output**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EquityCalibrationTest" -Dbackgammon.calibrate=true -i`
Expected: console prints `K[CONTACT]`, `K[RACE]`, `GAMMON_COEFFS = ...`, `BG_COEFFS = ...`, and per-phase logLoss strictly below the `K=0.1` baseline. Copy the four printed values.

- [ ] **Step 2: Paste the fitted constants in**

In `WinProbability.kt`, replace the `K` map values with the printed `K[CONTACT]` / `K[RACE]`.
In `GammonModel.kt`, replace `GAMMON_COEFFS` and `BG_COEFFS` with the printed arrays. Change `internal var` → `internal val` (no longer mutated).

> If `GammonModelTest` (Task 4) now fails because the *fitted* coefficients change a monotonicity sign, the fit — not the test — is authoritative for real data; re-express that test against the fitted behavior (e.g. compare two realistic feature vectors) rather than hand-picked coefficients. Keep the `bg ≤ gammon` and borne-off-gate assertions (those are structural, enforced in code).

- [ ] **Step 3: Write the non-gated regression guard**

Create `ai/src/test/kotlin/dk/rlunde/backgammon/ai/EquityCalibrationGuardTest.kt` — a small, fast, deterministic sample asserting the committed model beats the flat baseline (guards against constant-rot). Uses depth-0 `INTERMEDIATE` (FULL weights, deterministic) for speed.

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.SeededDiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Fast guard: the committed per-phase K must beat the flat 50/50 baseline on a fixed sample. */
class EquityCalibrationGuardTest {
    private fun ref(seed: Long) = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(seed))

    @Test fun `committed win-prob beats the coin-flip baseline`() {
        val rows = ArrayList<Pair<Double, Boolean>>()
        for (g in 0 until 30) {
            val seed = (g + 1).toLong() * 1_099_511_628_211L
            val cg = selfPlayTrajectory(ref(seed xor WHITE_SALT), ref(seed xor BLACK_SALT), SeededDiceRoller(seed), Weights.FULL)
            if (cg.result.outcome == Outcome.TIMEOUT) continue
            val winner = if (cg.result.outcome == Outcome.WHITE_WIN) Player.WHITE else Player.BLACK
            cg.samples.forEach { rows.add(it.eval to (it.mover == winner)) }
        }
        // Average committed model log-loss across phases vs flat baseline (k=0).
        val committed = rows.map { (x, y) ->
            val p = WinProbability.fromEquity(x, GamePhase.CONTACT) // contact slope as the representative
            if (y) -Math.log(p.coerceIn(1e-12, 1.0)) else -Math.log((1 - p).coerceIn(1e-12, 1.0))
        }.average()
        val baseline = -Math.log(0.5)
        assertTrue(committed < baseline, "committed=$committed should beat 50/50 baseline=$baseline")
    }
}
```

- [ ] **Step 4: Run all `:ai` tests**

Run: `./gradlew :ai:test`
Expected: PASS — including the new guard and the (possibly adjusted) `GammonModelTest`.

- [ ] **Step 5: Record the finalized bars in the spec**

Per spec §7, the exact numeric bars are pinned after the first run. Append to the spec a one-line "Calibration results (2026-06-16)" note with the fitted `K` per phase and the achieved vs baseline log-loss, so the numbers are documented, not folklore.

- [ ] **Step 6: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/WinProbability.kt ai/src/main/kotlin/dk/rlunde/backgammon/ai/GammonModel.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/EquityCalibrationGuardTest.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/GammonModelTest.kt docs/superpowers/specs/2026-06-16-phase6a-equity-foundation-design.md
git commit -m "feat(ai): commit fitted equity-model constants + regression guard"
```

---

## Task 10: Show equity in the trainer detail sheet

Render `positionEquity` in `AnalysisSheet` and replace the uncalibrated footnote. Add a tiny pure formatter (so the string is unit-tested) and wire it in.

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/ui/screens/EquityFormat.kt`
- Create: `app/src/test/java/dk/rlunde/backgammon/ui/EquityFormatTest.kt`
- Modify: `app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt` (`AnalysisSheet`, lines ~87-172)

- [ ] **Step 1: Write the failing formatter test**

`EquityFormatTest.kt`:

```kotlin
package dk.rlunde.backgammon.ui

import dk.rlunde.backgammon.ai.OutcomeDistribution
import dk.rlunde.backgammon.ui.screens.equityLine
import kotlin.test.Test
import kotlin.test.assertEquals

class EquityFormatTest {
    @Test fun `formats win gammon bg and equity`() {
        val d = OutcomeDistribution(0.50, 0.18, 0.02, 0.20, 0.08, 0.02)
        // winProb = .70, gammon (incl bg) = .20, bg = .02, equity ~ +0.62
        assertEquals("Win 70% · G 20% · BG 2% · Eq +0.62", equityLine(d))
    }
}
```

> Confirm the expected equity by computing `d.cubelessEquity` if the literal differs by rounding; match the format string to the asserted output.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.ui.EquityFormatTest"`
Expected: FAIL — `equityLine` unresolved.

- [ ] **Step 3: Implement the formatter**

`EquityFormat.kt`:

```kotlin
package dk.rlunde.backgammon.ui.screens

import dk.rlunde.backgammon.ai.OutcomeDistribution

/** Compact one-line equity readout for the trainer. "G" includes backgammons; "BG" is the subset. */
fun equityLine(d: OutcomeDistribution): String {
    val win = (d.winProb * 100).toInt()
    val g = ((d.winGammon + d.winBackgammon) * 100).toInt()
    val bg = (d.winBackgammon * 100).toInt()
    return "Win %d%% · G %d%% · BG %d%% · Eq %+.2f".format(win, g, bg, d.cubelessEquity)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.ui.EquityFormatTest"`
Expected: PASS

- [ ] **Step 5: Wire it into `AnalysisSheet` and replace the footnote**

In `GameScreen.kt`:

(a) After the win-drop / qualitative `Text` block (after line ~124, before the rank section) add an equity line shown whenever equity is present:

```kotlin
analysis.positionEquity?.let { eq ->
    Spacer(Modifier.height(4.dp))
    Text(
        text = equityLine(eq),
        style = MaterialTheme.typography.bodyMedium,
    )
}
```

Also show it in the `isBestPlay` branch (after the "Best play:" line ~112) using the same `analysis.positionEquity?.let { ... }` block, so the on-demand hint shows equity too.

(b) Replace the footnote text (line ~167):

```kotlin
text = "* win% self-play-calibrated; gammon rates are model estimates (no rollouts)",
```

(c) Add the import: `import dk.rlunde.backgammon.ui.screens.equityLine` is unnecessary if `AnalysisSheet` is in the same `...ui.screens` package (it is) — no import needed.

- [ ] **Step 6: Build and run the app module tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. Then a debug build to confirm Compose compiles: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/screens/EquityFormat.kt app/src/test/java/dk/rlunde/backgammon/ui/EquityFormatTest.kt app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt
git commit -m "feat(app): show calibrated equity in trainer detail sheet"
```

---

## Task 11: Full verification

- [ ] **Step 1: Run the whole suite**

Run: `./gradlew test` (or `:core:test :ai:test :app:testDebugUnitTest`)
Expected: PASS across all modules.

- [ ] **Step 2: Confirm the gated harness still runs**

Run: `./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EquityCalibrationTest" -Dbackgammon.calibrate=true`
Expected: prints constants; logLoss beats baseline.

- [ ] **Step 3: Sanity-check the spec out-of-scope boundaries hold**

Grep confirms no cube/match types crept in: `grep -rniE 'CubeState|MatchState|doubleCube|takePoint' ai app core --include='*.kt'` → no hits. Move selection unchanged (no caller passes equity into `Expectimax`/`chooseMove`).

---

## Self-Review (completed during planning)

**Spec coverage:**
- §2 `GamePhase` → Task 1. `OutcomeDistribution`/`cubelessEquity` → Task 2. `EquityModel` → Task 6. Per-phase calibration → Tasks 3, 8, 9. Gammon/bg model → Tasks 4–5. Gated offline harness → Task 8. Trainer readout → Tasks 7, 10.
- §4.3 win-prob phase param + caller update → Task 3. §4.4 reconstruction (`bg ≤ g`, borne-off gate) → Tasks 4, 6. §5 offline-fit-then-commit → Tasks 8–9. §6 detail sheet + footnote replacement → Task 10. §7 tests (sum-to-1, symmetry, monotonicity, race degeneracy, log-loss beats baseline) → Tasks 2, 3, 6, 9. Out-of-scope → Task 11 Step 3.

**Placeholder scan:** Provisional coefficients in Tasks 3/4 are explicitly replaced by fitted values in Task 9; flagged, not silent TBDs. No "add error handling"-style gaps.

**Type consistency:** `OutcomeDistribution`, `GamePhase`/`GamePhases.of`, `GammonFeatures`/`GammonRates`/`GammonModel.loseRates`, `gammonFeaturesOf(s, loser=)`, `EquityModel.distribution(state, perspective, weights)`, `WinProbability.fromEquity(equity, phase)`, `MoveAnalysis.positionEquity`, `equityLine(OutcomeDistribution)` are used identically across tasks.

**Known cross-module note:** Tasks 1/5 tests assume the `core/BoardState` test-builder API; each such test step says to match the real builder used by existing `:core` tests before writing.
