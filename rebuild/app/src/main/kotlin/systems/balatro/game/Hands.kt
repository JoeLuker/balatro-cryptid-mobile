package systems.balatro.game

/**
 * Poker hand types with their level-1 base (chips, mult) and per-level increments
 * (lChips, lMult) — all from Balatro's G.GAME.hands table. A planet card raises a hand's
 * level; at level L the base is (baseChips + (L-1)*lChips, baseMult + (L-1)*lMult).
 */
enum class HandType(val baseChips: Int, val baseMult: Int, val lChips: Int = 0, val lMult: Int = 0) {
    NONE(0, 1),
    HIGH_CARD(5, 1, 10, 1),
    PAIR(10, 2, 15, 1),
    TWO_PAIR(20, 2, 20, 1),
    THREE_OF_A_KIND(30, 3, 20, 2),
    STRAIGHT(30, 4, 30, 3),
    FLUSH(35, 4, 15, 2),
    FULL_HOUSE(40, 4, 25, 2),
    FOUR_OF_A_KIND(60, 7, 30, 3),
    STRAIGHT_FLUSH(100, 8, 40, 4),
    FIVE_OF_A_KIND(120, 12, 35, 3),
    FLUSH_HOUSE(140, 14, 40, 4),
    FLUSH_FIVE(160, 16, 50, 3),
    // --- Cryptid-exclusive hand types (not returned by Hands.evaluate; stubs for joker dispatch) ---
    CRY_BULWARK(0, 1),       // cry_Bulwark: whole-deck-suit hand (c_cry_asteroidbelt)
    CRY_CLUSTERFUCK(0, 1),   // cry_Clusterfuck: specific multi-combo (cry_poker_hand_stuff)
    CRY_ULTPAIR(0, 1),       // cry_UltPair: paired hand across many ranks (c_cry_marsmoons)
    CRY_NONE(0, 1),          // cry_None: the "no valid hand" type (c_cry_nibiru)
    CRY_WHOLEDECK(0, 1),     // cry_WholeDeck: all 52 cards scored at once (c_cry_universe)
}

/**
 * FAITHFUL 1:1 port of Balatro's evaluate_poker_hand + get_X_same/get_flush/get_straight/get_highest
 * (functions/misc_functions.lua:376-621). Builds the same `results` table (each hand type -> the
 * cards forming it; the first one set is `top`, the played hand) and the same downgrade chain
 * (5oak -> 4oak -> 3oak -> pair). Structure matches the source so any joker that hooks hand
 * detection (Four Fingers, Shortcut, Smeared) is a localized change here, not a rewrite.
 *
 * Joker hooks are parameters defaulting to off (those jokers aren't ported yet): fourFingers makes
 * flush/straight need 4 not 5; shortcut allows one straight gap; smeared makes red/black suits
 * collide (via PlayingCard.isSuit). rankOf composes active RankMods (Card:get_id patches).
 */
object Hands {
    /** (best hand, the cards forming it, AND the set of every hand type the cards satisfy). That set
     *  is Balatro's `context.poker_hands` — jokers like the Cryptid "type" family fire when their
     *  hand is merely PRESENT (e.g. High Card is always present), not only when it's the played hand. */
    fun evaluate(
        cards: List<PlayingCard>,
        rankOf: (PlayingCard) -> Int = { it.id },
        fourFingers: Boolean = false, shortcut: Boolean = false, smeared: Boolean = false,
    ): Triple<HandType, List<PlayingCard>, Set<HandType>> {
        if (cards.isEmpty()) return Triple(HandType.NONE, emptyList(), emptySet())

        val _5 = getXSame(5, cards, rankOf)
        val _4 = getXSame(4, cards, rankOf)
        val _3 = getXSame(3, cards, rankOf)
        val _2 = getXSame(2, cards, rankOf)
        val _flush = getFlush(cards, fourFingers, smeared)
        val _straight = getStraight(cards, rankOf, fourFingers, shortcut)
        val _highest = getHighest(cards, rankOf)

        // results: built in source order; `top` is the FIRST hand type satisfied (the played hand).
        val results = HashMap<HandType, List<PlayingCard>>()
        var top: HandType? = null
        fun set(h: HandType, cs: List<PlayingCard>) { results[h] = cs; if (top == null) top = h }

        if (_5.isNotEmpty() && _flush.isNotEmpty()) set(HandType.FLUSH_FIVE, _5[0])
        if (_3.isNotEmpty() && _2.isNotEmpty() && _flush.isNotEmpty()) set(HandType.FLUSH_HOUSE, _3[0] + _2[0])
        if (_5.isNotEmpty()) set(HandType.FIVE_OF_A_KIND, _5[0])
        if (_flush.isNotEmpty() && _straight.isNotEmpty()) {                       // Straight Flush = flush ∪ straight
            val ret = _flush[0].toMutableList()
            for (v in _straight[0]) if (v !in _flush[0]) ret.add(v)
            set(HandType.STRAIGHT_FLUSH, ret)
        }
        if (_4.isNotEmpty()) set(HandType.FOUR_OF_A_KIND, _4[0])
        if (_3.isNotEmpty() && _2.isNotEmpty()) set(HandType.FULL_HOUSE, _3[0] + _2[0])
        if (_flush.isNotEmpty()) set(HandType.FLUSH, _flush[0])
        if (_straight.isNotEmpty()) set(HandType.STRAIGHT, _straight[0])
        if (_3.isNotEmpty()) set(HandType.THREE_OF_A_KIND, _3[0])
        if (_2.size == 2 || (_3.size == 1 && _2.size == 1)) {                      // Two Pair (source line 479)
            val a = _2[0]; val b = if (_2.size >= 2) _2[1] else _3[0]
            set(HandType.TWO_PAIR, a + b)
        }
        if (_2.isNotEmpty()) set(HandType.PAIR, _2[0])
        if (_highest.isNotEmpty()) set(HandType.HIGH_CARD, _highest[0])

        val best = top ?: HandType.HIGH_CARD
        return Triple(best, results[best] ?: emptyList(), results.keys.toSet())
    }

    /** get_X_same: groups of EXACTLY num cards sharing an id, ordered high-id first (source:592). */
    private fun getXSame(num: Int, hand: List<PlayingCard>, idOf: (PlayingCard) -> Int): List<List<PlayingCard>> {
        val vals = arrayOfNulls<List<PlayingCard>>(15)   // id 1..14
        for (i in hand.indices.reversed()) {
            if (idOf(hand[i]) !in 2..14) continue            // stones (random-negative id) never group
            val curr = ArrayList<PlayingCard>(); curr.add(hand[i])
            for (j in hand.indices) if (idOf(hand[i]) == idOf(hand[j]) && i != j) curr.add(hand[j])
            if (curr.size == num) vals[idOf(curr[0])] = curr
        }
        val ret = ArrayList<List<PlayingCard>>()
        for (i in 14 downTo 1) vals[i]?.let { ret.add(it) }
        return ret
    }

    /** get_flush: one suit covering >= (5 - fourFingers) cards (source:522). */
    private fun getFlush(hand: List<PlayingCard>, fourFingers: Boolean, smeared: Boolean): List<List<PlayingCard>> {
        val need = 5 - (if (fourFingers) 1 else 0)
        if (hand.size > 5 || hand.size < need) return emptyList()
        for (suit in Suit.values()) {
            val t = hand.filter { it.isSuit(suit, smeared) }
            if (t.size >= need) return listOf(t)
        }
        return emptyList()
    }

    /** get_straight: a consecutive run (Ace high or low), shortcut allows one gap (source:548). */
    private fun getStraight(hand: List<PlayingCard>, idOf: (PlayingCard) -> Int, fourFingers: Boolean, shortcut: Boolean): List<List<PlayingCard>> {
        val need = 5 - (if (fourFingers) 1 else 0)
        if (hand.size > 5 || hand.size < need) return emptyList()
        val ids = HashMap<Int, MutableList<PlayingCard>>()
        for (c in hand) { val id = idOf(c); if (id in 2..14) ids.getOrPut(id) { ArrayList() }.add(c) }
        val t = ArrayList<PlayingCard>()
        var len = 0; var straight = false; var skipped = false
        for (j in 1..14) {
            val key = if (j == 1) 14 else j
            if (ids[key] != null) { len++; skipped = false; t.addAll(ids[key]!!) }
            else if (shortcut && !skipped && j != 14) { skipped = true }
            else { len = 0; skipped = false; if (!straight) t.clear(); if (straight) break }
            if (len >= need) straight = true
        }
        return if (straight) listOf(t) else emptyList()
    }

    private fun getHighest(hand: List<PlayingCard>, idOf: (PlayingCard) -> Int): List<List<PlayingCard>> {
        val highest = hand.maxByOrNull { it.nominal } ?: return emptyList()
        return listOf(listOf(highest))
    }
}
