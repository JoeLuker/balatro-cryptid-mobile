package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Adversarial verification of the HELD-in-hand scoring pass (distinct from played-card scoring):
 * Baron (X1.5 per held King), held Steel (X1.5), and Mime (retriggers held cards that produced an
 * effect). Each combo below resolves to X2.25 over a played Pair of 2s (chips 10 + 2 + 2 = 14;
 * base mult 2) → 14 × 4.5 = 63, but via a different held mechanism. Isolated file (not Oracle.kt).
 */
class HeldCardComboTest {
    private val pairOf2s = listOf(PlayingCard(Suit.S, 2), PlayingCard(Suit.H, 2))
    private fun score(held: List<PlayingCard>, jokers: List<FJoker>) = Score.score(pairOf2s, jokers, held).score

    @Test fun baronTwoHeldKings() {
        // Baron: each held King → X1.5. Two held Kings → X1.5^2 = X2.25. → 63.
        assertEquals(63.0, score(
            listOf(PlayingCard(Suit.S, 13), PlayingCard(Suit.H, 13)), listOf(FJoker("j_baron"))), 0.0)
    }

    @Test fun mimeDoublesHeldSteel() {
        // Held Steel Queen → X1.5; Mime retriggers it (it produced an effect) → X1.5 twice = X2.25. → 63.
        assertEquals(63.0, score(
            listOf(PlayingCard(Suit.S, 12, enhancement = Enhancement.STEEL)), listOf(FJoker("j_mime"))), 0.0)
    }

    @Test fun steelAndBaronStackOnHeldKing() {
        // One held Steel King: Steel X1.5 AND Baron X1.5 both fire in the held pass → X2.25. → 63.
        assertEquals(63.0, score(
            listOf(PlayingCard(Suit.S, 13, enhancement = Enhancement.STEEL)), listOf(FJoker("j_baron"))), 0.0)
    }
}
