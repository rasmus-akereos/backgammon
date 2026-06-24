# Phase 6c-ii — Cubeful Cube Decisions (Janowski): Design Spec

**Status:** Design — ready for plan-writing.
**Slice:** Phase 6, slice 6c-2 (cubeful cube). Builds on 6c-1 (calibrated gammon-rate model).
**Date:** 2026-06-24
**Related:**
- `docs/superpowers/specs/2026-06-19-phase6c-i-gammon-rate-model-design.md` (the trustworthy `OutcomeDistribution` this slice consumes)
- `docs/superpowers/specs/2026-06-16-phase6a-equity-foundation-design.md` §4 (`OutcomeDistribution`, `cubelessEquity`)
- `docs/superpowers/specs/2026-06-18-phase6b-ii-ai-cube-design.md` (the gammonless `CubePolicy` this slice replaces)
- Rick Janowski, *Take-Points in Money Games* (1993); GNU Backgammon manual, "Cubeful equities".

---

## 1. Goal

Replace the gammonless heuristic cube thresholds (`CubePolicy.shouldDouble(0.70)` / `shouldTake(0.21)`) with a proper **cubeful, gammon-aware** money-game cube model. From the calibrated 6-way `OutcomeDistribution`, derive cubeful equity, an accurate take point, recube vig, and the "too good to double" decision via Janowski's closed-form model. Upgrade the AI's offer/response decisions and surface a cube-action recommendation in the trainer.

**Done looks like:** the AI doubles inside a sensible window (~68–79% gammonless contact), takes/passes at the gammon-adjusted take point (~20–25%), recognises "too good to double" positions and plays on, and never doubles a perfectly dead/closed position incorrectly. The trainer shows the cube verdict + take/cash points when the cube is live.

## 2. Background

6b-ii shipped a deliberately crude gammonless cube: a fixed `0.70` double trigger and `0.21` take threshold on the single-win probability, ignoring gammon mass and cube ownership. 6c-1 made `OutcomeDistribution` (and therefore `W`/`L` — average win/loss values) trustworthy. 6c-2 spends that: the cube decisions now read the full distribution and apply Janowski's cubeful model, which is the standard tractable (O(1), no rollouts) way to turn a cubeless distribution into cube actions.

This is a **money game** (cube cap 64, `CubeState` in `:app`). Match equity, Crawford, and match-score-aware cube are out of scope (§10).

## 3. The Janowski model

All equities are **normalised to the current cube value = 1** (decisions are scale-invariant; the live cube value only scales the displayed absolute points, never the verdict).

### 3.1 Inputs from the distribution

For the player whose cube decision we evaluate, from their `OutcomeDistribution d`:
- `p = d.winProb` — cubeless win probability.
- `W = (d.winSingle + 2·d.winGammon + 3·d.winBackgammon) / d.winProb` — mean cubeless **win** value, ∈ [1,3].
- `L = (d.loseSingle + 2·d.loseGammon + 3·d.loseBackgammon) / d.loseProb` — mean cubeless **loss** value, ∈ [1,3].

**Guards:** if `d.winProb < 1e-6`, set `W = 1.0`; if `d.loseProb < 1e-6`, set `L = 1.0` (avoids divide-by-zero at certain win/loss; the value is irrelevant there because the corresponding probability mass is ~0).

Let `S = W + L + 0.5·x`.

### 3.2 Cube efficiency `x`

`x ∈ [0,1]` is the cube-life index: `x=0` is a dead cube (cubeful ≡ cubeless), `x=1` a perfectly live cube. Two hand-set constants, selected by the same phase split `WinProbability` uses (`GamePhases.of(state)`):

```kotlin
internal val CUBE_EFFICIENCY: Map<GamePhase, Double> = mapOf(
    GamePhase.CONTACT to 0.68,
    GamePhase.RACE    to 0.60,
)
```

These are starting values from cube-theory literature, not calibrated (cube efficiency calibrates poorly from cubeless self-play — see 6c-1 §3 rationale). They are pinned behind a guard test (§8) that asserts the resulting take point lands in the known ~20–25% band. Tuning `x` later is a one-line constant change.

### 3.3 Cubeful equity by cube ownership

Janowski's closed forms (verbatim, normalised cube = 1):

| Cube owner | Cubeful equity |
|---|---|
| Player on roll owns it | `E_own = p·S − L` |
| Opponent owns it | `E_opp = p·S − L − 0.5·x` |
| Centred (either may double) | `E_center = (4/(4−x))·(p·S − L − 0.25·x)` |

**Sanity (locked as test anchors):** at `x=0` all three reduce to cubeless `E₀ = p·(W+L) − L = p·W − (1−p)·L`. ✓

### 3.4 Cube points

- **Take point** (receiver's min win prob to take): `TP = (L − 0.5) / S`.
- **Cash point** (doubler cashes — opponent should pass): `CP = (L + 0.5 + 0.5·x) / S`.

**Anchors (gammonless, `W=L=1`):**

| | dead (x=0) | live (x=1) |
|---|---|---|
| `TP` | 25.0% | 20.0% |
| `CP` | 75.0% | 80.0% |

The `0.5·x` term in `S` and the `0.5·x` offset in `E_opp` **are** the recube vig: owning the cube lowers your take point (25%→20%) and widens the doubler's window.

## 4. Decision rules

### 4.1 Response (receiver faces a double)

Computed from the **receiver's own** distribution (`W_r`, `L_r`, `p_r`, `S_r`):

> **TAKE** iff `p_r ≥ TP_r`, else **DROP**.

Derivation: taking makes the receiver the cube owner at stake 2, equity `2·E_own(p_r) = 2(p_r·S_r − L_r)`; dropping is `−1`. Take iff `2(p_r·S_r − L_r) ≥ −1` ⇔ `p_r ≥ (L_r − 0.5)/S_r = TP_r`. ✓

### 4.2 Offer (player on roll may double; cube centred or owned by them)

Let `E_hold = E_center(p)` if the cube is centred, else `E_own(p)` (cube already owned by the doubler). Opponent takes iff `p ≤ CP`. Then:

- **If `p > CP`** (opponent would pass):
  - `TOO_GOOD` if `E_hold > 1.0` (playing on is worth more than the +1 you'd cash).
  - else `DOUBLE` (cash the point).
- **If `p ≤ CP`** (opponent would take): `DOUBLE` iff `2·E_opp(p) > E_hold`, else `NO_DOUBLE`.

The double trigger `2·E_opp(p) > E_hold` is the **minimum double point**; solving it gives:
- centred cube: `p > ( L + x(3−x)/(2(2−x)) ) / S`
- owned cube: `p > (L + x) / S`

**Anchors (gammonless):** at `x=1` both equal `CP = 80%` (a perfectly live cube is never doubled before the cash point). At `x=0.68`: centred window ≈ **[68.3%, 78.6%]**, owned (redouble) window ≈ **[71.8%, 78.6%]** — the redouble window is correctly narrower (cube owner can wait longer). These are locked as test anchors.

> Implementation note: implement the offer as the three explicit equity comparisons above (`E_hold`, `2·E_opp`, `+1`), **not** by hard-coding the solved DP formula. The solved formulas exist only to anchor the tests. This keeps the code a direct transcription of the model and robust to future `x` changes.

## 5. Module design (`:ai`)

Two new pure objects, plus facade methods. `:ai` must not reference `:app` types (`CubeState` lives in `:app`), so cube ownership crosses the boundary as a plain enum.

### 5.1 `CubeEquity` (new, internal)

```kotlin
internal enum class CubeOwner { ME, OPPONENT, CENTERED }

/** Janowski cubeful quantities for the player on roll, normalised to cube value 1. */
internal data class CubeEquities(
    val p: Double, val w: Double, val l: Double, val x: Double,
    val takePoint: Double, val cashPoint: Double,
    val holdEquity: Double,    // E_center or E_own per current ownership
    val doubleTake: Double,    // 2 · E_opp
    val doublePass: Double,    // = 1.0
)

internal object CubeEquity {
    fun of(d: OutcomeDistribution, phase: GamePhase, owner: CubeOwner): CubeEquities
}
```

`of` computes `W`,`L` (with §3.1 guards), `S`, `TP`, `CP`, `E_own`/`E_opp`/`E_center`, and packs `holdEquity` from `owner`.

### 5.2 `CubeDecision` (new, internal)

```kotlin
enum class OfferVerdict { NO_DOUBLE, DOUBLE, TOO_GOOD }
enum class ResponseVerdict { TAKE, DROP }

internal object CubeDecision {
    /** Offer decision for the player on roll. Requires owner == ME or CENTERED. */
    fun offer(eq: CubeEquities): OfferVerdict
    /** Take/drop for the receiver, evaluated from the RECEIVER's own equities. */
    fun response(eq: CubeEquities): ResponseVerdict   // TAKE iff eq.p >= eq.takePoint
}
```

`offer` implements §4.2 verbatim (compare `holdEquity`, `doubleTake`, `doublePass=1.0`, gated on `p` vs `cashPoint`).

### 5.3 `CubeAdvisor` facade (existing, public)

Add public methods (build the distribution internally via `EquityModel.distribution(state, …, Weights.FULL)`, mirroring the current `winProb`). `iOwnCube`/centred crosses as `CubeOwner` so `:app` passes a plain value, never an `:ai` internal:

```kotlin
object CubeAdvisor {
    fun winProb(state: BoardState, perspective: Player): Double          // unchanged
    fun offerVerdict(state: BoardState, perspective: Player, owner: CubeOwner): OfferVerdict
    fun responseVerdict(state: BoardState, receiver: Player): ResponseVerdict
    /** For the trainer hint: the full equities (public read-only copy). */
    fun equities(state: BoardState, perspective: Player, owner: CubeOwner): CubeEquities
}
```

`CubeOwner` and `CubeEquities` become **public** (they cross to `:app`); `CubeEquity`/`CubeDecision` objects stay internal.

### 5.4 `CubePolicy` — deleted

Remove `CubePolicy.kt` and its two constants; migrate the two call sites (§6).

## 6. Plumbing (`:app`)

Map the `:app` `CubeState` ownership to `CubeOwner` at the call site:
`val owner = when { cube.isCentred -> CubeOwner.CENTERED; cube.owner == side -> CubeOwner.ME; else -> CubeOwner.OPPONENT }`.

### 6.1 `AiTurnDriver` (pre-roll offer)

Current code: `if (CubePolicy.shouldDouble(winProb)) { offerDouble(); … }`. Replace with:

```kotlin
val owner = ownerFor(controller.uiState.cube, aiSide)   // ME or CENTERED here (mayDouble already gated)
val verdict = withContext(dispatcher) { CubeAdvisor.offerVerdict(controller.uiState.board, aiSide, owner) }
if (verdict == OfferVerdict.DOUBLE) { controller.offerDouble(); publish(); return }
// NO_DOUBLE and TOO_GOOD both fall through to roll-and-play (TOO_GOOD = keep playing for the gammon)
```

### 6.2 `GameViewModel` (AI response to a human double)

Current code: `respondDouble(if (CubePolicy.shouldTake(winProb)) TAKE else DROP)`. Replace with:

```kotlin
val verdict = withContext(analysisDispatcher) { CubeAdvisor.responseVerdict(controller.uiState.board, ai) }
controller.respondDouble(if (verdict == ResponseVerdict.TAKE) CubeResponse.TAKE else CubeResponse.DROP)
```

### 6.3 Trainer cube hint (`GameScreen.kt` `AnalysisSheet`)

When training mode is on **and** the cube is live (game not over, `phase` is a normal play/roll phase), show a compact cube line derived from `CubeAdvisor.equities(board, onRoll, owner)`:
- The on-roll player's offer verdict as text: `NO_DOUBLE`→"No double", `DOUBLE`→"Double / take" if `p ≤ CP` else "Double / pass", `TOO_GOOD`→"Too good to double".
- Take point and cash point as percentages (e.g. "TP 22% · CP 79%").
- The cubeful hold equity (`holdEquity`) alongside the existing cubeless equity, labelled so the difference (the cube's value) is visible.

No new analysis plumbing: `AnalysisSheet` already has the board + perspective; this is a few read-only lines reusing `CubeAdvisor`. Gate it on the same `trainingMode` flag the existing on-demand analysis uses.

## 7. Data flow

```
OutcomeDistribution (6c-1)  ──►  CubeEquity.of(dist, phase, owner)  ──►  CubeEquities
                                                                            │
                              ┌─────────────────────────────────────────────┤
                              ▼                                             ▼
                     CubeDecision.offer  ──► OfferVerdict          CubeDecision.response ──► ResponseVerdict
                              │                                             │
        AiTurnDriver (DOUBLE→offer; else play)            GameViewModel (TAKE/DROP)
                              │
                     GameScreen AnalysisSheet (verdict + TP/CP + cubeful equity)
```

## 8. Testing

- **`CubeEquityTest`** (unit, fast):
  - Gammonless anchors: `TP`/`CP` = {25%,75%} at x=0 and {20%,80%} at x=1.
  - All three cubeful equities reduce to `cubelessEquity` at x=0 (drive `x` via a test-only phase or by constructing equities directly).
  - `W,L ∈ [1,3]`; §3.1 guards (winProb≈0 ⇒ W=1; loseProb≈0 ⇒ L=1) return finite values.
  - Monotonicity (verified signs): higher `L` (more loss-gammon risk) **raises** `TP` — gammon danger makes takes harder; higher `W` (more win-gammon upside) **lowers** `CP` — you can cash/double-out sooner.
- **`CubeDecisionTest`** (unit, fast):
  - Response: a clear take (`p > TP`), a clear pass (`p < TP`), a marginal case straddling `TP`.
  - Offer (centred): `NO_DOUBLE` below the window; `DOUBLE` inside [DP, CP]; at `p > CP` with low gammon mass → `DOUBLE` (cash); at `p > CP` with heavy gammon mass (`E_hold > 1`) → `TOO_GOOD`.
  - Offer (owned): redouble window narrower than the centred window at the same `x` (anchor: x=0.68 ⇒ owned DP ≈ 71.8% > centred DP ≈ 68.3%).
- **`CubeCalibrationGuardTest`** (non-gated, fast): across sampled positions the computed take point stays within [0.18, 0.27] for both phase `x` values, and `TOO_GOOD` only fires when `holdEquity > 1.0` (never as a substitute for a normal cash).
- **Plumbing:**
  - `AiTurnDriverTest`: a position inside the double window ⇒ AI offers the cube; a `TOO_GOOD` position ⇒ AI does **not** double and plays on (existing balanced fixtures already avoid spurious doubles — keep them green).
  - `GameViewModel` (existing cube test): AI takes a sound double and drops a hopeless one per `responseVerdict`.

## 9. Files

- `ai/.../CubeEquity.kt` — new: `CubeOwner` (public), `CubeEquities` (public), `CubeEquity` (internal).
- `ai/.../CubeDecision.kt` — new: `OfferVerdict`/`ResponseVerdict` (public), `CubeDecision` (internal).
- `ai/.../CubeAdvisor.kt` — add `offerVerdict`/`responseVerdict`/`equities` (edit).
- `ai/.../CubePolicy.kt` — delete.
- `ai/src/test/.../CubeEquityTest.kt`, `CubeDecisionTest.kt`, `CubeCalibrationGuardTest.kt` — new.
- `app/.../game/AiTurnDriver.kt` — offer-verdict rewrite + `ownerFor` mapping (edit).
- `app/.../viewmodel/GameViewModel.kt` — response-verdict rewrite; drop `CubePolicy` import (edit).
- `app/.../ui/screens/GameScreen.kt` — trainer cube hint in `AnalysisSheet` (edit).
- `app/src/test/.../AiTurnDriverTest.kt` — offer/too-good cases (edit).

## 10. Out of scope (→ later)

- Match equity, Crawford, match-score-aware cube (this is money-only).
- Multi-ply / lookahead cubeful evaluation (stays 0-ply on the static eval; no rollouts).
- Cube-influenced **checker** play (move selection is unchanged).
- Position-adaptive `x` (gnubg-style effective cube life) — we use two phase constants.
- Cube-action history / analysis log.
- Beavers / raccoons.
