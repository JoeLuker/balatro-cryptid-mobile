package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Starting-deck variants (Phase.DECK_SELECT → pickDeck): the modifier deltas bake into the run, and the
 * composition decks rebuild the persistent deck. RunState starts at DECK_SELECT so pickDeck applies.
 */
class DeckVariantTest {
    @Test fun redDeckAddsADiscard() {
        val rs = RunState(); val before = rs.baseDiscards
        rs.pickDeck(DeckVariant.RED)
        assertEquals(before + 1, rs.baseDiscards)
    }

    @Test fun blueDeckAddsAHand() {
        val rs = RunState(); val before = rs.baseHands
        rs.pickDeck(DeckVariant.BLUE)
        assertEquals(before + 1, rs.baseHands)
    }

    @Test fun yellowDeckStartsWithExtraMoney() {
        val rs = RunState(); val before = rs.money
        rs.pickDeck(DeckVariant.YELLOW)
        assertEquals(before + 10, rs.money)
    }

    @Test fun blackDeckGivesAnExtraJokerSlot() {
        val rs = RunState()
        rs.pickDeck(DeckVariant.BLACK)
        assertEquals(6, rs.maxJokers)            // MAX_JOKERS(5) + 1
    }

    @Test fun abandonedDeckHasNoFaceCards() {
        val rs = RunState()
        rs.pickDeck(DeckVariant.ABANDONED)
        assertEquals(0, rs.snapshot().deck.count { it.rank in 11..13 })
    }

    @Test fun checkeredDeckIsAllSpadesAndHearts() {
        val rs = RunState()
        rs.pickDeck(DeckVariant.CHECKERED)
        val deck = rs.snapshot().deck
        assertEquals(52, deck.size)
        assertEquals(0, deck.count { it.suit == "C" || it.suit == "D" })
    }
}
