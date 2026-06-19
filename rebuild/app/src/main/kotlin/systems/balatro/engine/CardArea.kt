package systems.balatro.engine

import kotlin.math.max
import kotlin.math.sin

/**
 * Minimal-but-faithful CardArea (the P0.5 runtime object that owns card Moveables and lays them out).
 * A CardArea IS a Moveable (its T is the area rect from set_screen_positions); it holds child card
 * Moveables and, each frame, runs the relevant `align_cards` branch (cardarea.lua) to set every
 * card's target T (spread/fan/wobble/lift). The host loop then springs each card's VT toward that T,
 * so cards move through the one engine — no per-card ad-hoc animator.
 *
 * This is intentionally scoped to what the play field needs now: the `joker` branch (jokers +
 * consumeables) and the `hand` branch. The full Card class (abilities, dissolve, save/load) and the
 * other align branches are the rest of P0.5; cards here are plain Moveables (a Card IS a Moveable)
 * plus a `highlighted` flag.
 */
class CardArea(
    scene: SceneRegistry,
    t: Transform,
    val kind: String,          // "joker" | "hand" | "consumeable"
    var cardLimit: Int = 5,
    private val isConsumeables: Boolean = false,
) : Moveable(scene, t) {

    /** Child cards (Moveables). Each is registered in the same scene so the host loop sweeps it. */
    val cards = ArrayList<Moveable>()
    /** Selection/highlight by card index (hand uses this for the lift). */
    val highlighted = HashSet<Int>()

    /** Grow/shrink the card list to [n], creating cards at the area centre and deregistering removed. */
    fun setCardCount(n: Int) {
        while (cards.size < n) {
            cards.add(Moveable(scene, Transform(T.x, T.y, CARD_W, CARD_H)).also { it.zoom = true })
        }
        while (cards.size > n) cards.removeAt(cards.size - 1).remove()
    }

    /**
     * Set each card's target T for this frame. Port of cardarea.lua align_cards — the `joker` branch
     * (565-582) and the `hand` branch (506-520). [reducedMotion] freezes the idle sin wobble (repro).
     * `card_w == self.card_w == CARD_W`, so the `0.5*(card_w - card.T.w)` terms vanish.
     */
    fun alignCards(clock: GameClock, reducedMotion: Boolean, tempLimit: Int = cardLimit) {
        val n = cards.size
        if (n == 0) return
        val real = clock.real
        when (kind) {
            "hand" -> {
                val maxCards = max(n, tempLimit)
                val denom = max(maxCards - 1, 1).toDouble()
                cards.forEachIndexed { idx, c ->
                    val k = idx + 1
                    c.T.r = 0.2 * (-n / 2.0 - 0.5 + k) / n +
                        (if (reducedMotion) 0.0 else 0.02 * sin(2 * real + c.T.x))
                    c.T.x = T.x + (T.w - CARD_W) * ((k - 1) / denom - 0.5 * (n - maxCards) / denom)
                    val lift = if (idx in highlighted) HIGHLIGHT_H else 0.0
                    c.T.y = T.y + T.h / 2 - CARD_H / 2 - lift +
                        (if (reducedMotion) 0.0 else 0.03 * sin(0.666 * real + c.T.x)) +
                        Math.abs(0.5 * (-n / 2.0 + k - 0.5) / n) - 0.2
                    c.T.x += c.shadowParallax.x / 30
                }
            }
            else /* joker / consumeable */ -> {
                cards.forEachIndexed { idx, c ->
                    val k = idx + 1
                    c.T.r = 0.1 * (-n / 2.0 - 0.5 + k) / n +
                        (if (reducedMotion) 0.0 else 0.02 * sin(2 * real + c.T.x))
                    c.T.x = when {
                        n > 2 || (n > 1 && isConsumeables) -> T.x + (T.w - CARD_W) * ((k - 1.0) / (n - 1))
                        n > 1 && !isConsumeables -> T.x + (T.w - CARD_W) * ((k - 0.5) / n)
                        else -> T.x + T.w / 2 - CARD_W / 2
                    }
                    val lift = if (idx in highlighted) HIGHLIGHT_H / 2 else 0.0
                    c.T.y = T.y + T.h / 2 - CARD_H / 2 - lift +
                        (if (reducedMotion) 0.0 else 0.03 * sin(0.666 * real + c.T.x))
                    c.T.x += c.shadowParallax.x / 30
                }
            }
        }
    }

    companion object {
        const val CARD_W = 2.4 * 35.0 / 41.0
        const val CARD_H = 2.4 * 47.0 / 41.0
        const val HIGHLIGHT_H = 1.0   // G.HIGHLIGHT_H (refine when the hand is routed through this)
    }
}
