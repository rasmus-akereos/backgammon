# Phase 0 — Core Rules Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the pure-Kotlin backgammon rules engine (`:core` module) with a full, deterministic JVM unit-test suite, per `docs/superpowers/specs/2026-06-10-phase0-engine-design.md`.

**Architecture:** A single Gradle `kotlin("jvm")` module, `:core`, with zero Android dependencies. Immutable domain types (`BoardState`, `Dice`, `Move`/`SubMove`), a stateless `MoveGenerator` (3-stage legal-move enumeration + `apply`/`pass`), and a `Scoring` object (win/gammon/backgammon detection + pip count). Everything is exercised by JUnit 5 tests run via `gradlew :core:test`.

**Tech Stack:** Kotlin 2.x, JVM 17, Gradle (Kotlin DSL), JUnit 5, `kotlin.test`.

---

## Board & coordinate conventions (read once — every task assumes these)

- `points: IntArray` size 26. Indices `1..24` are points; `0` and `25` are sentinels.
- Sign: `+n` = n WHITE checkers on a point, `-n` = n BLACK checkers, `0` = empty.
- WHITE moves high→low, home = `1..6`, bears off toward `0`.
- BLACK moves low→high, home = `19..24`, bears off toward `25`.
- **Per-player helpers** (used pervasively):
  - sign: WHITE `+1`, BLACK `-1`.
  - normal destination of die `d` from point `p`: WHITE `p - d`, BLACK `p + d`.
  - bar-entry point for die `d`: WHITE `25 - d` (→19..24), BLACK `d` (→1..6).
  - off sentinel: WHITE `0`, BLACK `25`. bar `from`-sentinel: WHITE `25`, BLACK `0`.
  - distance-to-off for a checker on point `p`: WHITE `p`, BLACK `25 - p`.
  - home range: WHITE `1..6`, BLACK `19..24`.
- A point is **blocked** for the mover if it holds ≥2 opponent checkers. Landing on exactly 1
  opponent checker is a **hit** (blot → bar).

---

## File structure

All under `core/src/main/kotlin/dk/rlunde/backgammon/core/` (tests mirror under
`core/src/test/kotlin/dk/rlunde/backgammon/core/`):

| File | Responsibility | Public API |
|---|---|---|
| `Player.kt` | The two players + per-player direction helpers | `enum class Player { WHITE, BLACK }`; `Player.opponent`, `Player.sign` |
| `Dice.kt` | Immutable dice value | `data class Dice(a,b)` with `isDouble`, `pips()` |
| `DiceRoller.kt` | Dice sources | `interface DiceRoller`; `SeededDiceRoller(seed)`; `RandomDiceRoller` |
| `Move.kt` | One die used / a full turn | `data class SubMove(from,to,die,isHit)`; `data class Move(subMoves)` |
| `BoardState.kt` | The board, bar, off, side to move | `data class BoardState(points,bar,off,toMove)` + custom `equals`/`hashCode` + small read helpers |
| `StartingPosition.kt` | Canonical opening position | `fun startingPosition(): BoardState` |
| `MoveGenerator.kt` | Legal-move generation + state transitions | `object MoveGenerator { legalMoves; apply; pass }` + `internal`/`private` stage fns |
| `Scoring.kt` | Terminal-state scoring + metrics | `object Scoring { isGameOver; winnerAndValue; pipCount }` |

---

## Task 0: Gradle scaffold + smoke test

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `core/build.gradle.kts`, `.gitignore`
- Create: `core/src/test/kotlin/dk/rlunde/backgammon/core/SmokeTest.kt`
- Generate: `gradlew`, `gradlew.bat`, `gradle/wrapper/*`

- [ ] **Step 1: Create `.gitignore`**

```gitignore
.gradle/
build/
*.iml
.idea/
local.properties
```

- [ ] **Step 2: Create `gradle/libs.versions.toml`**

```toml
[versions]
kotlin = "2.0.21"
junit = "5.11.3"

[libraries]
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

- [ ] **Step 3: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "backgammon"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include(":core")
```

- [ ] **Step 4: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
```

- [ ] **Step 5: Create `core/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-ea") // enable assertions so check()/require() invariants fire under test
}
```

- [ ] **Step 6: Create the smoke test** `core/src/test/kotlin/dk/rlunde/backgammon/core/SmokeTest.kt`

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun `gradle and junit are wired up`() {
        assertEquals(4, 2 + 2)
    }
}
```

- [ ] **Step 7: Generate the Gradle wrapper**

Run: `gradle wrapper --gradle-version 8.10`
Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.
If `gradle` is not installed: install it first (`choco install gradle` or `scoop install gradle`, or download from gradle.org and add to PATH), then re-run.

- [ ] **Step 8: Run the smoke test**

Run: `.\gradlew.bat :core:test`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 9: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle core
git commit -m "chore: scaffold Gradle :core module + smoke test"
```

---

## Task 1: `Player`

**Files:**
- Create: `core/src/main/kotlin/dk/rlunde/backgammon/core/Player.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/PlayerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerTest {
    @Test fun `opponent flips`() {
        assertEquals(Player.BLACK, Player.WHITE.opponent)
        assertEquals(Player.WHITE, Player.BLACK.opponent)
    }

    @Test fun `sign is +1 for white and -1 for black`() {
        assertEquals(1, Player.WHITE.sign)
        assertEquals(-1, Player.BLACK.sign)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "*PlayerTest"`
Expected: FAIL — `Player` unresolved.

- [ ] **Step 3: Implement `Player.kt`**

```kotlin
package dk.rlunde.backgammon.core

enum class Player {
    WHITE, BLACK;

    val opponent: Player get() = if (this == WHITE) BLACK else WHITE

    /** +1 = WHITE checkers stored as positive; -1 = BLACK stored as negative. */
    val sign: Int get() = if (this == WHITE) 1 else -1
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :core:test --tests "*PlayerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/Player.kt core/src/test/kotlin/dk/rlunde/backgammon/core/PlayerTest.kt
git commit -m "feat(core): add Player enum with opponent/sign"
```

---

## Task 2: `Dice`

**Files:**
- Create: `core/src/main/kotlin/dk/rlunde/backgammon/core/Dice.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/DiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiceTest {
    @Test fun `non-double yields two pips`() {
        assertEquals(listOf(3, 5), Dice(3, 5).pips())
        assertFalse(Dice(3, 5).isDouble)
    }

    @Test fun `double yields four pips`() {
        assertEquals(listOf(4, 4, 4, 4), Dice(4, 4).pips())
        assertTrue(Dice(4, 4).isDouble)
    }

    @Test fun `die values outside 1-6 are rejected`() {
        assertFailsWith<IllegalArgumentException> { Dice(0, 3) }
        assertFailsWith<IllegalArgumentException> { Dice(3, 7) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "*DiceTest"`
Expected: FAIL — `Dice` unresolved.

- [ ] **Step 3: Implement `Dice.kt`**

```kotlin
package dk.rlunde.backgammon.core

data class Dice(val a: Int, val b: Int) {
    init { require(a in 1..6 && b in 1..6) { "die values must be 1..6, got ($a,$b)" } }

    val isDouble: Boolean get() = a == b

    /** The pips available this turn: two for a normal roll, four for a double. */
    fun pips(): List<Int> = if (isDouble) List(4) { a } else listOf(a, b)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :core:test --tests "*DiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/Dice.kt core/src/test/kotlin/dk/rlunde/backgammon/core/DiceTest.kt
git commit -m "feat(core): add Dice value type with 1..6 validation"
```

---

## Task 3: `DiceRoller` (seeded + random)

**Files:**
- Create: `core/src/main/kotlin/dk/rlunde/backgammon/core/DiceRoller.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/DiceRollerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiceRollerTest {
    @Test fun `same seed produces identical sequence`() {
        val a = SeededDiceRoller(42)
        val b = SeededDiceRoller(42)
        val seqA = List(20) { a.roll() }
        val seqB = List(20) { b.roll() }
        assertEquals(seqA, seqB)
    }

    @Test fun `different seeds usually differ`() {
        val seqA = SeededDiceRoller(1).let { r -> List(20) { r.roll() } }
        val seqB = SeededDiceRoller(2).let { r -> List(20) { r.roll() } }
        assertTrue(seqA != seqB)
    }

    @Test fun `all rolled die values are in 1-6`() {
        val r = SeededDiceRoller(7)
        repeat(200) {
            val d = r.roll()
            assertTrue(d.a in 1..6 && d.b in 1..6)
        }
        // RandomDiceRoller also only ever produces valid Dice (construction would throw otherwise)
        repeat(200) {
            val d = RandomDiceRoller().roll()
            assertTrue(d.a in 1..6 && d.b in 1..6)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "*DiceRollerTest"`
Expected: FAIL — `SeededDiceRoller` unresolved.

- [ ] **Step 3: Implement `DiceRoller.kt`**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.random.Random

interface DiceRoller {
    fun roll(): Dice
}

/** Deterministic, reproducible roller — the backbone of tests and the self-play harness. */
class SeededDiceRoller(seed: Long) : DiceRoller {
    private val random = Random(seed)
    override fun roll(): Dice = Dice(random.nextInt(1, 7), random.nextInt(1, 7))
}

/** System-random roller for real play. Included so the DiceRoller interface has a 2nd impl. */
class RandomDiceRoller : DiceRoller {
    override fun roll(): Dice = Dice(Random.nextInt(1, 7), Random.nextInt(1, 7))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :core:test --tests "*DiceRollerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/DiceRoller.kt core/src/test/kotlin/dk/rlunde/backgammon/core/DiceRollerTest.kt
git commit -m "feat(core): add DiceRoller with seeded + random impls"
```

---

## Task 4: `Move` / `SubMove`

**Files:**
- Create: `core/src/main/kotlin/dk/rlunde/backgammon/core/Move.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/MoveTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MoveTest {
    @Test fun `move carries its ordered sub-moves and total pips`() {
        val m = Move(listOf(
            SubMove(from = 13, to = 10, die = 3, isHit = false),
            SubMove(from = 24, to = 19, die = 5, isHit = true),
        ))
        assertEquals(2, m.subMoves.size)
        assertEquals(8, m.pipsUsed)
        assertEquals(5, m.subMoves[1].die)
    }

    @Test fun `empty move uses zero pips`() {
        assertEquals(0, Move(emptyList()).pipsUsed)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "*MoveTest"`
Expected: FAIL — `Move`/`SubMove` unresolved.

- [ ] **Step 3: Implement `Move.kt`**

```kotlin
package dk.rlunde.backgammon.core

/**
 * A single checker movement using one die.
 * [from]/[to] use board indices 1..24, plus sentinels:
 *   off  -> 0 (WHITE) / 25 (BLACK);  bar -> from = 25 (WHITE) / 0 (BLACK).
 * [isHit] is true when [to] held exactly one opponent checker (a blot).
 */
data class SubMove(val from: Int, val to: Int, val die: Int, val isHit: Boolean)

/** A complete legal turn: an ordered list of sub-moves. */
data class Move(val subMoves: List<SubMove>) {
    /** Sum of die FACE values played — the metric for the max-pips rule. */
    val pipsUsed: Int get() = subMoves.sumOf { it.die }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :core:test --tests "*MoveTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/Move.kt core/src/test/kotlin/dk/rlunde/backgammon/core/MoveTest.kt
git commit -m "feat(core): add SubMove/Move with pipsUsed"
```

---

## Task 5: `BoardState` (value equality + helpers)

**Files:**
- Create: `core/src/main/kotlin/dk/rlunde/backgammon/core/BoardState.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/BoardStateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BoardStateTest {
    private fun emptyBoard(toMove: Player = Player.WHITE) = BoardState(
        points = IntArray(26),
        bar = mapOf(Player.WHITE to 0, Player.BLACK to 0),
        off = mapOf(Player.WHITE to 0, Player.BLACK to 0),
        toMove = toMove,
    )

    @Test fun `equality is by content, not array reference`() {
        val a = emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 5 }) }
        val b = emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 5 }) }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun `differing points compare unequal`() {
        val a = emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 5 }) }
        val b = emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 4 }) }
        assertNotEquals(a, b)
    }

    @Test fun `differing toMove compare unequal`() {
        assertNotEquals(emptyBoard(Player.WHITE), emptyBoard(Player.BLACK))
    }

    @Test fun `usable as a HashSet key`() {
        val set = hashSetOf(
            emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 5 }) },
        )
        val same = emptyBoard().let { it.copy(points = it.points.copyOf().also { p -> p[6] = 5 }) }
        assertTrue(set.contains(same))
    }

    @Test fun `read helpers expose bar and off as non-null ints`() {
        val s = emptyBoard().copy(bar = mapOf(Player.WHITE to 2, Player.BLACK to 0))
        assertEquals(2, s.barCount(Player.WHITE))
        assertEquals(0, s.offCount(Player.BLACK))
        assertEquals(5, s.copy(points = s.points.copyOf().also { it[6] = 5 }).count(Player.WHITE, 6))
        assertFalse(s.isBlockedFor(Player.WHITE, 6)) // empty point not blocked
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "*BoardStateTest"`
Expected: FAIL — `BoardState` unresolved.

- [ ] **Step 3: Implement `BoardState.kt`**

```kotlin
package dk.rlunde.backgammon.core

/**
 * The full game state. [points] is size 26 (indices 1..24 are points; 0 and 25 are sentinels).
 * Positive = WHITE checkers, negative = BLACK. [bar]/[off] are ALWAYS total over Player
 * (both keys present). The [points] array is treated as deeply immutable — never mutated in
 * place — which is what makes BoardState safe as a HashSet/HashMap key.
 */
data class BoardState(
    val points: IntArray,
    val bar: Map<Player, Int>,
    val off: Map<Player, Int>,
    val toMove: Player,
) {
    fun barCount(player: Player): Int = bar.getValue(player)
    fun offCount(player: Player): Int = off.getValue(player)

    /** Number of [player]'s checkers on board point [index] (always >= 0). */
    fun count(player: Player, index: Int): Int {
        val v = points[index]
        return if (player == Player.WHITE) maxOf(v, 0) else maxOf(-v, 0)
    }

    /** A point is blocked for [player] if the opponent has >= 2 checkers there. */
    fun isBlockedFor(player: Player, index: Int): Boolean = count(player.opponent, index) >= 2

    /** A point is a blot for [player] to hit if the opponent has exactly 1 checker there. */
    fun isBlotFor(player: Player, index: Int): Boolean = count(player.opponent, index) == 1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoardState) return false
        return points.contentEquals(other.points) &&
            bar == other.bar &&
            off == other.off &&
            toMove == other.toMove
    }

    override fun hashCode(): Int {
        var result = points.contentHashCode()
        result = 31 * result + bar.hashCode()
        result = 31 * result + off.hashCode()
        result = 31 * result + toMove.hashCode()
        return result
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :core:test --tests "*BoardStateTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/BoardState.kt core/src/test/kotlin/dk/rlunde/backgammon/core/BoardStateTest.kt
git commit -m "feat(core): add BoardState with content equality + read helpers"
```

---

## Task 6: `StartingPosition`

**Files:**
- Create: `core/src/main/kotlin/dk/rlunde/backgammon/core/StartingPosition.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/StartingPositionTest.kt`

The canonical layout, from WHITE's perspective (WHITE positive): 2 on 24, 5 on 13, 3 on 8,
5 on 6. BLACK is the mirror (negative): 2 on 1, 5 on 12, 3 on 17, 5 on 19.

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StartingPositionTest {
    @Test fun `canonical opening layout`() {
        val s = startingPosition()
        val expected = IntArray(26)
        expected[24] = 2; expected[13] = 5; expected[8] = 3; expected[6] = 5   // WHITE +
        expected[1] = -2; expected[12] = -5; expected[17] = -3; expected[19] = -5 // BLACK -
        assertEquals(expected.toList(), s.points.toList())
    }

    @Test fun `15 checkers per side, empty bar and off, white to move`() {
        val s = startingPosition()
        val white = (1..24).sumOf { s.count(Player.WHITE, it) }
        val black = (1..24).sumOf { s.count(Player.BLACK, it) }
        assertEquals(15, white)
        assertEquals(15, black)
        assertEquals(0, s.barCount(Player.WHITE))
        assertEquals(0, s.barCount(Player.BLACK))
        assertEquals(0, s.offCount(Player.WHITE))
        assertEquals(0, s.offCount(Player.BLACK))
        assertEquals(Player.WHITE, s.toMove)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "*StartingPositionTest"`
Expected: FAIL — `startingPosition` unresolved.

- [ ] **Step 3: Implement `StartingPosition.kt`**

```kotlin
package dk.rlunde.backgammon.core

/** The standard backgammon opening position, WHITE to move. */
fun startingPosition(): BoardState {
    val points = IntArray(26)
    // WHITE (+): 2 on 24, 5 on 13, 3 on 8, 5 on 6
    points[24] = 2; points[13] = 5; points[8] = 3; points[6] = 5
    // BLACK (-): mirror — 2 on 1, 5 on 12, 3 on 17, 5 on 19
    points[1] = -2; points[12] = -5; points[17] = -3; points[19] = -5
    return BoardState(
        points = points,
        bar = mapOf(Player.WHITE to 0, Player.BLACK to 0),
        off = mapOf(Player.WHITE to 0, Player.BLACK to 0),
        toMove = Player.WHITE,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :core:test --tests "*StartingPositionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/StartingPosition.kt core/src/test/kotlin/dk/rlunde/backgammon/core/StartingPositionTest.kt
git commit -m "feat(core): add canonical startingPosition"
```

---

## Task 7: `Scoring.pipCount`

**Files:**
- Create: `core/src/main/kotlin/dk/rlunde/backgammon/core/Scoring.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/PipCountTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PipCountTest {
    private fun board(points: IntArray, bar: Map<Player, Int> = mapOf(Player.WHITE to 0, Player.BLACK to 0)) =
        BoardState(points, bar, mapOf(Player.WHITE to 0, Player.BLACK to 0), Player.WHITE)

    @Test fun `starting position is 167 per side`() {
        val s = startingPosition()
        assertEquals(167, Scoring.pipCount(s, Player.WHITE))
        assertEquals(167, Scoring.pipCount(s, Player.BLACK))
    }

    @Test fun `white counts point index, black counts 25 minus index`() {
        val p = IntArray(26)
        p[6] = 1    // WHITE on 6  -> 6 pips
        p[19] = -1  // BLACK on 19 -> 25-19 = 6 pips
        val s = board(p)
        assertEquals(6, Scoring.pipCount(s, Player.WHITE))
        assertEquals(6, Scoring.pipCount(s, Player.BLACK))
    }

    @Test fun `bar checkers count as 25 each`() {
        val p = IntArray(26)
        p[6] = 1 // 6 pips on board
        val s = board(p, bar = mapOf(Player.WHITE to 2, Player.BLACK to 0))
        assertEquals(6 + 2 * 25, Scoring.pipCount(s, Player.WHITE))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "*PipCountTest"`
Expected: FAIL — `Scoring` unresolved.

- [ ] **Step 3: Implement `Scoring.kt` (pipCount only for now)**

```kotlin
package dk.rlunde.backgammon.core

object Scoring {
    /**
     * Standard pip count: WHITE checker on point p contributes p; BLACK contributes 25 - p;
     * a checker on the bar contributes 25 for either player.
     */
    fun pipCount(state: BoardState, player: Player): Int {
        var total = state.barCount(player) * 25
        for (p in 1..24) {
            val n = state.count(player, p)
            if (n > 0) total += n * if (player == Player.WHITE) p else 25 - p
        }
        return total
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :core:test --tests "*PipCountTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/Scoring.kt core/src/test/kotlin/dk/rlunde/backgammon/core/PipCountTest.kt
git commit -m "feat(core): add Scoring.pipCount with per-player direction"
```

---

## Task 8: `Scoring.isGameOver` + `winnerAndValue`

**Files:**
- Modify: `core/src/main/kotlin/dk/rlunde/backgammon/core/Scoring.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/WinScoringTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WinScoringTest {
    private fun board(
        points: IntArray,
        whiteOff: Int = 0, blackOff: Int = 0,
        whiteBar: Int = 0, blackBar: Int = 0,
        toMove: Player = Player.WHITE,
    ) = BoardState(
        points,
        bar = mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
        off = mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff),
        toMove,
    )

    @Test fun `not over while both have checkers in play`() {
        assertFalse(Scoring.isGameOver(startingPosition()))
        assertNull(Scoring.winnerAndValue(startingPosition()))
    }

    @Test fun `single win - loser has borne off at least one`() {
        // WHITE off 15; BLACK off 3, remaining 12 on point 19
        val p = IntArray(26); p[19] = -12
        val s = board(p, whiteOff = 15, blackOff = 3)
        assertTrue(Scoring.isGameOver(s))
        assertEquals(Player.WHITE to 1, Scoring.winnerAndValue(s))
    }

    @Test fun `gammon - loser borne off none, no checker on bar or in winner home`() {
        // WHITE wins (off 15). BLACK 15 checkers all on point 19 (NOT in WHITE home 1..6, not on bar)
        val p = IntArray(26); p[19] = -15
        val s = board(p, whiteOff = 15)
        assertEquals(Player.WHITE to 2, Scoring.winnerAndValue(s))
    }

    @Test fun `backgammon - loser borne off none with a checker in winner home (white wins)`() {
        // WHITE wins. BLACK has a checker on point 3 (inside WHITE home 1..6)
        val p = IntArray(26); p[3] = -1; p[19] = -14
        val s = board(p, whiteOff = 15)
        assertEquals(Player.WHITE to 3, Scoring.winnerAndValue(s))
    }

    @Test fun `backgammon - loser borne off none with a checker on the bar`() {
        val p = IntArray(26); p[19] = -14
        val s = board(p, whiteOff = 15, blackBar = 1)
        assertEquals(Player.WHITE to 3, Scoring.winnerAndValue(s))
    }

    @Test fun `backgammon - black wins, white checker in black home 19..24`() {
        // BLACK wins (off 15). WHITE has a checker on point 22 (inside BLACK home 19..24)
        val p = IntArray(26); p[22] = 1; p[6] = 14
        val s = board(p, blackOff = 15, toMove = Player.BLACK)
        assertEquals(Player.BLACK to 3, Scoring.winnerAndValue(s))
    }

    @Test fun `near-miss - loser in winner home but borne off one is only single`() {
        val p = IntArray(26); p[3] = -1; p[19] = -13
        val s = board(p, whiteOff = 15, blackOff = 1)
        assertEquals(Player.WHITE to 1, Scoring.winnerAndValue(s))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "*WinScoringTest"`
Expected: FAIL — `isGameOver`/`winnerAndValue` unresolved.

- [ ] **Step 3: Extend `Scoring.kt`**

Add these to the `Scoring` object (alongside `pipCount`):

```kotlin
    fun isGameOver(state: BoardState): Boolean =
        state.offCount(Player.WHITE) == 15 || state.offCount(Player.BLACK) == 15

    /**
     * Non-null exactly when the game is over. Returns (winner, multiplier):
     *   1 = single (loser borne off >= 1),
     *   2 = gammon (loser borne off none),
     *   3 = backgammon (loser borne off none AND has a checker on the bar
     *        OR in the WINNER's home board: 1..6 if winner WHITE, 19..24 if winner BLACK).
     */
    fun winnerAndValue(state: BoardState): Pair<Player, Int>? {
        val whiteWon = state.offCount(Player.WHITE) == 15
        val blackWon = state.offCount(Player.BLACK) == 15
        check(!(whiteWon && blackWon)) { "both players cannot bear off all 15" }
        if (!whiteWon && !blackWon) return null

        val winner = if (whiteWon) Player.WHITE else Player.BLACK
        val loser = winner.opponent
        if (state.offCount(loser) > 0) return winner to 1

        val winnerHome = if (winner == Player.WHITE) 1..6 else 19..24
        val loserInWinnerHome = winnerHome.any { state.count(loser, it) > 0 }
        val multiplier = if (state.barCount(loser) > 0 || loserInWinnerHome) 3 else 2
        return winner to multiplier
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :core:test --tests "*WinScoringTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/Scoring.kt core/src/test/kotlin/dk/rlunde/backgammon/core/WinScoringTest.kt
git commit -m "feat(core): add isGameOver + winnerAndValue (single/gammon/backgammon)"
```

---

## Task 9: `MoveGenerator.apply` + `pass` (single-submove fold)

This builds state transitions first; move *generation* (Task 10–12) reuses the private
`applySubMove`. `apply` validates the 15-checker invariant and never aliases the input array.

**Files:**
- Create: `core/src/main/kotlin/dk/rlunde/backgammon/core/MoveGenerator.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/ApplyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ApplyTest {
    private fun board(
        points: IntArray,
        whiteBar: Int = 0, blackBar: Int = 0,
        whiteOff: Int = 0, blackOff: Int = 0,
        toMove: Player = Player.WHITE,
    ) = BoardState(
        points,
        bar = mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
        off = mapOf(Player.WHITE to whiteOff, Player.BLACK to blackOff),
        toMove,
    )

    @Test fun `simple white move relocates a checker and flips toMove`() {
        val p = IntArray(26); p[13] = 5
        val s = board(p)
        val out = MoveGenerator.apply(s, Move(listOf(SubMove(13, 10, 3, isHit = false))))
        assertEquals(4, out.count(Player.WHITE, 13))
        assertEquals(1, out.count(Player.WHITE, 10))
        assertEquals(Player.BLACK, out.toMove)
    }

    @Test fun `hit sends the opponent blot to the bar`() {
        val p = IntArray(26); p[13] = 1; p[10] = -1 // WHITE on 13, BLACK blot on 10
        val s = board(p)
        val out = MoveGenerator.apply(s, Move(listOf(SubMove(13, 10, 3, isHit = true))))
        assertEquals(1, out.count(Player.WHITE, 10))
        assertEquals(0, out.count(Player.BLACK, 10))
        assertEquals(1, out.barCount(Player.BLACK))
    }

    @Test fun `white enters from the bar`() {
        val p = IntArray(26)
        val s = board(p, whiteBar = 1)
        // die 6 -> WHITE enters on 25-6 = 19
        val out = MoveGenerator.apply(s, Move(listOf(SubMove(from = 25, to = 19, die = 6, isHit = false))))
        assertEquals(0, out.barCount(Player.WHITE))
        assertEquals(1, out.count(Player.WHITE, 19))
    }

    @Test fun `white bears off`() {
        val p = IntArray(26); p[3] = 1
        val s = board(p, whiteOff = 14)
        val out = MoveGenerator.apply(s, Move(listOf(SubMove(from = 3, to = 0, die = 3, isHit = false))))
        assertEquals(15, out.offCount(Player.WHITE))
        assertEquals(0, out.count(Player.WHITE, 3))
    }

    @Test fun `apply does not mutate or alias the input`() {
        val p = IntArray(26); p[13] = 5
        val s = board(p)
        val before = s.points.toList()
        val out = MoveGenerator.apply(s, Move(listOf(SubMove(13, 10, 3, isHit = false))))
        assertEquals(before, s.points.toList())          // input unchanged
        assertNotSame(s.points, out.points)              // no array aliasing
    }

    @Test fun `pass flips toMove and changes nothing else`() {
        val s = startingPosition()
        val out = MoveGenerator.pass(s)
        assertEquals(Player.BLACK, out.toMove)
        assertEquals(s.points.toList(), out.points.toList())
        assertTrue(s.bar == out.bar && s.off == out.off)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "*ApplyTest"`
Expected: FAIL — `MoveGenerator` unresolved.

- [ ] **Step 3: Implement `MoveGenerator.kt` (apply/pass + private helpers)**

```kotlin
package dk.rlunde.backgammon.core

/** Stateless rules engine: legal-move generation and state transitions. Thread-safe by statelessness. */
object MoveGenerator {

    // ---- sentinels & per-player geometry -------------------------------------------------
    private fun offSentinel(player: Player) = if (player == Player.WHITE) 0 else 25
    private fun barFrom(player: Player) = if (player == Player.WHITE) 25 else 0
    private fun entryPoint(player: Player, die: Int) = if (player == Player.WHITE) 25 - die else die
    private fun normalTo(player: Player, from: Int, die: Int) =
        if (player == Player.WHITE) from - die else from + die
    private fun homeRange(player: Player) = if (player == Player.WHITE) 1..6 else 19..24
    private fun distToOff(player: Player, point: Int) = if (player == Player.WHITE) point else 25 - point

    // ---- state transitions ----------------------------------------------------------------

    /** Flip the side to move; used to advance the turn on a forced pass (empty legal-move set). */
    fun pass(state: BoardState): BoardState = state.copy(toMove = state.toMove.opponent)

    /** Apply a complete legal turn, returning a NEW state. Input is never mutated or aliased. */
    fun apply(state: BoardState, move: Move): BoardState {
        var current = state
        for (sm in move.subMoves) current = applySubMove(current, sm)
        val flipped = current.copy(toMove = current.toMove.opponent)
        check(conserves15(flipped)) { "checker count invariant violated by $move" }
        return flipped
    }

    /** Apply ONE sub-move for state.toMove. Allocates a fresh points array (copy-on-write). */
    private fun applySubMove(state: BoardState, sm: SubMove): BoardState {
        val mover = state.toMove
        val points = state.points.copyOf()
        val bar = state.bar.toMutableMap()
        val off = state.off.toMutableMap()

        // remove the moving checker from its origin
        if (sm.from == barFrom(mover) && sm.from !in 1..24) {
            bar[mover] = bar.getValue(mover) - 1
        } else {
            points[sm.from] -= mover.sign
        }

        // place it at its destination
        if (sm.to == offSentinel(mover) && sm.to !in 1..24) {
            off[mover] = off.getValue(mover) + 1
        } else {
            if (sm.isHit) {
                points[sm.to] = 0                      // remove the single opponent checker
                bar[mover.opponent] = bar.getValue(mover.opponent) + 1
            }
            points[sm.to] += mover.sign
        }

        return BoardState(points, bar, off, mover) // toMove unchanged here; apply() flips once at end
    }

    private fun conserves15(state: BoardState): Boolean = Player.entries.all { player ->
        val onBoard = (1..24).sumOf { state.count(player, it) }
        onBoard + state.barCount(player) + state.offCount(player) == 15
    }
}
```

Note: `barFrom(WHITE)=25` and `offSentinel(BLACK)=25` overlap, so the guards also check
`!in 1..24` together with the sentinel match; since 0 and 25 are never valid board points the
combined guard is unambiguous for the moving player's own bar/off.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :core:test --tests "*ApplyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/MoveGenerator.kt core/src/test/kotlin/dk/rlunde/backgammon/core/ApplyTest.kt
git commit -m "feat(core): add MoveGenerator.apply + pass with no-alias copy-on-write"
```

---

## Task 10: Single-die legality + Stage 1 enumeration

Add the private `legalSubMovesFor(state, die)` helper (bar-first, blocked, hit, normal,
bear-off) and `enumerateSequences`. We test them through a temporary `internal` entry point.

**Files:**
- Modify: `core/src/main/kotlin/dk/rlunde/backgammon/core/MoveGenerator.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/EnumerationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnumerationTest {
    private fun board(
        points: IntArray, whiteBar: Int = 0, blackBar: Int = 0, toMove: Player = Player.WHITE,
    ) = BoardState(
        points,
        bar = mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
        off = mapOf(Player.WHITE to 0, Player.BLACK to 0),
        toMove,
    )

    @Test fun `single-die normal moves for white`() {
        val p = IntArray(26); p[13] = 2
        val subs = MoveGenerator.legalSubMovesForTest(board(p), 3)
        assertEquals(1, subs.size)
        assertEquals(SubMove(13, 10, 3, isHit = false), subs.single())
    }

    @Test fun `blocked destination yields no sub-move`() {
        val p = IntArray(26); p[13] = 1; p[10] = -2 // BLACK owns 10
        val subs = MoveGenerator.legalSubMovesForTest(board(p), 3)
        assertTrue(subs.isEmpty())
    }

    @Test fun `landing on a blot is flagged as a hit`() {
        val p = IntArray(26); p[13] = 1; p[10] = -1
        val subs = MoveGenerator.legalSubMovesForTest(board(p), 3)
        assertEquals(SubMove(13, 10, 3, isHit = true), subs.single())
    }

    @Test fun `bar-first - only entry sub-moves while on the bar`() {
        val p = IntArray(26); p[13] = 2
        val s = board(p, whiteBar = 1)
        val subs = MoveGenerator.legalSubMovesForTest(s, 6) // enter on 25-6 = 19
        assertEquals(SubMove(from = 25, to = 19, die = 6, isHit = false), subs.single())
    }

    @Test fun `bar entry blocked - no sub-moves`() {
        val p = IntArray(26); p[19] = -2 // BLACK blocks 19
        val s = board(p, whiteBar = 1)
        assertTrue(MoveGenerator.legalSubMovesForTest(s, 6).isEmpty())
    }

    @Test fun `exact bear-off for white`() {
        val p = IntArray(26); p[3] = 1; p[1] = 1 // all home
        val subs = MoveGenerator.legalSubMovesForTest(board(p), 3)
        assertTrue(subs.contains(SubMove(from = 3, to = 0, die = 3, isHit = false)))
    }

    @Test fun `overflow bear-off only from the farthest point`() {
        val p = IntArray(26); p[4] = 1; p[3] = 1 // all home, highest is 4
        val subs = MoveGenerator.legalSubMovesForTest(board(p), 5) // 5 > 4 -> bear off the 4
        assertTrue(subs.contains(SubMove(from = 4, to = 0, die = 5, isHit = false)))
        // must NOT bear off the 3-point with the 5 (a higher checker exists at 4)
        assertTrue(subs.none { it.from == 3 && it.to == 0 })
    }

    @Test fun `enumerate produces full turns using both dice`() {
        val p = IntArray(26); p[13] = 2
        val seqs = MoveGenerator.enumerateSequencesForTest(board(p), listOf(3, 5))
        // 13/10/5 and 13/8 then 13/10 etc. — every complete sequence uses 2 sub-moves here
        assertTrue(seqs.all { it.size == 2 })
        assertTrue(seqs.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "*EnumerationTest"`
Expected: FAIL — helpers unresolved.

- [ ] **Step 3: Add legality + enumeration to `MoveGenerator.kt`**

Add inside the `MoveGenerator` object:

```kotlin
    // ---- single-die legality ---------------------------------------------------------------

    /** All legal single-die sub-moves for state.toMove using [die], honouring bar-first. */
    private fun legalSubMovesFor(state: BoardState, die: Int): List<SubMove> {
        val mover = state.toMove

        // Bar-first: if any checker is on the bar, the ONLY legal sub-move is an entry.
        if (state.barCount(mover) > 0) {
            val to = entryPoint(mover, die)
            return if (state.isBlockedFor(mover, to)) {
                emptyList()
            } else {
                listOf(SubMove(barFrom(mover), to, die, isHit = state.isBlotFor(mover, to)))
            }
        }

        val subs = mutableListOf<SubMove>()

        // Normal moves: any of the mover's checkers to an on-board destination.
        for (from in 1..24) {
            if (state.count(mover, from) == 0) continue
            val to = normalTo(mover, from, die)
            if (to in 1..24 && !state.isBlockedFor(mover, to)) {
                subs.add(SubMove(from, to, die, isHit = state.isBlotFor(mover, to)))
            }
        }

        // Bear-off: only when all 15 are home.
        if (allHome(state, mover)) {
            val occupied = homeRange(mover).filter { state.count(mover, it) > 0 }
            val maxDist = occupied.maxOfOrNull { distToOff(mover, it) } ?: 0
            for (from in occupied) {
                val dist = distToOff(mover, from)
                val exact = dist == die
                val overflow = dist == maxDist && die > dist
                if (exact || overflow) {
                    subs.add(SubMove(from, offSentinel(mover), die, isHit = false))
                }
            }
        }
        return subs
    }

    private fun allHome(state: BoardState, player: Player): Boolean {
        if (state.barCount(player) > 0) return false
        val outside = if (player == Player.WHITE) 7..24 else 1..18
        return outside.all { state.count(player, it) == 0 }
    }

    // ---- Stage 1: enumerate every complete dice sequence ----------------------------------

    private fun enumerateSequences(state: BoardState, remainingDice: List<Int>): List<List<SubMove>> {
        if (remainingDice.isEmpty()) return listOf(emptyList())
        val results = mutableListOf<List<SubMove>>()
        var anyPlayable = false
        for (die in remainingDice.toSet()) { // distinct values; doubles collapse naturally
            for (sm in legalSubMovesFor(state, die)) {
                anyPlayable = true
                val next = applySubMove(state, sm)
                val rest = remainingDice.toMutableList().apply { remove(die) }
                for (tail in enumerateSequences(next, rest)) {
                    results.add(listOf(sm) + tail)
                }
            }
        }
        if (!anyPlayable) results.add(emptyList()) // no die playable from here -> stop
        return results
    }

    // ---- test-only entry points (replaced by legalMoves wiring in Task 12) ----------------
    internal fun legalSubMovesForTest(state: BoardState, die: Int) = legalSubMovesFor(state, die)
    internal fun enumerateSequencesForTest(state: BoardState, dice: List<Int>) =
        enumerateSequences(state, dice)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :core:test --tests "*EnumerationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/MoveGenerator.kt core/src/test/kotlin/dk/rlunde/backgammon/core/EnumerationTest.kt
git commit -m "feat(core): add single-die legality + Stage 1 enumeration"
```

---

## Task 11: Stage 2 (max-pips filter) + Stage 3 (dedup by outcome)

**Files:**
- Modify: `core/src/main/kotlin/dk/rlunde/backgammon/core/MoveGenerator.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/FilterDedupTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FilterDedupTest {
    @Test fun `filterMaxPips keeps only the longest-pip sequences`() {
        val a = listOf(SubMove(13, 10, 3, false))                       // 3 pips
        val b = listOf(SubMove(13, 10, 3, false), SubMove(13, 8, 5, false)) // 8 pips
        val kept = MoveGenerator.filterMaxPipsForTest(listOf(a, b, emptyList()))
        assertEquals(listOf(b), kept)
    }

    @Test fun `dedup collapses sequences that reach the same board`() {
        // Two orderings of the same two checker moves reach an identical board.
        val p = IntArray(26); p[13] = 2
        val s = BoardState(
            p,
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            mapOf(Player.WHITE to 0, Player.BLACK to 0),
            Player.WHITE,
        )
        val seq1 = listOf(SubMove(13, 10, 3, false), SubMove(13, 8, 5, false))
        val seq2 = listOf(SubMove(13, 8, 5, false), SubMove(13, 10, 3, false))
        val moves = MoveGenerator.dedupByOutcomeForTest(s, listOf(seq1, seq2))
        assertEquals(1, moves.size) // same outcome -> one Move
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "*FilterDedupTest"`
Expected: FAIL — helpers unresolved.

- [ ] **Step 3: Add Stage 2 + Stage 3 to `MoveGenerator.kt`**

```kotlin
    // ---- Stage 2: maximum total pips consumed ---------------------------------------------

    private fun filterMaxPips(sequences: List<List<SubMove>>): List<List<SubMove>> {
        val maxPips = sequences.maxOfOrNull { seq -> seq.sumOf { it.die } } ?: 0
        return sequences.filter { seq -> seq.sumOf { it.die } == maxPips }
    }

    // ---- Stage 3: distinct resulting board states only ------------------------------------

    private fun dedupByOutcome(state: BoardState, sequences: List<List<SubMove>>): List<Move> {
        val seen = HashSet<BoardState>()
        val result = mutableListOf<Move>()
        for (seq in sequences) {
            val outcome = apply(state, Move(seq))
            if (seen.add(outcome)) result.add(Move(seq))
        }
        return result
    }

    internal fun filterMaxPipsForTest(seqs: List<List<SubMove>>) = filterMaxPips(seqs)
    internal fun dedupByOutcomeForTest(state: BoardState, seqs: List<List<SubMove>>) =
        dedupByOutcome(state, seqs)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :core:test --tests "*FilterDedupTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/MoveGenerator.kt core/src/test/kotlin/dk/rlunde/backgammon/core/FilterDedupTest.kt
git commit -m "feat(core): add Stage 2 max-pips filter + Stage 3 dedup"
```

---

## Task 12: `legalMoves` wiring + rule-level tests

Wire the three stages into the public `legalMoves`. This is where the must-use-both / must-
play-larger / forfeit rules are verified end-to-end.

**Files:**
- Modify: `core/src/main/kotlin/dk/rlunde/backgammon/core/MoveGenerator.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/LegalMovesTest.kt`

- [ ] **Step 1: Add the public `legalMoves` to `MoveGenerator.kt`**

```kotlin
    /** All distinct, fully-legal turns for [state] given [dice]. Empty list = forced pass. */
    fun legalMoves(state: BoardState, dice: Dice): List<Move> {
        val sequences = enumerateSequences(state, dice.pips())
        val best = filterMaxPips(sequences)
        if (best.singleOrNull()?.isEmpty() == true) return emptyList() // only the no-move "sequence"
        return dedupByOutcome(state, best)
    }
```

- [ ] **Step 2: Write the failing test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegalMovesTest {
    private fun board(
        points: IntArray, whiteBar: Int = 0, blackBar: Int = 0, toMove: Player = Player.WHITE,
    ) = BoardState(
        points,
        bar = mapOf(Player.WHITE to whiteBar, Player.BLACK to blackBar),
        off = mapOf(Player.WHITE to 0, Player.BLACK to 0),
        toMove,
    )

    @Test fun `must use both dice when possible`() {
        val p = IntArray(26); p[13] = 2
        val moves = MoveGenerator.legalMoves(board(p), Dice(3, 5))
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.subMoves.size == 2 }) // every legal turn plays both dice
    }

    @Test fun `forfeit when on the bar and both entry points blocked`() {
        // BLACK owns entry points for both die 1 (->24) and die 2 (->23)
        val p = IntArray(26); p[24] = -2; p[23] = -2
        val s = board(p, whiteBar = 1)
        assertEquals(emptyList(), MoveGenerator.legalMoves(s, Dice(1, 2)))
    }

    @Test fun `must play the larger die when only one die is playable`() {
        // WHITE one checker on 13. Die options 6 and 5.
        // Block both single-die destinations so they're not co-playable as a 2-die sequence,
        // but leave only the larger (6 -> 7) playable; the 5 -> 8 is blocked.
        val p = IntArray(26)
        p[13] = 1
        p[8] = -2  // blocks the 5 (13->8)
        // 6 -> 7 is open; after 13->7, the 5 (7->2) is open too? Force single-die: block 7->2.
        p[2] = -2  // blocks 7->2 so the 5 cannot follow the 6
        // also block 8->3 path irrelevant since 8 blocked. So the only play is the single 6.
        val moves = MoveGenerator.legalMoves(board(p), Dice(6, 5))
        assertEquals(1, moves.size)
        val only = moves.single().subMoves.single()
        assertEquals(6, only.die)         // larger die forced
        assertEquals(SubMove(13, 7, 6, isHit = false), only)
    }

    @Test fun `dedup returns distinct resulting states only`() {
        val p = IntArray(26); p[13] = 2
        val moves = MoveGenerator.legalMoves(board(p), Dice(3, 5))
        val outcomes = moves.map { MoveGenerator.apply(board(p), it) }
        assertEquals(outcomes.size, outcomes.toSet().size) // all outcomes distinct
    }

    @Test fun `doubles give up to four moves`() {
        val p = IntArray(26); p[13] = 4
        val moves = MoveGenerator.legalMoves(board(p), Dice(2, 2))
        assertTrue(moves.any { it.subMoves.size == 4 })
        assertTrue(moves.all { it.subMoves.size == 4 }) // all four playable here -> must use all
    }
}
```

- [ ] **Step 3: Run test to verify it fails, then passes**

Run: `.\gradlew.bat :core:test --tests "*LegalMovesTest"`
Expected: After Step 1 implementation present, PASS. (If you wrote the test first: FAIL on `legalMoves` unresolved, then add Step 1, then PASS.)

- [ ] **Step 4: Run the full suite**

Run: `.\gradlew.bat :core:test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dk/rlunde/backgammon/core/MoveGenerator.kt core/src/test/kotlin/dk/rlunde/backgammon/core/LegalMovesTest.kt
git commit -m "feat(core): wire legalMoves (max-pips, must-play-larger, forfeit, doubles)"
```

---

## Task 13: Stage-1 ordering completeness + bear-off-vs-move interaction

Two correctness regression tests called out by the spec review (B1, M2/m10).

**Files:**
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/OrderingAndBearOffTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderingAndBearOffTest {
    private fun board(points: IntArray, toMove: Player = Player.WHITE) = BoardState(
        points,
        mapOf(Player.WHITE to 0, Player.BLACK to 0),
        mapOf(Player.WHITE to 0, Player.BLACK to 0),
        toMove,
    )

    @Test fun `playable only in one die order is still found`() {
        // WHITE single checker on 9, roll (4,5).
        // Order 5-then-4: 9->4->0? no. Use a blocking layout so only one order plays both dice.
        // 9->5 (die4) then 5->0 bear-off not all-home; keep on board: 9->4 (die5) then 4->0? not home.
        // Simpler: single checker on 9; block 9->4 (die5 dest) so the 5 can't go first,
        // but 9->5 (die4) then 5->0... ensure both dice get used via the 4-first order only.
        val p = IntArray(26)
        p[9] = 1
        p[4] = -2 // blocks the die-5 move from 9 (9->4). So die 5 cannot be played first.
        // die 4 first: 9->5 ; then die 5: 5->0 requires all-home (it is now: only checker on 5) -> bear off
        val moves = MoveGenerator.legalMoves(board(p), Dice(4, 5))
        // The full two-die play (9->5 then bear off with 5) must be present, proving the
        // small-die-first order was explored.
        assertTrue(moves.any { m -> m.subMoves.size == 2 && m.subMoves.last().to == 0 })
    }

    @Test fun `overflow bear-off counts die face value, not distance`() {
        // WHITE all home: one on 2. Roll (6,6) doubles. 6 > 2 and it is the farthest -> bear off.
        val p = IntArray(26); p[2] = 1
        val moves = MoveGenerator.legalMoves(board(p), Dice(6, 6))
        // Only one checker, so the best play bears it off with a single 6 (face value 6 used).
        val best = moves.single()
        assertEquals(1, best.subMoves.size)
        assertEquals(SubMove(from = 2, to = 0, die = 6, isHit = false), best.subMoves.single())
    }

    @Test fun `in-home move vs bear-off - max pips forces the fuller play`() {
        // WHITE all home: checkers on 6 and 5. Roll (1,2).
        // A play using both dice (e.g. 6->5 then 6->4, or 6->4 & 5->4...) must beat any 1-die play.
        val p = IntArray(26); p[6] = 1; p[5] = 1
        val moves = MoveGenerator.legalMoves(board(p), Dice(1, 2))
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.subMoves.size == 2 }) // both dice forced
    }
}
```

- [ ] **Step 2: Run the test**

Run: `.\gradlew.bat :core:test --tests "*OrderingAndBearOffTest"`
Expected: PASS. (If any fail, the bug is real — fix `MoveGenerator` per the spec before continuing.)

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/dk/rlunde/backgammon/core/OrderingAndBearOffTest.kt
git commit -m "test(core): pin Stage-1 ordering completeness + bear-off face-value"
```

---

## Task 14: Game-driver util + invariant fuzz + determinism

A tiny pure-core driver (reused by the Phase 3 self-play harness) plus the seeded fuzz test
that proves the 15-checker invariant holds across full random games.

**Files:**
- Create: `core/src/test/kotlin/dk/rlunde/backgammon/core/GameDriver.kt`
- Test: `core/src/test/kotlin/dk/rlunde/backgammon/core/FuzzInvariantTest.kt`

- [ ] **Step 1: Create the driver** (test util — pure, deterministic given the roller)

```kotlin
package dk.rlunde.backgammon.core

import kotlin.random.Random

/**
 * Plays one full game: roll -> pick a legal move (or pass) -> apply, until game over or the
 * safety cap. [pick] selects an index into the legal-move list; defaults to seeded-random.
 * Returns the sequence of states (each committed turn), starting from [start].
 */
fun playGame(
    start: BoardState,
    roller: DiceRoller,
    choiceRandom: Random,
    maxTurns: Int = 2000,
    onState: (BoardState) -> Unit = {},
): BoardState {
    var state = start
    var turns = 0
    onState(state)
    while (!Scoring.isGameOver(state) && turns < maxTurns) {
        val moves = MoveGenerator.legalMoves(state, roller.roll())
        state = if (moves.isEmpty()) MoveGenerator.pass(state)
        else MoveGenerator.apply(state, moves[choiceRandom.nextInt(moves.size)])
        onState(state)
        turns++
    }
    return state
}
```

- [ ] **Step 2: Write the fuzz/determinism test**

```kotlin
package dk.rlunde.backgammon.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FuzzInvariantTest {
    private fun conserves15(s: BoardState): Boolean = Player.entries.all { pl ->
        (1..24).sumOf { s.count(pl, it) } + s.barCount(pl) + s.offCount(pl) == 15
    }

    @Test fun `15 checkers per side hold after every committed turn`() {
        for (seed in listOf(1L, 42L, 7L)) {
            var turns = 0
            playGame(startingPosition(), SeededDiceRoller(seed), Random(seed), maxTurns = 1000) { st ->
                assertTrue(conserves15(st), "invariant broke (seed=$seed)")
                turns++
            }
            assertTrue(turns >= 50, "game with seed=$seed was suspiciously short ($turns states)")
        }
    }

    @Test fun `same seed replays identically`() {
        val statesA = mutableListOf<BoardState>()
        val statesB = mutableListOf<BoardState>()
        playGame(startingPosition(), SeededDiceRoller(123), Random(123)) { statesA.add(it) }
        playGame(startingPosition(), SeededDiceRoller(123), Random(123)) { statesB.add(it) }
        assertEquals(statesA, statesB)
    }
}
```

- [ ] **Step 3: Run the test**

Run: `.\gradlew.bat :core:test --tests "*FuzzInvariantTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add core/src/test/kotlin/dk/rlunde/backgammon/core/GameDriver.kt core/src/test/kotlin/dk/rlunde/backgammon/core/FuzzInvariantTest.kt
git commit -m "test(core): seeded game-driver + invariant fuzz + determinism"
```

---

## Task 15: Rule-to-test traceability + final green run

**Files:**
- Create: `core/src/test/kotlin/dk/rlunde/backgammon/core/RuleTraceability.kt`

- [ ] **Step 1: Create the traceability record** (a comment block + a trivial test so it lives in the suite)

```kotlin
package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Product spec docs/backgammon-app-spec.md §5 rule -> test mapping (review n1):
 *  1. Bar first .............. EnumerationTest.`bar-first...`, LegalMovesTest.`forfeit when on the bar...`
 *  2. Blocked points ......... EnumerationTest.`blocked destination...`
 *  3. Hitting ................ EnumerationTest.`landing on a blot...`, ApplyTest.`hit sends...`
 *  4. Doubles = four moves ... LegalMovesTest.`doubles give up to four moves`
 *  5. Max dice / play larger . LegalMovesTest.`must use both dice...`, `must play the larger die...`,
 *                              OrderingAndBearOffTest.`playable only in one die order...`
 *  6. Bear off ............... EnumerationTest.`exact bear-off`, `overflow bear-off...`,
 *                              OrderingAndBearOffTest.`overflow ... face value`, `in-home move vs bear-off`
 *  7. Win / gammon / bg ...... WinScoringTest.*
 *  Invariant (spec §11) ...... FuzzInvariantTest.`15 checkers per side...`
 *  Determinism (spec §11) .... DiceRollerTest, FuzzInvariantTest.`same seed replays identically`
 */
class RuleTraceability {
    @Test fun `traceability record exists`() = assertTrue(true)
}
```

- [ ] **Step 2: Run the entire suite**

Run: `.\gradlew.bat :core:test`
Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 3: Confirm no Android dependency leaked in**

Run: `.\gradlew.bat :core:dependencies --configuration compileClasspath`
Expected: only Kotlin stdlib (no `androidx`/`android` artifacts).

- [ ] **Step 4: Commit**

```bash
git add core/src/test/kotlin/dk/rlunde/backgammon/core/RuleTraceability.kt
git commit -m "test(core): add §5 rule-to-test traceability record"
```

---

## Self-review (completed by plan author)

**Spec coverage:** §2 structure → Task 0; §3 model (`Player`/`Dice`/`Move`/`BoardState` + equality/immutability + Dice validation) → Tasks 1–5; `StartingPosition` → Task 6; §4 move-gen 3 stages + bar entry + bear-off + forfeit → Tasks 9–13; §5 `apply`/`pass` no-alias + invariant → Task 9; §6 scoring + pipCount → Tasks 7–8; §8 tests (discriminating max-pips, forfeit/empty, no-alias, pipCount, StartingPosition, two backgammon fixtures, fuzz seeds/N, dedup distinctness, determinism, ordering, in-home-vs-bearoff) → Tasks 5–14; §9 success criteria incl. traceability + no-Android-dep check → Task 15. No gaps found.

**Placeholder scan:** No TBD/TODO; every code step contains complete code.

**Type consistency:** `SubMove(from,to,die,isHit)`, `Move.pipsUsed`, `BoardState.count/barCount/offCount/isBlockedFor/isBlotFor`, `MoveGenerator.legalMoves/apply/pass`, `Scoring.isGameOver/winnerAndValue/pipCount`, `startingPosition()`, `playGame(...)` are used consistently across all tasks. Sentinels (off 0/25, bar-from 25/0) and per-player geometry are defined once in MoveGenerator and reused.
