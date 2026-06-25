package systems.balatro.game

import kotlin.random.Random

/**
 * A standard 52-card deck with a SEEDED shuffle. The full set is PERSISTENT across the run
 * (so tarot enhancements stick to cards between blinds); reshuffle returns everything to the
 * draw pile and reorders. Draw pulls from the top. RNG parity with Balatro's own shuffle is
 * a later concern; this is a deterministic, replayable deck.
 */
class Deck(seed: Long) {
    private val rng = Random(seed)
    private val all = ArrayList<PlayingCard>(52)        // persistent — enhancements live here
    private val drawPile = ArrayDeque<PlayingCard>()

    init {
        for (s in Suit.values()) for (r in 2..14) all.add(PlayingCard(s, r))
        reshuffle()
    }

    /** Return every card to the draw pile and shuffle; card enhancements are preserved. */
    fun reshuffle() { drawPile.clear(); all.shuffle(rng); drawPile.addAll(all) }

    /** Draw up to n from the top (fewer if the pile runs low). */
    fun draw(n: Int): List<PlayingCard> {
        val out = ArrayList<PlayingCard>(n)
        repeat(n) { if (drawPile.isNotEmpty()) out.add(drawPile.removeFirst()) }
        return out
    }

    /** Enhance a random un-enhanced card in the deck (a tarot's effect); returns it, or null if all are enhanced. */
    fun enhanceRandom(e: Enhancement): PlayingCard? {
        val idxs = all.indices.filter { all[it].enhancement == Enhancement.NONE }
        if (idxs.isEmpty()) return null
        val i = idxs.random(rng)
        all[i] = all[i].copy(enhancement = e)
        return all[i]
    }

    /** Put a seal on a random un-sealed card; returns it, or null if all are sealed. */
    fun sealRandom(s: Seal): PlayingCard? {
        val idxs = all.indices.filter { all[it].seal == Seal.NONE }
        if (idxs.isEmpty()) return null
        val i = idxs.random(rng)
        all[i] = all[i].copy(seal = s)
        return all[i]
    }

    /** Put an edition ("Foil"/"Holo"/"Poly") on a random un-editioned card (Aura spectral). */
    fun editionRandom(ed: String): PlayingCard? {
        val idxs = all.indices.filter { all[it].edition.isEmpty() }
        if (idxs.isEmpty()) return null
        val i = idxs.random(rng)
        all[i] = all[i].copy(edition = ed)
        return all[i]
    }

    /** Enhance a specific card already in the deck (tarot player-choice path). Matches by value
     *  (suit+rank+enhancement+seal) and replaces the first matching card. If the card isn't found
     *  (e.g. it was already enhanced) nothing changes; returns true iff a card was modified. */
    fun enhanceCard(card: PlayingCard, e: Enhancement): Boolean {
        val i = all.indexOf(card); if (i < 0) return false
        all[i] = all[i].copy(enhancement = e)
        // Mirror the update in the draw pile if the card is still there.
        val di = drawPile.indexOf(card); if (di >= 0) { drawPile.removeAt(di); drawPile.add(di, all[i]) }
        return true
    }

    /** Put a seal on a specific card already in the deck (tarot player-choice path). Matches by value. */
    fun sealCard(card: PlayingCard, s: Seal): Boolean {
        val i = all.indexOf(card); if (i < 0) return false
        all[i] = all[i].copy(seal = s)
        val di = drawPile.indexOf(card); if (di >= 0) { drawPile.removeAt(di); drawPile.add(di, all[i]) }
        return true
    }

    /** Convert a specific card's suit (Star/Sun/Moon/World tarots). Matches by value. */
    fun setSuitCard(card: PlayingCard, suit: Suit): Boolean {
        val i = all.indexOf(card); if (i < 0) return false
        all[i] = all[i].copy(suit = suit)
        val di = drawPile.indexOf(card); if (di >= 0) { drawPile.removeAt(di); drawPile.add(di, all[i]) }
        return true
    }

    /** Set a specific card's rank (Ouija spectral converts the whole hand to one rank). Matches by value. */
    fun setRankCard(card: PlayingCard, rank: Int): Boolean {
        val i = all.indexOf(card); if (i < 0) return false
        all[i] = all[i].copy(rank = rank)
        val di = drawPile.indexOf(card); if (di >= 0) { drawPile.removeAt(di); drawPile.add(di, all[i]) }
        return true
    }

    /** Raise a specific card's rank by 1, wrapping Ace(14)→2 (Strength tarot). Matches by value. */
    fun rankUpCard(card: PlayingCard): Boolean {
        val i = all.indexOf(card); if (i < 0) return false
        val nr = if (all[i].rank >= 14) 2 else all[i].rank + 1
        all[i] = all[i].copy(rank = nr)
        val di = drawPile.indexOf(card); if (di >= 0) { drawPile.removeAt(di); drawPile.add(di, all[i]) }
        return true
    }

    /** Destroy a specific card from the deck (The Hanged Man tarot). Matches by value. */
    fun destroyCard(card: PlayingCard): Boolean {
        val i = all.indexOf(card); if (i < 0) return false
        all.removeAt(i); reshuffle(); return true
    }

    /** Count cards in the FULL persistent deck with a given enhancement (for j_stone, j_steel_joker). */
    fun countEnhancement(e: Enhancement): Int = all.count { it.enhancement == e }

    /** Is any card of [rank] (2..14, Ace=14) still in the persistent deck? j_cry_blacklist
     *  self-destructs once its blacklisted rank is gone from every zone — and play∪hand∪discard∪deck
     *  partition the full persistent deck, so this single check is the faithful condition. */
    fun hasRank(rank: Int): Boolean = all.any { it.rank == rank }

    /** Cards in the FULL persistent deck carrying ANY enhancement (j_drivers_license: X3 at >=16).
     *  Recomputed from deck state so re-enhancing a card can't drift the tally (vanilla counts
     *  cards whose get_enhancements() is non-empty). */
    val enhancedCards: Int get() = all.count { it.enhancement != Enhancement.NONE }

    val remaining: Int get() = drawPile.size

    /** The full persistent deck (with enhancements/seals) — for run serialization. */
    fun composition(): List<PlayingCard> = all.toList()
    /** Replace the deck with [cards] (restore from a save) and reshuffle. */
    fun setComposition(cards: List<PlayingCard>) { all.clear(); all.addAll(cards); reshuffle() }
    /** Add a card to the deck (a Standard pack pick) and return it to the draw pile. */
    fun add(card: PlayingCard) { all.add(card); reshuffle() }
    /** Destroy a random card from the deck (Immolate); returns it, or null if empty. */
    fun removeRandom(): PlayingCard? {
        if (all.isEmpty()) return null
        val c = all.removeAt(all.indices.random(rng)); reshuffle(); return c
    }

    /** Permanently add a permaBonus chip increment to a specific card — j_hiker effect.
     *  Matches by value identity (suit+rank+enhancement+seal+permaBonus), updates both `all`
     *  and `drawPile` if the card is still there. Returns true iff a card was found.
     *  The card passed in carries the OLD permaBonus (pre-increment); after this call the
     *  deck instance has the incremented value which future hands will pick up via draw(). */
    fun addPermaBonus(card: PlayingCard, amount: Int): Boolean {
        val i = all.indexOf(card); if (i < 0) return false
        val updated = all[i].copy(permaBonus = all[i].permaBonus + amount)
        all[i] = updated
        val di = drawPile.indexOf(card); if (di >= 0) { drawPile.removeAt(di); drawPile.add(di, updated) }
        return true
    }

    /** Permanently set a card's enhancement (e.g. Midas Mask turning a scored face card to Gold). */
    fun setEnhancement(card: PlayingCard, enh: Enhancement): Boolean {
        val i = all.indexOf(card); if (i < 0) return false
        val updated = all[i].copy(enhancement = enh)
        all[i] = updated
        val di = drawPile.indexOf(card); if (di >= 0) { drawPile.removeAt(di); drawPile.add(di, updated) }
        return true
    }

    /** Permanently destroy a specific card from the deck — e.g. a played Glass card that shattered
     *  (Card:shatter removes it from G.playing_cards). Matches by value, dropping the first equal
     *  instance from the persistent deck and the draw pile if it's still there. Returns true iff one
     *  was removed. Deck composition is serialized, so the destruction persists across save/load. */
    fun removeCard(card: PlayingCard): Boolean {
        val i = all.indexOf(card); if (i < 0) return false
        all.removeAt(i)
        val di = drawPile.indexOf(card); if (di >= 0) drawPile.removeAt(di)
        return true
    }
}
