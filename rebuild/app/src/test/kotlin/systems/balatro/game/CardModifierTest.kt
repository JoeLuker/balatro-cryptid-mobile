package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Per-card modifiers — a played/held card's own editions, enhancements, and seals (Card:get_chip_*
 * + eval_card). The oracle covers some through full scores; this pins each modifier's contribution
 * against the Pair-of-Aces baseline (10 chips + 11 + 11 = 32 × 2 = 64) so a wrong constant or a
 * dropped modifier is caught directly.
 */
class CardModifierTest {
    private val plainAce = PlayingCard(Suit.H, 14)   // partners the modified card into a Pair
    private fun score(played: List<PlayingCard>, held: List<PlayingCard> = emptyList()) =
        Score.score(played, emptyList(), held).score

    @Test fun cardEditionsApplyTheirOwnEffect() {
        assertEquals(164.0, score(listOf(PlayingCard(Suit.S, 14, edition = "Foil"), plainAce)), 0.0)   // +50 chips → (10+61+11)×2
        assertEquals(384.0, score(listOf(PlayingCard(Suit.S, 14, edition = "Holo"), plainAce)), 0.0)   // +10 mult → 32×(2+10)
        assertEquals(96.0, score(listOf(PlayingCard(Suit.S, 14, edition = "Poly"), plainAce)), 0.0)    // ×1.5 → 64×1.5
    }

    @Test fun playedEnhancementsApplyTheirEffect() {
        assertEquals(124.0, score(listOf(PlayingCard(Suit.S, 14, enhancement = Enhancement.BONUS), plainAce)), 0.0)  // +30 chips → (10+41+11)×2
        assertEquals(192.0, score(listOf(PlayingCard(Suit.S, 14, enhancement = Enhancement.MULT), plainAce)), 0.0)   // +4 mult → 32×6
        assertEquals(128.0, score(listOf(PlayingCard(Suit.S, 14, enhancement = Enhancement.GLASS), plainAce)), 0.0)  // ×2 mult → 64×2
    }

    @Test fun steelScoresOnlyWhileHeldInHand() {
        val steelKing = PlayingCard(Suit.S, 13, enhancement = Enhancement.STEEL)
        // played Pair of Aces (64), a Steel card held → ×1.5 the whole hand
        assertEquals(96.0, score(listOf(PlayingCard(Suit.S, 14), plainAce), held = listOf(steelKing)), 0.0)
    }

    @Test fun stoneCardAlwaysAddsFiftyChips() {
        val stone = PlayingCard(Suit.S, 2, enhancement = Enhancement.STONE)   // no rank — never breaks the Pair
        assertEquals(164.0, score(listOf(PlayingCard(Suit.S, 14), plainAce, stone)), 0.0)  // (10+11+11+50)×2
    }

    @Test fun redSealRetriggersTheCard() {
        // the Red-seal Ace scores twice: (10 + 11 + 11 + 11) × 2
        assertEquals(86.0, score(listOf(PlayingCard(Suit.S, 14, seal = Seal.RED), plainAce)), 0.0)
    }
}
