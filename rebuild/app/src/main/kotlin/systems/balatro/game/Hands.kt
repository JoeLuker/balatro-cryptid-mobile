package systems.balatro.game

import systems.balatro.engine.Component

/**
 * A pre-evaluation rank remap (Balatro patches Card:get_id()). Stored as a component on
 * the joker that imposes it, so hand detection composes the active remaps as DATA — the
 * extension surface for "this joker changes what hand the cards form", distinct from the
 * tally Effects that fire mid-cascade. Maps an effective rank to an effective rank, so
 * remaps chain. Chip values are NOT affected (they come from the card's nominal, not id).
 */
class RankMod(val map: (Int) -> Int) : Component

/** Poker hand types with their level-1 base (chips, mult), matching Balatro. */
enum class HandType(val baseChips: Int, val baseMult: Int) {
    NONE(0, 1),
    HIGH_CARD(5, 1),
    PAIR(10, 2),
    TWO_PAIR(20, 2),
    THREE_OF_A_KIND(30, 3),
    STRAIGHT(30, 4),
    FLUSH(35, 4),
    FULL_HOUSE(40, 4),
    FOUR_OF_A_KIND(60, 7),
    STRAIGHT_FLUSH(100, 8),
    FIVE_OF_A_KIND(120, 12),
    FLUSH_HOUSE(140, 14),
    FLUSH_FIVE(160, 16),
}

object Hands {
    /**
     * Best hand + the SCORING cards (kickers excluded — only these contribute chips).
     * `rankOf` is the effective rank used for poker detection (identity by default; a
     * RankMod joker like maximized makes face cards collide). Chip values are unaffected.
     */
    fun evaluate(cards: List<PlayingCard>, rankOf: (PlayingCard) -> Int = { it.rank }): Pair<HandType, List<PlayingCard>> {
        if (cards.isEmpty()) return HandType.NONE to emptyList()
        val groups = cards.groupBy { rankOf(it) }.values.sortedByDescending { it.size }
        val top = groups[0].size
        val second = groups.getOrNull(1)?.size ?: 0
        val isFlush = cards.size == 5 && cards.all { it.suit == cards[0].suit }
        val straight = straightCards(cards, rankOf)

        return when {
            top == 5 && isFlush -> HandType.FLUSH_FIVE to cards
            top == 5 -> HandType.FIVE_OF_A_KIND to cards
            top == 3 && second == 2 && isFlush -> HandType.FLUSH_HOUSE to cards
            top == 4 -> HandType.FOUR_OF_A_KIND to groups[0]
            top == 3 && second == 2 -> HandType.FULL_HOUSE to cards
            straight != null && isFlush -> HandType.STRAIGHT_FLUSH to straight
            isFlush -> HandType.FLUSH to cards
            straight != null -> HandType.STRAIGHT to straight
            top == 3 -> HandType.THREE_OF_A_KIND to groups[0]
            top == 2 && second == 2 -> HandType.TWO_PAIR to (groups[0] + groups[1])
            top == 2 -> HandType.PAIR to groups[0]
            else -> HandType.HIGH_CARD to listOf(cards.maxByOrNull { rankOf(it) }!!)
        }
    }

    private fun straightCards(cards: List<PlayingCard>, rankOf: (PlayingCard) -> Int): List<PlayingCard>? {
        if (cards.size != 5) return null
        val ranks = cards.map { rankOf(it) }.toSortedSet()
        if (ranks.size != 5) return null
        if (ranks.last() - ranks.first() == 4) return cards          // normal run
        if (ranks == sortedSetOf(14, 2, 3, 4, 5)) return cards       // A-2-3-4-5
        return null
    }
}
