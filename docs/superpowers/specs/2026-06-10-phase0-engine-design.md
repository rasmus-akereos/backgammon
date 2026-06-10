# Phase 0 — Core Rules Engine — Design

**Date:** 2026-06-10
**Owner:** Rasmus (rlunde)
**Status:** Approved design, revised after multi-lens spec review, ready for implementation plan
**Source spec:** `docs/backgammon-app-spec.md` (§3–§5, §11, §12 Phase 0)

---

## 1. Scope

Build **only** the pure-Kotlin core rules engine and its full unit-test suite — Phase 0
of the build plan (spec §12). No UI, no AI, no persistence.

The engine is a pure-Kotlin library with **zero Android dependencies**, fully exercisable
by JVM unit tests (`gradlew :core:test`) — no Android SDK or emulator required. This is the
foundation every later phase (AI, UI, coaching) depends on, so correctness here is the entire
deliverable.

**Out of scope for this cycle:** Compose board (Phase 1), `AiPlayer`/expectimax (Phase 2–3),
persistence (Phase 4), analyzer/coaching (Phase 5), cube/match play (Phase 6).

## 2. Project structure

Gradle project, Kotlin DSL. **Only `:core` is built and the only module created this phase.**
Do **not** scaffold empty `:ai`/`:data`/`:ui` modules now. The root build is structured so
later phases add modules without restructuring:

- `:core` — `kotlin("jvm")`, no Android plugin. (this phase)
- `:ai` — later; `kotlin("jvm")`, depends `:core`. Stays pure-JVM so it's emulator-free
  unit-testable (product spec §3).
- `:data`, `:app`/`:ui` — later; Android modules, depend `:core` (+ `:ai`).

```
backgammon/
├── settings.gradle.kts          // include(":core") only
├── build.gradle.kts             // root: shared Kotlin/version config
├── gradle/libs.versions.toml    // version catalog
├── gradlew / gradlew.bat        // wrapper (generated)
└── core/
    ├── build.gradle.kts         // kotlin("jvm"), JUnit5; test task runs with assertions
    └── src/
        ├── main/kotlin/dk/rlunde/backgammon/core/
        │   ├── BoardState.kt    // BoardState, Player
        │   ├── Dice.kt          // Dice (value type only)
        │   ├── DiceRoller.kt    // DiceRoller, SeededDiceRoller, RandomDiceRoller
        │   ├── Move.kt          // SubMove, Move
        │   ├── MoveGenerator.kt // legalMoves, apply, pass (generation + transitions)
        │   ├── Scoring.kt       // isGameOver, winnerAndValue, pipCount (terminal state + metrics)
        │   └── StartingPosition.kt
        └── test/kotlin/dk/rlunde/backgammon/core/  // mirrored test files
```

- **Package root:** `dk.rlunde.backgammon` (core under `dk.rlunde.backgammon.core`) —
  reverse-DNS of the owner's `rlunde.dk` domain. This is the stable Android `applicationId`
  and supersedes the product spec's `com.lunde.backgammon` placeholder (product spec §14.8).
- **Toolchain:** Kotlin 2.x, JVM 17 target, JUnit 5 + `kotlin.test` assertions.
- (Renamed from product spec's `Rules.kt` to `Scoring.kt` since it holds terminal-state
  scoring + board metrics, not the movement rules — those live in `MoveGenerator.kt`. No
  open-ended "helpers" bucket; any shared predicate is a named `private` function.)

## 3. Domain model

Standard internal board, points numbered 1–24 from White's perspective (spec §4):

- Points **1–6** = White's home board; **19–24** = Black's home board.
- White moves high→low and bears off toward point 0; Black moves low→high and bears off
  toward point 25.
- `points` is size 26: indices 1..24 are real points; 0 and 25 are off-board sentinels.
- One signed `Int` per point: `+n` = n White checkers, `-n` = n Black checkers, `0` = empty.

```kotlin
enum class Player { WHITE, BLACK }

data class BoardState(
    val points: IntArray,      // size 26; treated as DEEPLY IMMUTABLE (never mutated in place)
    val bar: Map<Player, Int>, // ALWAYS total over Player (both keys present, default 0)
    val off: Map<Player, Int>, // ALWAYS total over Player (both keys present, default 0)
    val toMove: Player
) {
    // equals: points.contentEquals(other.points) AND == on bar, off, toMove.
    // hashCode: combine points.contentHashCode() with bar/off/toMove hashCodes (e.g.
    //   31*(31*(31*points.contentHashCode()+bar.hashCode())+off.hashCode())+toMove.hashCode()).
    // equals and hashCode MUST be overridden together and stay mutually consistent.
}

data class Dice(val a: Int, val b: Int) {
    init { require(a in 1..6 && b in 1..6) }
    val isDouble get() = a == b
    fun pips(): List<Int> = if (isDouble) List(4) { a } else listOf(a, b)
}

data class SubMove(val from: Int, val to: Int, val die: Int, val isHit: Boolean)
data class Move(val subMoves: List<SubMove>)
```

**Why the custom `equals`/`hashCode` (review B3):** `BoardState` is used as a `HashSet` key in
move-dedup (§4 Stage 3) and asserted for equality in tests. A `data class` auto-`equals`
compares `IntArray` by reference, so we override it. The override is *mixed*:
`contentEquals` on `points` only, `==` on the other three fields. **Safety contract:** the
`points` array is never mutated in place — `apply` always allocates a fresh array — so the
hash of a live key never changes. (We keep `IntArray` per product spec §4; the Phase-0
justification is correct dedup + test equality, not future AI perf.)

**`bar`/`off` totality:** both `Player` keys are always present (default `0`), guaranteed by
`StartingPosition` and `apply`, so reads never produce `null` and structural equality is
stable. (Accessors return non-null `Int`.)

**Vocabulary:** a *sequence* (§4) is a partial-or-complete ordered list of `SubMove`s during
enumeration; a completed sequence becomes a `Move` (a full legal turn). `SubMove` = one die
used. A *blot* = a point with exactly one checker.

**Invariant:** total checkers per player (`points + bar + off`) always == 15.

## 4. Move generation — `MoveGenerator.legalMoves(state, dice)`

Three stages, decomposed into testable `internal` functions:
`enumerateSequences` → `filterMaxPips` → `dedupByOutcome`.

**Stage 1 — `enumerateSequences`: recurse over (remaining die × every legal checker).**
Treat the turn as consuming a multiset of pips (`dice.pips()` → 2 values, 4 for doubles). At
**every** recursion depth, branch over each *remaining* die paired with every checker that can
legally play it on the **current (recursed) state**, apply that single `SubMove`, recurse on
the remaining dice. Both die orderings and all intermediate branches emerge naturally from
this — there is **no** separate top-level "orderings" loop (review B1).

Single-die legality at each step (a `private fun` helper):
- **Bar-first, re-checked every depth:** if `bar[toMove] > 0`, the only legal sub-moves are
  bar entries. Entry point for die `d`: **WHITE → `25 − d`** (lands 19–24); **BLACK → `d`**
  (lands 1–6). Entry is blocked if the target holds ≥2 opposing checkers; a single opposing
  checker there is hit. Non-entry sub-moves are generated only once the bar is empty (applies
  per die of a doubles turn; multi-checker partial entry falls out of the recursion).
- **Blocked points:** a point with ≥2 opposing checkers cannot be landed on.
- **Hitting:** landing on a blot (exactly 1 opposing checker) sends it to the bar.
- **Bear-off:** legal only when all 15 of `toMove` are home. Exact roll bears off. Overflow
  (die > distance to off) bears off **only if no checker sits on a higher-pip point** —
  WHITE: the highest-numbered occupied home point; BLACK: the lowest-numbered occupied home
  point. A checker may instead be moved within the home board (bear-off is **not** forced when
  a legal in-home move exists; the max-pips filter still applies).

**Stage 2 — `filterMaxPips`: keep sequences consuming the maximum total pips (spec §5 rule 5).**
"Total pips consumed" = the **sum of die face values played**, *not* board distance travelled
— so an overflow bear-off counts its full die value (review M2). This single metric provably
subsumes both halves of rule 5 (it is *total pips*, **not** "most dice," which would be wrong):
- Any two-die sequence consumes `a+b` > `max(a,b)` ≥ any one-die sequence → "use both dice
  whenever possible." ✓
- When both can't be played together, the larger single die consumes more pips → "play the
  larger die." ✓ (A dice-*count* metric ties the two single-die plays and fails this.)
- Doubles → maximizes the number of the four sub-moves played. ✓

**Stage 3 — `dedupByOutcome`: distinct resulting states only.**
Different sub-move orders can yield identical end positions. Dedup `Move`s by the `BoardState`
that `apply` produces, using a `HashSet<BoardState>` (O(n), relies on §3 equals/hashCode). So
`legalMoves` returns *distinct outcomes*, not redundant paths.

**Forfeit / pass:** `legalMoves` returns `emptyList()` when no legal turn exists — both the
bar case (checkers on the bar, no die can enter) and the general fully-blocked case. The
caller advances the turn via `pass` (§5).

**Perspective:** moves are generated in absolute coordinates; direction is set by `toMove`
(White high→low toward 0; Black low→high toward 25). Home is points 1–6 (White), 19–24 (Black).

## 5. Apply & pass — `MoveGenerator`

**`apply(state, move): BoardState`** folds the sub-moves into a **new** `BoardState`: builds
`points` via `copyOf()` (never aliases the input array), moves the checker, sends any hit blot
to the bar, increments `off` on bear-off, flips `toMove`. Assumes a legal `Move`. Validates
the 15-checker invariant with `require()`/`check()` (always-on — *not* `assert()`, which is a
JVM no-op unless `-ea` is set; the `:core` test task also enables assertions).

**`pass(state): BoardState`** flips `toMove`, otherwise unchanged — so turn advancement on a
forced pass (when `legalMoves` is empty) lives in core, not in the UI/AI layers (review M6).

`MoveGenerator` holds **no mutable state** (thread-safe by statelessness); any future caching
is passed in, never stored on the object.

## 6. Win detection, scoring & board metrics — `Scoring.kt`

- `isGameOver(state): Boolean` — true when either player's `off == 15`.
- `winnerAndValue(state): Pair<Player, Int>?` — non-null **exactly when** `isGameOver` is true
  (derive from the same predicate). Multiplier:
  - **1 (single)** — loser has borne off ≥1.
  - **2 (gammon)** — loser has borne off none.
  - **3 (backgammon)** — loser has borne off none **AND** (loser has a checker on the bar
    **OR** a checker in the **winner's** home board). Winner's home board = **points 1–6 if the
    winner is WHITE, points 19–24 if the winner is BLACK**; loser's checkers are those with the
    opposite sign (review B2).
  - The impossible double-win (both `off == 15`) is asserted against (debug `check`).
- `pipCount(state, player): Int` — **WHITE:** checker on point `p` contributes `p`; **BLACK:**
  contributes `25 − p`; **bar:** 25 for either player. Sum over that player's checkers
  (review M4). Used by tests now; by AI eval and the UI pip display later.

*(`winnerAndValue`'s gammon/backgammon multiplier and `pipCount` are deliberate forward-builds
— cheap, rules are stable, and both are tested here. Match-play consumption is a later phase.)*

## 7. Dice rolling — `DiceRoller.kt`

- `interface DiceRoller { fun roll(): Dice }`
- `class SeededDiceRoller(seed: Long) : DiceRoller` — backed by seeded `kotlin.random.Random`,
  fully deterministic/reproducible. Backbone of every test and the later self-play harness.
- `class RandomDiceRoller : DiceRoller` — system-random. Included now **only** so the
  `DiceRoller` interface has a second implementation (validates the abstraction); it has no
  other Phase-0 consumer.

All rollers produce die values in `1..6` (enforced by `Dice`'s `init`).

## 8. Testing strategy (the deliverable)

Built **test-first (TDD)**: for each rule, write the failing test against a known position,
then implement until green. All tests run on plain JVM (`gradlew :core:test`), no emulator.
Each product-spec §5 rule (1–7) maps to at least one named test; the mapping is recorded as a
traceability comment block in the test sources and checked at §9 sign-off (review n1).

1. **Move-gen correctness** (hand-built positions): bar re-entry can/can't; **bar forfeit →
   `legalMoves` is empty** when no die can enter; **general fully-blocked → empty**; blocked
   points; hitting a blot; bar-entry hit; enter-from-bar-then-continue same turn; doubles
   giving 1/2/3/4 usable moves; must-use-both-dice.
2. **Max-pips metric — discriminating test (review M5):** a position where each single die is
   individually legal but not both together, and the two single-die plays differ; assert the
   smaller-die-only `Move` is **absent** (proves the metric is total-pips, not dice-count).
   Plus a position playable only in one specific die order (proves Stage-1 completeness, B1).
3. **Bear-off:** exact roll; overflow with/without a higher checker (**counting die face value**,
   for both White and Black orientations); move-within-home instead of off; an in-home position
   where bearing off vs moving differ in dice used → max-pips forces the fuller play.
4. **Invariant fuzz (reproducible):** seeds `{1, 42, 7}`, ≥200 turns each; assert 15-per-side
   after **every** `apply`; assert same-seed runs produce identical state/move traces.
5. **No-aliasing:** snapshot input `BoardState`, call `apply`, assert input unchanged **and**
   `result.points !== input.points`.
6. **Win/scoring:** single; gammon; two **backgammon** fixtures (loser-on-bar; loser-in-
   winner's-home) for **both** winning colours; a near-miss (loser borne off ≥1) scored
   single/gammon, not backgammon; `winnerAndValue` is null iff `!isGameOver`.
7. **pipCount:** `StartingPosition` == 167 per side; asymmetric positions for both players;
   bar checkers counted as 25.
8. **StartingPosition:** exact 26-element `points` array (canonical 24/13/8/6 mirrored), empty
   bar/off, defined `toMove`.
9. **Dedup:** a position where multiple sub-move orders collapse to one outcome — assert
   `legalMoves.map { apply(it) }.toSet().size == legalMoves.size` and an exact expected count.
10. **Determinism:** same seed → same dice sequence.

*(A minimal pure-core game-driver — `legalMoves` → choose → `apply`, `pass` on empty,
terminate on `isGameOver` — backs the fuzz test and is reusable by the Phase 3 self-play
harness; keep it in test/util, not duplicated.)*

## 9. Success criteria

- `gradlew :core:test` is green with the full §8 suite (assertions enabled).
- Every product-spec §5 rule (1–7) is enforced and mapped to a named test (traceability block
  present).
- The 15-checker invariant holds across the seeded fuzz run; `apply` never mutates/aliases its
  input.
- `StartingPosition` and `pipCount` are pinned by tests (167 per side).
- `:core` compiles as a standalone `kotlin("jvm")` module with no Android dependency; no
  `:ai`/`:data`/`:ui` modules created.
