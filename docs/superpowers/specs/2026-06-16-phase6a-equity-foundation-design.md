# Phase 6a — Equity Foundation: Design Spec

**Status:** Design — ready for plan-writing.
**Date:** 2026-06-16
**Slice:** First of four in the Phase 6 (doubling cube & match play) decomposition.
**Related:**
- `docs/superpowers/specs/2026-06-12-phase6-doubling-cube-design.md` (Phase 6 stub; this slice implements its §4–§5 equity groundwork)
- `docs/superpowers/specs/2026-06-12-phase3-strong-ai-design.md` §4.7 (`WinProbability.fromEquity`, the hook this calibrates)
- `docs/superpowers/specs/2026-06-14-phase5-trainer-design.md` §4.4–§4.5 (the trainer surfaces this slice extends)
- `docs/backgammon-app-spec.md` §8 (doubling cube — Phase 6)

---

## 0. Phase 6 decomposition (context)

Phase 6 as originally stubbed bundles too much for one spec. It is sliced into four independently shippable pieces, each with its own spec → plan → build:

| Slice | Delivers | Depends on |
|------|----------|-----------|
| **6a — Equity foundation** (this doc) | Calibrated win probability + full 6-outcome distribution + cubeless equity; read-only equity readout in the trainer. Pure `:ai` logic plus a thin `:app` display. | — |
| 6b — Cube mechanics + UI | `CubeState` (value/owner/centred), offer-double → take/drop flow, cube indicator, persistence. Simple win-prob threshold for AI decisions. | 6a |
| 6c — Cubeful cube brain | Cubeful equity, recube vig, accurate take point, "too good", volatility. | 6a, 6b |
| 6d — Match play + Crawford | Play to N, match-equity table, match-adjusted decisions. | 6a–6c |

Money-game-with-cube becomes playable after 6b; cube *skill* arrives with 6c; match play is the final layer.

## 1. Goal

Produce a **trustworthy, cheap, calibrated equity** for any position and surface it (read-only) in the existing trainer. "Equity" here means the **cubeless money equity** in points, derived from a full outcome distribution. This is the foundation every later cube decision compares against; nothing downstream is sound until it exists.

Concretely, this slice fixes the two disclaimers the Phase-3 groundwork left open (recorded verbatim in `WinProbability.kt` and the trainer footnote): the win-prob is **uncalibrated** (`K = 0.1` provisional) and **single-win only — no gammon rates**.

## 2. Scope

**In scope**
- A `GamePhase` classifier (`CONTACT` / `RACE`).
- An `OutcomeDistribution` type and `cubelessEquity`.
- `EquityModel`: position → `OutcomeDistribution`, using committed offline-fitted constants.
- Per-phase calibration of the win-prob logistic; feature-based gammon/backgammon-rate models.
- An **offline, gated** self-play calibration harness that emits fitted constants + diagnostics.
- A read-only equity readout in the trainer detail sheet and on-demand Analyse view.

**Out of scope (explicit — later slices)**
- ❌ `CubeState`, double / take / drop, cubeful equity, recube vig — 6b/6c.
- ❌ Match play, Crawford, match-equity table — 6d.
- ❌ Rollouts of any kind; external reference data / engines.
- ❌ Using equity to **drive** AI move choice. The search still ranks moves by the raw eval score exactly as today; equity is a **derived, displayed** quantity only. (It begins driving decisions in 6b, for the cube — never for checker play in this slice.)

## 3. Estimation approach (decisions)

Settled during brainstorming:

- **Static / feature-based** estimation (not rollouts). Cheap, deterministic, slots into the existing static `Evaluator` / `Features`. Acceptable accuracy cost: gammon rates will be roughest in heavy-contact positions; that is tolerable for a read-only display and is revisited before the cube *trusts* it (6c).
- **Self-play frequencies** are the calibration target — fully self-contained, no external reference data. Accepted risk: equity is internally consistent with our engine's strength, not guaranteed to match published/"true" equity. An external-reference regression set is explicitly deferred (possible 6a follow-up or 6c).
- **Per-phase logistic + feature-based gammon model** (Approach 2 of three considered). Smooth, cheap at runtime (the trainer calls this per move on a phone), honors the "per-phase buckets" intent already noted in the Phase-3 groundwork. A misfitting bucket can later be promoted to a non-parametric lookup without changing the API.

## 4. Engine design (`:ai`)

### 4.1 Game phase

```kotlin
enum class GamePhase { CONTACT, RACE }

internal object GamePhases {
    fun of(state: BoardState): GamePhase =
        if (Features.noContact(state)) GamePhase.RACE else GamePhase.CONTACT
}
```

Start with two buckets only. `BEAROFF` is **deliberately not** split out now; add it only if §6 calibration diagnostics show the `RACE` logistic misfitting late races. (Decision recorded: confirmed during brainstorming.)

### 4.2 Outcome distribution & cubeless equity

```kotlin
/** Full 6-way outcome distribution from one side's perspective. Components are
 *  probabilities summing to 1 (within a small epsilon). */
data class OutcomeDistribution(
    val winSingle: Double, val winGammon: Double, val winBackgammon: Double,
    val loseSingle: Double, val loseGammon: Double, val loseBackgammon: Double,
) {
    val winProb: Double get() = winSingle + winGammon + winBackgammon
    val loseProb: Double get() = loseSingle + loseGammon + loseBackgammon

    /** Cubeless money equity in points, ∈ [−3, +3]. */
    val cubelessEquity: Double get() =
        (winSingle + 2 * winGammon + 3 * winBackgammon) -
        (loseSingle + 2 * loseGammon + 3 * loseBackgammon)
}
```

Perspective convention matches the evaluator (spec §4.4 of phase3): the distribution is taken from the side the eval score is measured from. Swapping perspective mirrors the six components (win↔lose) and negates `cubelessEquity`.

### 4.3 Win probability (per-phase calibration)

`WinProbability.fromEquity` gains a phase parameter; `K` becomes per-phase. Intercept-free, so it stays symmetric about 0.5 at `evalScore = 0`.

```kotlin
internal object WinProbability {
    // Fitted offline (§5). Provisional 0.1 retired.
    private val K: Map<GamePhase, Double> = mapOf(/* CONTACT to …, RACE to … */)
    fun fromEquity(equity: Double, phase: GamePhase): Double =
        1.0 / (1.0 + exp(-K.getValue(phase) * equity))
}
```

Existing callers — the trainer's `winProbDrop` computation — are updated to pass the phase of the relevant position. (This silently improves the trainer's existing win% numbers too.)

### 4.4 Gammon / backgammon rates

Conditional on the win/lose split from §4.3, small **feature-based logistic** functions give:
- `P(gammon | win)`, `P(backgammon | win)`
- `P(gammon | lose)`, `P(backgammon | lose)`

with `P(backgammon | …) ≤ P(gammon | …)` enforced (bg is a stricter gammon), so the six components reconstruct cleanly:
```
winGammon      = winProb  · (P(g|win)  − P(bg|win))
winBackgammon  = winProb  · P(bg|win)
winSingle      = winProb  · (1 − P(g|win))
```
(and symmetrically for losses). Inputs are the features that actually predict a gammon — the **trailing** side's borne-off count and pip count (gammon risk), plus residual contact / checkers on the bar or in the opponent's home board (backgammon risk). Coefficients are fit offline (§5) and committed as constants in `EquityModel`.

### 4.5 `EquityModel`

```kotlin
object EquityModel {
    /** Combines §4.3 win-prob and §4.4 conditional rates into the full distribution. */
    fun distribution(state: BoardState, perspective: Player, weights: Weights): OutcomeDistribution
}
```

It computes the eval score (`Evaluator.evaluate`), classifies the phase, applies the calibrated win-prob, then the conditional gammon/bg models, and assembles the `OutcomeDistribution`. Allocation-light; safe to call per move on the UI path.

## 5. Calibration (offline, gated dev workflow)

Calibration is a **gated dev test** under `ai/src/test` — the same pattern as the existing gated self-play benchmark (`BenchmarkTest` / `SelfPlay`). **No training runs in the app.**

1. **Sample collection.** Play *N* self-play games with a **pinned reference weight set and fixed RNG seed** (both named explicitly in the plan, for reproducibility). At each ply, log a sample: `(phase, evalScore from the mover's perspective, position features for the gammon model)`.
2. **Labelling.** When the game ends, label every sample with that mover's eventual outcome class (win/lose × single/gammon/backgammon), via `Scoring.winnerAndValue`.
3. **Fitting.** Fit `K_phase` by 1-D logistic regression (minimize log-loss) per phase; fit the gammon/bg conditional-rate coefficients on the win- and lose-conditioned subsets.
4. **Output.** Print fitted constants + diagnostics: reliability bins (predicted vs realized), log-loss, and predicted-vs-realized gammon/bg frequencies.

The developer copies the fitted constants into `WinProbability.K` and `EquityModel`. A **fast regression test** then asserts the committed model meets the calibration bar (§7) on a fixed-seed sample, so the constants cannot silently rot.

## 6. App integration (`:app`)

Equity is a property of a **position**, so it reuses the trainer's surfaces with no new board real estate.

- **`GameViewModel`** computes `EquityModel.distribution(...)` for the relevant position and merges it into UI state through the existing `publish()` / `uiState.copy(...)` path — the same mechanism as `analysis` and `aiThinking`, off-main on `Dispatchers.Default`, guarded by the existing epoch / staleness token, cancelled on reset / new-game. **No equity types enter `GameController`** (consistent with the trainer's layering rule).
- **Detail sheet (trainer §4.4):** the uncalibrated single-win `~win% drop` is replaced with the **calibrated** win%, plus a compact distribution line and cubeless equity, e.g. `Win 72% · G 18% · BG 2% · Eq +0.74`.
- **On-demand "Analyse" (trainer §4.5):** the same equity block for the current pre-move position.
- **Footnote** "* win% is approximate (uncalibrated, single-win only)" is **replaced** with an honest, smaller note: win% is self-play-calibrated and gammon rates are model estimates (no rollouts). The "2-ply" depth tag is unchanged.

Strictly **read-only**: no cube, no double button, no change to move selection.

## 7. Testing & success criteria

**Unit (`:ai`)**
- `OutcomeDistribution` components sum to 1 within epsilon; `cubelessEquity` arithmetic correct on hand-worked cases.
- **Perspective symmetry:** swapping perspective mirrors the six components and negates `cubelessEquity`.
- **Monotonicity:** higher eval score ⇒ higher `winProb` within a phase.
- **Race degeneracy:** as the trailing side bears checkers off, gammon/bg rates collapse toward 0 sensibly.
- `P(bg|·) ≤ P(g|·)` invariant holds for all model inputs.

**Calibration regression (gated)**
- Committed model's log-loss is **strictly better** than the `K = 0.1` single-win baseline on a fixed-seed sample.
- Reliability (mean abs gap between predicted and realized, binned) is **below an agreed bar**.
- Predicted gammon/bg frequency matches realized within tolerance.

**On the numeric bars:** the success *criteria* are fixed now (must beat baseline log-loss; calibration error below a bar; gammon frequency within tolerance). The exact numeric thresholds are **finalized after the first calibration run** — achievable accuracy can't be known a priori. The plan will record the run, then pin the numbers; this is a deliberate post-first-run step, not an open TBD.

**App**
- Equity block renders with correct perspective sign; updates on commit; stale-guarded on reset / new-game like the analysis marker.

## 8. Files (anticipated)

- `ai/src/main/kotlin/.../GamePhase.kt` (or fold into `Features`) — phase classifier.
- `ai/src/main/kotlin/.../OutcomeDistribution.kt` — type + `cubelessEquity`.
- `ai/src/main/kotlin/.../EquityModel.kt` — distribution assembly + committed constants.
- `ai/src/main/kotlin/.../WinProbability.kt` — per-phase `K` (edit).
- `ai/src/test/kotlin/.../EquityCalibration*.kt` — gated harness + fast regression test.
- `ai/src/test/kotlin/.../OutcomeDistributionTest.kt`, `EquityModelTest.kt` — unit tests.
- `app/.../viewmodel/GameViewModel.kt` — compute + merge equity (edit).
- `app/.../ui/screens/GameScreen.kt` (detail sheet / Analyse) — equity block + footnote (edit).
- `MoveAnalysis` / `winProbDrop` callers — pass phase (edit).

## 9. Open items for the plan (not blockers)

- Name the exact reference weight set and seed for calibration reproducibility.
- Decide sample count *N* and whether to subsample plies (avoid over-weighting long games).
- Pin the gammon-model feature list precisely (the §4.4 set is the intended starting point).

## 10. Calibration results (2026-06-16)

First calibration run: 400 self-play games, reference `Difficulty.ADVANCED` (`Weights.FULL`, depth 1, deterministic), per-game seed `(g+1)·2654435761`.

**Win probability — calibrated, shipped.** Per-phase logistic slopes fitted by `EquityCalibrationTest`, beating the `K=0.1` baseline on held log-loss:

| phase | fitted K | log-loss | baseline (K=0.1) |
|-------|----------|----------|------------------|
| CONTACT | 0.0378 | 0.628 | 0.902 |
| RACE | 0.0555 | 0.192 | 0.224 |

Committed in `WinProbability.K`. The non-gated `EquityCalibrationGuardTest` pins this against the 50/50 baseline so the constants can't silently rot.

**Gammon/backgammon rates — deferred.** The automated fit was degenerate: the `pip` feature is unscaled (~0–167), so its fitted weight saturated the logistic to ≈0 gammon everywhere, and per-ply labels carry weak gammon signal (every ply inherits the *game's* final outcome). `GammonModel` therefore keeps deliberate hand-set, directionally-correct coefficients (more back-contact → more bg; a borne-off checker → no gammon). Rigorous gammon-rate calibration (standardize features + sample decisive late positions) is deferred to a follow-up; per §4 the cube does not require trustworthy gammon awareness until slice 6c. Cubeless equity in 6a is therefore essentially gammonless (≈ `2·winProb − 1`), which is sound in races and the common case.
