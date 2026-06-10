package dk.rlunde.backgammon.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Product spec docs/backgammon-app-spec.md §5 rule -> test mapping:
 *  1. Bar first .............. EnumerationTest bar-first tests, LegalMovesTest forfeit-on-bar
 *  2. Blocked points ......... EnumerationTest blocked-destination
 *  3. Hitting ................ EnumerationTest blot-is-hit, ApplyTest hit-sends-to-bar
 *  4. Doubles = four moves ... LegalMovesTest doubles-give-four
 *  5. Max dice / play larger . LegalMovesTest must-use-both + must-play-larger,
 *                              OrderingAndBearOffTest one-die-order
 *  6. Bear off ............... EnumerationTest exact + overflow, OrderingAndBearOffTest face-value + in-home-vs-bearoff
 *  7. Win / gammon / bg ...... WinScoringTest (all)
 *  Invariant (spec 11) ....... FuzzInvariantTest 15-per-side
 *  Determinism (spec 11) ..... DiceRollerTest, FuzzInvariantTest same-seed-replays
 */
class RuleTraceability {
    @Test fun `traceability record exists`() = assertTrue(true)
}
