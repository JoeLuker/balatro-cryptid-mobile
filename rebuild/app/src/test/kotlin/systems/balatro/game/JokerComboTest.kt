package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Adversarial verification of multi-joker scoring combos not covered by the per-mechanism unit tests:
 * an X-mult individual (Photograph) copied by Blueprint, and two retrigger sources stacking on the
 * same card. Isolated file (not Oracle.kt). King = 10 chips; Photograph = X2 Mult on the first scored
 * face card; Sock&Buskin retriggers faces (+1); Hanging Chad retriggers the first scored card (+2).
 */
class JokerComboTest {
    private fun king(suit: Suit, ed: String = "") = PlayingCard(suit, 13, edition = ed)
    private fun score(cards: List<PlayingCard>, jokers: List<FJoker>) = Score.score(cards, jokers).score

    @Test fun photographXMultCopiedByBlueprint() {
        // Pair of Kings + [Blueprint, Photograph]: first King gets Photograph X2 AND Blueprint's copy X2.
        // chips 10 + 2*10 = 30; mult 2 * 2 * 2 = 8 → 240.
        assertEquals(240.0, score(
            listOf(king(Suit.S), king(Suit.H)),
            listOf(FJoker("j_blueprint"), FJoker("j_photograph"))), 0.0)
    }

    @Test fun sockAndHangingChadStackOnFirstCard() {
        // Pair of Kings + Sock + Hanging Chad: first King 4 triggers (base + sock + chad×2), second 2.
        // chips 10 + 6*10 = 70; mult 2 → 140.
        assertEquals(140.0, score(
            listOf(king(Suit.S), king(Suit.H)),
            listOf(FJoker("j_sock_and_buskin"), FJoker("j_hanging_chad"))), 0.0)
    }

    @Test fun holoFirstCardRetriggeredByHangingChad() {
        // First King Holo + Hanging Chad: first King 3 triggers (base + chad×2), each +10 Holo Mult.
        // chips 10 + 4*10 = 50; mult 2 + 3*10 = 32 → 1600.
        assertEquals(1600.0, score(
            listOf(king(Suit.S, "Holo"), king(Suit.H)),
            listOf(FJoker("j_hanging_chad"))), 0.0)
    }
}
