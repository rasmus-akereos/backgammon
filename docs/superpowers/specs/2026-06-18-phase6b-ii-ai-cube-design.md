# Phase 6b-ii — AI Cube Decisions: Design Spec

**Status:** Design — ready for plan-writing.
**Date:** 2026-06-18
**Slice:** Phase 6, slice 6b-ii (AI cube decisions). Turns on doubling vs the computer; builds on 6b-i (cube mechanics) and 6a (calibrated win-prob).
**Related:**
- `docs/superpowers/specs/2026-06-17-phase6b-cube-mechanics-design.md` §10 (the deferred items this implements)
- `docs/superpowers/specs/2026-06-16-phase6a-equity-foundation-design.md` (calibrated `WinProbability`)
- `docs/superpowers/specs/2026-06-12-phase6-doubling-cube-design.md` §3 (cube-decision intent; the "do not ship 25%" take-point warning)

---

## 1. Goal

Make the doubling cube fully functional in **vs-computer** games: the human can double the AI (the AI takes/drops), and the AI offers doubles on its own turn (the human takes/drops). Decisions use a simple threshold on 6a's calibrated single-win probability. **Done looks like:** in a vs-computer game the human can tap the cube to double and the AI responds sensibly; the AI offers a double when it's clearly ahead and the human can take/drop; the game ends or continues with the correct cube value.

## 2. Scope

**In scope**
- `:ai` — a public `CubeAdvisor` win-prob facade and a public `CubePolicy` (thresholds).
- `app/game` — enable the cube vs the computer: drop `offerDouble`'s hot-seat gate; widen `canDouble` to the human's turn in both modes.
- `AiTurnDriver` — a pre-roll cube-offer check; bail on `CUBE_OFFERED`.
- `GameViewModel` — the AI-responds hook in `onOfferDouble`.

**Out of scope**
- ❌ "Too good to double" / gammon-aware cube math, accurate cubeful take points → **6c**. The AI uses gammonless single-win thresholds and may over-cube gammonish positions (documented limitation).
- ❌ Searched (expectimax) win-prob for cube decisions — static eval only (a 6c-or-later upgrade).
- ❌ Match play, Crawford, beavers, Jacoby — 6d / out of scope.
- ❌ Any change to checker move selection.

## 3. Decisions (brainstorming)

- **Static eval win-prob** for AI cube decisions (cheap, deterministic; matches the 6b-i plan). The Phase-6 stub's "searched value" ideal is deferred.
- **Thresholds (provisional):** `DOUBLE_MIN = 0.70`, `TAKE_MIN = 0.21` (the cubeful live-cube take point — **not** the dead-cube 0.25, per stub §3). Refined with gammon awareness in 6c.

## 4. `:ai` additions

Both are `public` and expose only `Double`/`core` types (no `internal` `:ai` type crosses the module boundary — the 6a/6b-i constraint).

```kotlin
/** Public facade: calibrated single-win probability for [perspective] from the static eval (spec §4.3 of 6a). */
object CubeAdvisor {
    fun winProb(state: BoardState, perspective: Player): Double =
        WinProbability.fromEquity(Evaluator.evaluate(state, perspective, Weights.FULL), GamePhases.of(state))
}

/** Provisional cube thresholds on the gammonless single-win prob. Refined in 6c. */
object CubePolicy {
    const val DOUBLE_MIN = 0.70  // offer once win-prob reaches the market window
    const val TAKE_MIN = 0.21    // receiver takes if its own win-prob >= ~21% (cubeful live-cube take point)
    fun shouldDouble(winProb: Double): Boolean = winProb >= DOUBLE_MIN
    fun shouldTake(receiverWinProb: Double): Boolean = receiverWinProb >= TAKE_MIN
}
```
Note: `Evaluator.evaluate(state, perspective, ...)` takes an explicit `perspective` independent of `state.toMove` — correct for the take decision, where the AI receiver is **not** the side on roll.

## 5. Enable the cube vs the computer (`app/game`)

- **`GameController.offerDouble()`** — remove the 6b-i `if (aiSide != null) return` gate. Preconditions become just `phase == NEED_ROLL` and `cube.mayDouble(committed.toMove)`. Legitimacy of the caller is enforced by the UI (only the human's turn surfaces the affordance) and the driver (the AI calls it only on its own turn). No new state.
- **`GameUiState.canDouble`** — change `aiSide == null` → `toMove != aiSide`:
  `val canDouble get() = phase == Phase.NEED_ROLL && cube.mayDouble(toMove) && toMove != aiSide`.
  In hot-seat (`aiSide == null`) this is true for both players (unchanged behaviour); in vs-computer it is true only on the human's turn, so the board cube-tap / Double affordance now works against the AI.

## 6. AI offers a double (`AiTurnDriver`, pre-roll)

At the **top** of `maybeRunTurn`, before rolling:
1. Keep the existing guards (`ai`/`aiSide` null, `running`, `toMove != aiSide`, `GAME_OVER`), and **add**: if `phase == CUBE_OFFERED` → return (a double is pending; not the AI's action).
2. New pre-roll check: if `controller.uiState.cube.mayDouble(aiSide)` and `CubePolicy.shouldDouble(CubeAdvisor.winProb(controller.uiState.board, aiSide))` → `controller.offerDouble()`, `publish()`, and **return** (do not roll). The job ends; the human now sees Take/Drop.

On the human's **take**, the cube transfers to the human, so `cube.mayDouble(aiSide)` is now false → when `maybeRunAi()` relaunches the AI's turn it skips the offer and rolls normally (**no re-double loop**). On **drop**, the game is over.

The driver references the public `CubeAdvisor`/`CubePolicy` (`:ai`) and `controller.uiState.cube`/`board`. At `NEED_ROLL` the partial board equals the committed board, so `controller.uiState.board` is the right input.

## 7. AI responds to the human's double (`GameViewModel.onOfferDouble`)

After the human offers (`controller.offerDouble()` then `publish()`), if `controller.uiState.phase == CUBE_OFFERED` and `controller.uiState.toMove.opponent == aiSide` (the doubler is the human; the responder is the AI), the VM launches a short off-thread job (a brief "thinking" pace via the injected dispatcher, mirroring `AiTurnDriver`) that:
- computes `receiverWinProb = CubeAdvisor.winProb(controller.uiState.board, aiSide)`,
- calls `controller.respondDouble(if (CubePolicy.shouldTake(receiverWinProb)) CubeResponse.TAKE else CubeResponse.DROP)`,
- `publish()`.

After a **take**, control returns to `NEED_ROLL` with the **human** on roll (the doubler) — no `maybeRunAi()` needed. After a **drop**, the game is over. The job is guarded by the existing epoch/cancellation pattern (cancelled on new game / reset) so a stale response can't land on a fresh controller.

## 8. Testing

**`:ai` (unit)**
- `CubePolicy.shouldDouble`/`shouldTake` threshold boundaries.
- `CubeAdvisor.winProb`: 0.5 at the symmetric start; monotonic (a lopsided position for the leader > 0.5); perspective-symmetric (`winProb(s, WHITE) + winProb(s, BLACK) ≈ 1`).

**`app` (VM, injected `StandardTestDispatcher`/`TestScope`, rigged `controllerFactory` board — mirrors `GameViewModelAnalysisTest`)**
- **AI offers:** vs-computer, AI to roll, board rigged so `CubeAdvisor.winProb(board, aiSide) ≥ 0.70` → after `advanceUntilIdle()` the phase is `CUBE_OFFERED` (the AI offered instead of rolling).
- **Human takes the AI's double → AI resumes:** from that state, `onRespondDouble(TAKE)` → cube doubled, owner = human; `advanceUntilIdle()` → AI rolls and plays; phase advances and it's the human's turn (no second offer).
- **AI responds to a human double — take:** vs-computer, human to roll, board rigged so the AI's receiver win-prob ≥ 0.21 → `onOfferDouble()`, `advanceUntilIdle()` → cube doubled (owner AI), phase past `CUBE_OFFERED`, human on roll.
- **AI responds — drop:** board rigged so the AI's receiver win-prob < 0.21 → `onOfferDouble()`, `advanceUntilIdle()` → `GAME_OVER`, winner = human, value = pre-double cube value.
- **No re-double loop:** AI offers → human takes → AI's resumed turn does not offer again.

**Boundary:** existing suites stay green; no checker-move-selection change (the search still ranks by raw eval).

## 9. Files (anticipated)

- `ai/.../CubeAdvisor.kt` — new (public facade).
- `ai/.../CubePolicy.kt` — new (public thresholds).
- `ai/src/test/.../CubePolicyTest.kt`, `ai/src/test/.../CubeAdvisorTest.kt` — new.
- `app/.../game/GameController.kt` — drop `offerDouble` hot-seat gate (edit).
- `app/.../game/GameUiState.kt` — `canDouble` gate `aiSide == null` → `toMove != aiSide` (edit).
- `app/.../game/AiTurnDriver.kt` — pre-roll cube-offer + `CUBE_OFFERED` bail (edit).
- `app/.../viewmodel/GameViewModel.kt` — AI-responds hook in `onOfferDouble` (edit).
- `app/src/test/.../viewmodel/GameViewModelCubeAiTest.kt` — new VM tests.

## 10. Known limitation (→ 6c)

Without gammon rates and a "too good to double" gate, the AI may double positions it should play on for a gammon, and its take point is a single-win approximation. Acceptable for a playable cube; cube *skill* arrives in 6c.
