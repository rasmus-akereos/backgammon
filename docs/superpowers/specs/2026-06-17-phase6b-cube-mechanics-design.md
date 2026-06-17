# Phase 6b-i — Doubling Cube Mechanics + UI (hot-seat): Design Spec

**Status:** Design — ready for plan-writing.
**Date:** 2026-06-17
**Slice:** Phase 6, slice 6b-i (cube mechanics, hot-seat). Split from the original 6b after spec review — see §0.
**Related:**
- `docs/superpowers/specs/2026-06-16-phase6a-equity-foundation-design.md` (calibrated `WinProbability`; §0 has the 6a–6d table)
- `docs/superpowers/specs/2026-06-12-phase6-doubling-cube-design.md` (Phase 6 stub: cube model §2, AI decision §3, the "do not ship 25%" warning)
- `docs/backgammon-app-spec.md` §8

---

## 0. Why this slice was split (6b-i vs 6b-ii)

Spec review surfaced that bundling the AI cube layer with the human cube mechanics made one slice carry the two riskiest, lowest-value pieces: the AI offer→stop→resume coroutine dance (which doesn't fit the existing fire-and-forget `aiJob` model) and a gammonless cube policy that plays incorrectly by construction. Both are isolable. So:

- **6b-i (this spec):** the cube as a complete, testable mechanism in **hot-seat** — `CubeState`, the offer/take/drop turn flow, the cube UI, and cube-scaled game value. No AI cube decisions; the cube is inactive in vs-computer mode.
- **6b-ii (future spec):** AI cube decisions — a `public` win-prob facade over `:ai`, a `CubePolicy` (with `TAKE_MIN = 0.21`, the cubeful live-cube take point — **not** the dead-cube 0.25 the stub §3 warns against), and the VM wiring for the AI to offer (job returns immediately; resume on take via `maybeRunAi()`) and to respond. This is where vs-computer doubling turns on.

The "both modes" goal from brainstorming is met across 6b-i + 6b-ii.

## 1. Goal

A fully working doubling cube in **hot-seat** money play: either player offers a double before rolling, the other takes or drops, the cube value/owner update, and the game's value at game over is `cube × gammon multiplier`. **Done looks like:** a hot-seat game can be driven `NEED_ROLL → offerDouble → CUBE_OFFERED → take → roll/play → … → GAME_OVER`, with the reported stake equal to `cube.value × multiplier`; and a drop ends the game immediately with the doubler winning the pre-double cube value.

## 2. Scope

**In scope**
- `CubeState` (value / owner / centred) + pure transitions, in `app/game`.
- `GameController`: one new phase `CUBE_OFFERED`; actions `offerDouble()` and `respondDouble(CubeResponse)`.
- A unified terminal result so drop and board-win share one game-over path.
- Cube-scaled game value (`effectiveStake = cube.value × winValue`) including the drop case.
- UI: cube indicator, "Double" button, Take/Drop controls, cube-aware result text — **hot-seat only**.

**Out of scope (explicit)**
- ❌ **AI cube decisions** (offer / take / drop) and the vs-computer cube — **6b-ii**. In vs-computer mode the cube is inactive: no Double button, no indicator interaction; games play exactly as today.
- ❌ Persistence / resume (none exists in the app).
- ❌ Session/running score; Jacoby; beavers/raccoons; automatic doubles.
- ❌ Match play, Crawford, match-equity table — 6d.
- ❌ Cubeful equity, recube vig, accurate take point, "too good to double" — 6c.
- ❌ Any change to checker move selection.

## 3. Decisions (settled in brainstorming + spec review)

- **Per-game stake only.** New Game resets, as today.
- **Cube is hot-seat-only in 6b-i** (AI cube → 6b-ii). This sidesteps the AI-async complexity and means the cube and training mode (vs-computer only) never co-occur in this slice.
- **Architecture (Approach A):** cube state + flow are turn-flow concerns → `GameController` in `app/game`; the pure controller stays free of any AI/policy types (none are introduced in 6b-i).
- **Cube rules** follow standard backgammon: offer before the roll; only the cube owner or either side when centred may double; a take doubles the value and transfers ownership to the taker; a drop forfeits the current (pre-double) cube value.

## 4. Cube model (`app/game`)

```kotlin
/** A take continues the game; a drop ends it. */
enum class CubeResponse { TAKE, DROP }

/** Doubling-cube state. owner == null means centred (either side may double). */
data class CubeState(val value: Int = 1, val owner: Player? = null) {
    val isCentred: Boolean get() = owner == null
    /** The player whose turn it is (pre-roll) may double if the cube is centred or they own it, below the cap. */
    fun mayDouble(player: Player): Boolean = (owner == null || owner == player) && value < CUBE_CAP
    /** After a take: value doubles and the cube passes to the taker. */
    fun afterTake(taker: Player): CubeState = CubeState(value * 2, taker)

    companion object { const val CUBE_CAP = 64 } // 2^6, conventional money-game cap
}
```

Value changes **only on a take**. No `max` parameter (no caller varies it in 6b).

## 5. Turn-flow integration (`GameController`)

Doubling happens at the start of a turn, before the roll. After any commit, `committed.toMove` is the next player to roll, so `cube.mayDouble(committed.toMove)` is evaluated from the correct (about-to-roll) perspective.

New state on `GameController`: `cube: CubeState` (default centred 1), a pending-double marker `doubler: Player?`, and a unified `terminal: TerminalResult?`.

```kotlin
/** The single source of game-over truth: a board win OR a cube drop. */
data class TerminalResult(val winner: Player, val winValue: Int) // winValue = gammon multiplier (1/2/3); drop = 1
```

- **`Phase.CUBE_OFFERED`** added to the `Phase` enum.
- **`offerDouble()`** — legal only when `phase == NEED_ROLL` and `cube.mayDouble(committed.toMove)`; in vs-computer it is additionally gated to hot-seat (`aiSide == null`) for 6b-i. Sets `doubler = committed.toMove`, phase `CUBE_OFFERED`. Cube value unchanged. No-op otherwise. **Policy-free:** `offerDouble`/`respondDouble` are always invoked by external code (UI/tests); no policy type is imported into `GameController`.
- **`respondDouble(response: CubeResponse)`** — legal only when `phase == CUBE_OFFERED` (no-op otherwise). Responder = `doubler.opponent`.
  - `TAKE` → `cube = cube.afterTake(doubler.opponent)`; clear `doubler`; phase returns to `NEED_ROLL`, same player on roll (the doubler).
  - `DROP` → `terminal = TerminalResult(winner = doubler, winValue = 1)`; clear `doubler`; phase `GAME_OVER`. Cube left undoubled.

**`compute()` — single terminal path (avoids calling `Scoring.winnerAndValue` on a non-terminal board):**
```
phase = when {
    terminal != null || Scoring.isGameOver(committed) -> GAME_OVER
    doubler != null -> CUBE_OFFERED
    passing -> MOVING
    dice == null -> NEED_ROLL
    else -> (existing MOVING/COMMITTABLE logic)
}
val result: TerminalResult? = terminal
    ?: if (Scoring.isGameOver(committed)) Scoring.winnerAndValue(committed)!!.let { TerminalResult(it.first, it.second) } else null
// winner = result?.winner ; winValue = result?.winValue ?: 0
```
`Scoring.winnerAndValue` is only ever called on a real board win. `newGame()` resets all three new fields explicitly: `cube = CubeState(); doubler = null; terminal = null` (the VM also re-instantiates `GameController`, which resets them via the default constructor — both paths must reset).

## 6. Game value

`GameUiState` gains `cube: CubeState` and a computed `val canDouble: Boolean get() = phase == NEED_ROLL && cube.mayDouble(toMove) && aiSide == null`. The displayed stake is **`effectiveStake = cube.value × winValue`** — the single value every consumer (result text, and any future readout) uses. `winValue` retains its raw meaning (gammon multiplier 1/2/3; a drop sets it to 1, so `effectiveStake` = the pre-double cube value the dropper forfeits). The full stake is computed in the display layer; it is **not** stored on `GameController` and `Scoring` is **not** changed.

## 7. UI (`GameScreen`, board canvas) — hot-seat only

- **Cube indicator:** a 24dp × 24dp square showing the cube's current face value, drawn in the side rail via `BoardGeometry` — vertically centred when the cube is centred, shifted toward the owner's half (≈25% / 75% of rail height) when owned. Shown only in hot-seat games.
- **"Double" button:** shown when `canDouble`; appears to the left of "Roll" (Roll does not move). Tap → `offerDouble()`.
- **Take / Drop controls:** shown when `phase == CUBE_OFFERED`. The responding player's colour/name is labelled prominently (hot-seat shares one device — make it clear whose decision it is). Tap → `respondDouble(TAKE | DROP)`.
- **Result text:** conventional phrasing including the cube, e.g. `"Black wins 4 (gammon, 2-cube)"`, `"White wins 2 (drop)"`. Produced by a pure `formatResult(winner, winValue, cube, dropped)` function (unit-tested).
- **Per-phase control visibility:**

  | Phase | Roll | Double | Take/Drop | Commit | Undo |
  |-------|------|--------|-----------|--------|------|
  | NEED_ROLL | ✓ | ✓ (if `canDouble`) | — | — | — |
  | CUBE_OFFERED | — | — | ✓ | — | — |
  | MOVING | — | — | — | — | ✓ |
  | COMMITTABLE | — | — | — | ✓ | ✓ |
  | GAME_OVER | — | — | — | — | — |

## 8. Testing

**Pure (`app/game`)**
- `CubeState`: `mayDouble` centred → true (both); owned-by-me → true; owned-by-opponent → false; at cap (64) → false. `afterTake` doubles value, sets owner = taker.
- `GameController`:
  - `offerDouble` legal only from `NEED_ROLL` + `mayDouble` (and hot-seat); else no-op; phase → `CUBE_OFFERED`.
  - `respondDouble(TAKE)`: value doubles, owner = taker, phase → `NEED_ROLL`, same player on roll. After a take, `offerDouble()` by the **original** doubler is a no-op (cube.owner now blocks it); the taker may redouble.
  - `respondDouble(DROP)`: `GAME_OVER`, `winner = doubler`, `winValue = 1`; **drop-stake invariant** — take once (cube = 2), redouble, drop → `effectiveStake == 2` (not 4).
  - `respondDouble` is a no-op when phase ≠ `CUBE_OFFERED`.
  - Board-win stake: `effectiveStake == cube.value × multiplier`.
  - **Full loop:** `NEED_ROLL → offerDouble → TAKE → roll → stage → commit → … → GAME_OVER`, asserting the final `effectiveStake`.
  - Take → roll → no legal moves → auto-pass path fires cleanly.
  - `newGame()` recentres the cube and clears `doubler`/`terminal`.

**UI**
- `canDouble` visibility (incl. `aiSide == null` gate) and the per-phase control matrix as pure logic.
- `formatResult(...)` output strings (single/gammon/backgammon × cube value, and drop).

## 9. Files (anticipated)

- `app/.../game/CubeState.kt` — new (`CubeState`, `CubeResponse`).
- `app/.../game/GameController.kt` — `Phase.CUBE_OFFERED`, `offerDouble`/`respondDouble`, `cube`/`doubler`/`terminal`, unified `compute()` (edit; grows to ~240 lines, no extraction planned for 6b).
- `app/.../game/GameUiState.kt` — `cube`, computed `canDouble`, `TerminalResult` (edit).
- `app/.../ui/screens/GameScreen.kt` — Double/Take/Drop controls, result text, `formatResult` (edit/new).
- `app/.../ui/board/BoardGeometry.kt` / `BoardCanvas.kt` — cube indicator (edit).
- Tests alongside each.

## 10. Deferred to 6b-ii (recorded so nothing is lost)

- `public` win-prob facade over `:ai` (e.g. `object CubeAdvisor { fun winProb(state, perspective): Double }`) — `Evaluator`/`Weights`/`WinProbability`/`GamePhases` are `internal`, so the VM needs a public entry point (same constraint hit in 6a).
- `CubePolicy` (`public`, `Double`-only API): `shouldDouble(winProb)` (`DOUBLE_MIN = 0.70`), `shouldTake(receiverWinProb)` with **`TAKE_MIN = 0.21`** (cubeful live-cube take point; the dead-cube 0.25 over-drops — stub §3). `Evaluator` takes an explicit `perspective` independent of `board.toMove` (the receiver isn't on roll).
- VM/`AiTurnDriver` wiring: AI offers by calling `offerDouble()` and the job **returns immediately** (`maybeRunTurn` treats `CUBE_OFFERED` as not-its-turn, like `GAME_OVER`); on a human `TAKE` the VM calls `maybeRunAi()` to start a fresh AI turn. AI-as-responder is triggered from `onOfferDouble()` after `publish()` when `doubler.opponent == aiSide`.
- Known limitation to document in 6b-ii/6c: without gammon rates and a "too good" gate, the AI may over-cube strong gammonish positions; cube play becomes correct in 6c.
