# Phase 5 — Training Mode (move analyzer / coach) — Design

> **Status:** design (revised after multi-lens spec review). Follows Phase 3
> (strong AI). Reuses Phase 3 primitives (`Expectimax`, `Evaluator`,
> `WinProbability`) — see `2026-06-12-phase3-strong-ai-design.md`. The
> doubling-cube design (`2026-06-12-phase6-doubling-cube-design.md`) is
> independent.

> **Terminology note.** "eval score" = the evaluator's unbounded, pip-scaled
> linear output (`Evaluator.evaluate`). "search value" = the value
> `Expectimax` returns for a candidate at the analysis depth, **expressed from
> the perspective of the side that is on move at the analysed position** (the
> human) — see §3.5. "win%" = `WinProbability.fromEquity(score)`, a logistic
> squash with a **provisional, uncalibrated** `K`. These are distinct; the
> design depends on the distinction (§3.2) and on the sign convention (§3.5).
>
> **Not "equity".** Per Phase 3, "equity" is reserved for the backgammon
> domain quantity ∈ [−1, +1]. This phase's headline `evalLoss` is an
> **eval-score gap**, *not* the cubeless/cubeful equity loss GNU Backgammon /
> XG report (§3.3). The win% line (§3.3) is the closest cross-tool-comparable
> number, and is itself approximate.

## 1. Goal

A non-intrusive **Training mode**: an opt-in toggle that grades *the human
player's* moves so a learner can see how good each move was and *why* —
attributed to the same positional features the AI uses. No ML/training loop;
this is post-hoc analysis built on the existing evaluator and search.

## 2. Scope

**In scope**
- A `training: Boolean` game setting (off by default), **vs-computer only**.
- **Post-move feedback:** after each human move, a quality **marker**
  (Best/Good/Inaccuracy/Mistake/Blunder), tappable for detail.
- **On-demand analysis:** a button that analyses the *current* (pre-move)
  position — best move + factors, doubling as a hint by explicit request.
- A **feature breakdown** explaining best-vs-played positionally.
- Headline **band** + secondary **win% drop** (flagged approximate).

**Non-goals (explicitly deferred)**
- Live hints shown *before* you move automatically (the §4.5 button is
  on-demand only).
- Post-game review timeline / per-move history list → later.
- Rollouts, **gammon/backgammon-aware equity**, puzzles → later. Rankings and
  win% are **single-win only**; in gammon-rich positions (blitzes, deep
  backgames, prime-vs-prime) they can diverge from true equity (§3.3).
- Self-play calibration of `K` and of band thresholds → tuning follow-up
  (ships provisional; §3.3, §6).
- Analysing the computer's moves; **hot-seat training** (no AI seat to define
  "your moves" against — §4.1).
- Persistence of analyses across app restarts.

## 3. The analysis model

### 3.1 Public API (`:ai`)

`MoveAnalyzer` is the one new public entry point. Its **result types are also
public** (`MoveAnalysis`, `AnalyzedPlay`, `FeatureDelta`, `Band`, `Feature`)
because they appear on the public surface; only the engine internals
(`Evaluator`, `Expectimax`, `WinProbability`, `Features`) stay `internal`.

```kotlin
object MoveAnalyzer {                          // public (the one exported entry point)
    /**
     * Analyse [playedMove] against all [legal] plays from [state] under [dice].
     * Deterministic (no RNG). [playedMove] is matched to a legal play by the
     * resulting board (§3.6), so tap-order differences are tolerated.
     */
    fun analyze(state: BoardState, dice: Dice, playedMove: Move, legal: List<Move>): MoveAnalysis
}
```

```kotlin
data class MoveAnalysis(
    val band: Band,
    val playedRank: Int,        // 1-based competition rank (ties share a rank, §3.4)
    val tiedForBest: Boolean,   // true when playedRank == 1 by a tie
    val totalCandidates: Int,
    val evalLoss: Double,       // search-value gap: best − played (>= 0); eval-score points
    val winProbDrop: Double?,   // approximate (provisional K); null when suppressed (§3.7)
    val terminal: Boolean,      // played move ends the game (§3.7)
    val best: AnalyzedPlay,
    val played: AnalyzedPlay,
    val featureDeltas: List<FeatureDelta>,  // best vs played, sorted by |delta| desc; may be empty (§3.7)
    val forced: Boolean,        // totalCandidates == 1
)
data class AnalyzedPlay(val move: Move, val score: Double)   // score = human-perspective search value (§3.5)
data class FeatureDelta(val feature: Feature, val best: Double, val played: Double) {
    val delta: Double get() = best - played
}
enum class Band { BEST, GOOD, INACCURACY, MISTAKE, BLUNDER }
// Band labels are chess-style by product choice. Backgammon-conventional
// equivalents for reference: GOOD≈ok, INACCURACY≈Doubtful, MISTAKE≈Error,
// BLUNDER≈Blunder.
```

`rankMoves` (§3.4) returns `List<AnalyzedPlay>` directly — there is no separate
`ScoredMove` type.

### 3.2 Two scores, by design

- **Verdict** (`best`, `evalLoss`, `band`, `winProbDrop`): from the **2-ply
  search** ranking (§3.4) — the trustworthy "how much worse," accounting for
  the opponent's replies.
- **Explanation** (`featureDeltas`): a **static (depth-0) attribution** of the
  two post-move positions — "you left a blot; best made the 5-point."

These need **not** reconcile to the unit (a move can be best for lookahead
reasons static factors miss). The UI frames `featureDeltas` as *"why,
positionally — the dominant factors,"* never as a line-item proof summing to
`evalLoss`. Making everything depth-0 so they reconcile was rejected: it would
weaken "best" to greedy 1-ply.

### 3.3 Banding & win%

`band` derives from `(playedRank, evalLoss)` via thresholds in **one constants
block**, marked **PROVISIONAL** (tuning follow-up, §6). BEST is keyed on the
**integer rank**, never on a `Double == 0.0` test (floating accumulation makes
a co-optimal move's loss a tiny non-zero):

| Band | rule (provisional) |
|------|--------------------|
| BEST | `playedRank == 1` (incl. ties for best) |
| GOOD | rank > 1 and `evalLoss ≤ 0.5` |
| INACCURACY | `evalLoss ≤ 2.0` |
| MISTAKE | `evalLoss ≤ 5.0` |
| BLUNDER | `evalLoss > 5.0` |

Thresholds are `Double` consts (`GOOD_MAX`, `INACCURACY_MAX`, `MISTAKE_MAX`)
banded by `evalLoss <= …`.

`winProbDrop = WinProbability.fromEquity(best.score) −
WinProbability.fromEquity(played.score)`, both human-perspective (§3.5). Two
honesty caveats, surfaced in the UI:
1. **Uncalibrated:** `K` is provisional **and** was provisioned against the
   *static-eval* scale, but here it squashes a *depth-1 search value* — so
   `winProbDrop` is doubly approximate until §6 calibration (which must fit `K`
   against this same depth-1/`FULL` reference, §3.4).
2. **Single-win only:** no gammon/backgammon equity — see §2 non-goals.

`evalLoss` is an **eval-score gap, not equity loss** (terminology note); the
UI must not present it with units that imply cross-tool equity.

### 3.4 Engine changes (`:ai`, additive)

**(a) `Evaluator.breakdown()` — per-feature attribution.**

```kotlin
fun breakdown(state: BoardState, perspective: Player, weights: Weights): List<FeatureContribution>
data class FeatureContribution(val feature: Feature, val value: Double)  // weight already applied
enum class Feature { PIP, OFF, BAR, BACK_CHECKER, HOME_POINT, KEY_POINT, PRIME, ANCHOR, ADVANCED_ANCHOR, BLOT }
```

- A **shared private term-builder** returns `List<FeatureContribution>` **one
  per `Weights` field, in field-declaration order**; `evaluate()` becomes
  `terms(...).sumOf { it.value }`. There is **no second summation path**. The
  no-contact fast-path returns only the `PIP`/`OFF` entries (genuinely all
  that is scored).
- `Feature` constants correspond **1:1 and in-order** to `Weights` fields. A
  `:ai` test asserts this correspondence (count + order) so a future weight
  cannot be added without a matching `Feature`. Naming tracks the fields:
  `KEY_POINT` (the `fivePoint` weight via `keyPointsMade` scores **both the
  5-point and bar-point**; UI label "Key points (5/bar)"), `ADVANCED_ANCHOR`
  (the `advancedAnchor` field — no abbreviation).

**(b) `Expectimax.rankMoves()` — all root candidates scored, for analysis.**

```kotlin
fun rankMoves(state: BoardState, dice: Dice, legal: List<Move>,
              weights: Weights, depth: Int, budget: NodeBudget): List<AnalyzedPlay>  // sorted best-first
```

- **Human-perspective score (§3.5):** each play's score is
  `-value(apply(state, play), depth-stepped, ...)` — the root negation, so
  positive = good for `state.toMove`. `AnalyzedPlay` allocation is **root-only**
  (one per legal play), never inside the recursive `value()` node loop.
- **No root pruning** — every legal play gets a real search value, so the
  ranking and `playedRank` are exact. `topK` pruning still applies at deeper
  nodes.
- **Budget, all-or-nothing:** analysis passes its **own** `NodeBudget` sized to
  the candidate set (`legal.size` × per-candidate depth-1 cost), **not** the
  shared 200k playing budget. `rankMoves` must **never mix depth-1 and
  fallback depth-0 scores** in one ranking; if the analysis budget would be
  exceeded it is raised (analysis is off-main-thread and one-shot — §4.2), so
  every candidate is scored at the same depth. (Cost is therefore O(`legal.size`),
  **not** ≈ one `bestMove` call; on doubles this is many× a `bestMove`, which
  is acceptable off-main-thread.)

**(c) `bestMove()` unchanged for the playing AI.** `bestMove` **retains its
root `topK` pruning** and the shared playing `NodeBudget`. It is **not**
re-expressed over the unpruned analysis `rankMoves`; doing so would change the
AI's root candidate set and **invalidate the Phase-3 `TierOrderingTest`
pinned baselines**. A §6 parity test asserts that, *at equal depth and with
root pruning applied*, the top of the ranking equals `bestMove` — the two
share the inner `value()`/negation logic but keep distinct root policies.

`MoveAnalyzer.analyze` then: `rankMoves` at **depth 1 (2-ply, "Advanced")**
with `Weights.FULL`, no noise → locates `playedMove` by resulting board (§3.6)
→ computes `playedRank`/`tiedForBest`/`evalLoss`/`band`/`winProbDrop` →
computes `breakdown(bestPost, perspective = human, FULL)` and
`breakdown(playedPost, perspective = human, FULL)` → zips into `featureDeltas`
sorted by `|delta|`. The depth-1/`FULL`/no-noise reference is **the**
calibration target for §6.

### 3.5 Perspective & sign (single source of correctness)

`Expectimax.value(s, …)` returns the eval **from `s.toMove`'s perspective**;
`apply()` flips the mover, so `value(apply(state, play))` is the *opponent's*
view. The root therefore **negates exactly once**: an `AnalyzedPlay.score` is
`-value(apply(state, play), …)`, i.e. positive = good for the human who is on
move at `state`. `best.score ≥ played.score` always; `evalLoss ≥ 0`. The
static `breakdown` is called with `perspective = human` (the side that just
moved) for **both** post-states, so the explanation and the verdict share the
human sign convention. A §6 test asserts `best.score ≥ played.score` and that
a planted blunder yields a strictly positive `evalLoss`.

### 3.6 Matching the played move

`playedMove` is matched to a `legal` play by **resulting board state**
(`apply(state, playedMove) == apply(state, candidate)`), not raw
`List<SubMove>` equality — because `GameController.commit()` assembles the
played `Move` in the human's physical tap order, which need not equal
`MoveGenerator.legalMoves`' canonical sub-move order. If no legal play matches
(should be impossible), `analyze` fails fast (`require`).

### 3.7 Terminal & extreme-value handling

- **Game-ending played move** (post-move terminal): `terminal = true`,
  `featureDeltas` empty, `winProbDrop = null`. The winning move ranks 1 →
  BEST; a non-winning play that missed the win is shown qualitatively
  ("missed the win"), **not** as a raw `evalLoss` near `WIN_CONSTANT`.
- **`WIN_CONSTANT`-scale scores** generally (forced win/loss in the search):
  when `|best.score|` or `|played.score|` is within the `WIN_CONSTANT` band,
  suppress the numeric `evalLoss`/`winProbDrop` in the UI and show a
  qualitative label. `winProbDrop` is `null` in that case.

## 4. App integration (`:app`)

The app layering is `GameViewModel` (Android `ViewModel`; owns coroutines +
`StateFlow`) → `GameController` (pure state machine; owns board/dice/staged) →
`AiTurnDriver` (runs one AI turn off-main). Analysis orchestration lives in
`GameViewModel`; analysis **types never enter the pure `GameController`**.

### 4.1 Activation
- Add `training: Boolean` to `GameConfig` (default `false`).
- `SetupScreen`: a "Training" chip group (Off/On) shown **only when
  `opponent == Opponent.COMPUTER`** (alongside Difficulty, which is already
  gated the same way). **Not offered for hot-seat** (no AI seat to define
  "your moves" against).

### 4.2 Orchestration (`GameViewModel`)
- `GameController.commit()` is extended to expose the just-committed turn and
  its pre-move context — e.g. a `lastCommitted: CommittedTurn?`
  (`data class CommittedTurn(val preBoard: BoardState, val dice: Dice, val move: Move)`).
  `GameViewModel` reads it, recomputes `legal =
  MoveGenerator.legalMoves(preBoard, dice)`, and calls `MoveAnalyzer.analyze`.
- Runs in a dedicated `analysisJob` in `viewModelScope` via
  `withContext(Dispatchers.Default)` (mirroring `AiTurnDriver`), **independent
  of `aiJob`**. `MoveAnalyzer.analyze` itself stays a **plain non-suspend**
  pure function.
- **Only the human seat is analysed:** skip the AI seat (`aiSide`) and forced
  passes.
- **Staleness via an epoch token:** each request carries a monotonically
  increasing turn epoch; on completion the result is published only if the
  epoch is still current. The prior `analysisJob` is **cancelled** on the next
  human commit, on `undo`, on reset, and on `onNewGame()` (where `aiJob` is
  already cancelled). The latest `MoveAnalysis?` is held as a `GameViewModel`
  field and merged into the emitted state in `publish()` via
  `controller.uiState.copy(...)` (the same pattern used for `aiThinking`),
  not computed inside `GameController`.

### 4.3 Per-move marker
A small colored badge in the existing turn/dice indicator area (no new board
real estate), colored by `band` (green→red). `forced` shows a neutral
"Forced" chip rather than "Best". Tapping opens the detail sheet. The badge
persists until the next human move replaces it; cleared on undo/reset.

### 4.4 Detail sheet (bottom sheet)
Same content from the marker tap or the on-demand button:
- Header: band · `evalLoss` (eval-score points) · `~win% drop*` (omitted when
  `winProbDrop == null`).
- "Your move ranked N of M" ("tied for 1st" when `tiedForBest`; omitted when
  `forced`).
- Best play and your play in **standard backgammon notation** including hits
  (`13/11*`), bar entry (`bar/20`), bear-off (`6/off`), and chaining
  (`13/11/5`) — a `:app` formatting concern (§6 covers a hit-notation test).
  A small **"2-ply"** depth tag near the best play makes the strength claim
  honest (eval-based opinion, not a rollout). "Show on board" reuses the
  board's existing move-highlight rendering.
- "Why (positional factors)": the **top 3** `featureDeltas` with sign and
  label.
- Footnote: "* win% is approximate (uncalibrated, single-win only)".

### 4.5 On-demand "Analyse" button
- On the game screen, enabled on the human's turn; disabled ("No legal moves")
  on a forced pass.
- Analyses the **current pre-move** position: shows the **best move + its
  factors only** (no band/comparison — there is no played move yet). The one
  place the trainer reveals the answer, by explicit request.

## 5. Edge cases
- **Forced pass:** no analysis, no marker; on-demand disabled.
- **Single legal play:** `forced = true`, rank 1/1, loss 0; UI shows "Forced".
- **Tie for best:** competition ranking (count of strictly-better plays + 1;
  ties share rank); `tiedForBest` true when rank 1 by a tie; band BEST.
- **Game-ending / `WIN_CONSTANT`-scale:** §3.7 (no breakdown, no numeric
  win%, qualitative label).
- **Race / no-contact:** breakdown contains only PIP/OFF.
- **Tap-order vs canonical order:** matched by resulting board (§3.6).
- **Staleness (undo/reset/new game mid-analysis):** epoch token + job cancel
  (§4.2) — no marker bound to a vanished position.

## 6. Testing

**Acceptance goal (phase done when):** the four `:ai` analyzer test groups +
the `:app` `GameViewModel` orchestration tests are green, and all provisional
constants (band thresholds, `K`) are **pinned as change-detectors** (like
`TierOrderingTest`), not asserted "correct" — calibration is out of scope by
design.

`:ai` (pure, deterministic — fast unit tests):
- `breakdown()` **sums to** `evaluate()` across a corpus that **includes
  race/no-contact positions** (the fast-path is where drift is likeliest);
  no-contact yields only PIP/OFF.
- `Feature` ↔ `Weights` correspondence: count and order match (a new weight
  forces a new `Feature`).
- `rankMoves`: scores are **human-perspective** (`best.score ≥ played.score`;
  planted blunder → `evalLoss > 0`); list sorted best-first; **all** legal
  plays present (no root pruning); `playedRank` exact; a **large doubles
  move-set** yields all-same-depth scores (no depth-0 fallback mixed in).
- Parity: at equal depth **with root pruning**, ranking top == `bestMove`;
  `bestMove` behaviour and the Phase-3 baselines are unchanged.
- `MoveAnalyzer`: top move → BEST/rank 1/loss 0; planted weak move → worse
  band, positive `evalLoss`, correctly-signed `featureDeltas`; **tie** → shared
  rank + `tiedForBest`; **single move** → `forced`; **game-ending move**
  (reuse a near-win fixture) → `terminal`, empty `featureDeltas`,
  `winProbDrop == null`; **tap-order-permuted** played move ranks correctly
  (§3.6); a move **not in legal** fails fast.
- `winProbDrop`: **sign/direction only** (≥ 0 when `evalLoss > 0`, `null` in
  terminal/extreme cases) — **no magnitude assert** against the uncalibrated K.
- Banding: table-driven over `(playedRank, evalLoss)` → `Band`, including
  `(1, 0)→BEST`, `(2, 0.0)→GOOD` (decided & pinned), and each numeric edge
  (0.5/2.0/5.0). Pinned as a change-detector.

`:app`:
- `GameConfig.training` plumbs through setup → game state; chip shown only for
  `COMPUTER`.
- `GameViewModel` (with an **injected `TestDispatcher`/scope** so the race is
  deterministic): analysis fires **only** on human moves with training on;
  skips AI moves and passes; **supersede** — launch analysis for move A,
  advance to move B, assert A's result is dropped and B's stored; **undo/reset
  mid-analysis** yields no stale marker.
- Hit-notation: a hitting play renders with `*`.
- No instrumented UI tests (consistent with the existing suite).

**Tuning follow-up (not blocking):** calibrate band thresholds and
`WinProbability.K` against the self-play harness, fit to the depth-1/`FULL`
reference (§3.4); both ship provisional.

## 7. Files

New (`:ai`): `MoveAnalyzer.kt` (+ co-located public result records
`MoveAnalysis`, `AnalyzedPlay`, `FeatureDelta`, `Band` — no `:ai` file hosts
loose value types today, so they live with their owning type);
`Feature`/`FeatureContribution`/`breakdown` added to `Evaluator.kt` (the
evaluator's output vocabulary) drawing on `Features.kt`; `rankMoves` added to
`Expectimax.kt`.
New (`:app`): training marker + detail-sheet composables; `Analyse` button.
Modified (`:app`): `GameConfig`, `SetupScreen`, **`GameController`** (expose
`lastCommitted: CommittedTurn`), `GameViewModel` (analysis orchestration +
`analysisJob` + epoch), `GameUiState` (carry `MoveAnalysis?`), `GameScreen`.

## 8. Implementation sequencing

Build engine-first, then UI (two stages under this one spec): **(1)** `:ai` —
`breakdown`, `rankMoves`, `MoveAnalyzer`, fully unit-tested with no UI; **(2)**
`:app` — `GameController`/`GameViewModel` seam + marker, then the bottom sheet
+ board highlight + on-demand button. Stage 1 is independently verifiable and
de-risks the sign/budget correctness before any UI work.
