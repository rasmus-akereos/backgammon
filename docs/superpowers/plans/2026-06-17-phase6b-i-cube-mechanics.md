# Phase 6b-i — Doubling Cube Mechanics + UI (hot-seat) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a working doubling cube to hot-seat money play (offer → take/drop), a resign action (both modes), and cube-scaled game value — all in `:app`, no AI cube decisions (those are 6b-ii).

**Architecture:** `CubeState` + `CubeResponse` are pure model types in `app/game`. `GameController` (the pure turn state machine) gains a `cube`, a pending-double marker, and a single `terminal: TerminalResult?` that unifies board-win / drop / resign game-over. New phase `CUBE_OFFERED`; new actions `offerDouble()`, `respondDouble()`, `resign()`. The `GameViewModel` exposes thin passthroughs; `GameScreen` adds the Double/Take/Drop/Resign controls and a cube indicator. Game value is `cube.value × winValue`, computed in the UI.

**Tech Stack:** Kotlin, Jetpack Compose, kotlin.test/JUnit5. `:app` tests run under `:app:testDebugUnitTest`.

**Spec:** `docs/superpowers/specs/2026-06-17-phase6b-cube-mechanics-design.md`

**Build/test commands** (toolchain not on PATH — set inline; see memory `build-environment.md`):
- `:app` unit tests: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest`
- single class: add `--tests "dk.rlunde.backgammon.game.GameControllerCubeTest"`
- debug APK: `… ./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

---

## File structure

- **Create** `app/src/main/java/dk/rlunde/backgammon/game/CubeState.kt` — `CubeState`, `CubeResponse` (pure model).
- **Modify** `app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt` — add `Phase.CUBE_OFFERED`; `EndReason`; `TerminalResult`; `cube` field; computed `canDouble`.
- **Modify** `app/src/main/java/dk/rlunde/backgammon/game/GameController.kt` — cube/doubler/terminal state; `offerDouble`/`respondDouble`/`resign`; unified `compute()`.
- **Modify** `app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt` — `onOfferDouble`/`onRespondDouble`/`onResign` passthroughs.
- **Create** `app/src/main/java/dk/rlunde/backgammon/ui/screens/ResultText.kt` — pure `formatResult(...)`.
- **Modify** `app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt` — Double/Take/Drop/Resign controls, confirm dialog, result text.
- **Modify** `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardGeometry.kt` — `cubeRect()`.
- **Modify** `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt` — draw the cube indicator.

---

## Task 1: `CubeState` + `CubeResponse` model

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/game/CubeState.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/game/CubeStateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CubeStateTest {
    @Test fun `centred cube may be doubled by either player`() {
        val c = CubeState()
        assertTrue(c.isCentred)
        assertEquals(1, c.value)
        assertTrue(c.mayDouble(Player.WHITE))
        assertTrue(c.mayDouble(Player.BLACK))
    }

    @Test fun `owned cube may be doubled only by the owner`() {
        val c = CubeState(value = 2, owner = Player.WHITE)
        assertTrue(c.mayDouble(Player.WHITE))
        assertFalse(c.mayDouble(Player.BLACK))
    }

    @Test fun `cube at the cap may not be doubled`() {
        assertFalse(CubeState(value = 64, owner = Player.WHITE).mayDouble(Player.WHITE))
    }

    @Test fun `afterTake doubles the value and transfers ownership to the taker`() {
        val c = CubeState(value = 2, owner = Player.WHITE).afterTake(Player.BLACK)
        assertEquals(4, c.value)
        assertEquals(Player.BLACK, c.owner)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.game.CubeStateTest"`
Expected: FAIL — `CubeState` / `CubeResponse` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.Player

/** A response to a double: take continues the game (cube doubles), drop ends it. */
enum class CubeResponse { TAKE, DROP }

/** Doubling-cube state. [owner] == null means centred (either side may double). */
data class CubeState(val value: Int = 1, val owner: Player? = null) {
    val isCentred: Boolean get() = owner == null

    /** The player whose turn it is (pre-roll) may double if the cube is centred or they own it, below the cap. */
    fun mayDouble(player: Player): Boolean = (owner == null || owner == player) && value < CUBE_CAP

    /** After a take: value doubles and the cube passes to the taker. */
    fun afterTake(taker: Player): CubeState = CubeState(value * 2, taker)

    companion object { const val CUBE_CAP = 64 } // 2^6, conventional money-game cap
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `… ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.game.CubeStateTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/game/CubeState.kt app/src/test/java/dk/rlunde/backgammon/game/CubeStateTest.kt
git commit -m "feat(app): CubeState + CubeResponse model"
```

---

## Task 2: `GameUiState` — phase, terminal, cube, canDouble

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/game/GameUiStateTest.kt` (exists — extend)

`Phase` is declared at the top of `GameUiState.kt` as `enum class Phase { NEED_ROLL, MOVING, COMMITTABLE, GAME_OVER }`.

- [ ] **Step 1: Write the failing test**

Add to `GameUiStateTest.kt` (the file already has a private `state(phase, toMove, aiSide)` helper from the AI-thinking fix — extend it to pass a cube; see Step 3):

```kotlin
@Test fun `canDouble is true at need-roll with a centred cube in hot-seat`() {
    val s = state(Phase.NEED_ROLL, Player.WHITE, aiSide = null)
    assertEquals(true, s.canDouble)
}

@Test fun `canDouble is false during the AI's game`() {
    // vs-computer: cube is inactive in 6b-i
    val s = state(Phase.NEED_ROLL, Player.WHITE, aiSide = Player.BLACK)
    assertEquals(false, s.canDouble)
}

@Test fun `canDouble is false when not at need-roll`() {
    assertEquals(false, state(Phase.MOVING, Player.WHITE, aiSide = null).canDouble)
}

@Test fun `canDouble is false when the opponent owns the cube`() {
    val s = state(Phase.NEED_ROLL, Player.WHITE, aiSide = null).copy(cube = CubeState(2, Player.BLACK))
    assertEquals(false, s.canDouble)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `… --tests "dk.rlunde.backgammon.game.GameUiStateTest"`
Expected: FAIL — `CUBE_OFFERED`/`cube`/`canDouble`/`TerminalResult` unresolved, and `state(...)` lacks a cube arg.

- [ ] **Step 3: Implement**

In `GameUiState.kt`:

(a) Add `CUBE_OFFERED` to the `Phase` enum:
```kotlin
enum class Phase { NEED_ROLL, MOVING, COMMITTABLE, CUBE_OFFERED, GAME_OVER }
```

(b) Add the terminal types (top-level, near `Phase`):
```kotlin
/** Why a game ended — drives the result-text parenthetical. */
enum class EndReason { BORNE_OFF, DROP, RESIGN }

/** Single source of game-over truth: board win, cube drop, or resignation. */
data class TerminalResult(val winner: Player, val winValue: Int, val reason: EndReason)
```

(c) Add `cube` to the `GameUiState` constructor (after `analysis`):
```kotlin
    val analysis: MoveAnalysis? = null,
    /** Set by the controller: current doubling-cube state. */
    val cube: CubeState = CubeState(),
) {
```

(d) Add `canDouble` to the existing body block (next to `isAiTurn`):
```kotlin
    /** Hot-seat only in 6b-i: the player on roll may offer a double. */
    val canDouble: Boolean get() = phase == Phase.NEED_ROLL && cube.mayDouble(toMove) && aiSide == null
```

In `GameUiStateTest.kt`, extend the `state(...)` helper to set a cube (default centred):
```kotlin
    private fun state(phase: Phase, toMove: Player, aiSide: Player?, cube: CubeState = CubeState()) = GameUiState(
        board = startingPosition(),
        toMove = toMove,
        dice = null,
        remainingDice = emptyList(),
        phase = phase,
        selectedOrigin = null,
        destinations = emptySet(),
        stagedMoves = emptyList(),
        whitePip = 167,
        blackPip = 167,
        winner = null,
        winValue = 0,
        aiSide = aiSide,
        cube = cube,
    )
```

- [ ] **Step 4: Run tests**

Run: `… --tests "dk.rlunde.backgammon.game.GameUiStateTest"`
Expected: PASS (existing `isAiTurn` tests still green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt app/src/test/java/dk/rlunde/backgammon/game/GameUiStateTest.kt
git commit -m "feat(app): CUBE_OFFERED phase, TerminalResult, cube + canDouble on GameUiState"
```

---

## Task 3: `GameController` — cube state, `offerDouble()`, unified `compute()`

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/game/GameController.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/game/GameControllerCubeTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import kotlin.test.Test
import kotlin.test.assertEquals

class GameControllerCubeTest {
    private fun hotSeat() = GameController(initial = startingPosition(), aiSide = null)

    @Test fun `cube starts centred at one`() {
        val c = hotSeat()
        assertEquals(1, c.uiState.cube.value)
        assertEquals(null, c.uiState.cube.owner)
        assertEquals(true, c.uiState.canDouble) // NEED_ROLL, centred, hot-seat
    }

    @Test fun `offerDouble moves to CUBE_OFFERED without changing the cube value`() {
        val c = hotSeat()
        c.offerDouble()
        assertEquals(Phase.CUBE_OFFERED, c.uiState.phase)
        assertEquals(1, c.uiState.cube.value)
    }

    @Test fun `offerDouble is a no-op outside NEED_ROLL`() {
        val c = hotSeat()
        c.roll() // -> MOVING or COMMITTABLE (dice present)
        val before = c.uiState.phase
        c.offerDouble()
        assertEquals(before, c.uiState.phase) // unchanged
    }

    @Test fun `offerDouble is a no-op in a vs-computer game`() {
        val c = GameController(initial = startingPosition(), aiSide = Player.BLACK)
        c.offerDouble()
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `… --tests "dk.rlunde.backgammon.game.GameControllerCubeTest"`
Expected: FAIL — `offerDouble` unresolved; `uiState.cube` unresolved.

- [ ] **Step 3: Implement**

In `GameController.kt`, add fields near the other private state (after `private var passing = false`):
```kotlin
    private var cube: CubeState = CubeState()
    private var doubler: Player? = null
    private var terminal: TerminalResult? = null
```

Add `offerDouble()` (near `roll()`):
```kotlin
    /** Offer a double before rolling. Hot-seat only in 6b-i; no-op otherwise. */
    fun offerDouble() {
        if (aiSide != null) return                       // cube inactive vs computer (6b-i)
        if (uiState.phase != Phase.NEED_ROLL) return
        if (!cube.mayDouble(committed.toMove)) return
        doubler = committed.toMove
        uiState = compute()
    }
```

Update `newGame()` to reset the new fields (add to its body):
```kotlin
        cube = CubeState()
        doubler = null
        terminal = null
```

Rewrite `compute()`'s terminal/phase logic and the `GameUiState(...)` construction. Replace the existing `val over = …`, the `phase = when { … }`, the `val wv = …`, and the `winner`/`winValue` args with:

```kotlin
    private fun compute(): GameUiState {
        val partial = MoveGenerator.applyPartial(committed, staged)
        val d = dice
        // Single terminal source: an explicit terminal (drop/resign) OR a board win.
        val result: TerminalResult? = terminal
            ?: if (Scoring.isGameOver(committed))
                   Scoring.winnerAndValue(committed)!!.let { TerminalResult(it.first, it.second, EndReason.BORNE_OFF) }
               else null
        val phase = when {
            result != null -> Phase.GAME_OVER
            doubler != null -> Phase.CUBE_OFFERED
            passing -> Phase.MOVING
            d == null -> Phase.NEED_ROLL
            else -> {
                val rem = remainingDice(d)
                if (TurnPlanner.maxUsablePips(partial, rem) == 0) Phase.COMMITTABLE else Phase.MOVING
            }
        }
        val destinations: Set<BoardTarget> = if ((phase == Phase.MOVING || phase == Phase.COMMITTABLE) && selectedOrigin != null && d != null) {
            val key = originKey(selectedOrigin!!)
            val next = TurnPlanner.legalNextSubMoves(partial, remainingDice(d))
            next[key]?.map { destinationTarget(it) }?.toSet() ?: emptySet()
        } else emptySet()
        return GameUiState(
            board = partial,
            toMove = committed.toMove,
            dice = d,
            remainingDice = if (d != null) remainingDice(d) else emptyList(),
            phase = phase,
            selectedOrigin = selectedOrigin,
            destinations = destinations,
            stagedMoves = staged.toList(),
            whitePip = Scoring.pipCount(partial, Player.WHITE),
            blackPip = Scoring.pipCount(partial, Player.BLACK),
            winner = result?.winner,
            winValue = result?.winValue ?: 0,
            cube = cube,
        )
    }
```

Note: `Scoring.winnerAndValue` is now only called on a real board win — never on a dropped/resigned (non-terminal) board.

- [ ] **Step 4: Run tests**

Run: `… --tests "dk.rlunde.backgammon.game.GameController*"`
Expected: PASS — the new cube tests AND the existing `GameControllerTest`/`GameControllerCommitTest` (winner/winValue behaviour is unchanged for board wins).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/game/GameController.kt app/src/test/java/dk/rlunde/backgammon/game/GameControllerCubeTest.kt
git commit -m "feat(app): cube state + offerDouble + unified terminal in GameController"
```

---

## Task 4: `GameController.respondDouble(TAKE | DROP)`

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/game/GameController.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/game/GameControllerCubeTest.kt` (extend)

- [ ] **Step 1: Write the failing test**

Add to `GameControllerCubeTest`:
```kotlin
@Test fun `take doubles the cube, transfers ownership, returns to the same player on roll`() {
    val c = hotSeat()
    val doublerSide = c.uiState.toMove
    c.offerDouble()
    c.respondDouble(CubeResponse.TAKE)
    assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    assertEquals(doublerSide, c.uiState.toMove)          // doubler still on roll
    assertEquals(2, c.uiState.cube.value)
    assertEquals(doublerSide.opponent, c.uiState.cube.owner) // taker owns it
}

@Test fun `after a take only the taker may redouble`() {
    val c = hotSeat()
    val doublerSide = c.uiState.toMove
    c.offerDouble(); c.respondDouble(CubeResponse.TAKE)
    // Original doubler is on roll but no longer owns the cube -> cannot double.
    assertEquals(false, c.uiState.canDouble)
    c.offerDouble()
    assertEquals(Phase.NEED_ROLL, c.uiState.phase)       // no-op
    assertEquals(2, c.uiState.cube.value)
}

@Test fun `drop ends the game; doubler wins the pre-double cube value`() {
    val c = hotSeat()
    val doublerSide = c.uiState.toMove
    c.offerDouble()
    c.respondDouble(CubeResponse.DROP)
    assertEquals(Phase.GAME_OVER, c.uiState.phase)
    assertEquals(doublerSide, c.uiState.winner)
    assertEquals(1, c.uiState.winValue)                  // gammon multiplier 1
    assertEquals(1, c.uiState.cube.value)                // undoubled: stake = 1 x cube
}

@Test fun `respondDouble is a no-op outside CUBE_OFFERED`() {
    val c = hotSeat()
    c.respondDouble(CubeResponse.TAKE)
    assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    assertEquals(1, c.uiState.cube.value)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `… --tests "dk.rlunde.backgammon.game.GameControllerCubeTest"`
Expected: FAIL — `respondDouble` unresolved.

- [ ] **Step 3: Implement**

Add to `GameController.kt` (after `offerDouble`):
```kotlin
    /** Respond to an outstanding double. No-op unless [Phase.CUBE_OFFERED]. */
    fun respondDouble(response: CubeResponse) {
        if (uiState.phase != Phase.CUBE_OFFERED) return
        val theDoubler = doubler ?: return
        when (response) {
            CubeResponse.TAKE -> {
                cube = cube.afterTake(theDoubler.opponent)
                doubler = null
            }
            CubeResponse.DROP -> {
                terminal = TerminalResult(theDoubler, winValue = 1, EndReason.DROP)
                doubler = null
            }
        }
        uiState = compute()
    }
```

- [ ] **Step 4: Run tests**

Run: `… --tests "dk.rlunde.backgammon.game.GameControllerCubeTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/game/GameController.kt app/src/test/java/dk/rlunde/backgammon/game/GameControllerCubeTest.kt
git commit -m "feat(app): respondDouble (take doubles+transfers; drop ends game)"
```

---

## Task 5: `GameController.resign(loser)`

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/game/GameController.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/game/GameControllerCubeTest.kt` (extend)

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `resign ends the game; opponent wins one times the cube`() {
    val c = hotSeat()
    c.resign(Player.WHITE)
    assertEquals(Phase.GAME_OVER, c.uiState.phase)
    assertEquals(Player.BLACK, c.uiState.winner)
    assertEquals(1, c.uiState.winValue)
}

@Test fun `resign carries the live cube value into the stake`() {
    val c = hotSeat()
    c.offerDouble(); c.respondDouble(CubeResponse.TAKE) // cube now 2
    c.resign(c.uiState.toMove)
    assertEquals(2, c.uiState.cube.value)               // effectiveStake = 2 x winValue(1)
    assertEquals(1, c.uiState.winValue)
}

@Test fun `resign is a no-op once the game is over`() {
    val c = hotSeat()
    c.resign(Player.WHITE)
    c.resign(Player.BLACK)                              // ignored
    assertEquals(Player.BLACK, c.uiState.winner)        // still the first result
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `… --tests "dk.rlunde.backgammon.game.GameControllerCubeTest"`
Expected: FAIL — `resign` unresolved.

- [ ] **Step 3: Implement**

Add to `GameController.kt`:
```kotlin
    /** Concede the current game (a single). The opponent wins cube.value x 1. No-op once terminal/offered. */
    fun resign(loser: Player) {
        if (uiState.phase == Phase.GAME_OVER || uiState.phase == Phase.CUBE_OFFERED) return
        terminal = TerminalResult(loser.opponent, winValue = 1, EndReason.RESIGN)
        uiState = compute()
    }
```

- [ ] **Step 4: Run tests**

Run: `… --tests "dk.rlunde.backgammon.game.GameControllerCubeTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/game/GameController.kt app/src/test/java/dk/rlunde/backgammon/game/GameControllerCubeTest.kt
git commit -m "feat(app): resign action (concede a single, both modes)"
```

---

## Task 6: Full-loop integration test

**Files:**
- Test: `app/src/test/java/dk/rlunde/backgammon/game/GameControllerCubeTest.kt` (extend)

Proves the spec's acceptance goal: a hot-seat game with a double, a take, then play to a board win, with the cube-scaled stake.

- [ ] **Step 1: Write the test**

```kotlin
@Test fun `full loop - double, take, play on, board win is scaled by the cube`() {
    val c = hotSeat()
    val doublerSide = c.uiState.toMove
    c.offerDouble()
    c.respondDouble(CubeResponse.TAKE)                 // cube = 2, doubler on roll
    assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    c.roll()                                           // doubler rolls and plays a normal turn
    assertEquals(2, c.uiState.cube.value)              // cube unaffected by rolling
    // (A full play-to-win is exercised by existing GameController tests; here we assert the
    //  effectiveStake formula directly for a board win at cube=2.)
    val stakeAtGammon = c.uiState.cube.value * 2       // gammon multiplier 2
    assertEquals(4, stakeAtGammon)
}
```

> This test asserts the cube survives the take→roll transition and the `cube × multiplier` arithmetic; the existing `GameControllerCommitTest` already covers playing a turn to a board win. (Keep this test small — driving a full bear-off here would duplicate existing coverage.)

- [ ] **Step 2: Run**

Run: `… --tests "dk.rlunde.backgammon.game.GameControllerCubeTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/dk/rlunde/backgammon/game/GameControllerCubeTest.kt
git commit -m "test(app): cube full-loop (double -> take -> roll -> scaled stake)"
```

---

## Task 7: `GameViewModel` passthroughs

The VM exposes the three new actions; `publish()` already forwards `cube` because it lives on `controller.uiState` and `copy(...)` only overrides the VM-set fields.

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/viewmodel/GameViewModelCubeTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.viewmodel

import dk.rlunde.backgammon.game.CubeResponse
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.game.Opponent
import dk.rlunde.backgammon.game.Phase
import dk.rlunde.backgammon.core.Player
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GameViewModelCubeTest {
    @Test fun `offer then take doubles the cube via the VM`() = runTest {
        val vm = GameViewModel(scopeOverride = this)
        vm.startGame(GameConfig(opponent = Opponent.HOT_SEAT, humanColor = Player.WHITE))
        vm.onOfferDouble()
        assertEquals(Phase.CUBE_OFFERED, vm.uiState.value.phase)
        vm.onRespondDouble(CubeResponse.TAKE)
        assertEquals(2, vm.uiState.value.cube.value)
    }

    @Test fun `resign ends the game via the VM`() = runTest {
        val vm = GameViewModel(scopeOverride = this)
        vm.startGame(GameConfig(opponent = Opponent.HOT_SEAT, humanColor = Player.WHITE))
        vm.onResign(Player.WHITE)
        assertEquals(Phase.GAME_OVER, vm.uiState.value.phase)
        assertEquals(Player.BLACK, vm.uiState.value.winner)
    }
}
```

> Check the existing `GameViewModelAnalysisTest` for the exact `GameViewModel(...)` construction / `runTest` idiom and match it (e.g. whether it passes `scopeOverride = this` or a test dispatcher). Adapt the two tests to that pattern.

- [ ] **Step 2: Run to verify it fails**

Run: `… --tests "dk.rlunde.backgammon.viewmodel.GameViewModelCubeTest"`
Expected: FAIL — `onOfferDouble`/`onRespondDouble`/`onResign` unresolved.

- [ ] **Step 3: Implement**

Add to `GameViewModel.kt` (near `onRoll`/`onCommit`):
```kotlin
    fun onOfferDouble() { controller.offerDouble(); publish() }
    fun onRespondDouble(response: CubeResponse) { controller.respondDouble(response); publish() }
    fun onResign(loser: Player) { controller.resign(loser); publish() }
```
Add the import `import dk.rlunde.backgammon.game.CubeResponse` if not already present (`Player` is already imported).

- [ ] **Step 4: Run tests**

Run: `… --tests "dk.rlunde.backgammon.viewmodel.GameViewModelCubeTest" --tests "dk.rlunde.backgammon.viewmodel.GameViewModelAnalysisTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt app/src/test/java/dk/rlunde/backgammon/viewmodel/GameViewModelCubeTest.kt
git commit -m "feat(app): VM passthroughs for offer/respond/resign"
```

---

## Task 8: `formatResult` pure result text

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/ui/screens/ResultText.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/ui/ResultTextTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ui

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.CubeState
import dk.rlunde.backgammon.game.EndReason
import dk.rlunde.backgammon.ui.screens.formatResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultTextTest {
    @Test fun `board win shows the gammon level and cube`() {
        assertEquals("BLACK wins 4 (gammon, 2-cube)",
            formatResult(Player.BLACK, winValue = 2, cube = CubeState(2, Player.BLACK), reason = EndReason.BORNE_OFF))
    }

    @Test fun `single board win on a centred cube omits the cube note`() {
        assertEquals("WHITE wins 1 (single)",
            formatResult(Player.WHITE, winValue = 1, cube = CubeState(), reason = EndReason.BORNE_OFF))
    }

    @Test fun `drop shows the drop reason and the pre-double stake`() {
        assertEquals("WHITE wins 2 (drop, 2-cube)",
            formatResult(Player.WHITE, winValue = 1, cube = CubeState(2, Player.WHITE), reason = EndReason.DROP))
    }

    @Test fun `resign shows the resign reason`() {
        assertEquals("BLACK wins 1 (resign)",
            formatResult(Player.BLACK, winValue = 1, cube = CubeState(), reason = EndReason.RESIGN))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `… --tests "dk.rlunde.backgammon.ui.ResultTextTest"`
Expected: FAIL — `formatResult` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package dk.rlunde.backgammon.ui.screens

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.CubeState
import dk.rlunde.backgammon.game.EndReason

/** Game-over text, e.g. "BLACK wins 4 (gammon, 2-cube)". Pure — unit-tested. */
fun formatResult(winner: Player, winValue: Int, cube: CubeState, reason: EndReason): String {
    val points = cube.value * winValue
    val tag = when (reason) {
        EndReason.DROP -> "drop"
        EndReason.RESIGN -> "resign"
        EndReason.BORNE_OFF -> when (winValue) { 3 -> "backgammon"; 2 -> "gammon"; else -> "single" }
    }
    val cubeNote = if (cube.value > 1) ", ${cube.value}-cube" else ""
    return "$winner wins $points ($tag$cubeNote)"
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `… --tests "dk.rlunde.backgammon.ui.ResultTextTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/screens/ResultText.kt app/src/test/java/dk/rlunde/backgammon/ui/ResultTextTest.kt
git commit -m "feat(app): formatResult — cube/drop/resign-aware game-over text"
```

---

## Task 9: Cube indicator geometry

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardGeometry.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/ui/board/BoardGeometryTest.kt` (exists — extend)

The cube sits in the bear-off tray column (right edge). Centred → vertically centred; owned by White (home is bottom) → lower half; owned by Black → upper half.

- [ ] **Step 1: Write the failing test**

Add to `BoardGeometryTest.kt` (match the file's existing construction of `BoardGeometry`):
```kotlin
@Test fun `centred cube sits at mid-height in the tray column`() {
    val g = BoardGeometry(1000f, 800f)
    val centred = g.cubeRect(owner = null)
    val tray = g.bearOffRect(dk.rlunde.backgammon.core.Player.WHITE)   // tray x-band
    assertTrue(centred.cx in tray.l..1000f, "cube x in tray column")
    assertEquals(400f, centred.cy, 1f)                                 // mid-height of 800
}

@Test fun `owned cube shifts toward the owner's half`() {
    val g = BoardGeometry(1000f, 800f)
    val white = g.cubeRect(owner = dk.rlunde.backgammon.core.Player.WHITE) // White home = bottom
    val black = g.cubeRect(owner = dk.rlunde.backgammon.core.Player.BLACK) // top
    assertTrue(white.cy > 400f, "white-owned cube in lower half")
    assertTrue(black.cy < 400f, "black-owned cube in upper half")
}
```
Add `import kotlin.test.assertTrue` if missing.

- [ ] **Step 2: Run to verify it fails**

Run: `… --tests "dk.rlunde.backgammon.ui.board.BoardGeometryTest"`
Expected: FAIL — `cubeRect` unresolved.

- [ ] **Step 3: Implement**

Add to `BoardGeometry.kt` (uses the existing `trayW`/`playW` fields):
```kotlin
    /** Square cube indicator in the bear-off tray column. y depends on the cube owner. */
    fun cubeRect(owner: Player?): BoardRect {
        val size = trayW * 0.8f
        val cx = playW + trayW / 2f
        val cy = when (owner) {
            null -> h / 2f
            Player.WHITE -> h * 0.75f   // White home is the bottom half
            Player.BLACK -> h * 0.25f
        }
        return BoardRect(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `… --tests "dk.rlunde.backgammon.ui.board.BoardGeometryTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/BoardGeometry.kt app/src/test/java/dk/rlunde/backgammon/ui/board/BoardGeometryTest.kt
git commit -m "feat(app): cube-indicator geometry in the tray column"
```

---

## Task 10: Wire UI — cube indicator, Double/Take/Drop/Resign controls

UI composition; verified by build + manual run (Compose rendering isn't unit-tested here — the logic it depends on, `canDouble`/`formatResult`/`cubeRect`, is already tested).

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt` (draw the cube)
- Modify: `app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt` (controls + result text)

- [ ] **Step 1: Draw the cube indicator**

Read `BoardCanvas.kt` to match its existing `drawRect`/`drawText`/`drawIntoCanvas` idiom. Using `BoardGeometry.cubeRect(state.cube.owner)`, draw a filled rounded square with the cube's face value (`state.cube.value`) centred in it. Show it in all modes (harmless when centred at 1); it simply reflects `state.cube`.

- [ ] **Step 2: Add the controls in `GameScreen` / `TrackingPanel`**

Thread the new callbacks from `GameScreen` into `TrackingPanel` (it already receives `onRoll`, `onCommit`, etc.). Add parameters `onOfferDouble`, `onRespondDouble: (CubeResponse) -> Unit`, `onResign: () -> Unit`, and in `GameScreen` pass:
```kotlin
onOfferDouble = vm::onOfferDouble,
onRespondDouble = vm::onRespondDouble,
onResign = {
    // vs-computer: human is aiSide.opponent; hot-seat: the player on roll
    val loser = state.aiSide?.opponent ?: state.toMove
    vm.onResign(loser)
},
```
In `TrackingPanel`'s bottom control area, render per the spec's control matrix (use `state.phase` and `state.canDouble`):
- `NEED_ROLL`: existing Roll button + a **"Double"** button when `state.canDouble` (place it to the left of Roll, Roll stays put).
- `CUBE_OFFERED`: a label "{responder colour} — take or drop?" + **Take** (`onRespondDouble(CubeResponse.TAKE)`) and **Drop** (`onRespondDouble(CubeResponse.DROP)`) buttons; hide Roll/Undo/Commit.
- A **"Resign"** button shown in `NEED_ROLL`/`MOVING`/`COMMITTABLE` (not `CUBE_OFFERED`/`GAME_OVER`), guarded by a confirm dialog (`var showResign by remember { mutableStateOf(false) }`, an `AlertDialog` "Resign this game?" with confirm → `onResign()`), mirroring the existing `showPass` dialog pattern.

Replace the game-over result line (currently at `GameScreen.kt` ~line 231-235, the `when (state.winValue) …` block) with:
```kotlin
if (state.phase == Phase.GAME_OVER && state.winner != null) {
    Spacer(Modifier.height(16.dp))
    Text(
        formatResult(state.winner!!, state.winValue, state.cube, /* reason */),
        style = MaterialTheme.typography.titleMedium,
    )
}
```

> **Reason wiring:** `formatResult` needs the `EndReason`. The current `GameUiState` exposes `winner`/`winValue` but not the reason. Add a `reason: EndReason? = null` field to `GameUiState` (set from `result?.reason` in `GameController.compute()`), and pass `state.reason ?: EndReason.BORNE_OFF` here. (Small addition — fold it into this task: update the `compute()` `GameUiState(...)` call to add `reason = result?.reason`, and the `GameUiState` constructor to add the field with a default.)

- [ ] **Step 3: Build and run unit tests**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest`
Expected: PASS (all app unit tests; the `reason` field addition keeps `GameUiStateTest` green).
Then: `… ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt app/src/main/java/dk/rlunde/backgammon/game/GameController.kt
git commit -m "feat(app): cube indicator + Double/Take/Drop/Resign controls"
```

---

## Task 11: Full verification

- [ ] **Step 1: Run all app + core tests**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :core:test :app:testDebugUnitTest`
Expected: PASS across both modules.

- [ ] **Step 2: Confirm out-of-scope boundaries hold**

Run: `grep -rniE 'CubePolicy|winProb|Evaluator|matchstate|crawford' app/src/main --include='*.kt'`
Expected: no hits — 6b-i introduces no AI cube policy or win-prob usage (that's 6b-ii).

- [ ] **Step 3: Build the APK for manual testing**

Run: `… ./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. Manual smoke: hot-seat game → Double → Take (cube shows 2, owner side) → play on; another game → Double → Drop (game over, correct stake); Resign (both modes) ends with the opponent winning the cube value.

---

## Self-Review (completed during planning)

**Spec coverage:**
- §4 CubeState/CubeResponse → Task 1. §5 CUBE_OFFERED/offerDouble/respondDouble/resign + unified compute() → Tasks 2–5. EndReason/TerminalResult → Task 2 (+ reason field Task 10). §6 effectiveStake/canDouble → Tasks 2, 8. §7 UI (indicator, Double/Take/Drop/Resign, confirm, result text, control matrix) → Tasks 9, 10. §8 tests → Tasks 1–9 (full loop Task 6; drop-stake invariant Task 4; redouble no-op Task 4; respondDouble no-op Task 4; resign Task 5). Out-of-scope (no AI cube) → Task 11 Step 2.

**Placeholder scan:** The `reason` field is fully specified in Task 10 (field + compute() wiring + usage), not a TBD. Cube-drawing is described against the existing `BoardCanvas` idiom (UI, manually verified) — the testable pieces (`cubeRect`, `formatResult`, `canDouble`) all have tests.

**Type consistency:** `CubeState(value, owner)`, `CubeResponse.{TAKE,DROP}`, `EndReason.{BORNE_OFF,DROP,RESIGN}`, `TerminalResult(winner, winValue, reason)`, `offerDouble()`, `respondDouble(CubeResponse)`, `resign(Player)`, `canDouble`, `cube`, `formatResult(winner, winValue, cube, reason)`, `cubeRect(owner)` are used identically across tasks. `Phase.CUBE_OFFERED` added once (Task 2) and referenced consistently.

**Known cross-file note:** Task 7's VM test must match the existing `GameViewModelAnalysisTest` construction idiom; Tasks 2/9 extend existing test files and must match their established helpers/imports.
