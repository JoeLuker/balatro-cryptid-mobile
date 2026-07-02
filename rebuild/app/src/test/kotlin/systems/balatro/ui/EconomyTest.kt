package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shop economy — money in/out across buy, sell, reroll, discount, and end-of-round interest.
 * Pure RunState arithmetic (no Compose/Android beyond the no-op'd logger), so it runs off-device.
 * The oracle proves SCORING; nothing else pins the economy, where an off-by-one in a refund, a
 * discount floor, or the reroll-cost escalation is silent until a player notices wrong money.
 */
class EconomyTest {
    private fun offer(key: String, cost: Int) = Offer(key, key, "", cost)

    @Test fun priceAppliesDiscountWithAFloorAndCoupon() {
        val rs = RunState()
        assertEquals(10, rs.price(10))                 // no discount
        rs.discountPercent = 25
        assertEquals(7, rs.price(10))                  // max(1, 10*75/100) = 7
        assertEquals(1, rs.price(1))                   // floored at 1 (10*… rounds to 0 → 1)
        rs.couponThisShop = true
        assertEquals(0, rs.price(10))                  // coupon = 100% off → free
    }

    @Test fun buySpendsTheDiscountedCost() {
        val rs = RunState().apply { money = 20; discountPercent = 25 }
        val before = rs.owned.size
        val o = offer("j_joker", cost = 10)
        rs.shopItems = listOf(ShopItem.Jk(o))          // stage as live offer (paid buys are identity-gated)
        rs.buy(o)                                      // price = 7
        assertEquals(13, rs.money)
        assertEquals(before + 1, rs.owned.size)
    }

    @Test fun buyIsRefusedWhenUnaffordable() {
        val rs = RunState().apply { money = 5 }
        val before = rs.owned.size
        val o = offer("j_joker", cost = 10)
        rs.shopItems = listOf(ShopItem.Jk(o))
        rs.buy(o)                                      // 10 > 5 → no purchase
        assertEquals(5, rs.money)
        assertEquals(before, rs.owned.size)
    }

    @Test fun freeBuyIgnoresCost() {
        val rs = RunState().apply { money = 5 }
        val before = rs.owned.size
        rs.buy(offer("j_joker", cost = 10), free = true)
        assertEquals(5, rs.money)                      // free → no spend
        assertEquals(before + 1, rs.owned.size)
    }

    @Test fun buyIsRefusedWhenJokerSlotsAreFull() {
        val rs = RunState().apply { money = 100 }      // starts with 1 joker (init), maxJokers = 5
        repeat(4) { rs.buy(offer("j_joker", cost = 0), free = true) }
        assertEquals(5, rs.owned.size)
        rs.buy(offer("j_joker", cost = 0), free = true)  // 6th → slot full
        assertEquals(5, rs.owned.size)
    }

    @Test fun sellRefundsHalfTheCostFlooredAtOne() {
        val rs = RunState()
        rs.buy(offer("j_joker", cost = 6), free = true)
        rs.buy(offer("j_joker", cost = 1), free = true)
        val m = rs.money
        rs.sell(rs.owned.last())                       // cost 1 → refund max(1, 0) = 1
        assertEquals(m + 1, rs.money)
        rs.sell(rs.owned.last())                       // cost 6 → refund max(1, 3) = 3
        assertEquals(m + 4, rs.money)
    }

    @Test fun sellKeepsAtLeastOneJoker() {
        val rs = RunState()                            // only the starter joker
        val m = rs.money
        rs.sell(rs.owned.first())                      // size == 1 → refused
        assertEquals(1, rs.owned.size)
        assertEquals(m, rs.money)
    }

    @Test fun rerollCostEscalatesPerShopAndResets() {
        val rs = RunState().apply { money = 20; phase = Phase.SHOP }
        rs.reroll()                                    // base 5 + 0
        assertEquals(15, rs.money); assertEquals(1, rs.rerollIncrease)
        rs.reroll()                                    // 5 + 1
        assertEquals(9, rs.money); assertEquals(2, rs.rerollIncrease)
        rs.resetRerollCost()                           // entering a fresh shop
        assertEquals(0, rs.rerollIncrease)
        assertEquals(5, rs.rerollCost)                 // back to base
    }

    @Test fun interestIsOneDollarPerFiveHeldCappedByInterestCap() {
        val base = RunState().apply { money = 42 }     // default cap 5
        base.toEvalForPreview()
        assertEquals(5, base.evalRows.first { it.kind == EvalKind.INTEREST }.dollars)  // min(42/5=8, 5)
        val seedMoney = RunState().apply { money = 42; interestCap = 10 }  // Seed Money raises the cap
        seedMoney.toEvalForPreview()
        assertEquals(8, seedMoney.evalRows.first { it.kind == EvalKind.INTEREST }.dollars)  // min(8, 10)
    }
}
