# Phase 3 — Strong AI: Design Spec

**Status:** Approved design — revised after multi-lens spec review (3 blockers + 9 majors + minors addressed). Ready for plan-writing.
**Date:** 2026-06-12
**Builds on:** Phase 0 (`:core` rules engine, merged), Phase 1 (`:app` playable board, merged), Phase 2 (`:ai` module — `AiPlayer`, `Evaluator`/`Features`, `ShotTable`, `MoveSearch` 1-ply greedy, `Difficulty` Beginner/Intermediate, `HeuristicAiPlayer`; merged). Product spec: `docs/backgammon-app-spec.md` §6 (AI design), §6.4 (tiers), §8 (doubling cube — *Phase 6*), §11 (self-play harness), §12 (phase plan: *Phase 3 — Strong AI*).
**Companion:** `docs/superpowers/specs/2026-06-12-phase6-doubling-cube-design.md` (cube design, build deferred to Phase 6) — this spec builds only the `winProbability` groundwork it needs.

> **Terminology note.** Throughout this spec, **"eval score"** means the evaluator's *unbounded, pip-scaled* linear output (`Evaluator.evaluate`). This is **not** the backgammon-domain "cubeless equity" (∈ [−1, +1] points); true cubeless equity (`2·winProb − 1`, gammonless) is a downstream concern of the coach/cube. Earlier drafts called the eval-score "equity" — that term is now reserved for the domain quantity.

## 1. Goal

Make the AI genuinely strong and prove it. Replace the 1-ply greedy search with a depth-parameterised **expectimax** (chance nodes over the 21 distinct rolls) with **top-K move pruning** and an **eval budget**, and add two new tiers: **Advanced** (2-ply) and **Expert** (3-ply, tuned weights). Add a JVM **self-play harness** that *proves* the tiers are ordered (Expert > Advanced > Intermediate > Beginner). Ship a pure **win-probability** helper (`WinProbability.fromEquity`) as doubling-cube groundwork, with a documented provisional constant; its self-play calibration is deferred to the phase that first consumes it.

## 2. Scope

**In scope (Phase 3):**
- `:ai` search: a single depth-parameterised negamax-expectimax (`Expectimax.bestMove`), with top-K pruning and a per-call eval budget. **Subsumes and deletes `MoveSearch`** (depth 0 == today's greedy argmax).
- `:ai` tiers: `Difficulty` gains `searchDepth` + `topK`; add **Advanced** and **Expert**. Expert uses a re-tuned `Weights.FULL_TUNED`.
- `:ai` eval — **blocking-aware shot counting (committed)**: `ShotTable`/`Features.blotPenalty` must discount combination shots whose intermediate landing point is blocked. This is *not* optional — the top-K pruning key is the static eval, and the current blocking-blind table systematically under-ranks priming/holding plays (§4.3); a strong search on a biased key is worse, not better.
- `:ai` win-probability: `WinProbability.fromEquity` (logistic squash) with a **provisional `K`**. Pure function of the eval score; no calibration in Phase 3.
- `:ai` test harness: a `selfPlay` driver (parallel to core's `playGame`), a fast deterministic **tier-ordering** change-detector, and a `@Tag("benchmark")` harness whose **pairwise ordering assertion is the release-gate proof**.

**Explicitly deferred (do NOT build now):**
- **Self-play calibration of `K`** → the phase that first consumes win-prob (Phase 5 coach / Phase 6 cube). It will reuse this harness. Phase 3 ships a provisional `K` (§4.7).
- **Bear-off-efficiency / wastage eval term** → a small follow-up after the search lands (it does not gate pruning quality the way blocking-aware shots does).
- **Doubling cube** (state, AI double/take, UI, persistence), match play + Crawford → **Phase 6** (designed in the companion doc; the only Phase-3 output it consumes is `fromEquity`).
- **Coaching / analysis / rollouts / puzzles** → Phase 5 (deliberately reuse the `selfPlay` harness + `fromEquity`).
- **Automated weight tuning, transposition tables, NN eval** → future (§9). Phase 3 weights are hand-tuned, validated by the harness.
- **Persistence/resume, settings, animations** → Phase 4.
- **Any `AiPlayer` interface change**, and the unrelated `:app` `GAME_OVER` display fix (`GameUiState.isAiTurn`) — both out of scope here.

## 3. Architecture

Module graph unchanged; all Phase-3 code is pure-Kotlin and JVM-testable:

```
:app   (Compose, ViewModel)   depends on -> :core, :ai     (UNCHANGED in Phase 3)
:ai    (AiPlayer, Evaluator,  depends on -> :core
        Expectimax, WinProbability, Difficulty)
:core  (rules engine)         no deps                       (UNCHANGED in Phase 3)
```

- **Phase 3 is an `:ai`-only change.** `:core` and `:app` are untouched.
- **Public surface of `:ai`** stays `AiPlayer`, `HeuristicAiPlayer`, `Difficulty`. New `Expectimax`, `WinProbability`, `NodeBudget` join `Evaluator`/`Features`/`Weights`/`ShotTable` as `internal` (test-visible via the in-module test source set). `MoveSearch` is removed.
- `:ai` stays **coroutine-free**; threading remains the VM's job.

## 4. The `:ai` module

### 4.1 `AiPlayer` (unchanged)

```kotlin
interface AiPlayer { fun chooseMove(state: BoardState, dice: Dice, legal: List<Move>): Move }
```

Still pure + synchronous; the VM runs it off-main. Expert's deeper search costs more wall-clock, bounded by the eval budget (§4.4) and surfaced via the existing "AI thinking…" state.

### 4.2 `Expectimax` (the search)

Replaces `MoveSearch`. A single recursive negamax-form expectimax.

```kotlin
internal object Expectimax {
    /** Best full turn for [state].toMove given the known [dice]. [legal] is the canonical
     *  non-empty MoveGenerator.legalMoves(state, dice). depth 0 == greedy 1-ply argmax.
     *  Pre: state is NOT game-over (the VM never calls chooseMove on a finished game). */
    fun bestMove(
        state: BoardState, dice: Dice, legal: List<Move>,
        weights: Weights, depth: Int, topK: Int,
        budget: NodeBudget = NodeBudget(MAX_EVALS),   // default for tests; one fresh budget per real call
    ): Move
}
```

**Perspective convention (negamax).** `value(state, depth)` returns the eval score **from the perspective of `state.toMove`**. Because `MoveGenerator.apply` flips the mover, the parent **negates** each child's value. Single perspective rule everywhere — the standard guard against the sign-flip-makes-the-AI-lose bug (tested, §8).

```
value(state, depth, weights, topK, budget):
  if Scoring.isGameOver(state):
      # The winner just MOVED, so apply() flipped the side: state.toMove is now the LOSER.
      # terminalEquity returns the magnitude for the side that just moved (the winner);
      # the leading minus re-expresses it from state.toMove's (losing) perspective.
      return -terminalEquity(state)
  if depth == 0 or budget.exhausted:
      return Evaluator.evaluate(state, state.toMove, weights)
  ev = 0.0
  for (roll, w) in DISTINCT_ROLLS:                       # 21 entries: 15 non-doubles w=2/36, 6 doubles w=1/36
      if budget.exhausted: return Evaluator.evaluate(state, state.toMove, weights)
      rollLegal = MoveGenerator.legalMoves(state, roll)  # NOT the root's `legal` — regenerated per sampled roll
      best = if rollLegal.isEmpty():
                 -value(MoveGenerator.pass(state), depth - 1, weights, topK, budget)   # forced pass flips toMove
             else:
                 budget.spend(rollLegal.size)            # charge for the static evals we are about to do (§4.4)
                 rollLegal.prunedTo(topK, by = staticEval desc)
                         .maxOf { m -> -value(MoveGenerator.apply(state, m), depth - 1, weights, topK, budget) }
      ev += w * best
  return ev

terminalEquity(state) = WIN_CONSTANT * Scoring.winnerAndValue(state).second   # multiplier ∈ {1,2,3}; WIN_CONSTANT >> any heuristic eval

bestMove(state, dice, legal, weights, depth, topK, budget):
  # The root's `legal` is the caller's known-roll set, consumed ONLY here; every deeper node regenerates rollLegal.
  budget.spend(legal.size)
  return legal.prunedTo(topK, by = staticEval desc)
              .maxByOrNull { m -> -value(MoveGenerator.apply(state, m), depth, weights, topK, budget) }!!
              # maxByOrNull returns the FIRST maximal element → same first-max tie-break as the old MoveSearch.
              # Determinism comes from the stable iteration order of the pruned list (Kotlin sortedByDescending
              # is stable), never from float-equality of scores.
```

- **`staticEval(m)`** = `Evaluator.evaluate(MoveGenerator.apply(state, m), state.toMove, weights)` — the cheap 1-ply score, used both to rank for pruning and as the depth-0 / budget-exhausted leaf value.
- **Ply mapping** (matches the GNU Backgammon / eXtreme Gammon **"n-ply"** convention, where n counts the mover's own decision as ply 1): `depth=0` → evaluate after our known-roll move = **1-ply** (Intermediate; identical to today's `MoveSearch`). `depth=1` → + opponent chance node & reply = **2-ply** (Advanced). `depth=2` → + our subsequent roll & reply = **3-ply** (Expert). So Expert is a true 3-ply, directly comparable to published 3-ply strength.
- **`DISTINCT_ROLLS`** is a precomputed `List<Pair<Dice, Double>>`: 15 non-doubles at `2.0/36.0`, 6 doubles at `1.0/36.0`. Weights are stored as exact `count/36.0`; the final `ev` is used as-is (no renormalisation — argmax is scale-invariant, and tests compare with an epsilon, §8).
- **`WIN_CONSTANT`** is a tunable constant far above the heuristic eval range so a forced win/loss dominates. `winnerAndValue(state).first` is `state.toMove.opponent` at a terminal node (the winner just moved); only `.second` (the 1/2/3 multiplier) is read.

### 4.3 Top-K pruning

At each decision node, rank candidate moves by `staticEval` and recurse into only the top `topK` (≈8). Pruning is **exact at the leaf-adjacent level** (there the depth-0 value *is* the ranking key) and an approximation deeper — the accepted speed/strength trade (product-spec §6.2). **Pruning quality depends on the ranking key being unbiased**, which is exactly why blocking-aware shots (§2, §4.6) is a committed Phase-3 deliverable: the legacy blocking-blind `ShotTable` over-counts hits behind a prime, so it under-ranks the correct priming/holding play, and top-K would discard the very move the deep search should keep. `topK` is per-tier; depth-0 tiers ignore it (§4.5).

### 4.4 `NodeBudget` (per-call eval budget)

```kotlin
internal class NodeBudget(private val max: Int) {     // a class, not an object — every :ai object is stateless
    private var spent = 0
    fun spend(n: Int) { spent += n }
    val exhausted: Boolean get() = spent >= max
}
```

- **Counts the dominant cost: candidate static-evaluations** (each `apply`+`evaluate`), charged via `spend(legal.size)` before a node ranks its candidates — *not* chance-node count. This is what actually bounds wall-clock, because a single bushy doubles position can fan out to dozens of legal turns inside one node. `exhausted` is also checked at function entry and inside the per-roll loop, so the search bails to a static eval mid-expansion rather than blowing the bound.
- **Fresh instance per `bestMove` call** (the constructor default exists only so tests can inject a tiny budget). It is **mutable and not thread-safe** — fine, because the VM runs exactly one search at a time on `Dispatchers.Default`; it is never shared across calls.
- **`MAX_EVALS`** is tuned so Expert meets a wall-clock target of **≈ p95 ≤ 1.0 s on a 2020-class mid-range phone**. The §8 perf smoke asserts the *eval count* (stable), not raw wall-clock (JVM-noisy); the 1.0 s figure is the device-side target the constant is set against.

### 4.5 `Difficulty` + `HeuristicAiPlayer`

```kotlin
enum class Difficulty(
    internal val weights: Weights,
    internal val noise: Double,
    internal val searchDepth: Int,   // chance-node depth; 0 == 1-ply
    internal val topK: Int,          // candidates kept per decision node
) {
    BEGINNER    (Weights.SIMPLIFIED, noise = 0.25, searchDepth = 0, topK = Int.MAX_VALUE), // no pruning; unreached at depth 0
    INTERMEDIATE(Weights.FULL,       noise = 0.0,  searchDepth = 0, topK = Int.MAX_VALUE),
    ADVANCED    (Weights.FULL,       noise = 0.0,  searchDepth = 1, topK = 8),
    EXPERT      (Weights.FULL_TUNED, noise = 0.0,  searchDepth = 2, topK = 8),
}
```

- Fields stay `internal` (matching the Phase-2 `weights`/`noise` treatment): `:app` selects a `Difficulty` constant and passes it opaquely; only `:ai` reads the fields.
- Depth-0 tiers use `topK = Int.MAX_VALUE` (a harmless sentinel: "no pruning") rather than `0`, which would prune to nothing if the `depth==0` guard ever regressed.
- `HeuristicAiPlayer` keeps its noise path unchanged (Beginner still samples plausibly-weak from the simplified top-3); the "best" path routes through `Expectimax.bestMove(..., difficulty.searchDepth, difficulty.topK)`. Depth 0 reduces to the existing greedy argmax, so **Beginner/Intermediate behaviour is byte-for-byte unchanged** (verified against the kept Phase-2 tests).

### 4.6 Eval: blocking-aware shots (committed)

Refine `Features.blotPenalty` / `ShotTable` so a combination (indirect) shot is counted **only if its intermediate landing point is not blocked** by a made point (≥2) of the blot's owner. Direct shots (1–6) are unaffected. Rationale and why it's committed rather than optional: it is the ranking key for top-K pruning (§4.3); a biased key compounds at every node of the deeper search. Validated by a directional unit test (§8) and by self-play: if blocking-aware Advanced does **not** beat blocking-blind Advanced by ≥ a small margin over the §6.3 seed set, the term is reverted (documented in the plan). `Weights.FULL_TUNED` (Expert) is a hand re-tuning of `FULL` plus any weight the refinement introduces. *(Bear-off-efficiency wastage is deferred — §2.)*

### 4.7 `WinProbability` (cube groundwork — provisional, uncalibrated)

```kotlin
internal object WinProbability {
    // Provisional, NOT yet self-play-calibrated. Calibrated against a named reference weight set
    // (Weights.FULL) in the phase that first consumes win-prob (Phase 5/6). See §6.3 / §9.
    const val K: Double = 0.1
    /** Single-win probability for the side the [equity] (eval score) is measured from. */
    fun fromEquity(equity: Double): Double = 1.0 / (1.0 + exp(-K * equity))   // kotlin.math.exp
}
```

- The evaluator is **zero-sum on a fixed state** (`evaluate(s, WHITE) == -evaluate(s, BLACK)`), so `fromEquity(0) == 0.5` and the function is symmetric (`fromEquity(x) + fromEquity(-x) == 1.0`). These hold for **any `K > 0`** — they pin the function *shape*, not its calibration.
- **Why `K` is provisional, not fit now (B2):** a single baked `K` is scale-dependent on the weight vector (so it differs across tiers), and an honest fit needs a fixed turn-cycle sampling convention (on-roll vs off-roll), per-phase buckets (race/holding/backgame break a single global `K`), and a calibration-error assertion. None of that pays off in Phase 3, where **nothing in shipping code consumes win-prob**. We ship the API + provisional constant now; the consuming phase calibrates (reusing the §6 harness) and is the first place the extra rigour earns its keep.
- **What it models:** single-win probability only — no gammon/backgammon rates. The cube companion doc owns gammon estimation.
- The state-taking convenience (`winProbability(state, perspective, weights) = fromEquity(evaluate(...))`) is **not** added in Phase 3 — it has no caller yet; it lands in the consuming phase.

## 5. `:core` / `:app` integration

**None.** Phase 3 changes only `:ai`. One note for the consuming side (a Phase-4 `:app` follow-up, not Phase 3): perceived "AI thinking…" latency = search time + `AiTurnDriver.paceMs`. To avoid Expert's longer search *stacking* on the fixed pace, the driver should overlap them (`delay(max(0, paceMs − searchElapsed))`) rather than add a full `paceMs` after the search. Tracked as a Phase-4 polish item.

## 6. Self-play harness (`:ai` test source set)

### 6.1 `selfPlay` driver

```kotlin
enum class Outcome { WHITE_WIN, BLACK_WIN, TIMEOUT }
data class GameResult(val outcome: Outcome, val value: Int, val turns: Int)   // value = 1/2/3, 0 on TIMEOUT

fun selfPlay(white: AiPlayer, black: AiPlayer, roller: DiceRoller, maxTurns: Int = 2000): GameResult
```

Plays one full game: roll → `legalMoves` → `pass` if empty else `player.chooseMove(...)` → `apply`, until `Scoring.isGameOver` (→ `WHITE_WIN`/`BLACK_WIN` + `winnerAndValue`) **or** `maxTurns` (→ `TIMEOUT`, `value = 0`). Mirrors core's `GameDriver.kt`/`playGame` (kept as `SelfPlay.kt`/`selfPlay`; it is a test util, not a `*Test` class).

- **`TIMEOUT` is a first-class outcome (M3):** a non-terminating game (e.g. two strong bots in a mutual-holding standoff under a low `maxTurns`) never fabricates a winner. Callers **exclude** `TIMEOUT` from win-rate denominators and assert the timeout count is 0 over their chosen seeds/`maxTurns` (a non-zero count means the seeds or `maxTurns` are wrong, not that a tier lost).
- **Seed-derivation contract (M5):** `selfPlay` consumes only the `roller`. Callers construct each player with its **own** independent RNG and the roller from one game seed via fixed salts, e.g. `SeededDiceRoller(gameSeed)`, `HeuristicAiPlayer(diff, Random(gameSeed xor WHITE_SALT))`, `Random(gameSeed xor BLACK_SALT)` (salts are committed constants; the `xor 0x5DEECE66D…`-style decorrelation Phase-2's `HeuristicAiPlayerTest` already uses). A determinism test asserts identical seeds → identical `GameResult` across two runs.

### 6.2 Tier-ordering change-detector (fast, per-commit)

A deterministic JVM test over a **fixed, procedurally-generated** seed array (seeds `0 until N`, documented — not hand-picked), alternating colours each game to cancel first-move advantage. Because Advanced/Expert are deterministic and Beginner is seeded, each pairing is a fixed number; the test **pins the exact win count** per pairing (e.g. "Expert beats Advanced 63/100 on seeds 0..99") as a **regression baseline / change-detector**, not a `>= threshold`. It states the minimum `N` below which it is not credited as proof. `TIMEOUT` games are excluded and asserted to be 0. This guards against silent strength regressions; it is **not** the ordering proof.

### 6.3 Benchmark — the ordering proof (heavy, `@Tag("benchmark")`)

A larger harness (e.g. 200–500 games per pairing, **independent dice per game**) gated behind `@Tag("benchmark")` / a system property. It **asserts** full pairwise ordering `Expert > Advanced > Intermediate > Beginner` by a real margin and records the win-rate table. This run is a **release gate** (run at merge, table committed into the plan/PR) — §8 "Done" requires it to *pass*, not merely produce output. This same harness is the future home of `K` calibration (deferred, §4.7) and Phase-5 puzzle-mining; neither is built now.

## 7. Doubling cube — out of scope (see companion doc)

The cube is **designed but not built** — its design lives in `docs/superpowers/specs/2026-06-12-phase6-doubling-cube-design.md` (Phase 6). The only constraint Phase 3 must honour for it: **`WinProbability.fromEquity` returns a single-win probability in [0, 1], measured from the side the eval score is taken from; gammon/backgammon rates are not modelled here.** Everything else (cube state/owner, double/take/drop thresholds, gammon estimation, cubeful-vs-cubeless take points, UI, persistence) is the companion doc's concern.

## 8. Testing

**`:ai` (JVM):**
- **Depth-0 == greedy:** `Expectimax.bestMove(..., depth=0)` returns the same move as the old 1-ply argmax across a battery of positions (pins Beginner/Intermediate-unchanged; replaces the `MoveSearch` test).
- **Returned move is legal (property):** over a battery (bar-entry, forced single-die, all-blocked, doubles) and every tier, `bestMove`'s result is `in legal` — an illegal return crashes `:app`.
- **Negamax perspective / terminal sign (the B3 guard):** (a) a 1-ply position where exactly one root move bears off the 15th checker — that move **must** be chosen; (b) a 2-ply position where some opponent roll wins — that line **must** score as a large loss, not a gain; (c) the kept zero-sum identity `evaluate(s, WHITE) == -evaluate(s, BLACK)`.
- **Chance-node weighting vs brute force:** a hand-checked tiny position's expectimax value equals an **independent** brute-force expansion over all **6×6 ordered** rolls (enumerate the 36 directly — do *not* re-expand `DISTINCT_ROLLS`, or it's a tautology), compared with an epsilon (`abs(a−b) < 1e-9`). Also assert `DISTINCT_ROLLS` has 21 entries, non-doubles `2/36`, doubles `1/36`.
- **Forced pass in search:** a position where some opponent rolls have no legal move recurses via `pass` and matches the brute-force value.
- **Top-K leaf-exactness:** at `depth=1`, pruning to `topK` yields the same chosen move as no pruning.
- **Eval budget:** with a tiny `MAX_EVALS`, a deep search terminates, returns a legal move, never exceeds the eval count, and the chosen root move equals the **argmax of static eval over the top-K** (proves graceful degradation to greedy, not to an arbitrary move); with a large budget the result equals the unbudgeted search.
- **Win-probability:** `fromEquity(0) == 0.5`; strictly monotonic in equity; symmetric (`fromEquity(x) + fromEquity(-x) == 1.0`). (Calibration quality is explicitly *not* tested here — `K` is provisional, §4.7.)
- **Blocking-aware shots:** a position where a combination shot's intermediate point is blocked scores a **lower** blot penalty than the open-path version; direct shots unchanged.

**Harness:**
- **Tier-ordering change-detector (§6.2):** deterministic exact win-count baseline over the fixed seed set; `TIMEOUT` count == 0; runtime within the unit-test budget.
- **Determinism (§6.1):** identical seeds → identical `GameResult` twice.
- **Benchmark (§6.3):** `@Tag("benchmark")` — asserts full pairwise ordering; not per-commit, but a documented release-gate command.

**Performance:**
- **Expert eval-budget smoke:** on a **bushy doubles** mid-game position (worst-case fan-out), `EXPERT.chooseMove` respects `MAX_EVALS` and returns within a generous soft wall-clock bound; the eval-count cap — not raw wall-clock — is the hard assertion.

**Done =** all `:ai` unit tests green (new search/win-prob/blocking-aware suite + kept Phase-2 tests); the tier-ordering change-detector passes deterministically with zero timeouts; the **benchmark's pairwise-ordering assertion passes** and its win-rate table is recorded at merge; the Expert perf smoke holds the eval budget. `K` ships as the documented provisional constant (no calibration assertion in Phase 3).

## 9. Risks / open points

- **3-ply cost on a phone.** Expert is the expensive tier. Mitigation: `MAX_EVALS` bounds the dominant cost (static-eval count), the `noContact` race short-circuit collapses deep race nodes to pip-only, and the perf smoke pins the budget against a bushy doubles position. If still slow, lower `MAX_EVALS` or `topK` before reducing depth.
- **Pruning bias.** Top-K by static eval can miss a move that only looks good after lookahead (accepted, product-spec §6.2). The *systematic* component (the blocking-blind shot bias) is removed by the committed §4.6 refinement; residual random misses are measured by the benchmark.
- **Provisional `K`.** Win-prob is uncalibrated in Phase 3 and must not be trusted as an absolute probability yet. The consuming phase calibrates against a named reference weight set, with per-phase buckets and a fixed sampling convention; any weights re-tune obligates re-calibration. Documented so no consumer ships on the provisional value.
- **Harness validity.** The fast detector is a change-detector over a fixed procedural seed set; the tagged benchmark (independent dice, large N) is the authority. `TIMEOUT` games are excluded everywhere, so a long standoff can't masquerade as a loss for the stronger tier.
- **Latency stacking.** Expert search + fixed `paceMs` can stack into a long perceived wait; the §5 note schedules the overlap fix as Phase-4 polish.

## 10. File plan (for the implementation plan to expand)

```
ai/src/main/kotlin/dk/rlunde/backgammon/ai/
├── Expectimax.kt        // NEW: negamax value(), DISTINCT_ROLLS, internal class NodeBudget, top-K, MAX_EVALS  [internal]
│                        //      DELETE MoveSearch.kt (depth 0 == old greedy)
├── WinProbability.kt    // NEW: fromEquity + provisional const K (NO calibration, NO state wrapper)            [internal]
├── Difficulty.kt        // + searchDepth, topK (Int.MAX_VALUE sentinel for depth-0 tiers); add ADVANCED, EXPERT
├── Weights.kt           // + FULL_TUNED (Expert); + any weight the blocking-aware refinement needs
├── Features.kt          // blocking-aware blotPenalty (COMMITTED)
├── ShotTable.kt         // blocking-aware lookup support (COMMITTED)
└── HeuristicAiPlayer.kt // route "best" path through Expectimax.bestMove(searchDepth, topK)
ai/src/test/kotlin/dk/rlunde/backgammon/ai/
├── ExpectimaxTest.kt     // NEW: depth-0==greedy, move∈legal, terminal-sign, brute-force eq, pass, pruning, budget
├── WinProbabilityTest.kt // NEW: midpoint / monotonic / symmetric (shape only)
├── SelfPlay.kt           // NEW: selfPlay driver + Outcome/GameResult (test util; mirrors core GameDriver.kt)
├── TierOrderingTest.kt   // NEW: deterministic exact-count change-detector (fixed seeds 0..N), zero TIMEOUT
├── BenchmarkTest.kt      // NEW: @Tag("benchmark") — ASSERTS pairwise ordering; records win-rate table
└── (MoveSearchTest.kt folded into ExpectimaxTest; FeaturesTest/ShotTableTest gain the blocking-aware cases)
docs/superpowers/specs/
└── 2026-06-12-phase6-doubling-cube-design.md  // NEW companion: full cube design (build = Phase 6)
```

## 11. Workflow

Brainstorming → **this spec** → multi-lens spec review (done; revised) → implementation plan (writing-plans) → subagent-driven-development → finishing-a-development-branch. Build via `./gradlew :ai:test` (JAVA_HOME = Android Studio JBR per project setup).
