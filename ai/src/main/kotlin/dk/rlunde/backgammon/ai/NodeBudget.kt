package dk.rlunde.backgammon.ai

/**
 * A single-use, mutable eval-count budget for one [Expectimax.bestMove] call. NOT thread-safe:
 * the VM runs exactly one search at a time on Dispatchers.Default, and a fresh instance is created
 * per search, so it is never shared. Counts candidate static-evaluations — the dominant search cost.
 */
internal class NodeBudget(private val max: Int) {
    private var spent = 0
    fun spend(n: Int) { spent += n }
    val exhausted: Boolean get() = spent >= max
}
