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

    @Test fun enhanceRandomEnhancesOneCardInPlace() {
        val deck = Deck(seed = 1L)
        assertEquals(0, deck.countEnhancement(Enhancement.MULT))
        val card = deck.enhanceRandom(Enhancement.MULT)            // as The Empress tarot would
        assertEquals(Enhancement.MULT, card?.enhancement)
        assertEquals(1, deck.countEnhancement(Enhancement.MULT))
        assertEquals("enhancing is in place, not an add/remove", 52, deck.composition().size)
    }

    @Test fun addPermaBonusAccumulatesOnACard() {
        val deck = Deck(seed = 2L)
        val ace = PlayingCard(Suit.S, 14)
        assertTrue(deck.addPermaBonus(ace, 5))                     // Hiker: +5 chips, persisted to the deck
        assertEquals(5, deck.composition().first { it.suit == Suit.S && it.rank == 14 }.permaBonus)
        val grown = deck.composition().first { it.suit == Suit.S && it.rank == 14 }
        assertTrue(deck.addPermaBonus(grown, 5))                   // cumulative
        assertEquals(10, deck.composition().first { it.suit == Suit.S && it.rank == 14 }.permaBonus)
        assertFalse("a card not in the deck can't gain a bonus", deck.addPermaBonus(PlayingCard(Suit.S, 14, Enhancement.STONE), 5))
    }

    @Test fun addAndSetCompositionResizeTheDeck() {
        val deck = Deck(seed = 3L)
        deck.add(PlayingCard(Suit.S, 14, Enhancement.STONE))
        assertEquals(53, deck.composition().size)
        deck.setComposition(listOf(PlayingCard(Suit.H, 2), PlayingCard(Suit.D, 3)))
        assertEquals(2, deck.composition().size)
        assertFalse(deck.hasRank(14))
        assertTrue(deck.hasRank(2))
    }

    @Test fun sealRandomSealsOneCard() {
        val deck = Deck(seed = 4L)
        val card = deck.sealRandom(Seal.GOLD)                      // as The Moon tarot would
        assertEquals(Seal.GOLD, card?.seal)
        assertEquals(1, deck.composition().count { it.seal == Seal.GOLD })
    }
}
