# Phase 6b-ii — AI Cube Decisions — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn on the doubling cube vs the computer — the human can double the AI (AI takes/drops), and the AI offers doubles on its turn (human takes/drops), driven by a simple threshold on 6a's calibrated win probability.

**Architecture:** Two public `:ai` objects — `CubeAdvisor` (static-eval win-prob facade) and `CubePolicy` (thresholds). `app/game` drops the hot-seat gate on the cube. The AI offers in `AiTurnDriver` (pre-roll) and responds in `GameViewModel.onOfferDouble`. No checker-move-selection change.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx-coroutines-test, kotlin.test/JUnit5.

**Spec:** `docs/superpowers/specs/2026-06-18-phase6b-ii-ai-cube-design.md`

**Build/test** (toolchain not on PATH; see memory `build-environment.md`):
- `:ai` tests: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "..."`
- `:app` tests: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "..."`
- APK: `… ./gradlew :app:assembleDebug`

---

## File structure
- **Create** `ai/.../CubePolicy.kt`, `ai/.../CubeAdvisor.kt` (public).
- **Create** `ai/src/test/.../CubePolicyTest.kt`, `ai/src/test/.../CubeAdvisorTest.kt`.
- **Modify** `app/.../game/GameController.kt` (drop `offerDouble` hot-seat gate), `app/.../game/GameUiState.kt` (`canDouble` gate), `app/.../game/AiTurnDriver.kt` (pre-roll offer + bail), `app/.../viewmodel/GameViewModel.kt` (AI-responds hook).
- **Modify** `app/src/test/.../game/GameUiStateTest.kt` (update `canDouble` cases).
- **Create** `app/src/test/.../viewmodel/GameViewModelCubeAiTest.kt`.

---

## Task 1: `CubePolicy`

**Files:** Create `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubePolicy.kt`; test `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubePolicyTest.kt`

- [ ] **Step 1: Write the failing test**
```kotlin
package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CubePolicyTest {
    @Test fun `doubles at or above the double threshold`() {
        assertTrue(CubePolicy.shouldDouble(0.70))
        assertTrue(CubePolicy.shouldDouble(0.85))
        assertFalse(CubePolicy.shouldDouble(0.69))
    }
    @Test fun `takes at or above the take point, drops below`() {
        assertTrue(CubePolicy.shouldTake(0.21))
        assertTrue(CubePolicy.shouldTake(0.40))
        assertFalse(CubePolicy.shouldTake(0.20))
    }
}
```
- [ ] **Step 2: Run — expect FAIL** (`CubePolicy` unresolved).
  Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.CubePolicyTest"`
- [ ] **Step 3: Implement**
```kotlin
package dk.rlunde.backgammon.ai

/** Provisional cube thresholds on the gammonless single-win prob (spec §3). Refined in 6c. */
object CubePolicy {
    const val DOUBLE_MIN = 0.70  // offer once win-prob reaches the market window
    const val TAKE_MIN = 0.21    // receiver takes if its own win-prob >= ~21% (cubeful live-cube take point)
    fun shouldDouble(winProb: Double): Boolean = winProb >= DOUBLE_MIN
    fun shouldTake(receiverWinProb: Double): Boolean = receiverWinProb >= TAKE_MIN
}
```
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit**
```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubePolicy.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubePolicyTest.kt
git commit -m "feat(ai): CubePolicy double/take thresholds"
```

---

## Task 2: `CubeAdvisor` (win-prob facade)

**Files:** Create `ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeAdvisor.kt`; test `ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeAdvisorTest.kt`

- [ ] **Step 1: Write the failing test**
```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CubeAdvisorTest {
    @Test fun `even start is one half`() {
        assertEquals(0.5, CubeAdvisor.winProb(startingPosition(), Player.WHITE), 1e-6)
    }
    @Test fun `perspectives sum to one`() {
        val s = startingPosition()
        assertEquals(1.0, CubeAdvisor.winProb(s, Player.WHITE) + CubeAdvisor.winProb(s, Player.BLACK), 1e-6)
    }
    @Test fun `leader is well above half`() {
        // BLACK has borne off 13; WHITE none → BLACK win-prob ~1.
        val p = IntArray(26).also { it[24] = -2; it[6] = 8; it[8] = 7 }   // BLACK 2 on 24; WHITE 15 on 6/8
        val s = BoardState(p, mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 0, Player.BLACK to 13), Player.BLACK)
        assertTrue(CubeAdvisor.winProb(s, Player.BLACK) > 0.9, "leader win-prob")
    }
}
```
- [ ] **Step 2: Run — expect FAIL** (`CubeAdvisor` unresolved).
  Run: `… ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.CubeAdvisorTest"`
- [ ] **Step 3: Implement**
```kotlin
package dk.rlunde.backgammon.ai

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Player

/** Public facade: calibrated single-win probability for [perspective] from the static eval (6a §4.3). */
object CubeAdvisor {
    fun winProb(state: BoardState, perspective: Player): Double =
        WinProbability.fromEquity(Evaluator.evaluate(state, perspective, Weights.FULL), GamePhases.of(state))
}
```
- [ ] **Step 4: Run — expect PASS.** (`WinProbability`/`Evaluator`/`Weights`/`GamePhases` are `internal` to `:ai`; `CubeAdvisor` is in the same module so it can use them, and it exposes only `Double` to `:app`.)
- [ ] **Step 5: Commit**
```bash
git add ai/src/main/kotlin/dk/rlunde/backgammon/ai/CubeAdvisor.kt ai/src/test/kotlin/dk/rlunde/backgammon/ai/CubeAdvisorTest.kt
git commit -m "feat(ai): CubeAdvisor static-eval win-prob facade"
```

---

## Task 3: Enable the cube vs the computer

**Files:** Modify `app/.../game/GameController.kt`, `app/.../game/GameUiState.kt`; update `app/src/test/java/dk/rlunde/backgammon/game/GameUiStateTest.kt`

- [ ] **Step 1: Update the `canDouble` tests** (the 6b-i gate was `aiSide == null`; the new gate is `toMove != aiSide`). In `GameUiStateTest`, replace the `canDouble is false during the AI's game` test and add the AI-turn case:
```kotlin
@Test fun `canDouble is true on the human's turn vs computer`() {
    // Human = WHITE to move, AI = BLACK.
    assertEquals(true, state(Phase.NEED_ROLL, Player.WHITE, aiSide = Player.BLACK).canDouble)
}
@Test fun `canDouble is false on the AI's turn`() {
    assertEquals(false, state(Phase.NEED_ROLL, Player.BLACK, aiSide = Player.BLACK).canDouble)
}
```
(Keep the existing hot-seat `canDouble is true at need-roll …`, `… not at need-roll`, and `… opponent owns the cube` tests — they still hold.)

- [ ] **Step 2: Run — expect FAIL** (`canDouble is true on the human's turn vs computer` fails under the old `aiSide == null` gate).
  Run: `… ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.game.GameUiStateTest"`

- [ ] **Step 3: Implement**
In `GameUiState.kt`, change the `canDouble` computed property:
```kotlin
    /** The human on roll may offer a double (both modes). */
    val canDouble: Boolean get() = phase == Phase.NEED_ROLL && cube.mayDouble(toMove) && toMove != aiSide
```
In `GameController.kt`, `offerDouble()` — remove the hot-seat gate line `if (aiSide != null) return` (keep the `phase`/`mayDouble` checks):
```kotlin
    fun offerDouble() {
        if (uiState.phase != Phase.NEED_ROLL) return
        if (!cube.mayDouble(committed.toMove)) return
        doubler = committed.toMove
        uiState = compute()
    }
```

- [ ] **Step 4: Run — expect PASS** (`GameUiStateTest` and the existing `GameControllerCubeTest`; note the 6b-i test `offerDouble is a no-op in a vs-computer game` — it now WOULD offer, so update it: see below).

  In `GameControllerCubeTest`, the 6b-i test `offerDouble is a no-op in a vs-computer game` asserted the cube was inert vs computer. That behaviour changed. Replace it with:
```kotlin
@Test fun `offerDouble works regardless of aiSide (gating is the caller's job)`() {
    val c = GameController(initial = startingPosition(), aiSide = Player.BLACK)
    c.offerDouble()
    assertEquals(Phase.CUBE_OFFERED, c.uiState.phase)
}
```
  Re-run `… --tests "dk.rlunde.backgammon.game.GameControllerCubeTest" --tests "dk.rlunde.backgammon.game.GameUiStateTest"` → PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/dk/rlunde/backgammon/game/GameController.kt app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt app/src/test/java/dk/rlunde/backgammon/game/GameUiStateTest.kt app/src/test/java/dk/rlunde/backgammon/game/GameControllerCubeTest.kt
git commit -m "feat(app): enable the cube vs the computer (human-turn canDouble; policy-free offerDouble)"
```

---

## Task 4: AI offers a double (`AiTurnDriver`) + offer/resume/no-loop VM tests

**Files:** Modify `app/.../game/AiTurnDriver.kt`; create `app/src/test/java/dk/rlunde/backgammon/viewmodel/GameViewModelCubeAiTest.kt`

- [ ] **Step 1: Write the failing VM tests** (rigged boards; mirrors `GameViewModelAnalysisTest`'s deterministic setup):
```kotlin
package dk.rlunde.backgammon.viewmodel

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.DiceRoller
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.CubeResponse
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.game.GameController
import dk.rlunde.backgammon.game.Opponent
import dk.rlunde.backgammon.game.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelCubeAiTest {
    private class FixedRoller(private val d: Dice) : DiceRoller { override fun roll() = d }

    /** BLACK far ahead (13 borne off); WHITE all on the board. [toMove] decides who acts first. */
    private fun blackLeads(toMove: Player) = BoardState(
        IntArray(26).also { it[24] = -2; it[6] = 8; it[8] = 7 },
        mapOf(Player.WHITE to 0, Player.BLACK to 0),
        mapOf(Player.WHITE to 0, Player.BLACK to 13),
        toMove,
    )

    /** Build a vs-computer VM (human = WHITE, AI = BLACK) on a rigged initial board. */
    private fun vm(scope: TestScope, initial: BoardState): GameViewModel {
        val vm = GameViewModel(
            analysisDispatcher = StandardTestDispatcher(scope.testScheduler),
            controllerFactory = { aiSide -> GameController(initial = initial, roller = FixedRoller(Dice(3, 1)), aiSide = aiSide) },
            scopeOverride = scope,
        )
        vm.startGame(GameConfig(opponent = Opponent.COMPUTER, humanColor = Player.WHITE))
        return vm
    }

    @Test fun `AI offers a double when well ahead`() = runTest {
        val v = vm(this, blackLeads(Player.BLACK)) // AI (BLACK) is on roll and dominating
        advanceUntilIdle()
        assertEquals(Phase.CUBE_OFFERED, v.uiState.value.phase)
    }

    @Test fun `human takes the AI's double, then the AI resumes and plays`() = runTest {
        val v = vm(this, blackLeads(Player.BLACK))
        advanceUntilIdle()
        assertEquals(Phase.CUBE_OFFERED, v.uiState.value.phase)
        v.onRespondDouble(CubeResponse.TAKE)   // human (WHITE) takes
        assertEquals(2, v.uiState.value.cube.value)
        assertEquals(Player.WHITE, v.uiState.value.cube.owner)
        advanceUntilIdle()                      // AI resumes its turn
        // No second offer: the AI no longer owns the cube, so it just rolls/plays.
        assertNotEquals(Phase.CUBE_OFFERED, v.uiState.value.phase)
    }
}
```
- [ ] **Step 2: Run — expect FAIL** (`AI offers…` fails: the driver currently rolls instead of offering).
  Run: `… ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.viewmodel.GameViewModelCubeAiTest"`

- [ ] **Step 3: Implement the pre-roll offer + bail in `AiTurnDriver.maybeRunTurn`.** Add the imports `import dk.rlunde.backgammon.ai.CubeAdvisor` and `import dk.rlunde.backgammon.ai.CubePolicy`, and change the guard/opening of `maybeRunTurn`:
```kotlin
    suspend fun maybeRunTurn(
        controller: GameController,
        onThinking: (Boolean) -> Unit,
        publish: () -> Unit,
    ) {
        if (ai == null || aiSide == null || running) return
        if (controller.uiState.toMove != aiSide) return
        if (controller.uiState.phase == Phase.GAME_OVER) return
        if (controller.uiState.phase == Phase.CUBE_OFFERED) return  // a double is pending — not our action

        // Pre-roll: offer a double if clearly ahead and the cube is available.
        if (controller.uiState.cube.mayDouble(aiSide) &&
            CubePolicy.shouldDouble(CubeAdvisor.winProb(controller.uiState.board, aiSide))) {
            controller.offerDouble(); publish()
            return
        }

        running = true
        onThinking(true); publish()
        // ... existing roll → (auto-pass | choose+apply) body unchanged ...
    }
```
(Leave the rest of the function body exactly as-is.)

- [ ] **Step 4: Run — expect PASS** (both tests). Build too: `… ./gradlew :app:assembleDebug`.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/dk/rlunde/backgammon/game/AiTurnDriver.kt app/src/test/java/dk/rlunde/backgammon/viewmodel/GameViewModelCubeAiTest.kt
git commit -m "feat(app): AI offers a double pre-roll when ahead (driver); offer/resume tests"
```

---

## Task 5: AI responds to the human's double (`GameViewModel`) + take/drop tests

**Files:** Modify `app/.../viewmodel/GameViewModel.kt`; extend `GameViewModelCubeAiTest.kt`

- [ ] **Step 1: Add the failing tests** (human offers; AI responds). Add to `GameViewModelCubeAiTest`:
```kotlin
    /** WHITE far ahead (13 borne off); BLACK all on the board — the AI (BLACK) is hopeless. */
    private fun whiteLeads(toMove: Player) = BoardState(
        IntArray(26).also { it[1] = 2; it[19] = 8; it[21] = 7 },  // WHITE 2 on 1; BLACK 15 on 19/21
        mapOf(Player.WHITE to 0, Player.BLACK to 0),
        mapOf(Player.WHITE to 13, Player.BLACK to 0),
        toMove,
    )

    @Test fun `AI takes a human double when not hopeless`() = runTest {
        val v = vm(this, blackLeads(Player.WHITE))  // human (WHITE) on roll, but BLACK (AI) is ahead → AI takes
        v.onOfferDouble()                            // human doubles
        advanceUntilIdle()
        assertEquals(2, v.uiState.value.cube.value)  // AI took
        assertEquals(Player.BLACK, v.uiState.value.cube.owner)
        assertNotEquals(Phase.CUBE_OFFERED, v.uiState.value.phase)
    }

    @Test fun `AI drops a human double when hopeless`() = runTest {
        val v = vm(this, whiteLeads(Player.WHITE))  // human (WHITE) on roll and dominating → AI (BLACK) drops
        v.onOfferDouble()
        advanceUntilIdle()
        assertEquals(Phase.GAME_OVER, v.uiState.value.phase)
        assertEquals(Player.WHITE, v.uiState.value.winner)
        assertEquals(1, v.uiState.value.cube.value)  // dropped at pre-double value
    }
```
- [ ] **Step 2: Run — expect FAIL** (the AI never responds; phase stays `CUBE_OFFERED`).
  Run: `… --tests "dk.rlunde.backgammon.viewmodel.GameViewModelCubeAiTest"`

- [ ] **Step 3: Implement the AI-responds hook.** In `GameViewModel.kt`, replace `onOfferDouble` and add imports `import dk.rlunde.backgammon.ai.CubeAdvisor`, `import dk.rlunde.backgammon.ai.CubePolicy`, `import dk.rlunde.backgammon.core.Player` (Player likely already imported), `import kotlinx.coroutines.withContext`:
```kotlin
    fun onOfferDouble() {
        controller.offerDouble()
        publish()
        // vs-computer: if the human just doubled the AI, the AI takes/drops.
        val ai = aiSide ?: return
        if (controller.uiState.phase != Phase.CUBE_OFFERED) return
        if (controller.uiState.toMove.opponent != ai) return  // responder isn't the AI
        aiJob = scope.launch {
            val winProb = withContext(analysisDispatcher) { CubeAdvisor.winProb(controller.uiState.board, ai) }
            controller.respondDouble(if (CubePolicy.shouldTake(winProb)) CubeResponse.TAKE else CubeResponse.DROP)
            publish()
        }
    }
```
`Phase` is already imported in the VM (used elsewhere); `CubeResponse` was imported in 6b-i. `aiJob` is the existing field (cancelled on `onNewGame`), so a stale response can't land on a fresh game.

- [ ] **Step 4: Run — expect PASS** (all four `GameViewModelCubeAiTest` cases + the existing `GameViewModelCubeTest`/`GameViewModelAnalysisTest`). Build: `… ./gradlew :app:assembleDebug`.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt app/src/test/java/dk/rlunde/backgammon/viewmodel/GameViewModelCubeAiTest.kt
git commit -m "feat(app): AI takes/drops a human double (VM hook); take/drop tests"
```

---

## Task 6: Full verification + APK

- [ ] **Step 1: Compile gate + all suites**
Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :ai:test --tests "dk.rlunde.backgammon.ai.Cube*" :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; new `Cube*` + `GameViewModelCubeAiTest` green. (Skip the full `:ai:test` — `TierOrderingTest` is ~16 min and unaffected.)

- [ ] **Step 2: Boundary check** — move selection untouched:
Run: `grep -rnE 'CubeAdvisor|CubePolicy' ai/src/main app/src/main --include='*.kt'`
Expected: `CubeAdvisor`/`CubePolicy` referenced only by their defs, `AiTurnDriver`, and `GameViewModel` — NOT inside `Expectimax`/`MoveAnalyzer`/`HeuristicAiPlayer` (cube logic never touches checker-move choice).

- [ ] **Step 3: Manual checklist** (debug APK, vs-computer): tap the cube on your turn → AI takes or drops (a brief "thinking" then it resolves); play until the AI is well ahead → it offers a double and you get Take/Drop; take it → the AI plays on (cube shows 2, owned by you), no double-loop; a hopeless human double → AI takes and plays on.

- [ ] **Step 4:** APK at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Self-Review (completed during planning)

**Spec coverage:** §4 `CubeAdvisor`/`CubePolicy` → Tasks 1–2. §5 enable vs computer (offerDouble gate, canDouble) → Task 3. §6 AI offers (pre-roll + `CUBE_OFFERED` bail) → Task 4. §7 AI responds (VM hook) → Task 5. §8 tests (policy/advisor units; AI offers; human-takes-resumes; no-loop; AI takes; AI drops) → Tasks 1,2,4,5. §9 files → all tasks. §10 limitation → carried in spec.

**Placeholder scan:** none. Rigged test boards are concrete (BLACK/WHITE 13-borne-off lopsided positions clear the 0.70/0.21 thresholds by a wide margin, so they're not threshold-edge fragile). Task 4 Step 3 says "existing body unchanged" referencing the verbatim current `maybeRunTurn` body — the engineer keeps it as-is.

**Type consistency:** `CubeAdvisor.winProb(state, perspective): Double`, `CubePolicy.shouldDouble/shouldTake(Double): Boolean`, `canDouble` (`toMove != aiSide`), `offerDouble()` (policy-free), `onOfferDouble()` hook, `respondDouble(CubeResponse)` — consistent across tasks. The two 6b-i tests whose behaviour changes (`canDouble is false during the AI's game`, `offerDouble is a no-op in a vs-computer game`) are explicitly updated in Task 3.
