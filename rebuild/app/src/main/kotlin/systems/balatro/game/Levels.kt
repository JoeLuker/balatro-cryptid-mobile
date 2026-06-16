package systems.balatro.game

import systems.balatro.engine.Component
import systems.balatro.engine.World

/**
 * Per-hand-type levels (planet cards raise them). DATA in the world — a single component,
 * read by the scoring loop, mutated by planets — not a global the way Balatro's G.GAME.hands
 * is. Hands start at level 1; a level adds (lChips, lMult) to that hand's base.
 */
class HandLevels : Component {
    private val lvl = HashMap<HandType, Int>()
    fun level(h: HandType): Int = lvl[h] ?: 1
    fun levelUp(h: HandType, by: Int = 1) { lvl[h] = level(h) + by }
}

object Levels {
    /** The world's hand-level state, created on first use (so the no-planet path stays allocation-free). */
    fun ensure(world: World): HandLevels {
        val s = world.store<HandLevels>()
        if (s.size == 0) world.add(world.create(), HandLevels())
        return s.at(0)
    }

    /** Read-only: the level state if any planet has been used, else null (=> every hand is level 1). */
    fun get(world: World): HandLevels? = world.store<HandLevels>().let { if (it.size > 0) it.at(0) else null }
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
