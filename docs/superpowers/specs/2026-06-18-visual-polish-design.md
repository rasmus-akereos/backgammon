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
    error = Color(0xFFCF6679), onError = Color(0xFF1A1205),       // muted rose, legible on navy (not Material's default red)
    scrim = Color(0xFF0A1526),                                    // navy-tinted dialog/sheet scrim
)
private val BackgammonTypography = Typography() // base; titleLarge / titleMedium / labelLarge copied with fontWeight = FontWeight.SemiBold
```
`BackgammonTheme` passes `colorScheme = BackgammonColors` and `typography = BackgammonTypography`, both top-level constants in `Theme.kt` (named params on the single `darkColorScheme(...)` call — no `.copy()`). The explicit `surfaceContainer*` roles are **required** — the two `AlertDialog`s (pass/resign), the `ModalBottomSheet` analysis sheet, and the `CubePrompt` `Card` derive their container color from them; the `scrim`/`error` roles keep dialog scrims and any error state on-palette.

The `surfaceContainer*` params require Material3 ≥ 1.2 (the project is on BOM `2024.12.x` → M3 1.3.x, so they compile). **Dynamic color is intentionally not used**: passing an explicit `colorScheme` already prevents Material You from overriding the scheme — do not add `dynamicDarkColorScheme()`. The game keeps a fixed dark navy/gold identity regardless of system light/dark or wallpaper.

## 5. Consolidate hardcoded colors (`ui/screens/GameScreen.kt`)

Create a small `AppColors` object (in `:app`, e.g. `ui/theme/AppColors.kt`) holding **only pure `Color` constants** — no `Brush`, no Compose-draw imports, and **no `:ai`/`Band` import**. It is chrome-only: composables reference `AppColors`, never `BoardColors` (board pigments stay in `BoardColors`).

- **Move-quality band colors** — `bandDisplay()` currently hardcodes 5 literals (BEST green … BLUNDER `#C62828`) that go near-black at low alpha on navy. Move them into `AppColors`, **re-tuned to read on navy** with concrete values: `bandBest = #66BB6A`, `bandGood = #9CCC65`, `bandInaccuracy = #FFB74D`, `bandMistake = #FF8A65`, `bandBlunder = #EF5350`. Bump the chip container alpha from `0.15f` to exactly `0.22f`. Requirement: chip **text contrast ≥ 4.5:1** against the chip container (`bandColor.copy(alpha = 0.22f)` over `surface`); the values above are chosen to satisfy it. Green→red semantic ordering preserved.
- **The `Band → (label, Color)` mapping stays in `ui/screens`** (a pure `bandColor(band: Band): Color` and `bandLabel(band): String`, next to the existing `bandDisplay`, which already imports `Band`) — it is *not* in `AppColors`, so the theme palette never imports `:ai`. These pure functions are unit-tested (§8).
- **Roll button** — drop the inline `ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))` override; the Roll button uses the theme `primary` (bright gold). Red is intentionally not used by any button in this scheme. All other buttons inherit the scheme.

## 6. Dice (`ui/board/DiceView.kt`)

- A dedicated `AppColors.diceFace` (cream) and `dicePip` (charcoal) — **not** coupled to `BoardColors.whiteChecker`/`blackChecker` (so Slice B checker changes don't alter dice). Update **every** dice-color reference in `DiceView.kt` (verify by grep: no `BoardColors.*` remains in the file).
- Make `drawDieFace` **`private`/`internal` to `DiceView.kt`**; `DiceRow`/`DieFace` are its only callers. Its face gradient is a **top-level `val` normalized vertical gradient** (`Brush.verticalGradient` over `0f..1f`, scaled to the face rect by `DrawScope`), so it needs no `side` parameter and is allocated once — never inside the per-frame `DrawScope`.
- The `used`/dimmed state is **not** "alpha on the brush" (a `Brush` has no alpha). Pass `alpha = if (used) 0.4f else 1f` to the `drawRoundRect`/pip `drawCircle` calls (the `DrawScope` alpha parameter) using the one shared brush.
- Rounded-rect faces with a subtle top-down gradient (stops e.g. `#FBF7EE → #E7E0D0`) and a **simple offset shadow** — a translucent filled round-rect drawn at offset `(2.dp, 3.dp)`, color `Color(0x4D000000)` (**no blur**, no `BlurMaskFilter`/native `Paint`).
- Pips are filled circles in **canonical Western die layouts**, spelled out so 3 and 6 aren't ambiguous: 1 = center; 2 = top-left + bottom-right; **3 = top-left + center + bottom-right (single diagonal with center)**; 4 = four corners; 5 = four corners + center; **6 = two vertical columns of three (left column rows 1/2/3, right column rows 1/2/3)**.

## 7. Side panel (`ui/screens/GameScreen.kt`)

Wrap **only the stats sub-block** (turn label + pip line + "this turn" moves — not the whole panel `Column`) in a `Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium)` with explicit padding, so the card reads against the outer `surface`-colored panel. The decluttered 6b-i control set is unchanged; the **Take** (`CubePrompt`) and **New game** buttons inherit the bright-gold primary; **Drop**/**Undo**/**Analyse** stay `OutlinedButton`s; **Resign** stays the small text link. Confirm the Roll button no longer sets a `containerColor` (per §5).

## 8. Testing

Chrome theming is visual → verified by **debug build + manual playtest**, plus the regression and pure-logic guards below:
- **Compile + run all suites** as a done-step: `./gradlew :core:test :ai:test :app:testDebugUnitTest :app:assembleDebug` — all green (no public API change; `assembleDebug` is the true compile gate after color renames).
- **Unit tests** (pure, no Compose render):
  - `bandColor(band)` **and** `bandLabel(band)` for all five `Band` values (label + color, guarding the refactor of `bandDisplay`'s literals).
  - Extract `pipOffsets(face: Int): List<Offset>` from the dice layout and assert: face 1 → 1 pip at center; face 6 → 6 pips, none at center (two columns of three); face 3 → 3 pips on the diagonal incl. center; each face's pip **count** equals its value.
- **Manual checklist** (binary): no Material-purple/grey in any screen (buttons, both AlertDialogs, the analysis bottom sheet incl. its scrim, the cube prompt); stats card reads as grouped navy; Roll button is the salient gold action (and no longer red); **band chip text contrast ≥ 4.5:1** (measure on a screenshot, not by glance); dice pips render standard layouts; cream-on-navy text legible at arm's length on the debug device.

## 9. Files (anticipated)

- `app/.../ui/theme/Theme.kt` — `BackgammonColors` scheme + `BackgammonTypography` (both top-level vals).
- `app/.../ui/theme/AppColors.kt` — new: **pure `Color` constants only** (band* + dice colors); no `:ai`/`Brush` imports.
- `app/.../ui/screens/GameScreen.kt` — `bandColor(band)`/`bandLabel(band)` extraction (the `Band` mapping lives here) + chip alpha `0.22f`; stats sub-block `Surface`; Roll button override removed.
- `app/.../ui/board/DiceView.kt` — shaded dice, canonical pips via extracted `pipOffsets(face)`, top-level vertical-gradient brush, `private` `drawDieFace`, `AppColors` dice colors (no `BoardColors.*`).
- `app/src/test/.../BandColorTest.kt` — `bandColor`/`bandLabel` mapping test.
- `app/src/test/.../DicePipLayoutTest.kt` — `pipOffsets` count/position test.

## 10. Follow-ups (later)

- **Slice B** — board depth.
- A distinctive display font; animations.
