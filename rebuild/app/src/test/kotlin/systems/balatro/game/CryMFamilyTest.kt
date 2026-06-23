package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity-audit regression tests for the cry-m-family jokers (j_cry_m, j_cry_mstack, j_cry_jimball).
 *
 * Two bugs were fixed:
 *
 * Bug 1 — is_jolly() narrowing (j_cry_m, j_cry_mstack):
 *   Lua: `context.card:is_jolly()` (lib/misc.lua:302) covers THREE kinds of joker:
 *     a) key == "Jolly Joker"     → j_jolly
 *     b) key == "cry-jollysus Joker" → j_cry_jollysus
 *     c) edition.key == "e_cry_m" → any joker with the M edition
 *   The Kotlin was checking `soldKey == "j_jolly"` only, missing (b) and (c).
 *   Fix: RunScreen.sell() now uses `o.fj.isJolly()` (JokerManifest.kt:155).
 *
 * Bug 2 — jimball pre/post-increment timing (j_cry_jimball):
 *   Lua: fires in `context.before`, reading G.GAME.hands[scoring_name].played BEFORE the
 *   current play is recorded (misc_joker.lua:1626). Any other visible hand type with
 *   v.played >= play_more_than resets x to 1.
 *   Kotlin was using `handPlayed(r.handType)` (post-increment), which is one higher than
 *   the Lua value. Consequence: on the FIRST ever play of a hand type, Lua resets (0>=0
 *   for every other visible hand), but the old Kotlin would grow (+0.15).
 *   Fix: RunScreen uses `handPlayed(r.handType) - 1` as the pre-increment equivalent.
 */
class CryMFamilyTest {

    // ─────────────────────────────────────────────────────────────────────────
    // is_jolly() correctness (FJoker.isJolly, JokerManifest.kt:155)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun isJolly_matches_j_jolly_key() {
        assertTrue(FJoker("j_jolly").isJolly())
    }

    @Test fun isJolly_matches_j_cry_jollysus_key() {
        // Selling a jollysus must also trigger j_cry_m and j_cry_mstack (lib/misc.lua:303).
        assertTrue(FJoker("j_cry_jollysus").isJolly())
    }

    @Test fun isJolly_matches_cry_m_edition() {
        // Any joker with the M edition counts as a Jolly (lib/misc.lua:306).
        assertTrue(FJoker("j_joker", edition = "cry_m").isJolly())
        assertTrue(FJoker("j_cry_bonk", edition = "cry_m").isJolly())
    }

    @Test fun isJolly_does_not_match_plain_joker() {
        assertFalse(FJoker("j_joker").isJolly())
        assertFalse(FJoker("j_joker", edition = "Foil").isJolly())
        assertFalse(FJoker("j_joker", edition = "Poly").isJolly())
    }

    @Test fun isJolly_does_not_match_jollysus_edition_other_key() {
        // "cry_m" edition check is on the edition field, not the key — other editions don't count.
        assertFalse(FJoker("j_cry_m").isJolly())   // j_cry_m itself has no edition by default
    }

    // ─────────────────────────────────────────────────────────────────────────
    // j_cry_jimball accumulation — pre-increment fix (RunScreen when-block)
    //
    // We test the comparison formula directly since the accumulation lives in
    // RunScreen's when-block, not a MANIFEST reducer.  The helper below mirrors
    // the fixed RunScreen code exactly.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors RunScreen's jimball when-block after the pre-increment fix:
     *   val playMoreThan = handPlayed(currentType) - 1
     *   val beaten = _handPlayed.any { (h, n) -> h != currentType && n >= playMoreThan }
     *   if (beaten) { if (x > 1.0) x = 1.0 } else x += 0.15
     *
     * [handPlays] is the post-increment snapshot (_handPlayed after recordHandPlayed).
     */
    private fun jimballStep(handPlays: Map<HandType, Int>, currentType: HandType, currentX: Double): Double {
        val playMoreThan = (handPlays[currentType] ?: 0) - 1   // pre-increment equivalent
        val beaten = handPlays.any { (h, n) -> h != currentType && n >= playMoreThan }
        return if (beaten) { if (currentX > 1.0) 1.0 else currentX } else currentX + 0.15
    }

    @Test fun jimball_firstEverPlay_doesNotGrow() {
        // First play of a Pair in a fresh run: post-increment map shows Pair=1, HighCard=0.
        // Lua pre-increment: play_more_than=0.  HighCard.played=0 >= 0 → beaten=true → no growth.
        // Old Kotlin (post-increment): playMoreThan=1; 0 >= 1 false → beaten=false → x grows (WRONG).
        // Fixed Kotlin: playMoreThan=1-1=0; 0 >= 0 → beaten=true → x stays 1.0 (correct).
        val map = mapOf(HandType.PAIR to 1, HandType.HIGH_CARD to 0)
        val xAfter = jimballStep(map, HandType.PAIR, 1.0)
        assertEquals(1.0, xAfter, 0.0)
    }

    @Test fun jimball_dominantHand_grows() {
        // Pair played 3 times, no other hand type ever played.
        // Post-increment: Pair=3 (only key present).  playMoreThan=3-1=2.
        // No other hand type → beaten=false → x grows by 0.15.
        val map = mapOf(HandType.PAIR to 3)
        val xAfter = jimballStep(map, HandType.PAIR, 1.0)
        assertEquals(1.15, xAfter, 1e-9)
    }

    @Test fun jimball_tiedHandAtPreIncrementLevel_resets() {
        // Pair and HighCard both played 2 times; now playing a third Pair.
        // Post-increment snapshot: Pair=3, HighCard=2.
        // Lua pre-increment: play_more_than = 2.  HighCard=2 >= 2 → beaten=true → reset.
        // Old Kotlin (post-increment): playMoreThan=3; HC=2 < 3 → beaten=false → grows (WRONG).
        // Fixed: playMoreThan=3-1=2; HC=2 >= 2 → beaten=true → resets to 1.0.
        val map = mapOf(HandType.PAIR to 3, HandType.HIGH_CARD to 2)
        val xAfter = jimballStep(map, HandType.PAIR, 1.45)
        assertEquals(1.0, xAfter, 0.0)
    }

    @Test fun jimball_strictlyDominant_continues_growing() {
        // Pair played 5 times, HighCard played 3 times (Pair strictly ahead by 2).
        // Post-increment: Pair=5. playMoreThan=5-1=4.  HC=3 < 4 → beaten=false → grows.
        val map = mapOf(HandType.PAIR to 5, HandType.HIGH_CARD to 3)
        val xAfter = jimballStep(map, HandType.PAIR, 1.30)
        assertEquals(1.45, xAfter, 1e-9)
    }

    @Test fun jimball_jokerMain_reads_preAccumulatedX() {
        // The jokerMain read path itself is unaffected by the timing fix — it just reads j.x.
        // Pair aces: chips=32, mult=2.  jimball x=1.5 → Xmult×1.5 → mult=3 → floor(32×3)=96.
        val s = Score.score(PlayingCard.hand("S_A", "H_A"), listOf(FJoker("j_cry_jimball", x = 1.5)))
        assertEquals(96.0, s.score, 0.0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // j_cry_m jokerMain read path (pre-seeded, already covered by Oracle case)
    // Included here to keep the cry-m-family coverage in one place.
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun cryM_jokerMain_readsAccumulatedX() {
        // x=14 (1 Jolly sold → +13 from base 1): Pair aces chips=32, mult=2.
        // XMult(14) → mult=28 → floor(32×28)=896.
        val s = Score.score(PlayingCard.hand("S_A", "H_A"), listOf(FJoker("j_cry_m", x = 14.0)))
        assertEquals(896.0, s.score, 0.0)
    }

    @Test fun cryM_noEffect_atDefaultX() {
        // Default x=1.0 → guard (x > 1.0) false → Effect.None → base score 64.
        val s = Score.score(PlayingCard.hand("S_A", "H_A"), listOf(FJoker("j_cry_m")))
        assertEquals(64.0, s.score, 0.0)
    }
}
