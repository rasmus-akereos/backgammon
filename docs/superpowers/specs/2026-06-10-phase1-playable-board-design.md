# Phase 1 — Playable Compose Board (hot-seat) — Design

**Date:** 2026-06-10
**Owner:** Rasmus (rlunde)
**Status:** Approved design, ready for implementation plan
**Source spec:** `docs/backgammon-app-spec.md` (§9 UI/UX, §12 Phase 1, §13 layout)
**Builds on:** Phase 0 core engine (`:core`), merged to `main`.

---

## 1. Scope

Build a **playable backgammon board** for hot-seat (human vs human) play on Android, validating
the Phase 0 rules engine against a real UI. The app launches **straight into a fresh game**.

**In scope:** the `:app` Android module; a Compose `Canvas` board (fixed White's-perspective
orientation); tap-to-roll, tap-to-move with legal-destination highlighting, free undo, and
commit; auto-pass when there are no legal moves; a game-over banner with the win value; a
"New game" action; an always-on pip-count readout. A small **pure turn-planner** added to
`:core` powers the incremental move building.

**Out of scope (deferred):** AI / difficulty (Phase 2); persistence / resume (Phase 4);
animations, theme switching, settings, hint, coach markers, sound/haptics (Phase 4+);
drag-to-move, board-flip, landscape. No Home/menu screen yet.

## 2. Architecture

```
:app  (Android: Compose UI + GameViewModel)   ← new this phase, depends on :core
  ↓
:core (pure-Kotlin engine + turn planner)      ← Phase 0, plus small additions this phase
```

`:core` stays pure Kotlin with **zero Android dependencies**. All rules and turn logic live
there (unit-testable on the JVM). `:app` holds only rendering, input, and UI/turn *state* —
no rules logic.

**`settings.gradle.kts`** adds `include(":app")`. A `local.properties` (git-ignored) with
`sdk.dir` points the Android build at the installed SDK
(`C:\Users\RasmusLundgaardHanse\AppData\Local\Android\Sdk`).

### 2.1 Build toolchain (flagged risk — resolve as the first plan task)

Phase 1 introduces the Android Gradle Plugin (AGP). Only platforms **android-36 / android-36.1**
are installed locally, and `compileSdk 36` requires a recent AGP that needs a Gradle newer than
the currently pinned **8.10**. Resolution (decide + verify in plan Task 0):

- **Preferred:** bump the Gradle wrapper to **8.11.x** (compatible with Kotlin 2.0.21) and use an
  AGP that supports `compileSdk 36` (e.g. AGP 8.7.x+), plus the Kotlin Compose compiler Gradle
  plugin (`org.jetbrains.kotlin.plugin.compose`, Kotlin 2.0+).
- **Fallback:** install an `android-35` platform via `sdkmanager` and use AGP 8.6 on Gradle 8.10.

`minSdk = 26`, `targetSdk = compileSdk` (per product spec §2). `:core` continues to compile to
JVM-17 bytecode. Exact versions (AGP, Gradle, Compose BOM) are pinned in plan Task 0 and verified
by a build + launch on the **Pixel 9 Pro** emulator.

## 3. The turn planner (pure, added to `:core`)

The engine produces *complete* legal turns (`legalMoves`), but the UI builds a turn one
sub-move at a time and must never strand the player into an illegal/incomplete turn. Two new
pure functions on `MoveGenerator` solve this by keeping every staged sub-move on a
**maximum-pips path**:

```kotlin
/** Most pips playable from [state] using the dice still in hand. 0 = turn is complete. */
fun maxUsablePips(state: BoardState, remainingDice: List<Int>): Int

/**
 * The single-die sub-moves legal from [state] that PRESERVE the maximum-pips total — i.e. each
 * returned sm satisfies: sm.die + maxUsablePips(applySubMove(state, sm), remainingDice − sm.die)
 *   == maxUsablePips(state, remainingDice).
 * This makes the "use the maximum number of dice / play the larger" rule hold incrementally:
 * the player can only ever build a complete legal turn, and can never strand a die.
 */
fun legalNextSubMoves(state: BoardState, remainingDice: List<Int>): List<SubMove>
```

Both reuse Phase 0's `enumerateSequences`. `maxUsablePips` = the max pip-sum over enumerated
sequences from `state` with `remainingDice`. These run on partial mid-turn states whose `toMove`
is still the mover (sub-moves are applied without flipping until commit). To support this, the
internal `applySubMove` and `enumerateSequences` get minimal `internal`/exposed access as needed
(or thin public wrappers), staying within `:core`.

- **Selectable origins / destinations:** for a tapped point `p`, the highlighted destinations are
  `{ sm.to : sm ∈ legalNextSubMoves(partial, remaining), sm.from == p }`.
- **Commit available** exactly when `maxUsablePips(partial, remaining) == 0`.

This planner is unit-tested on the JVM with no Android dependency.

## 4. Board rendering (`:app`, Compose `Canvas`)

Portrait, board fills the width, **fixed White's-perspective** orientation, sized relative to
canvas dimensions (resolution-independent).

### 4.1 Geometry — single source of truth
`BoardGeometry(widthPx, heightPx)` computes the screen rectangle/anchor for every element: the 24
point-triangles, the central **bar**, the two **bear-off trays**, and the **dice** area. Both
the draw code and tap hit-testing use it. To stay JVM-unit-testable, `BoardGeometry` depends on
**no Compose types** — it takes plain `Float`s and returns a plain `Rect` data class (`l,t,r,b`).
The inverse:

```kotlin
sealed interface BoardTarget {
    data class Point(val index: Int) : BoardTarget   // 1..24
    data object Bar : BoardTarget
    data object BearOff : BoardTarget
    data object Dice : BoardTarget
    data object None : BoardTarget
}
fun BoardGeometry.hitTest(x: Float, y: Float): BoardTarget
```

`BoardCanvas` adapts Compose's `Size`→(`widthPx,heightPx`) when building the geometry and a tap
`Offset`→(`x,y`) when calling `hitTest`. Because `BoardGeometry`/`hitTest` are pure arithmetic over
floats, they are JVM-unit-testable with no Android/Compose dependency.

### 4.2 Layout (fixed)
Standard board: bottom-right quadrant = White's home (points **1–6**), bottom-left = **7–12**,
top-left = **13–18**, top-right = **19–24**; central **bar** column between the halves;
**bear-off tray** down the right edge. Points are alternating-tone triangles.

### 4.3 Drawn elements
- **Checkers:** filled circles with a subtle inner ring (white `#F4F1EA` + `#C9C2B0` ring; black
  `#23262B` + `#3A3F47` ring). Stack up a point; when a point holds >5, compress/fan and draw a
  small count badge.
- **Dice:** two rounded squares with drawn pips (four when doubles); a die consumed by the staged
  turn is dimmed.
- **Bar & trays:** checkers sent to the bar render in the central bar; borne-off checkers stack in
  the owner's tray.
- **Highlights (amber `#E0A526`):** the selected checker's origin point and a glow on each legal
  destination. A whose-turn / phase indicator (text) at the top; a small **pip-count** readout per
  side.
- **Theme:** single **teal felt** default (`#1F4E4A`; cream `#E8DCC0` / tan `#9C6B3F` points) in a
  `BoardColors` object so themes are easy to add later. No theme switching this phase.
- **No animations:** checkers snap to position; each committed sub-move re-renders the board.

## 5. State & interaction (`GameViewModel`)

`GameViewModel` exposes one immutable `GameUiState` via `StateFlow`; the Canvas renders it. The
ViewModel holds turn/UI state and delegates **all** rules to `:core`. A `DiceRoller` is injected
(`RandomDiceRoller` in the app, `SeededDiceRoller` in tests).

### 5.1 Held state
`committed: BoardState` (board at the start of the current turn), `dice: Dice?`,
`staged: List<SubMove>`, `selectedPoint: Int?`. Derived each update: `partial` (= `committed`
with `staged` applied, mover not yet flipped — this is the board drawn), `remainingDice`,
`legalNextSubMoves`, highlighted destinations, `phase`, and pip counts.

### 5.2 GameUiState (what the Canvas needs)
The `partial` board (points/bar/off), the dice with per-die used flags, `selectedPoint`, the set
of highlighted destination targets, `toMove`, `phase`, both pip counts, and (when over) the winner
+ value. One-shot UI events (e.g. "No legal moves") are emitted via a separate channel/`SharedFlow`.

### 5.3 Turn phases
- **NeedRoll** (`dice == null`): tap the dice (or the side panel) → roll. Then
  `legalMoves(committed, dice)`: if empty → emit "No legal moves" and **auto-pass** (`pass`,
  clear dice → other player's NeedRoll); else → Moving.
- **Moving:** tap a checker whose origin is in `legalNextSubMoves` → select + highlight its
  destinations; tap a highlighted destination → append that `SubMove` to `staged`; tap elsewhere →
  deselect. **Undo** pops the last staged sub-move. When `maxUsablePips(partial, remaining) == 0`
  → **Committable**.
- **Committable:** **Commit** applies `Move(staged)` via `MoveGenerator.apply(committed, …)` →
  next `committed` (turn flips), clears staged/dice. Then `Scoring.isGameOver` → GameOver, else
  next player's NeedRoll.
- **GameOver:** overlay banner showing winner + single/gammon/backgammon (from
  `Scoring.winnerAndValue`) and a **New Game** button (resets to `startingPosition()`).

Bar/bear-off need no special UI: on the bar, the planner offers only entry sub-moves (tap the bar
checker → entry points highlight); bearing off offers the off-tray as the destination.

### 5.4 Threading
Planner/engine calls are synchronous on the main thread — boards are tiny and there's no AI
search yet (off-main-thread work arrives in Phase 2).

## 6. Package layout (`:app`)

```
app/src/main/java/dk/rlunde/backgammon/
├── MainActivity.kt              // sets Compose content -> GameScreen
├── ui/
│   ├── GameScreen.kt            // top-level composable; collects GameUiState; buttons
│   ├── board/
│   │   ├── BoardCanvas.kt       // Canvas draw + pointerInput; adapts Compose Size/Offset
│   │   ├── BoardGeometry.kt     // pure float geometry + hitTest + BoardTarget + Rect
│   │   └── BoardColors.kt       // teal theme palette
│   └── theme/                   // Material theme scaffolding (default)
└── viewmodel/
    └── GameViewModel.kt         // GameUiState, phases, staging, commit/undo, new game
```
`AndroidManifest.xml` declares a single launcher `MainActivity` and **no `INTERNET` permission**
(offline, per product spec). `BoardGeometry` lives in `:app/ui/board` but depends on no Android or
Compose types (plain floats), so its tests run as fast JVM unit tests in `:app`.

## 7. Testing strategy

- **Turn-planner (`:core`, JVM, TDD):** `maxUsablePips`/`legalNextSubMoves` — must-use-both enforced
  incrementally (a stranding sub-move is not offered); must-play-larger; bar-entry-only-while-on-bar;
  bear-off destinations; doubles staged up to 4; Committable when `maxUsablePips == 0`; a fully
  staged sequence's outcome matches a `legalMoves` result.
- **`GameViewModel` (`:app`, JVM unit test, `SeededDiceRoller` + coroutines-test):** roll→move→commit
  flips the turn; auto-pass on a no-legal-move roll; undo pops a sub-move; commit gated until the max
  is used; game-over → correct winner/value; new game resets.
- **`BoardGeometry.hitTest` (`:app`, JVM, pure):** tap inside each of the 24 point rects + bar/tray/dice
  returns the right `BoardTarget`.
- **Manual emulator smoke (Phase-1 acceptance):** build → install → launch on **Pixel 9 Pro**; play a
  full hot-seat game exercising a hit, a bar re-entry, a bear-off, and the win banner. Automated
  Compose instrumented UI tests are deferred.

## 8. Success criteria

- `:app` builds, installs, and launches on the Pixel 9 Pro emulator straight into a hot-seat game.
- A full hot-seat game is playable to completion via tap-to-roll / tap-to-move / undo / commit,
  including hitting, bar re-entry, bear-off, auto-pass, and a correct win banner.
- JVM tests green: turn-planner, `GameViewModel` turn loop (seeded), `BoardGeometry` hit-test.
- `:core` remains Android-free; `:app` depends on `:core`; no `INTERNET` permission in the manifest.
