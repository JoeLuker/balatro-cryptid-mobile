package systems.balatro.game

import kotlin.random.Random

/**
 * A standard 52-card deck with a SEEDED shuffle, so a round is reproducible (same seed =>
 * same draw order) — the hook the oracle/telemetry needs to replay a session. Draw pulls
 * from the top; the discard pile isn't reshuffled (a blind is short), matching Balatro's
 * "draw down the deck" feel. RNG parity with Balatro's own shuffle is a later concern; this
 * is a deterministic, replayable deck to make the round real.
 */
class Deck(seed: Long) {
    private val rng = Random(seed)
    private val cards = ArrayDeque<PlayingCard>()

    init { reset() }

    fun reset() {
        cards.clear()
        val all = ArrayList<PlayingCard>(52)
        for (s in Suit.values()) for (r in 2..14) all.add(PlayingCard(s, r))
        all.shuffle(rng)
        cards.addAll(all)
    }

    /** Draw up to n from the top (fewer if the deck runs low). */
    fun draw(n: Int): List<PlayingCard> {
        val out = ArrayList<PlayingCard>(n)
        repeat(n) { if (cards.isNotEmpty()) out.add(cards.removeFirst()) }
        return out
    }

    val remaining: Int get() = cards.size
}
