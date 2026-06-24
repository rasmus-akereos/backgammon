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

**Done looks like:** the AI doubles inside a sensible window (~67–79% gammonless contact), takes/passes at the gammon-adjusted take point (~20–25%), recognises "too good to double" positions and plays on, and never doubles a perfectly dead/closed position incorrectly. The trainer shows the cube verdict + take/cash points when the cube is live.

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

**Guards:** if `d.winProb < 1e-6`, set `W = 1.0`; if `d.loseProb < 1e-6`, set `L = 1.0` (avoids divide-by-zero at certain win/loss; the value is irrelevant there because the corresponding probability mass is ~0). After computation, **clamp `W` and `L` to `[1.0, 3.0]`** to absorb any rounding in the upstream distribution.

The distribution `d` is always built for the player whose decision we evaluate (the on-roll player for an offer; the **receiver** for a response — see §5.3). `p` is therefore that player's own win probability.

Let `S = W + L + 0.5·x`.

### 3.2 Cube efficiency `x`

`x ∈ [0,1]` is the cube-life index: `x=0` is a dead cube (cubeful ≡ cubeless), `x=1` a perfectly live cube. `x` is high when equity moves in small, smooth increments (you can recube near the optimum) and low when equity gyrates and "blows past" the recube window. Two hand-set constants, selected by the same phase split `WinProbability` uses (`GamePhases.of(state)`):

```kotlin
internal val CUBE_EFFICIENCY: Map<GamePhase, Double> = mapOf(
    GamePhase.CONTACT to 0.65,
    GamePhase.RACE    to 0.75,
)
```

**Direction matters: `RACE > CONTACT`.** A race is the smooth, low-volatility regime where the cube is *most* live/efficient; heavy-contact (shots, blitzes, backgames) swings past the recube window and is *less* efficient. Janowski's commonly-cited contact default is ≈0.65–0.68, with races higher. The §8 take-point guard band (~20–25%) does **not** discriminate the direction (both 0.65 and 0.75 land a gammonless TP in-band), so the direction is asserted here from cube theory and locked by a dedicated §8 assertion that `RACE > CONTACT` and that the race double window sits lower (more aggressive) than the contact one.

These are starting values from cube-theory literature, not calibrated (cube efficiency calibrates poorly from cubeless self-play — see 6c-1 §3 rationale). Tuning `x` later is a one-line constant change. **Lookup is total:** `CubeEquity.of` reads `x` via `CUBE_EFFICIENCY.getValue(phase)` (exhaustive over `GamePhase`'s two values — a miss is a programmer error, matching `WinProbability.K`), and asserts `require(x in 0.0..1.0)` to guard a fat-finger tuning that would otherwise divide-by-zero in `E_center`'s `4/(4−x)`.

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

All cube rules in §4 assume **beavers/raccoons OFF** (the money-game default here). Enabling them would change the receiver's decision region near `p≈0.5` (the beaver option) and shade the doubler away from beaverable positions; out of scope (§10).

### 4.2 Offer (player on roll may double; cube centred or owned by them)

Let `E_hold = E_center(p)` if the cube is centred, else `E_own(p)` (cube already owned by the doubler). Opponent takes iff `p ≤ CP`. Then:

- **If `p > CP`** (opponent would pass):
  - `TOO_GOOD` if `cubelessEquity > 1.0` — playing the gammon out is worth more than the +1 you'd cash. **Use the cubeless equity `E₀ = p·W − (1−p)·L` here, NOT `E_hold`.** `E_center` carries the `4/(4−x)` cube-access multiplier and would fire TOO_GOOD too eagerly; once you decline to double you forgo turning the cube, so the play-on value is the (cube-dormant) cubeless equity. `E₀` is the conservative, textbook "play on for the gammon" threshold and is already available as `OutcomeDistribution.cubelessEquity`.
  - else `DOUBLE` (cash the point).
- **If `p ≤ CP`** (opponent would take): `DOUBLE` iff `2·E_opp(p) > E_hold`, else `NO_DOUBLE`.

**Cash-point caveat (accepted approximation):** `CP` is computed from the *doubler's own* `W`/`L` under Janowski's symmetric-mirror assumption (the receiver's win/loss profile mirrors the doubler's). In skewed-gammon positions (e.g. backgames) the "opponent would take iff `p ≤ CP`" test can disagree with what `responseVerdict` (§4.1) would actually return for that opponent. This only affects the doubler's offer-vs-cash framing, never a take/drop the receiver actually faces; accepted (no rollouts).

The double trigger `2·E_opp(p) > E_hold` is the **minimum double point**; solving it gives:
- centred cube: `p > ( L + x(3−x)/(2(2−x)) ) / S`
- owned cube: `p > (L + x) / S`

**Anchors (gammonless):** at `x=1` both equal `CP = 80%` (a perfectly live cube is never doubled before the cash point; gammonless — with gammons the `x=1` DP and CP separate). At `x=0.65` (contact): centred window ≈ **[67.3%, 78.5%]**, owned (redouble) window ≈ **[71.0%, 78.5%]** — the redouble window is correctly narrower (cube owner can wait longer). At `x=0.75` (race) the windows sit lower/more aggressive. These are locked as test anchors.

**Redoubles are now live.** Because the owned-cube branch fires when `p > (L+x)/S`, after the AI *takes* a double it owns the cube and can legitimately redouble on a later turn (this is intended, and a behavioural change from 6b-ii where the `0.70` trigger rarely re-fired). The redouble chain terminates at the cube cap: `CubeState.mayDouble` returns false at value 64, so the offer path is never reached there (the verdict math itself is scale-invariant and unaffected by the cap; only the displayed `holdEquity` near the cap is mildly optimistic — accepted, display-only).

> Implementation note: implement the offer as the explicit equity comparisons above (`E_hold`, `2·E_opp`, `cubelessEquity`, `+1`), **not** by hard-coding the solved DP formula. The solved formulas exist only to anchor the tests. This keeps the code a direct transcription of the model and robust to future `x` changes.

## 5. Module design (`:ai`)

Two new pure objects, plus facade methods. `:ai` must not reference `:app` types (`CubeState` lives in `:app`), so cube ownership crosses the boundary as a plain enum.

### 5.1 `CubeEquity` (new)

```kotlin
enum class CubeOwner { ME, OPPONENT, CENTERED }   // public: crosses to :app

/** Janowski cubeful quantities for the player on roll, normalised to cube value 1.
 *  Field names follow the OutcomeDistribution convention (descriptive, not single-letter);
 *  §3's p/W/L/x are math aliases used only in the derivation prose. */
data class CubeEquities(                            // public: crosses to :app (like OutcomeDistribution)
    val winProb: Double,            // p
    val meanWin: Double,            // W ∈ [1,3]
    val meanLoss: Double,           // L ∈ [1,3]
    val cubeEfficiency: Double,     // x
    val cubelessEquity: Double,     // E₀ = p·W − (1−p)·L  (drives the TOO_GOOD test, §4.2)
    val takePoint: Double,          // TP
    val cashPoint: Double,          // CP
    val holdEquity: Double,         // E_center or E_own per current ownership
    val doubleTake: Double,         // 2 · E_opp
)
// The cash value (+1) is the literal 1.0, referenced directly in CubeDecision.offer — not a stored field.

internal object CubeEquity {
    fun of(d: OutcomeDistribution, phase: GamePhase, owner: CubeOwner): CubeEquities
}
```

`of` reads `x` via `CUBE_EFFICIENCY.getValue(phase)` (with `require(x in 0.0..1.0)`), computes `W`,`L` (with §3.1 guards + `[1,3]` clamp), `S`, `cubelessEquity`, `TP`, `CP`, `E_own`/`E_opp`/`E_center`, and packs `holdEquity` from `owner` (`CENTERED → E_center`, `ME → E_own`, `OPPONENT → E_opp`).

### 5.2 `CubeDecision` (new, internal)

```kotlin
enum class OfferVerdict { NO_DOUBLE, DOUBLE, TOO_GOOD }   // public: crosses to :app
enum class ResponseVerdict { TAKE, DROP }                 // public: crosses to :app

internal object CubeDecision {
    /** Offer decision (§4.2). Returns NO_DOUBLE when owner == OPPONENT (cannot double a cube you don't own). */
    fun offer(eq: CubeEquities, owner: CubeOwner): OfferVerdict
    /** Take/drop for the receiver (§4.1). Takes no CubeOwner: taking always transfers the cube to the
     *  receiver at stake 2, so the take point (L−0.5)/S is independent of the pre-double owner. */
    fun response(eq: CubeEquities): ResponseVerdict
}
```

`offer` implements §4.2 (compare `holdEquity`, `doubleTake`, `cubelessEquity`, and the literal `1.0`, gated on `winProb` vs `cashPoint`); for `owner == OPPONENT` it short-circuits to `NO_DOUBLE`. `response` returns `TAKE` iff `eq.winProb ≥ eq.takePoint` (rule owned by §4.1; not restated in code comments).

### 5.3 `CubeAdvisor` facade (existing, public)

Add public methods. Each builds the distribution internally via `EquityModel.distribution(state, perspective, Weights.FULL)` — **with the perspective of the player whose decision it is**:

```kotlin
object CubeAdvisor {
    fun winProb(state: BoardState, perspective: Player): Double          // see drift note below
    fun offerVerdict(state: BoardState, perspective: Player, owner: CubeOwner): OfferVerdict   // dist built with perspective = on-roll player
    fun responseVerdict(state: BoardState, receiver: Player): ResponseVerdict                  // dist built with perspective = RECEIVER
    /** For the trainer hint: the full equities (public read-only copy). */
    fun equities(state: BoardState, perspective: Player, owner: CubeOwner): CubeEquities
}
```

**Perspective (M3):** `responseVerdict(state, receiver)` MUST build the distribution with `perspective = receiver` — the take point is the receiver's, and `Evaluator.evaluate` takes an explicit `perspective` independent of `state.toMove` (the 6b-ii perspective hazard). A §8 test asserts `responseVerdict` is invariant to `state.toMove`.

**Win-prob single source (M4):** the kept `winProb` facade currently routes through `WinProbability.fromEquity(Evaluator.evaluate(...))`, while the new methods read `EquityModel.distribution(...).winProb`. These are equal today but nothing pins it. Redefine `winProb(state, p) = EquityModel.distribution(state, p, Weights.FULL).winProb` so there is a single win-prob source, and add a §8 test asserting `equities(s, p, owner).winProb == winProb(s, p)`.

`CubeOwner`, `CubeEquities`, `OfferVerdict`, and `ResponseVerdict` are **public** (they cross to `:app`, consistent with `OutcomeDistribution`/`MoveAnalysis` already crossing as rich types); the `CubeEquity`/`CubeDecision` objects stay internal. `CubeEquities`' diagnostic fields (`meanWin`/`meanLoss`/`cubeEfficiency`/`doubleTake`) are not consumed by `:app` — the trainer hint reads only `winProb`/`cubelessEquity`/`takePoint`/`cashPoint`/`holdEquity` plus the verdict — but are kept on the one public bundle for test/diagnostic use rather than spawning a second type.

### 5.4 `CubePolicy` — deleted

Remove `CubePolicy.kt` and its two constants; migrate the two call sites (§6).

## 6. Plumbing (`:app`)

A single shared helper maps `:app`'s `CubeState` ownership to `:ai`'s `CubeOwner`. It lives in `app/.../game/` beside `CubeState` (both `AiTurnDriver` and the trainer hint use it; defining it once prevents two divergent copies):

```kotlin
fun ownerFor(cube: CubeState, side: Player): CubeOwner = when {
    cube.isCentred    -> CubeOwner.CENTERED
    cube.owner == side -> CubeOwner.ME
    else              -> CubeOwner.OPPONENT
}
```

### 6.1 `AiTurnDriver` (pre-roll offer)

Current code: `if (CubePolicy.shouldDouble(winProb)) { offerDouble(); … }`. Replace with:

```kotlin
val owner = ownerFor(controller.uiState.cube, aiSide)   // ME or CENTERED here (mayDouble already gated)
val verdict = withContext(dispatcher) { CubeAdvisor.offerVerdict(controller.uiState.board, aiSide, owner) }
if (verdict == OfferVerdict.DOUBLE) { controller.offerDouble(); publish(); return }
// NO_DOUBLE and TOO_GOOD both fall through to roll-and-play (TOO_GOOD = keep playing for the gammon)
```

**TOO_GOOD never stalls the game.** It only declines to *offer* the cube — the turn falls through to the normal roll-and-play path, and a too-good position terminates by bearing off as usual. The `if (cube.mayDouble(aiSide))` guard that already wraps this block (and bails on `CUBE_OFFERED`) means `offerVerdict` is only reached when the AI may actually double, so `owner` is `ME` or `CENTERED` here. A §8 `AiTurnDriverTest` asserts a TOO_GOOD position still rolls and applies a move (not merely "does not double").

### 6.2 `GameViewModel` (AI response to a human double)

Current code: `respondDouble(if (CubePolicy.shouldTake(winProb)) TAKE else DROP)`. Replace with:

```kotlin
val verdict = withContext(analysisDispatcher) { CubeAdvisor.responseVerdict(controller.uiState.board, ai) }
controller.respondDouble(if (verdict == ResponseVerdict.TAKE) CubeResponse.TAKE else CubeResponse.DROP)
```

### 6.3 Trainer cube hint (`GameScreen.kt` — new composable)

**This is NOT an edit to `AnalysisSheet`.** `AnalysisSheet(analysis: MoveAnalysis)` is a *post-move* dialog (shown only when `state.analysis != null`) and has no `board`/`cube`/`onRoll` — it cannot host a live-cube hint. Instead add a new sibling composable `CubeHintLine`, rendered inline in the play screen (a sibling to the existing `if (state.training && state.analysis != null)` block), reading from `GameUiState` (which already exposes `board`, `toMove`, `cube`, `phase`, `aiSide`):

```kotlin
@Composable
fun CubeHintLine(board: BoardState, onRoll: Player, owner: CubeOwner) { /* reads CubeAdvisor.equities(board, onRoll, owner) */ }
```

**Gating (call site):** render only when `state.training` **and** `state.phase == Phase.NEED_ROLL` (the pre-roll decision point) **and** `state.cube.mayDouble(onRoll)` (the on-roll side can actually offer). Hide entirely at `CUBE_OFFERED` and `GAME_OVER`, and when the on-roll side cannot double (cube maxed at 64, or owned by the opponent). `onRoll = state.toMove`, `owner = ownerFor(state.cube, state.toMove)` (§6).

**Content** (from `CubeAdvisor.equities(board, onRoll, owner)`):
- Offer verdict as text: `NO_DOUBLE`→"No double", `DOUBLE`→"Double / take" if `winProb ≤ cashPoint` else "Double / pass", `TOO_GOOD`→"Too good to double".
- Take/cash points as percentages (e.g. "TP 22% · CP 79%").
- `holdEquity` (cubeful) alongside `cubelessEquity`, labelled so the cube's value (the difference) is visible.

The verdict→string mapping (including the `winProb ≤ cashPoint` sub-branch) is unit-tested as a pure formatter (§8), mirroring the existing `EquityFormatTest`.

## 7. Data flow

```
OutcomeDistribution (6c-1)  ──►  CubeEquity.of(dist, phase, owner)  ──►  CubeEquities
                                                                            │
                              ┌─────────────────────────────────────────────┤
                              ▼                                             ▼
              CubeDecision.offer(eq, owner) ──► OfferVerdict     CubeDecision.response(eq) ──► ResponseVerdict
                              │                                             │
        AiTurnDriver (DOUBLE→offer; else play)            GameViewModel (TAKE/DROP)
                              │
                     GameScreen CubeHintLine (verdict + TP/CP + cubeful vs cubeless equity)
```

## 8. Testing

- **`CubeEquityTest`** (unit, fast):
  - Gammonless anchors: `TP`/`CP` = {25%,75%} at x=0 and {20%,80%} at x=1.
  - All three cubeful equities reduce to `cubelessEquity` at x=0 (construct equities directly with x=0).
  - `meanWin`,`meanLoss` ∈ [1,3] (incl. the clamp); §3.1 guards (winProb≈0 ⇒ W=1; loseProb≈0 ⇒ L=1) return finite values.
  - `require(x in 0.0..1.0)` holds for both phase constants; `RACE > CONTACT` (M1 — locked so the direction can't silently regress).
  - Monotonicity, pinned to numeric anchors (not direction-only): at x=0.65, `TP(L=1.0)` and `TP(L=1.5)` match independently-recomputed constants to 1e-4, and `TP(L=1.5) > TP(L=1.0)` (more loss-gammon risk **raises** `TP`); likewise `CP` **falls** as `W` rises.
  - Win-prob single source: `equities(s, p, owner).winProb == winProb(s, p)` (M4).
- **`CubeDecisionTest`** (unit, fast):
  - Response: clear take (`p > TP`), clear pass (`p < TP`), marginal case straddling `TP`. Perspective invariance: `responseVerdict(state, receiver)` is unchanged when `state.toMove` is flipped (M3).
  - **Double-window edges pinned (M6b):** gammonless (W=L=1) at x=0.65 — centred `offer(p=0.673)==NO_DOUBLE`, `offer(p=0.675)==DOUBLE`; owned `offer(p=0.709)==NO_DOUBLE`, `offer(p=0.711)==DOUBLE`. This locks both analytic edges and proves the redouble window is genuinely narrower.
  - **TOO_GOOD pinned to its cause (M6a):** a locked gammon-heavy fixture asserts all three facts together — `winProb > cashPoint` ∧ `cubelessEquity > 1.0` ∧ `offer == TOO_GOOD`. A contrasting fixture (`winProb > cashPoint` but `cubelessEquity < 1.0`) asserts `offer == DOUBLE`, proving the branch boundary, not one side. A centred-cube position with only *moderate* gammon mass must **not** fire TOO_GOOD (guards the M2 `4/(4−x)` inflation — would have fired under the old `E_center > 1` test).
  - Offer for `owner == OPPONENT` ⇒ `NO_DOUBLE` (n1).
- **Dead-position verdicts (M6c):** `p≈1.0` (e.g. `winSingle=1.0`) ⇒ offer is `DOUBLE` or `TOO_GOOD`, never `NO_DOUBLE`; `p≈0.0` ⇒ `responseVerdict == DROP` and offer `NO_DOUBLE`. Explicit `p=0.0`/`p=1.0` boundary cases confirm the `cubelessEquity > 1.0` tie-break resolves deterministically (a pure single win sits at `E₀≈1.0` — pin which way it falls).
- **`CubeCalibrationGuardTest`** (non-gated, fast): over the fixed-seed self-play position set used by `GammonCalibrationGuardTest` (n4 — deterministic), assert, for both phase `x` values:
  - **Structural (every position):** `0 < takePoint < cashPoint < 1` — always true by construction (`CP − TP = (1+0.5x)/S > 0`); catches sign/scale/inversion regressions.
  - **Reference band (gammonless subset):** the tight gammonless take-point band `[0.18, 0.27]` holds **only** for near-gammonless positions (`meanWin < 1.05 && meanLoss < 1.05`). Gammon skew legitimately moves the take point — loss-gammon danger raises it (a backgame loser can sit at ~0.32), win-gammon upside lowers it (toward ~0.12) — so the band is asserted only on the subset where both mean values are ≈1, and the subset must be non-empty. (This subsumes the old, incorrect "band holds for all positions" claim.)
  - **Behavioural (every position):** `TOO_GOOD` only fires when `cubelessEquity > 1.0`.
- **`CubeHintFormatTest`** (unit, fast — mirrors `EquityFormatTest`): `DOUBLE`+`winProb≤cashPoint`→"Double / take", `DOUBLE`+`winProb>cashPoint`→"Double / pass", `TOO_GOOD`→"Too good to double", `NO_DOUBLE`→"No double".
- **Plumbing:**
  - `AiTurnDriverTest`: a position inside the double window ⇒ AI offers the cube; a `TOO_GOOD` position ⇒ AI does **not** double **but still rolls and applies a move** (m7); cube at value 64 ⇒ AI does not offer (`mayDouble` guard) and `equities` still returns finite numbers (m6). Existing balanced fixtures stay green.
  - `GameViewModel` (existing cube test): AI takes a sound double and drops a hopeless one per `responseVerdict`.

## 9. Files

- `ai/.../CubeEquity.kt` — new: `CubeOwner` (public), `CubeEquities` (public), `CubeEquity` (internal); `CUBE_EFFICIENCY` map.
- `ai/.../CubeDecision.kt` — new: `OfferVerdict`/`ResponseVerdict` (public), `CubeDecision` (internal).
- `ai/.../CubeAdvisor.kt` — add `offerVerdict`/`responseVerdict`/`equities`; redefine `winProb` via the distribution (edit).
- `ai/.../CubePolicy.kt` — **delete**.
- `ai/src/test/.../CubePolicyTest.kt` — **delete** (orphaned by the `CubePolicy` deletion; otherwise breaks test compile).
- `ai/src/test/.../CubeEquityTest.kt`, `CubeDecisionTest.kt`, `CubeCalibrationGuardTest.kt`, `CubeHintFormatTest.kt` — new.
- `app/.../game/CubeOwnership.kt` (or alongside `CubeState.kt`) — new: `ownerFor(cube, side): CubeOwner` shared helper.
- `app/.../game/AiTurnDriver.kt` — offer-verdict rewrite using `ownerFor` (edit).
- `app/.../viewmodel/GameViewModel.kt` — response-verdict rewrite; drop `CubePolicy` import (edit).
- `app/.../ui/screens/GameScreen.kt` — new `CubeHintLine` composable + gated call site (edit; NOT inside `AnalysisSheet`).
- `app/src/test/.../AiTurnDriverTest.kt` — offer/too-good/cap-64 cases (edit).

## 10. Out of scope (→ later)

- Match equity, Crawford, match-score-aware cube (this is money-only).
- Multi-ply / lookahead cubeful evaluation (stays 0-ply on the static eval; no rollouts).
- Cube-influenced **checker** play (move selection is unchanged).
- Position-adaptive `x` (gnubg-style effective cube life) — we use two phase constants.
- Cube-action history / analysis log.
- Beavers / raccoons.
