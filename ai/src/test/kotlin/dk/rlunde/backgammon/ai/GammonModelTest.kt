package dk.rlunde.backgammon.ai

import kotlin.test.Test
import kotlin.test.assertTrue

class GammonModelTest {
    // features: loserBorneOff, loserPip, loserBackContact
    @Test fun `borne-off loser has near-zero gammon risk`() {
        val r = GammonModel.loseRates(GammonFeatures(borneOff = 3, pip = 40, backContact = 0))
        assertTrue(r.gammon < 0.05, "borne-off >0 must crush gammon risk: ${r.gammon}")
        assertTrue(r.backgammon <= r.gammon)
    }

    @Test fun `backgammon never exceeds gammon`() {
        val r = GammonModel.loseRates(GammonFeatures(borneOff = 0, pip = 160, backContact = 2))
        assertTrue(r.backgammon <= r.gammon)
        assertTrue(r.gammon in 0.0..1.0 && r.backgammon in 0.0..1.0)
    }

    @Test fun `more trapped checkers raises backgammon risk`() {
        val none = GammonModel.loseRates(GammonFeatures(0, 150, 0))
        val some = GammonModel.loseRates(GammonFeatures(0, 150, 3))
        assertTrue(some.backgammon >= none.backgammon)
    }
}
