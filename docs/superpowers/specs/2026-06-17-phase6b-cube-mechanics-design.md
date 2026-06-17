# Phase 6b — Doubling Cube Mechanics + UI: Design Spec

**Status:** Design — ready for plan-writing.
**Date:** 2026-06-17
**Slice:** Second of four in the Phase 6 decomposition (after 6a equity foundation).
**Related:**
- `docs/superpowers/specs/2026-06-16-phase6a-equity-foundation-design.md` (the calibrated `WinProbability` this consumes; §0 has the full 6a–6d slice table)
- `docs/superpowers/specs/2026-06-12-phase6-doubling-cube-design.md` (Phase 6 stub: cube model §2, AI decision §3)
- `docs/backgammon-app-spec.md` §8 (doubling cube)

---

## 1. Goal

Make a **money game with a working doubling cube** playable in both hot-seat and vs-computer modes: offer double → take/drop, a cube indicator on the board, and the game's value scaled by the cube at game over. AI cube decisions use a simple threshold on 6a's calibrated win probability.

## 2. Scope

**In scope**
- `CubeState` (value / owner / centred) + pure transitions, in `app/game`.
- Turn-flow integration in `GameController`: one new phase `CUBE_OFFERED`; actions `offerDouble()` and `respondDouble(take)`.
- Game value at game over = `cube.value × gammon multiplier`, including the drop case.
- AI cube policy in `:ai` (`CubePolicy`) driven by static-eval win-prob; driver/VM wiring for AI-offers and AI-responds.
- UI: cube indicator, "Double" button, take/drop controls, cube-aware result text.
- Both **hot-seat** (either human doubles, the other responds) and **vs-computer**.

**Out of scope (explicit)**
- ❌ Persistence / resume (none exists in the app today — nothing to extend).
- ❌ Session/running score across games; Jacoby rule; beavers/raccoons; automatic doubles.
- ❌ Match play, Crawford, match-equity table — slice 6d.
- ❌ Cubeful equity, recube vig, accurate take point, "too good to double" — slice 6c.
- ❌ Any change to **checker move selection**. The cube decision is the *only* new consumer of win-prob; `Expectimax`/`chooseMove` are untouched.

## 3. Decisions (settled in brainstorming)

- **Per-game stake only.** New Game resets, exactly as today; no cross-game bookkeeping.
- **Both modes** support the cube.
- **AI cube play is deliberately simple and provisional.** Thresholds on the 6a single-win (gammonless) win-prob; no gammon-aware "too good" logic. Real cube skill is 6c.
- **Architecture (Approach A):** cube *state + flow* are turn-flow concerns → they live with `GameController` in `app/game`, not `:core` (which knows nothing of turns/score). The AI *decision* is a pure policy in `:ai`. This keeps each module's existing responsibility intact.

## 4. Cube model (`app/game`)

```kotlin
/** Doubling-cube state. owner == null means centred (either side may double). */
data class CubeState(val value: Int = 1, val owner: Player? = null) {
    val isCentred: Boolean get() = owner == null
    /** The player on roll may double if the cube is centred or they own it, below the cap. */
    fun mayDouble(player: Player, max: Int = 64): Boolean =
        (owner == null || owner == player) && value < max
    /** After a take: value doubles and the cube passes to the taker. */
    fun afterTake(taker: Player): CubeState = CubeState(value * 2, taker)
}
```

Cap at 64 (conventional casual money play). The value changes **only on a take**.

## 5. Turn-flow integration (`GameController`)

Doubling happens at the start of a turn, before the roll.

- **New phase `CUBE_OFFERED`** added to the `Phase` enum.
- **`offerDouble()`** — legal only when `phase == NEED_ROLL` and `cube.mayDouble(committed.toMove)` (and not the AI seat acting through the human path, mirroring the existing `aiSide` guards on `tap`/`commit`). Records `doubler = committed.toMove`; sets phase `CUBE_OFFERED`. Cube value unchanged. No-op otherwise.
- **`respondDouble(take: Boolean)`** — legal only when `phase == CUBE_OFFERED`. The responder is `doubler.opponent`.
  - **take:** `cube = cube.afterTake(taker = doubler.opponent)`; clear the pending-double; phase returns to `NEED_ROLL` with `committed.toMove` unchanged (still the doubler), who rolls and plays.
  - **drop:** set a `dropped` terminal with `droppedWinner = doubler`; phase becomes `GAME_OVER`. Cube value left undoubled.

**State added to `GameController`:** `cube: CubeState`, a pending-double marker (the `doubler`), and `dropped`/`droppedWinner`. `newGame()` resets all three (cube back to centred 1).

**`compute()` changes:**
- Phase: `dropped || Scoring.isGameOver(committed)` → `GAME_OVER`; else pending-double → `CUBE_OFFERED`; else the existing `NEED_ROLL/MOVING/COMMITTABLE` logic.
- Winner/value: if `dropped` → `winner = droppedWinner`, `winValue = 1` (a drop is always a single stake × the cube). Else the existing `Scoring.winnerAndValue`.

## 6. Game value

`GameUiState` gains `cube: CubeState` and `canDouble: Boolean` (`phase == NEED_ROLL && cube.mayDouble(toMove)`). The displayed game value is **`cube.value × winValue`** — uniform across board wins and drops (drop sets `winValue = 1` and leaves the cube undoubled, so the stake is the pre-double value the dropper forfeits). The existing `winner`/`winValue` fields are retained; `cube` is added so the UI can show the indicator and the value breakdown.

## 7. AI cube policy (`:ai`)

```kotlin
/** Provisional cube thresholds on 6a's calibrated single-win prob (gammonless). Refined in 6c. */
object CubePolicy {
    const val DOUBLE_MIN = 0.70  // offer once win-prob reaches the market window
    const val TAKE_MIN = 0.25    // receiver takes if its own win-prob ≥ ~25% (cubeless drop point)
    fun shouldDouble(winProb: Double): Boolean = winProb >= DOUBLE_MIN
    fun shouldTake(receiverWinProb: Double): Boolean = receiverWinProb >= TAKE_MIN
}
```

- Win-prob source: a **static** eval of the committed board from the relevant perspective — `WinProbability.fromEquity(Evaluator.evaluate(board, perspective, Weights.FULL), GamePhases.of(board))`. Cheap; no extra search. (A future slice may upgrade to a searched value.)
- No "too good" gate (needs gammon rates, 6c). At very high win-prob the AI simply doubles to cash, which is correct in money play.

## 8. Driver / VM wiring (`GameViewModel`, `AiTurnDriver`)

Two hooks around the existing AI turn, orchestrated in the VM (analysis/AI orchestration already lives there; the pure `GameController` stays free of AI/policy types):

- **AI on roll:** before the AI rolls, if `cube.mayDouble(aiSide)` and `CubePolicy.shouldDouble(aiWinProb)`, call `offerDouble()` and **stop the AI job** — control returns to the human, who responds via the UI. On a human **take**, the AI resumes its turn (roll + play) via the existing `maybeRunAi()` path; on **drop**, the game is over.
- **AI as responder:** when `phase == CUBE_OFFERED` and the responder (`doubler.opponent`) is `aiSide`, the VM computes the AI's receiver win-prob and calls `respondDouble(CubePolicy.shouldTake(...))`.

Win-prob perspective: for doubling, `aiSide` (the player on roll); for taking, `aiSide` (the receiver).

## 9. UI (`GameScreen`, board canvas)

- **Cube indicator:** a small square showing the cube's current face value, drawn on the side rail via `BoardGeometry` — mid-height when centred, shifted to the owner's half when owned. Visible at all times (value 1, centred, at game start).
- **"Double" button:** shown on the human's turn when `canDouble` (alongside Roll). Tapping → `offerDouble()`.
- **Take / Drop controls:** shown when `phase == CUBE_OFFERED` and the human is the responder (in vs-computer, only when the AI offered; in hot-seat, whenever it is the responder human's choice). Tapping → `respondDouble(take)`.
- **Result text:** game-over message includes the cube, e.g. `"Black wins 4 (gammon × 2-cube)"` and `"White wins 2 (drop)"`.

## 10. Testing

**Pure (`app/game`)**
- `CubeState.mayDouble`: centred → true for both; owned-by-me → true; owned-by-opponent → false; at cap (64) → false. `afterTake` doubles value and sets owner = taker.
- `GameController`: `offerDouble` legal only from `NEED_ROLL` + `mayDouble`, else no-op; phase becomes `CUBE_OFFERED`. `respondDouble(take=true)` doubles the cube, transfers ownership, returns to `NEED_ROLL` with the same player on roll. `respondDouble(take=false)` ends the game with `winner = doubler`, value = pre-double cube value. After a take, the opponent (now owner) may redouble but the original doubler may not. Board-win stake = `cube.value × multiplier`. `newGame()` recentres the cube.

**`:ai`**
- `CubePolicy.shouldDouble`/`shouldTake` threshold boundaries.
- VM tests (injected dispatcher, mirroring existing `GameViewModel` tests): AI offers a double when win-prob is high and it may double; AI takes/drops a human's double per threshold; on a human take the AI resumes and plays.

**UI**
- `canDouble` and responder button-visibility logic; cube-aware result-string formatting.

## 11. Files (anticipated)

- `app/.../game/CubeState.kt` — new (model + transitions).
- `app/.../game/GameController.kt` — phase enum + `offerDouble`/`respondDouble` + cube/dropped state (edit).
- `app/.../game/GameUiState.kt` — `cube`, `canDouble` (edit).
- `ai/.../CubePolicy.kt` — new.
- `app/.../viewmodel/GameViewModel.kt` (+ maybe `AiTurnDriver.kt`) — cube hooks (edit).
- `app/.../ui/screens/GameScreen.kt`, `app/.../ui/board/BoardGeometry.kt` / `BoardCanvas.kt` — indicator + buttons + result text (edit).
- Tests alongside each.

## 12. Open items for the plan (not blockers)

- Exact cube-indicator geometry on the side rail (size, centred vs owned y-offset).
- Whether the AI's cube win-prob should later use a searched value instead of static eval (deferred; static is fine for 6b).
