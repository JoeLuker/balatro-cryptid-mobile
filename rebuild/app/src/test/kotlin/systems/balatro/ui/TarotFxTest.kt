package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import systems.balatro.game.Enhancement
import systems.balatro.game.PlayingCard
import systems.balatro.game.Suit
import org.junit.Test

/**
 * The tarot effects (aim → useTarot path): suit conversion, rank-up (Ace wraps to 2), destroy, and the
 * per-tarot target cap. Cards in the hand also exist in the standard deck, so the deck mutation matches.
 */
class TarotFxTest {
    private fun rs(vararg hand: PlayingCard) = RunState().apply { this.hand = hand.toList() }
    private fun use(rs: RunState, t: TarotOffer, vararg idx: Int) {
        rs.pendingTarot = t; rs.tarotTarget = idx.toSet(); rs.useTarot()
    }

    @Test fun starConvertsSelectedToDiamonds() {
        val rs = rs(PlayingCard(Suit.S, 10), PlayingCard(Suit.H, 5))
        use(rs, TarotOffer("The Star", TarotFx.ConvertSuit(Suit.D, 3)), 0, 1)
        assertTrue(rs.hand.all { it.suit == Suit.D })
    }

    @Test fun strengthRaisesRankAndWrapsAce() {
        val rs = rs(PlayingCard(Suit.S, 14))            // Ace
        use(rs, TarotOffer("Strength", TarotFx.RankUp(2)), 0)
        assertEquals(2, rs.hand[0].rank)                // 14 → 2
    }

    @Test fun hangedManDestroysSelectedFromHandAndDeck() {
        val rs = rs(PlayingCard(Suit.S, 10), PlayingCard(Suit.H, 5))
        val deckBefore = rs.snapshot().deck.size
        use(rs, TarotOffer("The Hanged Man", TarotFx.Destroy(2)), 0, 1)
        assertTrue(rs.hand.isEmpty())
        assertEquals(deckBefore - 2, rs.snapshot().deck.size)
    }

    @Test fun enhanceRespectsMaxTargetCap() {
        val rs = rs(PlayingCard(Suit.S, 10), PlayingCard(Suit.H, 5))
        use(rs, TarotOffer("The Lovers", TarotFx.Enhance(Enhancement.WILD, 1)), 0, 1)  // max 1
        assertEquals(1, rs.hand.count { it.enhancement == Enhancement.WILD })
    }

    // Non-targeted tarots apply via useConsumable.
    private fun useHeld(rs: RunState, t: TarotOffer) {
        rs.consumables.add(Consumable.TarotC(t)); rs.useConsumable(rs.consumables.size - 1)
    }

    @Test fun hermitDoublesMoneyCappedAt20() {
        val rs = RunState().apply { money = 5 }
        useHeld(rs, TarotOffer("The Hermit", TarotFx.DoubleMoney)); assertEquals(10, rs.money)   // +min(5,20)
        rs.money = 100
        useHeld(rs, TarotOffer("The Hermit", TarotFx.DoubleMoney)); assertEquals(120, rs.money)  // +min(100,20)
    }

    @Test fun emperorCreatesTwoTarots() {
        val rs = RunState()
        useHeld(rs, TarotOffer("The Emperor", TarotFx.CreateTarots(2)))
        assertEquals(2, rs.consumables.count { it is Consumable.TarotC })  // Emperor consumed, 2 created
    }

    @Test fun highPriestessCreatesPlanets() {
        val rs = RunState()
        useHeld(rs, TarotOffer("The High Priestess", TarotFx.CreatePlanets(2)))
        assertEquals(2, rs.consumables.count { it is Consumable.PlanetC })
    }

    @Test fun foolCopiesTheLastConsumableUsed() {
        val rs = RunState()
        useHeld(rs, TarotOffer("The Empress", TarotFx.Enhance(Enhancement.MULT, 2)))  // last used = Empress
        useHeld(rs, TarotOffer("The Fool", TarotFx.CreateLastUsed))
        // The Fool created a copy of the Empress (now held in a slot).
        assertEquals(1, rs.consumables.count { it is Consumable.TarotC && (it as Consumable.TarotC).t.name == "The Empress" })
    }

    @Test fun foolFizzlesWhenNothingUsedYet() {
        val rs = RunState()
        useHeld(rs, TarotOffer("The Fool", TarotFx.CreateLastUsed))
        assertEquals(0, rs.consumables.size)   // nothing to copy → no-op
    }

    @Test fun deathMakesLeftCardACopyOfRight() {
        val rs = rs(PlayingCard(Suit.S, 3), PlayingCard(Suit.H, 13))   // left=3♠, right=K♥
        use(rs, TarotOffer("Death", TarotFx.ConvertCopy), 0, 1)
        assertEquals(Suit.H, rs.hand[0].suit)   // left became a copy of the right
        assertEquals(13, rs.hand[0].rank)
        assertEquals(13, rs.hand[1].rank)       // right unchanged
    }
}
