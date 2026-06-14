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

    val remaining: Int get() = drawPile.size
}
