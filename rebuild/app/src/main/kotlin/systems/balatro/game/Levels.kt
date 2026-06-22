package systems.balatro.game

/**
 * Per-hand-type levels (planet cards raise them). Plain run state (held in RunState), not the ECS
 * World. Hands start at level 1; a level adds (lChips, lMult) to that hand's base.
 */
class HandLevels {
    private val lvl = HashMap<HandType, Int>()
    fun level(h: HandType): Int = lvl[h] ?: 1
    fun levelUp(h: HandType, by: Int = 1) { lvl[h] = level(h) + by }
    /** Snapshot / restore the per-hand levels (run serialization). */
    fun all(): Map<HandType, Int> = lvl.toMap()
    fun setAll(m: Map<HandType, Int>) { lvl.clear(); lvl.putAll(m) }
}

/** A planet card levels up one hand type. The names/targets are Balatro's. */
enum class Planet(val display: String, val hand: HandType) {
    PLUTO("Pluto", HandType.HIGH_CARD),
    MERCURY("Mercury", HandType.PAIR),
    URANUS("Uranus", HandType.TWO_PAIR),
    VENUS("Venus", HandType.THREE_OF_A_KIND),
    SATURN("Saturn", HandType.STRAIGHT),
    JUPITER("Jupiter", HandType.FLUSH),
    EARTH("Earth", HandType.FULL_HOUSE),
    MARS("Mars", HandType.FOUR_OF_A_KIND),
    NEPTUNE("Neptune", HandType.STRAIGHT_FLUSH),
}
