# Phase 6 — Doubling Cube: Design Spec (build deferred)

**Status:** Design only — **not** to be built until Phase 6. Captured now (during Phase 3 brainstorming) so the Phase-3 `WinProbability` groundwork is shaped correctly. Supersedes the cube sketch in product-spec §8.
**Date:** 2026-06-12
**Related:** `docs/backgammon-app-spec.md` §8 (doubling cube & match play — Phase 6), `docs/superpowers/specs/2026-06-12-phase3-strong-ai-design.md` §4.7 (the `fromEquity` output this consumes).

> This document is a **stub design**, deliberately not implementation-ready. It will get its own brainstorm → spec-review → plan when Phase 6 starts. Its job today is to (a) record the decisions made during the Phase-3 discussion and (b) pin the one constraint Phase 3 must satisfy.

## 1. The one Phase-3 constraint

Phase 3 ships `WinProbability.fromEquity(equity): Double` — a **single-win** probability in `[0, 1]`, measured from the side the eval score is taken from, symmetric about 0.5. That is all the cube needs from Phase 3. Everything below is Phase-6 work.

## 2. Cube model

- `value: Int` — current cube value, `1 → 2 → 4 → 8 → …`.
- `owner: Player?` — who may next double; `null` = **centred** (either side may double).
- Lives in a `CubeState`/`MatchState` type (likely `:core` or `game/`), serialized into the resume blob (Phase 4/6 persistence touchpoint).
- The **Crawford rule** and match-length state belong with match play (also Phase 6), tracked alongside the cube.

## 3. AI cube decision

Driven by the **searched** eval score → `WinProbability.fromEquity` (Phase 6 feeds the expectimax root value, not the static eval Phase 3 squashes — same function, better input).

- **Double / redouble:** when win probability enters roughly the **70–80%** market window and the position is not "too good" to cash (i.e. not so strong that playing on for a gammon beats turning the cube). Volatility-sensitive; tunable.
- **Take / drop:** the classic gammonless break-even is the **25% cubeless, dead-cube** drop point (on a 2-cube you risk 2 to gain 2 → 25% wins to break even). **With a live cube the take point is lower — roughly 20–21%** — because the taker owns recube vig. Phase 6 must model this: **do not ship 25% as if it were the cubeful number.** Start from 25% cubeless and adjust downward for recube equity and volatility.
- These are tunable thresholds, refined by self-play and/or rollouts.

## 4. Gammon awareness (prerequisite for trustworthy cube math)

The Phase-3 win-prob is **single-win only**. Correct cube equity near gammonish positions needs gammon/backgammon **rates** (P(win gammon), P(lose gammon), …), because they shift both the doubling window ("too good to double") and the take point. Phase 6 must add gammon-rate estimation — from eval features or truncated rollouts — **before** the cube is trusted in blitz/backgame/heavy-contact positions. Until then the cube is only sound in roughly gammonless races.

## 5. Equity vocabulary (to keep straight in Phase 6)

- **Cubeless equity** ∈ [−1, +1] gammonless (wider with gammons): `2·winProb − 1`.
- **Cubeful equity** accounts for cube ownership and recube vig — the basis for take/drop.
- The Phase-3 evaluator's raw output is an **unbounded eval score**, *not* equity; `fromEquity` maps it to a probability. Phase 6 derives points-equity from that probability (plus gammon rates), not from the raw score.

## 6. UI / persistence touchpoints (Phase 6)

- A cube affordance on the board: **offer double**, and **take / drop** on the receiving turn.
- A cube indicator (current value + owner / centred).
- Cube + match state in the resume blob and (optional) game-history stats.

## 7. Build trigger

When Phase 6 begins: run this through brainstorming → a full implementation-ready spec (calibration of win-prob, gammon-rate model, cubeful take-point math, match play + Crawford, UI, persistence) → plan → implementation. Do not implement from this stub.
