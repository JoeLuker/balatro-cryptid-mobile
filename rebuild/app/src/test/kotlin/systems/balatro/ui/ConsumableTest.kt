package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import systems.balatro.game.Enhancement
import systems.balatro.game.HandType
import systems.balatro.game.Planet

/**
 * Consumables — using a tarot / planet / spectral from the consumable slots applies a run effect and
 * removes the card. Plain state the oracle never touches. Asserts the deterministic dimension of each
 * (the deck is private, so deck changes are observed through the public snapshot()).
 */
class ConsumableTest {
    @Test fun planetLevelsItsHandAndIsConsumed() {
        val rs = RunState()
        rs.consumables.add(Consumable.PlanetC(Planet.MERCURY))   // Mercury → Pair
        rs.useConsumable(0)
        assertEquals(2, rs.handLevels.level(HandType.PAIR))
        assertTrue("the used consumable is removed", rs.consumables.isEmpty())
    }

    @Test fun blackHoleLevelsEveryHand() {
        val rs = RunState()
        rs.consumables.add(Consumable.SpectralC(Spectral.BLACK_HOLE))
        rs.useConsumable(0)
        assertEquals(2, rs.handLevels.level(HandType.PAIR))
        assertEquals(2, rs.handLevels.level(HandType.FLUSH))
        assertEquals(2, rs.handLevels.level(HandType.STRAIGHT_FLUSH))
    }

    @Test fun immolateDestroysFiveCardsAndGrants20() {
        val rs = RunState().apply { money = 10 }
        val deckBefore = rs.snapshot().deck.size                 // a fresh 52-card deck
        rs.consumables.add(Consumable.SpectralC(Spectral.IMMOLATE))
        rs.useConsumable(0)
        assertEquals(deckBefore - 5, rs.snapshot().deck.size)
        assertEquals(30, rs.money)                               // 10 + 20
    }

    @Test fun wraithCreatesAJokerAndZeroesMoney() {
        val rs = RunState().apply { money = 50 }
        val owned = rs.owned.size
        rs.consumables.add(Consumable.SpectralC(Spectral.WRAITH))
        rs.useConsumable(0)
        assertEquals(owned + 1, rs.owned.size)
        assertEquals(0, rs.money)
    }

    @Test fun ectoplasmLowersBaseHandSize() {
        val rs = RunState()
        assertEquals(8, rs.baseHandSize)
        rs.consumables.add(Consumable.SpectralC(Spectral.ECTOPLASM))
        rs.useConsumable(0)
        assertEquals(7, rs.baseHandSize)
    }

    @Test fun tarotEnhancesADeckCard() {
        val rs = RunState()
        // useConsumable is the fallback (no aim target) path: enhancement tarots enhance a random card.
        rs.consumables.add(Consumable.TarotC(TarotOffer("The Empress", TarotFx.Enhance(Enhancement.MULT, 2))))
        rs.useConsumable(0)
        assertEquals("one deck card gains the Mult enhancement", 1, rs.snapshot().deck.count { it.enh == "MULT" })
        assertTrue(rs.consumables.isEmpty())
    }
}
