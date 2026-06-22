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
    var boardKeys: List<String> = emptyList()   // keys of every joker on the board (jtron counts j_joker)
    var handsLeft = -1                      // hands remaining this round (-1 = unknown; acrobat: ==0 last hand)
    var discardsLeft = -1                   // discards remaining (-1 = unknown; mystic_summit: ==0)
    var bossBlind = false                   // true when current blind is a boss blind (apjoker)
    var smeared = false                     // Smeared Joker: red/black suits collide in every is_suit check
    var pareidolia = false                  // Pareidolia: every card counts as a face in every is_face check
    var debuffSuit: Suit? = null            // boss suit-debuff: cards of this suit score/trigger nothing and are never faces
    var board: List<FJoker> = emptyList()   // every joker in board order — Blueprint/Brainstorm resolve copy targets here
    var blueprintDepth = 0                  // copy-chain depth (context.blueprint); bounded by board size to stop cycles
    var jokerRetriggerCheck = false          // true during the retrigger sub-loop (mirrors context.retrigger_joker_check)
    var retriggeredJoker: FJoker? = null     // the board joker currently being evaluated for retriggers (context.other_card)
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
        // Copy-jokers (SMODS.blueprint_effect, utils.lua:2089) delegate to a target joker's calculate in EVERY
        // context: Brainstorm copies the leftmost joker; Blueprint / Old Blueprint copy the joker to their right.
        // Skip a missing/self target; the copy-chain depth is bounded by board size (stops Brainstorm⇄Blueprint cycles).
        when (j.key) {
            "j_blueprint", "j_cry_oldblueprint", "j_brainstorm" -> {
                val target = if (j.key == "j_brainstorm") ctx.board.firstOrNull()
                    else ctx.board.indexOfFirst { it === j }.let { i -> if (i < 0) null else ctx.board.getOrNull(i + 1) }
                if (target == null || target === j || ctx.blueprintDepth > ctx.board.size) return null
                ctx.blueprintDepth++
                val ret = calcJoker(target, ctx)
                ctx.blueprintDepth--
                return ret
            }
        }
        // RETRIGGER_JOKER_CHECK: each board joker votes whether to retrigger ctx.retriggeredJoker
        // (SMODS.calculate_retriggers, utils.lua:1602). Mirrors the per-card repetition guard.
        // The check fires ONLY in this sub-loop (jokerRetriggerCheck=true); not re-nested (like Lua's guard).
        if (ctx.jokerRetriggerCheck) {
            val rj = ctx.retriggeredJoker ?: return null
            when (j.key) {
                // chad: retrigger the LEFTMOST board joker j.n times (config.extra.retriggers=2).
                // If Chad itself is leftmost it retriggers itself (correct; Lua has no self-exclusion here).
                "j_cry_chad" -> if (rj === ctx.board.firstOrNull() && j.n > 0) return Fx().apply { repetitions = j.n }
                // loopy: retrigger all OTHER board jokers min(j.n, 40) times (j.n = Jolly Jokers sold; default 0).
                "j_cry_loopy" -> if (j !== rj && j.n > 0) return Fx().apply { repetitions = minOf(j.n, 40) }
                // spectrogram: retrigger the RIGHTMOST board joker j.n times (j.n = Echo-enhanced cards scored;
                //   accumulated during the per-card pass when m_cry_echo enhancement is present — no-op until
                //   m_cry_echo is modelled in the Enhancement enum).
                "j_cry_spectrogram" -> if (rj === ctx.board.lastOrNull() && j.n > 0) return Fx().apply { repetitions = j.n }
                // flip_side: retrigger any joker with the double-sided edition once.
                "j_cry_flip_side" -> if (rj.edition == "cry_double_sided") return Fx().apply { repetitions = 1 }
            }
            return null
        }
        val oc = ctx.otherCard
        // INDIVIDUAL: a joker reacting to each scored card (context.individual, cardarea == G.play)
        if (ctx.individual && ctx.cardarea == "play" && oc != null) when (j.key) {
            "j_greedy_joker"     -> if (oc.isSuit(Suit.D, ctx.smeared)) return Fx().apply { mult = 3.0 }
            "j_lusty_joker"      -> if (oc.isSuit(Suit.H, ctx.smeared)) return Fx().apply { mult = 3.0 }
            "j_wrathful_joker"   -> if (oc.isSuit(Suit.S, ctx.smeared)) return Fx().apply { mult = 3.0 }
            "j_gluttenous_joker" -> if (oc.isSuit(Suit.C, ctx.smeared)) return Fx().apply { mult = 3.0 }
            "j_even_steven"      -> if (oc.id in setOf(2, 4, 6, 8, 10)) return Fx().apply { mult = 4.0 }
            "j_odd_todd"         -> if (oc.id == 14 || oc.id in setOf(3, 5, 7, 9)) return Fx().apply { chips = 31.0 }
            "j_scholar"          -> if (oc.id == 14) return Fx().apply { chips = 20.0; mult = 4.0 }
            // --- vanilla individual jokers, faithful from calculate_joker (port-vanilla-jokers workflow) ---
            "j_arrowhead"        -> if (oc.isSuit(Suit.S, ctx.smeared)) return Fx().apply { chips = 50.0 }      // +50 Chips/Spade
            "j_onyx_agate"       -> if (oc.isSuit(Suit.C, ctx.smeared)) return Fx().apply { mult = 7.0 }        // +7 Mult/Club
            "j_fibonacci"        -> if (oc.id in setOf(2, 3, 5, 8, 14)) return Fx().apply { mult = 8.0 }  // +8 Mult per A/2/3/5/8
            "j_scary_face"       -> if (oc.isFace || ctx.pareidolia) return Fx().apply { chips = 30.0 }              // +30 Chips/face
            "j_smiley"           -> if (oc.isFace || ctx.pareidolia) return Fx().apply { mult = 5.0 }                // +5 Mult/face
            "j_triboulet"        -> if (oc.id == 12 || oc.id == 13) return Fx().apply { xMult = 2.0 }  // X2 Mult/K,Q
            "j_walkie_talkie"    -> if (oc.id == 10 || oc.id == 4) return Fx().apply { chips = 10.0; mult = 4.0 }  // 10/4 -> +10c +4m
            // X2 on the FIRST face. is_face (card.lua:1193) returns nil for a debuffed card BEFORE the Pareidolia
            // check, so a debuffed card is never the "first face" — exclude it from the scan (and from oc's own test).
            "j_photograph"       -> if ((oc.isFace || ctx.pareidolia) && oc.suit != ctx.debuffSuit &&
                ctx.scoringHand.firstOrNull { (it.isFace || ctx.pareidolia) && it.suit != ctx.debuffSuit } == oc) return Fx().apply { xMult = 2.0 }
            // --- Cryptid individual ---
            "j_cry_iterum"            -> return Fx().apply { xMult = 2.0 }               // X2 Mult per scored played card (also retriggers in repetition block)
            "j_cry_lightupthenight"   -> if (oc.id == 2 || oc.id == 7) return Fx().apply { xMult = 1.5 }  // X1.5 per scored 2/7
            "j_cry_krustytheclown"    -> j.x += 0.02   // scaling: +0.02 Xmult per scored card, applied at joker_main
            "j_cry_wee_fib"           -> if (oc.id == 14 || oc.id == 2 || oc.id == 3 || oc.id == 5 || oc.id == 8) j.mult += 3.0  // +3 Mult/scored Fibonacci, applied at joker_main
            "j_cry_antennastoheaven"  -> if (oc.id == 4 || oc.id == 7) j.xc += 0.1   // scaling: +0.1 Xchips per scored 4/7, applied at joker_main
            // caramel: X1.75 Mult per scored played card (j.x=1.75 default; decreases per round, self-destructs)
            "j_cry_caramel"           -> if (j.x >= 1.0) return Fx().apply { xMult = j.x }
        }
        // REPETITION: jokers that retrigger a scored card (context.repetition)
        if (ctx.repetition && oc != null) when (j.key) {
            "j_cry_iterum"    -> return Fx().apply { repetitions = 1 }                   // +1 retrigger per scored played card (base; immutable max 40)
            "j_cry_weegaming" -> if (oc.id == 2) return Fx().apply { repetitions = 2 }   // +2 retriggers per scored 2
            "j_cry_nosound"   -> if (oc.id == 7) return Fx().apply { repetitions = 3 }   // +3 retriggers per scored 7
            "j_cry_exposed"   -> if (!(oc.isFace || ctx.pareidolia)) return Fx().apply { repetitions = 2 }   // +2 retriggers per scored non-face
            "j_cry_mask"      -> if (oc.isFace || ctx.pareidolia) return Fx().apply { repetitions = 3 }    // +3 retriggers per scored face
            "j_cry_mstack"    -> if (ctx.cardarea == "play") return Fx().apply { repetitions = j.n }  // +j.n retriggers per scored played card (j.n=retriggers, default 1; earned by selling jolly jokers)
            // vanilla retrigger jokers (card.lua:3895): Sock and Buskin retriggers each face once;
            // Hanging Chad retriggers the FIRST scored card twice (context.other_card == scoring_hand[1]);
            // Dusk retriggers every played card on the last hand (hands_left == 0);
            // Hack retriggers 2/3/4/5 once each.
            "j_sock_and_buskin" -> if (oc.isFace || ctx.pareidolia) return Fx().apply { repetitions = 1 }
            "j_hanging_chad"    -> if (oc === ctx.scoringHand.firstOrNull()) return Fx().apply { repetitions = 2 }
            "j_dusk"            -> if (ctx.handsLeft == 0) return Fx().apply { repetitions = 1 }
            "j_hack"            -> if (oc.id in 2..5) return Fx().apply { repetitions = 1 }
        }
        // JOKER_MAIN: the joker's main flat/scaling effect (context.joker_main)
        if (ctx.jokerMain) when (j.key) {
            "j_joker"     -> return Fx().apply { multMod = 4.0 }
            // --- vanilla joker_main, self-contained (computed from the played/scoring hand) ---
            "j_half"      -> if (ctx.fullHand.size <= 3) return Fx().apply { multMod = 20.0 }       // +20 Mult if <=3 cards
            "j_stuntman"  -> return Fx().apply { chipMod = 250.0 }                                  // +250 Chips
            "j_seeing_double" -> {                                                                  // X2 if a Club + a non-Club score
                // seeing_double_check (utils.lua:2474) tallies via is_suit WITHOUT bypass_debuff, so a
                // debuffed card returns nil and is not counted on either side — exclude debuffed cards.
                val nd = ctx.scoringHand.filter { it.suit != ctx.debuffSuit }
                val club = nd.any { it.isSuit(Suit.C, ctx.smeared) }
                val other = nd.any { it.enhancement != Enhancement.STONE && !it.isSuit(Suit.C, ctx.smeared) }
                if (club && other) return Fx().apply { xMultMod = 2.0 }
            }
            // Flower Pot calls is_suit('<suit>', true) with bypass_debuff=true (card.lua:4358), so it COUNTS
            // debuffed cards' suits — the all-scoring-cards scan (incl. debuffed) is correct; do NOT exclude them.
            "j_flower_pot" -> if (Suit.values().all { s -> ctx.scoringHand.any { it.isSuit(s, ctx.smeared) } })  // X3 if all 4 suits score
                return Fx().apply { xMultMod = 3.0 }
            // --- vanilla "+chips if played hand contains <type>" family (game.lua j_sly..j_crafty;
            //     config {t_chips,type}). The generic branch (card.lua:4209) fires when the played cards
            //     CONTAIN the type via context.poker_hands — containment, not top rank (a Full House
            //     contains a Pair + Three of a Kind), exactly the Cryptid "type" jokers below. ---
            "j_sly"     -> if (HandType.PAIR in ctx.pokerHands)            return Fx().apply { chipMod = 50.0 }
            "j_wily"    -> if (HandType.THREE_OF_A_KIND in ctx.pokerHands) return Fx().apply { chipMod = 100.0 }
            "j_clever"  -> if (HandType.TWO_PAIR in ctx.pokerHands)        return Fx().apply { chipMod = 80.0 }
            "j_devious" -> if (HandType.STRAIGHT in ctx.pokerHands)        return Fx().apply { chipMod = 100.0 }
            "j_crafty"  -> if (HandType.FLUSH in ctx.pokerHands)           return Fx().apply { chipMod = 80.0 }
            // --- vanilla "+Mult if played hand contains <type>" family (game.lua j_jolly..j_droll;
            //     config {t_mult,type}). Same containment branch (card.lua:4203, mult_mod = t_mult). ---
            "j_jolly"   -> if (HandType.PAIR in ctx.pokerHands)            return Fx().apply { multMod = 8.0 }
            "j_zany"    -> if (HandType.THREE_OF_A_KIND in ctx.pokerHands) return Fx().apply { multMod = 12.0 }
            "j_mad"     -> if (HandType.TWO_PAIR in ctx.pokerHands)        return Fx().apply { multMod = 10.0 }
            "j_crazy"   -> if (HandType.STRAIGHT in ctx.pokerHands)        return Fx().apply { multMod = 12.0 }
            "j_droll"   -> if (HandType.FLUSH in ctx.pokerHands)           return Fx().apply { multMod = 10.0 }
            // --- scaling / state joker_main (the run loop sets the accumulators; zero-defaults no-op) ---
            "j_green_joker", "j_spare_trousers", "j_swashbuckler", "j_red_card", "j_cry_wee_fib", "j_cry_zooble",
            "j_cry_poor_joker", "j_cry_foodm" ->
                if (j.mult > 0.0) return Fx().apply { multMod = j.mult }                       // accumulated +Mult
            // poor_joker: j.mult += mult_mod(4) each time this joker pays rent (rental context, non-scoring)
            // foodm: j.mult=40 by default (decreases per round, self-destructs; replenished by selling jolly jokers)
            "j_obelisk", "j_hologram", "j_ramen", "j_campfire", "j_loyalty_card", "j_throwback", "j_cry_krustytheclown", "j_cry_eternalflame", "j_cry_whip",
            "j_cry_dropshot", "j_cry_chili_pepper", "j_cry_mondrian", "j_cry_fading_joker", "j_cry_keychange",
            "j_cry_verisimile", "j_cry_duplicare", "j_cry_clockwork" ->
                if (j.x > 1.0) return Fx().apply { xMultMod = j.x }                            // accumulated Xmult
            // clockwork: j.x += xmult_mod(0.25) every 3rd hand (before, non-scoring); joker_main reads j.x
            // dropshot:    j.x += Xmult_mod(0.2) * non-scoring-hand cards of random suit each hand (before, non-scoring)
            // chili_pepper: j.x += Xmult_mod(0.5) each end_of_round (non-scoring); self-destructs after rounds_remaining hits 0
            // mondrian:    j.x += extra(0.25) each end_of_round where discard was not used (non-scoring)
            // fading_joker: j.x += xmult_mod(1) when this perishable joker expires (perishable_debuffed, non-scoring)
            // keychange:   j.x += xmgain(0.25) each time a hand type is played for the first time this round (before, non-scoring); resets end_of_round
            // verisimile:  j.x += denominator each pseudorandom_result hit; joker_main reads j.x
            // duplicare:   j.x += Xmult_mod(1) per post_trigger / individual card played (non-scoring); joker_main reads j.x
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
            // formidiulosus: Emult = 1 + 0.01*candy_count (update() hook, stored in j.x); joker_main reads j.x
            // starfruit: Emult = j.x (starts at 2.0, decreases by 0.2 per reroll, self-destructs at <=1)
            "j_cry_stella_mortis", "j_cry_formidiulosus", "j_cry_starfruit" -> if (j.x > 1.0) return Fx().apply { eMult = j.x }
            // primus: Emult = j.x (base 1.01, +0.17 in the before-pass when the whole hand is prime); mult^x.
            // Lives here (not the loop) so the copy-jokers can copy it like any other joker_main effect.
            "j_cry_primus" -> if (j.x > 1.0) return Fx().apply { eMult = j.x }
            // happyhouse: Emult=4 after 114 hands played (joker_main fires only when j.n > 0 = check exceeded trigger)
            "j_cry_happyhouse" -> if (j.n > 0) return Fx().apply { eMult = 4.0 }
            // circulus_pistoris: fires exactly when hands_left == 3 (Lua: >=hands_remaining && <hands_remaining+1, hands_remaining=3)
            "j_cry_circulus_pistoris" -> if (ctx.handsLeft == 3) return Fx().apply { xChipMod = PI; eMult = PI }
            // facile: Emult=3 (fixed) if scored-card count this hand <=10; counter tracked externally (j.n);
            //         nearly always fires (<= 5 cards in standard play; retrigger edge cases not modelled)
            "j_cry_facile" -> return Fx().apply { eMult = 3.0 }
            // exponentia: scales Emult (j.x, base 1.0) +Emult_mod(0.03) each time any xmult effect fires during scoring;
            //             joker_main reads j.x and applies mult^j.x when above 1 (no-op while x==1.0 / never scaled)
            "j_cry_exponentia" -> if (j.x > 1.0) return Fx().apply { eMult = j.x }
            // jtron: Emult = 1 + (# of base "j_joker" Jokers on the board); no-op when none present
            "j_cry_jtron" -> { val n = ctx.boardKeys.count { it == "j_joker" }; if (n > 0) return Fx().apply { eMult = 1.0 + n } }
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
            // pirate_dagger: j.xc += 0.25 * sell_cost of joker to the right (which is destroyed) at setting_blind
            "j_cry_antennastoheaven", "j_cry_spaceglobe", "j_cry_pirate_dagger" ->
                if (j.xc > 1.0) return Fx().apply { xChipMod = j.xc }  // accumulated Xchips
            // supercell: +15 Chips, X2 Chips, +15 Mult, X2 Mult (config.extra.stat1=15, stat2=2; non-modest path)
            "j_cry_supercell" -> return Fx().apply { chipMod = 15.0; xChipMod = 2.0; multMod = 15.0; xMultMod = 2.0 }
            // m: X(x_mult) Mult; x_mult starts at 1, gains +13 each time a Jolly Joker is sold (selling_card, non-scoring)
            "j_cry_m" -> if (j.x > 1.0) return Fx().apply { xMultMod = j.x }
            // longboi: Xmult = j.x (= G.GAME.monstermult at equip time, starts 1, grows end_of_round); same guard
            "j_cry_longboi" -> if (j.x > 1.0) return Fx().apply { xMultMod = j.x }
            // biggestm: X(j.x) Mult when j.n > 0 (j.n=1 when "before" check fired this hand, 0 otherwise)
            "j_cry_biggestm" -> if (j.n > 0) return Fx().apply { xMultMod = j.x }
            // kittyprinter: flat X2 Xmult every hand (config.extra.Xmult=2)
            "j_cry_kittyprinter" -> return Fx().apply { xMultMod = 2.0 }
            // spy: flat X0.5 Xmult every hand (x_mult=0.5); effectively halves mult
            "j_cry_spy" -> return Fx().apply { xMultMod = 0.5 }
            // apjoker: X4 Xmult when the current blind is a boss blind (G.GAME.blind.boss)
            "j_cry_apjoker" -> if (ctx.bossBlind) return Fx().apply { xMultMod = 4.0 }
            // clicked_cookie: +chips from j.chips accumulator (starts 200, decrements 1 per cry_press click)
            "j_cry_clicked_cookie" -> return Fx().apply { chipMod = j.chips }
            // monkey_dagger: +chips from j.chips accumulator (+10*sell_cost of left joker at setting_blind, that joker destroyed)
            "j_cry_monkey_dagger" -> if (j.chips != 0.0) return Fx().apply { chipMod = j.chips }
            // unjust_dagger: Xmult from j.x accumulator (+0.2*sell_cost of left joker at setting_blind, that joker destroyed)
            // jimball: Xmult from j.x accumulator (+0.15 per context.before when this hand type is least-played)
            // pizza_slice: Xmult from j.x accumulator (+0.5 per other pizza_slice sold)
            // wheelhope: Xmult from j.x accumulator (+0.5 per Wheel of Fortune pseudorandom_result trigger)
            // cut: Xmult from j.x accumulator (+0.5 per Code consumable destroyed when leaving shop)
            // python: Xmult from j.x accumulator (+0.15 per Code consumable used)
            "j_cry_unjust_dagger", "j_cry_jimball", "j_cry_pizza_slice", "j_cry_wheelhope",
            "j_cry_cut", "j_cry_python" ->
                if (j.x > 1.0) return Fx().apply { xMultMod = j.x }
            // fspinner: +chips from j.chips accumulator (+6 per context.before when another hand type has been played as many times)
            "j_cry_fspinner" -> if (j.chips != 0.0) return Fx().apply { chipMod = j.chips }
            // --- Cryptid custom hand-type jokers (fire only when Cryptid-extended hand detection is active) ---
            // These branches are dormant until CRY_* hand types are returned by hand evaluation;
            // listed here so the dispatch is complete when those hand types are ported.
            "j_cry_stronghold"       -> if (ctx.scoringName == HandType.CRY_BULWARK)     return Fx().apply { xMultMod = 5.0 }
            "j_cry_wtf"              -> if (ctx.scoringName == HandType.CRY_CLUSTERFUCK) return Fx().apply { xMultMod = 10.0 }
            "j_cry_clash"            -> if (ctx.scoringName == HandType.CRY_ULTPAIR)     return Fx().apply { xMultMod = 12.0 }
            "j_cry_the"              -> if (ctx.scoringName == HandType.CRY_NONE)        return Fx().apply { xMultMod = 2.0 }
            "j_cry_annihalation"     -> if (ctx.scoringName == HandType.CRY_WHOLEDECK)   return Fx().apply { xMultMod = 5.2 }   // approx: Lua uses Emult=5.2 not Xmult
            "j_cry_words_cant_even"  -> if (ctx.scoringName == HandType.CRY_WHOLEDECK)   return Fx().apply { xMultMod = 52000000.0 }
            "j_cry_bonkers"          -> if (ctx.scoringName == HandType.CRY_BULWARK)     return Fx().apply { multMod = 20.0 }
            "j_cry_fuckedup"         -> if (ctx.scoringName == HandType.CRY_CLUSTERFUCK) return Fx().apply { multMod = 37.0 }
            "j_cry_foolhardy"        -> if (ctx.scoringName == HandType.CRY_ULTPAIR)     return Fx().apply { multMod = 42.0 }
            "j_cry_undefined"        -> if (ctx.scoringName == HandType.CRY_NONE)        return Fx().apply { multMod = 5.0 }
            "j_cry_adroit"           -> if (ctx.scoringName == HandType.CRY_BULWARK)     return Fx().apply { chipMod = 170.0 }
            "j_cry_penetrating"      -> if (ctx.scoringName == HandType.CRY_CLUSTERFUCK) return Fx().apply { chipMod = 270.0 }
            "j_cry_treacherous"      -> if (ctx.scoringName == HandType.CRY_ULTPAIR)     return Fx().apply { chipMod = 300.0 }
            "j_cry_nebulous"         -> if (ctx.scoringName == HandType.CRY_NONE)        return Fx().apply { chipMod = 30.0 }
            "j_cry_many_lost_minds"  -> if (ctx.scoringName == HandType.CRY_WHOLEDECK)   return Fx().apply { chipMod = 8.0658175e67 }
            // thalia: Xmult = C(n,2) * xmgain (xmgain=1) where n = count of DISTINCT rarities among all board jokers
            // (including Thalia itself, rarity=4 Legendary). ctx.board now carries FJoker.rarity so this is faithful.
            // n=1→bonus=0 (no-op); n=2→bonus=1 (X1, identity); n=3→bonus=3 (X3); n=4→bonus=6 (X6); n=5→bonus=10 (X10).
            "j_cry_thalia" -> {
                val n = ctx.board.map { it.rarity }.filter { it > 0 }.toSet().size
                val bonus = n * (n - 1) / 2
                if (bonus >= 1) return Fx().apply { xMultMod = bonus.toDouble() }
            }
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
            // circus: Xmult based on other joker's rarity (Rare=3→X2, cry_epic=5→X3, Legendary=4→X4, cry_exotic=6→X20).
            // Base values from config.extra circus_rarities (scalable at runtime; engine uses base config).
            // Rarity int convention: 1=Common,2=Uncommon,3=Rare,4=Legendary,5=cry_epic(Epic),6=cry_exotic(Exotic).
            "j_cry_circus" -> {
                if (oj !== j) {
                    val xm = when (oj.rarity) { 3 -> 2.0; 5 -> 3.0; 4 -> 4.0; 6 -> 20.0; else -> 1.0 }
                    if (xm > 1.0) return Fx().apply { xMultMod = xm }
                }
            }
            "j_cry_waluigi"  -> return Fx().apply { xMultMod = 2.5 }                                  // X2.5 once per board joker (incl self)
            // --- Cryptid edition reactors (the card-edition branch is unreachable: cards carry no edition here) ---
            "j_cry_meteor"    -> if (oj !== j && oj.edition == "Foil") return Fx().apply { chipMod = 75.0 }   // +75 Chips / other Foil joker
            "j_cry_exoplanet" -> if (oj !== j && oj.edition == "Holo") return Fx().apply { multMod = 15.0 }   // +15 Mult / other Holo joker
            "j_cry_stardust"  -> if (oj !== j && oj.edition == "Poly") return Fx().apply { xMultMod = 2.0 }   // X2 Mult / other Poly joker
            "j_cry_universe"  -> if (oj !== j && oj.edition == "Astral") return Fx().apply { eMult = 1.2 }    // Emult^1.2 per other Astral-edition joker
        }
        return null
    }

    /** evaluate_play (state_events.lua:571) — the cascade. `trace` (when non-null) records the
     *  running chips/mult after the base and after each scoring card + the joker passes, so the UI
     *  can animate the build-up. trace=null is the oracle/hot path (no overhead). */
    fun score(
        played: List<PlayingCard>, jokers: List<FJoker>, held: List<PlayingCard> = emptyList(),
        level: Int = 1, debuff: Debuff = Debuff.None, handsLeft: Int = -1, discardsLeft: Int = -1,
        bossBlind: Boolean = false,
        trace: MutableList<ScoreStep>? = null,
    ): ScoreResult {
        // j_cry_maximized patches get_id: pips collide at 10, faces at 13 (so disparate faces pair).
        val rankOf: (PlayingCard) -> Int =
            if (jokers.any { it.key == "j_cry_maximized" }) { c -> c.id.let { if (it in 2..10) 10 else if (it in 11..13) 13 else it } }
            else { c -> c.id }
        // Hand-detection hooks (each a board joker): Four Fingers → flush/straight need 4 not 5;
        // Shortcut → straights may skip one rank; Smeared → red/black suits collide in is_suit.
        val fourFingers = jokers.any { it.key == "j_four_fingers" }
        val shortcut = jokers.any { it.key == "j_shortcut" }
        val smeared = jokers.any { it.key == "j_smeared" }
        val (handType, handCards, pokerHands) = Hands.evaluate(played, rankOf, fourFingers, shortcut, smeared)
        // final_scoring_hand (state_events.lua:743): a played card scores if it's in the evaluated hand,
        // always-scores (stone), or Splash is on the board — j_splash makes EVERY played card score.
        val splash = jokers.any { it.key == "j_splash" }
        val pareidolia = jokers.any { it.key == "j_pareidolia" }   // every card is a face (Card:is_face patch)
        val scoringHand = played.filter { splash || it in handCards || it.enhancement == Enhancement.STONE }

        // hand base, raised by planet level (lvl 1 = unchanged), then halved by Flint (base only).
        var chips = (handType.baseChips + (level - 1) * handType.lChips).toDouble()
        var mult = (handType.baseMult + (level - 1) * handType.lMult).toDouble()
        if (debuff is Debuff.Flint) { chips = floor(chips / 2); mult = floor(mult / 2) }
        val ctx = Sctx().apply {
            fullHand = played; this.scoringHand = scoringHand; scoringName = handType; this.pokerHands = pokerHands
            this.handsLeft = handsLeft; this.discardsLeft = discardsLeft; this.bossBlind = bossBlind
            this.boardKeys = jokers.map { it.key }; this.smeared = smeared; this.pareidolia = pareidolia
            this.debuffSuit = (debuff as? Debuff.DebuffSuit)?.suit; this.board = jokers
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

        // held-in-hand pass: the card's own held effect (steel x1.5) + each joker reacting to held cards.
        // Mime (j_mime) retriggers each held card once IF it produced any effect (card_effects non-empty).
        val mime = jokers.any { it.key == "j_mime" }
        ctx.heldHand = held
        for (card in held) {
            ctx.cardarea = "hand"; ctx.held = true; ctx.otherCard = card
            val effects = ArrayList<Fx>()
            effects.add(evalCard(card, ctx))
            for (j in jokers) calcJoker(j, ctx)?.let { effects.add(it) }
            val heldReps = 1 + if (mime && effects.any { !it.empty }) 1 else 0
            repeat(heldReps) {
                for (fx in effects) { if (fx.xMult != 0.0) mult *= fx.xMult; if (fx.mult != 0.0) mult += fx.mult; if (fx.hMult != 0.0) mult += fx.hMult }
            }
            ctx.held = false
        }

        // JOKER MAIN pass: each joker's main effect, then its edition (foil/holo/poly), then a
        // joker-retrigger sub-loop (context.retrigger_joker_check, utils.lua:1602) — board order.
        // calcJoker self-resolves copy-jokers and primus. The retrigger sub-loop mirrors the per-card
        // reps loop: every board joker votes on retrigger count, then the main effect fires again.
        // Non-recursive (jokerRetriggerCheck=true suppresses further retrigger collection).
        fun applyJokerFx(fx: Fx) {
            if (fx.chipMod != 0.0) chips += fx.chipMod
            if (fx.xChipMod != 1.0) chips *= fx.xChipMod
            if (fx.multMod != 0.0) mult += fx.multMod
            if (fx.xMultMod != 1.0) mult *= fx.xMultMod
            if (fx.eMult != 1.0) mult = mult.pow(fx.eMult)
        }
        for (j in jokers) {
            ctx.cardarea = "jokers"; ctx.jokerMain = true; ctx.individual = false; ctx.otherCard = null
            calcJoker(j, ctx)?.let { applyJokerFx(it) }
            when (j.edition) { "Foil" -> chips += 50.0; "Holo" -> mult += 10.0; "Poly" -> mult *= 1.5 }
            // JOKER-RETRIGGER sub-loop (context.retrigger_joker_check, utils.lua:1602):
            // ask every board joker whether to retrigger j (once, non-recursive per Lua guard).
            ctx.jokerRetriggerCheck = true; ctx.retriggeredJoker = j
            var jokerReps = 0
            for (retriggerVoter in jokers) jokerReps += calcJoker(retriggerVoter, ctx)?.repetitions ?: 0
            ctx.jokerRetriggerCheck = false; ctx.retriggeredJoker = null
            ctx.jokerMain = true  // restore for re-fires
            repeat(jokerReps) { calcJoker(j, ctx)?.let { applyJokerFx(it) } }
            ctx.jokerMain = false
        }

        // OTHER_JOKER pass: every board joker offered to each joker once (joker-on-joker), board order
        for (other in jokers) {
            ctx.otherJoker = other; ctx.cardarea = "jokers"
            for (j in jokers) calcJoker(j, ctx)?.let { fx ->
                if (fx.multMod != 0.0) mult += fx.multMod
                if (fx.chipMod != 0.0) chips += fx.chipMod
                if (fx.xMultMod != 1.0) mult *= fx.xMultMod
                if (fx.eMult != 1.0) mult = mult.pow(fx.eMult)  // universe: Emult per Astral joker
            }
        }
        ctx.otherJoker = null

        if (jokers.isNotEmpty()) trace?.add(ScoreStep("jokers", chips, mult))
        return ScoreResult(handType, chips, mult, floor(chips * mult))
    }
}
