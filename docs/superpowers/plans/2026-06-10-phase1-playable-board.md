# Phase 1 — Playable Compose Board Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A hot-seat (human-vs-human) Android backgammon board: a Compose `Canvas` board with tap-to-roll, tap-to-move, undo, commit, auto-pass, and a win banner — validating the Phase 0 engine against a real UI.

**Architecture:** New `:app` Android module depending on `:core`. All rules stay in `:core` (a new pure `TurnPlanner` powers incremental move-building). The turn state machine is a pure `GameController` in `:app` (JVM-tested, no Android). `GameViewModel` is a thin `androidx` adapter exposing `StateFlow<GameUiState>` + a `Channel<UiEvent>`. The board is one `Canvas` driven by a shared, pure `BoardGeometry` for draw + tap hit-testing.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (Material3), AGP 8.7.x on Gradle 8.10, `compileSdk 35` / `minSdk 26`, JDK 21 (Android Studio JBR), JUnit 5 + `kotlin.test` + kotlinx-coroutines (no AI yet).

---

## Conventions (read once — every task assumes these)

- **Run all Gradle commands in the Bash tool with `JAVA_HOME` set:**
  ```
  export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
  ./gradlew <task> --console=plain 2>&1 | tail -40
  ```
- `:core` package `dk.rlunde.backgammon.core`; `:app` package `dk.rlunde.backgammon`.
- Phase 0 engine API available: `MoveGenerator.legalMoves(state, dice)`, `apply(state, move)`, `pass(state)`; `Scoring.isGameOver/winnerAndValue/pipCount`; `startingPosition()`; `SeededDiceRoller(Long)`/`RandomDiceRoller()`; `BoardState.count(p,i)/barCount(p)/offCount(p)/isBlockedFor/isBlotFor`; `Dice(a,b).pips()`; `SubMove(from,to,die,isHit)`; `Move(subMoves).pipsUsed`; `Player.WHITE/BLACK`, `.opponent`, `.sign`.
- Board model: `points` size 26, idx 1..24; `+`=WHITE, `-`=BLACK. WHITE home 1–6, off sentinel 0, bar `from`=25, enters `25-die`. BLACK home 19–24, off sentinel 25, bar `from`=0, enters `die`.
- TDD per task: failing test → run & SEE fail → implement → run & SEE pass → commit. Never push.

## File structure

```
settings.gradle.kts                      // add google(); include(":app")
gradle/libs.versions.toml                // add AGP, compose plugin, compose-bom, coroutines, lifecycle
local.properties                         // (git-ignored) sdk.dir
core/src/main/.../MoveGenerator.kt       // helpers private->internal; add applyPartial
core/src/main/.../TurnPlanner.kt         // NEW: maxUsablePips, legalNextSubMoves
app/build.gradle.kts                     // android app + compose
app/src/main/AndroidManifest.xml         // launcher MainActivity, no INTERNET
app/src/main/java/dk/rlunde/backgammon/
├── MainActivity.kt
├── ui/screens/GameScreen.kt
├── ui/board/BoardGeometry.kt            // pure: BoardRect, BoardTarget, hitTest (no androidx.*)
├── ui/board/BoardCanvas.kt              // Compose Canvas draw + pointerInput
├── ui/board/BoardColors.kt
├── ui/theme/Theme.kt
├── game/GameController.kt               // pure turn state machine
├── game/GameUiState.kt                  // GameUiState, Phase, UiEvent
└── viewmodel/GameViewModel.kt
app/src/test/java/dk/rlunde/backgammon/  // JVM unit tests for controller/uistate/geometry
```

---

## Task 0: Android toolchain + blank `:app` (gated spike)

**Goal:** prove the Compose/AGP/Gradle toolchain works end-to-end before any feature code.

**Files:**
- Modify: `gradle/libs.versions.toml`, `settings.gradle.kts`
- Create: `local.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/dk/rlunde/backgammon/MainActivity.kt`

- [ ] **Step 1: Install the `android-35` platform** (avoids a Gradle wrapper bump)

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
SDK="/c/Users/RasmusLundgaardHanse/AppData/Local/Android/Sdk"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="C:\Users\RasmusLundgaardHanse\AppData\Local\Android\Sdk" "platforms;android-35" 2>&1 | tail -5
ls "$SDK/platforms"
```
Expected: `android-35` now present alongside `android-36`/`android-36.1`. (If `cmdline-tools/latest` is absent, use Android Studio's SDK Manager UI to install "Android 15 (API 35)", or `sdkmanager` from `$SDK/cmdline-tools/<ver>/bin`.)

- [ ] **Step 2: Create `local.properties`** (git-ignored already via `.gitignore`)

```properties
sdk.dir=C\:\\Users\\RasmusLundgaardHanse\\AppData\\Local\\Android\\Sdk
```

- [ ] **Step 3: Add versions to `gradle/libs.versions.toml`**

Add under `[versions]`:
```toml
agp = "8.7.3"
composeBom = "2024.12.01"
coroutines = "1.9.0"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
```
Add under `[libraries]`:
```toml
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-graphics = { module = "androidx.compose.ui:ui-graphics" }
compose-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
```
Add under `[plugins]`:
```toml
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 4: Update `settings.gradle.kts`** — add `google()` and the `:app` module, and a plugin-management repo block

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "backgammon"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":core")
include(":app")
```

- [ ] **Step 5: Create `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dk.rlunde.backgammon"
    compileSdk = 35

    defaultConfig {
        applicationId = "dk.rlunde.backgammon"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
```
Also add to `gradle/libs.versions.toml` `[plugins]` the Android-Kotlin plugin (the `kotlin-jvm` plugin won't work for an Android module):
```toml
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

- [ ] **Step 6: Create `app/src/main/AndroidManifest.xml`** (no INTERNET permission)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:label="Backgammon"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 7: Create the blank `MainActivity.kt`**

```kotlin
package dk.rlunde.backgammon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("ok") }
    }
}
```

- [ ] **Step 8: Gate A — build, install, launch on the emulator**

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
SDK="/c/Users/RasmusLundgaardHanse/AppData/Local/Android/Sdk"
# start the emulator (background) if not already running:
"$SDK/emulator/emulator.exe" -avd Pixel_9_Pro -no-snapshot -no-boot-anim &
"$SDK/platform-tools/adb.exe" wait-for-device
./gradlew :app:installDebug --console=plain 2>&1 | tail -20
"$SDK/platform-tools/adb.exe" shell am start -n dk.rlunde.backgammon/.MainActivity
```
Expected: `BUILD SUCCESSFUL`, app installs, the activity launches showing "ok". If AGP/Compose versions fail to resolve, adjust (e.g. AGP 8.6.1 + Compose BOM 2024.09.03) and retry — record the working set in the version catalog. **Do not proceed until this launches.**

- [ ] **Step 9: Gate B — `:core` still green** (no wrapper bump, but verify)

```
./gradlew :core:test --console=plain 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL, 55 tests pass.

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml app
git commit -m "chore(app): scaffold Android :app module + blank Compose activity"
```

---

## Task 1: `:core` `TurnPlanner` (incremental move planning)

**Files:**
- Modify: `core/src/main/kotlin/dk/rlunde/backgammon/core/MoveGenerator.kt`
- Create: `core/src/main/kotlin/dk/rlunde/backgammon/core/TurnPlanner.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/TurnPlannerTest.kt`

- [ ] **Step 1: Widen visibility + add `applyPartial` in `MoveGenerator.kt`**

Change `private fun enumerateSequences`, `private fun legalSubMovesFor`, and `private fun applySubMove` to `internal fun` (module-visible within `:core` only — NOT visible to `:app`). Then add this public function inside the `MoveGenerator` object:

```kotlin
    /** Fold sub-moves onto [state] WITHOUT flipping toMove — used to build mid-turn partial states. */
    fun applyPartial(state: BoardState, subMoves: List<SubMove>): BoardState {
        var current = state
        for (sm in subMoves) current = applySubMove(current, sm)
        return current
    }
```

- [ ] **Step 2: Write the failing test `TurnPlannerTest.kt`**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnPlannerTest {
    private fun board(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0, toMove: Player = Player.WHITE) =
        BoardState(
            points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            toMove,
        )

    @Test fun `maxUsablePips is full roll when both dice playable`() {
        val p = IntArray(26); p[13] = 2
        assertEquals(8, TurnPlanner.maxUsablePips(board(p), listOf(3, 5)))
    }

    @Test fun `maxUsablePips is zero when no die playable`() {
        val p = IntArray(26); p[13] = 1; p[10] = -2; p[8] = -2 // both 3 and 5 dests blocked
        assertEquals(0, TurnPlanner.maxUsablePips(board(p), listOf(3, 5)))
    }

    @Test fun `legalNextSubMoves groups by origin and only offers max-pips moves`() {
        val p = IntArray(26); p[13] = 2
        val next = TurnPlanner.legalNextSubMoves(board(p), listOf(3, 5))
        assertTrue(13 in next.keys)
        val dests = next.getValue(13).map { it.to }.toSet()
        assertEquals(setOf(10, 8), dests) // 13->10 (die3), 13->8 (die5)
    }

    @Test fun `doubles - legalNextSubMoves stays non-empty across all four staged dice`() {
        // WHITE 4 checkers on 13, dice (2,2,2,2). After staging k moves, k=0..3 still offers a move.
        val committed = board(IntArray(26).also { it[13] = 4 })
        var partial = committed
        var remaining = listOf(2, 2, 2, 2)
        repeat(4) { k ->
            val next = TurnPlanner.legalNextSubMoves(partial, remaining)
            assertTrue(next.isNotEmpty(), "stranded after $k staged dice")
            val sm = next.values.first().first()
            partial = MoveGenerator.applyPartial(partial, listOf(sm))
            remaining = remaining.toMutableList().apply { remove(sm.die) }
        }
        assertEquals(0, TurnPlanner.maxUsablePips(partial, remaining))
    }

    @Test fun `must play larger - only the larger single die is offered when both cannot be played`() {
        // From LegalMovesTest position: one checker on 13, only the 6 is playable (not the 5, not both)
        val p = IntArray(26); p[13] = 1; p[8] = -2; p[2] = -2
        val next = TurnPlanner.legalNextSubMoves(board(p), listOf(6, 5))
        val all = next.values.flatten()
        assertEquals(1, all.size)
        assertEquals(SubMove(13, 7, 6, isHit = false), all.single())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

`./gradlew :core:test --tests "*TurnPlannerTest"` → FAIL (`TurnPlanner` unresolved).

- [ ] **Step 4: Implement `TurnPlanner.kt`**

```kotlin
package dk.rlunde.backgammon.core

/**
 * Drives incremental (one sub-move at a time) turn building for the UI by only ever offering
 * sub-moves that keep the player on a MAXIMUM-pips path — so a complete legal turn is always
 * reachable and a die is never stranded. Pure; reuses MoveGenerator's enumeration.
 */
object TurnPlanner {
    /** Most pips playable from [state] using the dice still in hand. 0 ⇒ the turn is complete. */
    fun maxUsablePips(state: BoardState, remainingDice: List<Int>): Int =
        MoveGenerator.enumerateSequences(state, remainingDice)
            .maxOfOrNull { seq -> seq.sumOf { it.die } } ?: 0

    /**
     * Legal next single-die sub-moves that PRESERVE maxUsablePips, grouped by origin point
     * (bar uses its from-sentinel as the key). Recompute after every staging change; never cache.
     */
    fun legalNextSubMoves(state: BoardState, remainingDice: List<Int>): Map<Int, List<SubMove>> {
        val best = maxUsablePips(state, remainingDice)
        if (best == 0) return emptyMap()
        val memo = HashMap<BoardState, Int>()
        fun maxFrom(s: BoardState, dice: List<Int>): Int =
            memo.getOrPut(s) {
                MoveGenerator.enumerateSequences(s, dice).maxOfOrNull { seq -> seq.sumOf { it.die } } ?: 0
            }
        val result = LinkedHashMap<Int, MutableList<SubMove>>()
        for (die in remainingDice.toSet()) {
            val rest = remainingDice.toMutableList().apply { remove(die) }
            for (sm in MoveGenerator.legalSubMovesFor(state, die)) {
                val after = MoveGenerator.applySubMove(state, sm)
                if (sm.die + maxFrom(after, rest) == best) {
                    result.getOrPut(sm.from) { mutableListOf() }.add(sm)
                }
            }
        }
        return result
    }
}
```
Note: the `memo` keys on the post-sub-move `BoardState`; since each `(die, rest)` pair recomputes the same `after` states across candidates, this avoids repeated enumeration within one call (the §5.4 perf concern).

- [ ] **Step 5: Run test to verify it passes**

`./gradlew :core:test --tests "*TurnPlannerTest"` → PASS. Then full `:core` suite: `./gradlew :core:test` → all green (regression gate).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/MoveGenerator.kt core/src/main/kotlin/dk/rlunde/backgammon/core/TurnPlanner.kt core/src/test/kotlin/dk/rlunde/backgammon/core/TurnPlannerTest.kt
git commit -m "feat(core): add TurnPlanner (maxUsablePips, legalNextSubMoves) + applyPartial"
```

---

## Task 2: `GameUiState`, `Phase`, `UiEvent`, `BoardTarget`

`BoardTarget` is shared by geometry, controller, and UI, so it lives with the pure board types.

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardGeometry.kt` (only the `BoardTarget`/`BoardRect` decls for now; `hitTest` added in Task 5)
- Create: `app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/game/GameUiStateTest.kt`

- [ ] **Step 1: Write the failing test `GameUiStateTest.kt`**

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.core.startingPosition
import dk.rlunde.backgammon.ui.board.BoardTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GameUiStateTest {
    @Test fun `point target rejects out-of-range index`() {
        assertFailsWith<IllegalArgumentException> { BoardTarget.Point(0) }
        assertFailsWith<IllegalArgumentException> { BoardTarget.Point(25) }
    }

    @Test fun `ui state carries the board and phase`() {
        val s = GameUiState(
            board = startingPosition(),
            toMove = Player.WHITE,
            dice = null,
            remainingDice = emptyList(),
            phase = Phase.NEED_ROLL,
            selectedOrigin = null,
            destinations = emptySet(),
            whitePip = 167,
            blackPip = 167,
            winner = null,
            winValue = 0,
        )
        assertEquals(Phase.NEED_ROLL, s.phase)
        assertEquals(167, s.whitePip)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

`./gradlew :app:testDebugUnitTest --tests "*GameUiStateTest"` → FAIL (types unresolved).

- [ ] **Step 3: Create `BoardGeometry.kt` (target/rect decls only for now)**

```kotlin
package dk.rlunde.backgammon.ui.board

import dk.rlunde.backgammon.core.Player

/** A screen rectangle in pixels. Pure data — no Compose/Android types (so geometry is JVM-tested). */
data class BoardRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun contains(x: Float, y: Float): Boolean = x >= l && x < r && y >= t && y < b
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f
}

/** A tappable region of the board. */
sealed interface BoardTarget {
    data class Point(val index: Int) : BoardTarget { init { require(index in 1..24) { "point $index" } } }
    data object Bar : BoardTarget
    data class BearOff(val player: Player) : BoardTarget
    data object Dice : BoardTarget
}
```

- [ ] **Step 4: Create `GameUiState.kt`**

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.BoardState
import dk.rlunde.backgammon.core.Dice
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.ui.board.BoardTarget

enum class Phase { NEED_ROLL, MOVING, COMMITTABLE, GAME_OVER }

/** One-shot UI events (consumed once). */
sealed interface UiEvent {
    /** No legal moves this roll; the turn auto-passes after the player acknowledges. */
    data object NoLegalMoves : UiEvent
}

/** Immutable snapshot the board renders. [board] is the partial mid-turn board (mover not flipped). */
data class GameUiState(
    val board: BoardState,
    val toMove: Player,
    val dice: Dice?,
    val remainingDice: List<Int>,
    val phase: Phase,
    val selectedOrigin: BoardTarget?,
    val destinations: Set<BoardTarget>,
    val whitePip: Int,
    val blackPip: Int,
    val winner: Player?,
    val winValue: Int,
)
```

- [ ] **Step 5: Run test to verify it passes**

`./gradlew :app:testDebugUnitTest --tests "*GameUiStateTest"` → PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/BoardGeometry.kt app/src/main/java/dk/rlunde/backgammon/game/GameUiState.kt app/src/test/java/dk/rlunde/backgammon/game/GameUiStateTest.kt
git commit -m "feat(app): add BoardTarget/BoardRect, GameUiState, Phase, UiEvent"
```

---

## Task 3: `GameController` (pure turn state machine)

The heart of the UI logic, fully JVM-tested with `SeededDiceRoller` and injected positions.

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/game/GameController.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/game/GameControllerTest.kt`

- [ ] **Step 1: Write the failing test `GameControllerTest.kt`**

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.*
import dk.rlunde.backgammon.ui.board.BoardTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameControllerTest {
    private fun controller(points: IntArray, whiteBar: Int = 0, blackBar: Int = 0,
                           whiteOff: Int = 0, blackOff: Int = 0,
                           toMove: Player = Player.WHITE, seed: Long = 1L): GameController {
        val board = BoardState(
            points,
            mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
            mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff),
            toMove,
        )
        return GameController(initial = board, roller = SeededDiceRoller(seed))
    }

    @Test fun `fresh controller needs a roll`() {
        val c = GameController(roller = SeededDiceRoller(1))
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
        assertEquals(167, c.uiState.whitePip)
    }

    @Test fun `roll then move then commit flips the turn`() {
        val p = IntArray(26); p[13] = 2; p[24] = 2 // simple white-only-ish; black minimal
        p[1] = -2
        val c = controller(p)
        c.roll()
        assertEquals(Phase.MOVING, c.uiState.phase)
        // select a point that has a legal origin, then move to its first destination
        val origin = c.uiState.board // partial
        // tap origin 13 (will have destinations), then a destination
        c.tap(BoardTarget.Point(13))
        assertTrue(c.uiState.destinations.isNotEmpty())
        val dest = c.uiState.destinations.first()
        c.tap(dest)
        // continue until committable, then commit
        while (c.uiState.phase == Phase.MOVING) {
            val sel = c.uiState.board
            // pick any legal origin and its first dest
            val tgt = c.uiState.destinations.firstOrNull()
            if (tgt != null) c.tap(tgt) else {
                // need to select first
                c.tapAnyLegalOrigin()
            }
        }
        assertEquals(Phase.COMMITTABLE, c.uiState.phase)
        c.commit()
        assertEquals(Player.BLACK, c.uiState.toMove)
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    }

    @Test fun `auto-pass emits event when no legal moves`() {
        // WHITE on the bar; BLACK owns the two entry points for the seeded roll.
        // Seed chosen so the first roll's entry points are both blocked.
        val p = IntArray(26)
        p[24] = -2; p[23] = -2; p[22] = -2; p[21] = -2; p[20] = -2; p[19] = -2 // BLACK closes its home 19-24
        val c = controller(p, whiteBar = 1, toMove = Player.WHITE, seed = 1)
        val events = c.roll()
        assertTrue(events.contains(UiEvent.NoLegalMoves))
        // after acknowledged pass, it is BLACK's turn to roll
        c.acknowledgePass()
        assertEquals(Player.BLACK, c.uiState.toMove)
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
    }

    @Test fun `game over reports winner and value from a near-terminal position`() {
        // WHITE has 14 off and one checker on point 2; rolling will bear it off.
        val p = IntArray(26); p[2] = 1; p[19] = -15
        val c = controller(p, whiteOff = 14, toMove = Player.WHITE, seed = 3)
        c.roll()
        while (c.uiState.phase == Phase.MOVING) c.tapAnyLegalOrigin().let { c.tapFirstDestination() }
        if (c.uiState.phase == Phase.COMMITTABLE) c.commit()
        assertEquals(Phase.GAME_OVER, c.uiState.phase)
        assertEquals(Player.WHITE, c.uiState.winner)
        assertEquals(2, c.uiState.winValue) // black borne off none -> gammon (2x)
    }

    @Test fun `undo with nothing staged is a no-op`() {
        val c = GameController(roller = SeededDiceRoller(1))
        c.roll()
        val before = c.uiState
        c.undo()
        assertEquals(before.phase, c.uiState.phase)
    }

    @Test fun `tap during NEED_ROLL is a no-op`() {
        val c = GameController(roller = SeededDiceRoller(1))
        val before = c.uiState
        c.tap(BoardTarget.Point(13))
        assertEquals(before, c.uiState)
    }

    @Test fun `new game resets to the initial board`() {
        val c = GameController(roller = SeededDiceRoller(1))
        c.roll()
        c.newGame()
        assertEquals(Phase.NEED_ROLL, c.uiState.phase)
        assertEquals(startingPosition().points.toList(), c.uiState.board.points.toList())
    }
}
```
(Test helpers `tapAnyLegalOrigin`/`tapFirstDestination` are small extensions defined in the test file:)
```kotlin
private fun GameController.tapAnyLegalOrigin() {
    val origin = uiState.let { st ->
        // find a legal origin via the planner-driven destinations: try every point
        (1..24).map { BoardTarget.Point(it) }.firstOrNull { tgtCandidate ->
            tap(tgtCandidate); uiState.destinations.isNotEmpty()
        }
    }
    // if none selected (e.g. bar), try Bar
    if (uiState.destinations.isEmpty()) tap(BoardTarget.Bar)
}
private fun GameController.tapFirstDestination() {
    uiState.destinations.firstOrNull()?.let { tap(it) }
}
```

- [ ] **Step 2: Run test to verify it fails**

`./gradlew :app:testDebugUnitTest --tests "*GameControllerTest"` → FAIL (`GameController` unresolved).

- [ ] **Step 3: Implement `GameController.kt`**

```kotlin
package dk.rlunde.backgammon.game

import dk.rlunde.backgammon.core.*
import dk.rlunde.backgammon.ui.board.BoardTarget

/**
 * Pure (no Android) turn state machine for hot-seat play. Holds the committed board, the current
 * dice, the staged sub-moves, and the selected origin; recomputes [uiState] after every action.
 * Rules are delegated to :core (MoveGenerator / TurnPlanner / Scoring).
 */
class GameController(
    private val initial: BoardState = startingPosition(),
    private val roller: DiceRoller = RandomDiceRoller(),
) {
    private var committed: BoardState = initial
    private var dice: Dice? = null
    private val staged = mutableListOf<SubMove>()
    private var selectedOrigin: BoardTarget? = null
    private var passing = false   // true after an auto-pass that awaits acknowledgement

    var uiState: GameUiState = compute(); private set

    fun roll(): List<UiEvent> {
        if (uiState.phase != Phase.NEED_ROLL) return emptyList()
        val d = roller.roll()
        dice = d
        return if (MoveGenerator.legalMoves(committed, d).isEmpty()) {
            passing = true
            uiState = compute()
            listOf(UiEvent.NoLegalMoves)
        } else {
            uiState = compute()
            emptyList()
        }
    }

    /** Called by the UI when the player dismisses the "no legal moves" notice. */
    fun acknowledgePass() {
        if (!passing) return
        committed = MoveGenerator.pass(committed)
        dice = null
        passing = false
        staged.clear()
        selectedOrigin = null
        uiState = compute()
    }

    fun tap(target: BoardTarget) {
        if (uiState.phase != Phase.MOVING && uiState.phase != Phase.COMMITTABLE) return
        val d = dice ?: return
        val partial = MoveGenerator.applyPartial(committed, staged)
        val remaining = remainingDice(d)
        val next = TurnPlanner.legalNextSubMoves(partial, remaining)

        // 1) If a checker/origin is selected and target is one of its destinations -> stage it.
        val sel = selectedOrigin
        if (sel != null) {
            val fromKey = originKey(sel)
            val chosen = next[fromKey]?.firstOrNull { sameTarget(destinationTarget(it), target) }
            if (chosen != null) {
                staged.add(chosen)
                selectedOrigin = null
                uiState = compute()
                return
            }
        }
        // 2) Otherwise, treat the tap as selecting an origin that has legal moves.
        val key = originKey(target)
        if (key != null && next.containsKey(key)) {
            selectedOrigin = target
        } else {
            selectedOrigin = null // deselect
        }
        uiState = compute()
    }

    fun undo() {
        if (staged.isEmpty()) return
        staged.removeAt(staged.lastIndex)
        selectedOrigin = null
        uiState = compute()
    }

    fun commit() {
        if (uiState.phase != Phase.COMMITTABLE) return
        committed = MoveGenerator.apply(committed, Move(staged.toList()))
        staged.clear()
        selectedOrigin = null
        dice = null
        uiState = compute()
    }

    fun newGame() {
        committed = initial
        dice = null
        staged.clear()
        selectedOrigin = null
        passing = false
        uiState = compute()
    }

    // ---- derivation -----------------------------------------------------------------------

    private fun remainingDice(d: Dice): List<Int> {
        val rem = d.pips().toMutableList()
        for (sm in staged) rem.remove(sm.die)
        return rem
    }

    /** The origin int key used by TurnPlanner (point index, or the bar from-sentinel). */
    private fun originKey(target: BoardTarget): Int? = when (target) {
        is BoardTarget.Point -> target.index
        BoardTarget.Bar -> if (committed.toMove == Player.WHITE) 25 else 0
        else -> null
    }

    private fun destinationTarget(sm: SubMove): BoardTarget =
        if (sm.to == 0 || sm.to == 25) BoardTarget.BearOff(committed.toMove)
        else BoardTarget.Point(sm.to)

    private fun sameTarget(a: BoardTarget, b: BoardTarget) = a == b

    private fun compute(): GameUiState {
        val partial = MoveGenerator.applyPartial(committed, staged)
        val d = dice
        val over = Scoring.isGameOver(committed)
        val phase = when {
            over -> Phase.GAME_OVER
            d == null -> Phase.NEED_ROLL
            else -> {
                val rem = remainingDice(d)
                if (TurnPlanner.maxUsablePips(partial, rem) == 0) Phase.COMMITTABLE else Phase.MOVING
            }
        }
        val destinations: Set<BoardTarget> = if ((phase == Phase.MOVING || phase == Phase.COMMITTABLE) && selectedOrigin != null && d != null) {
            val key = originKey(selectedOrigin!!)
            val next = TurnPlanner.legalNextSubMoves(partial, remainingDice(d))
            next[key]?.map { destinationTarget(it) }?.toSet() ?: emptySet()
        } else emptySet()
        val wv = if (over) Scoring.winnerAndValue(committed) else null
        return GameUiState(
            board = partial,
            toMove = committed.toMove,
            dice = d,
            remainingDice = if (d != null) remainingDice(d) else emptyList(),
            phase = phase,
            selectedOrigin = selectedOrigin,
            destinations = destinations,
            whitePip = Scoring.pipCount(partial, Player.WHITE),
            blackPip = Scoring.pipCount(partial, Player.BLACK),
            winner = wv?.first,
            winValue = wv?.second ?: 0,
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

`./gradlew :app:testDebugUnitTest --tests "*GameControllerTest"` → PASS. Fix any test-helper issues (the helpers may select-then-move in two taps; ensure `tapAnyLegalOrigin` selects and `tapFirstDestination` completes).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/game/GameController.kt app/src/test/java/dk/rlunde/backgammon/game/GameControllerTest.kt
git commit -m "feat(app): add pure GameController turn state machine"
```

---

## Task 4: `GameViewModel` (thin androidx adapter)

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt`

- [ ] **Step 1: Implement `GameViewModel.kt`** (no unit test — pure logic is in `GameController`, already tested; this is wiring verified by the emulator smoke)

```kotlin
package dk.rlunde.backgammon.viewmodel

import androidx.lifecycle.ViewModel
import dk.rlunde.backgammon.game.GameController
import dk.rlunde.backgammon.game.GameUiState
import dk.rlunde.backgammon.game.UiEvent
import dk.rlunde.backgammon.ui.board.BoardTarget
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class GameViewModel : ViewModel() {
    private val controller = GameController()
    private val _uiState = MutableStateFlow(controller.uiState)
    val uiState: StateFlow<GameUiState> = _uiState

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private fun publish(events: List<UiEvent> = emptyList()) {
        _uiState.value = controller.uiState
        events.forEach { _events.trySend(it) }
    }

    fun onRoll() = publish(controller.roll())
    fun onTap(target: BoardTarget) { controller.tap(target); publish() }
    fun onUndo() { controller.undo(); publish() }
    fun onCommit() { controller.commit(); publish() }
    fun onNewGame() { controller.newGame(); publish() }
    fun onAcknowledgePass() { controller.acknowledgePass(); publish() }
}
```

- [ ] **Step 2: Verify it compiles**

`./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -10` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/viewmodel/GameViewModel.kt
git commit -m "feat(app): add GameViewModel adapter (StateFlow + event Channel)"
```

---

## Task 5: `BoardGeometry.hitTest` + layout rects

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardGeometry.kt`
- Test: `app/src/test/java/dk/rlunde/backgammon/ui/board/BoardGeometryTest.kt`

- [ ] **Step 1: Write the failing test `BoardGeometryTest.kt`**

```kotlin
package dk.rlunde.backgammon.ui.board

import dk.rlunde.backgammon.core.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardGeometryTest {
    private val g = BoardGeometry(1080f, 1920f)

    @Test fun `every point center hit-tests to that point`() {
        for (i in 1..24) {
            val r = g.pointRect(i)
            assertEquals(BoardTarget.Point(i), g.hitTest(r.cx, r.cy), "point $i")
        }
    }

    @Test fun `bar center hits Bar`() {
        val r = g.barRect()
        assertEquals(BoardTarget.Bar, g.hitTest(r.cx, r.cy))
    }

    @Test fun `tray centers hit the right player`() {
        assertEquals(BoardTarget.BearOff(Player.WHITE), g.hitTest(g.bearOffRect(Player.WHITE).cx, g.bearOffRect(Player.WHITE).cy))
        assertEquals(BoardTarget.BearOff(Player.BLACK), g.hitTest(g.bearOffRect(Player.BLACK).cx, g.bearOffRect(Player.BLACK).cy))
    }

    @Test fun `tap outside any region returns null`() {
        assertNull(g.hitTest(-5f, -5f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

`./gradlew :app:testDebugUnitTest --tests "*BoardGeometryTest"` → FAIL.

- [ ] **Step 3: Implement `BoardGeometry` (append to `BoardGeometry.kt`)**

```kotlin
package dk.rlunde.backgammon.ui.board

import dk.rlunde.backgammon.core.Player

// (BoardRect and BoardTarget from Task 2 remain above this in the same file.)

/**
 * Pure layout math: maps a board element to a screen [BoardRect] and a tap (x,y) to a [BoardTarget].
 * Fixed White's-perspective portrait layout. Right edge holds the two bear-off trays + dice column.
 * Bottom-right = White home 1..6 (1 nearest the right edge), bottom-left = 7..12, top-left = 13..18,
 * top-right = Black home 19..24 (24 nearest the right edge). Central bar between the halves.
 */
class BoardGeometry(private val w: Float, private val h: Float) {
    private val trayW = w * 0.10f                 // right-edge tray/dice column
    private val playW = w - trayW
    private val barW = playW * 0.08f
    private val halfW = (playW - barW) / 2f        // each 6-point half
    private val colW = halfW / 6f
    private val rowH = h / 2f

    // Columns 0..5 within a half (0 = nearest the bar). Helper to get a quadrant column rect.
    private fun colRect(leftEdge: Float, colFromLeft: Int, top: Float): BoardRect {
        val l = leftEdge + colFromLeft * colW
        return BoardRect(l, top, l + colW, top + rowH)
    }

    /** Screen rect for point [index] (1..24). */
    fun pointRect(index: Int): BoardRect = when (index) {
        in 1..6 -> {            // bottom-right: 1 nearest right edge (col 5) .. 6 nearest bar (col 0)
            val colFromLeft = index - 1            // 0..5 from bar; 1->0? define: 6=bar side
            // 6 at bar (col0), 1 at right (col5): colFromBar = 6-index
            val colFromBar = 6 - index
            val leftEdge = halfW + barW            // right half starts after left-half+bar
            colRect(leftEdge, colFromBar, rowH)
        }
        in 7..12 -> {           // bottom-left: 7 nearest bar (rightmost of left half) .. 12 at left edge
            val colFromLeft = 12 - index           // 12->0 (left edge) .. 7->5 (bar side)
            colRect(0f, colFromLeft, rowH)
        }
        in 13..18 -> {          // top-left: 13 at left edge .. 18 nearest bar
            val colFromLeft = index - 13           // 13->0 .. 18->5
            colRect(0f, colFromLeft, 0f)
        }
        else -> {               // 19..24 top-right: 19 nearest bar .. 24 at right edge
            val colFromBar = index - 19            // 19->0 .. 24->5
            val leftEdge = halfW + barW
            colRect(leftEdge, colFromBar, 0f)
        }
    }

    fun barRect(): BoardRect = BoardRect(halfW, 0f, halfW + barW, h)

    fun bearOffRect(player: Player): BoardRect =
        if (player == Player.WHITE) BoardRect(playW, rowH, w, h)   // bottom-right tray
        else BoardRect(playW, 0f, w, rowH)                          // top-right tray

    fun diceRect(): BoardRect = BoardRect(0f, rowH - rowH * 0.15f, halfW, rowH + rowH * 0.15f) // center band, left half

    fun hitTest(x: Float, y: Float): BoardTarget? {
        if (x < 0f || y < 0f || x > w || y > h) return null
        if (diceRect().contains(x, y)) return BoardTarget.Dice
        if (barRect().contains(x, y)) return BoardTarget.Bar
        if (bearOffRect(Player.WHITE).contains(x, y)) return BoardTarget.BearOff(Player.WHITE)
        if (bearOffRect(Player.BLACK).contains(x, y)) return BoardTarget.BearOff(Player.BLACK)
        for (i in 1..24) if (pointRect(i).contains(x, y)) return BoardTarget.Point(i)
        return null
    }
}
```
Note: the `colFromLeft` unused locals are illustrative; keep only `colFromBar`/`colFromLeft` actually used per branch. The test pins centers, so adjust constants until all 24 centers round-trip.

- [ ] **Step 4: Run test to verify it passes**

`./gradlew :app:testDebugUnitTest --tests "*BoardGeometryTest"` → PASS. (Tune `diceRect`/tray bounds if a center collides; dice band sits in the left half's vertical middle, away from point centers.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/BoardGeometry.kt app/src/test/java/dk/rlunde/backgammon/ui/board/BoardGeometryTest.kt
git commit -m "feat(app): add BoardGeometry layout + hitTest with boundary handling"
```

---

## Task 6: `BoardColors` + theme

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardColors.kt`
- Create: `app/src/main/java/dk/rlunde/backgammon/ui/theme/Theme.kt`

- [ ] **Step 1: Create `BoardColors.kt`**

```kotlin
package dk.rlunde.backgammon.ui.board

import androidx.compose.ui.graphics.Color

object BoardColors {
    val felt = Color(0xFF1F4E4A)
    val pointLight = Color(0xFFE8DCC0)
    val pointDark = Color(0xFF9C6B3F)
    val whiteChecker = Color(0xFFF4F1EA)
    val whiteRing = Color(0xFFC9C2B0)
    val blackChecker = Color(0xFF23262B)
    val blackRing = Color(0xFF3A3F47)
    val highlight = Color(0xFFE0A526)
    val bar = Color(0xFF153833)
}
```

- [ ] **Step 2: Create `ui/theme/Theme.kt`**

```kotlin
package dk.rlunde.backgammon.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

@Composable
fun BackgammonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
```

- [ ] **Step 3: Verify compile + commit**

`./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -5` → SUCCESS.
```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/BoardColors.kt app/src/main/java/dk/rlunde/backgammon/ui/theme/Theme.kt
git commit -m "feat(app): add BoardColors palette + Material theme"
```

---

## Task 7: `BoardCanvas` (draw + tap)

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt`

- [ ] **Step 1: Implement `BoardCanvas.kt`**

```kotlin
package dk.rlunde.backgammon.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.GameUiState

@Composable
fun BoardCanvas(state: GameUiState, onTap: (BoardTarget) -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val g = BoardGeometry(size.width.toFloat(), size.height.toFloat())
                    g.hitTest(offset.x, offset.y)?.let(onTap)
                }
            }
    ) {
        val g = BoardGeometry(size.width, size.height)
        drawBoard(g, state)
    }
}

private fun DrawScope.drawBoard(g: BoardGeometry, state: GameUiState) {
    drawRect(BoardColors.felt)
    // points
    for (i in 1..24) {
        val r = g.pointRect(i)
        val light = (i % 2 == 0)
        drawTriangle(r, if (light) BoardColors.pointLight else BoardColors.pointDark, pointingUp = i in 1..12)
    }
    // bar
    val bar = g.barRect()
    drawRect(BoardColors.bar, topLeft = Offset(bar.l, bar.t), size = androidx.compose.ui.geometry.Size(bar.r - bar.l, bar.b - bar.t))
    // checkers on points
    for (i in 1..24) {
        val w = state.board.count(Player.WHITE, i)
        val b = state.board.count(Player.BLACK, i)
        if (w > 0) drawStack(g.pointRect(i), w, Player.WHITE, i in 1..12)
        if (b > 0) drawStack(g.pointRect(i), b, Player.BLACK, i in 1..12)
    }
    // bar checkers
    drawBarCheckers(g, state.board.barCount(Player.WHITE), Player.WHITE)
    drawBarCheckers(g, state.board.barCount(Player.BLACK), Player.BLACK)
    // off trays
    drawTray(g.bearOffRect(Player.WHITE), state.board.offCount(Player.WHITE), Player.WHITE)
    drawTray(g.bearOffRect(Player.BLACK), state.board.offCount(Player.BLACK), Player.BLACK)
    // highlights: selected origin + destinations
    state.selectedOrigin?.let { highlightTarget(g, it) }
    state.destinations.forEach { highlightTarget(g, it) }
    // dice
    drawDice(g.diceRect(), state)
}

// --- small helpers (kept compact; tune radii/insets visually on the emulator) ---
private fun DrawScope.drawTriangle(r: BoardRect, color: androidx.compose.ui.graphics.Color, pointingUp: Boolean) {
    val path = androidx.compose.ui.graphics.Path().apply {
        if (pointingUp) { moveTo(r.l, r.b); lineTo(r.r, r.b); lineTo((r.l + r.r) / 2, r.t) }
        else { moveTo(r.l, r.t); lineTo(r.r, r.t); lineTo((r.l + r.r) / 2, r.b) }
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawStack(r: BoardRect, n: Int, player: Player, fromBottom: Boolean) {
    val radius = (r.r - r.l) / 2f * 0.9f
    val color = if (player == Player.WHITE) BoardColors.whiteChecker else BoardColors.blackChecker
    val ring = if (player == Player.WHITE) BoardColors.whiteRing else BoardColors.blackRing
    val shown = minOf(n, 5)
    for (k in 0 until shown) {
        val cy = if (fromBottom) r.b - radius - k * radius * 2 else r.t + radius + k * radius * 2
        drawCircle(ring, radius, Offset(r.cx, cy))
        drawCircle(color, radius * 0.82f, Offset(r.cx, cy))
    }
}

private fun DrawScope.drawBarCheckers(g: BoardGeometry, n: Int, player: Player) {
    if (n == 0) return
    val bar = g.barRect()
    val radius = (bar.r - bar.l) / 2f * 0.8f
    val baseY = if (player == Player.WHITE) bar.b - radius - bar.b * 0.1f else bar.t + radius + bar.t + bar.b * 0.0f + radius
    val color = if (player == Player.WHITE) BoardColors.whiteChecker else BoardColors.blackChecker
    drawCircle(color, radius, Offset(bar.cx, baseY))
}

private fun DrawScope.drawTray(r: BoardRect, n: Int, player: Player) {
    if (n == 0) return
    val color = if (player == Player.WHITE) BoardColors.whiteChecker else BoardColors.blackChecker
    val h = (r.b - r.t)
    for (k in 0 until n) {
        val y = r.t + 6f + k * (h / 16f)
        drawRect(color, topLeft = Offset(r.l + 6f, y), size = androidx.compose.ui.geometry.Size(r.r - r.l - 12f, h / 18f))
    }
}

private fun DrawScope.highlightTarget(g: BoardGeometry, t: BoardTarget) {
    val r = when (t) {
        is BoardTarget.Point -> g.pointRect(t.index)
        BoardTarget.Bar -> g.barRect()
        is BoardTarget.BearOff -> g.bearOffRect(t.player)
        BoardTarget.Dice -> g.diceRect()
    }
    drawRect(BoardColors.highlight.copy(alpha = 0.35f), topLeft = Offset(r.l, r.t),
        size = androidx.compose.ui.geometry.Size(r.r - r.l, r.b - r.t))
}

private fun DrawScope.drawDice(r: BoardRect, state: GameUiState) {
    val d = state.dice ?: return
    val faces = d.pips()
    val remaining = state.remainingDice.toMutableList()
    val side = (r.b - r.t) * 0.8f
    faces.forEachIndexed { idx, face ->
        val used = !remaining.remove(face)
        val x = r.l + 8f + idx * (side + 8f)
        val alpha = if (used) 0.35f else 1f
        drawRect(androidx.compose.ui.graphics.Color.White.copy(alpha = alpha),
            topLeft = Offset(x, r.cy - side / 2), size = androidx.compose.ui.geometry.Size(side, side))
        // pip count rendered as a simple centered number is added in GameScreen overlay if desired
    }
}
```
Note: this draws functional (not yet pretty) shapes; pip glyphs on dice and fan-out for >5 are visual polish to tune on the emulator (Task 10), not correctness.

- [ ] **Step 2: Verify compile + commit**

`./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -10` → SUCCESS.
```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/board/BoardCanvas.kt
git commit -m "feat(app): add BoardCanvas (Canvas draw + tap hit-testing)"
```

---

## Task 8: `GameScreen` (controls, banner, events)

**Files:**
- Create: `app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt`

- [ ] **Step 1: Implement `GameScreen.kt`**

```kotlin
package dk.rlunde.backgammon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.Phase
import dk.rlunde.backgammon.game.UiEvent
import dk.rlunde.backgammon.ui.board.BoardCanvas
import dk.rlunde.backgammon.viewmodel.GameViewModel

@Composable
fun GameScreen(vm: GameViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var showPass by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { if (it is UiEvent.NoLegalMoves) showPass = true }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Active-player banner + pip counts
        val turnLabel = if (state.toMove == Player.WHITE) "WHITE to move" else "BLACK to move"
        Text(text = if (state.phase == Phase.GAME_OVER) "Game over" else turnLabel, style = MaterialTheme.typography.titleLarge)
        Text("White pip ${state.whitePip}   •   Black pip ${state.blackPip}", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(8.dp))
        BoardCanvas(state = state, onTap = vm::onTap, modifier = Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.phase) {
                Phase.NEED_ROLL -> Button(onClick = vm::onRoll) { Text("Roll") }
                Phase.MOVING -> Button(onClick = vm::onUndo, enabled = true) { Text("Undo") }
                Phase.COMMITTABLE -> {
                    Button(onClick = vm::onUndo) { Text("Undo") }
                    Button(onClick = vm::onCommit) { Text("Commit") }
                }
                Phase.GAME_OVER -> Button(onClick = vm::onNewGame) { Text("New game") }
            }
        }

        if (state.phase == Phase.GAME_OVER && state.winner != null) {
            val v = when (state.winValue) { 3 -> "backgammon"; 2 -> "gammon"; else -> "single" }
            Text("${state.winner} wins ($v)", style = MaterialTheme.typography.titleMedium)
        }
    }

    if (showPass) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { TextButton(onClick = { showPass = false; vm.onAcknowledgePass() }) { Text("OK") } },
            title = { Text("No legal moves") },
            text = { Text("No legal moves for this roll — passing to the other player.") },
        )
    }
}
```

- [ ] **Step 2: Verify compile + commit**

`./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -10` → SUCCESS.
```bash
git add app/src/main/java/dk/rlunde/backgammon/ui/screens/GameScreen.kt
git commit -m "feat(app): add GameScreen (controls, banner, pass dialog)"
```

---

## Task 9: Wire `MainActivity` to `GameScreen`

**Files:**
- Modify: `app/src/main/java/dk/rlunde/backgammon/MainActivity.kt`

- [ ] **Step 1: Replace `MainActivity` body**

```kotlin
package dk.rlunde.backgammon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dk.rlunde.backgammon.ui.screens.GameScreen
import dk.rlunde.backgammon.ui.theme.BackgammonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BackgammonTheme { GameScreen() } }
    }
}
```

- [ ] **Step 2: Build, install, launch**

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
SDK="/c/Users/RasmusLundgaardHanse/AppData/Local/Android/Sdk"
./gradlew :app:installDebug --console=plain 2>&1 | tail -15
"$SDK/platform-tools/adb.exe" shell am start -n dk.rlunde.backgammon/.MainActivity
```
Expected: the board renders with the opening position, "WHITE to move", a Roll button.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dk/rlunde/backgammon/MainActivity.kt
git commit -m "feat(app): wire MainActivity to GameScreen"
```

---

## Task 10: Scripted manual smoke + final verification

**Files:** none (verification only). Optional: add a debug-only forced-roll seam if needed to script exact dice.

- [ ] **Step 1: Run the full JVM test suites**

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :core:test :app:testDebugUnitTest --console=plain 2>&1 | tail -15
```
Expected: all green — `:core` (55 + TurnPlanner) and `:app` (GameUiState, GameController, BoardGeometry).

- [ ] **Step 2: Scripted emulator smoke (acceptance)** — install, launch, and verify each, observing the stated pass criteria:

```
./gradlew :app:installDebug --console=plain 2>&1 | tail -5
"$SDK/platform-tools/adb.exe" shell am start -n dk.rlunde.backgammon/.MainActivity
```
Play a hot-seat game and confirm:
- **(a) Hit:** maneuver a checker onto a point holding exactly one opposing checker → the opposing checker appears on the central bar. PASS if it leaves the point and shows on the bar.
- **(b) Bar re-entry:** with a checker on the bar, on the next turn only entry destinations highlight (White → top-right 19–24, Black → bottom-right 1–6). PASS if no other move is offered until the bar is cleared.
- **(c) Bear-off:** bring all 15 of one side home, then bear off → the off-tray highlights only when legal and the checker moves to the tray. PASS on a legal bear-off.
- **(d) Auto-pass:** reach a position where a player has no legal move (e.g. closed out on the bar) → the "No legal moves" dialog appears and, on OK, the turn flips. PASS if the dialog shows and turn flips.
- **(e) Win banner:** play to completion → the banner shows the correct winner and single/gammon/backgammon. PASS if correct.

(If reaching these states by free play is slow, temporarily lower a side's checkers via a debug initial position in `GameController` to exercise bear-off/win quickly — revert before finishing.)

- [ ] **Step 3: Confirm `:core` Android-free + manifest offline**

```
./gradlew :core:dependencies --configuration compileClasspath 2>&1 | grep -i android || echo "no android deps in :core (good)"
grep -i "INTERNET" app/src/main/AndroidManifest.xml || echo "no INTERNET permission (good)"
```

- [ ] **Step 4: Commit any tuning** (visual insets, dice pips) made during the smoke

```bash
git add -A
git commit -m "polish(app): tune board rendering after emulator smoke"
```

---

## Self-review (completed by plan author)

**Spec coverage:** §2.1 toolchain → Task 0 (gated, compileSdk 35 + AGP 8.7.x, no wrapper bump → no `:core` regression risk; google() added). §3 TurnPlanner (multiset doubles, Map-by-origin, `internal` not leaked to `:app`, `applyPartial`) → Task 1. §5 GameController pure + GameViewModel adapter + GameUiState/Phase/UiEvent + injectable initial state + Channel events → Tasks 2–4. §4.1/§4.2 BoardGeometry (BoardRect, nullable hitTest, two trays, BearOff(player), Point require, continuous-path layout) → Tasks 2 & 5. §4.3 colors/theme/active-player banner/pip labels → Tasks 6, 8. Canvas → Task 7. MainActivity/manifest offline → Task 9. §7 tests (TurnPlanner, GameController incl. auto-pass+game-over+negative paths via injected positions, BoardGeometry boundaries, scripted smoke) → Tasks 1,3,5,10. §8 gates A/B → Task 0 & 10. No gaps.

**Placeholder scan:** Task 0 carries concrete versions with an explicit "adjust + record if resolution fails" loop (the spec-mandated empirical spike), not a TBD. Canvas visual fine-tuning (dice pips, >5 fan) is flagged as Task-10 polish, not correctness. No `TODO`/`implement later`.

**Type consistency:** `BoardTarget{Point(index),Bar,BearOff(player),Dice}`, `GameUiState` fields, `GameController.{roll,tap,undo,commit,newGame,acknowledgePass,uiState}`, `TurnPlanner.{maxUsablePips,legalNextSubMoves}`, `MoveGenerator.applyPartial`, `BoardGeometry.{pointRect,barRect,bearOffRect,diceRect,hitTest}` are used consistently across tasks. `legalNextSubMoves` returns `Map<Int,List<SubMove>>` everywhere. `remainingDice` is multiset throughout.
