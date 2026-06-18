package dk.rlunde.backgammon.ui.board

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DicePipLayoutTest {
    @Test fun `pip count equals face value`() {
        for (f in 1..6) assertEquals(f, pipOffsets(f).size, "face $f")
    }
    @Test fun `face 1 is a single centre pip`() {
        assertEquals(Offset(0.5f, 0.5f), pipOffsets(1).single())
    }
    @Test fun `face 3 is a diagonal including the centre`() {
        val p = pipOffsets(3)
        assertEquals(3, p.size)
        assertTrue(p.contains(Offset(0.5f, 0.5f)))
    }
    @Test fun `face 6 has two columns of three and no centre pip`() {
        val p = pipOffsets(6)
        assertEquals(6, p.size)
        assertTrue(p.none { it.x == 0.5f && it.y == 0.5f })
        assertEquals(3, p.count { it.x == 0.28f })
        assertEquals(3, p.count { it.x == 0.72f })
    }
}
