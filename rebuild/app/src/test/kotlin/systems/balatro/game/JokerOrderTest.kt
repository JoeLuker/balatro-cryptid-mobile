package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Adversarial verification that joker board ORDER changes the score by sequencing additive vs
 * multiplicative mult — the core reason the play-HUD reorder feature matters (beyond Blueprint/
 * Brainstorm). j_joker = +4 Mult, j_cavendish = X3 Mult (both unconditional); jokers apply in
 * board order during the joker_main pass. Pair of Aces = 32 chips, base mult 2.
 */
class JokerOrderTest {
    private fun pairAces(vararg jokers: FJoker) = Score.score(PlayingCard.hand("S_A", "H_A"), jokers.toList()).score

    @Test fun additiveBeforeMultiplicative() {
        // [+4, X3]: (2 + 4) * 3 = 18 → 32 * 18 = 576.
        assertEquals(576.0, pairAces(FJoker("j_joker"), FJoker("j_cavendish")), 0.0)
    }

    @Test fun multiplicativeBeforeAdditive() {
        // [X3, +4]: (2 * 3) + 4 = 10 → 32 * 10 = 320. Same jokers, swapped order → different score.
        assertEquals(320.0, pairAces(FJoker("j_cavendish"), FJoker("j_joker")), 0.0)
    }
}
