# Phase 6c-ii — Cubeful Cube Decisions (Janowski) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the gammonless heuristic cube (`CubePolicy.shouldDouble(0.70)`/`shouldTake(0.21)`) with a cubeful, gammon-aware money-game cube model (Janowski), driving the AI's offer/response decisions and a trainer cube hint.

**Architecture:** Two new pure `:ai` objects — `CubeEquity` (Janowski equities from the 6c-1 `OutcomeDistribution`) and `CubeDecision` (offer/response verdicts) — exposed through the existing `CubeAdvisor` facade. `:app` consumes verdicts via a plain `CubeOwner` enum (never `:ai` internals), maps its `CubeState` ownership through a shared `ownerFor` helper, and renders a `CubeHintLine` in the trainer. `CubePolicy` is deleted.

**Tech Stack:** Kotlin (JVM-17 `:core`/`:ai`, Android/Jetpack-Compose `:app`), Gradle, kotlin.test/JUnit, coroutines-test.

**Spec:** `docs/superpowers/specs/2026-06-24-phase6c-ii-cubeful-cube-design.md`

**Build/test commands (env not on PATH after reboot):**
- `:ai` tests: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "<FQCN>"`
- `:app` unit tests: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "<FQCN>"`
- Debug APK: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:assembleDebug`

**Branch:** `phase6c-ii-cubeful` (already checked out; the spec is committed there).

---

## File Structure

- `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeEquity.kt` — **new.** `CubeOwner` (public enum), `CubeEquities` (public data class), `CubeEquity` (internal object) with the `CUBE_EFFICIENCY` map and the Janowski math.
- `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeDecision.kt` — **new.** `OfferVerdict`/`ResponseVerdict` (public enums), `CubeDecision` (internal object) with `offer`/`response`.
- `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeAdvisor.kt` — **edit.** Add `offerVerdict`/`responseVerdict`/`equities`; redefine `winProb` via the distribution.
- `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubePolicy.kt` — **delete** (Task 7).
- `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubePolicyTest.kt` — **delete** (Task 7).
- `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeEquityTest.kt`, `CubeDecisionTest.kt`, `CubeAdvisorCubeTest.kt`, `CubeCalibrationGuardTest.kt` — **new.**
- `app/src/main/java/dk/rlunde/backgammon/game/CubeOwnership.kt` — **new.** `ownerFor(cube, side): CubeOwner`.
- `app/src/main/java/dk/rlunde/backgammon/game/AiTurnDriver.kt` — **edit.** Offer-verdict rewrite.
- `app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt` — **edit.** Response-verdict rewrite.
- `app/src/main/java/dk/rlunde/backgammon/ui/screens/CubeHintFormat.kt` — **new.** Pure `cubeHintLine(verdict, eq)` formatter.
- `app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt` — **edit.** `CubeHintLine` composable + gated call site in `TrackingPanel`.
- `app/src/test/java/dk/rlunde/backgammon/ui/CubeHintFormatTest.kt` — **new.**
- `app/src/test/java/dk/rlunde/backgammon/game/AiTurnDriverTest.kt` — **edit.** Offer / too-good / cap-64 cases.

---

## Task 1: `CubeEquity` — Janowski equities (`:ai`)

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeEquity.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeEquityTest.kt`

- [ ] **Step 1: Write the failing test**

Create `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeEquityTest.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CubeEquityTest {
    /** Gammonless distribution with the given win probability (W=L=1). */
    private fun gammonless(p: Double) = OutcomeDistribution(
        winSingle = p, winGammon = 0.0, winBackgammon = 0.0,
        loseSingle = 1.0 - p, loseGammon = 0.0, loseBackgammon = 0.0,
    )

    @Test fun `take and cash points hit the gammonless dead-cube anchors at x=0`() {
        val eq = CubeEquity.ofX(gammonless(0.5), x = 0.0, owner = CubeOwner.CENTERED)
        assertEquals(0.25, eq.takePoint, 1e-9)
        assertEquals(0.75, eq.cashPoint, 1e-9)
    }

    @Test fun `take and cash points hit the gammonless live-cube anchors at x=1`() {
        val eq = CubeEquity.ofX(gammonless(0.5), x = 1.0, owner = CubeOwner.CENTERED)
        assertEquals(0.20, eq.takePoint, 1e-9)
        assertEquals(0.80, eq.cashPoint, 1e-9)
    }

    @Test fun `all three cubeful equities reduce to cubeless at x=0`() {
        val d = gammonless(0.65)
        val cubeless = d.cubelessEquity  // = 2*0.65 - 1 = 0.30
        for (owner in CubeOwner.values()) {
            val eq = CubeEquity.ofX(d, x = 0.0, owner = owner)
            assertEquals(cubeless, eq.holdEquity, 1e-9, "holdEquity for $owner")
        }
        // doubleTake = 2*E_opp, and E_opp = cubeless at x=0
        assertEquals(2 * cubeless, CubeEquity.ofX(d, 0.0, CubeOwner.CENTERED).doubleTake, 1e-9)
    }

    @Test fun `meanWin and meanLoss are clamped into one-to-three`() {
        // Degenerate distribution: certain single win => p=1, loseProb~0 => L forced to 1.
        val certainWin = OutcomeDistribution(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val eq = CubeEquity.ofX(certainWin, 0.65, CubeOwner.CENTERED)
        assertTrue(eq.meanWin in 1.0..3.0, "meanWin=${eq.meanWin}")
        assertTrue(eq.meanLoss in 1.0..3.0, "meanLoss=${eq.meanLoss}")
        assertTrue(eq.holdEquity.isFinite())
    }

    @Test fun `higher loss-gammon risk raises the take point (pinned)`() {
        // L=1.0 baseline.
        val tpLow = CubeEquity.ofX(gammonless(0.5), 0.65, CubeOwner.CENTERED).takePoint
        // L=1.5: loser loses single half the time, gammon half the time; W stays 1.
        val highLoss = OutcomeDistribution(
            winSingle = 0.5, winGammon = 0.0, winBackgammon = 0.0,
            loseSingle = 0.25, loseGammon = 0.25, loseBackgammon = 0.0,
        )
        val tpHigh = CubeEquity.ofX(highLoss, 0.65, CubeOwner.CENTERED).takePoint
        assertEquals(0.2150537, tpLow, 1e-4)
        assertEquals(0.3539823, tpHigh, 1e-4)
        assertTrue(tpHigh > tpLow, "gammon danger must raise the take point")
    }

    @Test fun `higher win-gammon upside lowers the cash point`() {
        val cpLow = CubeEquity.ofX(gammonless(0.5), 0.65, CubeOwner.CENTERED).cashPoint
        val highWin = OutcomeDistribution(
            winSingle = 0.25, winGammon = 0.25, winBackgammon = 0.0,  // W=1.5
            loseSingle = 0.5, loseGammon = 0.0, loseBackgammon = 0.0,
        )
        val cpHigh = CubeEquity.ofX(highWin, 0.65, CubeOwner.CENTERED).cashPoint
        assertTrue(cpHigh < cpLow, "win-gammon upside must lower the cash point")
    }

    @Test fun `cube efficiency constants are in range and RACE exceeds CONTACT`() {
        val contact = CubeEquity.CUBE_EFFICIENCY.getValue(GamePhase.CONTACT)
        val race = CubeEquity.CUBE_EFFICIENCY.getValue(GamePhase.RACE)
        assertTrue(contact in 0.0..1.0 && race in 0.0..1.0)
        assertTrue(race > contact, "a race cube is more live than a contact cube")
    }

    @Test fun `out-of-range cube efficiency is rejected`() {
        try {
            CubeEquity.ofX(gammonless(0.5), x = 4.0, owner = CubeOwner.CENTERED)
            assertTrue(false, "expected require() to reject x=4.0")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.CubeEquityTest"`
Expected: FAIL — `CubeEquity` / `CubeOwner` / `CubeEquities` unresolved.

- [ ] **Step 3: Write the implementation**

Create `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeEquity.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.CubeEquityTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeEquity.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeEquityTest.kt
git commit -m "feat(ai): CubeEquity — Janowski cubeful equities from the outcome distribution"
```

---

## Task 2: `CubeDecision` — offer / response verdicts (`:ai`)

**Files:**
- Create: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeDecision.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeDecisionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeDecisionTest.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class CubeDecisionTest {
    private fun gammonless(p: Double) = OutcomeDistribution(p, 0.0, 0.0, 1.0 - p, 0.0, 0.0)
    private fun offer(d: OutcomeDistribution, x: Double, owner: CubeOwner) =
        CubeDecision.offer(CubeEquity.ofX(d, x, owner), owner)
    private fun response(d: OutcomeDistribution, x: Double) =
        CubeDecision.response(CubeEquity.ofX(d, x, CubeOwner.ME))

    // --- Response (take/drop) -------------------------------------------------------------
    @Test fun `clear take above the take point`() {
        assertEquals(ResponseVerdict.TAKE, response(gammonless(0.40), 0.65))
    }

    @Test fun `clear drop below the take point`() {
        assertEquals(ResponseVerdict.DROP, response(gammonless(0.10), 0.65))
    }

    @Test fun `marginal take straddling the take point`() {
        // gammonless x=0.65 take point = 0.2150537
        assertEquals(ResponseVerdict.DROP, response(gammonless(0.214), 0.65))
        assertEquals(ResponseVerdict.TAKE, response(gammonless(0.216), 0.65))
    }

    // --- Offer window edges (gammonless, x=0.65) — pinned to the solved DP -----------------
    @Test fun `centred double window opens at the analytic minimum double point`() {
        // centred DP gammonless x=0.65 = 0.673436
        assertEquals(OfferVerdict.NO_DOUBLE, offer(gammonless(0.673), 0.65, CubeOwner.CENTERED))
        assertEquals(OfferVerdict.DOUBLE, offer(gammonless(0.675), 0.65, CubeOwner.CENTERED))
    }

    @Test fun `owned redouble window opens later than the centred window`() {
        // owned DP gammonless x=0.65 = 0.709677 (> centred 0.673436)
        assertEquals(OfferVerdict.NO_DOUBLE, offer(gammonless(0.709), 0.65, CubeOwner.ME))
        assertEquals(OfferVerdict.DOUBLE, offer(gammonless(0.711), 0.65, CubeOwner.ME))
    }

    // --- Too good vs cash -----------------------------------------------------------------
    @Test fun `too good fires only when cubeless equity exceeds one (pinned cause)`() {
        // p=0.85, W=1.8, L=1.133, cubelessEquity=1.36; CP≈0.601 at x=0.65
        val tooGood = OutcomeDistribution(0.20, 0.62, 0.03, 0.13, 0.02, 0.0)
        val eq = CubeEquity.ofX(tooGood, 0.65, CubeOwner.CENTERED)
        assertEquals(true, eq.winProb > eq.cashPoint)
        assertEquals(true, eq.cubelessEquity > 1.0)
        assertEquals(OfferVerdict.TOO_GOOD, CubeDecision.offer(eq, CubeOwner.CENTERED))
    }

    @Test fun `past the cash point but not too good is a normal cash`() {
        // p=0.82, cubelessEquity=0.68 < 1, CP≈0.769 at x=0.65
        val cash = OutcomeDistribution(0.78, 0.04, 0.0, 0.18, 0.0, 0.0)
        val eq = CubeEquity.ofX(cash, 0.65, CubeOwner.CENTERED)
        assertEquals(true, eq.winProb > eq.cashPoint)
        assertEquals(OfferVerdict.DOUBLE, CubeDecision.offer(eq, CubeOwner.CENTERED))
    }

    @Test fun `centred cube-access inflation does not spuriously trigger too good`() {
        // Moderate gammon, p=0.80: E_center≈1.13 (>1) but cubelessEquity=0.85 (<1) ⇒ must DOUBLE, not TOO_GOOD.
        val moderate = OutcomeDistribution(0.55, 0.25, 0.0, 0.20, 0.0, 0.0)
        val eq = CubeEquity.ofX(moderate, 0.65, CubeOwner.CENTERED)
        assertEquals(true, eq.holdEquity > 1.0)        // the inflated centred equity
        assertEquals(true, eq.cubelessEquity < 1.0)
        assertEquals(OfferVerdict.DOUBLE, CubeDecision.offer(eq, CubeOwner.CENTERED))
    }

    // --- Boundary / dead positions --------------------------------------------------------
    @Test fun `opponent-owned cube can never be doubled`() {
        assertEquals(OfferVerdict.NO_DOUBLE, offer(gammonless(0.99), 0.65, CubeOwner.OPPONENT))
    }

    @Test fun `certain single win cashes rather than playing on`() {
        val certain = OutcomeDistribution(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)  // cubelessEquity = 1.0 exactly
        assertEquals(OfferVerdict.DOUBLE, offer(certain, 0.65, CubeOwner.CENTERED))
    }

    @Test fun `certain loss is a drop`() {
        assertEquals(ResponseVerdict.DROP, response(OutcomeDistribution(0.0, 0.0, 0.0, 1.0, 0.0, 0.0), 0.65))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.CubeDecisionTest"`
Expected: FAIL — `CubeDecision` / `OfferVerdict` / `ResponseVerdict` unresolved.

- [ ] **Step 3: Write the implementation**

Create `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeDecision.kt`:

```kotlin
package dk.rlunde.backgammon.ai

/** What the player on roll should do with the cube (spec §4.2). Crosses to :app. */
enum class OfferVerdict { NO_DOUBLE, DOUBLE, TOO_GOOD }

/** How a receiver should answer a double (spec §4.1). Crosses to :app. */
enum class ResponseVerdict { TAKE, DROP }

/** Turns [CubeEquities] into cube verdicts (spec §4). */
internal object CubeDecision {
    /**
     * Offer decision for the player on roll. [owner] must reflect [eq] (it packs holdEquity);
     * an OPPONENT-owned cube cannot be doubled, so it returns NO_DOUBLE.
     */
    fun offer(eq: CubeEquities, owner: CubeOwner): OfferVerdict {
        if (owner == CubeOwner.OPPONENT) return OfferVerdict.NO_DOUBLE
        return if (eq.winProb > eq.cashPoint) {
            // Opponent would pass: cash for +1, unless playing the gammon out is worth more.
            if (eq.cubelessEquity > 1.0) OfferVerdict.TOO_GOOD else OfferVerdict.DOUBLE
        } else {
            // Opponent would take: double iff being-taken beats holding the cube.
            if (eq.doubleTake > eq.holdEquity) OfferVerdict.DOUBLE else OfferVerdict.NO_DOUBLE
        }
    }

    /** Take iff the receiver's win prob clears their take point (owner-independent — see spec §4.1). */
    fun response(eq: CubeEquities): ResponseVerdict =
        if (eq.winProb >= eq.takePoint) ResponseVerdict.TAKE else ResponseVerdict.DROP
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.CubeDecisionTest"`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeDecision.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeDecisionTest.kt
git commit -m "feat(ai): CubeDecision — offer/response verdicts (too-good = cubeless equity > 1)"
```

---

## Task 3: `CubeAdvisor` facade — verdicts, equities, single win-prob source (`:ai`)

**Files:**
- Modify: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeAdvisor.kt`
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeAdvisorCubeTest.kt`

- [ ] **Step 1: Write the failing test**

Create `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeAdvisorCubeTest.kt`:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import kotlin.test.Test
import kotlin.test.assertEquals

class CubeAdvisorCubeTest {
    /** WHITE far ahead in a pure race (no contact): a clear double / easy take for WHITE. */
    private fun whiteWayAhead(): BoardState {
        val pts = IntArray(26)
        pts[1] = 2; pts[2] = 2          // WHITE: 4 checkers far home, rest off-board conceptually
        pts[24] = -2; pts[23] = -2; pts[22] = -2  // BLACK still back
        return BoardState(
            points = pts,
            bar = mapOf(Player.WHITE to 0, Player.BLACK to 0),
            off = mapOf(Player.WHITE to 11, Player.BLACK to 9),
            toMove = Player.WHITE,
        )
    }

    @Test fun `equities winProb equals the winProb facade (single source)`() {
        val s = whiteWayAhead()
        val eqP = CubeAdvisor.equities(s, Player.WHITE, CubeOwner.CENTERED).winProb
        assertEquals(CubeAdvisor.winProb(s, Player.WHITE), eqP, 1e-12)
    }

    @Test fun `responseVerdict is invariant to whose turn it is`() {
        val s = whiteWayAhead()
        val asWhiteToMove = CubeAdvisor.responseVerdict(s, Player.BLACK)
        val flipped = s.copy(toMove = Player.BLACK)
        val asBlackToMove = CubeAdvisor.responseVerdict(flipped, Player.BLACK)
        assertEquals(asWhiteToMove, asBlackToMove)
    }

    @Test fun `the trailing side facing a double from a hopeless race drops`() {
        val s = whiteWayAhead()
        assertEquals(ResponseVerdict.DROP, CubeAdvisor.responseVerdict(s, Player.BLACK))
    }
}
```

> Note for the implementer: if `BoardState`'s constructor/`copy` signature differs from the above (check `core/.../BoardState.kt`), adjust the fixture to construct a lopsided race — the test only needs a position where WHITE's win prob is high and BLACK's is low. Keep the three assertions.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.CubeAdvisorCubeTest"`
Expected: FAIL — `equities`/`responseVerdict` unresolved on `CubeAdvisor`.

- [ ] **Step 3: Replace the implementation**

Replace the entire body of `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeAdvisor.kt` with:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player

/**
 * Public facade for cube decisions (spec §5.3). Builds the cubeless [OutcomeDistribution] from the
 * static eval (6a §4.3) and applies the Janowski cubeful model. The distribution is always built
 * with the perspective of the player whose decision it is.
 */
object CubeAdvisor {
    /** Calibrated single-win probability for [perspective] (single source: the outcome distribution). */
    fun winProb(state: BoardState, perspective: Player): Double =
        dist(state, perspective).winProb

    /** Cube-offer verdict for the on-roll [perspective] given who owns the cube. */
    fun offerVerdict(state: BoardState, perspective: Player, owner: CubeOwner): OfferVerdict =
        CubeDecision.offer(CubeEquity.of(dist(state, perspective), GamePhases.of(state), owner), owner)

    /** Take/drop verdict for the [receiver] of a double (distribution built from the receiver's side). */
    fun responseVerdict(state: BoardState, receiver: Player): ResponseVerdict =
        CubeDecision.response(CubeEquity.of(dist(state, receiver), GamePhases.of(state), CubeOwner.ME))

    /** Full equities for the trainer hint. */
    fun equities(state: BoardState, perspective: Player, owner: CubeOwner): CubeEquities =
        CubeEquity.of(dist(state, perspective), GamePhases.of(state), owner)

    private fun dist(state: BoardState, perspective: Player) =
        EquityModel.distribution(state, perspective, Weights.FULL)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.CubeAdvisorCubeTest" --tests "dk.rlunde.backgammon.ai.CubeAdvisorTest"`
Expected: PASS — the new tests pass and the existing `CubeAdvisorTest` (if any) stays green (`winProb` returns the same value as before).

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeAdvisor.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeAdvisorCubeTest.kt
git commit -m "feat(ai): CubeAdvisor cube verdicts + equities; winProb via the distribution"
```

---

## Task 4: `CubeCalibrationGuardTest` — non-gated band guard (`:ai`)

**Files:**
- Test: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeCalibrationGuardTest.kt`
- Reference: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/SelfPlay.kt` (the `selfPlayTrajectory` helper used by `GammonCalibrationGuardTest`)

- [ ] **Step 1: Read the existing guard test and self-play helper**

Run: `sed -n '1,60p' ai/src/test/kotlin/dk/rlunde/backgammon/ai/GammonCalibrationGuardTest.kt` and inspect `SelfPlay.kt` for the exact `selfPlayTrajectory(...)` signature, seed convention, and the `CalibrationSample` fields (`state`, `mover`, `decisive`). Mirror that style so this guard is deterministic.

- [ ] **Step 2: Write the test**

Create `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeCalibrationGuardTest.kt`. Use the SAME fixed-seed `selfPlayTrajectory` helper and seed set as `GammonCalibrationGuardTest` (adapt the call to its real signature). The assertions:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Player
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Non-gated guard: across deterministic self-play positions, the Janowski take point stays in the
 * known money-game band for both phase cube-efficiency constants, and TOO_GOOD only fires when the
 * cubeless equity actually exceeds the +1 cash value (spec §8).
 */
class CubeCalibrationGuardTest {
    @Test fun `take point stays in the 18-to-27 percent band for both phases`() {
        // Sample positions from fixed-seed self-play (mirror GammonCalibrationGuardTest's helper+seeds).
        val samples = selfPlayTrajectory(games = 40, seed0 = 1L)   // adjust to the real signature
        var checked = 0
        for (s in samples) {
            for (phase in listOf(GamePhase.CONTACT, GamePhase.RACE)) {
                val d = EquityModel.distribution(s.state, Player.WHITE, Weights.FULL)
                val tp = CubeEquity.of(d, phase, CubeOwner.ME).takePoint
                assertTrue(tp in 0.18..0.27, "take point $tp out of band in $phase")
                checked++
            }
        }
        assertTrue(checked > 0, "guard sampled no positions")
    }

    @Test fun `too good never fires unless cubeless equity exceeds one`() {
        val samples = selfPlayTrajectory(games = 40, seed0 = 1L)
        for (s in samples) {
            val eq = CubeAdvisor.equities(s.state, Player.WHITE, CubeOwner.CENTERED)
            if (CubeDecision.offer(eq, CubeOwner.CENTERED) == OfferVerdict.TOO_GOOD) {
                assertTrue(eq.cubelessEquity > 1.0, "TOO_GOOD fired with cubelessEquity=${eq.cubelessEquity}")
            }
        }
    }
}
```

> The `selfPlayTrajectory(...)` call and `s.state` access MUST match the real helper. If the helper yields `CalibrationSample` objects, use the field that holds the board (per `SelfPlay.kt`). The take-point band [0.18, 0.27] is intentionally wider than the gammonless 20–25% to admit gammon-skewed positions.

- [ ] **Step 3: Run the test to verify it passes**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.CubeCalibrationGuardTest"`
Expected: PASS. If the band assertion fails, do NOT widen it blindly — inspect whether a real position legitimately sits outside (a true degenerate near-certain position is gated by the W/L guards; if a genuine contact position exceeds 0.27, that is a finding to report, not a tolerance to relax).

- [ ] **Step 4: Commit**

```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeCalibrationGuardTest.kt
git commit -m "test(ai): non-gated cube guard — take-point band + too-good cause"
```

---

## Task 5: `ownerFor` helper + `AiTurnDriver` offer rewrite (`:app`)

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/game/CubeOwnership.kt`
- Modify: `app/src/main/java/dk/rlunde/backgammon/game/AiTurnDriver.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/game/AiTurnDriverTest.kt`

- [ ] **Step 1: Create the shared helper**

Create `app/src/main/java/dk/rlunde/backgammon/game/CubeOwnership.kt`:

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.ai.CubeOwner
import dk.rlunde.backgammon.core.Player

/** Maps :app's CubeState ownership to :ai's CubeOwner, from [side]'s perspective (spec §6). */
fun ownerFor(cube: CubeState, side: Player): CubeOwner = when {
    cube.isCentred -> CubeOwner.CENTERED
    cube.owner == side -> CubeOwner.ME
    else -> CubeOwner.OPPONENT
}
```

- [ ] **Step 2: Write the failing tests**

Add these test methods to `app/src/test/java/dk/rlunde/backgammon/game/AiTurnDriverTest.kt` (keep the existing tests). The fixtures use the same `controller(...)`/`FakeAi` helpers already in that file:

```kotlin
    @Test fun `AI offers the cube from a strong centred-cube position`() = runTest {
        // WHITE far ahead in a race: win prob inside the double window.
        val p = IntArray(26); p[1] = 2; p[2] = 2; p[23] = -2; p[24] = -2
        val c = GameController(
            initial = BoardState(p, mapOf(Player.WHITE to 0, Player.BLACK to 0),
                mapOf(Player.WHITE to 11, Player.BLACK to 9), Player.WHITE),
            roller = SeededDiceRoller(1), aiSide = Player.WHITE)
        val driver = AiTurnDriver(Player.WHITE, FakeAi(), StandardTestDispatcher(testScheduler), 0, 0)
        driver.maybeRunTurn(c, onThinking = {}, publish = {})
        testScheduler.advanceUntilIdle()
        assertEquals(Phase.CUBE_OFFERED, c.uiState.phase)
    }

    @Test fun `a too-good position does not double but still rolls and plays`() = runTest {
        // WHITE almost certain to win a gammon: too good to double — must keep playing, not stall.
        val p = IntArray(26)
        p[1] = 1; p[2] = 1                 // WHITE: a couple of checkers left to bear off
        p[24] = -3; p[23] = -3; p[20] = -2 // BLACK: many checkers, none borne off (gammon threat)
        val c = GameController(
            initial = BoardState(p, mapOf(Player.WHITE to 0, Player.BLACK to 0),
                mapOf(Player.WHITE to 13, Player.BLACK to 0), Player.WHITE),
            roller = SeededDiceRoller(1), aiSide = Player.WHITE)
        val ai = FakeAi()
        val driver = AiTurnDriver(Player.WHITE, ai, StandardTestDispatcher(testScheduler), 0, 0)
        driver.maybeRunTurn(c, onThinking = {}, publish = {})
        testScheduler.advanceUntilIdle()
        assertTrue(c.uiState.phase != Phase.CUBE_OFFERED, "must not offer when too good")
        assertTrue(ai.calls >= 1 || c.uiState.toMove == Player.BLACK, "must roll and play, not stall")
    }
```

Add imports if missing: `import dk.rlunde.backgammon.core.BoardState`, `Player`, `kotlin.test.assertTrue`.

> The too-good fixture must be a position where WHITE's win prob exceeds the cash point AND the cubeless equity exceeds 1 (heavy gammon mass: BLACK has borne off nothing and is deep). If the AI still offers, make BLACK's position more lopsided (more BLACK checkers stuck back, WHITE closer to off). Verify by temporarily logging `CubeAdvisor.equities(board, WHITE, CENTERED)`.
>
> **Cube cap (spec §8):** no new test needed — at cube value 64 `CubeState.mayDouble` already returns false (it is `value < CUBE_CAP`, unit-tested in `core`), so the `if (cube.mayDouble(aiSide))` guard makes the offer path unreachable. The verdict math is scale-invariant and unaffected by the cap.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.game.AiTurnDriverTest"`
Expected: FAIL — the old `CubePolicy.shouldDouble(0.70)` path won't produce these verdicts (and won't compile once the import changes in Step 4 if done first). Run after Step 4 if compile order requires.

- [ ] **Step 4: Rewrite the offer block in `AiTurnDriver`**

In `app/src/main/java/dk/rlunde/backgammon/game/AiTurnDriver.kt`:

Replace the import line `import dk.rlunde.backgammon.ai.CubePolicy` with:
```kotlin
import dk.rlunde.backgammon.ai.OfferVerdict
```

Replace the pre-roll offer block:
```kotlin
        if (controller.uiState.cube.mayDouble(aiSide)) {
            val winProb = withContext(dispatcher) { CubeAdvisor.winProb(controller.uiState.board, aiSide) }
            if (CubePolicy.shouldDouble(winProb)) {
                controller.offerDouble(); publish()
                return
            }
        }
```
with:
```kotlin
        // Pre-roll: offer a double when the cubeful model says so (NO_DOUBLE/TOO_GOOD fall through to play).
        if (controller.uiState.cube.mayDouble(aiSide)) {
            val owner = ownerFor(controller.uiState.cube, aiSide)  // ME or CENTERED (mayDouble gated)
            val verdict = withContext(dispatcher) {
                CubeAdvisor.offerVerdict(controller.uiState.board, aiSide, owner)
            }
            if (verdict == OfferVerdict.DOUBLE) {
                controller.offerDouble(); publish()
                return
            }
        }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.game.AiTurnDriverTest"`
Expected: PASS (new + existing). The existing balanced fixtures (`p[13]=2;p[12]=-2`) must still NOT offer — confirm they stay green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/game/CubeOwnership.kt app/src/main/java/dk/rlunde/backgammon/game/AiTurnDriver.kt app/src/test/java/dk/rlunde/backgammon/game/AiTurnDriverTest.kt
git commit -m "feat(app): AI cube offer via cubeful verdict; shared ownerFor helper"
```

---

## Task 6: `GameViewModel` response rewrite (`:app`)

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt`

- [ ] **Step 1: Rewrite the AI response block**

In `app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt`:

Replace `import dk.rlunde.backgammon.ai.CubePolicy` with:
```kotlin
import dk.rlunde.backgammon.ai.ResponseVerdict
```
(keep the existing `import dk.rlunde.backgammon.ai.CubeAdvisor`.)

Replace, inside `onOfferDouble`:
```kotlin
        aiJob = scope.launch {
            val winProb = withContext(analysisDispatcher) { CubeAdvisor.winProb(controller.uiState.board, ai) }
            controller.respondDouble(if (CubePolicy.shouldTake(winProb)) CubeResponse.TAKE else CubeResponse.DROP)
            publish()
        }
```
with:
```kotlin
        aiJob = scope.launch {
            val verdict = withContext(analysisDispatcher) {
                CubeAdvisor.responseVerdict(controller.uiState.board, ai)
            }
            controller.respondDouble(if (verdict == ResponseVerdict.TAKE) CubeResponse.TAKE else CubeResponse.DROP)
            publish()
        }
```

- [ ] **Step 2: Build to verify it compiles (CubePolicy still exists, referenced nowhere now)**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the existing GameViewModel cube test (if present)**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.viewmodel.GameViewModelTest"`
Expected: PASS — AI takes a sound double and drops a hopeless one. If the existing test fixtures relied on the `0.21` threshold and now resolve differently, adjust the fixture so the take case has receiver win prob clearly above ~25% and the drop case clearly below ~15% (the cubeful take point is gammon-dependent but lands near 20–25% gammonless).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt
git commit -m "feat(app): AI double response via cubeful responseVerdict"
```

---

## Task 7: Delete `CubePolicy` and its test (`:ai`)

**Files:**
- Delete: `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubePolicy.kt`
- Delete: `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubePolicyTest.kt`

- [ ] **Step 1: Confirm no remaining references**

Run: `grep -rn "CubePolicy" ai/src app/src`
Expected: no matches (Tasks 5 & 6 removed the two call sites).

- [ ] **Step 2: Delete the files**

```bash
git rm ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubePolicy.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubePolicyTest.kt
```

- [ ] **Step 3: Build both modules to verify nothing broke**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, all `:ai` tests pass.

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(ai): delete gammonless CubePolicy (superseded by cubeful CubeDecision)"
```

---

## Task 8: Trainer cube hint — formatter + `CubeHintLine` (`:app`)

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/ui/screens/CubeHintFormat.kt`
- Create: `app/src/test/java/dk/rlunde/backgammon/ui/CubeHintFormatTest.kt`
- Modify: `app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt`

- [ ] **Step 1: Write the failing formatter test**

Create `app/src/test/java/dk/rlunde/backgammon/ui/CubeHintFormatTest.kt`:

```kotlin
package dk.rlunde.backgammon.ui

import dk.rlunde.backgammon.ai.CubeEquities
import dk.rlunde.backgammon.ai.OfferVerdict
import dk.rlunde.backgammon.ui.screens.cubeHintLine
import kotlin.test.Test
import kotlin.test.assertTrue

class CubeHintFormatTest {
    private fun eq(winProb: Double, cashPoint: Double) = CubeEquities(
        winProb = winProb, meanWin = 1.5, meanLoss = 1.2, cubeEfficiency = 0.65,
        cubelessEquity = 0.5, takePoint = 0.22, cashPoint = cashPoint,
        holdEquity = 0.4, doubleTake = 0.3,
    )

    @Test fun `double take when below the cash point`() {
        assertTrue(cubeHintLine(OfferVerdict.DOUBLE, eq(0.70, 0.79)).startsWith("Double / take"))
    }

    @Test fun `double pass when above the cash point`() {
        assertTrue(cubeHintLine(OfferVerdict.DOUBLE, eq(0.85, 0.79)).startsWith("Double / pass"))
    }

    @Test fun `too good label`() {
        assertTrue(cubeHintLine(OfferVerdict.TOO_GOOD, eq(0.90, 0.60)).startsWith("Too good to double"))
    }

    @Test fun `no double label`() {
        assertTrue(cubeHintLine(OfferVerdict.NO_DOUBLE, eq(0.55, 0.79)).startsWith("No double"))
    }

    @Test fun `includes take and cash points`() {
        val line = cubeHintLine(OfferVerdict.NO_DOUBLE, eq(0.55, 0.79))
        assertTrue(line.contains("TP 22%") && line.contains("CP 79%"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.ui.CubeHintFormatTest"`
Expected: FAIL — `cubeHintLine` unresolved.

- [ ] **Step 3: Write the formatter**

Create `app/src/main/java/dk/rlunde/backgammon/ui/screens/CubeHintFormat.kt`:

```kotlin
package dk.rlunde.backgammon.ui.screens

import dk.rlunde.backgammon.ai.CubeEquities
import dk.rlunde.backgammon.ai.OfferVerdict
import kotlin.math.roundToInt

/** Compact one-line cube readout for the trainer (spec §6.3). Pure; unit-tested. */
fun cubeHintLine(verdict: OfferVerdict, eq: CubeEquities): String {
    val action = when (verdict) {
        OfferVerdict.NO_DOUBLE -> "No double"
        OfferVerdict.DOUBLE -> if (eq.winProb <= eq.cashPoint) "Double / take" else "Double / pass"
        OfferVerdict.TOO_GOOD -> "Too good to double"
    }
    val tp = (eq.takePoint * 100).roundToInt()
    val cp = (eq.cashPoint * 100).roundToInt()
    return "%s · TP %d%% · CP %d%% · Cube %+.2f (cubeless %+.2f)"
        .format(action, tp, cp, eq.holdEquity, eq.cubelessEquity)
}
```

- [ ] **Step 4: Run the formatter test to verify it passes**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.ui.CubeHintFormatTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Add the `CubeHintLine` composable**

In `app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt`, add this composable near `TrackingPanel` (after the `private fun TrackingPanel(...)` block). Add imports if missing: `dk.rlunde.backgammon.core.BoardState`, `dk.rlunde.backgammon.ai.CubeAdvisor`, `dk.rlunde.backgammon.ai.CubeOwner`.

```kotlin
@Composable
private fun CubeHintLine(board: BoardState, onRoll: Player, owner: CubeOwner, modifier: Modifier = Modifier) {
    val eq = CubeAdvisor.equities(board, onRoll, owner)
    val verdict = CubeAdvisor.offerVerdict(board, onRoll, owner)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = cubeHintLine(verdict, eq),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
```

- [ ] **Step 6: Render it at the gated call site**

In `TrackingPanel`, immediately before the existing band-marker block (`if (state.training && state.analysis != null) {`), insert:

```kotlin
        // Cube hint: training on, pre-roll, and the on-roll side may actually double.
        if (state.training && state.phase == Phase.NEED_ROLL && state.cube.mayDouble(state.toMove)) {
            CubeHintLine(
                board = state.board,
                onRoll = state.toMove,
                owner = ownerFor(state.cube, state.toMove),
            )
            Spacer(Modifier.height(8.dp))
        }
```

Add import if missing: `import dk.rlunde.backgammon.game.ownerFor`.

- [ ] **Step 7: Build the APK to verify Compose compiles**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/screens/CubeHintFormat.kt app/src/test/java/dk/rlunde/backgammon/ui/CubeHintFormatTest.kt app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt
git commit -m "feat(app): trainer cube hint (verdict + TP/CP + cubeful vs cubeless equity)"
```

---

## Task 9: Full verification + debug APK

**Files:** none (verification only).

- [ ] **Step 1: Run the full `:ai` + `:app` test suites and build the APK**

Run:
```bash
JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk \
  ./gradlew :ai:test :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL; all suites green; `app/build/outputs/apk/debug/app-debug.apk` produced.

- [ ] **Step 2: Confirm the boundary held**

Run: `git diff main..HEAD --stat -- ai/src/main` (or `phase3-strong-ai..HEAD`)
Expected: changes confined to `CubeEquity.kt`, `CubeDecision.kt`, `CubeAdvisor.kt`, and the `CubePolicy.kt` deletion — no edits to `WinProbability.kt`, `EquityModel.kt`, `GammonModel.kt`, or `Evaluator.kt`.

- [ ] **Step 3: Confirm `CubePolicy` is gone**

Run: `grep -rn "CubePolicy" ai app` — expected: no matches.

- [ ] **Step 4: Commit (if any verification fixups were needed)**

Only if Steps 1–3 required changes; otherwise nothing to commit.

---

## Notes for the implementer

- **No rollouts, O(1) on the UI path.** `CubeEquity.of` is pure arithmetic over a single `OutcomeDistribution`. The trainer hint calls `equities` + `offerVerdict` (two distribution builds per recomposition) — acceptable for a static 0-ply eval; do not memoise unless profiling shows a problem.
- **`:ai` is internal-by-default.** Only `CubeOwner`, `CubeEquities`, `OfferVerdict`, `ResponseVerdict` are public (they cross to `:app`). `CubeEquity`/`CubeDecision` objects stay `internal`. `ofX` is `internal` (test seam).
- **Perspective discipline (spec §5.3 / 6b-ii hazard).** `responseVerdict` builds the distribution with `perspective = receiver`. Never assume `state.toMove` is the decision-maker.
- **Numeric anchors are load-bearing.** The window edges (centred 0.673/0.675, owned 0.709/0.711 at x=0.65) and the take/cash anchors (25/75% at x=0, 20/80% at x=1) are the proof the Janowski transcription is correct — do not adjust them to make a buggy implementation pass.
- **`RACE > CONTACT`.** If you ever flip the constants, `CubeEquityTest` fails by design (spec §3.2).
```
