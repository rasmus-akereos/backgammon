# Phase 6c-i — Gammon-Rate Model (calibrated) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace 6a's degenerate hand-set gammon coefficients with a properly calibrated fit — standardized features + decisive-window labeling — so the trainer's G%/BG% become trustworthy.

**Architecture:** Feature scaling lives in one shared `GammonModel.scaledRow(f)` used by both the runtime logistic and the calibration harness (no drift). `CalibrationSample` gains a `decisive` flag; the gated harness fits the gammon/bg coefficients only on decisive-window rows and we commit the result. Win-prob `K` and `EquityModel` assembly are untouched.

**Tech Stack:** Kotlin, kotlin.test/JUnit5; gated self-play calibration harness (`-Dbackgammon.calibrate=true`, forwarded to the `:ai` test JVM).

**Spec:** `docs/superpowers/specs/2026-06-19-phase6c-i-gammon-rate-model-design.md`

**Build/test** (toolchain not on PATH; see memory `build-environment.md`):
- `:ai` tests: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "..."`
- gated calibration: `… ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EquityCalibrationTest" -Dbackgammon.calibrate=true --rerun-tasks -i`
- APK: `… ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:assembleDebug`

---

## File structure
- **Modify** `ai/.../GammonModel.kt` — add `scaledRow`; logistic uses it; provisional then fitted scaled coefficients.
- **Modify** `ai/src/test/.../SelfPlay.kt` — `CalibrationSample` gains `decisive`; `selfPlayTrajectory` sets it.
- **Modify** `ai/src/test/.../EquityCalibrationTest.kt` — scale gammon rows via `GammonModel.scaledRow`; gate on `decisive`; diagnostics.
- **Modify** `ai/src/test/.../GammonModelTest.kt` — directional cases re-expressed against fitted coefficients.
- **Create** `ai/src/test/.../GammonCalibrationGuardTest.kt` — non-gated calibration guard.
- **Modify** `app/.../ui/screens/GameScreen.kt` — footnote wording.
- **Modify** `docs/.../2026-06-16-phase6a-equity-foundation-design.md` §10 — note 6c-1 closes it.

---

## Task 1: Feature scaling in `GammonModel` (+ provisional scaled coefficients)

**Files:** Modify `ai/src/main/kotlin/dk/rlunde/backgammon/ai/GammonModel.kt`; tests in `ai/src/test/kotlin/dk/rlunde/backgammon/ai/GammonModelTest.kt` (exists — should stay green).

The 6a `GammonModel.logistic` uses raw features (unscaled `pip` saturated the fit). Add a shared `scaledRow` and feed it to the logistic. The hand-set coefficients were tuned for *raw* features, so they must be replaced with provisional values sensible for *scaled* features (final fitted values land in Task 3).

- [ ] **Step 1: Run the existing GammonModelTest to confirm the baseline is green**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.GammonModelTest"`
Expected: PASS (3 tests) — this is the pre-change baseline.

- [ ] **Step 2: Edit `GammonModel.kt`** — replace the `GAMMON_COEFFS`/`BG_COEFFS` vals and the `logistic` function:

```kotlin
    // {intercept, wBorneOff, wPip, wBackContact} on SCALED features (see scaledRow). PROVISIONAL —
    // replaced by the offline fit in 6c-1 Task 3.
    internal var GAMMON_COEFFS = doubleArrayOf(-1.5, 0.0, 1.2, 0.6)
    internal var BG_COEFFS = doubleArrayOf(-3.0, 0.0, 1.0, 1.5)

    /** Features normalized to ~[0,1] with fixed divisors. Shared by the runtime model and the
     *  calibration harness so they can never drift. */
    internal fun scaledRow(f: GammonFeatures): DoubleArray =
        doubleArrayOf(f.borneOff / 15.0, f.pip / 167.0, f.backContact / 15.0)

    private fun logistic(c: DoubleArray, f: GammonFeatures): Double {
        val s = scaledRow(f)
        return 1.0 / (1.0 + exp(-(c[0] + c[1] * s[0] + c[2] * s[1] + c[3] * s[2])))
    }
```
Leave `loseRates` unchanged (it still calls `logistic(GAMMON_COEFFS, f)` / `logistic(BG_COEFFS, f)`, applies the `borneOff > 0 → (0,0)` gate and `min(bg, gammon)`). Note `GAMMON_COEFFS`/`BG_COEFFS` are `var` so Task 3 can paste fitted values; they stay `internal`.

- [ ] **Step 3: Run GammonModelTest** — Run the Step 1 command. Expected: PASS (the provisional scaled coefficients preserve the three invariants: borne-off gate → 0; `bg ≤ gammon`; more back-contact → higher bg, since `BG_COEFFS` `wBackContact = 1.5 > 0`).

- [ ] **Step 4: Commit**
```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/GammonModel.kt
git commit -m "feat(ai): standardize gammon-model features (scaledRow); provisional scaled coefficients"
```

---

## Task 2: Decisive-window flag + scaled, gated harness

**Files:** Modify `ai/src/test/kotlin/dk/rlunde/backgammon/ai/SelfPlay.kt` and `ai/src/test/kotlin/dk/rlunde/backgammon/ai/EquityCalibrationTest.kt`

- [ ] **Step 1: Add `decisive` to `CalibrationSample`** (in `SelfPlay.kt`). Change the data class:
```kotlin
internal data class CalibrationSample(
    val mover: Player,
    val eval: Double,
    val phase: GamePhase,
    val winnerFeatures: GammonFeatures, // opponent-as-loser features (if mover wins)
    val loserFeatures: GammonFeatures,  // mover-as-loser features (if mover loses)
    val decisive: Boolean,              // position is in the gammon-decidable window
)
```
And in `selfPlayTrajectory`, where the sample is built, add the flag (a gammon is decidable once either side has borne off, or in a no-contact race):
```kotlin
        samples.add(
            CalibrationSample(
                mover = mover,
                eval = Evaluator.evaluate(state, mover, weights),
                phase = GamePhases.of(state),
                winnerFeatures = gammonFeaturesOf(state, loser = mover.opponent),
                loserFeatures = gammonFeaturesOf(state, loser = mover),
                decisive = Features.noContact(state) ||
                    state.offCount(Player.WHITE) > 0 || state.offCount(Player.BLACK) > 0,
            )
        )
```
(`Features` is `internal` to `:ai`; this test file is in `:ai` so it has access.)

- [ ] **Step 2: Scale + gate the gammon rows in `EquityCalibrationTest`.** In `fit and print equity model constants`, replace the inline `row(...)` helper and the row-collection so it uses `GammonModel.scaledRow` and only adds gammon rows for decisive samples:
```kotlin
            for (s in cg.samples) {
                val moverWon = s.mover == winner
                winByPhase.getValue(s.phase).add(s.eval to moverWon)   // win-prob: ALL plies (unchanged)
                if (!s.decisive) continue                               // gammon rows: decisive window only
                if (moverWon) {            // opponent is the loser -> winnerFeatures describe the loser
                    winGam.add(GammonModel.scaledRow(s.winnerFeatures) to (value >= 2))
                    winBg.add(GammonModel.scaledRow(s.winnerFeatures) to (value == 3))
                } else {                   // mover is the loser
                    loseGam.add(GammonModel.scaledRow(s.loserFeatures) to (value >= 2))
                    loseBg.add(GammonModel.scaledRow(s.loserFeatures) to (value == 3))
                }
            }
```
(Delete the old local `fun row(f) = doubleArrayOf(f.borneOff.toDouble(), …)`. The `fitLogistic(winGam + loseGam, 3)` / `fitLogistic(winBg + loseBg, 3)` calls and the `println` of the coeffs stay.) Also add a diagnostics line after fitting, printing predicted-vs-realized gammon frequency over the collected decisive rows:
```kotlin
        val realizedGammon = (winGam + loseGam).count { it.second }.toDouble() / (winGam + loseGam).size
        println("gammon rows=${winGam.size + loseGam.size}  realized gammon rate=$realizedGammon")
```

- [ ] **Step 3: Compile + confirm the harness is still a no-op without the flag**

Run: `… ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EquityCalibrationTest" --tests "dk.rlunde.backgammon.ai.EquityCalibrationGuardTest"`
Expected: PASS (no-op gated test compiles; the existing win-prob guard still passes — adding the `decisive` field doesn't affect it).

- [ ] **Step 4: Commit**
```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/SelfPlay.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/EquityCalibrationTest.kt
git commit -m "test(ai): decisive-window flag + scaled/gated gammon rows in the calibration harness"
```

---

## Task 3: Run calibration, commit fitted coefficients

**Files:** Modify `ai/src/main/kotlin/dk/rlunde/backgammon/ai/GammonModel.kt`; `docs/.../2026-06-19-phase6c-i-gammon-rate-model-design.md` (results note). **Needs the build env.**

- [ ] **Step 1: Run the gated harness** (must force a re-run; the `-D` flag doesn't invalidate the task cache):

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.EquityCalibrationTest" -Dbackgammon.calibrate=true --rerun-tasks -i > /tmp/cal6c.log 2>&1; grep -E 'GAMMON_COEFFS|BG_COEFFS|gammon rows|realized gammon' /tmp/cal6c.log`
Expected: prints `GAMMON_COEFFS = doubleArrayOf(…)`, `BG_COEFFS = doubleArrayOf(…)`, and the gammon-rows/realized-rate diagnostic. (Takes several minutes — 400 ADVANCED self-play games.)

- [ ] **Step 2: Paste the fitted coefficients** into `GammonModel.kt`, replacing the provisional `GAMMON_COEFFS`/`BG_COEFFS` with the printed arrays. Update the comment to note they are fitted (date) and the realized gammon rate from the run. Change `var` → `val` (no longer mutated).

- [ ] **Step 3: Record results in the spec.** Append a "Calibration results (date)" section to `docs/.../2026-06-19-phase6c-i-gammon-rate-model-design.md` with the fitted coefficients, the gammon-row count, and the realized-vs-(mean-)predicted gammon rate. Pin the guard tolerance (Task 4) from this run.

- [ ] **Step 4: Confirm `GammonModelTest` still passes with the fitted coefficients**

Run: `… ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.GammonModelTest"`
- If it PASSES, proceed. If the `more trapped checkers raises backgammon risk` directional case FAILS (a fitted sign differs from the provisional), do NOT weaken it blindly — in Task 4 re-express it against the fitted model's actual monotonicity (the structural cases must always hold). Note the outcome here.

- [ ] **Step 5: Commit**
```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/GammonModel.kt docs/superpowers/specs/2026-06-19-phase6c-i-gammon-rate-model-design.md
git commit -m "feat(ai): commit fitted gammon/bg coefficients (scaled features, decisive window)"
```

---

## Task 4: Calibration guard + directional-test alignment

**Files:** Create `ai/src/test/kotlin/dk/rlunde/backgammon/ai/GammonCalibrationGuardTest.kt`; adjust `ai/src/test/kotlin/dk/rlunde/backgammon/ai/GammonModelTest.kt` if Task 3 Step 4 flagged a directional case.

- [ ] **Step 1: Write the guard test** — on a small fixed-seed sample, the committed model's mean predicted gammon rate over decisive-window **loser** positions tracks the realized gammon frequency, and race degeneracy holds:

```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.SeededDiceRoller
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Fast, non-gated guard: the committed gammon model is roughly calibrated to self-play, and
 *  gammon risk decays as the loser bears off. Guards against constant-rot / re-calibration drift. */
class GammonCalibrationGuardTest {
    private fun ref(seed: Long) = HeuristicAiPlayer(Difficulty.INTERMEDIATE, Random(seed))

    @Test fun `committed gammon model is roughly calibrated on a fixed sample`() {
        var predSum = 0.0; var realized = 0; var n = 0
        for (g in 0 until 60) {
            val seed = (g + 1).toLong() * 1_099_511_628_211L
            val cg = selfPlayTrajectory(ref(seed xor WHITE_SALT), ref(seed xor BLACK_SALT), SeededDiceRoller(seed), Weights.FULL)
            if (cg.result.outcome == Outcome.TIMEOUT) continue
            val winner = if (cg.result.outcome == Outcome.WHITE_WIN) Player.WHITE else Player.BLACK
            val loserGammoned = cg.result.value >= 2
            // Loser's decisive-window positions: mover is the eventual loser.
            for (s in cg.samples) if (s.decisive && s.mover != winner) {
                predSum += GammonModel.loseRates(s.loserFeatures).gammon
                if (loserGammoned) realized++
                n++
            }
        }
        assertTrue(n > 0, "expected decisive loser positions")
        val meanPred = predSum / n
        val realizedRate = realized.toDouble() / n
        // TOLERANCE pinned from the 6c-1 calibration run (Task 3); start at 0.15, tighten if the run supports it.
        assertTrue(abs(meanPred - realizedRate) < 0.15,
            "gammon model miscalibrated: predicted=$meanPred realized=$realizedRate")
    }

    @Test fun `gammon risk decays as the loser bears off`() {
        val early = GammonModel.loseRates(GammonFeatures(borneOff = 0, pip = 120, backContact = 0)).gammon
        val late = GammonModel.loseRates(GammonFeatures(borneOff = 5, pip = 60, backContact = 0)).gammon
        assertTrue(late < early, "gammon risk should fall once the loser bears off: early=$early late=$late")
    }
}
```
(The borne-off gate makes `late` exactly 0, so the second test is robust regardless of fitted coefficients. If the calibration run in Task 3 showed the `0.15` tolerance is comfortably met, tighten it to match the recorded result.)

- [ ] **Step 2: Run the guard** — Run: `… ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.GammonCalibrationGuardTest"`. Expected: PASS. If `roughly calibrated` fails, the committed coefficients (Task 3) don't generalize to the INTERMEDIATE sample — STOP and report (do not just widen the tolerance past 0.2 without flagging).

- [ ] **Step 3: Align `GammonModelTest` directional case** (only if Task 3 Step 4 flagged it). If the fitted model no longer satisfies "more back-contact → more bg" at the old test points, re-express that test to compare two feature vectors the fitted model actually distinguishes (per the recorded coefficients), keeping the structural cases (`bg ≤ gammon`, borne-off gate, range) unchanged. If Task 3 Step 4 passed, make no change here.

- [ ] **Step 4: Commit**
```bash
git add ai/src/test/kotlin/dk/rlunde/backgammon/ai/GammonCalibrationGuardTest.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/GammonModelTest.kt
git commit -m "test(ai): gammon calibration guard + fitted-model directional alignment"
```

---

## Task 5: Trainer footnote + close 6a defer note

**Files:** Modify `app/.../ui/screens/GameScreen.kt`; `docs/.../2026-06-16-phase6a-equity-foundation-design.md`

- [ ] **Step 1: Update the trainer footnote.** In `GameScreen.kt`'s `AnalysisSheet`, the footnote currently reads `"* win% self-play-calibrated; gammon rates are model estimates (no rollouts)"`. Change to:
```kotlin
                        text = "* win% and gammon rates are self-play-calibrated (no rollouts)",
```

- [ ] **Step 2: Close the 6a defer note.** In `docs/superpowers/specs/2026-06-16-phase6a-equity-foundation-design.md` §10, append to the gammon-deferral paragraph: `**(Closed in slice 6c-1, 2026-06-19: features standardized + decisive-window calibration; coefficients now fitted.)**`

- [ ] **Step 3: Build** — Run: `… ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt docs/superpowers/specs/2026-06-16-phase6a-equity-foundation-design.md
git commit -m "feat(app): trainer footnote — gammon rates now calibrated; close 6a defer note"
```

---

## Task 6: Full verification + APK

- [ ] **Step 1: Run the affected suites** (skip the ~16-min `TierOrderingTest`):

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.GammonModelTest" --tests "dk.rlunde.backgammon.ai.GammonCalibrationGuardTest" --tests "dk.rlunde.backgammon.ai.EquityModelTest" --tests "dk.rlunde.backgammon.ai.WinProbabilityTest" --tests "dk.rlunde.backgammon.ai.EquityCalibration*" :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all listed green.

- [ ] **Step 2: Boundary check** — win-prob/EquityModel/move-selection untouched:
Run: `git diff phase3-strong-ai..HEAD -- ai/src/main | grep -E '^\+' | grep -iE 'WinProbability\.K|Evaluator\.|Expectimax|fromEquity'`
Expected: no additions changing `WinProbability.K`, the `Evaluator`, `Expectimax`, or `EquityModel.distribution` — 6c-1 touches only `GammonModel`.

- [ ] **Step 3: Manual check** (debug APK, training mode): the detail-sheet equity line shows non-degenerate gammon rates — e.g., a clearly-winning racing position shows a plausible G% (not ~0% everywhere as before), and the footnote reads "calibrated".

- [ ] **Step 4:** APK at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Self-Review (completed during planning)

**Spec coverage:** §4 feature standardization (`scaledRow`, divisors, shared) → Task 1. §5 decisive-window labeling (`decisive` flag + predicate + gammon-row gating) → Task 2. §6 recalibration (scaled+gated harness, refit gammon coeffs, win-prob `K` unchanged, diagnostics) → Tasks 2–3. §7 testing (structural invariants kept, directional re-expressed, guard, success bar pinned post-run) → Tasks 1,3,4. §8 trainer footnote → Task 5. §9 files → all tasks. §10 out-of-scope → boundary check Task 6 Step 2.

**Placeholder scan:** the fitted coefficients are pasted in Task 3 from the harness output (not a TBD — a deliberate run-then-commit, as 6a did); the guard tolerance (0.15) is an explicit starting value pinned from the Task 3 run. The directional-test alignment (Task 4 Step 3) is conditional on Task 3 Step 4's observed result, not vague.

**Type consistency:** `GammonModel.scaledRow(GammonFeatures): DoubleArray` used identically in the logistic (Task 1) and the harness (Task 2); `CalibrationSample.decisive: Boolean` set in `selfPlayTrajectory` (Task 2) and read in the harness (Task 2) and guard (Task 4); `GAMMON_COEFFS`/`BG_COEFFS` `var`→`val` across Tasks 1/3; `GammonModel.loseRates`/`GammonFeatures`/`GammonRates` unchanged signatures.
