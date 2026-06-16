package systems.balatro.game

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow

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

/**
 * A joker on the board: its key + the per-instance ability state its calculate_joker branch reads.
 * Scaling jokers accumulate in non-scoring events (the run loop owns that); at score time they read
 * the current value. Zero-defaults make every scaling joker a no-op (multMod 0 / xMultMod 1 /
 * chipMod 0), so an un-wired joker never perturbs the oracle.
 *   mult  — accumulated +Mult (green/trousers/swashbuckler)
 *   x     — accumulated Xmult (obelisk/hologram/ramen/loyalty/throwback), 1.0 = none
 *   chips — accumulated +Chips (square/runner/castle)
 *   n     — a count the joker scales by (steel: steel cards; stone: stone cards; banner: discards;
 *           abstract: jokers; supernova: hand-type plays; blue: deck size; drivers: enhanced cards)
 */
class FJoker(
    val key: String, var mult: Double = 0.0, val edition: String = "", var x: Double = 1.0,
    var chips: Double = 0.0, var n: Int = 0, val rarity: Int = 0, var xc: Double = 1.0,
)

/** Balatro's `context` table — the flags a calculate_joker / eval_card branch inspects. */
class Sctx {
    var cardarea: String = ""              // "play" | "hand" | "jokers"
    var fullHand: List<PlayingCard> = emptyList()
    var scoringHand: List<PlayingCard> = emptyList()
    var scoringName: HandType = HandType.NONE
    var pokerHands: Set<HandType> = emptySet()   // context.poker_hands: every hand type the played cards satisfy
    var otherCard: PlayingCard? = null     // the scored/held card a joker reacts to (individual)
    var otherJoker: FJoker? = null         // the board joker offered (joker-on-joker)
    var individual = false
    var jokerMain = false
    var before = false
    var repetition = false
    var edition = false
    var held = false                       // held-in-hand pass (cardarea == G.hand)
    var heldHand: List<PlayingCard> = emptyList()
    var handsLeft = -1                      // hands remaining this round (-1 = unknown; acrobat: ==0 last hand)
    var discardsLeft = -1                   // discards remaining (-1 = unknown; mystic_summit: ==0)
}

/** What eval_card / calculate_joker returns. INDIVIDUAL effects use chips/mult/x_mult; the
 *  joker_main pass uses chip_mod/mult_mod/Xmult_mod; the source distinguishes them by field name. */
class Fx {
    var chips = 0.0; var mult = 0.0; var xMult = 0.0; var hMult = 0.0
    var chipMod = 0.0; var multMod = 0.0; var xMultMod = 1.0; var xChipMod = 1.0   // Xchip_mod: Cryptid X-chips
    var eMult = 1.0                                                                  // Emult_mod: Cryptid exponential mult (mult^eMult)
    var repetitions = 0
    val empty get() = chips == 0.0 && mult == 0.0 && xMult == 0.0 && hMult == 0.0 &&
        chipMod == 0.0 && multMod == 0.0 && xMultMod == 1.0 && xChipMod == 1.0 && eMult == 1.0 && repetitions == 0
}

object Score {
    /** Composite ranks per Cryptid's primus prime-check — everything else (incl. Ace=14) is "prime". */
    private val PRIMUS_COMPOSITES = setOf(4, 6, 8, 9, 10, 11, 12, 13)

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
            // --- Cryptid individual ---
            "j_cry_iterum"            -> return Fx().apply { xMult = 2.0 }               // X2 Mult per scored played card (also retriggers in repetition block)
            "j_cry_lightupthenight"   -> if (oc.id == 2 || oc.id == 7) return Fx().apply { xMult = 1.5 }  // X1.5 per scored 2/7
            "j_cry_krustytheclown"    -> j.x += 0.02   // scaling: +0.02 Xmult per scored card, applied at joker_main
            "j_cry_wee_fib"           -> if (oc.id == 14 || oc.id == 2 || oc.id == 3 || oc.id == 5 || oc.id == 8) j.mult += 3.0  // +3 Mult/scored Fibonacci, applied at joker_main
            "j_cry_antennastoheaven"  -> if (oc.id == 4 || oc.id == 7) j.xc += 0.1   // scaling: +0.1 Xchips per scored 4/7, applied at joker_main
        }
        // REPETITION: jokers that retrigger a scored card (context.repetition)
        if (ctx.repetition && oc != null) when (j.key) {
            "j_cry_iterum"    -> return Fx().apply { repetitions = 1 }                   // +1 retrigger per scored played card (base; immutable max 40)
            "j_cry_weegaming" -> if (oc.id == 2) return Fx().apply { repetitions = 2 }   // +2 retriggers per scored 2
            "j_cry_nosound"   -> if (oc.id == 7) return Fx().apply { repetitions = 3 }   // +3 retriggers per scored 7
            "j_cry_exposed"   -> if (!oc.isFace) return Fx().apply { repetitions = 2 }   // +2 retriggers per scored non-face
            "j_cry_mask"      -> if (oc.isFace) return Fx().apply { repetitions = 3 }    // +3 retriggers per scored face
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
            // --- scaling / state joker_main (the run loop sets the accumulators; zero-defaults no-op) ---
            "j_green_joker", "j_spare_trousers", "j_swashbuckler", "j_red_card", "j_cry_wee_fib", "j_cry_zooble",
            "j_cry_poor_joker" ->
                if (j.mult > 0.0) return Fx().apply { multMod = j.mult }                       // accumulated +Mult
            // poor_joker: j.mult += mult_mod(4) each time this joker pays rent (rental context, non-scoring)
            "j_obelisk", "j_hologram", "j_ramen", "j_campfire", "j_loyalty_card", "j_throwback", "j_cry_krustytheclown", "j_cry_eternalflame", "j_cry_whip",
            "j_cry_dropshot", "j_cry_chili_pepper", "j_cry_mondrian", "j_cry_fading_joker", "j_cry_keychange" ->
                if (j.x > 1.0) return Fx().apply { xMultMod = j.x }                            // accumulated Xmult
            // dropshot:    j.x += Xmult_mod(0.2) * non-scoring-hand cards of random suit each hand (before, non-scoring)
            // chili_pepper: j.x += Xmult_mod(0.5) each end_of_round (non-scoring); self-destructs after rounds_remaining hits 0
            // mondrian:    j.x += extra(0.25) each end_of_round where discard was not used (non-scoring)
            // fading_joker: j.x += xmult_mod(1) when this perishable joker expires (perishable_debuffed, non-scoring)
            // keychange:   j.x += xmgain(0.25) each time a hand type is played for the first time this round (before, non-scoring); resets end_of_round
            "j_square", "j_runner", "j_castle", "j_wee", "j_cry_cursor", "j_cry_crustulum" ->
                if (j.chips != 0.0) return Fx().apply { chipMod = j.chips }                    // accumulated +Chips
            "j_steel_joker" -> if (j.n > 0) return Fx().apply { xMultMod = 1.0 + 0.2 * j.n }   // X(1 + 0.2*steel cards)
            "j_stone"       -> if (j.n > 0) return Fx().apply { chipMod = 25.0 * j.n }         // +25 / stone card
            "j_blue_joker"  -> if (j.n > 0) return Fx().apply { chipMod = 2.0 * j.n }          // +2 / deck card
            "j_banner"      -> if (j.n > 0) return Fx().apply { chipMod = 30.0 * j.n }         // +30 / remaining discard
            "j_supernova"   -> if (j.n > 0) return Fx().apply { multMod = j.n.toDouble() }     // +1 / this hand-type play
            "j_abstract"    -> if (j.n > 0) return Fx().apply { multMod = 3.0 * j.n }          // +3 / joker on board
            "j_drivers_license" -> if (j.n >= 16) return Fx().apply { xMultMod = 3.0 }         // X3 if >=16 enhanced
            "j_acrobat"     -> if (ctx.handsLeft == 0) return Fx().apply { xMultMod = 3.0 }    // X3 on last hand
            "j_mystic_summit" -> if (ctx.discardsLeft == 0) return Fx().apply { multMod = 15.0 } // +15 at 0 discards
            "j_cry_night"   -> if (ctx.handsLeft == 0) return Fx().apply { eMult = 3.0 }       // Emult: mult^3 on the final hand
            // stella_mortis: Emult scales via ending_shop (destroy planet -> +0.4 per planet, stored in j.x); starts at 1
            "j_cry_stella_mortis" -> if (j.x > 1.0) return Fx().apply { eMult = j.x }
            // circulus_pistoris: fires exactly when hands_left == 3 (Lua: >=hands_remaining && <hands_remaining+1, hands_remaining=3)
            "j_cry_circulus_pistoris" -> if (ctx.handsLeft == 3) return Fx().apply { xChipMod = PI; eMult = PI }
            // facile: Emult=3 (fixed) if scored-card count this hand <=10; counter tracked externally (j.n);
            //         nearly always fires (<= 5 cards in standard play; retrigger edge cases not modelled)
            "j_cry_facile" -> return Fx().apply { eMult = 3.0 }
            // exponentia: scales Emult (j.x, base 1.0) +Emult_mod(0.03) each time any xmult effect fires during scoring;
            //             joker_main reads j.x and applies mult^j.x when above 1 (no-op while x==1.0 / never scaled)
            "j_cry_exponentia" -> if (j.x > 1.0) return Fx().apply { eMult = j.x }
            // --- Cryptid joker_main ---
            "j_cry_cube"           -> return Fx().apply { chipMod = 6.0 }                      // +6 Chips
            "j_cry_brokenhome"     -> return Fx().apply { xMultMod = 11.4 }                    // X11.4 Mult
            "j_cry_triplet_rhythm" -> if (ctx.scoringHand.count { it.id == 3 } == 3) return Fx().apply { xMultMod = 3.0 }  // X3 iff exactly 3 threes
            // --- Cryptid "type" jokers: fire when the played cards CONTAIN this hand (context.poker_hands), flat ---
            "j_cry_giggly"    -> if (HandType.HIGH_CARD in ctx.pokerHands)      return Fx().apply { multMod = 4.0 }
            "j_cry_silly"     -> if (HandType.FULL_HOUSE in ctx.pokerHands)     return Fx().apply { multMod = 16.0 }
            "j_cry_nutty"     -> if (HandType.FOUR_OF_A_KIND in ctx.pokerHands) return Fx().apply { multMod = 19.0 }
            "j_cry_manic"     -> if (HandType.STRAIGHT_FLUSH in ctx.pokerHands) return Fx().apply { multMod = 22.0 }
            "j_cry_delirious" -> if (HandType.FIVE_OF_A_KIND in ctx.pokerHands) return Fx().apply { multMod = 22.0 }
            "j_cry_wacky"     -> if (HandType.FLUSH_HOUSE in ctx.pokerHands)    return Fx().apply { multMod = 30.0 }
            "j_cry_kooky"     -> if (HandType.FLUSH_FIVE in ctx.pokerHands)     return Fx().apply { multMod = 30.0 }
            "j_cry_dubious"   -> if (HandType.HIGH_CARD in ctx.pokerHands)      return Fx().apply { chipMod = 20.0 }
            "j_cry_shrewd"    -> if (HandType.FOUR_OF_A_KIND in ctx.pokerHands) return Fx().apply { chipMod = 150.0 }
            "j_cry_tricksy"   -> if (HandType.STRAIGHT_FLUSH in ctx.pokerHands) return Fx().apply { chipMod = 170.0 }
            "j_cry_foxy"      -> if (HandType.FULL_HOUSE in ctx.pokerHands)     return Fx().apply { chipMod = 130.0 }
            "j_cry_savvy"     -> if (HandType.FIVE_OF_A_KIND in ctx.pokerHands) return Fx().apply { chipMod = 170.0 }
            "j_cry_subtle"    -> if (HandType.FLUSH_HOUSE in ctx.pokerHands)    return Fx().apply { chipMod = 240.0 }
            "j_cry_discreet"  -> if (HandType.FLUSH_FIVE in ctx.pokerHands)     return Fx().apply { chipMod = 240.0 }
            "j_cry_nuts"      -> if (HandType.STRAIGHT_FLUSH in ctx.pokerHands) return Fx().apply { xMultMod = 5.0 }
            "j_cry_quintet"   -> if (HandType.FIVE_OF_A_KIND in ctx.pokerHands) return Fx().apply { xMultMod = 5.0 }
            "j_cry_unity"     -> if (HandType.FLUSH_HOUSE in ctx.pokerHands)    return Fx().apply { xMultMod = 9.0 }
            "j_cry_swarm"     -> if (HandType.FLUSH_FIVE in ctx.pokerHands)     return Fx().apply { xMultMod = 9.0 }
            "j_cry_duos"      -> if (HandType.TWO_PAIR in ctx.pokerHands || HandType.FULL_HOUSE in ctx.pokerHands) return Fx().apply { xMultMod = 2.5 }  // X2.5 Two Pair/Full House
            "j_cry_home"      -> if (HandType.FULL_HOUSE in ctx.pokerHands)    return Fx().apply { xMultMod = 3.5 }
            "j_cry_filler"    -> if (HandType.HIGH_CARD in ctx.pokerHands)     return Fx().apply { xMultMod = 1.00000000000003 }  // meme: ~X1 always
            "j_cry_nice"      -> if (ctx.fullHand.any { it.id == 6 } && ctx.fullHand.any { it.id == 9 }) return Fx().apply { chipMod = 420.0 }  // +420 Chips on a "69"
            "j_cry_big_cube"  -> return Fx().apply { xChipMod = 6.0 }   // X6 Chips
            // antennastoheaven: j.xc += 0.1 per scored 4/7 (individual, accumulated above)
            // spaceglobe: j.xc += Xchipmod(0.2) each time the current target hand type is played (before, non-scoring); target rotates on match
            "j_cry_antennastoheaven", "j_cry_spaceglobe" -> if (j.xc > 1.0) return Fx().apply { xChipMod = j.xc }  // accumulated Xchips
            // supercell: +15 Chips, X2 Chips, +15 Mult, X2 Mult (config.extra.stat1=15, stat2=2; non-modest path)
            "j_cry_supercell" -> return Fx().apply { chipMod = 15.0; xChipMod = 2.0; multMod = 15.0; xMultMod = 2.0 }
            // m: X(x_mult) Mult; x_mult starts at 1, gains +13 each time a Jolly Joker is sold (selling_card, non-scoring)
            "j_cry_m" -> if (j.x > 1.0) return Fx().apply { xMultMod = j.x }
        }
        // HELD-IN-HAND: jokers reacting to each card held (context.cardarea == G.hand)
        if (ctx.held && oc != null) when (j.key) {
            "j_baron"          -> if (oc.id == 13) return Fx().apply { xMult = 1.5 }           // King held: X1.5
            "j_shoot_the_moon" -> if (oc.id == 12) return Fx().apply { mult = 13.0 }           // Queen held: +13 Mult
            "j_raised_fist"    -> {                                                            // +2x nominal of LOWEST held card
                val low = ctx.heldHand.filter { it.enhancement != Enhancement.STONE }.minByOrNull { it.nominal }
                if (low != null && oc == low) return Fx().apply { mult = 2.0 * low.chips }
            }
        }
        // OTHER_JOKER: a joker reacting to each board joker (context.other_joker)
        val oj = ctx.otherJoker
        if (oj != null) when (j.key) {
            "j_baseball"     -> if (oj !== j && oj.rarity == 2) return Fx().apply { xMultMod = 1.5 }  // X1.5 / Uncommon joker
            "j_cry_waluigi"  -> return Fx().apply { xMultMod = 2.5 }                                  // X2.5 once per board joker (incl self)
            // --- Cryptid edition reactors (the card-edition branch is unreachable: cards carry no edition here) ---
            "j_cry_meteor"    -> if (oj !== j && oj.edition == "Foil") return Fx().apply { chipMod = 75.0 }   // +75 Chips / other Foil joker
            "j_cry_exoplanet" -> if (oj !== j && oj.edition == "Holo") return Fx().apply { multMod = 15.0 }   // +15 Mult / other Holo joker
            "j_cry_stardust"  -> if (oj !== j && oj.edition == "Poly") return Fx().apply { xMultMod = 2.0 }   // X2 Mult / other Poly joker
        }
        return null
    }

    /** evaluate_play (state_events.lua:571) — the cascade. `trace` (when non-null) records the
     *  running chips/mult after the base and after each scoring card + the joker passes, so the UI
     *  can animate the build-up. trace=null is the oracle/hot path (no overhead). */
    fun score(
        played: List<PlayingCard>, jokers: List<FJoker>, held: List<PlayingCard> = emptyList(),
        level: Int = 1, debuff: Debuff = Debuff.None, handsLeft: Int = -1, discardsLeft: Int = -1,
        trace: MutableList<ScoreStep>? = null,
    ): ScoreResult {
        // j_cry_maximized patches get_id: pips collide at 10, faces at 13 (so disparate faces pair).
        val rankOf: (PlayingCard) -> Int =
            if (jokers.any { it.key == "j_cry_maximized" }) { c -> c.id.let { if (it in 2..10) 10 else if (it in 11..13) 13 else it } }
            else { c -> c.id }
        val (handType, handCards, pokerHands) = Hands.evaluate(played, rankOf)
        // scoring_hand = hand cards + stones (always score), in played order (Splash would add all)
        val scoringHand = played.filter { it in handCards || it.enhancement == Enhancement.STONE }

        // hand base, raised by planet level (lvl 1 = unchanged), then halved by Flint (base only).
        var chips = (handType.baseChips + (level - 1) * handType.lChips).toDouble()
        var mult = (handType.baseMult + (level - 1) * handType.lMult).toDouble()
        if (debuff is Debuff.Flint) { chips = floor(chips / 2); mult = floor(mult / 2) }
        val ctx = Sctx().apply {
            fullHand = played; this.scoringHand = scoringHand; scoringName = handType; this.pokerHands = pokerHands
            this.handsLeft = handsLeft; this.discardsLeft = discardsLeft
        }

        // BEFORE pass: j_cry_primus raises its Emult (j.x, base 1.01) by 0.17 if the whole hand is prime.
        for (j in jokers) if (j.key == "j_cry_primus" && played.all { it.id !in PRIMUS_COMPOSITES }) j.x += 0.17
        // j_cry_zooble: +1 Mult per DISTINCT rank in the scoring hand, unless the hand is a Straight (scaling).
        for (j in jokers) if (j.key == "j_cry_zooble" && HandType.STRAIGHT !in pokerHands && HandType.STRAIGHT_FLUSH !in pokerHands)
            j.mult += scoringHand.filter { it.enhancement != Enhancement.STONE }.map { it.id }.distinct().size.toDouble()
        // j_cry_whip: +0.5 Xmult if the played hand holds a 2 and a 7 of different suits (WILD = all suits).
        for (j in jokers) if (j.key == "j_cry_whip") {
            fun suitsOf(id: Int) = played.filter { it.id == id }
                .flatMap { if (it.enhancement == Enhancement.WILD) Suit.values().toList() else listOf(it.suit) }.toSet()
            val ts = suitsOf(2); val ss = suitsOf(7)
            if (ts.isNotEmpty() && ss.isNotEmpty() && (ts.size > 1 || ss.size > 1 || ts.first() != ss.first())) j.x += 0.5
        }
        trace?.add(ScoreStep("base · ${handType.name.lowercase().replace('_', ' ')}", chips, mult))

        fun apply(fx: Fx) {                         // the effects[ii] application block (lines 702-777)
            if (fx.chips != 0.0) chips += fx.chips
            if (fx.mult != 0.0) mult += fx.mult
            if (fx.xMult != 0.0) {
                mult *= fx.xMult
                // exponentia: +Emult_mod(0.03) each time a non-trivial xmult fires during scored-card pass
                if (fx.xMult != 1.0) for (ej in jokers) if (ej.key == "j_cry_exponentia") ej.x += 0.03
            }
        }

        // per scoring card: card's own scoring + each joker's individual reaction
        for (card in scoringHand) {
            if (debuff is Debuff.DebuffSuit && card.suit == debuff.suit) continue   // debuffed: scores/triggers nothing
            ctx.cardarea = "play"; ctx.individual = false; ctx.otherCard = card
            var reps = 1 + (if (card.seal == Seal.RED) 1 else 0)            // red seal repetition
            for (jk in jokers) { ctx.repetition = true; calcJoker(jk, ctx)?.let { reps += it.repetitions }; ctx.repetition = false }  // joker retriggers
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
            trace?.add(ScoreStep("+ ${card.label}", chips, mult))
        }

        // held-in-hand pass: the card's own held effect (steel x1.5) + each joker reacting to held cards
        ctx.heldHand = held
        for (card in held) {
            ctx.cardarea = "hand"; ctx.held = true; ctx.otherCard = card
            val effects = ArrayList<Fx>()
            effects.add(evalCard(card, ctx))
            for (j in jokers) calcJoker(j, ctx)?.let { effects.add(it) }
            for (fx in effects) { if (fx.xMult != 0.0) mult *= fx.xMult; if (fx.mult != 0.0) mult += fx.mult; if (fx.hMult != 0.0) mult += fx.hMult }
            ctx.held = false
        }

        // JOKER MAIN pass: each joker's main effect, then its edition (foil/holo/poly) — board order.
        // j_cry_oldblueprint re-applies the joker to its right; j_cry_primus is the Emult pow (mult^x).
        for ((idx, j) in jokers.withIndex()) {
            ctx.cardarea = "jokers"; ctx.jokerMain = true; ctx.individual = false; ctx.otherCard = null
            val target = if (j.key == "j_cry_oldblueprint") jokers.getOrNull(idx + 1) else j
            if (target != null) {
                if (target.key == "j_cry_primus") { if (target.x > 1.0) mult = mult.pow(target.x) }
                else calcJoker(target, ctx)?.let { fx ->
                    if (fx.chipMod != 0.0) chips += fx.chipMod
                    if (fx.xChipMod != 1.0) chips *= fx.xChipMod   // X-chips multiplies the running chip total
                    if (fx.multMod != 0.0) mult += fx.multMod
                    if (fx.xMultMod != 1.0) mult *= fx.xMultMod
                    if (fx.eMult != 1.0) mult = mult.pow(fx.eMult)  // Emult raises mult to a power (applied last)
                }
            }
            when (j.edition) { "Foil" -> chips += 50.0; "Holo" -> mult += 10.0; "Poly" -> mult *= 1.5 }
            ctx.jokerMain = false
        }

        // OTHER_JOKER pass: every board joker offered to each joker once (joker-on-joker), board order
        for (other in jokers) {
            ctx.otherJoker = other; ctx.cardarea = "jokers"
            for (j in jokers) calcJoker(j, ctx)?.let { fx ->
                if (fx.multMod != 0.0) mult += fx.multMod
                if (fx.chipMod != 0.0) chips += fx.chipMod
                if (fx.xMultMod != 1.0) mult *= fx.xMultMod
            }
        }
        ctx.otherJoker = null

        if (jokers.isNotEmpty()) trace?.add(ScoreStep("jokers", chips, mult))
        return ScoreResult(handType, chips, mult, floor(chips * mult))
    }
}
