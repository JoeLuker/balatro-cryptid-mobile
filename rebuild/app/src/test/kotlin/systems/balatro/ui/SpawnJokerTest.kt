package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spawn jokers — they create cards/consumables when the Blind is selected (context.setting_blind),
 * via the board scan in startRound(). Driven through selectBlind → startRound; deck changes are read
 * through the public snapshot().
 */
class SpawnJokerTest {
    private fun offer(key: String) = Offer(key, key, "", 0)
    private fun selectBlindWith(key: String): RunState {
        val rs = RunState()
        rs.buy(offer(key), free = true)
        rs.phase = Phase.BLIND_SELECT
        rs.selectBlind()
        return rs
    }

    @Test fun marbleAddsAStoneCardEachBlind() {
        val rs = selectBlindWith("j_marble")
        assertEquals("one Stone card added to the deck", 1, rs.snapshot().deck.count { it.enh == "STONE" })
        assertEquals("deck grows by one", 53, rs.snapshot().deck.size)
        rs.phase = Phase.BLIND_SELECT; rs.selectBlind()
        assertEquals("another Stone each blind", 2, rs.snapshot().deck.count { it.enh == "STONE" })
    }

    @Test fun cartomancerCreatesATarotWhenThereIsRoom() {
        val rs = selectBlindWith("j_cartomancer")
        assertEquals("one consumable created", 1, rs.consumables.size)
        assertTrue("it is a Tarot", rs.consumables.first() is Consumable.TarotC)
    }
}
