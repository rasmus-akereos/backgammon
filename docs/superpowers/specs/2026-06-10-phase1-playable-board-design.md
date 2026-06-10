# Phase 1 — Playable Compose Board (hot-seat) — Design

**Date:** 2026-06-10
**Owner:** Rasmus (rlunde)
**Status:** Approved design, revised after multi-lens spec review, ready for implementation plan
**Source spec:** `docs/backgammon-app-spec.md` (§9 UI/UX, §12 Phase 1, §13 layout)
**Builds on:** Phase 0 core engine (`:core`), merged to `main`.

---

## 1. Scope

Build a **playable backgammon board** for hot-seat (human vs human) play on Android, validating
the Phase 0 rules engine against a real UI. The app launches **straight into a fresh game**.

**In scope:** the `:app` Android module; a Compose `Canvas` board (fixed White's-perspective
orientation); tap-to-roll, tap-to-move with legal-destination highlighting, free undo, and
commit; auto-pass when there are no legal moves; a game-over banner with the win value; a
"New game" action; an always-on pip-count readout. A small **pure `TurnPlanner`** added to
`:core` powers incremental move building; a pure **`GameController`** in `:app` owns the turn
state machine (so the logic is JVM-tested without Android).

**Out of scope (deferred):** AI / difficulty (Phase 2); persistence / resume (Phase 4);
animations, theme switching, settings, hint, coach markers, sound/haptics (Phase 4+);
drag-to-move, board-flip, landscape. No Home/menu screen yet.

**Accepted Phase-1 limitations (explicit, not gaps):**
- **Black plays "inverted"** — the board is fixed to White's perspective, so Black's home is the
  top-right and Black moves upward. This is a common convention for digital hot-seat; mitigated
  by a prominent active-player cue (§4.3). A board-flip option is a later phase.
- **No opening roll-off** — the game starts with **White to move** and a normal two-die roll
  (real backgammon's single-die roll-off is deferred; revisit with match play).
- **Process death loses the in-progress game** (no persistence this phase). Configuration
  changes (rotation is portrait-locked, but dark-mode / font-scale / multi-window still occur)
  must **not** lose state — see §5.

## 2. Architecture

```
:app  (Android: Compose UI + GameController + GameViewModel)   ← new this phase, depends on :core
  ↓
:core (pure-Kotlin engine + TurnPlanner)                       ← Phase 0, plus a TurnPlanner this phase
```

`:core` stays pure Kotlin with **zero Android dependencies**. All *rules* live there. The *turn
state machine* lives in `:app` as a pure `GameController` (also Android-free in its logic), with
`GameViewModel` a thin lifecycle adapter. The Compose layer only renders state and forwards taps.

**`settings.gradle.kts`** adds `include(":app")` **and adds `google()`** to
`dependencyResolutionManagement.repositories` (AGP and Compose artifacts are not on Maven
Central). A git-ignored `local.properties` with `sdk.dir` points the build at the installed SDK
(`C:\Users\RasmusLundgaardHanse\AppData\Local\Android\Sdk`). The Android **`applicationId` is
`dk.rlunde.backgammon`** (matching `:core`'s package root; supersedes the product spec §13/§14.8
`com.lunde.backgammon` placeholder).

### 2.1 Build toolchain — resolve empirically as the FIRST plan task (gated)

Phase 1 introduces the Android Gradle Plugin (AGP) + Jetpack Compose. Only platforms
**android-36 / android-36.1** are installed locally, and `compileSdk 36` requires a recent AGP
that in turn needs a Gradle newer than the currently pinned **8.10**. The exact compatible
versions cannot be settled from memory and **must be pinned by a short spike**, not guessed in
prose. Plan **Task 0** does this and is gated:

1. Choose a coherent set and pin it in `gradle/libs.versions.toml`: **Gradle wrapper**, **AGP**,
   **Compose BOM**, and the **Compose Compiler Gradle plugin** `org.jetbrains.kotlin.plugin.compose`
   whose version **must equal the Kotlin version (2.0.21)**. Preferred direction: bump the wrapper
   the minimum needed for an AGP that supports `compileSdk 36`. Fallback: install an `android-35`
   platform — `sdkmanager --sdk_root="C:\Users\RasmusLundgaardHanse\AppData\Local\Android\Sdk" "platforms;android-35"`
   (ANDROID_HOME is unset; pass `--sdk_root`) — and use an AGP that runs on Gradle 8.10 with
   `compileSdk 35`.
2. Add `google()` to settings repositories.
3. **Gate A:** a blank `:app` (single `MainActivity` rendering `setContent { Text("ok") }`)
   **builds, installs, and launches on the Pixel 9 Pro emulator**.
4. **Gate B:** after any wrapper bump, **all 55 `:core` tests stay green** (`./gradlew :core:test`).

`minSdk = 26`; `targetSdk = compileSdk`. `:core` continues to compile to JVM-17 bytecode. Only
after Gates A+B pass does app implementation begin.

## 3. The turn planner (`TurnPlanner`, pure, added to `:core`)

The engine produces *complete* legal turns (`legalMoves`), but the UI builds a turn one sub-move
at a time and must never strand the player into an illegal/incomplete turn. A new **`TurnPlanner`**
object — co-located in `MoveGenerator.kt` so it can call the existing **`private`**
`enumerateSequences`/`applySubMove` **without changing their visibility** (no new board-mutation
surface leaks to `:app`) — exposes exactly two public functions:

```kotlin
object TurnPlanner {
    /** Most pips playable from [state] using the dice still in hand. 0 ⇒ the turn is complete. */
    fun maxUsablePips(state: BoardState, remainingDice: List<Int>): Int

    /**
     * Legal next single-die sub-moves that PRESERVE the maximum-pips total, grouped by origin
     * point (the bar uses the bar `from`-sentinel as its key). Each returned sm satisfies:
     *   sm.die + maxUsablePips(applySubMove(state, sm), remainingDice with ONE sm.die removed)
     *     == maxUsablePips(state, remainingDice)
     */
    fun legalNextSubMoves(state: BoardState, remainingDice: List<Int>): Map<Int, List<SubMove>>
}
```

- **`remainingDice` is a multiset (`List<Int>`)**, and "remove `sm.die`" removes **exactly one
  occurrence** (mirroring Phase 0's `remainingDice.toMutableList().apply { remove(die) }`). This
  is essential for **doubles**: with `[4,4,4,4]`, set-style subtraction would drop all 4s and
  strand the player. (Test: `legalNextSubMoves` stays non-empty across all four staged 4s.)
- **Invariant relied upon:** for the two rolled dice, max *pip-sum* ⇒ max *dice count* (since
  `a+b > max(a,b)`, and doubles are uniform), so the commit gate below never under-uses dice.
  Pinned by a `:core` test.
- **Mid-turn states keep `toMove` = the mover** (sub-moves are applied without flipping until
  commit), so `enumerateSequences` operates correctly on them.
- **Destinations for a tapped point `p`** = `legalNextSubMoves(partial, remaining)[p]?.map { it.to }`.
- **Commit available** exactly when `maxUsablePips(partial, remaining) == 0`.
- **Must be recomputed against the post-staging `partial`/`remaining` after every append and every
  undo — never cached across a staging change** (a cached set can strand the player).

`TurnPlanner` is unit-tested on the JVM with no Android dependency. (Rationale for a `:core`
planner over filtering `legalMoves` in `:app`: incremental, order-independent, never strands,
keeps all rules logic in tested `:core`.)

## 4. Board rendering (`:app`, Compose `Canvas`)

Portrait, board fills the width, **fixed White's-perspective** orientation, sized relative to
canvas dimensions (resolution-independent).

### 4.1 Geometry — single source of truth (pure, no Compose types)
`BoardGeometry(widthPx, heightPx)` computes the screen rectangle/anchor for every element: the 24
point-triangles, the central **bar**, **two bear-off trays**, and the **dice** area. It depends on
**no Compose/Android types** — plain `Float`s in, a plain `BoardRect(l,t,r,b)` data class out (named
`BoardRect` to avoid colliding with `android.graphics.Rect` / Compose `Rect`). The inverse:

```kotlin
sealed interface BoardTarget {
    data class Point(val index: Int) : BoardTarget { init { require(index in 1..24) } } // 1..24
    data object Bar : BoardTarget
    data class BearOff(val player: Player) : BoardTarget   // White & Black trays are distinct
    data object Dice : BoardTarget
}
fun BoardGeometry.hitTest(x: Float, y: Float): BoardTarget?   // null = no interactive region
```

`BoardCanvas` adapts Compose's `Size`→(`widthPx,heightPx`) and a tap `Offset`→(`x,y`). The full
point column (triangle + any overflow stack + count badge) hit-tests to that `Point`. Because
`BoardGeometry`/`hitTest` are pure float arithmetic, they're JVM-unit-testable in `:app` with no
Android dependency (`BoardGeometry.kt` must contain **no `androidx.*`/`android.*` imports**).

### 4.2 Layout (fixed) — continuous path
Standard board, so a player's path is one continuous sweep. Quadrants and **intra-quadrant index
order**:
- **Bottom-right = White home, points 1–6**, with **1 nearest the right edge** increasing leftward to 6 at the bar.
- **Bottom-left = 7–12**, 7 just left of the bar increasing leftward to 12 at the left edge.
- **Top-left = 13–18**, 13 at the left edge increasing rightward to 18 at the bar.
- **Top-right = Black home, points 19–24**, 19 just right of the bar increasing rightward to 24 at the right edge.

This makes White's 24→1 path sweep continuously (top-right→top-left→bottom-left→bottom-right) with
no jump at the bar, and points **1 and 24 sit adjacent to the right-edge trays**. Central **bar**
column between the halves. **Two bear-off trays on the right edge: White's in the bottom-right
(beside 1–6), Black's in the top-right (beside 19–24).** Points are alternating-tone triangles.

### 4.3 Drawn elements
- **Checkers:** filled circles with a subtle inner ring (white `#F4F1EA`+`#C9C2B0`; black
  `#23262B`+`#3A3F47`). Stack up a point; >5 → compress/fan + a small count badge.
- **Dice:** two rounded squares with pips (four when doubles); a die consumed by the staged turn
  is dimmed.
- **Bar & trays:** a checker on the bar is drawn in the central bar on its owner's far side; on
  tap, its entry destinations highlight in the **opponent's home quadrant** (White entry → top-right
  19–24, Black entry → bottom-right 1–6). Borne-off checkers stack in the owner's tray. The mover's
  off-tray is highlighted as a destination **only when `legalNextSubMoves` contains an off sub-move**
  from the selected point; otherwise inert.
- **Highlights (amber `#E0A526`):** the selected origin and a glow on each legal destination.
- **Active-player cue (required):** a prominent "WHITE/BLACK to move" banner plus a tint of the
  active side's home quadrant — so the inverted-Black case is unambiguous.
- **Pip count:** White's count in the bottom half (beside its home), Black's in the top half; each
  is that player's standard distance-to-bear-off (White: Σ point index; Black: Σ 25−index; bar
  checker = 25), from `Scoring.pipCount`.
- **Theme:** single **teal felt** default in a `BoardColors` object (kept per owner decision; one
  palette, no switching). A minimal `MaterialTheme` wraps the screen because the Commit/Undo/New-Game
  controls and banner use Material3 components.
- **No animations:** checkers snap; each committed sub-move re-renders the board.

## 5. State & interaction (`GameController` + `GameViewModel`)

To keep the turn logic JVM-testable, it lives in a **pure `GameController`** (plain Kotlin — no
`androidx.lifecycle`, no `Dispatchers.Main`) that owns the state machine and produces an immutable
`GameUiState`. **`GameViewModel`** (a real `androidx.lifecycle.ViewModel`, so it survives
configuration changes) is a thin adapter: it holds a `GameController`, exposes
`StateFlow<GameUiState>`, forwards taps, and emits one-shot UI events. **Process death loses the
game** (acceptable; no `SavedStateHandle` yet — that seam is Phase 4).

`GameController` takes an **injectable initial `BoardState`** (default `startingPosition()`) and an
injected `DiceRoller` (`RandomDiceRoller` in the app, `SeededDiceRoller` in tests) — so auto-pass
and game-over are testable from constructed positions without playing hundreds of moves.

### 5.1 Held state (in `GameController`)
`committed: BoardState`, `dice: Dice?`, `staged: List<SubMove>`, `selectedOrigin: BoardTarget?`.
Derived each update: `partial` (= `committed` with `staged` applied, mover not flipped — the board
drawn), `remainingDice` (= `dice.pips()` as a **multiset** minus the multiset of `staged.map{die}`),
`legalNextSubMoves`, highlighted destinations, `phase`, both pip counts.

### 5.2 `GameUiState` + `Phase` (own file, pure, no Android)
A data class carrying the `partial` board (points/bar/off), dice with per-die used flags,
`selectedOrigin`, the highlighted destination set, `toMove`, `phase`, both pip counts, and (when
over) winner + value. One-shot events use a **`Channel<UiEvent>`** exposed as `receiveAsFlow()` and
collected in `GameScreen` inside a `LaunchedEffect` (consumed-once semantics — not `SharedFlow`).

### 5.3 Turn phases
- **NeedRoll** (`dice == null`): tap the dice → roll. Then `legalMoves(committed, dice)`: if empty
  → emit a **"No legal moves — passing" event that the player acknowledges (tap to continue)** then
  **auto-pass** (`pass`, clear dice → other player's NeedRoll); else → Moving. (Termination: a
  closed-out player keeps passing only until the opponent's forced progress reopens an entry — it
  cannot loop forever.)
- **Moving:** tap a checker whose origin is in `legalNextSubMoves` → select + highlight its
  destinations; tap a highlighted destination → append that `SubMove`. **Precedence:** if a tapped
  point is *both* a highlighted destination and a legal origin, the **destination wins** (re-select
  requires deselecting first by tapping empty space). Tapping a non-destination, non-origin →
  deselect. **Undo** pops the last staged sub-move (no-op when `staged` is empty) and recomputes.
  When `maxUsablePips(partial, remaining) == 0` → **Committable**.
- **Committable:** **Commit** (no-op unless phase is Committable; transitions phase atomically to
  guard double-taps) applies `Move(staged)` via `MoveGenerator.apply` → next `committed` (turn
  flips), clears staged/dice. Then `Scoring.isGameOver` → GameOver, else next player's NeedRoll.
- **GameOver:** overlay banner with winner + single/gammon/backgammon (`Scoring.winnerAndValue`,
  read from off-counts so the `toMove` flip is irrelevant) and a **New Game** button (resets to
  the initial `BoardState`).

### 5.4 Threading & performance
`GameController` is synchronous on the main thread (no AI yet). Planner calls must complete in
well under a frame (~16 ms) — board states are tiny — but `legalNextSubMoves` invokes
`maxUsablePips` per candidate, and opening **doubles** enumerate a large tree, so **memoize
`maxUsablePips` by `BoardState` within a single tap's computation**. Add a `:core` perf assertion
(opening doubles, bar-entry positions) bounding a planner call. Phase 2's AI will wrap
`chooseMove` in `viewModelScope.launch(Dispatchers.Default)`; the `StateFlow` contract is unchanged
by that, and no `:core` change may introduce search on the main thread.

## 6. Package layout (`:app`)

```
app/src/main/java/dk/rlunde/backgammon/
├── MainActivity.kt              // sets Compose content -> GameScreen
├── ui/
│   ├── screens/
│   │   └── GameScreen.kt        // top-level composable; collects state; buttons; event LaunchedEffect
│   ├── board/
│   │   ├── BoardCanvas.kt       // @Composable entry only: draw + pointerInput; delegates tap dispatch
│   │   ├── BoardGeometry.kt     // pure float geometry + hitTest + BoardTarget + BoardRect (no androidx.*)
│   │   └── BoardColors.kt       // teal palette
│   └── theme/                   // minimal Material3 theme (used by buttons/banner)
├── game/
│   ├── GameController.kt        // pure turn state machine; injectable initial BoardState + DiceRoller
│   └── GameUiState.kt           // GameUiState data class + Phase + UiEvent (pure, no Android)
└── viewmodel/
    └── GameViewModel.kt         // androidx ViewModel; holds GameController; StateFlow + event Channel
```
`AndroidManifest.xml`: single launcher `MainActivity`, **no `INTERNET` permission** (offline).
JVM unit tests for `GameController`, `GameUiState`, and `BoardGeometry` live under `app/src/test/`.

## 7. Testing strategy

- **`TurnPlanner` (`:core`, JVM, TDD):** `maxUsablePips`/`legalNextSubMoves` — must-use-both
  enforced incrementally (a stranding sub-move is not offered); must-play-larger; bar-entry-only;
  bear-off destinations; **doubles staged up to 4 (non-empty across all four)**; Committable when
  `maxUsablePips == 0`; a fully-staged sequence's outcome matches a `legalMoves` result; the
  max-pip-sum ⇒ max-dice invariant. **All 55 existing `:core` tests must remain green** (regression
  gate).
- **`GameController` (`:app`, plain JVM unit tests, `SeededDiceRoller`):** roll→move→commit flips the
  turn; **auto-pass from a constructed blocked position + seed** (both die entries blocked, checker
  on bar); undo pops a sub-move and re-derives destinations correctly; commit gated until max used;
  **game-over from an injected near-terminal position** → correct winner/value; new game resets;
  **negative paths** — tap a non-legal destination (clears selection, staged unchanged), tap during
  NeedRoll (no-op), any tap after GameOver (no-op), undo with nothing staged (no-op). No
  `androidx.lifecycle`/`Dispatchers.Main` involved (logic is in the pure controller).
- **`BoardGeometry.hitTest` (`:app`, JVM, pure):** at a canonical canvas size (e.g. 1080×1920), a
  tap inside each of the 24 point rects + bar + both trays + dice returns the right `BoardTarget`,
  and a tap just outside a hit zone returns `null` (boundary tests). `BoardCanvas` feeds its
  **measured** `Size` into the same `BoardGeometry` (no hardcoded size in production).
- **Manual emulator smoke — scripted acceptance (Pixel 9 Pro):** a written step list using
  `SeededDiceRoller` with a pinned seed (or explicit forced rolls via a debug hook) that
  deterministically reaches each exercise, with an observable pass/fail per step: **(a) a hit**
  (a checker lands on a lone opponent checker → it appears on the bar), **(b) bar re-entry** (the
  barred checker can only move to entry points, in the opponent's home quadrant), **(c) a bear-off**
  (off-tray highlights only when legal; checker moves to the tray), **(d) auto-pass** (a blocked
  roll shows the acknowledged pass and flips turn), **(e) the win banner** (correct winner + value).
  Automated Compose instrumented UI tests remain deferred.

## 8. Success criteria

- **Gate A:** a blank `:app` builds, installs, and launches on the Pixel 9 Pro emulator (pinned
  toolchain versions recorded in §2.1 / the version catalog).
- **Gate B:** all 55 `:core` tests stay green after any wrapper bump.
- The full `:app` builds, installs, and launches straight into a hot-seat game.
- A full hot-seat game is playable to completion via tap-to-roll / tap-to-move / undo / commit,
  including hitting, bar re-entry, bear-off, the acknowledged auto-pass, and a correct win banner
  (the scripted smoke passes every step).
- JVM tests green: `TurnPlanner`, `GameController` turn loop + negative paths (seeded/injected),
  `BoardGeometry` hit-test + boundaries.
- `:core` remains Android-free; `:app` depends on `:core`; no `INTERNET` permission in the manifest.
