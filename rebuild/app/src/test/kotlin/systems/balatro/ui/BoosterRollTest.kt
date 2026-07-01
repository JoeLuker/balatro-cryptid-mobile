package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shop booster generation parity (vanilla create_card_for_shop booster pool): weighted by the summed
 *  per-pack weights, the first shop guarantees a Buffoon, and commons dominate the distribution. */
class BoosterRollTest {

    @Test fun firstShopGuaranteesABuffoonPack() {
        val b = rollBoosters(blind = 1, firstShop = true)
        assertEquals(2, b.size)
        assertTrue("first shop must include a Buffoon pack", b.any { it.kind == "Buffoon" })
        assertEquals("p_buffoon_normal", b.first().key)   // the guaranteed slot is the normal Buffoon
    }

    @Test fun nonFirstShopsAreNotForcedToBuffoon() {
        // Over many shops without the guarantee, Buffoon should be a minority (weight 1.2/0.6/0.15 of ~21 total).
        val buffoons = (0 until 200).count { blind -> rollBoosters(blind).any { it.kind == "Buffoon" } }
        assertTrue("Buffoon should not dominate non-first shops (saw $buffoons/200)", buffoons < 120)
    }

    @Test fun commonsDominateOverSpectral() {
        // Arcana/Celestial/Standard (weight 4 each) vs Spectral (0.6) — commons must vastly outnumber spectral.
        var common = 0; var spectral = 0
        for (blind in 0 until 300) rollBoosters(blind).forEach {
            when (it.kind) { "Arcana", "Celestial", "Standard" -> common++; "Spectral" -> spectral++ }
        }
        assertTrue("commons ($common) should far exceed spectral ($spectral)", common > spectral * 4)
    }
}
