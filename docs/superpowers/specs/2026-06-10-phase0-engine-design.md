# Phase 0 — Core Rules Engine — Design

**Date:** 2026-06-10
**Owner:** Rasmus (rlunde)
**Status:** Approved design, ready for implementation plan
**Source spec:** `docs/backgammon-app-spec.md` (§3–§5, §11, §12 Phase 0)

---

## 1. Scope

Build **only** the pure-Kotlin core rules engine and its full unit-test suite — Phase 0
of the build plan (spec §12). No UI, no AI, no persistence.

The engine is a pure-Kotlin library with **zero Android dependencies**, fully exercisable
by JVM unit tests (`./gradlew :core:test`) — no Android SDK or emulator required. This is
the foundation every later phase (AI, UI, coaching) depends on, so correctness here is the
entire deliverable.

**Out of scope for this cycle:** Compose board (Phase 1), `AiPlayer`/expectimax (Phase 2–3),
persistence (Phase 4), analyzer/coaching (Phase 5), cube/match play (Phase 6).

## 2. Project structure

Gradle multi-module project, Kotlin DSL. Only `:core` is built this phase; the `:app`
Android module is added in a later phase without restructuring.

```
backgammon/
├── settings.gradle.kts          // includes :core (later: :app)
├── build.gradle.kts             // root: shared Kotlin/version config
├── gradle/libs.versions.toml    // version catalog
└── core/
    ├── build.gradle.kts         // kotlin("jvm"), JUnit5 — NO android plugin
    └── src/
        ├── main/kotlin/backgammon/rlunde/core/
        │   ├── BoardState.kt    // BoardState, Player
        │   ├── Dice.kt          // Dice, DiceRoller, SeededDiceRoller, RandomDiceRoller
        │   ├── Move.kt          // SubMove, Move
        │   ├── MoveGenerator.kt // legalMoves + apply
        │   ├── Rules.kt         // win/gammon/backgammon detection, pip count, helpers
        │   └── StartingPosition.kt
        └── test/kotlin/backgammon/rlunde/core/  // mirrored test files
```

- **Package root:** `backgammon.rlunde` (core under `backgammon.rlunde.core`), per owner's
  choice. (Note: unusual vs. reverse-domain convention; revisit if `rlunde.backgammon` was
  intended.)
- **Toolchain:** Kotlin 2.x, JVM 17 target, JUnit 5 + `kotlin.test` assertions.

## 3. Domain model

Standard internal board, points numbered 1–24 from White's perspective (spec §4):

- Points **1–6** = White's home board; **19–24** = Black's home board.
- White bears off toward point 0; Black bears off toward point 25.
- `points` is size 26: indices 1..24 are real points; 0 and 25 are off-board sentinels.
- One signed `Int` per point: `+n` = n White checkers, `-n` = n Black checkers, `0` = empty.

```kotlin
enum class Player { WHITE, BLACK }

data class BoardState(
    val points: IntArray,      // size 26
    val bar: Map<Player, Int>,
    val off: Map<Player, Int>,
    val toMove: Player
)

data class Dice(val a: Int, val b: Int) {
    val isDouble get() = a == b
    fun pips(): List<Int> = if (isDouble) List(4) { a } else listOf(a, b)
}

data class SubMove(val from: Int, val to: Int, val die: Int, val isHit: Boolean)
data class Move(val subMoves: List<SubMove>)
```

**Refinement vs. raw spec:** `BoardState` uses `IntArray`, which breaks the data class's
auto-generated `equals`/`hashCode` (arrays compare by reference). Phase 0 depends on state
equality for move deduplication and test assertions, and the later AI search copies these
arrays heavily. **Decision:** keep `IntArray` for performance but provide an explicit
`equals`/`hashCode` using `contentEquals`/`contentHashCode` over all four fields. Correct and
fast.

**Invariant:** total checkers per player (`points + bar + off`) always == 15.

`StartingPosition.kt` provides the standard opening setup as a `BoardState`.

## 4. Move generation — `MoveGenerator.legalMoves(state, dice)`

Three stages:

**Stage 1 — Recursive enumeration of all dice sequences.**
Treat the turn as consuming a multiset of pips (`dice.pips()` → 2 values, 4 for doubles).
Recurse: at each step, for each *remaining* die, try every checker that can legally move that
die's distance (respecting bar-first, blocked points, hitting, bear-off), apply that single
`SubMove`, recurse on the remaining dice. Collect every complete sequence. For non-doubles,
try **both die orderings** (3-then-5 can reach states 5-then-3 cannot).

**Stage 2 — Filter to maximum total pips consumed (spec §5 rule 5).**
Keep only sequences that consume the **maximum total pips** (sum of the die values actually
played). This single metric provably subsumes both halves of rule 5 — note it is *total pips*,
**not** "most dice," which would be incorrect:
- Any two-die sequence consumes `a+b` pips, strictly more than `max(a,b)` ≥ any one-die
  sequence → forces "use both dice whenever possible." ✓
- When both dice can't be played together, among the one-die plays the larger die consumes
  more pips → forces "must play the larger die." ✓ (A "most dice" count would tie the two
  single-die plays at 1 and fail to force the larger — hence total pips, not dice count.)
- Doubles → maximizes the number of the four sub-moves played. ✓

No special-casing of the larger-die rule is needed.

**Stage 3 — Deduplicate by resulting board state.**
Different sub-move orders can yield identical end positions. Dedup `Move`s by the `BoardState`
that `apply` produces, so `legalMoves` returns *distinct outcomes*, not redundant paths.
(Relies on the `equals` fix in §3.)

**Edge cases (each gets a test):**
- **Bar re-entry:** if `bar[toMove] > 0`, only bar-entry sub-moves are legal; if no die can
  enter, return `emptyList()` → caller auto-passes.
- **No legal moves** (fully blocked) → `emptyList()`, turn forfeit.
- **Blocked points:** a point with 2+ opposing checkers cannot be landed on.
- **Hitting:** landing on a point with exactly 1 opposing checker (a blot) sends it to the bar.
- **Bear-off:** allowed only when all 15 are home. Exact roll bears off; an overshoot bears
  off the highest checker *only if* no checker sits on a higher point; a checker may instead
  move within the home board.

**Perspective:** moves are generated in absolute coordinates; direction is set by `toMove`
(White high→low toward 0; Black low→high toward 25). Home is points 1–6 (White), 19–24 (Black).

## 5. Apply — `MoveGenerator.apply(state, move)`

Folds the sub-moves into a new `BoardState`: moves the checker, sends any hit blot to the bar,
increments `off` on bear-off, flips `toMove`. Assumes a legal `Move` (legality is
`legalMoves`' responsibility). Asserts the 15-checker invariant (debug) on the result. Returns
a new immutable state (input untouched).

## 6. Win detection & scoring — `Rules.kt`

- `isGameOver(state): Boolean` — true when either player's `off == 15`.
- `winnerAndValue(state): Pair<Player, Int>?` — multiplier is **1** (single), **2** (gammon:
  loser bore off none), or **3** (backgammon: loser bore off none AND still has a checker on
  the bar or in the winner's home board). Pure; match-play consumption is a later phase.
- `pipCount(state, player): Int` — standard pip total (distance for all checkers to bear off,
  including those on the bar). Used by tests now; by AI eval and the UI pip display later.

## 7. Dice — `Dice.kt`

- `interface DiceRoller { fun roll(): Dice }`
- `class SeededDiceRoller(seed: Long) : DiceRoller` — backed by seeded `kotlin.random.Random`,
  fully deterministic/reproducible. Backbone of every test and the later self-play harness.
- `class RandomDiceRoller : DiceRoller` — system-random, for real play later.

## 8. Testing strategy (the deliverable)

Built **test-first (TDD)**: for each rule, write the failing test against a known position,
then implement until green. All tests run on plain JVM (`./gradlew :core:test`), no emulator.

1. **Move-gen correctness** against hand-constructed positions: bar re-entry (can/can't),
   blocked points, hitting a blot, doubles (four moves), must-use-both-dice,
   must-use-larger-die-when-only-one-playable.
2. **Bear-off:** exact roll, overflow with/without a higher checker, move-within-home.
3. **Invariant fuzz:** apply random legal moves (seeded) for N turns; assert 15-per-side never
   breaks after any `apply`.
4. **Win/scoring:** single, gammon, backgammon positions.
5. **Dedup:** positions where multiple sub-move orders collapse to one outcome return distinct
   results only.
6. **Determinism:** same seed → same dice sequence.

## 9. Success criteria

- `./gradlew :core:test` is green with the full §8 suite.
- All spec §5 rules enforced and covered by at least one test.
- The 15-checker invariant holds across a seeded fuzz run.
- Engine compiles as a standalone `kotlin("jvm")` module with no Android dependency.
