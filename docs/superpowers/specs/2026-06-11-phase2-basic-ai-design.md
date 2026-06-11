# Phase 2 — Basic AI: Design Spec

**Status:** Approved design — revised after multi-lens spec review (2 blockers + 11 majors + minors addressed). Ready for plan-writing.
**Date:** 2026-06-11
**Builds on:** Phase 0 (`:core` rules engine, merged) and Phase 1 (`:app` playable Compose board, merged). Product spec: `docs/backgammon-app-spec.md` §6 (AI design), §12 (phase plan: *Phase 2 — Basic AI*).

## 1. Goal

Add a single-player mode: the human plays one colour, the computer plays the other. Deliver a new pure-Kotlin `:ai` module with an `AiPlayer` interface, a hand-tuned linear **evaluator**, **1-ply expectimax** move selection, and two difficulty tiers — **Beginner** (simplified eval + injected noise) and **Intermediate** (full eval, no noise). Wire a lightweight in-app setup (difficulty • colour • opponent) and run the AI search off the main thread with a visible "thinking" state. Hot-seat (human-vs-human) remains available.

## 2. Scope

**In scope (Phase 2):**
- `:ai` module: `AiPlayer`, `Evaluator`, `MoveSearch` (1-ply greedy), `Difficulty`/weights, `HeuristicAiPlayer`.
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
        MoveSearch, Difficulty)
:core  (rules engine)            no deps          (UNCHANGED in Phase 2)
```

- New Gradle module `:ai`, package `dk.rlunde.backgammon.ai`. Added to `settings.gradle.kts`; build file mirrors `:core` (kotlin-jvm, JVM-17 bytecode). Dependencies: `:core` only; test: `kotlin-test`. **`:ai` is coroutine-free** — `chooseMove` is a plain (non-`suspend`) pure function (§4.1); all threading lives in `:app`'s VM. Coroutines (`kotlinx-coroutines-core`/`-test`) are an `:app` concern only.
- **Public surface of `:ai`** = `AiPlayer`, `HeuristicAiPlayer`, `Difficulty`. `Evaluator`/`Features`, `Weights`, `ShotTable`, and `MoveSearch` are `internal` (test-visible via the in-module test source set, exactly as `:core` keeps its stage helpers `internal`).
- `:app/build.gradle.kts` adds `implementation(project(":ai"))`.
- **Chosen integration:** the **ViewModel orchestrates** the AI turn; `:core` and `GameController` stay pure and synchronous. Rationale: keeps the rules engine and the turn state machine fully JVM-unit-testable; the only coroutine/threading concern lives at the `androidx` boundary (the VM), exactly as in Phase 1.

## 4. The `:ai` module

### 4.1 `AiPlayer` interface

```kotlin
interface AiPlayer {
    /**
     * Choose a full turn from the legal options. Pure + synchronous; the caller (the VM) runs it
     * off the UI thread via withContext(Dispatchers.Default). Perspective is state.toMove — the AI's
     * own side, guaranteed by the caller.
     * @param legal MUST be MoveGenerator.legalMoves(state, dice) for the same state/dice, non-empty.
     */
    fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move
}
```

- `chooseMove` is **not** `suspend` — it is CPU-bound pure computation, so `:ai` stays coroutine-free. The dispatcher hop is the caller's job (the VM wraps the call in `withContext(Dispatchers.Default)`, §5.2). *(This deviates from product-spec §6.1, which sketched a `suspend` interface for later async work like Phase-5 rollouts; revisit the modifier then. For Phase 2's instant 1-ply search a plain function is cleaner and warning-free.)*
- `legal` is the canonical full-turn set from `MoveGenerator.legalMoves(state, dice)` — the **same** set Phase-1 commit collapses staged sub-moves to, so the AI can never play a turn the staged/human flow would reject. The caller guarantees `legal` is non-empty (auto-pass is handled before `chooseMove` is invoked) and that `state.toMove` is the AI's side. `bestMove` assumes every element is legal and does not re-validate.

### 4.2 `Evaluator`

```kotlin
object Evaluator {
    /**
     * Score the position from [perspective]'s side; positive = good for [perspective].
     * Called on the POST-move BoardState (toMove already flipped by MoveGenerator.apply),
     * with [perspective] = the side that just moved (see §4.4). Pure.
     */
    fun evaluate(state: BoardState, perspective: Player, weights: Weights): Double
}
```

Files: `Evaluator.kt` (the weighted-sum assembly + the race/contact phase dispatch) and `Features.kt` (the feature functions, each `internal fun`) — mirroring `:core`'s small-focused-file convention rather than one god-file.

Returns a score where **positive = good for `perspective`**. A weighted linear combination of features, each `perspective` minus opponent where symmetric. **Perspective contract:** the VM evaluates the position *after* the AI's move (so blot/hit features correctly reflect the opponent's upcoming roll), but always from the mover's perspective — pinning this prevents a sign flip that would make the AI play to lose (tested in §7). Features (Phase 2 set):

1. **Pip differential** — `pipCount(opponent) − pipCount(perspective)` (positive when ahead in the race). Dominant term. **Uses `core.Scoring.pipCount` — do not reimplement.**
2. **Checkers borne off** — `offCount(perspective) − offCount(opponent)`.
3. **Blot exposure** — for each `perspective` blot at point `i` (i.e. `state.isBlotFor(perspective.opponent, i)` — the helper means "a lone checker the *named* player can hit", so pass the opponent), take its next-roll hit probability (§4.3) **× a cost proxy** = the pips that checker would lose if hit (its distance back to re-entry/travel), summed as a **penalty**. Probability-only would misvalue an advanced blot (≈24 pips) against an outfield blot (few pips); weighting by cost reflects shot *equity*, not just shot count.
4. **Home-board points made** — points in `perspective`'s home board (WHITE 1–6, BLACK 19–24) with ≥2 checkers; the 5-point and bar-point get extra weight.
5. **Prime length** — longest run of consecutive made points (≥2) owned by `perspective` (caps the opponent's escape).
6. **Anchors** — `perspective` points made inside the **opponent's** home board (defensive anchors); the advanced anchors (opponent's 20-point "golden point" and bar-point) get extra weight, mirroring feature 4's 5-point/bar-point bonus — a deep 23/24 anchor is worth less.
7. **Checkers on the bar** — `barCount(perspective)` penalty; `barCount(opponent)` bonus.
8. **Back-checker count** — `perspective` checkers still in the opponent's home quadrant (must escape), penalty.
9. **Race-vs-contact phase switch** — if the armies can no longer make contact (most-advanced opposing checkers have passed each other), collapse to **≈ pure pip differential** (now a sprint; reuses `Scoring.pipCount`); contact-only features (blot exposure, primes, anchors, back checkers) are zeroed.

**Out of scope (Phase 2):** the eval optimises *winning*, not the *win value* — no gammon-seeking (keeping the opponent back / on the bar) or gammon-saving play, even though `Scoring.winnerAndValue` already computes ×2/×3. Revisit with match play / cube (Phase 6). Bear-off is played by pip count only — no wastage/crossover term, so non-contact bear-offs may waste pips (acceptable for v1; efficient bear-off is a Phase-3 eval addition, see §8).

`Weights` is a small data class of named constants. Two instances:
- `Weights.FULL` — all 9 features, hand-tuned constants (Intermediate; reused/retuned in Phase 3).
- `Weights.SIMPLIFIED` — pip differential + checkers off + a crude (probability-only) blot penalty; other weights 0 (Beginner).

Weights are hand-tuned in Phase 2; precise tuning and the proof that tiers are *ordered* is a Phase 3 concern (the benchmark harness). Phase 2 needs them only good enough that Intermediate clearly beats random (§7) and plays a recognisable game. The full feature set is built now because it is shared by every later tier; it is validated in Phase 2 by **per-feature directional unit tests** (§7), not just the strength smoke.

### 4.3 Shot / hit-probability table

A precomputed constant: for shot distance `1..24`, the number of the 36 ordered dice rolls that cover that distance with one checker, divided by 36 — counting **direct shots (1–6) plus combination shots (the indirect total)**. Canonical total-coverage counts (out of 36): **1→11, 2→12, 3→14, 4→15, 5→15, 6→17**, then combination-only for 7–12 (8→6, 9→5, 12→3, etc.); 0 for >12 except the doubles-reachable distances. (Distance 1 is direct-only = 11; distance 6 is 11 direct + 6 combination = 17 — the single most strategically important number.) **Approximation (v1):** intermediate-point blocking is **ignored** — the table assumes the indirect path is open, so it slightly *over*-counts hits behind a prime (the safe direction: the bot is too shy, never reckless). Refined blocking is a Phase-3 priority (see §8). Documented as an approximation in code. The §7 test derives the combination counts independently rather than mirroring the implementation.

### 4.4 `MoveSearch` (1-ply greedy)

```kotlin
internal object MoveSearch {
    fun bestMove(state: BoardState, dice: Dice, legal: List<Move>, weights: Weights): Move
}
```

Phase 2 is **1-ply**, which degenerates to **greedy argmax** (not expectimax — there is no chance node when the roll is already known): for each `move` in `legal`, evaluate the post-move position
`Evaluator.evaluate(MoveGenerator.apply(state, move), state.toMove, weights)` (perspective stays `state.toMove`, the mover — see §4.2) and return the `move` with the maximum score. Tie-break deterministically (first maximum encountered) so behaviour is reproducible. Named `MoveSearch`, **not** `Expectimax`, because no expectation is taken; the genuine chance-node expectimax (≥2-ply + pruning) arrives in Phase 3 with its own entry point. No depth/ply scaffolding is added now — the 1-ply argmax needs none.

**Honest limitation:** at 1-ply the bot has no opponent-reply lookahead, so the **blot-exposure feature (§4.2.3) is the *only* signal standing in for "I'll get hit next roll."** Expect Intermediate to play the race well and avoid gross blots, but to make positional/tactical errors a 2-ply bot would not (it judges contact only statically). This tempers the product-spec §6.4 "punishes obvious mistakes" claim for Phase 2 (see §8).

### 4.5 `Difficulty` and `HeuristicAiPlayer`

```kotlin
enum class Difficulty(val weights: Weights, val noise: Double) {
    BEGINNER(Weights.SIMPLIFIED, noise = 0.25),
    INTERMEDIATE(Weights.FULL, noise = 0.0),
}

class HeuristicAiPlayer(
    private val difficulty: Difficulty,
    private val rng: kotlin.random.Random = kotlin.random.Random.Default,
) : AiPlayer {
    override fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move =
        if (rng.nextDouble() < difficulty.noise)
            sampleWeak(state, dice, legal, rng)              // plausible blunder, not absurd
        else
            MoveSearch.bestMove(state, dice, legal, difficulty.weights)
}
```

- `rng` is injected `kotlin.random.Random` (seeded in tests for determinism; independent of `:core`'s `DiceRoller`, which governs the physical dice). Beginner's 25% noise makes it human-fallible; Intermediate is noise-free.
- **Beginner noise samples from the top-K (≈3) moves ranked by the simplified eval**, not a uniform-random legal move. Uniform-random produces *non-human* blunders (busting a prime, dancing into a triple shot); top-K sampling yields plausible weak play, matching product-spec §6.4 "feels human-fallible." (`sampleWeak` ranks `legal` by `SIMPLIFIED` eval and picks uniformly among the top 3.)
- `Difficulty` carries only `weights` + `noise` (both have live consumers); **no `plies` field** — Phase 3 adds depth alongside the deeper-search branch that actually reads it.

## 5. `:core` / `:app` integration

### 5.1 `GameController` changes (stays pure + synchronous)

- Constructor gains `aiSide: Player? = null` (the colour the computer plays; `null` = hot-seat).
- New `fun applyMove(move: Move)`: `committed = MoveGenerator.apply(committed, move)`; clear `staged`/`selectedOrigin`/`dice`/`passing`; recompute `uiState`. This is the AI's commit path (the AI does not tap-stage). **Pre:** `move ∈ MoveGenerator.legalMoves(committed, dice)` — the same canonical full-turn set the staged/human flow collapses to (verified by a test, §7); `applyMove` trusts this and does not re-validate.
- Human move-building methods (`tap`, `undo`, `commit`) become no-ops when `toMove == aiSide`. **Hot-seat invariant:** `aiSide == null` and `Player` is non-null, so `toMove == aiSide` is always false ⇒ all human methods stay active and hot-seat is byte-for-byte unchanged. `roll()` is **not** gated by side — the VM calls it to roll for the AI (and the human Roll button is hidden on the AI's turn). `acknowledgePass()`/`newGame()` unchanged.
- **`GameUiState` gains `aiThinking: Boolean = false` and `aiSide: Player? = null`, both defaulted.** `GameController.compute()` leaves them at the defaults (it knows nothing about async or opponent type — it stays pure); the **VM populates them** when it republishes (§5.2). This keeps the established single-immutable-snapshot contract (one atomic emission) instead of a second `StateFlow` that could recompose out of step with `uiState`.

### 5.2 `GameViewModel` changes (owns async AI)

- Holds the current `GameController`, optional `aiPlayer: AiPlayer?`, `aiSide: Player?`, and the AI-turn `Job?`. Exposes only the existing `uiState: StateFlow<GameUiState>` and `events` channel — `aiThinking`/`aiSide` ride inside the published `GameUiState` (§5.1), so the UI reads one consistent snapshot. `publish()` copies the controller's `uiState` with the VM's `aiThinking`/`aiSide` overlaid, then emits.
- **Testable seam:** the turn-driving logic lives in a small `AiTurnDriver` (or an injectable suspend function) taking the `GameController`, the `AiPlayer`, and an injected `CoroutineDispatcher` (defaults to `Dispatchers.Default`). The VM owns the coroutine/lifecycle; the driver is pure enough to unit-test with `kotlinx-coroutines-test` + a fake `AiPlayer` over a `SeededDiceRoller` (§7).
- `fun startGame(config: GameConfig)` — **idempotent / one-shot:** if a game is already in progress it is ignored; it runs only on the SETUP→GAME transition (never on recomposition, so rotation can't rebuild the controller mid-game — §8/§M10). Resolves a Random colour **here in the VM** via a seedable RNG (so it's testable), builds `GameController(aiSide = …)` + `HeuristicAiPlayer(difficulty)` (or none for hot-seat), publishes initial state, then `maybeRunAi()` (covers AI-moves-first when the human is Black, including Random→Black).
- Actions (`onRoll`, `onCommit`, `onUndo`, `onAcknowledgePass`) → controller, `publish()`, then `maybeRunAi()`. **`onNewGame()` returns to SETUP and first cancels any in-flight AI `Job`** and resets `aiRunning`/`aiThinking` (so a stale AI turn can never wake and mutate a fresh controller).
- `private fun maybeRunAi()` — runs entirely on the main dispatcher up to the `withContext` hop; the `aiRunning` guard is a plain `var` (no atomic needed — main-confined). If `aiPlayer != null && controller.toMove == aiSide && controller.phase != GAME_OVER && !aiRunning`: set `aiRunning = true`, `aiThinking = true`, store the `Job` from `viewModelScope.launch`:
  ```
  try {
    val events = controller.roll()          // AI rolls via the SAME controller.roll()/DiceRoller as the human
    publish()                                // dice now visible (state only — see below)
    delay(DICE_VISIBLE)                      // ~600ms so the roll is readable
    if (UiEvent.NoLegalMoves in events) {    // AI auto-pass: do NOT forward the event
      controller.acknowledgePass(); publish()
    } else {
      val (board, dice) = controller.snapshot()         // immutable data-class snapshot
      val move = withContext(dispatcher) {              // legalMoves + search BOTH off-main
        aiPlayer.chooseMove(board, dice, MoveGenerator.legalMoves(board, dice))
      }
      controller.applyMove(move); publish()              // publish the post-move state…
    }
  } finally { aiThinking = false; aiRunning = false; publish() }   // …then clear, always
  ```
  - **B1 — no human dialog on the AI's turn:** the AI path calls `publish()` (state only); it never forwards `UiEvent.NoLegalMoves` to the shared `events` channel (that channel exists only to pop the *human's* auto-pass dialog). The `GameScreen` pass dialog is additionally suppressed when `toMove == aiSide`.
  - **M1 — `finally`** always clears `aiThinking`/`aiRunning`, so a throw in `chooseMove`/`legalMoves` or a `CancellationException` can never wedge the game on "AI thinking…". On an unexpected throw, log and leave the turn re-runnable (the guard is clear); it is not surfaced as a user error in Phase 2.
  - **M4** — `legalMoves` (potentially hundreds of turns on a doubles roll) runs inside `withContext`, not on the main thread. The captured `board`/`dice` are immutable snapshots; all controller mutation is main-confined, and the guard blocks re-entry, so nothing mutates the controller across the hop.
- After the AI move it is the human's turn (single AI side), so no recursion; if the AI move ended the game, `phase == GAME_OVER` stops `maybeRunAi` and the win banner shows (publish-then-clear order guarantees no lingering "thinking" over a finished game).

### 5.3 `GameConfig`

```kotlin
data class GameConfig(
    val difficulty: Difficulty,      // ignored when opponent == HOT_SEAT
    val humanColor: Player?,         // null = Random; the VM resolves it (seedable) in startGame
    val opponent: Opponent,          // COMPUTER | HOT_SEAT
)
enum class Opponent { COMPUTER, HOT_SEAT }
```

`humanColor == null` means the SetupScreen's "Random" choice; the **VM** resolves it via its seedable RNG inside `startGame` (testable), not the screen. For `COMPUTER`, `aiSide = resolvedHumanColor.opponent`; for `HOT_SEAT`, `aiSide = null`. `GameConfig`/`Opponent` live in `game/` (pure pre-Android domain types, alongside `GameUiState`/`GameController`).

## 6. UI

- **`SetupScreen`** (new): difficulty (Beginner / Intermediate), your colour (White / Black / Random), opponent (Computer / Hot-seat); a Start button → `vm.startGame(config)`. Landscape, themed to match the board screen.
- **`MainActivity`** hosts a simple top-level state: show `SetupScreen` until a game is started, then `GameScreen`; a "New game" action (the existing GAME_OVER button and a control on the board) returns to `SetupScreen`. No navigation library — a `rememberSaveable` screen enum (`SETUP` / `GAME`) is sufficient.
- **`GameScreen` / tracking panel**: when `state.aiSide != null` and it is the AI's turn (`state.toMove == state.aiSide`), the panel shows "AI thinking…" and the AI's dice in place of the Roll/Undo/Commit controls; the board ignores taps (the controller already no-ops them). The existing pips/lead/blots/this-turn tracker stays visible. **The "No legal moves" auto-pass dialog is suppressed when `state.toMove == state.aiSide`** (the AI passes silently — that dialog is for the human only; see §5.2/B1).
- Hot-seat (`state.aiSide == null`) renders exactly as Phase 1 (no AI affordances).

## 7. Testing

**`:ai` (JVM):**
- **Perspective / zero-sum (primary sign guard):** for the symmetric features, `evaluate(s, WHITE, w) == -evaluate(s, BLACK, w)` (within epsilon) on several positions including `startingPosition()` — a true zero-sum identity on the *same* state, which needs no error-prone mirror helper and catches the perspective-subtraction sign bug for every symmetric feature at once.
- **Hit preference (post-move sign guard):** the AI prefers a move that hits an opponent blot over an equal-pip non-hitting move — catches the pre/post-move perspective confusion the symmetry test misses.
- **Per-feature direction:** one focused test per feature that it moves the eval the right way (more off → higher; a checker on the bar → lower than entered; a made 5-point → higher; a longer prime → higher; an advanced anchor → higher than a deep one; a costly-blot exposure → lower than a cheap-blot exposure). This validates the *full* 9-feature eval at unit level (not just the strength smoke).
- **Phase switch:** a no-contact position evaluates to ≈ pip differential (contact features contribute ~0).
- **Shot table:** assert the canonical counts against an **independently hand-derived** enumeration (list the covering rolls), e.g. distance 1 = 11/36 (direct only), distance 6 = 17/36 (11 direct + 6 combination), one 7–12 combo distance; plus a monotonic-direction sanity check. Validates the enumeration, not a mirror of the implementation.
- **MoveSearch 1-ply:** with a stub `Weights`/evaluator scoring a known resulting state highest, `bestMove` returns that move; deterministic tie-break.
- **Noise (deterministic):** with a fixed seed + fixed trial count, assert the *exact* count of weak-branch vs best-branch picks for `BEGINNER`; assert `INTERMEDIATE` (noise = 0.0) never takes the weak branch and a hypothetical noise = 1.0 always does; assert the weak branch only ever returns a top-K move (never an arbitrary legal one).
- **Strength smoke (mini self-play, NOT the Phase-3 benchmark):** a **fixed array of game seeds**; the dice roller AND every `Random` (AI noise + the random baseline opponent) seeded; the result is therefore a single deterministic number — assert it is ≥ 80% over the fixed seed set (a deterministic threshold, not a probabilistic claim). **Runtime budget ~<2s** so it stays a unit test (keep the game count at the low end). The rigorous tier-ordering benchmark is Phase 3.

**`:app` (JVM):**
- **`GameController`:** `applyMove` applies a full move and flips `toMove`; `applyMove` of a move that bears off the 15th checker yields `phase == GAME_OVER` with the correct `winner`/`winValue` (mirrors the existing "game over reports winner" test — the AI-wins path); `tap`/`undo`/`commit` are no-ops when `toMove == aiSide`, while **`roll()` still works when `toMove == aiSide`** (pins the deliberate §5.1 asymmetry); a turn drawn from `MoveGenerator.legalMoves` is accepted by `applyMove` and equals one assemblable via the staged/`TurnPlanner` flow (legality-contract test); hot-seat (`aiSide == null`) plays **both** colours and all existing Phase 1 controller tests stay green.
- **`AiTurnDriver` (the AI-turn loop — unit-tested via the §5.2 seam):** with `kotlinx-coroutines-test` (a `:app` test dep), an injected `TestDispatcher`, a fake `AiPlayer`, and a `SeededDiceRoller`: (a) a normal turn rolls, applies the chosen move, and flips `toMove`; (b) the no-legal-move path calls `acknowledgePass` and **never calls `chooseMove`** and **never emits `NoLegalMoves` to the human `events` channel**; (c) overlapping invocations are rejected by the guard, and the guard + `aiThinking` are cleared on **every** exit path including `finally` after a thrown `chooseMove`; (d) AI-moves-first when the human is Black; (e) `onNewGame` mid-turn cancels the job and a stale move is never applied to the new controller.
- Only true Compose rendering is left to emulator smoke.

**Emulator smoke (acceptance):**
- Start vs **Beginner** and vs **Intermediate**: human rolls/moves/commits; on the AI's turn "AI thinking…" shows, the AI's dice appear (visible for ~600ms), then its move resolves; turn returns to the human. **No human "no legal moves" dialog ever appears on the AI's turn.**
- Colour = Black → AI moves first; Random resolves to one side.
- New game while the AI is thinking → returns to setup cleanly, no stuck "thinking", no stale move applied.
- Hot-seat still plays as in Phase 1 (both colours).
- Play a game to completion vs the AI → correct win banner, no lingering "thinking".

**Done = ** all `:ai` + `GameController` + `AiTurnDriver` unit tests green; the strength smoke passes at the pinned threshold on its fixed seed set; and the emulator smoke checklist passes for both tiers, both colours, hot-seat, new-game-mid-think, and a game played to a win banner.

## 8. Risks / open points

- **1-ply plays only roughly positional.** With no opponent-reply lookahead, the blot-exposure feature is the *sole* proxy for "I'll get hit," so Intermediate plays the race well but will make tactical/positional errors a 2-ply bot avoids. This honestly tempers product-spec §6.4's "punishes obvious mistakes" for Phase 2; 2-ply (Phase 3) is what makes Intermediate→Advanced genuinely strong.
- **Eval quality without the benchmark.** No rigorous tier-ordering proof in Phase 2 (Phase 3). Mitigation: per-feature directional tests validate each feature's sign/direction now, and the strength smoke guarantees Intermediate clearly beats random; weights only need to be reasonable.
- **Shot-table approximation** (ignores intermediate blocking) over-counts hits behind a prime — the *safe* direction (the bot is too shy, never reckless). But because the blot feature is the only reply-proxy at 1-ply, over-counting can suppress an otherwise-correct priming/holding play. Real blocking is a **Phase-3 priority**, not a "possible improvement."
- **Bear-off by pip count only.** No wastage/crossover term, so non-contact bear-offs may waste pips vs optimal. Acceptable for v1; an efficient-bear-off eval term is a Phase-3 addition.
- **Lifecycle / rotation.** The retained `GameViewModel` holds all game + AI state across configuration changes; an in-flight AI turn continues uninterrupted on rotation (only `onCleared` cancels `viewModelScope`). `startGame` is idempotent/one-shot and the `MainActivity` SETUP/GAME enum is `rememberSaveable`, so a config change re-attaches to the existing game rather than rebuilding the controller. State is in-memory only this phase — process death loses the game (persistence/resume is a later phase). `viewModel()` default construction still works because `startGame(config)` reconfigures the already-constructed VM (no custom factory needed).

## 9. File plan (for the implementation plan to expand)

```
settings.gradle.kts                       // include(":ai")
gradle/libs.versions.toml                 // (existing kotlin entry; no new deps for :ai)
ai/build.gradle.kts                       // NEW kotlin-jvm module, dep :core only (coroutine-free)
ai/src/main/kotlin/dk/rlunde/backgammon/ai/
├── AiPlayer.kt           // interface (non-suspend)        [public]
├── Evaluator.kt          // object Evaluator: weighted sum + phase dispatch   [internal]
├── Features.kt           // the 9 feature fns, each internal fun              [internal]
├── Weights.kt            // FULL / SIMPLIFIED constants                       [internal]
├── ShotTable.kt          // precomputed hit-probabilities                     [internal]
├── MoveSearch.kt         // object MoveSearch.bestMove (1-ply greedy argmax)  [internal]
├── Difficulty.kt         // enum (weights, noise) — no plies                  [public]
└── HeuristicAiPlayer.kt  // AiPlayer impl (+ sampleWeak top-K)                [public]
ai/src/test/kotlin/dk/rlunde/backgammon/ai/   // perspective/zero-sum, hit-pref, per-feature,
                                              // phase-switch, shot-table, MoveSearch, noise, strength smoke
app/build.gradle.kts                      // + implementation(project(":ai")); + coroutines-test (test)
app/src/main/java/dk/rlunde/backgammon/
├── game/GameController.kt                // + aiSide, applyMove, side-gated human methods
├── game/GameConfig.kt                    // NEW: GameConfig, Opponent
├── game/GameUiState.kt                   // + aiThinking, aiSide (defaulted, VM-populated)
├── game/AiTurnDriver.kt                  // NEW: testable AI-turn loop (injected dispatcher)
├── viewmodel/GameViewModel.kt            // + aiPlayer/aiSide/job, startGame (idempotent), maybeRunAi, publish overlay
├── ui/screens/SetupScreen.kt            // NEW
├── ui/screens/GameScreen.kt              // AI-turn panel state + pass-dialog suppression on AI turn
└── MainActivity.kt                       // SETUP <-> GAME (rememberSaveable enum)
app/src/test/java/dk/rlunde/backgammon/game/
├── GameControllerTest.kt                 // + aiSide/applyMove/roll-not-gated/legality/win tests
└── AiTurnDriverTest.kt                   // NEW: AI-turn loop (coroutines-test + fake AiPlayer + SeededDiceRoller)
```

## 10. Workflow

Brainstorming → **this spec** → multi-lens spec review (akereos-spec-reviewer) → implementation plan (writing-plans) → subagent-driven-development → finishing-a-development-branch. Build prerequisites per the project memory (JAVA_HOME = Android Studio JBR; `./gradlew`).
