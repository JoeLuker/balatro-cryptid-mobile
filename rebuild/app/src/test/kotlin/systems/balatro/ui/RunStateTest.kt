package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * First unit tests of the run loop itself. RunState holds the game state machine (owned/deck/money,
 * buy/sell/scoreBank/play) separate from the @Composable rendering; with android.util.Log no-op'd in
 * tests it drives directly off-device — the seam previously only reachable through the oracle.
 */
class RunStateTest {
    private fun offer(key: String, cost: Int = 0) = Offer(key, key, "", cost)

    @Test fun buySeedsInitialFJokerState() {
        val rs = RunState()
        val start = rs.owned.size
        rs.buy(offer("j_cry_bonk"), free = true)       // manifest initialState (chips=6, xc=3)
        assertEquals(start + 1, rs.owned.size)
        assertEquals(6.0, rs.owned.last().fj.chips, 0.0)
        assertEquals(3.0, rs.owned.last().fj.xc, 0.0)
        rs.buy(offer("j_cry_biggestm"), free = true)   // legacy fjXInit (x=7)
        assertEquals(7.0, rs.owned.last().fj.x, 0.0)
        rs.buy(offer("j_joker"), free = true)          // defaults
        assertEquals(1.0, rs.owned.last().fj.x, 0.0)
        assertEquals(0.0, rs.owned.last().fj.chips, 0.0)
    }

    @Test fun sellRefundsHalfCost() {
        val rs = RunState()
        rs.buy(offer("j_joker", cost = 6), free = true)
        rs.buy(offer("j_joker", cost = 4), free = true)
        val sizeBefore = rs.owned.size
        val moneyBefore = rs.money
        rs.sell(rs.owned.last())                        // cost 4 -> refund max(1, 4/2)=2
        assertEquals(sizeBefore - 1, rs.owned.size)
        assertEquals(moneyBefore + 2, rs.money)
    }

    @Test fun jollysusSpawnsAJokerWhenAJokerIsSold() {
        val rs = RunState()
        rs.buy(offer("j_cry_jollysus"), free = true)   // spawn armed (fj.n == 1)
        rs.buy(offer("j_joker"), free = true)
        rs.buy(offer("j_joker"), free = true)
        val before = rs.owned.size
        rs.sell(rs.owned.last())                        // sell -> jollysus spawns one (net 0)
        assertEquals(before, rs.owned.size)
    }
}
