# Backgammon (Android) — Build Specification

**Audience:** Claude Code (implementation), Lunde (owner)
**Goal:** A polished, fully offline single-player Backgammon app for Android, with a configurable AI opponent across multiple difficulty levels, **and a training mode that analyses your play, explains better moves, and tracks your improvement over time.**

---

## 1. Product goals & hard constraints

| Requirement | Decision |
|---|---|
| Platform | Android phone (portrait-first). |
| Connectivity | **100% offline.** No network permission requested at all. AI, persistence, everything runs on-device. |
| AI opponent | Multiple selectable difficulty levels (4 tiers, see §6). |
| Training | The AI doubles as a coach: live error feedback, post-game review, position puzzles, and improvement tracking (see §7). All on-device. |
| Graphics | Simple but attractive. Vector-drawn (no bitmap assets), scales to any screen, classic-but-clean look. |
| Persistence | Game resumes after closing the app. All local. |

**Explicit non-goals (v1):** online/multiplayer, accounts, ads, analytics, cloud sync, iOS.

---

## 2. Tech stack & rationale

- **Language:** Kotlin
- **UI:** Jetpack Compose (board drawn with `Canvas` / `drawScope`)
- **State:** `ViewModel` + Kotlin `StateFlow`
- **Persistence:** Jetpack DataStore for settings; serialized game state (kotlinx-serialization → JSON) for resume; Room/SQLite for the training analysis log (Phase 5) and optional game history (Phase 6)
- **Async:** Kotlin Coroutines (AI search runs off the main thread on `Dispatchers.Default`)
- **Min SDK:** 26 (Android 8.0); Target SDK: latest stable
- **No third-party game engine.** A board game is small enough to draw with Compose Canvas directly, which keeps the binary tiny and the rendering crisp at any DPI.

**Why native Kotlin over Flutter/React Native:** single target (Android only), no extra toolchain, first-class Canvas, and a pure-Kotlin engine is trivially unit-testable. *Alternative:* if iOS is ever wanted, Flutter is the cleanest port path — but that's a future decision, not v1.

---

## 3. Architecture

Strict layering, dependencies point **downward only**:

```
ui/  (Compose, ViewModel)        ← depends on core + ai + data
  ↓
ai/  (Evaluator, Search, Players) ← depends on core only
  ↓
core/ (rules engine, pure Kotlin) ← depends on nothing (no Android imports)
data/ (persistence, settings)     ← depends on core
```

The key design principle: **`core/` and `ai/` are pure Kotlin with zero Android dependencies.** This means the entire game logic and AI can be exercised by JVM unit tests and a self-play benchmark harness without an emulator. This matters for verifying that difficulty tiers are actually ordered (Expert beats Beginner at the expected rate) and that move generation is correct.

---

## 4. Domain model (`core/`)

Standard internal board, numbered 1–24 from **White's** perspective:

- Points **1–6** = White's home board; **19–24** = Black's home board.
- White bears off toward point 0; Black bears off toward point 25.
- One signed `Int` per point: `+n` = n White checkers, `-n` = n Black checkers, `0` = empty.

```kotlin
enum class Player { WHITE, BLACK }

data class BoardState(
    val points: IntArray,      // size 26: index 1..24 are points; 0 and 25 are off-board sentinels
    val bar: Map<Player, Int>, // checkers on the bar per player
    val off: Map<Player, Int>, // checkers borne off per player
    val toMove: Player
)

data class Dice(val a: Int, val b: Int) {
    val isDouble get() = a == b
    fun pips(): List<Int> = if (isDouble) List(4) { a } else listOf(a, b)
}

// A single checker movement (one die used). A full turn is an ordered list of these.
data class SubMove(val from: Int, val to: Int, val die: Int, val isHit: Boolean)

// A complete legal play for a given dice roll.
data class Move(val subMoves: List<SubMove>)
```

**Invariant:** total checkers per player (`points + bar + off`) always = 15.

---

## 5. Rules engine (`core/`)

Pure functions. The headline is `MoveGenerator`:

```kotlin
object MoveGenerator {
    /** All distinct, fully-legal turns for [state] given [dice]. */
    fun legalMoves(state: BoardState, dice: Dice): List<Move>

    /** Apply a complete turn, returning the resulting state (hits → bar, etc.). */
    fun apply(state: BoardState, move: Move): BoardState
}
```

Rules that **must** be enforced (these are where backgammon engines usually have bugs — write tests for each):

1. **Bar first.** If you have checkers on the bar, you must re-enter them before any other move. If you cannot enter, the turn is forfeit.
2. **Blocked points.** A point with 2+ opposing checkers cannot be landed on.
3. **Hitting.** Landing on a point with exactly 1 opposing checker (a *blot*) sends it to the bar.
4. **Doubles** give four moves of that value.
5. **Use the maximum number of dice possible.** If you can play both dice, you must. If only one is playable, you must play it; if either single die is playable but not both, you must play the **larger**. (Generate full legal-move set, then filter to those using the max number of pips — this rule falls out naturally.)
6. **Bearing off** is allowed only when all 15 of a player's checkers are in their home board. Bear off with an exact roll, or with a higher roll **only if** no checker sits on a higher point. A checker may also be moved within the home board instead of borne off.
7. **Win detection & gammon/backgammon scoring** (1× / 2× / 3× — relevant for match play, Phase 5).

**RNG:** dice come from a seedable source so games are reproducible in tests.

```kotlin
interface DiceRoller { fun roll(): Dice }
class SeededDiceRoller(seed: Long) : DiceRoller { /* deterministic */ }
```

---

## 6. AI design (`ai/`) — the interesting part

### 6.1 Interface

Difficulties are interchangeable strategies behind one interface:

```kotlin
interface AiPlayer {
    /** Choose a full turn from the legal options. Runs off the UI thread. */
    suspend fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move
    // Phase 5: cube decisions
    // suspend fun shouldDouble(state: BoardState): Boolean
    // suspend fun shouldTake(state: BoardState): Boolean
}
```

### 6.2 Core algorithm: Expectimax

Dice are stochastic, so search alternates **decision nodes** (a player choosing the best move) with **chance nodes** (the dice roll). A chance node expands over the **21 distinct rolls**: 15 non-doubles at probability 2/36 each, 6 doubles at 1/36 each.

```
value(state, depth):
  if depth == 0 or terminal: return evaluate(state)
  expectedValue = 0
  for each of 21 distinct rolls r, weight w:
    best = max over legalMoves(state, r) of value(apply(state, move), depth - 1)
    expectedValue += w * best
  return expectedValue
```

Branching is large (a roll can have dozens of legal turns), so for depth ≥ 2 **prune to the top-K candidate moves** at each node, ranked by a cheap 1-ply eval, before recursing (K ≈ 8–10). Cap total node budget per move so even Expert returns in well under a second on a phone.

### 6.3 Evaluation function

A weighted linear combination of features, always from the perspective of the side to move (positive = good for that side):

- **Pip count differential** (the dominant term in race positions)
- **Checkers borne off** (per side)
- **Blot exposure:** for each of your blots, the probability it gets hit next roll (precompute the hit-probability table by shot distance)
- **Points made in your home board** (especially the bar-point / 5-point)
- **Prime length** (consecutive made points blocking the opponent)
- **Anchors** in the opponent's home board
- **Checkers on the bar** (penalty if yours, bonus if theirs)
- **Back-checker count** (deep checkers that still need to escape)
- A **race-vs-contact phase switch:** once the two armies have passed each other (no contact possible), collapse the eval to almost pure pip count — it's now a sprint.

Weights are hand-tuned constants in v1. *Upgrade path (optional, plays to your strengths):* train a small evaluation net in Python — TD-Gammon style self-play with TD(λ), the classic result that a neural net learned expert-level backgammon purely from self-play — then export the weights to a small on-device model or a flat weight table. The on-device search stays the same; only `evaluate()` changes. Keep this out of v1 so training isn't a shipping blocker.

### 6.4 Difficulty tiers

| Tier | Algorithm | Notes |
|---|---|---|
| **Beginner** | 1-ply greedy on a *simplified* eval (pip count + crude blot avoidance), with injected noise: ~25% of turns pick a random legal move. | Feels human-fallible, beatable by a new player. |
| **Intermediate** | Full 1-ply expectimax + full eval, no noise. | Solid, punishes obvious mistakes. |
| **Advanced** | 2-ply expectimax + full eval + move pruning. | Strong club-level play. |
| **Expert** | 3-ply expectimax + tuned eval (+ NN eval if the optional upgrade is done). | Tournament-strength feel. |

The noise factor and ply depth are config parameters, so difficulty is a small data table, not four separate code paths.

### 6.5 Analysis engine (the trainer's brain)

The trainer is not a separate AI — it reuses the exact same search and evaluation, just pointed at *your* move instead of generating its own. For a given roll the engine already ranks every legal move by equity; your move's **equity loss** is simply `equity(best) − equity(yours)`. That single number drives all coaching.

```kotlin
enum class MoveClass { BEST, GOOD, DOUBTFUL, ERROR, BLUNDER }

data class RankedMove(val move: Move, val equity: Double)

data class FeatureDelta(val feature: String, val delta: Double) // why best beats yours

data class MoveAnalysis(
    val played: Move,
    val best: Move,
    val equityLoss: Double,            // 0.0 = you found the best play
    val classification: MoveClass,     // banded from equityLoss (tunable thresholds)
    val ranked: List<RankedMove>,      // all legal plays, best-first
    val explanation: List<FeatureDelta> // per-eval-feature breakdown of the gap
)

object Analyzer {
    suspend fun analyzeMove(state: BoardState, dice: Dice, played: Move, depth: Int): MoveAnalysis
    suspend fun analyzeGame(history: List<GameTurn>, depth: Int): GameReport
}
```

Two properties of the existing design make this cheap and unusually good:

- **Explanations come for free from the linear evaluation function.** Because the eval is a weighted sum of named features (§6.3), the difference between your move and the best move decomposes directly into per-feature deltas — "the better play makes your 5-point (+0.18) and cuts blot exposure (+0.07), at a small pip cost (−0.03)." A black-box neural net can't do this without extra attribution work. (Worth keeping in mind if you later go the NN route: you'd want to keep the linear features available for explanation even if a net produces the headline equity.)
- **Win probability is already needed for the cube** (§8), so the coach can show "your move dropped your win chance from 58% to 51%" alongside raw equity.

**Honest constraint — the coach is only as good as the engine judging it.** A 1-ply hand-tuned evaluator will confidently mislabel good moves as errors, which is worse than no coach. So:

- Analysis always runs at **Expert depth or higher**, independent of the difficulty you're *playing* against. (You can practise against Beginner but still be coached by Expert.)
- For trustworthy equities, support an optional **truncated rollout** in review mode: from a candidate position, play it out *N* times (e.g. 72–360 games) with the AI to a depth limit and average the result. Far more accurate than static eval, too slow for live play, fine for post-game.
- This is the feature that most justifies the optional **Python-trained neural eval** (§6.3): a stronger evaluator makes a materially more trustworthy coach.

---

## 7. Training & coaching mode (`ai/` + `ui/`)

Three modes, all built on the §6.5 analyzer. Training is a per-game toggle (a normal game has it off), so the opponent and the coach are independent choices.

### 7.1 Coach mode (live)
Plays like a normal game, but after each of *your* committed turns the analyzer runs (at Expert depth, in the background) and:
- Tags the move with its `MoveClass` and the equity lost, shown unobtrusively (a small coloured marker — green for best, amber for inaccuracy, red for blunder).
- On a tap, expands into the explanation: the better play highlighted on the board, the ranked alternatives, and the per-feature reasons (§6.5).
- **Take-back option:** if the move was an error/blunder, offer "try again" — revert and let you replay the roll. Default on in Coach mode, off in normal play; configurable.
- Optional **pre-move prompting:** before you move, the coach can ask you to commit, then reveal whether you matched the best play (turns coaching into active recall rather than passive correction).

### 7.2 Review mode (post-game)
Walk the finished game move by move. For each decision: your play, the best play, equity lost, win-probability swing, and the feature breakdown. A summary header gives:
- **Decisions analysed**, count of blunders / errors / inaccuracies.
- An **error rate** = average equity lost per decision (lower is better), plus the same split for checker plays vs. cube decisions.
- Your **biggest single blunder**, jump-to.

Review can run at higher fidelity than live Coach: enable **rollouts** (§6.5) for the equities. This is slow, so it runs as a one-off background job with a progress indicator, not in real time.

> **Metric honesty:** report the error rate in our engine's own equity units and use it to track *you against your past self*. Do **not** badge it as the normalised "PR" from desktop tools like eXtreme Gammon or GNU Backgammon — that figure depends on their rollout-grade equities and a specific normalisation, and claiming parity would be misleading. An internally consistent, improving number is the honest and genuinely useful thing here.

### 7.3 Position trainer (puzzles)
A standalone mode that presents a position + roll and asks for the best play, then scores you against the analyzer. Two sources of puzzles, both offline:
- A small **curated library** of classic motifs shipped with the app (priming battles, back-game timing, bear-off safety, blitz/attack, doubling decisions). Stored as serialized `BoardState` + roll + a short teaching note.
- **Auto-generated** puzzles mined from the self-play harness (§11): flag any position where one move beats all alternatives by a large equity margin — those are exactly the instructive "find the only good play" spots. This gives an endless supply with no manual authoring.

### 7.4 Improvement tracking
When training is on, persist each `MoveAnalysis` to the local history DB (§10). A simple **progress screen** then shows error-rate trend over time, a breakdown by game phase (opening / middle-game contact / bear-off) and by error type, and your puzzle accuracy. This is the part that turns "the AI told me I blundered" into "my back-game play has measurably improved over 40 games" — the consistency-of-decision angle you care about, applied to your own learning.

---

## 8. Doubling cube & match play — **OPTIONAL (Phase 6)**

If included:
- Cube state: current value (1→2→4…), owner (or centred).
- AI cube decisions from an estimated win probability (normalise the eval, or run a few quick rollouts): roughly **double** when win prob is in the ~70–80% "market window" and the position isn't too good to cash; **take** down to roughly the 25% cubeful drop point. These are tunable thresholds.
- Match play: play to *N* points, with gammon/backgammon multipliers and the Crawford rule.

Recommend shipping v1 as **single-game money play with no cube** and adding this later.

---

## 9. UI / UX (`ui/`)

### 9.1 Screens
1. **Home / New Game:** difficulty picker (4 tiers), choose your colour (or random), **training mode on/off**, [Phase 6: cube on/off, match length], "Resume game" if one is saved.
2. **Game board** (the main screen; shows live coach markers when training is on).
3. **Trainer hub:** position puzzles + your progress/error-rate trend.
4. **Settings:** animation speed, board theme, sound on/off, haptics on/off, coach take-backs on/off.

### 9.2 Board rendering (Compose `Canvas`)
- Portrait layout: board fills the width, 24 triangular points (alternating two tones), centre **bar**, bear-off **trays** down one side.
- Checkers: solid filled circles with a subtle inner ring/highlight for depth; stack and fan when a point holds >5.
- Dice: rounded squares with drawn pips; show both dice (or four for doubles).
- Everything sized relative to canvas dimensions so it's resolution-independent.

### 9.3 Visual style
Classic but clean and flat-with-subtle-depth. Suggested default theme:
- Board felt: deep teal `#1F4E4A` or warm walnut `#5A3E2B` (offer both as themes)
- Points: alternating `#E8DCC0` (cream) / `#9C6B3F` (tan)
- White checkers: `#F4F1EA` with `#C9C2B0` ring; Black checkers: `#23262B` with `#3A3F47` ring
- Accent / legal-move highlight: `#E0A526` (amber)
- Keep it to ~2 board themes in v1; don't over-build theming.

### 9.4 Interaction
- **Tap-to-move** (primary, best for one-handed use on a flight): tap a checker → legal destinations glow → tap a destination to move that sub-move. Repeat for the second die. Optional drag-to-move can come later.
- **Roll:** tap the dice (or a tap anywhere on your side) to roll; respect an "auto-roll" setting.
- **Undo:** revert sub-moves freely until you commit the turn.
- **Commit turn** button appears once a legal complete turn is staged.
- **No legal moves** → show a brief "no moves" toast and auto-pass.
- Short eased animations for checker slides, hits (the hit checker flies to the bar), and bear-off. Animation speed is a setting (and a "instant" option).
- Light **haptic** tick on roll/hit, and a discreet sound toggle.

### 9.5 On-board info
- Live **pip count** for both sides (toggleable).
- **Hint** button: asks the current AI difficulty for its best move and highlights it (nice for learning; toggle in settings).
- **Coach markers** (training mode only): the small green/amber/red indicator per move, tappable for the full explanation.
- Whose turn / "AI is thinking…" / "Coach analysing…" indicator while a search runs.

---

## 10. Persistence (`data/`)
- **Resume:** serialize `BoardState` + dice + difficulty + training-on flag + cube/match state to JSON on every committed turn; restore on launch.
- **Settings:** DataStore (theme, animation speed, sound, haptics, auto-roll, hints, pip count, coach take-backs).
- **Analysis log (with training, Phase 5):** Room table of per-decision `MoveAnalysis` records (date, difficulty, equity loss, classification, phase) — this is what powers the improvement-tracking screen (§7.4). Keep it compact: store the numbers and a position reference, not full ranked move lists.
- **Game history & stats (optional, Phase 6):** completed-game results and win rate per difficulty.

---

## 11. Testing & validation
- **Engine unit tests:** legal-move generation against known positions, including the tricky cases — bar re-entry, must-use-larger-die, doubles, bear-off exact/overflow, hitting. Use the seedable roller for determinism.
- **Invariant checks:** 15 checkers per side always conserved after `apply`.
- **AI self-play harness** (JVM, no emulator): run e.g. 500 games per pairing and assert the difficulty ordering holds — Expert should beat Beginner by a wide margin, Advanced should beat Intermediate, etc. This is the real proof the tiers mean something. *(Doubles as the puzzle-mining source for §7.3.)*
- **Analyzer self-consistency:** when the analyzer is asked to grade the move it would itself pick, equity loss must be 0 and class `BEST`; feature deltas of the best move against itself must sum to 0. Cheap test, catches sign/perspective bugs in the coach.
- **Performance budget:** assert Expert returns a move within a target wall-clock on a mid-range device, and that a live Coach analysis stays within an acceptable background latency (rollouts are exempt — they're explicitly slow).

---

## 12. Phased build plan (suggested order for Claude Code)

- **Phase 0 — Engine:** project scaffold, `BoardState`/`Dice`/`Move`, `MoveGenerator.legalMoves`/`apply`, win detection, seedable roller. Full unit-test suite. *(No UI yet.)*
- **Phase 1 — Playable board:** Compose board rendering + tap-to-move + commit/undo, hot-seat (human vs human) to validate rules against the UI.
- **Phase 2 — Basic AI:** `AiPlayer` interface, Beginner + Intermediate (1-ply expectimax + eval). Difficulty picker wired up. Coroutine off-main-thread search + "thinking" indicator.
- **Phase 3 — Strong AI:** Advanced + Expert (2/3-ply + pruning), self-play benchmark harness, performance tuning. *(Prerequisite for training — the coach needs a strong evaluator.)*
- **Phase 4 — Polish:** animations, hit/bear-off effects, pip count, hint, persistence/resume, settings, themes, sound/haptics.
- **Phase 5 — Training:** the `Analyzer` (§6.5), Coach mode, Review mode, position trainer + auto-mined puzzles, analysis-log persistence and the improvement-tracking screen. Optional truncated rollouts for review-grade equity.
- **Phase 6 — Optional:** doubling cube, match play + Crawford, game history + stats, NN evaluation upgrade (which also sharpens the coach).

A clean, shippable, satisfying app exists at the **end of Phase 4**; the full coaching experience lands at the **end of Phase 5**.

---

## 13. Suggested package layout

```
com.lunde.backgammon
├── core/      // BoardState, Dice, Move, MoveGenerator, Rules, DiceRoller   (pure Kotlin)
├── ai/        // AiPlayer, Evaluator, ExpectimaxSearch, Difficulty configs,
│              // Analyzer + Rollout (the trainer's brain)                    (pure Kotlin)
├── data/      // GameStateRepository, SettingsRepository,
│              // AnalysisLogRepository (Room), puzzle library
├── ui/
│   ├── board/    // BoardCanvas, checker/dice/point drawing, coach markers, animations
│   ├── screens/  // Home, Game, Trainer (puzzles + progress), Settings
│   └── theme/    // colours, board themes
└── viewmodel/    // GameViewModel, TrainerViewModel
```

---

## 14. Open decisions for you

These don't block Phase 0–1; decide before the phase that needs them:

1. **Doubling cube & match play** — include now or leave as Phase 6? *(Recommendation: Phase 6.)*
2. **Number of difficulty tiers** — 4 as specced, or fewer/more?
3. **Orientation** — portrait-only (simpler) or also landscape?
4. **NN evaluation upgrade** — scope the Python self-play training path separately? It sharpens both the opponent and the coach. *(Your wheelhouse; clean tie-in.)*
5. **Coach take-backs** — default on or off in Coach mode? (On = gentler practice; off = more game-realistic.)
6. **Puzzles** — ship a curated starter library, rely purely on auto-mined puzzles, or both? *(Recommendation: both — a dozen hand-picked classics plus the auto-miner.)*
7. **Error-rate metric** — happy to report it in the engine's own units (honest, self-relative), or do you want me to look into approximating a normalised PR-style figure later?
8. **App name & package id** — used `com.lunde.backgammon` as a placeholder.
9. **Board themes** — happy with the teal + walnut defaults, or want a specific palette?
