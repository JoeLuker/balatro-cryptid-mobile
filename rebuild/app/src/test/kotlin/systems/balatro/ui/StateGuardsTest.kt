package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import systems.balatro.game.FJoker

/**
 * Re-invocation guards on one-shot state mutations (docs/REVIEW-2026-07-01.md): a fast double-tap
 * used to duplicate sell refunds and re-apply voucher effects, and Owned's data-class equality made
 * removal ambiguous between identical jokers. All guards are identity-based.
 */
class StateGuardsTest {

    private fun joker(key: String, cost: Int = 4) =
        Owned(Offer(key, key, "test", cost), FJoker(key))

    @Test fun sellingTheSameJokerTwicePaysOnce() {
        val rs = RunState().apply { money = 0 }
        rs.owned.clear()
        rs.owned.add(joker("j_joker"))
        val victim = joker("j_greedy_joker", cost = 6)
        rs.owned.add(victim)
        rs.sell(victim)
        val afterFirst = rs.money
        assertTrue("first sell pays the refund", afterFirst > 0)
        assertEquals(1, rs.owned.size)
        assertEquals(1, rs.jokersSold.size)
        rs.sell(victim)                                   // stale double-tap: must be a no-op
        assertEquals("second sell must not pay again", afterFirst, rs.money)
        assertEquals(1, rs.owned.size)
        assertEquals("sale history must not double-record", 1, rs.jokersSold.size)
    }

    @Test fun sellingOneOfTwoIdenticalJokersRemovesExactlyThatOne() {
        // Owned is a data class — two fresh copies of the same joker compare EQUAL. Removal must
        // go by identity or selling one could remove the other (and strand the tapped card).
        val rs = RunState().apply { money = 0 }
        rs.owned.clear()
        val a = joker("j_joker"); val b = joker("j_joker")
        rs.owned.add(a); rs.owned.add(b)
        rs.sell(a)
        assertEquals(1, rs.owned.size)
        assertTrue("the untapped twin must survive", rs.owned[0] === b)
    }

    @Test fun redeemVoucherIsOneShot() {
        val rs = RunState().apply { money = 100 }
        val v = VoucherOffer("v_grabber", "Grabber", "+1 hand", 1, 10)
        rs.shopVoucher = v
        val handsBefore = rs.baseHands
        rs.redeemVoucher(v)
        assertEquals("effect applies once", handsBefore + 1, rs.baseHands)
        val moneyAfter = rs.money
        rs.redeemVoucher(v)                               // stale double-tap
        assertEquals("no second charge", moneyAfter, rs.money)
        assertEquals("run effect must not re-apply", handsBefore + 1, rs.baseHands)
    }

    @Test fun buyingAStaleShopOfferIsANoOp() {
        val rs = RunState().apply { money = 100 }
        rs.toShopForPreview()
        val jk = rs.shopItems.filterIsInstance<ShopItem.Jk>().firstOrNull()
        assertNotNull("deterministic first shop roll should stock a joker", jk)
        val ownedBefore = rs.owned.size
        rs.buy(jk!!.offer)
        val afterFirst = rs.money
        assertEquals(ownedBefore + 1, rs.owned.size)
        rs.buy(jk.offer)                                  // offer left the shop on the first buy
        assertEquals("no second charge", afterFirst, rs.money)
        assertEquals("no duplicate joker", ownedBefore + 1, rs.owned.size)
    }

    @Test fun buyingAStaleBoosterIsANoOp() {
        val rs = RunState().apply { money = 100 }
        rs.toShopForPreview()
        val b = rs.shopBoosters.firstOrNull()
        assertNotNull("first shop rolls boosters", b)
        rs.buyBooster(b!!)
        val afterFirst = rs.money
        rs.buyBooster(b)                                  // stale: gone from shopBoosters
        assertEquals("no second charge / reopen", afterFirst, rs.money)
    }
}
