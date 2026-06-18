# Visual Polish B — Board Depth (Refined Navy & Gold): Design Spec

**Status:** Design captured — **build after Slice A**. Gets its own plan when started.
**Date:** 2026-06-18
**Scope:** `:app` board rendering only (`BoardCanvas`, `BoardColors`). No game logic, geometry, `:core`/`:ai`, or public API changes.
**Related:** Slice A (theme/panel/dice) — `docs/superpowers/specs/2026-06-18-visual-polish-design.md`. Direction A2 from the brainstorming visual companion; this spec incorporates the spec-review findings (performance, legibility, structure).

---

## 1. Goal

Give the board physical depth so it reads as a premium set: gradient navy felt with a subtle vignette, a gold-pinstripe frame, gently shaded points, and **beveled checkers with a concentric ridge** (the stacking lip of a real checker), plus a glossy doubling cube. **Done looks like:** at real device scale — including a 5-high stack — checkers show a clear bevel and (when large enough) ridge, the felt has depth without muddying piece contrast, and the frame reads as gold-trimmed — verified on a mid-range device, all suites still green, no draw-loop jank.

## 2. Performance constraint (review blocker B1 — non-negotiable)

`drawBoard` runs every frame. **No `Brush`/`Paint` construction inside any `DrawScope`** function:
- Static-color brushes — felt radial, the two point linear-gradients, the two checker bevel brushes, the cube bevel — are **top-level `val`s** (or `remember`ed at the `BoardCanvas` composition site), built once.
- Geometry-dependent gloss uses the **fraction-of-bounds** form, e.g. `Brush.radialGradient(stops, center = Offset(0.35f, 0.30f), radius = 1f)`, applied via `drawCircle(brush, radius, center)` so it scales with each checker without per-instance allocation.
- The existing `drawCube` `android.graphics.Paint` is hoisted to a constant (it currently allocates per frame).
- Shadows are an **offset translucent circle/rect** drawn first — no `BlurMaskFilter`/native `Paint`.

## 3. Structure (review M8)

- `BoardColors` stays a **pure `Color` palette** — only `Color` constants, no `Brush`/Compose-draw imports. New stops follow the existing `white*/black*` and `felt/point*` naming: `feltCenter/feltEdge`, `frameDark/pinstripe`, `pointLightTop/pointLightBase/pointDarkTop/pointDarkBase`, `whiteHighlight/whiteShadow/whiteGroove/whiteRim`, `blackHighlight/blackShadow/blackGroove/blackRim`, `cubeFace*`.
- Brushes are built in `BoardCanvas` from those constants.
- Extract `private fun DrawScope.drawChecker(center: Offset, radius: Float, player: Player)` — the shadow + bevel-face + ridge sequence — reused by `drawStack`, `drawBarCheckers`, `drawTray` (avoids triplicating the sequence). `drawCube` reuses the bevel treatment.

## 4. Treatments

1. **Felt** — `Brush.radialGradient` lighter-navy center → darker edge, **centered on the playing area** (`Offset(playW/2f, h/2f)`, not full canvas, so the tray column doesn't skew it) + an inset vignette wash (radial `Color.Transparent` at ~60% → `#26000000` at edge). Params pinned in `BoardColors`/`BoardCanvas`, fixed once.
2. **Frame** — thick dark border (`frameDark`) with a thin **gold pinstripe** (`pinstripe`) inset just inside the edge (a stroke `drawRect`).
3. **Points** — each triangle filled with a vertical `linearGradient` (one of the two hoisted brushes). The darkest cream stop and lightest brown stop keep **≥ 3:1 luminance contrast** so alternation stays readable for pip counting.
4. **Checkers** (`drawChecker`) — per piece: soft drop shadow (offset translucent circle); glossy bevel face via the fraction-of-bounds radial gradient with **pinned per-player stops** (White `#FFFFFF → #C8C0B0`, Black `#484848 → #1A1A1A`) so midtones never converge; **retain a visible outer ring** so the black checker keeps ≥ 3:1 against the darkest felt stop; then the **ridge** — outer dark groove + inner light rim strokes (bright for white, subtle for black).
   - **Ridge size gate (review M4):** draw the ridge only when `radius ≥ 18dp`; below that, plain bevel only. Stroke widths scale with radius (`groove ≈ r·0.08`, `rim ≈ r·0.06`).
5. **Cube & borne-off** — cube gets the bevel; its face must stay light enough (`cubeFace` light center) for the value label, else set the label cream. (Borne-off-tray bevel is a **follow-up**, not in this slice — diffuse payoff per review.)
6. **Move highlights** (`highlightTarget`) — keep gold; **do not drop alpha below the current `0.35f`** (legal-move visibility is primary gameplay feedback). "Softer" comes from a corner radius / subtle glow on the highlight rect, not lower alpha.

## 5. Testing

Rendering is visual → **debug build + manual playtest**, plus:
- **Run all suites** (`:core`/`:ai`/`:app`) — green; no API change.
- **Unit test** the new `BoardColors` stop ordering (pure): for each pair, highlight lighter than shadow, rim lighter than groove (compare `Color.luminance()`), and the point cream/brown contrast ≥ 3:1.
- **Manual checklist** (binary, includes worst cases): gradient felt + gold pinstripe visible; bevel reads on white *and* black checkers; **ridge legible on a 5-high stack** (not muddy) and correctly omitted below the size gate; black checker distinct against the darkest felt; legal-move highlights still obvious; no draw-loop jank on a mid-range device.

## 6. Files (anticipated)

- `app/.../ui/board/BoardColors.kt` — new gradient/frame/ridge `Color` stops (pure palette).
- `app/.../ui/board/BoardCanvas.kt` — hoisted brushes; `drawChecker` extraction; felt/frame/point/cube treatments; ridge size gate; highlight softening.
- `app/src/test/.../BoardColorsTest.kt` — stop-ordering/contrast unit test.

## 7. Follow-ups

- Borne-off-tray piece bevel; checker-move & dice-roll animations; a Compose-text cube label (vs `nativeCanvas.drawText`).
