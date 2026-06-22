package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The payoff of the manifest: each joker's behaviour is PURE and testable in isolation — its scoring
 * hooks and state reducer, with no scoring cascade. The old scattered when-blocks could only be exercised
 * end-to-end through the oracle; these jokers can now be unit-tested directly.
 */
class JokerManifestTest {
    private fun spec(key: String) = JOKER_MANIFEST.getValue(key)
    private fun ctx(handType: HandType) = Sctx().apply { scoringName = handType; pokerHands = setOf(handType) }

    @Test fun strongholdFiresOnBulwarkOnly() {
        val s = FJokerState()
        assertEquals(5.0, spec("j_cry_stronghold").jokerMain!!(s, ctx(HandType.CRY_BULWARK))!!.xMultMod, 0.0)
        assertNull(spec("j_cry_stronghold").jokerMain!!(s, ctx(HandType.PAIR)))   // no Bulwark in pokerHands → no fire
    }

    @Test fun bonkInitScalingAndPerJoker() {
        val bonk = spec("j_cry_bonk")
        // initial state co-located with the spec
        assertEquals(6.0, bonk.initialState.chips, 0.0)
        assertEquals(3.0, bonk.initialState.xc, 0.0)
        // before-pass reducer: +1 chip on a Pair, no-op on other hands
        val afterPair = bonk.reduce!!(bonk.initialState, GameEvent.BeforeHand(ctx(HandType.PAIR)))
        assertEquals(7.0, afterPair.chips, 0.0)
        assertEquals(6.0, bonk.reduce!!(bonk.initialState, GameEvent.BeforeHand(ctx(HandType.FLUSH))).chips, 0.0)
        // other-joker hook: +chips per joker; x by xc for a Jolly-type joker
        assertEquals(7.0, bonk.otherJoker!!(afterPair, ctx(HandType.PAIR), FJoker("j_joker"))!!.chipMod, 0.0)
        assertEquals(21.0, bonk.otherJoker!!(afterPair, ctx(HandType.PAIR), FJoker("j_jolly"))!!.chipMod, 0.0)
    }

    @Test fun greenJokerAccumulatesAndFloorsAtZero() {
        val green = spec("j_green_joker")
        var s = FJokerState()
        s = green.reduce!!(s, GameEvent.HandScored(HandType.PAIR))   // +1
        s = green.reduce!!(s, GameEvent.HandScored(HandType.PAIR))   // +1
        s = green.reduce!!(s, GameEvent.Discarded(emptyList()))      // -1
        assertEquals(1.0, s.mult, 0.0)
        assertEquals(1.0, green.jokerMain!!(s, ctx(HandType.PAIR))!!.multMod, 0.0)
        // never below zero; mult 0 → joker_main does not fire
        val floored = green.reduce!!(FJokerState(), GameEvent.Discarded(emptyList()))
        assertEquals(0.0, floored.mult, 0.0)
        assertNull(green.jokerMain!!(floored, ctx(HandType.PAIR)))
    }
}
