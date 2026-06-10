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
}
