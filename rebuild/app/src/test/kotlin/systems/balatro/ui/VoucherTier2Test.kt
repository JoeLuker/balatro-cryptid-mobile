package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The added tier-2 / extra vouchers (Crystal Ball, Overstock Plus, Reroll Glut, Money Tree). Each
 * mutates one RunState run-modifier; separate file from VoucherTest to keep the original six untouched.
 */
class VoucherTier2Test {
    private fun voucher(key: String, extra: Int, cost: Int = 10) = VoucherOffer(key, key, "", extra, cost)
    private fun run(money: Int = 200) = RunState().apply { this.money = money }

    @Test fun crystalBallAddsConsumableSlot() {
        val rs = run()
        assertEquals(2, rs.consumableSlots)
        rs.redeemVoucher(voucher("v_crystal_ball", 1))
        assertEquals(3, rs.consumableSlots)
    }

    @Test fun overstockPlusAddsShopSlot() {
        val rs = run()
        rs.redeemVoucher(voucher("v_overstock_plus", 1))
        assertEquals(1, rs.shopSlotsBonus)
    }

    @Test fun rerollGlutLowersRerollBase() {
        val rs = run()
        val before = rs.rerollBase
        rs.redeemVoucher(voucher("v_reroll_glut", 2))
        assertEquals(before - 2, rs.rerollBase)
    }

    @Test fun moneyTreeRaisesInterestCapTo20() {
        val rs = run()
        rs.redeemVoucher(voucher("v_money_tree", 100, 100))
        assertEquals(20, rs.interestCap)
    }

    @Test fun tarotMerchantDoublesTarotRate() {
        val rs = run()
        rs.redeemVoucher(voucher("v_tarot_merchant", 4))
        assertEquals(8.0, rs.tarotRate, 0.0)   // 4 base + 4
    }

    @Test fun planetMerchantDoublesPlanetRate() {
        val rs = run()
        rs.redeemVoucher(voucher("v_planet_merchant", 4))
        assertEquals(8.0, rs.planetRate, 0.0)
    }

    @Test fun antimatterAddsAJokerSlot() {
        val rs = run()
        rs.redeemVoucher(voucher("v_antimatter", 1))
        assertEquals(1, rs.deckJokerBonus)
    }

    @Test fun directorsCutRerollsTheBossOncePerAnte() {
        val rs = run(); rs.redeemVoucher(voucher("v_directors_cut", 0))
        rs.phase = Phase.BLIND_SELECT; rs.blindIndex = 2          // ante 1 boss blind
        org.junit.Assert.assertTrue(rs.canRerollBoss())
        rs.rerollBoss()
        org.junit.Assert.assertFalse(rs.canRerollBoss())          // limit: 1 per ante
        assertEquals(1, rs.bossReshuffle)                         // seed advanced (boss re-picked)
    }

    @Test fun retconRerollsTheBossUnlimited() {
        val rs = run(); rs.redeemVoucher(voucher("v_retcon", 0))
        rs.phase = Phase.BLIND_SELECT; rs.blindIndex = 2
        rs.rerollBoss(); rs.rerollBoss()
        org.junit.Assert.assertTrue(rs.canRerollBoss())           // never limited
        assertEquals(2, rs.bossReshuffle)
    }

    @Test fun omenGlobeEnablesSpectralsInArcanaPacks() {
        val rs = run(); rs.redeemVoucher(voucher("v_omen_globe", 0))
        org.junit.Assert.assertTrue(rs.omenGlobe)
    }
}
