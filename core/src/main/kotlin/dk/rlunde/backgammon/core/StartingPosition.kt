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
