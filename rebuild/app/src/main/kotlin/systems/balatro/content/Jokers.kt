package systems.balatro.content

import systems.balatro.engine.Component
import systems.balatro.engine.Entity
import systems.balatro.engine.World
import systems.balatro.game.*

/**
 * Content = data + a registration. Porting a joker from the LÖVE/Cryptid build is:
 * (1) describe it as data, (2) register the effect(s) it contributes. No subclass of
 * Card, no override of calculate, no entry in a monkeypatched dispatch table. The
 * port is mechanical and each one is checked against score-oracle: if the new
 * scoring matches the original on the baseline seeds, the joker is "ported".
 *
 * Below are the first archetypes (flat, xmult, scaling) the spike covers — the same
 * 10-joker representative set the oracle baselines exercise. Add the rest by pattern.
 */

/** Per-joker mutable state lives in a component, not in the closure — so save/load,
 *  rewind, and inspection are reads of data, never a walk of a live object graph. */
data class JokerState(var scalar: BigValue = BigValue.ZERO) : Component

object Jokers {

    /** j_joker: +4 Mult. The simplest archetype. */
    fun joker(world: World, effects: Effects): Entity {
        val e = world.create()
        effects.register(e, setOf(Ctx.JOKER_MAIN)) { _, ctx ->
            ctx.tally.mult = ctx.tally.mult + BigValue.of(4)
        }
        return e
    }

    /** xmult archetype: ×1.5 Mult on scored. */
    fun greenJoker(world: World, effects: Effects): Entity {
        val e = world.create()
        effects.register(e, setOf(Ctx.JOKER_MAIN)) { _, ctx ->
            ctx.tally.mult = ctx.tally.mult * BigValue.of(1.5)
        }
        return e
    }

    /** A board of n jokers cycling the archetypes — exercises huge-stack UI + scoring at scale. */
    fun makeBoard(world: World, effects: Effects, n: Int): List<Entity> {
        val out = ArrayList<Entity>(n)
        for (i in 0 until n) out += when (i % 3) {
            0 -> joker(world, effects)
            1 -> greenJoker(world, effects)
            else -> scaler(world, effects, gainPerHand = 1.0)
        }
        return out
    }

    /**
     * Scaling archetype (e.g. a ride-the-bus / chili-pepper shape): grows its own
     * stored scalar over time, then contributes it. State is the JokerState component;
     * the handler mutates data it owns, which is exactly the no-shared-mutation,
     * rewind-safe property we wanted.
     */
    fun scaler(world: World, effects: Effects, gainPerHand: Double): Entity {
        val e = world.create()
        world.add(e, JokerState(BigValue.ZERO))
        effects.register(e, setOf(Ctx.BEFORE)) { w, ctx ->
            val st = w.get<JokerState>(ctx.self)!!
            st.scalar = st.scalar + BigValue.of(gainPerHand)
        }
        effects.register(e, setOf(Ctx.JOKER_MAIN)) { w, ctx ->
            val st = w.get<JokerState>(ctx.self)!!
            ctx.tally.mult = ctx.tally.mult + st.scalar
        }
        return e
    }
}
