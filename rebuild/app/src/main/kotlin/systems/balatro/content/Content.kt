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
        "j_lusty_joker" to reg(setOf(Ctx.INDIVIDUAL_SCORED)) { c ->                       // +3 Mult per scored Heart
            if (c.scoredPlaying?.suit == Suit.H) c.tally.mult = c.tally.mult + BigValue.of(3)
        },
        "j_wrathful_joker" to reg(setOf(Ctx.INDIVIDUAL_SCORED)) { c ->                    // +3 Mult per scored Spade
            if (c.scoredPlaying?.suit == Suit.S) c.tally.mult = c.tally.mult + BigValue.of(3)
        },
        "j_gluttenous_joker" to reg(setOf(Ctx.INDIVIDUAL_SCORED)) { c ->                  // +3 Mult per scored Club (base-game spelling)
            if (c.scoredPlaying?.suit == Suit.C) c.tally.mult = c.tally.mult + BigValue.of(3)
        },
        "j_even_steven" to reg(setOf(Ctx.INDIVIDUAL_SCORED)) { c ->                       // +4 Mult per scored even rank (2,4,6,8,10)
            if (c.scoredPlaying?.rank in setOf(2, 4, 6, 8, 10)) c.tally.mult = c.tally.mult + BigValue.of(4)
        },
        "j_odd_todd" to reg(setOf(Ctx.INDIVIDUAL_SCORED)) { c ->                          // +31 Chips per scored odd rank (A,3,5,7,9)
            val r = c.scoredPlaying?.rank; if (r == 14 || r in setOf(3, 5, 7, 9)) c.tally.chips = c.tally.chips + BigValue.of(31)
        },
        "j_scholar" to reg(setOf(Ctx.INDIVIDUAL_SCORED)) { c ->                           // +20 Chips & +4 Mult per scored Ace
            if (c.scoredPlaying?.rank == 14) { c.tally.chips = c.tally.chips + BigValue.of(20); c.tally.mult = c.tally.mult + BigValue.of(4) }
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
        // Broken Home: x11.4 Mult at JOKER_MAIN, self-destructs at END_OF_ROUND (round won).
        // The factory registers two handlers on the same entity. dispatchEndOfRound sweeps
        // world.store<SelfDestruct>() and destroys every marked entity after the pass.
        "j_cry_brokenhome" to { w: World, e: Effects ->
            val j = newJoker(w)
            e.register(j, setOf(Ctx.JOKER_MAIN)) { _, c ->
                c.tally.mult = c.tally.mult * BigValue.of(11.4)
            }
            e.register(j, setOf(Ctx.END_OF_ROUND)) { world, c ->
                world.add(c.self, SelfDestruct())
            }
            j
        },
        // scaling: x_mult accumulates +0.02 per scored card during play, applied at joker_main.
        // The accumulator is DATA on the joker entity (a component), not a field on a subclass — the
        // composition-faithful home for per-instance perpetual state.
        "j_cry_krustytheclown" to { w: World, e: Effects ->
            val j = newJoker(w)
            w.add(j, Scaling(1.0))
            e.register(j, setOf(Ctx.INDIVIDUAL_SCORED)) { world, c -> world.get<Scaling>(c.self)!!.x += 0.02 }
            e.register(j, setOf(Ctx.JOKER_MAIN)) { world, c ->
                val x = world.get<Scaling>(c.self)!!.x
                if (x > 1.0) c.tally.mult = c.tally.mult * BigValue.of(x)
            }
            j
        },
        // cross-joker: x2.5 Mult once per joker on the board (incl. itself — no self-exclusion).
        // Fires in the OTHER_JOKER pass that runs after joker_main, so flat +Mult jokers land first.
        "j_cry_waluigi" to reg(setOf(Ctx.OTHER_JOKER)) { c ->
            c.tally.mult = c.tally.mult * BigValue.of(2.5)
        },
        // blueprint: re-apply the joker immediately to the right, for whatever context is
        // firing — the copy reads the COPIED joker's own state (self = the target). Needs the
        // Effects instance to invoke one joker's handler, so it captures `e` from the factory.
        "j_cry_oldblueprint" to { w: World, e: Effects ->
            val j = newJoker(w)
            val copyCtxs = setOf(Ctx.BEFORE, Ctx.INDIVIDUAL_SCORED, Ctx.JOKER_MAIN, Ctx.RETRIGGER, Ctx.AFTER)
            e.register(j, copyCtxs) { world, c ->
                val target = Board.next(world, c.self) ?: return@register
                e.dispatchJoker(world, c, c.phase, target)
            }
            j
        },
        // hand-eval rank patch: face cards (J/Q/K) collide at 13, pips (2-10) at 10, so
        // disparate face cards form a set. A RankMod component on the joker, not a tally
        // Effect — it changes which hand the cards make, before any scoring fires.
        "j_cry_maximized" to { w: World, _: Effects ->
            val j = newJoker(w)
            w.add(j, RankMod { r -> if (r in 2..10) 10 else if (r in 11..13) 13 else r })
            j
        },
        // exponential (Talisman Emult): if the WHOLE played hand is prime-ranked, the
        // joker's Emult scales +0.17 (before), then at joker_main mult = mult ^ Emult.
        // "prime" = rank not composite per Cryptid's set (so A counts as prime, 10 does not).
        "j_cry_primus" to { w: World, e: Effects ->
            val j = newJoker(w)
            w.add(j, Scaling(1.01))
            e.register(j, setOf(Ctx.BEFORE)) { world, c ->
                if (c.playedCards.all { it.rank !in PRIMUS_COMPOSITES }) world.get<Scaling>(c.self)!!.x += 0.17
            }
            e.register(j, setOf(Ctx.JOKER_MAIN)) { world, c ->
                val emult = world.get<Scaling>(c.self)!!.x
                if (emult > 1.0) c.tally.mult = c.tally.mult.pow(emult)
            }
            j
        },
    )

    /** Composite ranks per Cryptid's primus check — everything else (incl. Ace=14) is "prime". */
    private val PRIMUS_COMPOSITES = setOf(4, 6, 8, 9, 10, 11, 12, 13)

    /** Mutable per-joker scalar accumulator on the joker entity (krusty x_mult, primus Emult). */
    private class Scaling(var x: Double) : Component

    /** Every joker enters the board (ordered slot store) on creation. */
    private fun newJoker(w: World): Entity = w.create().also { Board.add(w, it) }

    private fun reg(contexts: Set<Ctx>, effect: (Context) -> Unit): (World, Effects) -> Entity =
        { w, e -> val j = newJoker(w); e.register(j, contexts) { _, c -> effect(c) }; j }

    /** Instantiate a loadout by keys, in board order. */
    fun loadout(world: World, effects: Effects, keys: List<String>) =
        keys.forEach { byKey[it]?.invoke(world, effects) ?: error("unported joker: $it") }
}
