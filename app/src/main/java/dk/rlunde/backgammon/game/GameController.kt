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
    private val aiSide: Player? = null,
) {
    private var committed: BoardState = initial
    private var dice: Dice? = null
    private val staged = mutableListOf<SubMove>()
    private var selectedOrigin: BoardTarget? = null
    private var passing = false

    var lastCommitted: CommittedTurn? = null; private set
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
        if (committed.toMove == aiSide) return
        if (passing) return
        if (uiState.phase != Phase.MOVING && uiState.phase != Phase.COMMITTABLE) return
        val d = dice ?: return
        val partial = MoveGenerator.applyPartial(committed, staged)
        val remaining = remainingDice(d)
        val next = TurnPlanner.legalNextSubMoves(partial, remaining)

        val sel = selectedOrigin
        if (sel != null) {
            val fromKey = originKey(sel)
            val chosen = next[fromKey]?.firstOrNull { destinationTarget(it) == target }
            if (chosen != null) {
                staged.add(chosen)
                selectedOrigin = null
                uiState = compute()
                return
            }
        }
        val key = originKey(target)
        if (key != null && next.containsKey(key)) {
            selectedOrigin = target
        } else {
            selectedOrigin = null
        }
        uiState = compute()
    }

    fun undo() {
        if (committed.toMove == aiSide) return
        if (staged.isEmpty()) return
        staged.removeAt(staged.lastIndex)
        selectedOrigin = null
        uiState = compute()
    }

    fun commit() {
        if (committed.toMove == aiSide) return
        if (uiState.phase != Phase.COMMITTABLE) return
        val pre = committed
        val d = dice!!
        val move = Move(staged.toList())
        lastCommitted = CommittedTurn(pre, d, move)
        committed = MoveGenerator.apply(committed, move)
        staged.clear()
        selectedOrigin = null
        dice = null
        passing = false
        uiState = compute()
    }

    /** Apply a complete AI-chosen move (no tap-staging). Pre: move ∈ legalMoves(committed, dice). */
    fun applyMove(move: Move) {
        lastCommitted = null
        committed = MoveGenerator.apply(committed, move)
        staged.clear()
        selectedOrigin = null
        dice = null
        passing = false
        uiState = compute()
    }

    fun newGame() {
        lastCommitted = null
        committed = initial
        dice = null
        staged.clear()
        selectedOrigin = null
        passing = false
        uiState = compute()
    }

    // ---- derivation ----

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

    /** Test-only: stage a complete legal move without tap simulation. */
    internal fun stageForTest(move: Move) {
        staged.clear()
        staged.addAll(move.subMoves)
        uiState = compute()
    }

    private fun compute(): GameUiState {
        val partial = MoveGenerator.applyPartial(committed, staged)
        val d = dice
        val over = Scoring.isGameOver(committed)
        val phase = when {
            over -> Phase.GAME_OVER
            // Auto-pass awaiting acknowledgePass(): keep it MOVING so roll()/commit() stay no-ops
            // (a no-moves roll computes maxUsablePips == 0, which would otherwise read COMMITTABLE).
            passing -> Phase.MOVING
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
            stagedMoves = staged.toList(),
            whitePip = Scoring.pipCount(partial, Player.WHITE),
            blackPip = Scoring.pipCount(partial, Player.BLACK),
            winner = wv?.first,
            winValue = wv?.second ?: 0,
        )
    }
}
