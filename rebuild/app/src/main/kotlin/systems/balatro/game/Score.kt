package systems.balatro.game

import kotlin.math.floor

/**
 * FAITHFUL 1:1 port of Balatro's scoring cascade — evaluate_play (state_events.lua:571),
 * eval_card (common_events.lua:580), the Card:get_chip_* helpers, and Card:calculate_joker
 * (card.lua) — with the animation/event machinery (delay, G.E_MANAGER, juice, sounds, status
 * text) stripped. The CONTEXT shape and the per-effect application order match the source, so a
 * joker is a literal translation of its Lua calculate_joker branch, not a reinterpretation.
 *
 * Built ALONGSIDE the composition ScoreRun while jokers port over; the oracle runs both and only
 * switches when this path is also green. Optimization (collapsing back toward composition) is a
 * later pass — parity first.
 */

/** A joker on the board: its key + the ability params its calculate_joker branch reads. */
class FJoker(val key: String, val mult: Double = 0.0, val edition: String = "", var x: Double = 1.0)

/** Balatro's `context` table — the flags a calculate_joker / eval_card branch inspects. */
class Sctx {
    var cardarea: String = ""              // "play" | "hand" | "jokers"
    var fullHand: List<PlayingCard> = emptyList()
    var scoringHand: List<PlayingCard> = emptyList()
    var scoringName: HandType = HandType.NONE
    var otherCard: PlayingCard? = null     // the scored/held card a joker reacts to (individual)
    var otherJoker: FJoker? = null         // the board joker offered (joker-on-joker)
    var individual = false
    var jokerMain = false
    var before = false
    var repetition = false
    var edition = false
}

/** What eval_card / calculate_joker returns. INDIVIDUAL effects use chips/mult/x_mult; the
 *  joker_main pass uses chip_mod/mult_mod/Xmult_mod; the source distinguishes them by field name. */
class Fx {
    var chips = 0.0; var mult = 0.0; var xMult = 0.0; var hMult = 0.0
    var chipMod = 0.0; var multMod = 0.0; var xMultMod = 1.0
    var repetitions = 0
    val empty get() = chips == 0.0 && mult == 0.0 && xMult == 0.0 && hMult == 0.0 &&
        chipMod == 0.0 && multMod == 0.0 && xMultMod == 1.0 && repetitions == 0
}

object Score {
    // --- card scoring helpers (Card:get_chip_*), the played-card's own contribution -------------
    private fun chipBonus(c: PlayingCard): Double = when (c.enhancement) {   // get_chip_bonus
        Enhancement.STONE -> 50.0
        Enhancement.BONUS -> c.chips + 30.0
        else -> c.chips.toDouble()
    }
    private fun chipMult(c: PlayingCard): Double = if (c.enhancement == Enhancement.MULT) 4.0 else 0.0
    private fun chipXMult(c: PlayingCard): Double = if (c.enhancement == Enhancement.GLASS) 2.0 else 0.0
    private fun chipHXMult(c: PlayingCard): Double = if (c.enhancement == Enhancement.STEEL) 1.5 else 0.0

    /** eval_card (common_events.lua:580): a card's own scoring for its cardarea. */
    private fun evalCard(c: PlayingCard, ctx: Sctx): Fx {
        val r = Fx()
        when (ctx.cardarea) {
            "play" -> { r.chips = chipBonus(c); r.mult = chipMult(c); r.xMult = chipXMult(c) }
            "hand" -> { r.xMult = chipHXMult(c) }
        }
        return r
    }

    /** Card:calculate_joker — every joker's effect, dispatched by key + context (1:1 with the Lua). */
    private fun calcJoker(j: FJoker, ctx: Sctx): Fx? {
        val oc = ctx.otherCard
        // INDIVIDUAL: a joker reacting to each scored card (context.individual, cardarea == G.play)
        if (ctx.individual && ctx.cardarea == "play" && oc != null) when (j.key) {
            "j_greedy_joker"     -> if (oc.isSuit(Suit.D)) return Fx().apply { mult = 3.0 }
            "j_lusty_joker"      -> if (oc.isSuit(Suit.H)) return Fx().apply { mult = 3.0 }
            "j_wrathful_joker"   -> if (oc.isSuit(Suit.S)) return Fx().apply { mult = 3.0 }
            "j_gluttenous_joker" -> if (oc.isSuit(Suit.C)) return Fx().apply { mult = 3.0 }
            "j_even_steven"      -> if (oc.id in setOf(2, 4, 6, 8, 10)) return Fx().apply { mult = 4.0 }
            "j_odd_todd"         -> if (oc.id == 14 || oc.id in setOf(3, 5, 7, 9)) return Fx().apply { chips = 31.0 }
            "j_scholar"          -> if (oc.id == 14) return Fx().apply { chips = 20.0; mult = 4.0 }
            // --- vanilla individual jokers, faithful from calculate_joker (port-vanilla-jokers workflow) ---
            "j_arrowhead"        -> if (oc.isSuit(Suit.S)) return Fx().apply { chips = 50.0 }      // +50 Chips/Spade
            "j_onyx_agate"       -> if (oc.isSuit(Suit.C)) return Fx().apply { mult = 7.0 }        // +7 Mult/Club
            "j_fibonacci"        -> if (oc.id in setOf(2, 3, 5, 8, 14)) return Fx().apply { mult = 8.0 }  // +8 Mult per A/2/3/5/8
            "j_scary_face"       -> if (oc.isFace) return Fx().apply { chips = 30.0 }              // +30 Chips/face
            "j_smiley"           -> if (oc.isFace) return Fx().apply { mult = 5.0 }                // +5 Mult/face
            "j_triboulet"        -> if (oc.id == 12 || oc.id == 13) return Fx().apply { xMult = 2.0 }  // X2 Mult/K,Q
            "j_walkie_talkie"    -> if (oc.id == 10 || oc.id == 4) return Fx().apply { chips = 10.0; mult = 4.0 }  // 10/4 -> +10c +4m
            "j_photograph"       -> if (oc.isFace && ctx.scoringHand.firstOrNull { it.isFace } == oc) return Fx().apply { xMult = 2.0 }  // X2 on FIRST face
        }
        // JOKER_MAIN: the joker's main flat/scaling effect (context.joker_main)
        if (ctx.jokerMain) when (j.key) {
            "j_joker"     -> return Fx().apply { multMod = 4.0 }
            // --- vanilla joker_main, self-contained (computed from the played/scoring hand) ---
            "j_half"      -> if (ctx.fullHand.size <= 3) return Fx().apply { multMod = 20.0 }       // +20 Mult if <=3 cards
            "j_stuntman"  -> return Fx().apply { chipMod = 250.0 }                                  // +250 Chips
            "j_seeing_double" -> {                                                                  // X2 if a Club + a non-Club score
                val club = ctx.scoringHand.any { it.isSuit(Suit.C) }
                val other = ctx.scoringHand.any { it.enhancement != Enhancement.STONE && !it.isSuit(Suit.C) }
                if (club && other) return Fx().apply { xMultMod = 2.0 }
            }
            "j_flower_pot" -> if (Suit.values().all { s -> ctx.scoringHand.any { it.isSuit(s) } })  // X3 if all 4 suits score
                return Fx().apply { xMultMod = 3.0 }
        }
        return null
    }

    /** evaluate_play (state_events.lua:571) — the cascade, score only (animation stripped). */
    fun score(played: List<PlayingCard>, jokers: List<FJoker>, held: List<PlayingCard> = emptyList()): Double {
        val (handType, handCards) = Hands.evaluate(played)
        // scoring_hand = hand cards + stones (always score), in played order (Splash would add all)
        val scoringHand = played.filter { it in handCards || it.enhancement == Enhancement.STONE }

        var chips = handType.baseChips.toDouble()
        var mult = handType.baseMult.toDouble()
        val ctx = Sctx().apply { fullHand = played; this.scoringHand = scoringHand; scoringName = handType }

        fun apply(fx: Fx) {                         // the effects[ii] application block (lines 702-777)
            if (fx.chips != 0.0) chips += fx.chips
            if (fx.mult != 0.0) mult += fx.mult
            if (fx.xMult != 0.0) mult *= fx.xMult
        }

        // per scoring card: card's own scoring + each joker's individual reaction
        for (card in scoringHand) {
            ctx.cardarea = "play"; ctx.individual = false; ctx.otherCard = null
            var reps = 1 + (if (card.seal == Seal.RED) 1 else 0)            // red seal repetition
            repeat(reps) {
                val effects = ArrayList<Fx>()
                effects.add(evalCard(card, ctx))
                for (j in jokers) {
                    ctx.individual = true; ctx.otherCard = card
                    calcJoker(j, ctx)?.let { effects.add(it) }
                    ctx.individual = false
                }
                for (fx in effects) apply(fx)
            }
        }

        // held-in-hand pass: steel x1.5 etc.
        for (card in held) {
            ctx.cardarea = "hand"; ctx.otherCard = card
            val fx = evalCard(card, ctx)
            if (fx.xMult != 0.0) mult *= fx.xMult
            if (fx.hMult != 0.0) mult += fx.hMult
        }

        // JOKER MAIN pass: each joker's main effect (+ joker-on-joker, edition) — applied in board order
        for (j in jokers) {
            ctx.cardarea = "jokers"; ctx.jokerMain = true; ctx.individual = false; ctx.otherCard = null
            calcJoker(j, ctx)?.let { fx ->
                if (fx.multMod != 0.0) mult += fx.multMod
                if (fx.chipMod != 0.0) chips += fx.chipMod
                if (fx.xMultMod != 1.0) mult *= fx.xMultMod
            }
            ctx.jokerMain = false
        }

        return floor(chips * mult)
    }
}
