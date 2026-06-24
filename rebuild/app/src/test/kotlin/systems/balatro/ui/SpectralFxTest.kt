package systems.balatro.ui

import org.junit.Assert.assertEquals
import systems.balatro.game.PlayingCard
import systems.balatro.game.Suit
import org.junit.Test

/**
 * The new non-targeted spectrals (Sigil/Ouija whole-hand conversion; Familiar/Grim/Incantation
 * destroy-1-and-create). Applied via useConsumable → applySpectral. Hand cards also exist in the
 * standard deck so the deck mutation matches.
 */
class SpectralFxTest {
    private fun rs(vararg hand: PlayingCard) = RunState().apply { this.hand = hand.toList() }
    private fun use(rs: RunState, s: Spectral) {
        rs.consumables.add(Consumable.SpectralC(s)); rs.useConsumable(rs.consumables.size - 1)
    }

    @Test fun sigilConvertsHandToOneSuit() {
        val rs = rs(PlayingCard(Suit.S, 10), PlayingCard(Suit.H, 5), PlayingCard(Suit.D, 3))
        use(rs, Spectral.SIGIL)
        assertEquals(1, rs.hand.map { it.suit }.toSet().size)
    }

    @Test fun ouijaConvertsHandToOneRank() {
        val rs = rs(PlayingCard(Suit.S, 10), PlayingCard(Suit.H, 5))
        use(rs, Spectral.OUIJA)
        assertEquals(1, rs.hand.map { it.rank }.toSet().size)
    }

    @Test fun grimDestroysOneAndCreatesTwoAces() {
        val rs = rs(PlayingCard(Suit.S, 10), PlayingCard(Suit.H, 5))
        use(rs, Spectral.GRIM)
        assertEquals(3, rs.hand.size)                 // 2 − 1 destroyed + 2 created
        assertEquals(2, rs.hand.count { it.rank == 14 })  // created are Aces
    }

    @Test fun incantationCreatesFourNumberedCards() {
        val rs = rs(PlayingCard(Suit.S, 10), PlayingCard(Suit.H, 5))
        use(rs, Spectral.INCANTATION)
        assertEquals(5, rs.hand.size)                 // 2 − 1 + 4
    }
}
