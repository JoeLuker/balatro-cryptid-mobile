package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import systems.balatro.content.Edition

/** initialFJoker seeds every acquisition path (buy / Wraith / jollysus spawn) identically. Verifies the
 *  per-key initial scaling state the scoring engine reads — previously this lived inline in buy() and had
 *  no test, so a mis-seed (e.g. a newly-acquirable cry joker) could silently no-op. */
class InitialFJokerTest {
    private fun make(key: String) = initialFJoker(Offer(key, key, "", 0), 0.0)

    @Test fun seedsSpecialJokerState() {
        assertEquals(7.0, make("j_cry_biggestm").x, 0.0)      // config.extra.xmult — read as xMultMod
        assertEquals(1.05, make("j_cry_mprime").x, 0.0)       // ^Emult exponent (must be > 1.0 to fire)
        assertEquals(1.75, make("j_cry_caramel").x, 0.0)
        assertEquals(2.0, make("j_cry_starfruit").x, 0.0)
        assertEquals(2.0, make("j_ramen").x, 0.0)
        assertEquals(4, make("j_cry_busdriver").n)            // before-hand roll odds
        assertEquals(8, make("j_cry_chili_pepper").n)         // perishable countdown
        assertEquals(11, make("j_cry_caramel").n)
        assertEquals(1, make("j_cry_mstack").n)               // retriggers >= 1
        assertEquals(1, make("j_cry_jollysus").n)             // spawn flag armed
        assertEquals(6.0, make("j_cry_bonk").chips, 0.0)      // +chips per board joker
        assertEquals(3.0, make("j_cry_bonk").xc, 0.0)         // Jolly x-chips multiplier
        assertEquals(20.0, make("j_popcorn").mult, 0.0)
        assertEquals(1.01, make("j_cry_primus").x, 0.0)
        assertTrue(make("j_cry_blacklist").n in 2..14)        // random blacklisted rank id
        assertEquals(0.1 * CRYPTID_MEMBER_COUNT, make("j_cry_membershipcard").x, 0.0)          // 3859.8 → xMultMod
        assertEquals(CRYPTID_MEMBER_COUNT.toDouble(), make("j_cry_membershipcardtwo").chips, 0.0)  // 38598 → +chips
    }

    @Test fun swashbucklerReadsSellSum() {
        assertEquals(17.0, initialFJoker(Offer("j_swashbuckler", "", "", 0), 17.0).mult, 0.0)
    }

    @Test fun plainJokerGetsDefaults() {
        val j = make("j_joker")
        assertEquals(0, j.n)
        assertEquals(1.0, j.x, 0.0)
        assertEquals(0.0, j.mult, 0.0)
        assertEquals(0.0, j.chips, 0.0)
        assertEquals(1.0, j.xc, 0.0)
    }

    @Test fun editionMapsToFjEdition() {
        assertEquals("Foil", initialFJoker(Offer("j_joker", "", "", 0, edition = Edition.FOIL), 0.0).edition)
        assertEquals("", initialFJoker(Offer("j_joker", "", "", 0), 0.0).edition)
    }
}
