package systems.balatro.content

import systems.balatro.engine.Component
import systems.balatro.engine.Entity
import systems.balatro.engine.World
import systems.balatro.game.*

/**
 * Ported content, keyed by the ORIGINAL joker key so the oracle-parity harness can
 * instantiate the exact loadout a baseline recorded and assert the score matches.
 * Each entry registers the joker's effect(s) — data + a pure handler, no override.
 *
 * This wave: deterministic (non-RNG, non-scaling, non-cross-joker) archetypes, each
 * verified byte-for-byte against test/score-oracle-baselines.txt. RNG / scaling-state /
 * retrigger / cross-joker / Emult archetypes follow as their context plumbing lands.
 */
object Content {
    val byKey: Map<String, (World, Effects) -> Entity> = mapOf(
        // --- vanilla ---
        "j_joker" to reg(setOf(Ctx.JOKER_MAIN)) { c ->                                    // +4 Mult
            c.tally.mult = c.tally.mult + BigValue.of(4)
        },
        "j_greedy_joker" to reg(setOf(Ctx.INDIVIDUAL_SCORED)) { c ->                      // +3 Mult per scored Diamond
            if (c.scoredPlaying?.suit == Suit.D) c.tally.mult = c.tally.mult + BigValue.of(3)
        },
        // --- Cryptid (misc_joker.lua) ---
        "j_cry_cube" to reg(setOf(Ctx.JOKER_MAIN)) { c ->                                 // +6 Chips
            c.tally.chips = c.tally.chips + BigValue.of(6)
        },
        "j_cry_triplet_rhythm" to reg(setOf(Ctx.JOKER_MAIN)) { c ->                       // x3 Mult iff exactly 3 threes scored
            if (c.scoringCards.count { it.rank == 3 } == 3) c.tally.mult = c.tally.mult * BigValue.of(3)
        },
        "j_cry_lightupthenight" to reg(setOf(Ctx.INDIVIDUAL_SCORED)) { c ->               // x1.5 Mult per scored rank 2 or 7
            val r = c.scoredPlaying?.rank
            if (r == 2 || r == 7) c.tally.mult = c.tally.mult * BigValue.of(1.5)
        },
        "j_cry_weegaming" to reg(setOf(Ctx.RETRIGGER)) { c ->                             // +2 retriggers per scored rank 2
            if (c.scoredPlaying?.rank == 2) c.retriggers += 2
        },
        "j_cry_brokenhome" to reg(setOf(Ctx.JOKER_MAIN)) { c ->                           // x11.4 Mult (self-destruct is end-of-round only)
            c.tally.mult = c.tally.mult * BigValue.of(11.4)
        },
        // scaling: x_mult accumulates +0.02 per scored card during play, applied at joker_main.
        // The accumulator is DATA on the joker entity (a component), not a field on a subclass — the
        // composition-faithful home for per-instance perpetual state.
        "j_cry_krustytheclown" to { w: World, e: Effects ->
            val j = w.create()
            w.add(j, Scaling(1.0))
            e.register(j, setOf(Ctx.INDIVIDUAL_SCORED)) { world, c -> world.get<Scaling>(c.self)!!.x += 0.02 }
            e.register(j, setOf(Ctx.JOKER_MAIN)) { world, c ->
                val x = world.get<Scaling>(c.self)!!.x
                if (x > 1.0) c.tally.mult = c.tally.mult * BigValue.of(x)
            }
            j
        },
    )

    /** Mutable per-joker scaling accumulator, stored as a component on the joker entity. */
    private class Scaling(var x: Double) : Component

    private fun reg(contexts: Set<Ctx>, effect: (Context) -> Unit): (World, Effects) -> Entity =
        { w, e -> val j = w.create(); e.register(j, contexts) { _, c -> effect(c) }; j }

    /** Instantiate a loadout by keys, in board order. */
    fun loadout(world: World, effects: Effects, keys: List<String>) =
        keys.forEach { byKey[it]?.invoke(world, effects) ?: error("unported joker: $it") }
}
