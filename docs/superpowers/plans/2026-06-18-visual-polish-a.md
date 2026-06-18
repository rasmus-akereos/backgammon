# Visual Polish A — Theme + Panel + Dice — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Material's default purple scheme with a cohesive navy/gold theme across all chrome (buttons, dialogs, analysis sheet, cube prompt, panel), group the stats into a card, and polish the dice — presentation only, no game logic.

**Architecture:** A custom `darkColorScheme` + `Typography` in `Theme.kt`; a pure-`Color` `AppColors` palette for non-Material-role UI colors; the `Band→(label,color)` mapping and dice pip layout extracted to pure, unit-tested functions; dice get a hoisted gradient brush + offset shadow. No `:core`/`:ai`/geometry/board-depth changes (board depth = Slice B).

**Tech Stack:** Kotlin, Jetpack Compose Material3, kotlin.test/JUnit5. `:app` tests run under `:app:testDebugUnitTest`.

**Spec:** `docs/superpowers/specs/2026-06-18-visual-polish-design.md`

**Build/test commands** (toolchain not on PATH — set inline; see memory `build-environment.md`):
- app tests: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest`
- single class: add `--tests "dk.rlunde.backgammon.ui.BandColorTest"`
- APK: `… ./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

---

## File structure

- **Modify** `app/.../ui/theme/Theme.kt` — `BackgammonColors` (scheme) + `BackgammonTypography` (SemiBold titles).
- **Create** `app/.../ui/theme/AppColors.kt` — pure `Color` constants (band* + dice).
- **Modify** `app/.../ui/screens/GameScreen.kt` — `bandColor`/`bandLabel` extraction (internal, testable) + chip alpha `0.22f`; stats sub-block `Surface`; Roll button override removed.
- **Modify** `app/.../ui/board/DiceView.kt` — `pipOffsets` extraction; `private` `drawDieFace` with hoisted brush + offset shadow + `AppColors` colors.
- **Create** `app/src/test/.../ui/BandColorTest.kt`, `app/src/test/.../ui/board/DicePipLayoutTest.kt`.

Note: `drawDieFace`'s **canonical pip positions already exist** and are correct (3 = diagonal-with-center, 6 = two columns) — Task 4 only extracts them for testing, preserving the positions.

---

## Task 1: Navy/gold theme + typography

**Files:** Modify `app/src/main/java/dk/rlunde/backgammon/ui/theme/Theme.kt`

- [ ] **Step 1: Replace the file contents**

```kotlin
package dk.rlunde.backgammon.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

// Fixed navy/gold identity. Passing an explicit colorScheme already suppresses Material You —
// do not add dynamicDarkColorScheme(). Requires Material3 >= 1.2 for the surfaceContainer* roles.
private val BackgammonColors = darkColorScheme(
    primary = Color(0xFFF0B830), onPrimary = Color(0xFF1A1205),     // bright gold primary action
    secondary = Color(0xFFC9A227), onSecondary = Color(0xFF1A1205),
    background = Color(0xFF0F1D33), onBackground = Color(0xFFEEF2F8),
    surface = Color(0xFF13233F), onSurface = Color(0xFFEEF2F8),
    surfaceVariant = Color(0xFF1E3258), onSurfaceVariant = Color(0xFFC9D2E0),
    surfaceContainerLowest = Color(0xFF0E1A31), surfaceContainerLow = Color(0xFF152744),
    surfaceContainer = Color(0xFF1A2C4C), surfaceContainerHigh = Color(0xFF21345A),
    surfaceContainerHighest = Color(0xFF273B63),
    outline = Color(0xFF8A93A6),
    error = Color(0xFFCF6679), onError = Color(0xFF1A1205),         // muted rose, legible on navy
    scrim = Color(0xFF0A1526),                                       // navy-tinted dialog/sheet scrim
)

private val BaseTypography = Typography()
private val BackgammonTypography = BaseTypography.copy(
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun BackgammonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BackgammonColors, typography = BackgammonTypography, content = content)
}
```

- [ ] **Step 2: Verify it compiles**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (If `surfaceContainer*` or `scrim` are unresolved, the Compose-Material3 version predates 1.2 — stop and report; do not delete the roles.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/theme/Theme.kt
git commit -m "feat(app): navy/gold Material theme + SemiBold title typography"
```

---

## Task 2: `AppColors` palette

**Files:** Create `app/src/main/java/dk/rlunde/backgammon/ui/theme/AppColors.kt`

- [ ] **Step 1: Create the file**

```kotlin
package dk.rlunde.backgammon.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Non-Material-role UI colors (chrome only). Pure Color constants — NO Brush, NO Compose-draw
 * imports, NO :ai/Band import. Composables reference AppColors; board pigments stay in BoardColors.
 */
object AppColors {
    // Move-quality band colors, tuned to read as text on a 0.22-alpha chip over the navy surface.
    val bandBest = Color(0xFF66BB6A)
    val bandGood = Color(0xFF9CCC65)
    val bandInaccuracy = Color(0xFFFFB74D)
    val bandMistake = Color(0xFFFF8A65)
    val bandBlunder = Color(0xFFEF5350)
    val bandForced = Color(0xFFB0BEC5)

    // Dice — decoupled from BoardColors so Slice B checker changes don't alter dice.
    val diceFace = Color(0xFFFBF7EE)
    val diceFaceShade = Color(0xFFE7E0D0)
    val dicePip = Color(0xFF23262B)
}
```

- [ ] **Step 2: Verify it compiles**

Run: `… ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/theme/AppColors.kt
git commit -m "feat(app): AppColors palette (band + dice colors)"
```

---

## Task 3: Band color/label mapping (testable) + retuned chip

**Files:**
- Modify `app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/ui/BandColorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ui

import dk.rlunde.backgammon.ai.Band
import dk.rlunde.backgammon.ui.screens.bandColor
import dk.rlunde.backgammon.ui.screens.bandLabel
import dk.rlunde.backgammon.ui.theme.AppColors
import kotlin.test.Test
import kotlin.test.assertEquals

class BandColorTest {
    @Test fun `each band maps to its AppColors constant`() {
        assertEquals(AppColors.bandBest, bandColor(Band.BEST))
        assertEquals(AppColors.bandGood, bandColor(Band.GOOD))
        assertEquals(AppColors.bandInaccuracy, bandColor(Band.INACCURACY))
        assertEquals(AppColors.bandMistake, bandColor(Band.MISTAKE))
        assertEquals(AppColors.bandBlunder, bandColor(Band.BLUNDER))
    }

    @Test fun `each band has its label`() {
        assertEquals("Best", bandLabel(Band.BEST))
        assertEquals("Good", bandLabel(Band.GOOD))
        assertEquals("Inaccuracy", bandLabel(Band.INACCURACY))
        assertEquals("Mistake", bandLabel(Band.MISTAKE))
        assertEquals("Blunder", bandLabel(Band.BLUNDER))
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (`bandColor`/`bandLabel` unresolved)

Run: `… --tests "dk.rlunde.backgammon.ui.BandColorTest"`

- [ ] **Step 3: Implement** — in `GameScreen.kt`, replace the existing `bandDisplay`:

```kotlin
/** Returns label + color for the given analysis, taking forced flag into account. */
private fun bandDisplay(analysis: MoveAnalysis): Pair<String, Color> =
    if (analysis.forced) "Forced" to AppColors.bandForced
    else bandLabel(analysis.band) to bandColor(analysis.band)

internal fun bandLabel(band: Band): String = when (band) {
    Band.BEST -> "Best"
    Band.GOOD -> "Good"
    Band.INACCURACY -> "Inaccuracy"
    Band.MISTAKE -> "Mistake"
    Band.BLUNDER -> "Blunder"
}

internal fun bandColor(band: Band): Color = when (band) {
    Band.BEST -> AppColors.bandBest
    Band.GOOD -> AppColors.bandGood
    Band.INACCURACY -> AppColors.bandInaccuracy
    Band.MISTAKE -> AppColors.bandMistake
    Band.BLUNDER -> AppColors.bandBlunder
}
```
Add the import `import dk.rlunde.backgammon.ui.theme.AppColors` to `GameScreen.kt`. Then find the band-marker chip `Surface` in `TrackingPanel` (its `color = bandColor.copy(alpha = 0.15f)`) and change `0.15f` → `0.22f`.

- [ ] **Step 4: Run — expect PASS**

Run: `… --tests "dk.rlunde.backgammon.ui.BandColorTest"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt app/src/test/java/dk/rlunde/backgammon/ui/BandColorTest.kt
git commit -m "feat(app): testable band color/label mapping via AppColors; chip alpha 0.22"
```

---

## Task 4: Extract `pipOffsets` (testable dice layout)

**Files:**
- Modify `app/src/main/java/dk/rlunde/backgammon/ui/board/DiceView.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/ui/board/DicePipLayoutTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ui.board

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DicePipLayoutTest {
    @Test fun `pip count equals face value`() {
        for (f in 1..6) assertEquals(f, pipOffsets(f).size, "face $f")
    }
    @Test fun `face 1 is a single centre pip`() {
        assertEquals(Offset(0.5f, 0.5f), pipOffsets(1).single())
    }
    @Test fun `face 3 is a diagonal including the centre`() {
        val p = pipOffsets(3)
        assertEquals(3, p.size)
        assertTrue(p.contains(Offset(0.5f, 0.5f)))
    }
    @Test fun `face 6 has two columns of three and no centre pip`() {
        val p = pipOffsets(6)
        assertEquals(6, p.size)
        assertTrue(p.none { it.x == 0.5f && it.y == 0.5f })
        assertEquals(3, p.count { it.x == 0.28f })  // left column
        assertEquals(3, p.count { it.x == 0.72f })  // right column
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`pipOffsets` unresolved)

Run: `… --tests "dk.rlunde.backgammon.ui.board.DicePipLayoutTest"`

- [ ] **Step 3: Implement** — add to `DiceView.kt` (preserving the existing positions):

```kotlin
/** Canonical Western die pip positions, as fractions of the face (0..1). Pure — unit-tested. */
internal fun pipOffsets(face: Int): List<Offset> {
    val lo = 0.28f; val mid = 0.5f; val hi = 0.72f
    return when (face) {
        1 -> listOf(Offset(mid, mid))
        2 -> listOf(Offset(lo, lo), Offset(hi, hi))
        3 -> listOf(Offset(lo, lo), Offset(mid, mid), Offset(hi, hi))
        4 -> listOf(Offset(lo, lo), Offset(hi, lo), Offset(lo, hi), Offset(hi, hi))
        5 -> listOf(Offset(lo, lo), Offset(hi, lo), Offset(mid, mid), Offset(lo, hi), Offset(hi, hi))
        6 -> listOf(Offset(lo, lo), Offset(hi, lo), Offset(lo, mid), Offset(hi, mid), Offset(lo, hi), Offset(hi, hi))
        else -> emptyList()
    }
}
```
`Offset` is already imported in `DiceView.kt`. (Task 5 rewires `drawDieFace` to call this; for now the old `when (face)` block still exists — that's fine, the test only needs `pipOffsets`.)

- [ ] **Step 4: Run — expect PASS**

Run: `… --tests "dk.rlunde.backgammon.ui.board.DicePipLayoutTest"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/DiceView.kt app/src/test/java/dk/rlunde/backgammon/ui/board/DicePipLayoutTest.kt
git commit -m "feat(app): extract pipOffsets() with canonical-layout tests"
```

---

## Task 5: Shaded dice (gradient + offset shadow, `AppColors`, private)

**Files:** Modify `app/src/main/java/dk/rlunde/backgammon/ui/board/DiceView.kt`

- [ ] **Step 1: Rewrite `DieFace` + `drawDieFace`** (replace lines 31–60)

```kotlin
@Composable
private fun DieFace(face: Int, used: Boolean, size: Dp) {
    Canvas(Modifier.size(size)) {
        drawDieFace(this.size.minDimension, face, used)
    }
}

private val diceFaceBrush = Brush.verticalGradient(listOf(AppColors.diceFace, AppColors.diceFaceShade))
private val diceShadowColor = Color(0x4D000000)

/** A single die face: rounded shaded tile + canonical pips. A consumed die is dimmed via alpha. */
private fun DrawScope.drawDieFace(side: Float, face: Int, used: Boolean) {
    val a = if (used) 0.4f else 1f
    val corner = CornerRadius(side * 0.18f, side * 0.18f)
    // Simple offset shadow (no blur, no native Paint).
    drawRoundRect(color = diceShadowColor, topLeft = Offset(side * 0.05f, side * 0.07f),
        size = Size(side, side), cornerRadius = corner, alpha = a)
    drawRoundRect(brush = diceFaceBrush, topLeft = Offset.Zero, size = Size(side, side),
        cornerRadius = corner, alpha = a)
    val pipR = side * 0.085f
    pipOffsets(face).forEach { o ->
        drawCircle(AppColors.dicePip, pipR, Offset(o.x * side, o.y * side), alpha = a)
    }
}
```

- [ ] **Step 2: Fix imports** — in `DiceView.kt` add:
```kotlin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dk.rlunde.backgammon.ui.theme.AppColors
```
and **remove** the now-unused `import dk.rlunde.backgammon.ui.board.BoardColors` usage — verify by grep that no `BoardColors.` remains in the file:
Run: `grep -n 'BoardColors' app/src/main/java/dk/rlunde/backgammon/ui/board/DiceView.kt` → expect no output.

- [ ] **Step 3: Build + run dice test**

Run: `… ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.ui.board.DicePipLayoutTest"`
Expected: PASS (and `DiceView.kt` compiles; `drawDieFace` is now `private` with the new signature, called only by `DieFace`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/DiceView.kt
git commit -m "feat(app): shaded dice — hoisted gradient brush, offset shadow, AppColors, private drawDieFace"
```

---

## Task 6: Roll button + stats card

**Files:** Modify `app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt`

- [ ] **Step 1: Remove the Roll button color override**

In the `NEED_ROLL` branch of `TrackingPanel`'s controls, the Roll `Button` currently sets `colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White)`. Delete that `colors = …` argument so the button inherits the theme `primary`:

```kotlin
Phase.NEED_ROLL -> Button(onClick = onRoll, modifier = Modifier.fillMaxWidth()) { Text("Roll") }
```

- [ ] **Step 2: Wrap the stats sub-block in a Surface**

In `TrackingPanel`, wrap **only** the turn label + pip line + "This turn" moves block (not the whole `Column`) in:

```kotlin
Surface(
    color = MaterialTheme.colorScheme.surfaceContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth(),
) {
    Column(Modifier.padding(12.dp)) {
        // existing: turn label Text, Spacer, pip line Text, Spacer, "This turn" label + staged moves
    }
}
```
Keep the existing children verbatim inside the new `Column`; do not move the game-over result text, the band chip, dice, or controls into it.

- [ ] **Step 3: Build**

Run: `… ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt
git commit -m "feat(app): themed gold Roll button + grouped stats card"
```

---

## Task 7: Full verification + APK

- [ ] **Step 1: Compile gate + all suites**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :core:test :ai:test :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL across all (no public API change; `BandColorTest` + `DicePipLayoutTest` green).

- [ ] **Step 2: Boundary check** — no board-depth / dynamic-color crept in

Run: `grep -rniE 'dynamicDarkColorScheme|BoardColors' app/src/main/java/dk/rlunde/backgammon/ui/theme app/src/main/java/dk/rlunde/backgammon/ui/board/DiceView.kt`
Expected: no `dynamicDarkColorScheme`; no `BoardColors` in `DiceView.kt`.

- [ ] **Step 3: Manual checklist** (debug APK on device): no Material purple/grey anywhere (buttons, both AlertDialogs, analysis bottom sheet + its scrim, cube prompt); stats card reads as a grouped navy card; Roll button is the salient gold action (no longer red); band chip text contrast ≥ 4.5:1 on a screenshot (not by glance); dice show standard pip layouts with the shaded face + soft shadow; cream-on-navy text legible at arm's length.

- [ ] **Step 4:** APK at `app/build/outputs/apk/debug/app-debug.apk` for playtest.

---

## Self-Review (completed during planning)

**Spec coverage:** §4 theme (scheme + surfaceContainer*/scrim/error + typography + dynamic-color note) → Task 1. §5 AppColors pure + band mapping in ui/screens + 0.22 alpha + Roll override removal → Tasks 2, 3, 6. §6 dice (decoupled AppColors, private drawDieFace, hoisted normalized brush, alpha-via-param, offset shadow, canonical pips) → Tasks 4, 5. §7 stats sub-block surface + Roll → Task 6. §8 testing (bandColor+bandLabel test, pipOffsets test, run-all-suites + assembleDebug, manual checklist) → Tasks 3, 4, 7. §9 files → all tasks.

**Placeholder scan:** none — every step has concrete code/commands. The "wrap existing children" in Task 6 Step 2 references the verbatim existing block (no rewrite), which is unambiguous.

**Type consistency:** `bandColor(Band): Color`, `bandLabel(Band): String`, `AppColors.band*/dice*`, `pipOffsets(Int): List<Offset>`, `drawDieFace(side, face, used)` (private, new signature), `diceFaceBrush`/`diceShadowColor` are used identically across tasks. `BackgammonColors`/`BackgammonTypography` defined once (Task 1).
