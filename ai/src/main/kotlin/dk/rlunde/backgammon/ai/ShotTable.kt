package dk.rlunde.backgammon.ai

/**
 * Probability that a single opposing checker at a given shot [distance] hits next roll, out of 36
 * ordered dice rolls. Counts direct shots (1–6) plus combination/doubles shots (7–12), ASSUMING THE
 * INTERMEDIATE PATH IS OPEN — i.e. blocking by made points is ignored (a v1 approximation; the table
 * mildly over-counts hits behind a prime — the safe, over-cautious direction). See spec §4.3.
 */
internal object ShotTable {
    // index = distance 1..12; value = number of the 36 rolls that cover it (path open).
    private val COUNTS = intArrayOf(
        /* 0 */ 0,
        /* 1 */ 11, /* 2 */ 12, /* 3 */ 14, /* 4 */ 15, /* 5 */ 15, /* 6 */ 17,
        /* 7 */ 6,  /* 8 */ 6,  /* 9 */ 5,  /* 10 */ 3, /* 11 */ 2, /* 12 */ 3,
    )

    fun hitProbability(distance: Int): Double =
        if (distance in 1..12) COUNTS[distance] / 36.0 else 0.0
}
