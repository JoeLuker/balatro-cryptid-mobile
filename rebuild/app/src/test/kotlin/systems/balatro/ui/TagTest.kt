package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skip tags — trigger-based run-modifiers (the companion to vouchers and boss debuffs). Each fires at
 * its own [TagTrigger] (eval / round-start / shop-start / shop-final) and is then consumed. Plain
 * state the oracle never touches; this pins that each tag applies its grant at the right moment, is
 * removed once fired, and does NOT fire at the wrong trigger.
 */
class TagTest {
    @Test fun investmentGrants25AtEval() {
        val rs = RunState()
        rs.tags.add(Tag.INVESTMENT)
        val before = rs.money
        rs.enterRoundEval()                          // EVAL trigger (after defeating the blind)
        assertEquals(before + 25, rs.money)
        assertFalse("a fired tag is consumed", Tag.INVESTMENT in rs.tags)
    }

    @Test fun juggleAddsHandSizeAtRoundStart() {
        val rs = RunState().apply { phase = Phase.BLIND_SELECT }
        rs.tags.add(Tag.JUGGLE)
        rs.selectBlind()                             // → startRound: handSize = baseHandSize (8) + 3
        assertEquals(11, rs.handSize)
        assertFalse(Tag.JUGGLE in rs.tags)
    }

    @Test fun d6AndCouponApplyAtShopStartAndFinal() {
        val rs = RunState().apply { money = 50 }
        rs.tags.add(Tag.D_SIX); rs.tags.add(Tag.COUPON)
        rs.toShopForPreview()                        // fires SHOP_START then SHOP_FINAL
        assertTrue("D6 → free rerolls this shop", rs.freeRerollThisShop)
        assertTrue("Coupon → free shop this shop", rs.couponThisShop)
        assertFalse(Tag.D_SIX in rs.tags)
        assertFalse(Tag.COUPON in rs.tags)
    }

    @Test fun aTagDoesNotFireAtTheWrongTrigger() {
        val rs = RunState().apply { money = 50 }
        rs.tags.add(Tag.INVESTMENT)                  // EVAL only
        rs.toShopForPreview()                        // SHOP triggers must not consume it
        assertTrue("Investment is still pending after a shop transition", Tag.INVESTMENT in rs.tags)
        assertEquals(50, rs.money)
    }
}
