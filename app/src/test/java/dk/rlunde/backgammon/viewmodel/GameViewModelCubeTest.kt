package dk.rlunde.backgammon.viewmodel

import dk.rlunde.backgammon.core.Player
import dk.rlunde.backgammon.game.CubeResponse
import dk.rlunde.backgammon.game.GameConfig
import dk.rlunde.backgammon.game.Opponent
import dk.rlunde.backgammon.game.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelCubeTest {
    private fun hotSeatVm(scope: TestScope) = GameViewModel(
        analysisDispatcher = StandardTestDispatcher(scope.testScheduler),
        scopeOverride = scope,
    )

    @Test fun `offer then take doubles the cube via the VM`() = runTest {
        val vm = hotSeatVm(this)
        vm.startGame(GameConfig(opponent = Opponent.HOT_SEAT, humanColor = Player.WHITE))
        vm.onOfferDouble()
        assertEquals(Phase.CUBE_OFFERED, vm.uiState.value.phase)
        vm.onRespondDouble(CubeResponse.TAKE)
        assertEquals(2, vm.uiState.value.cube.value)
    }

    @Test fun `resign ends the game via the VM`() = runTest {
        val vm = hotSeatVm(this)
        vm.startGame(GameConfig(opponent = Opponent.HOT_SEAT, humanColor = Player.WHITE))
        vm.onResign(Player.WHITE)
        assertEquals(Phase.GAME_OVER, vm.uiState.value.phase)
        assertEquals(Player.BLACK, vm.uiState.value.winner)
    }
}
