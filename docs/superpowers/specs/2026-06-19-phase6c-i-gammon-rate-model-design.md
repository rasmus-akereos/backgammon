# Phase 6c-i — Gammon-Rate Model (calibrated): Design Spec

**Status:** Design — ready for plan-writing.
**Slice:** Phase 6, slice 6c-1 (gammon-rate model). First of two 6c sub-slices; 6c-2 (cubeful cube) builds on it.
**Date:** 2026-06-19
**Related:**
- `docs/superpowers/specs/2026-06-16-phase6a-equity-foundation-design.md` §4.4, §10 (the gammon model + the "fit was degenerate, deferred" note this closes)
- `docs/superpowers/specs/2026-06-12-phase6-doubling-cube-design.md` §4 (gammon awareness prerequisite for cube math)

---

## 1. Goal

Make the gammon/backgammon rates in `EquityModel` **trustworthy** by fixing the degenerate 6a fit. The trainer's G%/BG% (and cubeless equity) become calibrated against self-play instead of hand-set guesses. This is the prerequisite for gammon-aware cube decisions (6c-2).

**Done looks like:** the committed gammon model's predicted gammon/backgammon frequency tracks realized self-play frequency within an agreed bar (and beats the hand-set baseline); the trainer shows honest, self-play-calibrated G%/BG%; race positions degenerate sensibly (gammon → 0 as the loser bears off).

## 2. Background — why 6a's fit was degenerate

6a's `GammonModel` is a feature-based logistic on `{borneOff, pip, backContact}`. The automated fit was degenerate for two reasons (6a §10): (a) **unscaled `pip`** (range ~0–167) with a fitted weight saturated the logistic to ≈0 everywhere; (b) labeling **every ply** with the game's final outcome is noisy — an opening position carries no gammon signal. 6a shipped hand-set coefficients as a stopgap. 6c-1 fixes both root causes and recalibrates.

## 3. Decisions (brainstorming)

- **Keep the feature-based logistic** (not a bucketed lookup or rollouts) — the degeneracy was a conditioning bug, not a model-form flaw; the logistic keeps a tiny committed artifact and stays O(1) on the trainer's per-move path.
- **Standardize features** with fixed divisors (no stored mean/std).
- **Label from decisive positions** only (the bear-off/contact-resolved window), where the features actually discriminate gammon outcomes.

## 4. Feature standardization (`GammonModel`)

Normalize each feature to ~[0,1] with fixed divisors before the logistic, both at fit time (harness) and at runtime (`loseRates`):
- `borneOff / 15.0`, `pip / 167.0`, `backContact / 15.0`.

`loseRates(f)` keeps its structure: hard-gate to `(0,0)` when `borneOff > 0` (a gammon is then impossible); else `gammon = logistic(GAMMON_COEFFS · scaled(f))`, `bg = min(logistic(BG_COEFFS · scaled(f)), gammon)`. `GAMMON_COEFFS`/`BG_COEFFS` are `{intercept, wBorneOff, wPip, wBackContact}`, fit on the scaled features and committed as constants. The scaling is applied in one place (a private helper) used by both the runtime model and the harness, so they can't drift.

## 5. Decisive-position labeling (`selfPlayTrajectory` / `EquityCalibrationTest`)

Collect gammon-model training rows only from the **decidable window** of each self-play game: positions where **either side has borne off ≥ 1 checker, OR the position is a no-contact race** (`Features.noContact`). Outside that window the gammon outcome is undetermined and the row is noise. Each row = the **eventual loser's** scaled features at that position → `(wasGammoned: value ≥ 2, wasBackgammoned: value == 3)`. (The win-prob `(eval, didWin)` rows continue to be collected from all plies as in 6a — only the *gammon* rows are gated.)

The exact predicate is the starting rule; the plan may refine it if diagnostics show poor coverage (e.g., add a pip-difference threshold).

## 6. Recalibration (gated harness)

`EquityCalibrationTest` (gated `-Dbackgammon.calibrate=true`, reference `ADVANCED`/`Weights.FULL`, fixed seed — as 6a) is updated to:
1. scale gammon-row features (§4) and gate them on the decisive predicate (§5);
2. refit `GAMMON_COEFFS`/`BG_COEFFS` (the win-prob `K` fit and its values are **unchanged** from 6a);
3. print diagnostics: predicted vs realized gammon/bg frequency, bucketed by borne-off count and pip band.

The developer copies the fitted coefficients into `GammonModel`. `EquityModel.distribution` assembly is **unchanged** (it already composes win-prob × the conditional rates).

## 7. Testing

- **Unit (`GammonModelTest`)** — keep the construction-guaranteed invariants as hard tests: `bg ≤ gammon`; `borneOff > 0 → (0,0)`; rates in `[0,1]`. Re-express the directional cases (e.g. "more back-contact → more bg") against the **committed fitted** coefficients (a fitted sign may differ from the 6a hand values; verify the fitted model's actual monotonicity and assert that).
- **Calibration guard (non-gated, fast)** — new `GammonCalibrationGuardTest`: on a small fixed-seed self-play sample, the committed gammon model's mean predicted gammon rate (over decisive-window loser positions) tracks the realized gammon frequency within a tolerance, and beats the 6a hand-set baseline. Race degeneracy: as the loser's `borneOff` rises, predicted gammon rate falls.
- **`EquityModelTest`** stays green unchanged (sum-to-1, perspective symmetry — independent of the coefficient values).
- **Success bar**: the gammon calibration tolerance is fixed *now* as a criterion ("beat the hand-set baseline; predicted-vs-realized within the bar") and the exact numeric bar is pinned after the first calibration run (as 6a did for win-prob) — a deliberate post-run step, recorded in the spec, not an open TBD.

## 8. Trainer (`:app`)

No logic change — the detail sheet reads `EquityModel.positionEquity`, so recalibrated rates flow through. Update only the footnote in `GameScreen.kt`'s analysis sheet: from "gammon rates are model estimates (no rollouts)" to note they're now self-play-calibrated.

## 9. Files (anticipated)

- `ai/.../GammonModel.kt` — feature scaling + recommitted `GAMMON_COEFFS`/`BG_COEFFS` (edit).
- `ai/src/test/.../SelfPlay.kt` (`selfPlayTrajectory`) — emit scaled features + a decisive-window flag per sample (edit).
- `ai/src/test/.../EquityCalibrationTest.kt` — gate gammon rows on the decisive predicate; refit + diagnostics (edit).
- `ai/src/test/.../GammonModelTest.kt` — adjust directional cases to fitted coefficients (edit).
- `ai/src/test/.../GammonCalibrationGuardTest.kt` — new non-gated guard.
- `app/.../ui/screens/GameScreen.kt` — footnote wording (edit).
- `docs/.../2026-06-16-phase6a-equity-foundation-design.md` §10 — note 6c-1 closes the deferred gammon fit (edit).

## 10. Out of scope (→ 6c-2)

Cubeful equity, recube vig, accurate take point, "too good to double". `CubePolicy` keeps the gammonless single-win thresholds (`0.70`/`0.21`) until 6c-2 consumes this trustworthy distribution. No win-prob `K` change; no `EquityModel` assembly change; no rollouts; no checker-move-selection change.
