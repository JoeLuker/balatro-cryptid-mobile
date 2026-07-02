package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vouchers — the permanent run-modifiers a player redeems in the shop. Each mutates one RunState
 * field (the foundation the economy/round systems read). Plain state the oracle never touches; a
 * wrong target field or a missed effect is silent until a run behaves oddly several blinds later.
 */
class VoucherTest {
    private fun voucher(key: String, extra: Int) = VoucherOffer(key, key, "", extra)  // cost defaults to 10
    private fun run(money: Int = 50) = RunState().apply { this.money = money }
    /** Stage the voucher as the shop's live offer, then redeem — redeemVoucher is identity-gated
     *  against stale/double-tap invocations, so only the current shopVoucher can be redeemed. */
    private fun RunState.redeem(v: VoucherOffer) { shopVoucher = v; redeemVoucher(v) }

    @Test fun redeemSpendsRecordsAndApplies() {
        val rs = run(50)
        rs.redeem(voucher("v_overstock_norm", 1))
        assertEquals("spends the voucher cost", 40, rs.money)
        assertTrue("records the redemption", "v_overstock_norm" in rs.redeemedVouchers)
        assertEquals("Overstock: +1 shop slot", 1, rs.shopSlotsBonus)
    }

    @Test fun eachVoucherMutatesItsRunModifier() {
        run().also { it.redeem(voucher("v_clearance_sale", 25)) }
            .also { assertEquals("Clearance Sale → 25% off", 25, it.discountPercent) }
        run().also { it.redeem(voucher("v_reroll_surplus", 2)) }
            .also { assertEquals("Reroll Surplus → base 5 - 2", 3, it.rerollBase) }
        run().also { it.redeem(voucher("v_grabber", 1)) }
            .also { assertEquals("Grabber → +1 hand/round", 5, it.baseHands) }
        run().also { it.redeem(voucher("v_wasteful", 1)) }
            .also { assertEquals("Wasteful → +1 discard/round", 4, it.baseDiscards) }
        run().also { it.redeem(voucher("v_seed_money", 50)) }
            .also { assertEquals("Seed Money → interest cap 50/5 = \$10", 10, it.interestCap) }
    }

    @Test fun redeemIsRefusedWhenUnaffordable() {
        val rs = run(5)                                  // cost 10 > 5
        rs.redeem(voucher("v_grabber", 1))
        assertEquals(5, rs.money)
        assertEquals("no effect when refused", 4, rs.baseHands)
        assertFalse("v_grabber" in rs.redeemedVouchers)
    }
}
