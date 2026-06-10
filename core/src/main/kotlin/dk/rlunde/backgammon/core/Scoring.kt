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
