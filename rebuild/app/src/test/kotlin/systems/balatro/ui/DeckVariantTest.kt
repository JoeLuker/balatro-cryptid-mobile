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

    // ── Stakes (chosen alongside the deck on DECK_SELECT) ──
    @Test fun redStakeRemovesSmallBlindReward() {
        val rs = RunState(); rs.pickDeck(DeckVariant.YELLOW, stake = 2)
        assertEquals(0, rs.rewardForSlot(0))     // Red+ : Small Blind gives no money
        assertEquals(4, rs.rewardForSlot(1))     // Big Blind reward unchanged
    }

    @Test fun blueStakeRemovesADiscard() {
        val rs = RunState(); rs.pickDeck(DeckVariant.YELLOW, stake = 5)
        assertEquals(2, rs.baseDiscards)         // 3 − 1 (Blue stake)
    }

    @Test fun greenStakeScalesRequirementFaster() {
        val white = RunState().also { it.pickDeck(DeckVariant.YELLOW, stake = 1); it.blindIndex = 3 }  // ante 2
        val green = RunState().also { it.pickDeck(DeckVariant.YELLOW, stake = 3); it.blindIndex = 3 }
        assertEquals(800.0, white.targetForSlot(0), 0.0)   // scaling 1 table
        assertEquals(900.0, green.targetForSlot(0), 0.0)   // scaling 2 table
    }

    @Test fun purpleStakeUsesTheSteepestTable() {
        val purple = RunState().also { it.pickDeck(DeckVariant.YELLOW, stake = 6); it.blindIndex = 3 }
        assertEquals(1000.0, purple.targetForSlot(0), 0.0) // scaling 3 table, ante 2
    }
}
