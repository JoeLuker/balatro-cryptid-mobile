package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Passive jokers — they have no scoring effect; owning one adjusts a round-setup field (hand size /
 * hands / discards) via the board scan in startRound(). Driven through the real public entry
 * (selectBlind → startRound) and asserted on RunState. Base round: hand size 8, 4 hands, 3 discards.
 */
class PassiveJokerTest {
    private fun offer(key: String) = Offer(key, key, "", 0)
    private fun runWith(key: String): RunState {
        val rs = RunState()
        rs.buy(offer(key), free = true)
        rs.phase = Phase.BLIND_SELECT
        rs.selectBlind()                       // → startRound applies the passive board scan
        return rs
    }

    @Test fun jugglerAddsHandSize() {
        assertEquals(9, runWith("j_juggler").handSize)            // 8 + 1
    }

    @Test fun drunkardAddsADiscard() {
        assertEquals(4, runWith("j_drunkard").discardsLeft)       // 3 + 1
    }

    @Test fun troubadourTradesAHandForTwoHandSize() {
        val rs = runWith("j_troubadour")
        assertEquals(10, rs.handSize)                             // 8 + 2
        assertEquals(3, rs.handsLeft)                             // 4 - 1
    }

    @Test fun merryAndyTradesHandSizeForADiscard() {
        val rs = runWith("j_merry_andy")
        assertEquals(7, rs.handSize)                              // 8 - 1
        assertEquals(4, rs.discardsLeft)                          // 3 + 1
    }

    @Test fun burglarGivesThreeHandsAndNoDiscards() {
        val rs = runWith("j_burglar")
        assertEquals(7, rs.handsLeft)                             // 4 + 3
        assertEquals(0, rs.discardsLeft)                          // none
    }
}
