package systems.balatro.content

import systems.balatro.engine.Entity
import systems.balatro.engine.World
import systems.balatro.game.*

/** A joker (or card) finish. Documented Balatro values: Foil +50 Chips, Holo +10 Mult, Poly x1.5 Mult. */
enum class Edition(val tag: String) { NONE(""), FOIL("Foil"), HOLO("Holo"), POLY("Poly") }

/**
 * Editions as composition: an edition is just ONE more effect on the joker, registered right
 * after its base so it resolves with that joker (Foil/Holo add, Poly multiplies the mult). No
 * special-casing in the scoring loop — the joker carries an extra subscriber, nothing more.
 */
object Editions {
    fun apply(world: World, effects: Effects, joker: Entity, ed: Edition) {
        when (ed) {
            Edition.FOIL -> effects.register(joker, setOf(Ctx.JOKER_MAIN)) { _, c -> c.tally.chips = c.tally.chips + BigValue.of(50) }
            Edition.HOLO -> effects.register(joker, setOf(Ctx.JOKER_MAIN)) { _, c -> c.tally.mult = c.tally.mult + BigValue.of(10) }
            Edition.POLY -> effects.register(joker, setOf(Ctx.JOKER_MAIN)) { _, c -> c.tally.mult = c.tally.mult * BigValue.of(1.5) }
            Edition.NONE -> {}
        }
    }

    /** Spawn a joker by key WITH an edition — base effect + edition registered adjacent, in board order. */
    fun spawn(world: World, effects: Effects, key: String, ed: Edition): Entity {
        val e = Content.byKey.getValue(key)(world, effects)
        apply(world, effects, e, ed)
        return e
    }
}
