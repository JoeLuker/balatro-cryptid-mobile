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
    val kind: String,          // "joker" | "hand" | "consumeable" | "play"
    var cardLimit: Int = 5,
    private val isConsumeables: Boolean = false,
    val cardScale: Double = 1.0,   // card render scale — oracle: hand/play cards are 0.95, jokers 1.0
) : Moveable(scene, t) {

    /** Child cards (Moveables). Each is registered in the same scene so the host loop sweeps it. */
    val cards = ArrayList<Moveable>()
    /** Selection/highlight by card index (hand uses this for the lift). */
    val highlighted = HashSet<Int>()

    /** Grow/shrink the card list to [n], creating cards at the area centre and deregistering removed. */
    fun setCardCount(n: Int) {
        while (cards.size < n) {
            cards.add(Moveable(scene, Transform(T.x, T.y, CARD_W, CARD_H, scale = cardScale)).also { it.zoom = true })
        }
        while (cards.size > n) cards.removeAt(cards.size - 1).remove()
    }

    /** Make a new card Moveable belonging to this area. [atX]/[atY] is its SPAWN position (defaults
     *  to this area); pass another area's origin so the card flies IN from there (deck→hand deal,
     *  hand→play) — its VT starts at the spawn and align_cards springs it to its slot here. */
    fun newCard(atX: Double = T.x, atY: Double = T.y) =
        Moveable(scene, Transform(atX, atY, CARD_W, CARD_H, scale = cardScale)).also { it.zoom = true }

    /** cardarea.lua:50 emplace — append a card here (deck inserts at front). The Card-level bits
     *  (set_ability/set_ranks) are P0.5's Card; this owns only the Moveable membership + layout. */
    fun emplace(card: Moveable) {
        if (kind == "deck") cards.add(0, card) else cards.add(card)
    }

    /** cardarea.lua:85 remove_card — detach [card] from this area (it stays a live Moveable so a
     *  draw_card_from can re-home it). Returns it, or null if not here. */
    fun removeCard(card: Moveable): Moveable? {
        val i = cards.lastIndexOf(card)
        if (i < 0) return null
        cards.removeAt(i)
        return card
    }

    /** cardarea.lua:648 draw_card_from — TRANSFER a card from [area] into this one (deal/play
     *  animation). It's the SAME Moveable, so it keeps its VT (current screen position); align_cards
     *  then springs it to its new slot here — the faithful fly-in, no position reset. */
    fun drawCardFrom(area: CardArea): Moveable? {
        if (cards.size >= cardLimit && kind != "deck" && kind != "hand") return null
        val card = (if (area.kind == "deck" || area.kind == "discard") area.cards.lastOrNull() else area.cards.firstOrNull()) ?: return null
        area.removeCard(card)
        emplace(card)
        return card
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
                    if (c.states.drag.isOn) return@forEachIndexed   // vanilla: dragged card owns its T
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
            "play" -> {
                // cardarea.lua:551 play/shop branch — cards distributed across the area, no fan/wobble.
                val maxCards = max(n, tempLimit)
                val denom = max(maxCards - 1, 1).toDouble()
                cards.forEachIndexed { idx, c ->
                    if (c.states.drag.isOn) return@forEachIndexed
                    val k = idx + 1
                    c.T.r = 0.0
                    c.T.x = T.x + (T.w - CARD_W) * ((k - 1) / denom - 0.5 * (n - maxCards) / denom) +
                        (if (cardLimit == 1) 0.5 * (T.w - CARD_W) else 0.0)
                    val lift = if (idx in highlighted) HIGHLIGHT_H else 0.0
                    c.T.y = T.y + T.h / 2 - CARD_H / 2 - lift
                    c.T.x += c.shadowParallax.x / 30
                }
            }
            else /* joker / consumeable */ -> {
                cards.forEachIndexed { idx, c ->
                    if (c.states.drag.isOn) return@forEachIndexed
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

    /** True if any member is currently being dragged (gates the per-frame reorder). */
    fun anyDragged(): Boolean = cards.any { it.states.drag.isOn }

    /**
     * cardarea.lua align_cards tail: `table.sort(self.cards, a.T.x + a.T.w/2 < b.T.x + b.T.w/2)`.
     * Returns the indices of [cards] in vanilla sort order (identity permutation when nothing has
     * moved). The owner applies it to the source-of-truth list; next frame's rebuild realizes it —
     * same one-frame settle as vanilla's sort-at-end-of-align.
     */
    fun xOrder(): List<Int> =
        cards.indices.sortedBy { cards[it].T.x + cards[it].T.w / 2 }

    companion object {
        const val CARD_W = 2.4 * 35.0 / 41.0
        const val CARD_H = 2.4 * 47.0 / 41.0
        const val HIGHLIGHT_H = 1.0   // G.HIGHLIGHT_H (refine when the hand is routed through this)
    }
}
