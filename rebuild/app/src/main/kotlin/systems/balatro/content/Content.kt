package systems.balatro.content

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
        "j_cry_triplet_rhythm" to reg(setOf(Ctx.JOKER_MAIN)) { c ->                       // x3 Mult
            c.tally.mult = c.tally.mult * BigValue.of(3)
        },
    )

    private fun reg(contexts: Set<Ctx>, effect: (Context) -> Unit): (World, Effects) -> Entity =
        { w, e -> val j = w.create(); e.register(j, contexts) { _, c -> effect(c) }; j }

    /** Instantiate a loadout by keys, in board order. */
    fun loadout(world: World, effects: Effects, keys: List<String>) =
        keys.forEach { byKey[it]?.invoke(world, effects) ?: error("unported joker: $it") }
}
