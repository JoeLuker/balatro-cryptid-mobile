package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Adversarial verification that a card's EDITION re-applies on every retrigger (vanilla: each retrigger
 * re-runs eval_card, so Holo/Foil/Poly score again each time) — combined with Blueprint copying the
 * retrigger joker. Derived from the oracle's verified baselines: Pair of Kings + Sock&Buskin = 100
 * (each King face retriggers → 2 triggers), Pair of Kings + [Blueprint,Sock] = 140 (3 triggers).
 * King = 10 chips; Holo = +10 Mult/trigger; Foil = +50 Chips/trigger.
 */
class EditionRetriggerTest {
    private fun king(suit: Suit, ed: String) = PlayingCard(suit, 13, edition = ed)

    @Test fun holoReappliesPerRetrigger() {
        // Both Kings Holo + Sock: 2 triggers/King. chips 10 + 2*2*10 = 50; mult 2 + (4 triggers * 10) = 42 → 2100.
        // (If Holo applied only once per card, mult would be 22 → 1100.)
        assertEquals(2100.0, Score.score(
            listOf(king(Suit.S, "Holo"), king(Suit.H, "Holo")), listOf(FJoker("j_sock_and_buskin"))).score, 0.0)
    }

    @Test fun foilReappliesPerRetrigger() {
        // Both Kings Foil + Sock: chips 10 + 2*2*10 + (4 triggers * 50) = 250; mult 2 → 500.
        assertEquals(500.0, Score.score(
            listOf(king(Suit.S, "Foil"), king(Suit.H, "Foil")), listOf(FJoker("j_sock_and_buskin"))).score, 0.0)
    }

    @Test fun blueprintCopiedRetriggerStacksEditionPerTrigger() {
        // Both Kings Holo + [Blueprint, Sock]: Blueprint copies Sock → 3 triggers/King (baseline 140 plain).
        // chips 10 + 2*3*10 = 70; mult 2 + (6 triggers * 10) = 62 → 4340.
        assertEquals(4340.0, Score.score(
            listOf(king(Suit.S, "Holo"), king(Suit.H, "Holo")),
            listOf(FJoker("j_blueprint"), FJoker("j_sock_and_buskin"))).score, 0.0)
    }
}
