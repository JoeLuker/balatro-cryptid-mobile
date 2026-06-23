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
    var scoringPlays: Int = 0              // G.GAME.hands[scoringName].played — cumulative plays of this hand type INCLUDING the current hand (supernova: +mult)
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
    var debuffFace: Boolean = false           // THE_PLANT: all face cards (J/Q/K, incl. Pareidolia) score/trigger nothing
    var debuffCards: Set<PlayingCard>? = null  // THE_PILLAR: specific card instances debuffed (played earlier this Ante)
    var debuffAllCards: Boolean = false       // VERDANT_LEAF: all played cards score nothing; only jokers fire
    var debuffedJokerKey: String? = null      // CRIMSON_HEART: the currently-disabled joker key for this hand
    var board: List<FJoker> = emptyList()   // every joker in board order — Blueprint/Brainstorm resolve copy targets here
    var blueprintDepth = 0                  // copy-chain depth (context.blueprint); bounded by board size to stop cycles
    var jokerRetriggerCheck = false          // true during the retrigger sub-loop (mirrors context.retrigger_joker_check)
    var totalHandsPlayed: Int = 0            // G.GAME.hands_played (all hand types, cumulative) — loyalty_card jokerMain
    var handsPlayedAtCreate: Int = 0         // self.ability.hands_played_at_create — set per-joker in Score.kt calcJoker
    var retriggeredJoker: FJoker? = null     // the board joker currently being evaluated for retriggers (context.other_card)
    var selfJoker: FJoker? = null           // set by dispatchManifest to j before invoking any hook — enables identity guard (j !== rj / j !== oj)
    /** j_cry_maximized patches get_id: pips→10, faces→13. Used by every rank-literal comparison in
     *  calcJoker (individual/repetition/joker_main) to match Lua's context.other_card:get_id() calls. */
    var rankOf: (PlayingCard) -> Int = { it.id }
}

/** What eval_card / calculate_joker returns. INDIVIDUAL effects use chips/mult/x_mult; the
 *  joker_main pass uses chip_mod/mult_mod/Xmult_mod; the source distinguishes them by field name. */
class Fx {
    var chips = 0.0; var mult = 0.0; var xMult = 0.0; var hMult = 0.0
    var chipMod = 0.0; var multMod = 0.0; var xMultMod = 1.0; var xChipMod = 1.0   // Xchip_mod: Cryptid X-chips
    var eChipMod = 1.0                                                               // Echip_mod: Cryptid exponential chips (chips^eChipMod)
    var eMult = 1.0                                                                  // Emult_mod: Cryptid exponential mult (mult^eMult)
    var repetitions = 0
    var nullify = false                                                               // blacklist: sets chips=0 and mult=0 atomically (not expressible as additive/multiplicative)
    val empty get() = chips == 0.0 && mult == 0.0 && xMult == 0.0 && hMult == 0.0 &&
        chipMod == 0.0 && multMod == 0.0 && xMultMod == 1.0 && xChipMod == 1.0 &&
        eChipMod == 1.0 && eMult == 1.0 && repetitions == 0 && !nullify
}

object Score {
    /** Prime ranks for Cryptid's primus before-check (exotic.lua:606): 2, 3, 5, 7, Ace(14). */
    private val PRIMUS_PRIMES = setOf(2, 3, 5, 7, 14)

    /** Cryptid "M" pool (m.lua jokers with pools.M). j_cry_mprime counts an M-pool joker as Jolly-equivalent
     *  (m.lua:1538: is_jolly() OR pools.M) — NOT just j_jolly/jollysus/cry_m. (bonk uses is_jolly() only.) */
    internal val CRY_M_POOL = setOf(
        "j_cry_bubblem", "j_cry_foodm", "j_cry_mstack", "j_cry_mneon", "j_cry_notebook", "j_cry_bonk",
        "j_cry_loopy", "j_cry_scrabble", "j_cry_sacrifice", "j_cry_doodlem", "j_cry_virgo", "j_cry_smallestm",
        "j_cry_biggestm", "j_cry_macabre", "j_cry_megg", "j_cry_longboi",
    )

    // --- card scoring helpers (Card:get_chip_*), the played-card's own contribution -------------
    private fun chipBonus(c: PlayingCard): Double = when (c.enhancement) {   // get_chip_bonus
        Enhancement.STONE -> 50.0
        Enhancement.BONUS -> c.chips + 30.0
        else -> c.chips.toDouble()
    }
    private fun chipMult(c: PlayingCard): Double = if (c.enhancement == Enhancement.MULT) 4.0 else 0.0
    private fun chipXMult(c: PlayingCard): Double = if (c.enhancement == Enhancement.GLASS) 2.0 else 0.0
    private fun chipHXMult(c: PlayingCard): Double = if (c.enhancement == Enhancement.STEEL) 1.5 else 0.0
    // Abstract: ^Emult when played. Emult=1.15 confirmed from SpectralPack/Cryptid items/misc.lua
    // (config.extra.Emult = 1.15). Applied as mult = mult^1.15 each time an Abstract card is scored.
    private fun chipEMult(c: PlayingCard): Double = if (c.enhancement == Enhancement.ABSTRACT) 1.15 else 1.0

    /** eval_card (common_events.lua:580): a card's own scoring for its cardarea. */
    private fun evalCard(c: PlayingCard, ctx: Sctx): Fx {
        val r = Fx()
        when (ctx.cardarea) {
            "play" -> {
                r.chips = chipBonus(c); r.mult = chipMult(c); r.xMult = chipXMult(c); r.eMult = chipEMult(c)
                // A scored card applies its OWN edition (card.lua:1359-1366; e_foil=+50 Chips, e_holo=+10
                // Mult, e_polychrome=X1.5) — separate from joker editions and the Cryptid edition-reactor
                // jokers (meteor/exoplanet/stardust). Was previously unapplied, so foil/holo/poly cards
                // scored as plain. (Cryptid Astral's intrinsic e_mult on cards is still unmodelled.)
                when (c.edition) {
                    "Foil" -> r.chips += 50.0
                    "Holo" -> r.mult += 10.0
                    "Poly" -> r.xMult = (if (r.xMult == 0.0) 1.0 else r.xMult) * 1.5
                }
            }
            "hand" -> { r.xMult = chipHXMult(c) }
        }
        return r
    }

    /** Card:calculate_joker — every joker's effect, dispatched by key + context (1:1 with the Lua). */
    private fun calcJoker(j: FJoker, ctx: Sctx): Fx? {
        // CRIMSON_HEART: the chosen joker is disabled for this hand — acts as if it isn't there.
        if (ctx.debuffedJokerKey != null && j.key == ctx.debuffedJokerKey) return null
        // Copy-jokers (SMODS.blueprint_effect, utils.lua:2089) delegate to a target joker's calculate in EVERY
        // context: Brainstorm copies the leftmost joker; Blueprint / Old Blueprint copy the joker to their right.
        // Skip a missing/self target; the copy-chain depth is bounded by board size (stops Brainstorm⇄Blueprint cycles).
        when (j.key) {
            "j_blueprint", "j_cry_oldblueprint", "j_brainstorm" -> {
                val target = if (j.key == "j_brainstorm") ctx.board.firstOrNull()
                    else ctx.board.indexOfFirst { it === j }.let { i -> if (i < 0) null else ctx.board.getOrNull(i + 1) }
                if (target == null || target === j || ctx.blueprintDepth >= ctx.board.size) return null
                ctx.blueprintDepth++
                val ret = calcJoker(target, ctx)
                ctx.blueprintDepth--
                return ret
                // NOTE: inline-intercept jokers (broken_sync_catalyst, sync_catalyst) are NOT copyable
                // via Blueprint/Brainstorm — their effects live in the joker_main loop body, not in calcJoker.
            }
        }
        // MANIFEST: a migrated joker dispatches through its JokerSpec for the current context (kills the
        // scatter). Un-migrated keys fall through to the legacy when-blocks below.
        JOKER_MANIFEST[j.key]?.let { return dispatchManifest(it, j, ctx) }
        // RETRIGGER_JOKER_CHECK: each board joker votes whether to retrigger ctx.retriggeredJoker
        // (SMODS.calculate_retriggers, utils.lua:1602). Mirrors the per-card repetition guard.
        // The check fires ONLY in this sub-loop (jokerRetriggerCheck=true); not re-nested (like Lua's guard).
        if (ctx.jokerRetriggerCheck) {
            val rj = ctx.retriggeredJoker ?: return null
            when (j.key) {
                // chad: retrigger the LEFTMOST board joker j.n times (config.extra.retriggers=2).
                // Lua: context.other_card ~= self — chad does NOT retrigger itself (j !== rj guard).
                // (j_cry_chad migrated to JOKER_MANIFEST — batch 11a.)
                // loopy: retrigger all OTHER board jokers min(j.n, 40) times (j.n = Jolly Jokers sold; default 0).
                // (j_cry_loopy migrated to JOKER_MANIFEST — batch 11a.)
                // spectrogram: retrigger the RIGHTMOST board joker j.n times (j.n = Echo-enhanced cards scored;
                //   accumulated during the per-card pass when m_cry_echo enhancement is present — no-op until
                //   m_cry_echo is modelled in the Enhancement enum).
                // Lua guard: context.other_card ~= self — spectrogram must NOT retrigger itself.
                // (j_cry_spectrogram migrated to JOKER_MANIFEST — batch 11a.)
                // flip_side: retrigger any joker with the double-sided edition once.
                // (j_cry_flip_side migrated to JOKER_MANIFEST.)
                // boredom: 1-in-odds (default 2) pseudorandom retrigger of any other joker (epic.lua:868).
                // Pseudorandom — pseudoseed "cry_boredom_joker" is fixed per game state, so the run loop
                // pre-resolves: j.n=1 if roll succeeded (retrigger), j.n=0 if failed. Fires for any oj.
                // (j_cry_boredom migrated to JOKER_MANIFEST — batch 11a.)
            }
            return null
        }
        val oc = ctx.otherCard
        // INDIVIDUAL: a joker reacting to each scored card (context.individual, cardarea == G.play)
        if (ctx.individual && ctx.cardarea == "play" && oc != null) when (j.key) {
            // (greedy/lusty/wrathful/gluttonous + even_steven/odd_todd/scholar migrated to JOKER_MANIFEST.)
            // --- vanilla individual jokers, faithful from calculate_joker (port-vanilla-jokers workflow) ---
            // (j_arrowhead migrated to JOKER_MANIFEST.)
            // (j_onyx_agate migrated to JOKER_MANIFEST.)
            // (j_fibonacci migrated to JOKER_MANIFEST.)
            // (j_scary_face migrated to JOKER_MANIFEST.)
            // (j_smiley migrated to JOKER_MANIFEST.)
            // (j_triboulet migrated to JOKER_MANIFEST.)
            // (j_walkie_talkie migrated to JOKER_MANIFEST.)
            // X2 on the FIRST face. is_face returns nil for debuffed cards (suit-debuff or face-debuff)
            // before the Pareidolia check, so debuffed cards never count as the first face.
            // Exclude both suit-debuffed AND face-debuffed cards from the oc test and firstOrNull scan.
            // (j_photograph migrated to JOKER_MANIFEST.)
            // --- Cryptid individual ---
            // (j_cry_iterum migrated to JOKER_MANIFEST.)
            // (j_cry_lightupthenight migrated to JOKER_MANIFEST.)
            // (j_cry_krustytheclown migrated to JOKER_MANIFEST — batch 12 perCard hook.)
            // (j_cry_wee_fib migrated to JOKER_MANIFEST — batch 12 perCard hook.)
            // (j_cry_antennastoheaven migrated to JOKER_MANIFEST — batch 12 perCard hook.)
            // caramel: X1.75 Mult per scored played card (j.x=1.75 default; decreases per round, self-destructs)
            // (j_cry_caramel migrated to JOKER_MANIFEST.)
            // spectrogram accumulator: j.n counts Echo-enhanced scored cards this hand.
            // Migrated to manifest perCard hook (batch 12); accumulation now in dispatchManifest's perCard path.
            // Legacy entry removed; Score.kt preamble still resets j.n = 0 before each hand.
            // facile: count every scored-card pass (including retrigger repetitions) in j.n (= check2).
            // joker_main fires Emult=3 only when j.n <= 10 (exotic.lua:1002-1013), then resets to 0.
            // (j_cry_facile migrated to JOKER_MANIFEST — batch 12 perCard hook.)
            // Edition reactors: fire per scored playing card carrying that edition (misc_joker.lua:3635,3726,3817).
            // Separate from the other_joker path which fires per Foil/Holo/Poly joker on the board.
            // meteor held-chips are dead in Lua too (dev comment: "this doesn't exist yet"); held omitted.
            // (j_cry_meteor/exoplanet/stardust/universe migrated to JOKER_MANIFEST — batch 5c edition reactors.)
            "j_cry_meteor"            -> if (oc.edition == "Foil") return Fx().apply { chips = 75.0 }
            "j_cry_exoplanet"         -> if (oc.edition == "Holo") return Fx().apply { mult = 15.0 }
            "j_cry_stardust"          -> if (oc.edition == "Poly") return Fx().apply { xMult = 2.0 }
            // universe: Emult^1.2 per scored Astral-edition playing card (misc_joker.lua:8281-8288).
            // Also fires per Astral-edition joker (other_joker pass) and per Astral-edition held card (held pass).
            "j_cry_universe"          -> if (oc.edition == "Astral") return Fx().apply { eMult = 1.2 }
        }
        // REPETITION: jokers that retrigger a scored card (context.repetition)
        if (ctx.repetition && oc != null) when (j.key) {
            // (j_cry_iterum migrated to JOKER_MANIFEST.)
            // (j_cry_weegaming migrated to JOKER_MANIFEST.)
            // (j_cry_nosound migrated to JOKER_MANIFEST.)
            // (j_cry_exposed migrated to JOKER_MANIFEST.)
            // (j_cry_mask migrated to JOKER_MANIFEST.)
            // (j_cry_mstack migrated to JOKER_MANIFEST.)
            // vanilla retrigger jokers (card.lua:3895): Sock and Buskin retriggers each face once;
            // Hanging Chad retriggers the FIRST scored card twice (context.other_card == scoring_hand[1]);
            // Dusk retriggers every played card on the last hand (hands_left == 0);
            // Hack retriggers 2/3/4/5 once each.
            // (j_sock_and_buskin migrated to JOKER_MANIFEST.)
            // (j_hanging_chad migrated to JOKER_MANIFEST.)
            // (j_dusk migrated to JOKER_MANIFEST.)
            // (j_hack migrated to JOKER_MANIFEST.)
            // (j_cry_sock_and_sock migrated to JOKER_MANIFEST.)
            // sock_and_sock: retrigger each played Abstract card once (config.extra.retriggers=1; max 40).
            "j_cry_sock_and_sock" -> if (oc.enhancement == Enhancement.ABSTRACT) return Fx().apply { repetitions = 1 }
            // clockwork Effect 1 (epic.lua:2227): retrigger each Steel-enhanced held card once when c1==0.
            // j.n = c1 counter (cycles 0→1→0 per hand, limit=2). c1==0 every other hand starting from hand 1.
            // Fires in context.repetition + context.cardarea == G.hand (held-card retrigger path).
            "j_cry_clockwork" -> if (ctx.cardarea == "hand" && j.n == 0 && oc.enhancement == Enhancement.STEEL) return Fx().apply { repetitions = 1 }
        }
        // JOKER_MAIN: the joker's main flat/scaling effect (context.joker_main)
        if (ctx.jokerMain) when (j.key) {
            // (j_joker migrated to JOKER_MANIFEST.)
            // --- vanilla joker_main, self-contained (computed from the played/scoring hand) ---
            // (j_half migrated to JOKER_MANIFEST.)
            // (j_stuntman migrated to JOKER_MANIFEST.)
            // (j_seeing_double migrated to JOKER_MANIFEST.)
            // Flower Pot calls is_suit('<suit>', true) with bypass_debuff=true (card.lua:4358), so it COUNTS
            // debuffed cards' suits — the all-scoring-cards scan (incl. debuffed) is correct; do NOT exclude them.
            // (j_flower_pot migrated to JOKER_MANIFEST.)
            // --- vanilla "+chips if played hand contains <type>" family (game.lua j_sly..j_crafty;
            //     config {t_chips,type}). The generic branch (card.lua:4209) fires when the played cards
            //     CONTAIN the type via context.poker_hands — containment, not top rank (a Full House
            //     contains a Pair + Three of a Kind), exactly the Cryptid "type" jokers below. ---
            // (j_sly migrated to JOKER_MANIFEST.)
            // (vanilla "+Chips if hand contains <type>" family j_wily..j_crafty migrated to JOKER_MANIFEST.)
            // (vanilla "+Mult if hand contains <type>" family j_jolly..j_droll migrated to JOKER_MANIFEST.)
            // --- scaling / state joker_main (the run loop sets the accumulators; zero-defaults no-op) ---
            // (j_green_joker migrated to JOKER_MANIFEST.)
            // (j_spare_trousers migrated to JOKER_MANIFEST.)
            // (j_red_card/j_popcorn/j_cry_zooble/j_cry_poor_joker/j_cry_foodm migrated to JOKER_MANIFEST — batch 8d.)
            // j_swashbuckler stays legacy: seed is dynamic (swashSellSum) — no static initialState.
            // j_cry_wee_fib migrated to JOKER_MANIFEST (batch 12 perCard). Stays in this group because its
            //   j.mult accumulates during the perCard pass and the when-branch is only reached by the fallthrough
            //   of TRULY legacy keys (j.key not in JOKER_MANIFEST). Since wee_fib IS in manifest, the
            //   manifest early-return intercepts it — this entry is now dead for wee_fib. Remove wee_fib.
            "j_swashbuckler", "j_red_card", "j_popcorn",
            "j_cry_zooble", "j_cry_poor_joker", "j_cry_foodm" ->
                if (j.mult > 0.0) return Fx().apply { multMod = j.mult }                       // accumulated +Mult
            // j_popcorn: starts at +20 Mult (config.mult=20), −1 per hand (RunScreen before-pass); self-destructs at 0.
            //   RunScreen removes it before the next score() call, so score engine never sees mult<=0.
            // poor_joker: j.mult += mult_mod(4) each time this joker pays rent (rental context, non-scoring)
            // foodm: j.mult=40 by default (decreases per round, self-destructs; replenished by selling jolly jokers)
            // (j_ramen/j_campfire/j_obelisk/j_cry_paved_joker/j_cry_membershipcard/j_cry_dropshot/j_cry_chili_pepper/
            //  j_cry_mondrian/j_cry_fading_joker/j_cry_keychange/j_cry_verisimile/j_cry_duplicare/j_cry_clockwork migrated
            //  to JOKER_MANIFEST — batch 9a. Remaining: j_obelisk/j_loyalty_card/j_throwback/
            //  j_cry_whip stay in-group for legacy compat.)
            // j_cry_krustytheclown migrated to JOKER_MANIFEST (batch 12 perCard); removed from accumulator group.
            // j_cry_dropshot/mondrian/fading_joker/keychange/verisimile/duplicare/clockwork/paved_joker/membershipcard migrated to JOKER_MANIFEST (batch 9a).
            // j_ramen/j_campfire/j_cry_eternalflame migrated to JOKER_MANIFEST (Sold/Discarded-event accumulators).
            // j_cry_chili_pepper migrated to JOKER_MANIFEST (batch 9b: RoundEnd reducer; MANIFEST early-return at Score.kt:155).
            // j_hologram migrated to JOKER_MANIFEST (CardAdded-event reducer; RunScreen dispatches on Standard pack pick).
            // j_cry_mondrian migrated to JOKER_MANIFEST (batch 9b: RoundEnd(discardsUsed==0) reducer).
            // j_throwback migrated to JOKER_MANIFEST (BlindSkipped-event reducer; RunScreen.skipBlind() dispatches).
            // j_cry_whip migrated to JOKER_MANIFEST (BeforeHand reducer; Score.kt before-pass loop removed).
            // j_obelisk migrated to JOKER_MANIFEST (HandScored reducer; handPlays map passed from RunScreen).
            // j_loyalty_card migrated to JOKER_MANIFEST (jokerMain ctx-read; ctx.totalHandsPlayed + ctx.handsPlayedAtCreate).
            // j_cry_membershipcard / j_cry_verisimile / j_cry_duplicare / j_cry_clockwork / j_cry_keychange
            //   migrated to JOKER_MANIFEST (pure jokerMain readers or jokerMain+RoundEnd reset).
            // j_cry_fading_joker migrated to JOKER_MANIFEST (jokerMain unconditional; guard bug fixed).
            // j_cry_paved_joker: removed — Lua has no joker_main xmult path (probability-only joker).

            // j_cry_membershipcard migrated to JOKER_MANIFEST (pure jokerMain reader; x pre-set at init).
            // j_cry_verisimile migrated to JOKER_MANIFEST (pure jokerMain reader; pseudorandom accumulation in RunScreen).
            // j_cry_duplicare migrated to JOKER_MANIFEST (pure jokerMain reader; per-hand accumulation in RunScreen).
            // j_cry_keychange migrated to JOKER_MANIFEST (jokerMain + RoundEnd reset; per-hand accumulation in RunScreen).
            // j_cry_clockwork migrated to JOKER_MANIFEST (pure jokerMain reader; per-hand accumulation in RunScreen).
            // j_cry_fading_joker migrated to JOKER_MANIFEST (jokerMain unconditional; RunScreen perishable accumulation stays).
            //   Bug fix: Lua fires xmult unconditionally (no > 1 guard); the old Kotlin j.x > 1.0 gate was wrong.
            // j_cry_paved_joker: Lua has NO context.joker_main scoring path — paved_joker only does probability
            //   manipulation (stone cards fill straights/flushes) via Cryptid.get_paved_joker() in overrides.lua.
            //   The Kotlin j.x += 1.0 per perishable expiry and j.x > 1.0 → XMult was phantom scoring not in Lua.
            //   Removed from xmult when-arm; RunScreen perishable accumulation for paved_joker also removed.
            // j_cry_dropshot: NOT YET MIGRATED — requires cry_dropshot_card round-state (random suit chosen at
            //   round start, stored per-round). Accumulates x_mult += Xmult_mod(0.2) * count of non-scoring
            //   played cards of that suit (misc_joker.lua:57-89, context.before). Placeholder stays in-arm.
            "j_cry_dropshot" ->
                if (j.x > 1.0) return Fx().apply { xMultMod = j.x }                            // accumulated Xmult
            // dropshot:    j.x += Xmult_mod(0.2) * non-scoring-hand cards of random suit each hand (before, non-scoring)
            // pizza: has NO joker_main scoring path in Lua — only end_of_round countdown and selling_self pizza-slice
            //   spawn (misc_joker.lua:10139). j.x is never set for this key; removed from accumulator group.
            // alt_wheel_of_fortune: not a Joker object_type — only a UI tooltip key (set="Other") in wheelhope's
            //   loc_vars (misc_joker.lua:7325). Can never appear on the board; removed from accumulator group.
            // (j_square, j_runner migrated to JOKER_MANIFEST.)
            // (j_castle/j_cry_cursor/j_cry_crustulum migrated to JOKER_MANIFEST — j_wee remains legacy: unimplemented.)
            "j_castle", "j_wee", "j_cry_cursor", "j_cry_crustulum" ->
                if (j.chips != 0.0) return Fx().apply { chipMod = j.chips }                    // accumulated +Chips
            // (j_steel_joker migrated to JOKER_MANIFEST.)
            // (j_stone migrated to JOKER_MANIFEST.)
            // (j_blue_joker migrated to JOKER_MANIFEST.)
            // (j_banner migrated to JOKER_MANIFEST.)
            // (j_supernova migrated to JOKER_MANIFEST.)
            // (j_abstract migrated to JOKER_MANIFEST.)
            // (j_drivers_license migrated to JOKER_MANIFEST.)
            // (j_acrobat migrated to JOKER_MANIFEST.)
            // (j_mystic_summit migrated to JOKER_MANIFEST.)
            // (j_cry_night migrated to JOKER_MANIFEST.)
            // stella_mortis: Emult scales via ending_shop (destroy planet -> +0.4 per planet, stored in j.x); starts at 1
            // formidiulosus: Emult = 1 + 0.01*candy_count (update() hook, stored in j.x); joker_main reads j.x
            // starfruit: Emult = j.x (starts at 2.0, decreases by 0.2 per reroll, self-destructs at <=1)
            // (j_cry_stella_mortis/formidiulosus/starfruit migrated to JOKER_MANIFEST — batch 8b.)
            "j_cry_stella_mortis", "j_cry_formidiulosus", "j_cry_starfruit" -> if (j.x > 1.0) return Fx().apply { eMult = j.x }
            // primus: Emult = j.x (base 1.01, +0.17 in the before-pass when ANY played card is a prime rank); mult^x.
            // Lives here (not the loop) so the copy-jokers can copy it like any other joker_main effect.
            // (j_cry_primus migrated to JOKER_MANIFEST.)
            // happyhouse: Emult=4 after 114 hands played (joker_main fires only when j.n > 0 = check exceeded trigger)
            // (j_cry_happyhouse migrated to JOKER_MANIFEST.)
            // circulus_pistoris: fires exactly when hands_left == 3 (Lua: >=hands_remaining && <hands_remaining+1, hands_remaining=3)
            // exotic.lua:886: returns { echips = pi, emult = pi }. Talisman echips = exponentiation (chips^pi),
            // NOT multiplication. Use eChipMod (chips^PI) for chips and eMult (mult^PI) for mult.
            // (j_cry_circulus_pistoris migrated to JOKER_MANIFEST.)
            // facile: Emult=3 only when scored-card passes this hand <=10 (exotic.lua:1005-1013).
            // j.n is incremented once per individual pass (incl. retrigger reps) in the individual block above.
            // Reset j.n to 0 here regardless of whether Emult fires (mirrors check2=0 in the Lua).
            // (j_cry_facile jokerMain+reset migrated to JOKER_MANIFEST batch 12 — perCard accumulates j.n, reducer resets it via HandScored.)
            // exponentia: scales Emult (j.x, base 1.0) +Emult_mod(0.03) each time any xmult effect fires during scoring;
            //             joker_main reads j.x and applies mult^j.x when above 1 (no-op while x==1.0 / never scaled)
            // (j_cry_exponentia migrated to JOKER_MANIFEST.)
            // jtron: Emult = 1 + (# of base "j_joker" Jokers on the board); no-op when none present
            // (j_cry_jtron migrated to JOKER_MANIFEST.)
            // --- Cryptid joker_main ---
            // (j_cry_cube migrated to JOKER_MANIFEST.)
            // (j_cry_brokenhome migrated to JOKER_MANIFEST.)
            // (j_cry_triplet_rhythm migrated to JOKER_MANIFEST.)
            // --- Cryptid "type" jokers: fire when the played cards CONTAIN this hand (context.poker_hands), flat ---
            // (j_cry_giggly..j_cry_duos migrated to JOKER_MANIFEST — batch 4c hand-type flat jokers.)
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
            // (j_cry_filler migrated to JOKER_MANIFEST.)
            // (j_cry_nice migrated to JOKER_MANIFEST.)
            // (j_cry_big_cube migrated to JOKER_MANIFEST.)
            // antennastoheaven: j.xc += 0.1 per scored 4/7 (perCard accumulates; migrated batch 12)
            // spaceglobe: j.xc += Xchipmod(0.2) each hand type match (before, non-scoring); migrated batch 10c
            // pirate_dagger: j.xc += 0.25 * sell_cost of right joker at setting_blind; migrated batch 10c
            // (j_cry_antennastoheaven/j_cry_spaceglobe/j_cry_pirate_dagger migrated to JOKER_MANIFEST.)
            // supercell: +15 Chips, X2 Chips, +15 Mult, X2 Mult (config.extra.stat1=15, stat2=2; non-modest path)
            // (j_cry_supercell migrated to JOKER_MANIFEST.)
            // m: X(x_mult) Mult; x_mult starts at 1, gains +13 each time a Jolly Joker is sold (selling_card, non-scoring)
            // (j_cry_m migrated to JOKER_MANIFEST.)
            // longboi: Xmult = j.x (= G.GAME.monstermult at equip time, starts 1, grows end_of_round); same guard
            // (j_cry_longboi migrated to JOKER_MANIFEST.)
            // biggestm: X(j.x) Mult when j.n > 0 (j.n=1 when "before" check fired this hand, 0 otherwise)
            // (j_cry_biggestm migrated to JOKER_MANIFEST.)
            // kittyprinter: flat X2 Xmult every hand (config.extra.Xmult=2)
            // (j_cry_kittyprinter migrated to JOKER_MANIFEST.)
            // spy: Xmult = j.x each joker_main (spooky.lua:664). Run loop sets j.x = card.ability.x_mult
            // (default 0.5 from config). Oracle tests must always pass j.x explicitly.
            // (j_cry_spy migrated to JOKER_MANIFEST.)
            // apjoker: X4 Xmult when the current blind is a boss blind (G.GAME.blind.boss)
            // (j_cry_apjoker migrated to JOKER_MANIFEST.)
            // clicked_cookie: +chips from j.chips accumulator (starts 200, decrements 1 per cry_press click)
            // (j_cry_clicked_cookie migrated to JOKER_MANIFEST.)
            // monkey_dagger: +chips from j.chips accumulator (+10*sell_cost of left joker at setting_blind, that joker destroyed)
            // (j_cry_monkey_dagger migrated to JOKER_MANIFEST.)
            // unjust_dagger: Xmult from j.x accumulator (+0.2*sell_cost of left joker at setting_blind, that joker destroyed)
            // jimball: Xmult from j.x accumulator (+0.15 per hand while this hand type is the strict most-played; resets to x1 if tied/beaten)
            // pizza_slice: Xmult from j.x accumulator (+0.5 per other pizza_slice sold)
            // wheelhope: Xmult from j.x accumulator (+0.5 per Wheel of Fortune pseudorandom_result trigger)
            // cut: Xmult from j.x accumulator (+0.5 per Code consumable destroyed when leaving shop)
            // python: Xmult from j.x accumulator (+0.15 per Code consumable used)
            // (j_cry_unjust_dagger/jimball/pizza_slice/wheelhope/cut/python migrated to JOKER_MANIFEST — batch 8a.)
            "j_cry_unjust_dagger", "j_cry_jimball", "j_cry_pizza_slice", "j_cry_wheelhope",
            "j_cry_cut", "j_cry_python" ->
                if (j.x > 1.0) return Fx().apply { xMultMod = j.x }
            // fspinner: +chips from j.chips accumulator (+6 per context.before when another hand type has been played as many times)
            // (j_cry_fspinner migrated to JOKER_MANIFEST.)
            // membershipcardtwo: +chips = j.chips (pre-computed as chips * floor(member_count/chips_mod); epic.lua:112)
            // j.chips stores the full pre-computed chip bonus; fires when j.chips > 0.
            // (j_cry_membershipcardtwo migrated to JOKER_MANIFEST.)
            // --- Cryptid custom hand-type jokers ---
            // CRY_BULWARK, CRY_ULTPAIR, CRY_NONE are now live (Hands.evaluate returns them).
            // CRY_CLUSTERFUCK is now LIVE (Hands.evaluate detects it for ≥8 non-Gold no-pair/flush/straight cards).
            // CRY_WHOLEDECK remains DORMANT (requires scoring all 52 cards — not yet ported).
            // (j_cry_wtf..j_cry_many_lost_minds migrated to JOKER_MANIFEST — batch 7d custom hand-type group.)
            "j_cry_wtf"              -> if (ctx.scoringName == HandType.CRY_CLUSTERFUCK) return Fx().apply { xMultMod = 10.0 }
            "j_cry_clash"            -> if (ctx.scoringName == HandType.CRY_ULTPAIR)     return Fx().apply { xMultMod = 12.0 }
            "j_cry_the"              -> if (ctx.scoringName == HandType.CRY_NONE)        return Fx().apply { xMultMod = 2.0 }
            "j_cry_annihalation"     -> if (ctx.scoringName == HandType.CRY_WHOLEDECK)   return Fx().apply { eMult = 5.2 }   // Emult=5.2: mult^5.2 (misc_joker.lua:5853)
            "j_cry_words_cant_even"  -> if (ctx.scoringName == HandType.CRY_WHOLEDECK)   return Fx().apply { xMultMod = 52000000.0 }
            "j_cry_bonkers"          -> if (HandType.CRY_BULWARK in ctx.pokerHands)      return Fx().apply { multMod = 20.0 }
            "j_cry_fuckedup"         -> if (ctx.scoringName == HandType.CRY_CLUSTERFUCK) return Fx().apply { multMod = 37.0 }
            "j_cry_foolhardy"        -> if (ctx.scoringName == HandType.CRY_ULTPAIR)     return Fx().apply { multMod = 42.0 }
            "j_cry_undefined"        -> if (ctx.scoringName == HandType.CRY_NONE)        return Fx().apply { multMod = 5.0 }
            "j_cry_adroit"           -> if (HandType.CRY_BULWARK in ctx.pokerHands)      return Fx().apply { chipMod = 170.0 }
            "j_cry_penetrating"      -> if (ctx.scoringName == HandType.CRY_CLUSTERFUCK) return Fx().apply { chipMod = 270.0 }
            "j_cry_treacherous"      -> if (ctx.scoringName == HandType.CRY_ULTPAIR)     return Fx().apply { chipMod = 300.0 }
            "j_cry_nebulous"         -> if (ctx.scoringName == HandType.CRY_NONE)        return Fx().apply { chipMod = 30.0 }
            "j_cry_many_lost_minds"  -> if (ctx.scoringName == HandType.CRY_WHOLEDECK)   return Fx().apply { chipMod = 8.0658175e67 }
            // thalia: Xmult = C(n,2) * xmgain (xmgain=1) where n = count of DISTINCT rarities among all board jokers
            // (including Thalia itself, rarity=4 Legendary). ctx.board now carries FJoker.rarity so this is faithful.
            // n=1→bonus=0 (no-op); n=2→bonus=1 (X1, identity); n=3→bonus=3 (X3); n=4→bonus=6 (X6); n=5→bonus=10 (X10).
            // (j_cry_thalia migrated to JOKER_MANIFEST.)
            // blacklist: if the blacklisted rank (j.n, default 0→Ace=14) appears in the played or held hand,
            // zero both chips and mult (spooky.lua:1021-1038). Uses Fx.nullify since this is not expressible
            // as a standard additive/multiplicative modifier — must clobber both accumulators atomically.
            // j.n stores the blacklisted rank (0 = unset → treat as 14/Ace, matching config.extra.blacklist=14).
            "j_cry_blacklist" -> {
                val rank = if (j.n == 0) 14 else j.n
                val found = ctx.fullHand.any { it.id == rank } || ctx.heldHand.any { it.id == rank }
                if (found) return Fx().apply { nullify = true }
            }
            // googol_play: X1e100 Mult with 1-in-j.n odds (default j.n=8) (epic.lua:222-229).
            // Pseudorandom — the run loop sets j.x=1e100 when the roll succeeds, else j.x=1.0.
            // At score time, fire only when j.x > 1.0. Oracle tests must pre-set j.x=1e100 to exercise this path.
            // (j_cry_googol_play migrated to JOKER_MANIFEST.)
            // busdriver: +mult or -mult (default 50) each joker_main with 1-in-odds probability (misc_joker.lua:7653).
            // Pseudorandom — the run loop pre-resolves the roll: j.mult = +50 if success, -50 if fail (default).
            // At score time, j.mult != 0 fires; j.mult may be negative (debuff on failed roll).
            // (j_cry_busdriver migrated to JOKER_MANIFEST.)
        }
        // HELD-IN-HAND: jokers reacting to each card held (context.cardarea == G.hand)
        if (ctx.held && oc != null) when (j.key) {
            // (j_baron migrated to JOKER_MANIFEST.)
            // (j_shoot_the_moon migrated to JOKER_MANIFEST.)
            // (j_raised_fist migrated to JOKER_MANIFEST.)
            // (j_cry_exoplanet/stardust/universe held paths migrated to JOKER_MANIFEST.)
            // Edition reactors: fire per held card with that edition (misc_joker.lua:3735,3826).
            // Lua shows a "debuffed" message (no score) for debuffed held cards; the engine has no
            // held-card debuff tracking, so that edge case is not modelled — the fire condition is edition only.
            // meteor held-chips are dead in Lua ("this doesn't exist yet") — held omitted for meteor.
            "j_cry_exoplanet"  -> if (oc.edition == "Holo") return Fx().apply { hMult = 15.0 }
            "j_cry_stardust"   -> if (oc.edition == "Poly") return Fx().apply { xMult = 2.0 }
            // universe: Emult^1.2 per held Astral-edition card (misc_joker.lua:8290-8308).
            "j_cry_universe"   -> if (oc.edition == "Astral") return Fx().apply { eMult = 1.2 }
            // clockwork Effect 4 (epic.lua:2252-2268): extra Xmult per Steel-enhanced held card when steelenhc > 1.
            // j.xc = steelenhc (starts at 1.0, +0.1 every 7 hands via c4 counter, limit=7).
            // Fires context.individual + context.cardarea == G.hand + Steel enhancement + steelenhc != 1.
            "j_cry_clockwork"  -> if (oc.enhancement == Enhancement.STEEL && j.xc > 1.0) return Fx().apply { xMult = j.xc }
        }
        // OTHER_JOKER: a joker reacting to each board joker (context.other_joker)
        val oj = ctx.otherJoker
        if (oj != null) when (j.key) {
            // (j_baseball migrated to JOKER_MANIFEST.)
            // circus: Xmult based on other joker's rarity (Rare=3→X2, cry_epic=5→X3, Legendary=4→X4, cry_exotic=6→X20).
            // Base values from config.extra circus_rarities (scalable at runtime; engine uses base config).
            // Rarity int convention: 1=Common,2=Uncommon,3=Rare,4=Legendary,5=cry_epic(Epic),6=cry_exotic(Exotic).
            // (j_cry_circus migrated to JOKER_MANIFEST — batch 11b.)
            // (j_cry_waluigi migrated to JOKER_MANIFEST.)
            // (j_cry_meteor/exoplanet/stardust/universe other_joker paths migrated to JOKER_MANIFEST.)
            // --- Cryptid edition reactors (joker-on-joker path; card edition paths handled in individual/held blocks) ---
            "j_cry_meteor"    -> if (oj !== j && oj.edition == "Foil") return Fx().apply { chipMod = 75.0 }   // +75 Chips / other Foil joker
            "j_cry_exoplanet" -> if (oj !== j && oj.edition == "Holo") return Fx().apply { multMod = 15.0 }   // +15 Mult / other Holo joker
            "j_cry_stardust"  -> if (oj !== j && oj.edition == "Poly") return Fx().apply { xMultMod = 2.0 }   // X2 Mult / other Poly joker
            "j_cry_universe"  -> if (oj !== j && oj.edition == "Astral") return Fx().apply { eMult = 1.2 }    // Emult^1.2 per other Astral-edition joker
            // mprime: Emult^j.x (default 1.05) per Jolly-type or M-pool joker (m.lua:1534).
            // is_jolly() = key j_jolly or j_cry_jollysus, or edition e_cry_m.
            // M-pool jokers without those traits are unmodelled (FJoker has no pool field).
            // (j_cry_mprime migrated to JOKER_MANIFEST — batch 11b.)
            // (j_cry_bonk migrated to JOKER_MANIFEST — initial state + before-pass scaling + this other_joker hook.)
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
        debuffedJokerKey: String? = null,   // CRIMSON_HEART: key of the disabled joker for this hand
        handTypePlays: Map<HandType, Int> = emptyMap(),  // PRIOR run-total plays per hand type (NOT incl. this hand); supernova reads scoringName's count +1
        totalHandsPlayed: Int = 0,          // G.GAME.hands_played (all types, cumulative) — loyalty_card needs this in jokerMain
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
            this.scoringPlays = (handTypePlays[handType] ?: 0) + 1   // +1: this hand counts as a play (vanilla increments hand.played before the joker pass)
            this.handsLeft = handsLeft; this.discardsLeft = discardsLeft; this.bossBlind = bossBlind
            this.boardKeys = jokers.map { it.key }; this.smeared = smeared; this.pareidolia = pareidolia
            this.totalHandsPlayed = totalHandsPlayed
            this.debuffSuit = (debuff as? Debuff.DebuffSuit)?.suit
            this.debuffFace = debuff is Debuff.DebuffFace
            this.debuffCards = (debuff as? Debuff.DebuffCards)?.cards
            this.debuffAllCards = debuff is Debuff.DebuffAllCards
            this.debuffedJokerKey = debuffedJokerKey; this.board = jokers
            this.rankOf = rankOf
        }

        // BEFORE pass resets + per-hand scalars that must not carry over between hands.
        // j_cry_spectrogram: reset Echo-card count to 0 each hand (epic.lua:2047-2053 resets echonum=0
        //   in the before pass before counting scoring_hand; the engine accumulates in the per-card pass
        //   so the reset must happen here, before the individual pass runs).
        for (j in jokers) if (j.key == "j_cry_spectrogram") j.n = 0
        // j_cry_primus gains +0.17 Emult if ANY card in the played hand is a prime rank
        // (exotic.lua:603-619: loops full_hand, sets check=true on any 2/3/5/7/Ace, scales when check).
        // Uses get_id() in Lua — rankOf applies Maximized remapping so primes can never match when Maximized is on board.
        for (j in jokers) if (j.key == "j_cry_primus" && played.any { rankOf(it) in PRIMUS_PRIMES }) j.x += 0.17
        // j_cry_zooble: +1 Mult per DISTINCT rank in the scoring hand, unless the hand is a Straight (scaling).
        // Uses get_id() in Lua — rankOf applies Maximized remapping so distinct-rank count matches Lua.
        for (j in jokers) if (j.key == "j_cry_zooble" && HandType.STRAIGHT !in pokerHands && HandType.STRAIGHT_FLUSH !in pokerHands)
            j.mult += scoringHand.filter { it.enhancement != Enhancement.STONE }.map { rankOf(it) }.distinct().size.toDouble()
        // j_cry_biggestm: activate (j.n=1) when scoring_name matches type (default "Pair") (m.lua:1426-1437).
        // check persists until end_of_round reset; engine resets at new round via RunScreen.
        for (j in jokers) if (j.key == "j_cry_biggestm" && j.n == 0 && handType == HandType.PAIR) j.n = 1
        // MANIFEST before-pass: migrated jokers evolve their persistent state via their reducer (BeforeHand)
        // before the joker passes read it — e.g. j_cry_bonk scales its chip bonus on a Pair.
        for (j in jokers) JOKER_MANIFEST[j.key]?.reduce?.let { j.restore(it(j.snapshot(), GameEvent.BeforeHand(ctx))) }
        // j_cry_whip migrated to JOKER_MANIFEST (BeforeHand reducer). The MANIFEST loop above handles it.
        trace?.add(ScoreStep("base · ${handType.name.lowercase().replace('_', ' ')}", chips, mult))

        fun apply(fx: Fx) {                         // the effects[ii] application block (lines 702-777)
            if (fx.chips != 0.0) chips += fx.chips
            if (fx.mult != 0.0) mult += fx.mult
            if (fx.xMult != 0.0) {
                mult *= fx.xMult
                // exponentia: +Emult_mod(0.03) each time a non-trivial xmult fires during scored-card pass
                if (fx.xMult != 1.0) for (ej in jokers) if (ej.key == "j_cry_exponentia") ej.x += 0.03
            }
            // eMult from card enhancement (Abstract: mult^Emult during per-card pass)
            if (fx.eMult != 1.0) mult = mult.pow(fx.eMult)
        }

        // per scoring card: card's own scoring + each joker's individual reaction
        for (card in scoringHand) {
            if (debuff is Debuff.DebuffSuit && card.suit == debuff.suit) continue       // suit-debuffed
            if (debuff is Debuff.DebuffFace && (card.isFace || pareidolia)) continue      // THE_PLANT: face-debuffed (Pareidolia makes all cards faces)
            if (debuff is Debuff.DebuffCards && card in debuff.cards) continue                 // THE_PILLAR: previously played this Ante
            if (debuff is Debuff.DebuffAllCards) continue                                          // VERDANT_LEAF: all played cards are debuffed
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
        // Jokers may retrigger held cards via context.repetition + context.cardarea == G.hand
        // (clockwork Effect 1: retrigger Steel-enhanced held cards once when c1==0).
        // Mime (j_mime) retriggers each held card once IF it produced any effect (card_effects non-empty).
        val mime = jokers.any { it.key == "j_mime" }
        ctx.heldHand = held
        for (card in held) {
            ctx.cardarea = "hand"; ctx.held = true; ctx.otherCard = card
            // Collect held-card retrigger votes (context.repetition + cardarea=="hand").
            var heldJokerReps = 0
            ctx.repetition = true
            for (j in jokers) heldJokerReps += calcJoker(j, ctx)?.repetitions ?: 0
            ctx.repetition = false
            val effects = ArrayList<Fx>()
            effects.add(evalCard(card, ctx))
            for (j in jokers) calcJoker(j, ctx)?.let { effects.add(it) }
            // Held-card repetitions are ADDITIVE (SMODS): base 1 + joker retriggers + Red seal + Mime.
            // Red seal retriggers a HELD card too (card.lua:2810 `repetitions=1` fires in G.hand, not just
            // G.play) — this was previously missing. Mime adds 1 repetition, not a ×2 (card.lua:3487).
            val redSealRep = if (card.seal == Seal.RED) 1 else 0
            val mimeRep = if (mime && effects.any { !it.empty }) 1 else 0
            val heldReps = 1 + heldJokerReps + redSealRep + mimeRep
            repeat(heldReps) {
                for (fx in effects) {
                    if (fx.xMult != 0.0) {
                        mult *= fx.xMult
                        // exponentia: hook fires on ANY x_mult key with amount!=1 (exotic.lua:228-251),
                        // including held-card joker reactions (e.g. Baron's x_mult=1.5 per held King).
                        if (fx.xMult != 1.0) for (ej in jokers) if (ej.key == "j_cry_exponentia") ej.x += 0.03
                    }
                    if (fx.mult != 0.0) mult += fx.mult
                    if (fx.hMult != 0.0) mult += fx.hMult
                    if (fx.eMult != 1.0) mult = mult.pow(fx.eMult)  // universe: Emult per held Astral card
                }
            }
            ctx.held = false
        }

        // JOKER MAIN pass: each joker's main effect, then its edition (foil/holo/poly), then a
        // joker-retrigger sub-loop (context.retrigger_joker_check, utils.lua:1602) — board order.
        // calcJoker self-resolves copy-jokers and primus. The retrigger sub-loop mirrors the per-card
        // reps loop: every board joker votes on retrigger count, then the main effect fires again.
        // Non-recursive (jokerRetriggerCheck=true suppresses further retrigger collection).
        fun applyJokerFx(fx: Fx) {
            if (fx.nullify) { chips = 0.0; mult = 0.0 }   // blacklist: zero both accumulators (spooky.lua:1031-1032)
            if (fx.chipMod != 0.0) chips += fx.chipMod
            if (fx.xChipMod != 1.0) chips *= fx.xChipMod
            if (fx.eChipMod != 1.0) chips = chips.pow(fx.eChipMod)  // echips: exponentiation (chips^eChipMod)
            if (fx.multMod != 0.0) mult += fx.multMod
            if (fx.xMultMod != 1.0) {
                mult *= fx.xMultMod
                // exponentia: SMODS.calculate_individual_effect hook (exotic.lua:226) fires on every
                // x_mult/xmult/Xmult/x_mult_mod/xmult_mod/Xmult_mod key with amount != 1 — that
                // covers joker-main xMultMod returns as well as per-card individual xMult. Increment
                // here so joker xmults (Steel Joker, Ramen, Hologram, Cryptid xmult jokers, etc.)
                // all scale Exponentia, matching the Lua hook's scope.
                for (ej in jokers) if (ej.key == "j_cry_exponentia") ej.x += 0.03
            }
            if (fx.eMult != 1.0) mult = mult.pow(fx.eMult)
        }
        // JOKER MAIN + OTHER_JOKER pass (state_events.lua:847-928, board order):
        // For each board joker (_card): fire _card's joker_main and edition effects, then immediately
        // fire every board joker's other_joker reaction to _card — all applied before moving to the
        // next card. This interleaved order is faithful to the Lua, which collects joker_main +
        // all other_joker reactions into one effects table per _card, then calls trigger_effects
        // immediately. A separate post-pass would apply circus (and baseball/universe) xmults to
        // a larger accumulated mult, diverging from the Lua.
        fun applyOtherJokerFx(fx: Fx) {
            if (fx.multMod != 0.0) mult += fx.multMod
            if (fx.chipMod != 0.0) chips += fx.chipMod
            if (fx.xMultMod != 1.0) {
                mult *= fx.xMultMod
                // exponentia: SMODS.calculate_individual_effect fires for xmult keys in ALL scoring passes,
                // including other_joker (state_events.lua:879 → trigger_effects → calculate_individual_effect).
                // baseball, circus, waluigi, stardust all fire xMultMod here and must increment exponentia.
                for (ej in jokers) if (ej.key == "j_cry_exponentia") ej.x += 0.03
            }
            if (fx.eMult != 1.0) mult = mult.pow(fx.eMult)  // universe: Emult per Astral joker
        }
        for (j in jokers) {
            ctx.cardarea = "jokers"; ctx.jokerMain = true; ctx.individual = false; ctx.otherCard = null
            // pre_joker editions: Foil(+50 Chips) / Holo(+10 Mult) fire BEFORE the joker's own effect
            // (card.lua context.pre_joker) — so an xMult/xChip main multiplies the edition-boosted value
            // (e.g. Holo + an X2 joker is (mult+10)*2, not mult*2+10).
            when (j.edition) { "Foil" -> chips += 50.0; "Holo" -> mult += 10.0 }
            // broken_sync_catalyst: swap portion (10%) of chips into mult and vice versa (atomic).
            // cry_broken_swap=10 → portion=0.10. Not expressible as a standard Fx delta because it
            // reads and writes both accumulators simultaneously. Handled inline before calcJoker.
            // math: delta=(chips−mult)*0.10; chips−=delta, mult+=delta (pulls them toward each other by 10%).
            if (j.key == "j_cry_broken_sync_catalyst") {
                val delta = (chips - mult) * 0.10
                chips -= delta; mult += delta
            }
            // sync_catalyst: balances Chips and Mult (sets both to their average — "the non-broken variant").
            // Same inline-intercept pattern as broken_sync_catalyst (not expressible as a standard Fx delta).
            if (j.key == "j_cry_sync_catalyst") {
                val avg = (chips + mult) / 2.0
                chips = avg; mult = avg
            }
            calcJoker(j, ctx)?.let { applyJokerFx(it) }
            // post_joker edition: Poly X1.5 Mult applies AFTER the joker's main effect (card.lua
            // context.post_joker); it fires as an x_mult_mod, so it also feeds j_cry_exponentia.
            if (j.edition == "Poly") { mult *= 1.5; for (ej in jokers) if (ej.key == "j_cry_exponentia") ej.x += 0.03 }
            // Cryptid post_joker editions (multiplicative, like Poly): Astral raises Mult to ^1.1
            // (e_mult, misc.lua:1182); Mosaic multiplies Chips by 2.5 (x_chips, misc.lua:747).
            if (j.edition == "Astral") mult = mult.pow(1.1)
            if (j.edition == "cry_mosaic") chips *= 2.5
            // JOKER-RETRIGGER sub-loop (context.retrigger_joker_check, utils.lua:1602):
            // ask every board joker whether to retrigger j (once, non-recursive per Lua guard).
            ctx.jokerRetriggerCheck = true; ctx.retriggeredJoker = j
            var jokerReps = 0
            for (retriggerVoter in jokers) jokerReps += calcJoker(retriggerVoter, ctx)?.repetitions ?: 0
            ctx.jokerRetriggerCheck = false; ctx.retriggeredJoker = null
            ctx.jokerMain = true  // restore for re-fires
            repeat(jokerReps) { calcJoker(j, ctx)?.let { applyJokerFx(it) } }
            ctx.jokerMain = false

            // OTHER_JOKER reactions to j (inline, immediately after j's main turn).
            // In the Lua, these are collected in the same effects table as j's joker_main and
            // applied by the same trigger_effects call — so they see the mult AFTER j's main but
            // BEFORE the next joker's main (state_events.lua:871-918).
            ctx.otherJoker = j; ctx.cardarea = "jokers"
            for (voter in jokers) calcJoker(voter, ctx)?.let { applyOtherJokerFx(it) }
            ctx.otherJoker = null
        }

        if (jokers.isNotEmpty()) trace?.add(ScoreStep("jokers", chips, mult))
        return ScoreResult(handType, chips, mult, floor(chips * mult))
    }
}
