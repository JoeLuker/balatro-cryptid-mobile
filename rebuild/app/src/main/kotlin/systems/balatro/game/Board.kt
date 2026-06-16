package systems.balatro.game

import systems.balatro.engine.Component
import systems.balatro.engine.Entity
import systems.balatro.engine.World

/**
 * The joker board: which entities are jokers, and their left-to-right order. Order is
 * the single source of truth for position-dependent effects (blueprint copies pos+1)
 * and the for-each-other-joker pass (cross-joker Xmult). It is DATA — a marker component
 * whose Store preserves insertion order — not a privileged list some system owns.
 */
object JokerSlot : Component

object Board {
    fun add(world: World, joker: Entity) { world.add(joker, JokerSlot) }

    /** Joker entities in board order (insertion order of the slot store). */
    fun order(world: World): List<Entity> {
        val out = ArrayList<Entity>()
        world.store<JokerSlot>().each { e, _ -> out.add(e) }
        return out
    }

    /** The joker immediately to the right of `joker`, or null if it is last. */
    fun next(world: World, joker: Entity): Entity? {
        val o = order(world); val i = o.indexOf(joker)
        return if (i >= 0 && i + 1 < o.size) o[i + 1] else null
    }
}
