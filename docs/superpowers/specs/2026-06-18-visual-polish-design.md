# Visual Polish — Refined Navy & Gold (A2): Design Spec

**Status:** Design — ready for plan-writing.
**Date:** 2026-06-18
**Scope:** Presentation layer only (`:app` UI). No game logic, no `:core`/`:ai` changes, no public API changes.
**Related:** Chosen via the brainstorming visual companion (direction A2 "Rich depth" with ridged checkers). Mockups persist in `.superpowers/brainstorm/`.

---

## 1. Goal

Make the app look intentionally designed ("professional") by (a) replacing Material's default purple scheme with a cohesive **navy & gold** theme so all chrome matches the board, and (b) giving the board **depth** — gradient felt, a gold-pinstripe frame, beveled/glossy checkers with a real-piece **ridge**, and polished dice/cube. Direction A2 from the design exploration.

**Done looks like:** the board reads as a premium physical set (depth, ridged checkers), and every button/dialog/panel uses the navy/gold palette rather than Material purple — verified by a debug build on device.

## 2. Scope

**In scope** (all `:app`)
- Custom Material3 dark color scheme (navy surfaces, gold primary, cream on-surface) in `Theme.kt`.
- Board depth in `BoardCanvas.kt`: gradient felt + vignette, gold-pinstripe frame, gradient points, beveled checkers with ridge + drop shadow, glossy cube and borne-off pieces, softer translucent move highlights.
- New color stops in `BoardColors.kt`.
- Rounded, shaded dice in `DiceView.kt`.
- Minor `GameScreen.kt`: wrap side-panel stats in a subtle surface; Roll uses the themed gold primary (drop the hardcoded `Color(0xFFC62828)`).

**Out of scope**
- ❌ Animations / transitions (checker tweening, dice-roll animation), sound.
- ❌ A custom bundled font (system font + refined scale for now; a display font is an easy follow-up).
- ❌ Layout restructuring beyond the panel surface (the 6b-i declutter stands).
- ❌ Any game-logic, geometry (`BoardGeometry`), or `:core`/`:ai` change.

## 3. Decisions (from brainstorming)

- Direction **A2 — Refined Navy & Gold, rich/skeuomorphic depth** (vs A1 clean-flat, B wood, C minimal).
- Checkers get an **outer ridge** (the stacking lip of a real backgammon checker), rendered as concentric inner rings.
- **System font** retained (no new font dependency); the win comes from color + depth.

## 4. Theme (`ui/theme/Theme.kt`)

Replace `darkColorScheme()` with an explicit scheme so chrome matches the board:

- `primary` = gold `#E0A526`; `onPrimary` = near-black `#1A1205`.
- `background`/`surface` = deep navy (`#13233F` / a slightly lifted `#172A48` for surfaces); `onBackground`/`onSurface` = cream `#EEF2F8`.
- `secondary`/`tertiary` = muted gold/slate as needed for outlined buttons and chips.
- Keep `darkColorScheme()` as the base and override these roles (so unspecified roles stay sensible).

Body text keeps the default type scale; headings/labels may get slightly increased weight/letter-spacing via `MaterialTheme.typography` overrides (optional, low-risk). No font resource added.

## 5. Board depth (`ui/board/BoardCanvas.kt`, `BoardColors.kt`)

`BoardColors` gains the stops these need (felt-center/edge, frame, pinstripe gold, point-gradient pairs, checker-bevel highlight/shadow, ridge groove/rim per colour). Treatments:

1. **Felt** — `Brush.radialGradient` (lighter navy center → darker edge) as the base `drawRect`, plus an inset dark vignette wash.
2. **Frame** — the board draws within a thick dark border; a thin **gold pinstripe** is a `drawRect`/inset stroke just inside the frame edge.
3. **Points** — each triangle filled with a vertical `Brush.linearGradient` (cream pair / brown pair) instead of a flat colour.
4. **Checkers** (`drawStack`, and bar/tray pieces) — per checker:
   - soft **drop shadow**: a translucent dark circle offset down a couple of px, drawn first;
   - **face**: `Brush.radialGradient` with an off-centre light highlight (glossy bevel);
   - **ridge**: two concentric `drawCircle` strokes just inside the rim — an outer dark groove and an inner light rim (bright for white, subtle for black).
5. **Cube & borne-off** — same radial-bevel + ridge-lite treatment for consistency.
6. **Move highlights** (`highlightTarget`) — keep gold but as a softer translucent glow (lower alpha / rounded) rather than a flat fill.

Technique note: `DrawScope` has no built-in circle drop-shadow, so shadows are an offset translucent circle and the ridge is concentric stroked circles — all cheap; `drawStack` already layers ring+face, so this extends the existing structure. The `drawCube` added in 6b-i is updated to the same bevel/ridge style.

## 6. Dice (`ui/board/DiceView.kt`)

Rounded-rect dice faces with a subtle top-down gradient and a soft drop shadow; pips as filled circles with a faint inset. Match the cream/charcoal of the checkers. (No animation.)

## 7. Side panel (`ui/screens/GameScreen.kt`)

- Wrap the stats block (turn label + pip line + "this turn") in a subtle `Surface`/`Card` using the themed surface colour, for visual grouping.
- The **Roll** button uses the theme's gold `primary` (remove the hardcoded red `Color(0xFFC62828)`); other buttons inherit the themed scheme. The decluttered control set from 6b-i is unchanged.

## 8. Testing

Rendering is inherently visual → verified by **debug build + manual playtest** (APK), checking: navy/gold chrome throughout (no purple), gradient felt + gold pinstripe, beveled **ridged** checkers at game scale, glossy cube/dice, themed Roll button. No public API changes, so the existing `:app`/`:core`/`:ai` suites must remain green (regression guard). If any pure color/brush helper is extracted, give it a trivial unit test; otherwise no new unit tests (no testable logic added).

## 9. Files (anticipated)

- `app/.../ui/theme/Theme.kt` — custom color scheme (+ optional type tweaks).
- `app/.../ui/board/BoardColors.kt` — new gradient/frame/ridge stops.
- `app/.../ui/board/BoardCanvas.kt` — felt gradient/vignette, frame+pinstripe, point gradients, checker bevel+ridge+shadow, cube/tray bevel, softer highlights.
- `app/.../ui/board/DiceView.kt` — rounded shaded dice + pips.
- `app/.../ui/screens/GameScreen.kt` — stats surface; themed Roll button.

## 10. Open follow-ups (not in this slice)

- A distinctive display font for headings.
- Checker-move and dice-roll animations.
