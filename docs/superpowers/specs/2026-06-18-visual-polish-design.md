# Visual Polish A — Theme + Panel + Dice (Refined Navy & Gold): Design Spec

**Status:** Design — ready for plan-writing.
**Date:** 2026-06-18
**Scope:** Presentation layer only (`:app` UI chrome). No game logic, geometry, `:core`/`:ai`, or board-rendering depth (that's Slice B). No public API changes.
**Related:** Split from the original visual-polish spec after spec review (direction "Refined Navy & Gold, A2", chosen via the brainstorming visual companion). Board depth → `docs/superpowers/specs/2026-06-18-visual-polish-b-board-depth-design.md`.

---

## 0. Why two slices

Spec review flagged that the board-depth work (gradient felt, frame, point gradients, beveled+ridged checkers, glossy cube) is six independent visual treatments — the bulk of the effort, the per-density tweak risk, and the Compose draw-loop performance concern. So:

- **Slice A (this spec):** the cohesive **navy/gold Material theme** + side-panel surface + **dice** polish. Flat, low-risk, high-ROI — it removes the "Material purple" look from all chrome (buttons, dialogs, the analysis sheet, the cube prompt) in one pass.
- **Slice B:** board depth (felt/frame/points/checkers+ridge/cube), with the performance and legibility constraints the review raised.

## 1. Goal

Replace Material's default purple scheme with an intentional **navy & gold** palette so every piece of chrome — buttons, dialogs, the analysis bottom sheet, the cube prompt, the side panel — matches the navy board, and polish the dice. **Done looks like:** no Material-purple surfaces anywhere; the side panel reads as a grouped card; the primary action (Roll) is clearly salient; dice look crisp and shaded — verified by a debug build on device, with all existing unit suites still green.

## 2. Scope

**In scope** (all `:app`)
- A custom Material3 dark color scheme in `Theme.kt` (navy surfaces incl. `surfaceContainer*`, gold roles, cream text).
- Consolidate **all hardcoded UI colors** into one palette object and bring them into the scheme: the Roll button red and the five `bandDisplay` quality colors.
- A brighter, distinct **primary gold** so the Roll/Take buttons stay salient against the board's accent gold.
- Side panel (`GameScreen`): wrap the stats block in a themed surface; buttons inherit the scheme.
- Dice (`DiceView`): rounded, subtly shaded faces with a soft (allocation-free) shadow and canonical pip layouts, using a dedicated dice color and **hoisted** brushes.

**Out of scope**
- ❌ Board depth — felt gradient/vignette, frame/pinstripe, point gradients, checker bevel/ridge/shadow, cube gloss, highlight softening → **Slice B**.
- ❌ Animations, sound, a custom font file, layout restructuring.
- ❌ Game logic, `BoardGeometry`, `:core`/`:ai`.

## 3. Decisions (brainstorming + spec review)

- Direction **A2 — Refined Navy & Gold**.
- **System font** retained (no font resource). Headings get `FontWeight.SemiBold` via a small `Typography` override — committed, not "optional"; no letter-spacing change.
- **Roll/primary uses a brighter gold (`#F0B830`)**, distinct from the board accent gold (`#E0A526`), so the primary action pops.

## 4. Theme (`ui/theme/Theme.kt`)

Define the scheme once as a top-level constant (named parameters on the single `darkColorScheme(...)` call — no `.copy()`), passed by reference:

```kotlin
private val BackgammonColors = darkColorScheme(
    primary = Color(0xFFF0B830), onPrimary = Color(0xFF1A1205),   // bright gold primary action
    secondary = Color(0xFFC9A227), onSecondary = Color(0xFF1A1205),
    background = Color(0xFF0F1D33), onBackground = Color(0xFFEEF2F8),
    surface = Color(0xFF13233F), onSurface = Color(0xFFEEF2F8),
    surfaceVariant = Color(0xFF1E3258), onSurfaceVariant = Color(0xFFC9D2E0),
    // dialogs / bottom sheet / cards inherit the navy family, not Material grey:
    surfaceContainerLowest = Color(0xFF0E1A31), surfaceContainerLow = Color(0xFF152744),
    surfaceContainer = Color(0xFF1A2C4C), surfaceContainerHigh = Color(0xFF21345A), surfaceContainerHighest = Color(0xFF273B63),
    outline = Color(0xFF8A93A6),
)
```
`BackgammonTheme` passes `colorScheme = BackgammonColors` plus a `Typography` whose `titleMedium`/`titleLarge`/`labelLarge` use `FontWeight.SemiBold`. The explicit `surfaceContainer*` roles are **required** — the two `AlertDialog`s (pass/resign), the `ModalBottomSheet` analysis sheet, and the `CubePrompt` `Card` derive their container color from them, so omitting them would leave grey/purple surfaces.

## 5. Consolidate hardcoded colors (`ui/screens/GameScreen.kt`)

Create a small `AppColors` object (in `:app`, e.g. `ui/theme/`) holding the non-Material-role UI colors so none are stranded as inline literals:

- **Move-quality band colors** — `bandDisplay()` currently hardcodes 5 literals (BEST green … BLUNDER `#C62828`). Move them into `AppColors` as `bandBest/bandGood/bandInaccuracy/bandMistake/bandBlunder`, **re-tuned to read on navy** (lighten each hue for dark-surface legibility), and bump the chip container alpha from `0.15f` to `~0.22f` so the dark Blunder/Mistake shades stay visible. Extract a pure `bandColor(band: Band): Color` (unit-tested, §8). The green→red semantic ordering is preserved.
- **Roll button** — drop the inline `Color(0xFFC62828)`; the Roll button uses the theme `primary` (bright gold). All other buttons inherit the scheme.

## 6. Dice (`ui/board/DiceView.kt`)

- A dedicated `AppColors.diceFace` (cream) and `dicePip` (charcoal) — **not** coupled to `BoardColors.whiteChecker` (so Slice B checker changes don't alter dice).
- Rounded-rect faces with a subtle top-down gradient and a soft drop shadow (an offset translucent circle/rect — **no** `BlurMaskFilter`/native `Paint`). Pips are filled circles in **canonical die layouts** (1 center; 2/3 diagonal; 4/6 corners/columns; 5 corners+center).
- Any `Brush` is built **once** (top-level `val` or `remember`ed), never inside the per-frame `DrawScope` — `drawDieFace`'s signature is unchanged; the `used`/dimmed state applies `alpha` to the shared brush rather than a separate code path.

## 7. Side panel (`ui/screens/GameScreen.kt`)

Wrap the stats block (turn label + pip line + "this turn") in a themed `Surface`/`Card` (using `surfaceContainer`) for visual grouping. The decluttered 6b-i control set is unchanged; the **Take** (`CubePrompt`) and **New game** buttons inherit the bright-gold primary; **Drop**/**Undo**/**Analyse** stay `OutlinedButton`s; **Resign** stays the small text link.

## 8. Testing

Chrome theming is visual → verified by **debug build + manual playtest**, plus the regression and pure-logic guards below:
- **Run all suites** as a done-step: `./gradlew :core:test :ai:test :app:testDebugUnitTest` — all green (no public API change).
- **Unit test** the extracted `bandColor(band: Band)` mapping (every `Band` → its `AppColors` constant; pure, no Compose render).
- **Manual checklist** (binary): no Material-purple in any screen (buttons, both AlertDialogs, the analysis bottom sheet, the cube prompt); the stats card reads as grouped navy; the Roll button is clearly the salient action; band chip legible on navy at `0.22f`; dice pips legible with standard layouts; all text legible (cream-on-navy) at normal distance.

## 9. Files (anticipated)

- `app/.../ui/theme/Theme.kt` — `BackgammonColors` scheme + `Typography` override.
- `app/.../ui/theme/AppColors.kt` — new: band colors + dice colors (the consolidated non-role palette).
- `app/.../ui/screens/GameScreen.kt` — `bandColor()` extraction + retuned chip alpha; stats surface; Roll button themed.
- `app/.../ui/board/DiceView.kt` — shaded dice, canonical pips, hoisted brush, dedicated dice colors.
- `app/src/test/.../BandColorTest.kt` — new unit test.

## 10. Follow-ups (later)

- **Slice B** — board depth.
- A distinctive display font; animations.
