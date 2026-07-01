package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boss-blind debuffs — how each [Debuff] bends scoring. The oracle has a few cases, but the debuff →
 * score effect is worth pinning directly: Flint halves the base, the suit/face/card debuffs make
 * matching played cards score nothing (the hand class is still detected from all cards; only their
 * chip contribution is suppressed). Debuff.None must be byte-identical to an undebuffed hand.
 */
class BlindsTest {
    private fun score(hand: List<PlayingCard>, debuff: Debuff) = Score.score(hand, emptyList(), debuff = debuff).score
    private val pairAces = PlayingCard.hand("S_A", "H_A")   // PAIR base (10 chips, 2 mult); each Ace = 11 chips

    @Test fun noDebuffIsTheBaseline() {
        assertEquals(64.0, score(pairAces, Debuff.None), 0.0)                       // (10 + 11 + 11) × 2
    }

    @Test fun flintHalvesTheBaseChipsAndMult() {
        // base 10 → 5 chips, 2 → 1 mult (floored); the two Aces still add 11 each
        assertEquals(27.0, score(pairAces, Debuff.Flint), 0.0)                      // (5 + 22) × 1
    }

    @Test fun suitDebuffMakesThatSuitScoreNothing() {
        // S_A is a Spade → suppressed; only H_A's 11 chips land. Hand is still detected as a Pair.
        assertEquals(42.0, score(pairAces, Debuff.DebuffSuit(Suit.S)), 0.0)         // (10 + 11) × 2
    }

    @Test fun faceDebuffMakesFaceCardsScoreNothing() {
        val pairKings = PlayingCard.hand("S_K", "H_K")                              // Kings are faces, 10 chips each
        assertEquals(60.0, score(pairKings, Debuff.None), 0.0)                      // (10 + 10 + 10) × 2
        assertEquals(20.0, score(pairKings, Debuff.DebuffFace), 0.0)               // both Kings suppressed: 10 × 2
    }

    @Test fun allCardsDebuffLeavesOnlyTheBase() {
        assertEquals(20.0, score(pairAces, Debuff.DebuffAllCards), 0.0)            // 10 × 2 (no card scores)
    }

    @Test fun specificCardDebuffSkipsThatInstance() {
        // THE_PILLAR: a card instance played earlier this Ante scores nothing now.
        assertEquals(42.0, score(pairAces, Debuff.DebuffCards(setOf(PlayingCard(Suit.S, 14)))), 0.0)  // S_A skipped
    }
}
