package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Poker-hand evaluation — Hands.evaluate, the detection every score depends on. The oracle exercises
 * it only THROUGH a final score, so a wrong hand class or a missing downgrade can hide behind matching
 * arithmetic. These pin detection directly, including the `context.poker_hands` SET — a higher hand
 * must expose the lower hands it contains (a once-real bug: a Four of a Kind that omitted Three of a
 * Kind silently disabled Wily Joker) — and the Four Fingers / Shortcut / Smeared joker hooks.
 */
class HandsTest {
    private fun eval(vararg keys: String) = Hands.evaluate(PlayingCard.hand(*keys))
    private fun type(vararg keys: String) = eval(*keys).first

    @Test fun detectsEveryHandClass() {
        assertEquals(HandType.HIGH_CARD, type("S_2", "H_5", "D_7", "C_9", "S_J"))
        assertEquals(HandType.PAIR, type("S_A", "H_A"))
        assertEquals(HandType.TWO_PAIR, type("S_A", "H_A", "S_K", "H_K"))
        assertEquals(HandType.THREE_OF_A_KIND, type("S_A", "H_A", "D_A"))
        assertEquals(HandType.STRAIGHT, type("S_5", "H_6", "D_7", "C_8", "S_9"))
        assertEquals(HandType.FLUSH, type("S_2", "S_5", "S_7", "S_9", "S_J"))
        assertEquals(HandType.FULL_HOUSE, type("S_A", "H_A", "D_A", "S_K", "H_K"))
        assertEquals(HandType.FOUR_OF_A_KIND, type("S_A", "H_A", "D_A", "C_A"))
        assertEquals(HandType.STRAIGHT_FLUSH, type("S_5", "S_6", "S_7", "S_8", "S_9"))
        assertEquals(HandType.FIVE_OF_A_KIND, type("S_A", "H_A", "D_A", "C_A", "S_A"))
    }

    @Test fun aceFormsBothTheWheelAndBroadwayStraights() {
        assertEquals(HandType.STRAIGHT, type("S_A", "H_2", "D_3", "C_4", "S_5"))   // A-2-3-4-5
        assertEquals(HandType.STRAIGHT, type("S_T", "H_J", "D_Q", "C_K", "S_A"))   // 10-J-Q-K-A
    }

    @Test fun pokerHandsSetExposesEveryDowngrade() {
        val fourK = eval("S_A", "H_A", "D_A", "C_A").third
        assertTrue("Four of a Kind must contain Three of a Kind (the Wily bug)", HandType.THREE_OF_A_KIND in fourK)
        assertTrue("…and Pair", HandType.PAIR in fourK)
        assertTrue(HandType.HIGH_CARD in fourK)

        val fullH = eval("S_A", "H_A", "D_A", "S_K", "H_K").third
        assertTrue(HandType.THREE_OF_A_KIND in fullH)
        assertTrue(HandType.TWO_PAIR in fullH)
        assertTrue(HandType.PAIR in fullH)

        val sf = eval("S_5", "S_6", "S_7", "S_8", "S_9").third
        assertTrue("Straight Flush must contain Straight", HandType.STRAIGHT in sf)
        assertTrue("…and Flush", HandType.FLUSH in sf)
    }

    @Test fun fourFingersLowersFlushAndStraightToFourCards() {
        assertEquals(HandType.FLUSH, Hands.evaluate(PlayingCard.hand("S_2", "S_5", "S_7", "S_9"), fourFingers = true).first)
        assertEquals(HandType.STRAIGHT, Hands.evaluate(PlayingCard.hand("S_6", "H_7", "D_8", "C_9"), fourFingers = true).first)
        // …and without Four Fingers, four cards are NOT a flush/straight
        assertEquals(HandType.HIGH_CARD, type("S_2", "S_5", "S_7", "S_9"))
    }

    @Test fun shortcutAllowsAOneGapStraight() {
        assertEquals(HandType.HIGH_CARD, type("S_5", "H_6", "D_8", "C_9", "S_T"))     // 5-6-_-8-9-10: gap at 7
        assertEquals(HandType.STRAIGHT, Hands.evaluate(PlayingCard.hand("S_5", "H_6", "D_8", "C_9", "S_T"), shortcut = true).first)
    }

    @Test fun smearedCollapsesSuitColoursForAFlush() {
        // 3 hearts + 2 diamonds: a Flush only once Smeared makes the red suits collide
        assertEquals(HandType.HIGH_CARD, type("H_2", "D_5", "H_7", "D_9", "H_J"))
        assertEquals(HandType.FLUSH, Hands.evaluate(PlayingCard.hand("H_2", "D_5", "H_7", "D_9", "H_J"), smeared = true).first)
    }
}
