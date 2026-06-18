# Visual Polish B — Board Depth — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the board physical depth — gradient felt, a gold-pinstripe frame, shaded points, and beveled checkers with a real-piece ridge + soft shadow, plus a beveled cube and softened move highlights — presentation only.

**Architecture:** New `Color` stops in `BoardColors` (pure palette). In `BoardCanvas`, felt and point gradients are **hoisted top-level `Brush` vals** (built once, never per-frame). Checkers are drawn by a new `drawChecker(center, radius, player)` using **layered solid circles** (shadow + ring + face + gloss spot + ridge strokes) — zero per-checker brush allocation — reused by `drawStack` and `drawBarCheckers`. The cube's `Paint` is hoisted (fixes a per-frame allocation). No game logic, geometry, or `:core`/`:ai` changes.

**Tech Stack:** Kotlin, Jetpack Compose `DrawScope`, kotlin.test/JUnit5.

**Spec:** `docs/superpowers/specs/2026-06-18-visual-polish-b-board-depth-design.md`

**Build/test** (toolchain not on PATH; see memory `build-environment.md`):
- app tests: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest`
- APK: `… ./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

---

## Design notes locked during planning

- **Perf (spec §2):** the only `Brush` objects are 3 hoisted top-level `val`s (felt radial + two point vertical gradients), built once at class-load. `Brush.verticalGradient(colors)`/`radialGradient(colors)` with default bounds resolve to the draw area at draw time, so they need no size argument and are safe to hoist. Checkers/cube use solid `drawCircle`/`drawRect` only — no brushes, so the 30-checkers-per-frame allocation concern does not arise.
- **Felt vignette** is folded into the felt radial gradient (lighter centre → darker edge **is** the vignette); no separate wash. Centre defaults to the canvas centre — the tray column is narrow (~6%), so the minor off-centre vs the playing area is not worth a size-dependent brush.
- **Borne-off tray pieces** keep their current flat-bar rendering (bevel there is a deferred follow-up per spec §4.5) — `drawTray` is untouched, `drawChecker` is reused only by `drawStack` + `drawBarCheckers`.
- **Ridge size gate (spec §4 / M4):** ridge strokes draw only when `radius >= 18.dp.toPx()` (DrawScope is a `Density`), so tiny stacked checkers show a plain bevel instead of mud.

---

## File structure
- **Modify** `app/.../ui/board/BoardColors.kt` — new pure `Color` stops (felt/frame/points/checker bevel+ridge).
- **Modify** `app/.../ui/board/BoardCanvas.kt` — hoisted brushes + cube `Paint`; felt/points/frame treatments; `drawChecker` extraction; cube bevel; softened highlight.
- **Create** `app/src/test/.../ui/board/BoardColorsTest.kt` — stop-ordering / contrast test.

---

## Task 1: BoardColors depth stops + ordering test

**Files:**
- Modify `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardColors.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/ui/board/BoardColorsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.ui.board

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class BoardColorsTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05; val lb = b.luminance() + 0.05
        return (if (la > lb) la / lb else lb / la).toDouble()
    }

    @Test fun `point light family is clearly lighter than the dark family`() {
        val lightMin = minOf(BoardColors.pointLightTop.luminance(), BoardColors.pointLightBase.luminance())
        val darkMax = maxOf(BoardColors.pointDarkTop.luminance(), BoardColors.pointDarkBase.luminance())
        assertTrue(lightMin > darkMax, "light family must stay lighter than dark family")
    }

    @Test fun `point alternation keeps at least 3 to 1 contrast`() {
        assertTrue(contrast(BoardColors.pointLightBase, BoardColors.pointDarkTop) >= 3.0)
    }

    @Test fun `checker ridge rim is lighter than its groove`() {
        assertTrue(BoardColors.whiteRim.luminance() > BoardColors.whiteGroove.luminance())
        assertTrue(BoardColors.blackRim.luminance() > BoardColors.blackGroove.luminance())
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (new constants unresolved)

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.ui.board.BoardColorsTest"`

- [ ] **Step 3: Implement** — append to the `BoardColors` object (keep existing constants):

```kotlin
    // --- Depth (Visual Polish B) ---
    // Felt radial gradient (lighter centre → darker edge doubles as the vignette).
    val feltCenter = Color(0xFF1D3A63)
    val feltEdge = Color(0xFF0E1A31)
    // Frame + gold pinstripe.
    val frameDark = Color(0xFF0D1830)
    val pinstripe = Color(0xFFE0A526)
    // Point gradients — cream pair / brown pair. Light family stays clearly lighter than dark.
    val pointLightTop = Color(0xFFEFE3C6)
    val pointLightBase = Color(0xFFD8C8A4)
    val pointDarkTop = Color(0xFF8A5A2E)
    val pointDarkBase = Color(0xFF5E3C1E)
    // Checker bevel + ridge. Face/ring reuse whiteChecker/whiteRing/blackChecker/blackRing.
    val checkerShadow = Color(0x59000000)   // soft drop shadow under any checker
    val whiteHighlight = Color(0x80FFFFFF)  // glossy spot
    val whiteGroove = Color(0x33000000)     // ridge dark groove
    val whiteRim = Color(0x80FFFFFF)        // ridge light rim
    val blackHighlight = Color(0x4DFFFFFF)
    val blackGroove = Color(0x66000000)
    val blackRim = Color(0x33FFFFFF)
```

- [ ] **Step 4: Run — expect PASS** (same command as Step 2).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/BoardColors.kt app/src/test/java/dk/rlunde/backgammon/ui/board/BoardColorsTest.kt
git commit -m "feat(app): board-depth color stops + ordering/contrast test"
```

---

## Task 2: Felt gradient, shaded points, gold-pinstripe frame

**Files:** Modify `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt`

- [ ] **Step 1: Add hoisted brushes** (top-level, after the imports, before `BoardCanvas`):

```kotlin
private val feltBrush = androidx.compose.ui.graphics.Brush.radialGradient(
    listOf(BoardColors.feltCenter, BoardColors.feltEdge))
private val pointLightBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
    listOf(BoardColors.pointLightTop, BoardColors.pointLightBase))
private val pointDarkBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
    listOf(BoardColors.pointDarkTop, BoardColors.pointDarkBase))
```
(Or add `import androidx.compose.ui.graphics.Brush` and drop the FQNs.)

- [ ] **Step 2: Change `drawTriangle` to take a `Brush`** and use brushes for points + felt. Replace the felt+points section at the top of `drawBoard` and the `drawTriangle` function:

In `drawBoard`, replace `drawRect(BoardColors.felt)` and the points loop with:
```kotlin
    drawRect(feltBrush)
    for (i in 1..24) {
        val r = g.pointRect(i)
        drawTriangle(r, if (i % 2 == 0) pointLightBrush else pointDarkBrush, pointingUp = i in 1..12)
    }
```
Replace `drawTriangle`:
```kotlin
private fun DrawScope.drawTriangle(r: BoardRect, brush: androidx.compose.ui.graphics.Brush, pointingUp: Boolean) {
    val path = Path().apply {
        if (pointingUp) { moveTo(r.l, r.b); lineTo(r.r, r.b); lineTo((r.l + r.r) / 2, r.t) }
        else { moveTo(r.l, r.t); lineTo(r.r, r.t); lineTo((r.l + r.r) / 2, r.b) }
        close()
    }
    drawPath(path, brush)
}
```

- [ ] **Step 3: Add the frame + pinstripe**, drawn last in `drawBoard` (after the cube line, so it sits on top of felt edges). Add to the end of `drawBoard`:
```kotlin
    drawFrame()
```
and the function:
```kotlin
private fun DrawScope.drawFrame() {
    val frameW = minOf(size.width, size.height) * 0.025f
    // Dark frame band hugging the edge (stroke inset by half-width so it stays on-canvas).
    drawRect(BoardColors.frameDark, topLeft = Offset(frameW / 2f, frameW / 2f),
        size = Size(size.width - frameW, size.height - frameW), style = Stroke(width = frameW))
    // Thin gold pinstripe just inside the frame.
    val inset = frameW
    drawRect(BoardColors.pinstripe, topLeft = Offset(inset, inset),
        size = Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(width = frameW * 0.14f))
}
```

- [ ] **Step 4: Build + run the existing geometry/colors tests** (rendering is visual; confirm compile + no regression):

Run: `… ./gradlew :app:testDebugUnitTest --tests "dk.rlunde.backgammon.ui.board.*"` then `… ./gradlew :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt
git commit -m "feat(app): gradient felt + shaded points + gold-pinstripe frame"
```

---

## Task 3: Beveled, ridged checkers (`drawChecker`)

**Files:** Modify `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt`

- [ ] **Step 1: Add `drawChecker`** (the shadow + ring + face + gloss + ridge sequence):

```kotlin
private fun DrawScope.drawChecker(center: Offset, radius: Float, player: Player) {
    val face = if (player == Player.WHITE) BoardColors.whiteChecker else BoardColors.blackChecker
    val ring = if (player == Player.WHITE) BoardColors.whiteRing else BoardColors.blackRing
    val highlight = if (player == Player.WHITE) BoardColors.whiteHighlight else BoardColors.blackHighlight
    val groove = if (player == Player.WHITE) BoardColors.whiteGroove else BoardColors.blackGroove
    val rim = if (player == Player.WHITE) BoardColors.whiteRim else BoardColors.blackRim
    // Soft drop shadow (offset translucent circle — no blur).
    drawCircle(BoardColors.checkerShadow, radius, center + Offset(radius * 0.10f, radius * 0.14f))
    // Outer ring (keeps black checkers legible on dark felt) + face.
    drawCircle(ring, radius, center)
    drawCircle(face, radius * 0.92f, center)
    // Glossy highlight spot, up-left.
    drawCircle(highlight, radius * 0.42f, center + Offset(-radius * 0.28f, -radius * 0.30f))
    // Ridge — only when the checker is large enough to read it.
    if (radius >= 18.dp.toPx()) {
        drawCircle(groove, radius * 0.86f, center, style = Stroke(width = radius * 0.08f))
        drawCircle(rim, radius * 0.74f, center, style = Stroke(width = radius * 0.06f))
    }
}
```
Add imports if missing: `import androidx.compose.ui.unit.dp`.

- [ ] **Step 2: Use it in `drawStack`** — replace the per-checker draw loop body:
```kotlin
private fun DrawScope.drawStack(r: BoardRect, n: Int, player: Player, fromBottom: Boolean) {
    val byWidth = (r.r - r.l) / 2f * 0.9f
    val byHeight = (r.b - r.t) / (2f * MAX_STACK_SHOWN) * 0.98f
    val radius = minOf(byWidth, byHeight)
    val shown = minOf(n, MAX_STACK_SHOWN)
    for (k in 0 until shown) {
        val cy = if (fromBottom) r.b - radius - k * radius * 2 else r.t + radius + k * radius * 2
        drawChecker(Offset(r.cx, cy), radius, player)
    }
}
```

- [ ] **Step 3: Use it in `drawBarCheckers`** — replace its final `drawCircle` with `drawChecker`:
```kotlin
private fun DrawScope.drawBarCheckers(g: BoardGeometry, n: Int, player: Player) {
    if (n == 0) return
    val bar = g.barRect()
    val radius = (bar.r - bar.l) / 2f * 0.8f
    val baseY = if (player == Player.WHITE) bar.b - radius - bar.b * 0.1f else bar.t + radius + bar.b * 0.1f
    drawChecker(Offset(bar.cx, baseY), radius, player)
}
```

- [ ] **Step 4: Build + assemble**

Run: `… ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt
git commit -m "feat(app): beveled, ridged checkers via shared drawChecker (size-gated ridge)"
```

---

## Task 4: Beveled cube + hoisted Paint

**Files:** Modify `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt`

- [ ] **Step 1: Hoist the cube `Paint`** (top-level, near the brushes):
```kotlin
private val cubePaint = android.graphics.Paint().apply {
    color = android.graphics.Color.BLACK
    textAlign = android.graphics.Paint.Align.CENTER
    isAntiAlias = true
}
```

- [ ] **Step 2: Replace `drawCube`** with a beveled version that reuses the hoisted paint:
```kotlin
private fun DrawScope.drawCube(g: BoardGeometry, cube: CubeState, highlight: Boolean) {
    val r = g.cubeRect(cube.owner)
    val tl = Offset(r.l, r.t)
    val sz = Size(r.r - r.l, r.b - r.t)
    // Soft drop shadow.
    drawRect(BoardColors.checkerShadow, topLeft = Offset(r.l + sz.width * 0.06f, r.t + sz.height * 0.08f), size = sz)
    // Cream face + a subtle top highlight band (cheap bevel).
    drawRect(BoardColors.whiteChecker, topLeft = tl, size = sz)
    drawRect(BoardColors.whiteHighlight, topLeft = tl, size = Size(sz.width, sz.height * 0.4f))
    // Border: gold when the player on roll may double, else neutral.
    val border = if (highlight) BoardColors.highlight else BoardColors.bar
    drawRect(border, topLeft = tl, size = sz,
        style = Stroke(width = sz.width * (if (highlight) 0.12f else 0.06f)))
    // Value label (hoisted paint; text size set per draw — a field write, not an allocation).
    cubePaint.textSize = sz.height * 0.55f
    drawContext.canvas.nativeCanvas.drawText(cube.value.toString(), r.cx, r.cy + cubePaint.textSize * 0.35f, cubePaint)
}
```

- [ ] **Step 3: Build + assemble** — Run: `… ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt
git commit -m "feat(app): beveled cube with hoisted Paint (no per-frame allocation)"
```

---

## Task 5: Softened move highlights

**Files:** Modify `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt`

- [ ] **Step 1: Round the highlight** (keep alpha at the current 0.35 floor; soften via corner radius):
```kotlin
private fun DrawScope.highlightTarget(g: BoardGeometry, t: BoardTarget) {
    val r = when (t) {
        is BoardTarget.Point -> g.pointRect(t.index)
        BoardTarget.Bar -> g.barRect()
        is BoardTarget.BearOff -> g.bearOffRect(t.player)
        BoardTarget.Dice -> g.diceRect()
    }
    drawRoundRect(
        BoardColors.highlight.copy(alpha = 0.35f),
        topLeft = Offset(r.l, r.t),
        size = Size(r.r - r.l, r.b - r.t),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius((r.r - r.l) * 0.18f),
    )
}
```
(Or add `import androidx.compose.ui.geometry.CornerRadius`.)

- [ ] **Step 2: Build + assemble** — Run: `… ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt
git commit -m "feat(app): soften move highlights (rounded, alpha floored at 0.35)"
```

---

## Task 6: Full verification + APK

- [ ] **Step 1: Compile gate + all suites**

Run: `JAVA_HOME=/home/lunde/jdks/jdk-21.0.11+10 ANDROID_HOME=/home/lunde/Android/Sdk ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `BoardColorsTest` green. (Skip `:ai:test` here — its `TierOrderingTest` is ~16 min and unaffected by UI; the final-review/CI can run it.)

- [ ] **Step 2: Perf/boundary check** — no per-frame `Brush`/`Paint` construction inside `DrawScope`:

Run: `grep -nE 'Brush\.(radial|vertical|linear)Gradient|Paint\(' app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt`
Expected: matches appear ONLY at top-level `val` declarations (feltBrush/pointLightBrush/pointDarkBrush/cubePaint), NOT inside `drawBoard`/`drawChecker`/`drawCube`/`drawTriangle`.

- [ ] **Step 3: Manual checklist** (debug APK): gradient felt + gold pinstripe frame visible; points clearly alternate (countable); **bevel reads on white AND black checkers; ridge legible on a 5-high stack and absent on tiny checkers**; black checker distinct against the felt; cube beveled with legible value; legal-move highlights still obvious (rounded gold). No draw jank tapping/staging.

- [ ] **Step 4:** APK at `app/build/outputs/apk/debug/app-debug.apk` for playtest.

---

## Self-Review (completed during planning)

**Spec coverage:** §2 perf (hoisted brushes, no per-frame Brush/Paint, offset shadow, hoisted cube Paint) → Tasks 2/3/4 + Task 6 grep. §3 structure (pure BoardColors palette, `drawChecker` extraction reused by stack+bar) → Tasks 1/3. §4.1 felt+vignette → Task 2 (vignette folded into radial). §4.2 frame+pinstripe → Task 2. §4.3 point gradients + ≥3:1 → Tasks 1/2. §4.4 checker bevel+ridge+shadow, per-player stops, retained ring, size-gate → Tasks 1/3. §4.5 cube bevel (borne-off deferred) → Task 4. §4.6 highlight alpha floor + soften via shape → Task 5. §5 BoardColorsTest + run suites + manual → Tasks 1/6.

**Placeholder scan:** none — every step has concrete code/commands. Color values are concrete and the contrast test is the gate (the chosen `pointLightBase`/`pointDarkTop` give ≈3.6:1).

**Type consistency:** `drawChecker(center: Offset, radius: Float, player: Player)`, `drawTriangle(r, brush: Brush, pointingUp)`, `feltBrush`/`pointLightBrush`/`pointDarkBrush`/`cubePaint`, `drawFrame()`, and the new `BoardColors` stop names are used identically across tasks. `drawTriangle`'s signature change (Color→Brush) is applied in Task 2 with both call sites updated in the same step.
