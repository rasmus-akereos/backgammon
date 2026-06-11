# Phase 2 — Basic AI: Design Spec

**Status:** Approved design (brainstorming complete) — pending multi-lens spec review.
**Date:** 2026-06-11
**Builds on:** Phase 0 (`:core` rules engine, merged) and Phase 1 (`:app` playable Compose board, merged). Product spec: `docs/backgammon-app-spec.md` §6 (AI design), §12 (phase plan: *Phase 2 — Basic AI*).

## 1. Goal

Add a single-player mode: the human plays one colour, the computer plays the other. Deliver a new pure-Kotlin `:ai` module with an `AiPlayer` interface, a hand-tuned linear **evaluator**, **1-ply expectimax** move selection, and two difficulty tiers — **Beginner** (simplified eval + injected noise) and **Intermediate** (full eval, no noise). Wire a lightweight in-app setup (difficulty • colour • opponent) and run the AI search off the main thread with a visible "thinking" state. Hot-seat (human-vs-human) remains available.

## 2. Scope

**In scope (Phase 2):**
- `:ai` module: `AiPlayer`, `Evaluator`, `Expectimax` (1-ply), `Difficulty`/weights, `HeuristicAiPlayer`.
- `:core`/`:app` integration: `GameController` gains `aiSide` + `applyMove`; `GameViewModel` orchestrates the AI turn off-main.
- Lightweight setup screen; AI-turn presentation (paced, applied at once); "AI thinking" state.
- JVM tests for `:ai` and the controller changes; emulator smoke.

**Explicitly deferred (later phases — do NOT build now):**
- 2-ply/3-ply expectimax, top-K move pruning, node budget, the full self-play **benchmark harness** → **Phase 3** (Advanced/Expert).
- Coaching / analysis / equity-loss / rollouts → **Phase 5**.
- Doubling cube, match play, NN-trained eval → **Phase 6**.
- Persistence/resume of an in-progress game, settings (animation speed, auto-roll, hints) → later phases (§10 of product spec). Phase 2 holds game state in memory only.
- Checker movement animation → Phase 4 polish. Phase 2 applies the AI's move at once (no glide).

## 3. Architecture

Module dependency graph (all pure modules stay Android-free and JVM-testable):

```
:app   (Compose, ViewModel)      depends on -> :core, :ai
:ai    (AiPlayer, Evaluator,     depends on -> :core
        Expectimax, Difficulty)
:core  (rules engine)            no deps          (UNCHANGED in Phase 2)
```

- New Gradle module `:ai`, package `dk.rlunde.backgammon.ai`. Added to `settings.gradle.kts`; build file mirrors `:core` (kotlin-jvm, JVM-17 bytecode). Dependencies: `:core`, `kotlinx-coroutines-core` (for the `suspend` interface), test: `kotlin-test`, `kotlinx-coroutines-test`.
- `:app/build.gradle.kts` adds `implementation(project(":ai"))`.
- **Chosen integration:** the **ViewModel orchestrates** the AI turn; `:core` and `GameController` stay pure and synchronous. Rationale: keeps the rules engine and the turn state machine fully JVM-unit-testable; the only coroutine/threading concern lives at the `androidx` boundary (the VM), exactly as in Phase 1.

## 4. The `:ai` module

### 4.1 `AiPlayer` interface

```kotlin
interface AiPlayer {
    /** Choose a full turn from the legal options. Caller runs this off the UI thread. */
    suspend fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move
}
```

- `legal` is the full list of legal turns for `dice` (the caller computes it via `MoveGenerator.legalMoves`). The caller guarantees `legal` is non-empty (auto-pass is handled before `chooseMove` is invoked).
- `suspend` is a threading/cancellation marker only; the implementation is CPU-bound pure computation. Phase 2 does not implement cooperative cancellation (1-ply is fast); the search ignores cancellation.

### 4.2 `Evaluator`

```kotlin
fun evaluate(state: BoardState, perspective: Player, weights: Weights): Double
```

Returns a score where **positive = good for `perspective`**. A weighted linear combination of features, each computed for `perspective` minus the same feature for the opponent where symmetric. Features (Phase 2 set):

1. **Pip differential** — `pipCount(opponent) − pipCount(perspective)` (positive when you are ahead in the race). Dominant term.
2. **Checkers borne off** — `offCount(perspective) − offCount(opponent)`.
3. **Blot exposure** — for each `perspective` blot (point with exactly one of its checkers, `isBlotFor` from the opponent's view), the probability it is hit next roll, summed; entered as a **penalty**. Hit probability comes from a precomputed shot table (§4.3).
4. **Home-board points made** — count of points in `perspective`'s home board (WHITE 1–6, BLACK 19–24) with ≥2 of its checkers; the 5-point and bar-point get a small extra weight.
5. **Prime length** — longest run of consecutive made points (≥2 checkers) owned by `perspective` (caps the opponent's escape).
6. **Anchors** — `perspective` points made inside the **opponent's** home board (defensive anchors).
7. **Checkers on the bar** — `barCount(perspective)` penalty; `barCount(opponent)` bonus.
8. **Back-checker count** — `perspective` checkers still in the opponent's home board quadrant (deep checkers that must escape), penalty.
9. **Race-vs-contact phase switch** — if the two armies can no longer make contact (the most-advanced opposing checkers have passed each other), collapse to **≈ pure pip differential** (it is now a sprint); contact-only features (blot exposure, primes, anchors, back checkers) are zeroed.

`Weights` is a small data class of named constants. Two instances:
- `Weights.FULL` — all features, hand-tuned constants (used by Intermediate, and reused/retuned in Phase 3).
- `Weights.SIMPLIFIED` — pip differential + checkers off + a crude blot penalty only; other weights 0 (used by Beginner).

Weights are hand-tuned constants in Phase 2; precise tuning (and the proof that tiers are correctly ordered) is a Phase 3 concern with the benchmark harness. Phase 2 only needs them good enough that Intermediate clearly beats random (§7).

### 4.3 Shot / hit-probability table

A precomputed constant: for shot distance `1..24`, the number of the 36 ordered dice rolls that can cover that distance with a single checker (direct shots for 1–6, plus combination shots for 5–12 using both dice / doubles), divided by 36. **Approximation (v1):** intermediate-point blocking is **ignored** — the table assumes the indirect path is open. This over-counts some hits but is cheap and adequate for a static eval; refined blocking is a possible Phase 3 improvement. Documented as an approximation in code.

### 4.4 `Expectimax` (1-ply)

```kotlin
fun bestMove(state: BoardState, dice: Dice, legal: List<Move>, weights: Weights): Move
```

Phase 2 = **1-ply**: for each `move` in `legal`, compute `evaluate(MoveGenerator.apply(state, move), state.toMove, weights)` and return the `move` with the maximum score. (At 1-ply there is no further chance node — the current roll is already known; deeper plies that expand the 21 distinct rolls are Phase 3.) Tie-break deterministically (stable: first maximum encountered) so behaviour is reproducible. The function is structured so a `plies ≥ 2` path (chance-node expectation + pruning) can be added later without changing this signature's callers.

### 4.5 `Difficulty` and `HeuristicAiPlayer`

```kotlin
enum class Difficulty(val weights: Weights, val noise: Double, val plies: Int) {
    BEGINNER(Weights.SIMPLIFIED, noise = 0.25, plies = 1),
    INTERMEDIATE(Weights.FULL, noise = 0.0, plies = 1),
}

class HeuristicAiPlayer(
    private val difficulty: Difficulty,
    private val rng: Random = Random.Default,
) : AiPlayer {
    override suspend fun chooseMove(state, dice, legal): Move =
        if (rng.nextDouble() < difficulty.noise) legal.random(rng)
        else Expectimax.bestMove(state, dice, legal, difficulty.weights)
}
```

- `rng` is injected so tests are deterministic (seeded). Beginner's 25% noise makes it human-fallible; Intermediate is noise-free.
- `plies` is carried on `Difficulty` now (always 1 in Phase 2) so Phase 3 only adds rows and a deeper search branch — no new code paths per tier.

## 5. `:core` / `:app` integration

### 5.1 `GameController` changes (stays pure + synchronous)

- Constructor gains `aiSide: Player? = null` (the colour the computer plays; `null` = hot-seat).
- New `fun applyMove(move: Move)`: `committed = MoveGenerator.apply(committed, move)`; clear `staged`/`selectedOrigin`/`dice`/`passing`; recompute `uiState`. This is the AI's commit path (the AI does not tap-stage). Pre: `move` is a legal full turn for the current `dice`.
- Human move-building methods (`tap`, `undo`, `commit`) become no-ops when `toMove == aiSide` (the human cannot manipulate the board on the AI's turn). `roll()` is **not** gated by side — the VM calls it to roll for the AI; the human Roll button is hidden on the AI's turn anyway. `acknowledgePass()` and `newGame()` unchanged (the VM drives auto-pass for the AI too).
- `newGame()` keeps resetting to `initial`. Choosing a *new configuration* (difficulty/colour/opponent) rebuilds the controller (§5.2), not `newGame()`.
- No change to `GameUiState` — "AI thinking" is a presentation concern exposed separately by the VM (§5.2).

### 5.2 `GameViewModel` changes (owns async AI)

- Holds the current `GameController`, an optional `aiPlayer: AiPlayer?`, and `aiSide: Player?`. Exposes the existing `uiState: StateFlow<GameUiState>`, the `events` channel, plus a new `aiThinking: StateFlow<Boolean>` and enough config for the UI to know whose turn it is (e.g. `aiSide`).
- `fun startGame(config: GameConfig)` — builds a fresh `GameController(aiSide = …)` and `HeuristicAiPlayer(difficulty)` (or none for hot-seat), resolves a random colour if chosen, publishes initial state, then calls `maybeRunAi()` (covers the AI-moves-first case when the human is Black).
- Existing actions (`onRoll`, `onCommit`, `onUndo`, `onAcknowledgePass`, `onNewGame`) call through to the controller, publish, then call `maybeRunAi()`.
- `private fun maybeRunAi()` — if `aiPlayer != null && controller.toMove == aiSide && phase != GAME_OVER` and no AI turn is already running, set `aiThinking = true` and launch in `viewModelScope`:
  1. `val events = controller.roll()`; publish (the AI's dice are now visible).
  2. If `events` contains `NoLegalMoves`: brief `delay`, `controller.acknowledgePass()`, publish.
  3. Else: `val legal = MoveGenerator.legalMoves(board, dice)`; `val move = withContext(Dispatchers.Default) { aiPlayer.chooseMove(board, dice, legal) }`; pacing `delay(~800ms)`; `controller.applyMove(move)`; publish.
  4. Clear `aiThinking`; clear the running guard. (After the AI move it is the human's turn; with a single AI side no further `maybeRunAi` recursion is needed, but the call is idempotent.)
- Re-entrancy guard (`aiRunning` flag) prevents overlapping AI turns. `viewModelScope` cancellation on VM clear stops any in-flight search.

### 5.3 `GameConfig`

```kotlin
data class GameConfig(
    val difficulty: Difficulty,      // ignored when opponent == HOT_SEAT
    val humanColor: Player,          // resolved (random already chosen) before startGame
    val opponent: Opponent,          // COMPUTER | HOT_SEAT
)
enum class Opponent { COMPUTER, HOT_SEAT }
```

For `COMPUTER`, `aiSide = humanColor.opponent`. For `HOT_SEAT`, `aiSide = null`.

## 6. UI

- **`SetupScreen`** (new): difficulty (Beginner / Intermediate), your colour (White / Black / Random), opponent (Computer / Hot-seat); a Start button → `vm.startGame(config)`. Landscape, themed to match the board screen.
- **`MainActivity`** hosts a simple top-level state: show `SetupScreen` until a game is started, then `GameScreen`; a "New game" action (the existing GAME_OVER button and a control on the board) returns to `SetupScreen`. No navigation library — a `rememberSaveable` screen enum (`SETUP` / `GAME`) is sufficient.
- **`GameScreen` / tracking panel**: when `opponent == COMPUTER` and it is the AI's turn (`toMove == aiSide`), the panel shows "AI thinking…" and the AI's dice in place of the Roll/Undo/Commit controls; the board ignores taps (the controller already no-ops them). The existing pips/lead/blots/this-turn tracker stays visible.
- Hot-seat renders exactly as Phase 1 (no AI affordances).

## 7. Testing

**`:ai` (JVM):**
- **Evaluator sanity:** more checkers off scores higher; a checker on the bar scores worse than the same checker entered; in a pure race the side with fewer pips is favoured; perspective consistency (evaluating a symmetric/mirrored position yields opposite-signed scores).
- **Phase-switch:** a no-contact position evaluates to ≈ pip differential (contact features contribute ~0).
- **Shot table:** spot-check known values (distance 6 = 11/36 direct shots; distance 1 = 11/36; a representative combination distance) — documents the table is wired correctly.
- **Expectimax 1-ply:** with a stub `Weights`/evaluator that scores a known resulting state highest, `bestMove` returns that move; deterministic tie-break.
- **Noise:** `HeuristicAiPlayer(BEGINNER, seededRng)` returns a random legal move at ~the configured frequency over many trials; `INTERMEDIATE` always returns the expectimax best.
- **Strength smoke (mini self-play, NOT the Phase-3 benchmark):** over ~50–100 seeded games, `HeuristicAiPlayer(INTERMEDIATE)` beats a uniform-random player by a wide margin (assert e.g. ≥80% win rate). Kept small for test-suite speed; the rigorous tier-ordering benchmark is Phase 3.

**`:app` (JVM):**
- `GameController.applyMove` applies a full move and flips `toMove`; `tap`/`undo`/`commit` are no-ops when `toMove == aiSide`; hot-seat (`aiSide == null`) behaviour and all existing Phase 1 controller tests stay green.
- (VM coroutine orchestration is verified by emulator smoke, not unit tests.)

**Emulator smoke (acceptance):**
- Start vs **Beginner** and vs **Intermediate**: human rolls/moves/commits; on the AI's turn "AI thinking…" shows, the AI's dice appear, then its move resolves; turn returns to the human. Auto-pass works for the AI when it has no legal move.
- Colour choice (play as Black → AI moves first). Random colour resolves to one side.
- Hot-seat still plays as in Phase 1.
- Play a game to completion vs the AI → correct win banner.

## 8. Risks / open points

- **Eval quality without the benchmark.** Phase 2 has no rigorous tier-ordering proof (that is Phase 3). Mitigation: the strength smoke test guarantees Intermediate is meaningfully strong (beats random by a wide margin); hand-tuned weights only need to be reasonable, not optimal.
- **Shot-table approximation** (ignores blocking) can mildly over-penalise blots behind a prime. Acceptable for v1 static eval; revisit in Phase 3.
- **`viewModelScope` + `viewModel()` default construction.** `startGame(config)` reconfigures an already-constructed VM rather than relying on constructor injection, so the default `viewModel()` factory still works without a custom factory in Phase 2.

## 9. File plan (for the implementation plan to expand)

```
settings.gradle.kts                       // include(":ai")
gradle/libs.versions.toml                 // (reuse existing coroutines entries)
ai/build.gradle.kts                       // NEW kotlin-jvm module, deps :core + coroutines
ai/src/main/kotlin/dk/rlunde/backgammon/ai/
├── AiPlayer.kt
├── Evaluator.kt          // evaluate() + features
├── Weights.kt            // FULL / SIMPLIFIED constants
├── ShotTable.kt          // precomputed hit-probabilities
├── Expectimax.kt         // 1-ply bestMove
├── Difficulty.kt         // enum + config
└── HeuristicAiPlayer.kt
ai/src/test/kotlin/dk/rlunde/backgammon/ai/   // evaluator, expectimax, noise, strength smoke
app/build.gradle.kts                      // + implementation(project(":ai"))
app/src/main/java/dk/rlunde/backgammon/
├── game/GameController.kt                // + aiSide, applyMove, side-gated human methods
├── game/GameConfig.kt                    // NEW: GameConfig, Opponent
├── viewmodel/GameViewModel.kt            // + aiPlayer/aiSide/aiThinking, startGame, maybeRunAi
├── ui/screens/SetupScreen.kt            // NEW
├── ui/screens/GameScreen.kt              // AI-turn panel state
└── MainActivity.kt                       // SETUP <-> GAME screen state
app/src/test/java/dk/rlunde/backgammon/game/GameControllerTest.kt  // + aiSide/applyMove tests
```

## 10. Workflow

Brainstorming → **this spec** → multi-lens spec review (akereos-spec-reviewer) → implementation plan (writing-plans) → subagent-driven-development → finishing-a-development-branch. Build prerequisites per the project memory (JAVA_HOME = Android Studio JBR; `./gradlew`).
