package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deck.removeCard — the game-state half of Glass-card shatter: a shattered card is destroyed from the
 *  run's deck (G.playing_cards), not just dissolved on screen. Pure (no Compose), so a fast unit test. */
class DeckTest {

    @Test fun shatterDestroysCardPermanentlyAndUpdatesTallies() {
        val deck = Deck(seed = 42L)
        assertEquals(52, deck.composition().size)
        assertEquals(0, deck.enhancedCards)

        // Make the Ace of Spades Glass (as a tarot would), then shatter it.
        assertTrue(deck.enhanceCard(PlayingCard(Suit.S, 14), Enhancement.GLASS))
        val glassAce = PlayingCard(Suit.S, 14, Enhancement.GLASS)
        assertEquals(1, deck.countEnhancement(Enhancement.GLASS))
        assertEquals(1, deck.enhancedCards)

        assertTrue(deck.removeCard(glassAce))
        assertEquals(51, deck.composition().size)                 // permanently gone from the run
        assertFalse(deck.composition().contains(glassAce))
        assertEquals(0, deck.countEnhancement(Enhancement.GLASS))  // Driver's License tally updates
        assertEquals(0, deck.enhancedCards)

        // It must not reappear on reshuffle, and a second remove is a no-op.
        deck.reshuffle()
        assertEquals(51, deck.composition().size)
        assertFalse(deck.removeCard(glassAce))
    }

    @Test fun removeCardShrinksDrawPileAndRankPresence() {
        val deck = Deck(seed = 7L)
        val remainingBefore = deck.remaining                       // 52 — full draw pile after init reshuffle
        assertTrue(deck.hasRank(7))
        // Destroy all four 7s — blacklist's self-destruct condition (rank gone from the whole deck).
        for (s in Suit.values()) assertTrue(deck.removeCard(PlayingCard(s, 7)))
        assertEquals(remainingBefore - 4, deck.remaining)          // Blue Joker counts the smaller pile
        assertFalse(deck.hasRank(7))                               // rank fully absent → blacklist would self-destruct
    }
}
