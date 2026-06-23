package systems.balatro.game

/**
 * Joker MANIFEST — kills the "scatter", typed the Jane Street way.
 *
 * A joker is ONE JokerSpec co-locating its entire behaviour. Previously a joker was smeared across up to
 * ~8 sites in two files (Score.kt's individual/joker_main/before/other_joker when-blocks + RunScreen's
 * buy-init/scoreBank/discard/end-of-round loops). One spec mirrors Lua's single
 * calculate(self, card, context) more faithfully than that split does.
 *
 * Principles:
 *  - Make illegal states unrepresentable. A scoring hook returns a value from the sealed [Effect] algebra
 *    — exactly "add chips / add mult / x mult / x chips / ^mult / ^chips / retrigger / nullify / a
 *    composition" — not a 12-field mutable Fx bag a joker can fill inconsistently. The single TOTAL
 *    boundary [Effect.intoFx] translates the algebra into the engine's Fx; the engine's mechanism is
 *    unchanged.
 *  - Total functions. Hooks return [Effect] ([Effect.None] for "no effect", never null); the reducer is
 *    total over [GameEvent]; [intoFx]/[dispatchManifest] are exhaustive `when`s (compiler-checked).
 *  - Mechanism vs policy. The engine is a thin interpreter (dispatch + reducer threading + intoFx); each
 *    spec is one joker's policy, in one place. Pure scoring hooks (state + context -> Effect) and a single
 *    pure reducer (state + event -> state); the mutable FJoker is bridged only by the engine's snapshot/
 *    restore — a spec never touches it.
 *
 * Migration is incremental: calcJoker / the run loop consult the manifest first and fall back to the legacy
 * when-blocks for un-migrated keys, so jokers move one batch at a time, oracle-guarded (272 cases).
 */

/** The per-instance SCALING state a joker accumulates. Identity (key / edition / rarity) stays on FJoker. */
data class FJokerState(
    val mult: Double = 0.0,
    val x: Double = 1.0,
    val chips: Double = 0.0,
    val n: Int = 0,
    val xc: Double = 1.0,
)

/** Engine bridge between the mutable FJoker and the immutable state a spec operates on. */
fun FJoker.snapshot(): FJokerState = FJokerState(mult, x, chips, n, xc)
fun FJoker.restore(s: FJokerState) { mult = s.mult; x = s.x; chips = s.chips; n = s.n; xc = s.xc }

/**
 * The algebra of a single scoring contribution. Closed (sealed) so dispatch is exhaustive and a joker can
 * only express a real effect — no contradictory or half-set state. Composition is [All].
 */
sealed interface Effect {
    object None : Effect
    data class Chips(val n: Double) : Effect      // + chips
    data class Mult(val n: Double) : Effect        // + mult
    data class XMult(val x: Double) : Effect        // x mult
    data class XChips(val x: Double) : Effect       // x chips (Cryptid)
    data class EMult(val e: Double) : Effect        // mult ^ e (Cryptid Emult)
    data class EChips(val e: Double) : Effect       // chips ^ e (Cryptid Echip)
    data class HeldMult(val x: Double) : Effect     // held-in-hand x mult (Steel)
    data class Retrigger(val n: Int) : Effect       // repetitions
    object Nullify : Effect                          // chips = mult = 0 atomically (blacklist)
    data class All(val of: List<Effect>) : Effect    // composition

    companion object {
        /** Reduce a nullable additive bonus to an effect: 0.0 -> None. */
        fun chipsOrNone(n: Double): Effect = if (n != 0.0) Chips(n) else None
        fun multOrNone(n: Double): Effect = if (n != 0.0) Mult(n) else None
    }
}

/**
 * Translate an [Effect] into the engine's Fx for the relevant pass. The Fx distinguishes the INDIVIDUAL
 * fields (chips/mult/xMult/hMult) from the joker_main *Mod fields by historical Lua-port convention; this
 * is the ONE place that mapping lives. Total: every Effect variant is handled.
 */
internal fun Effect.intoFx(individual: Boolean): Fx {
    val fx = Fx()
    fun go(e: Effect) {
        when (e) {
            Effect.None -> {}
            is Effect.Chips -> if (individual) fx.chips += e.n else fx.chipMod += e.n
            is Effect.Mult -> if (individual) fx.mult += e.n else fx.multMod += e.n
            is Effect.XMult -> if (individual) fx.xMult = (if (fx.xMult == 0.0) 1.0 else fx.xMult) * e.x else fx.xMultMod *= e.x
            is Effect.XChips -> fx.xChipMod *= e.x
            is Effect.EMult -> fx.eMult *= e.e
            is Effect.EChips -> fx.eChipMod *= e.e
            is Effect.HeldMult -> fx.hMult = (if (fx.hMult == 0.0) 1.0 else fx.hMult) * e.x
            is Effect.Retrigger -> fx.repetitions += e.n
            Effect.Nullify -> fx.nullify = true
            is Effect.All -> e.of.forEach { go(it) }
        }
    }
    go(this)
    return fx
}

/** Game events a joker's state reducer reacts to — each carries just enough to be a pure input. */
sealed interface GameEvent {
    /** Before-pass, once per hand, with the scoring context (scoringName / pokerHands known). */
    data class BeforeHand(val ctx: Sctx) : GameEvent
    /** A hand was scored and banked (run loop). [playedCount] = number of cards played (for j_square etc.). */
    data class HandScored(val handType: HandType, val playedCount: Int = 0) : GameEvent
    /** Cards were discarded (run loop). */
    data class Discarded(val cards: List<PlayingCard>) : GameEvent
}

typealias ScoreHook = (self: FJokerState, ctx: Sctx) -> Effect
typealias CardHook = (self: FJokerState, ctx: Sctx, card: PlayingCard) -> Effect   // individual / held: the card is passed, never null
typealias OtherJokerHook = (self: FJokerState, ctx: Sctx, other: FJoker) -> Effect
typealias Reducer = (state: FJokerState, event: GameEvent) -> FJokerState

/** One joker's complete behaviour, co-located. Every hook optional — a joker fills only what it uses. */
data class JokerSpec(
    val initialState: FJokerState = FJokerState(),
    val jokerMain: ScoreHook? = null,        // context.joker_main
    val individual: CardHook? = null,         // context.individual, per scored played card (receives the card)
    val held: CardHook? = null,               // context.cardarea == hand (receives the held card)
    val otherJoker: OtherJokerHook? = null,   // context.other_joker
    val retrigger: ScoreHook? = null,         // context.retrigger_joker_check
    val reduce: Reducer? = null,              // pure state evolution on game events
)

/** Dispatch a migrated joker through its spec for the CURRENT scoring context (mirrors calcJoker's flags). */
internal fun dispatchManifest(spec: JokerSpec, j: FJoker, ctx: Sctx): Fx? {
    val self = j.snapshot()
    val individual = (ctx.individual && ctx.cardarea == "play") || ctx.held
    val effect: Effect = when {
        ctx.jokerRetriggerCheck                  -> spec.retrigger?.invoke(self, ctx) ?: Effect.None
        ctx.individual && ctx.cardarea == "play" && ctx.otherCard != null -> spec.individual?.invoke(self, ctx, ctx.otherCard!!) ?: Effect.None
        ctx.held && ctx.otherCard != null        -> spec.held?.invoke(self, ctx, ctx.otherCard!!) ?: Effect.None
        ctx.otherJoker != null                   -> spec.otherJoker?.invoke(self, ctx, ctx.otherJoker!!) ?: Effect.None
        ctx.jokerMain                            -> spec.jokerMain?.invoke(self, ctx) ?: Effect.None
        else                                     -> Effect.None
    }
    return if (effect == Effect.None) null else effect.intoFx(individual)
}

/** is_jolly() (Cryptid lib/misc.lua:302): Jolly Joker, cry-jollysus, or the cry_m edition. */
internal fun FJoker.isJolly(): Boolean = key == "j_jolly" || key == "j_cry_jollysus" || edition == "cry_m"

/**
 * The registry — one joker, one place.
 * Batch 1: the 3 proof-of-concept jokers + the vanilla "+Mult if hand contains <type>" conditional family.
 */
val JOKER_MANIFEST: Map<String, JokerSpec> = mapOf(
    // ── vanilla "+Mult if hand contains <type>" family (game.lua j_jolly..j_droll) ──────────────────
    "j_jolly" to JokerSpec(jokerMain = { _, ctx -> if (HandType.PAIR in ctx.pokerHands) Effect.Mult(8.0) else Effect.None }),
    "j_zany"  to JokerSpec(jokerMain = { _, ctx -> if (HandType.THREE_OF_A_KIND in ctx.pokerHands) Effect.Mult(12.0) else Effect.None }),
    "j_mad"   to JokerSpec(jokerMain = { _, ctx -> if (HandType.TWO_PAIR in ctx.pokerHands) Effect.Mult(10.0) else Effect.None }),
    "j_crazy" to JokerSpec(jokerMain = { _, ctx -> if (HandType.STRAIGHT in ctx.pokerHands) Effect.Mult(12.0) else Effect.None }),
    "j_droll" to JokerSpec(jokerMain = { _, ctx -> if (HandType.FLUSH in ctx.pokerHands) Effect.Mult(10.0) else Effect.None }),

    // ── stronghold — pure conditional joker_main (CRY_BULWARK present -> X5 Mult) ────────────────────
    "j_cry_stronghold" to JokerSpec(
        jokerMain = { _, ctx -> if (HandType.CRY_BULWARK in ctx.pokerHands) Effect.XMult(5.0) else Effect.None },
    ),

    // ── bonk — initial chips/xc + before-pass scaling on a Pair + per-board-joker chips ─────────────
    "j_cry_bonk" to JokerSpec(
        initialState = FJokerState(chips = 6.0, xc = 3.0),
        reduce = { s, e -> if (e is GameEvent.BeforeHand && e.ctx.scoringName == HandType.PAIR) s.copy(chips = s.chips + 1.0) else s },
        otherJoker = { s, _, oj -> Effect.chipsOrNone(if (oj.isJolly()) s.chips * s.xc else s.chips) },
    ),

    // ── green_joker — +1 Mult/hand, -1/discard; the accumulated Mult is read in joker_main ──────────
    "j_green_joker" to JokerSpec(
        reduce = { s, e -> when (e) {
            is GameEvent.HandScored -> s.copy(mult = s.mult + 1.0)
            is GameEvent.Discarded  -> s.copy(mult = maxOf(0.0, s.mult - 1.0))
            is GameEvent.BeforeHand -> s
        } },
        jokerMain = { s, _ -> Effect.multOrNone(if (s.mult > 0.0) s.mult else 0.0) },
    ),

    // ── batch 2: vanilla "+Chips if hand contains <type>" family (game.lua j_wily..j_crafty) ─────────
    "j_wily"    to JokerSpec(jokerMain = { _, ctx -> if (HandType.THREE_OF_A_KIND in ctx.pokerHands) Effect.Chips(100.0) else Effect.None }),
    "j_clever"  to JokerSpec(jokerMain = { _, ctx -> if (HandType.TWO_PAIR in ctx.pokerHands) Effect.Chips(80.0) else Effect.None }),
    "j_devious" to JokerSpec(jokerMain = { _, ctx -> if (HandType.STRAIGHT in ctx.pokerHands) Effect.Chips(100.0) else Effect.None }),
    "j_crafty"  to JokerSpec(jokerMain = { _, ctx -> if (HandType.FLUSH in ctx.pokerHands) Effect.Chips(80.0) else Effect.None }),

    // ── batch 2: per-scored-card reactors (the individual pass) ─────────────────────────────────────
    "j_greedy_joker"     to JokerSpec(individual = { _, ctx, c -> if (c.isSuit(Suit.D, ctx.smeared)) Effect.Mult(3.0) else Effect.None }),
    "j_lusty_joker"      to JokerSpec(individual = { _, ctx, c -> if (c.isSuit(Suit.H, ctx.smeared)) Effect.Mult(3.0) else Effect.None }),
    "j_wrathful_joker"   to JokerSpec(individual = { _, ctx, c -> if (c.isSuit(Suit.S, ctx.smeared)) Effect.Mult(3.0) else Effect.None }),
    "j_gluttenous_joker" to JokerSpec(individual = { _, ctx, c -> if (c.isSuit(Suit.C, ctx.smeared)) Effect.Mult(3.0) else Effect.None }),
    "j_even_steven"      to JokerSpec(individual = { _, _, c -> if (c.id in setOf(2, 4, 6, 8, 10)) Effect.Mult(4.0) else Effect.None }),
    "j_odd_todd"         to JokerSpec(individual = { _, _, c -> if (c.id == 14 || c.id in setOf(3, 5, 7, 9)) Effect.Chips(31.0) else Effect.None }),
    "j_scholar"          to JokerSpec(individual = { _, _, c -> if (c.id == 14) Effect.All(listOf(Effect.Chips(20.0), Effect.Mult(4.0))) else Effect.None }),

    // ── batch 3: per-hand scaling accumulators (reducer accrues on HandScored; joker_main reads the total) ──
    "j_spare_trousers" to JokerSpec(
        reduce = { s, e -> if (e is GameEvent.HandScored && (e.handType == HandType.TWO_PAIR || e.handType == HandType.FULL_HOUSE)) s.copy(mult = s.mult + 2.0) else s },
        jokerMain = { s, _ -> Effect.multOrNone(s.mult) },
    ),
    "j_runner" to JokerSpec(
        reduce = { s, e -> if (e is GameEvent.HandScored && (e.handType == HandType.STRAIGHT || e.handType == HandType.STRAIGHT_FLUSH)) s.copy(chips = s.chips + 15.0) else s },
        jokerMain = { s, _ -> Effect.chipsOrNone(s.chips) },
    ),
    "j_square" to JokerSpec(
        reduce = { s, e -> if (e is GameEvent.HandScored && e.playedCount == 5) s.copy(chips = s.chips + 4.0) else s },
        jokerMain = { s, _ -> Effect.chipsOrNone(s.chips) },
    ),

    // ── batch 4a: per-scored-card reactors — vanilla individual jokers ────────────────────────────────
    // arrowhead: +50 Chips per scored Spade (game.lua)
    "j_arrowhead"     to JokerSpec(individual = { _, ctx, c -> if (c.isSuit(Suit.S, ctx.smeared)) Effect.Chips(50.0) else Effect.None }),
    // onyx_agate: +7 Mult per scored Club (game.lua)
    "j_onyx_agate"    to JokerSpec(individual = { _, ctx, c -> if (c.isSuit(Suit.C, ctx.smeared)) Effect.Mult(7.0) else Effect.None }),
    // fibonacci: +8 Mult per scored A, 2, 3, 5, 8 (Fibonacci ranks; get_id, Maximized maps pips→10)
    "j_fibonacci"     to JokerSpec(individual = { _, _, c -> if (c.id in setOf(2, 3, 5, 8, 14)) Effect.Mult(8.0) else Effect.None }),
    // scary_face: +30 Chips per scored face card (includes Pareidolia)
    "j_scary_face"    to JokerSpec(individual = { _, ctx, c -> if (c.isFace || ctx.pareidolia) Effect.Chips(30.0) else Effect.None }),
    // smiley: +5 Mult per scored face card (includes Pareidolia)
    "j_smiley"        to JokerSpec(individual = { _, ctx, c -> if (c.isFace || ctx.pareidolia) Effect.Mult(5.0) else Effect.None }),
    // triboulet: X2 Mult per scored King or Queen (game.lua)
    "j_triboulet"     to JokerSpec(individual = { _, _, c -> if (c.id == 12 || c.id == 13) Effect.XMult(2.0) else Effect.None }),
    // walkie_talkie: +10 Chips, +4 Mult per scored 10 or 4 (game.lua)
    "j_walkie_talkie" to JokerSpec(individual = { _, _, c ->
        if (c.id == 10 || c.id == 4) Effect.All(listOf(Effect.Chips(10.0), Effect.Mult(4.0))) else Effect.None
    }),
    // hack: retrigger each scored 2, 3, 4, or 5 (game.lua)
    "j_hack"          to JokerSpec(individual = { _, _, c -> if (c.id in 2..5) Effect.Retrigger(1) else Effect.None }),
    // sock_and_buskin: retrigger each scored face card (game.lua)
    "j_sock_and_buskin" to JokerSpec(individual = { _, ctx, c -> if (c.isFace || ctx.pareidolia) Effect.Retrigger(1) else Effect.None }),
    // dusk: retrigger every scored card on the last hand of the round (game.lua)
    "j_dusk"          to JokerSpec(individual = { _, ctx, _ -> if (ctx.handsLeft == 0) Effect.Retrigger(1) else Effect.None }),

    // ── batch 4b: joker_main flat effect — no scaling state, simple condition ────────────────────────
    // half: +20 Mult when hand size ≤ 3 played cards (game.lua)
    "j_half"         to JokerSpec(jokerMain = { _, ctx -> if (ctx.fullHand.size <= 3) Effect.Mult(20.0) else Effect.None }),
    // sly: +50 Chips when played hand contains a Pair (game.lua; j_sly is the "+Chips / Pair" entry)
    "j_sly"          to JokerSpec(jokerMain = { _, ctx -> if (HandType.PAIR in ctx.pokerHands) Effect.Chips(50.0) else Effect.None }),
    // acrobat: X3 Mult on the final hand of the round (game.lua)
    "j_acrobat"      to JokerSpec(jokerMain = { _, ctx -> if (ctx.handsLeft == 0) Effect.XMult(3.0) else Effect.None }),
    // mystic_summit: +15 Mult when discards remaining is 0 (game.lua)
    "j_mystic_summit" to JokerSpec(jokerMain = { _, ctx -> if (ctx.discardsLeft == 0) Effect.Mult(15.0) else Effect.None }),
    // cry_night: Emult^3 (mult^3) on the final hand of the round (Cryptid misc_joker.lua)
    "j_cry_night"    to JokerSpec(jokerMain = { _, ctx -> if (ctx.handsLeft == 0) Effect.EMult(3.0) else Effect.None }),
    // cry_supercell: +15 Chips, X2 Chips, +15 Mult, X2 Mult unconditionally (Cryptid misc_joker.lua)
    "j_cry_supercell" to JokerSpec(jokerMain = { _, _ ->
        Effect.All(listOf(Effect.Chips(15.0), Effect.XChips(2.0), Effect.Mult(15.0), Effect.XMult(2.0)))
    }),
    // cry_kittyprinter: X2 Mult unconditionally (Cryptid misc_joker.lua config.extra.Xmult=2)
    "j_cry_kittyprinter" to JokerSpec(jokerMain = { _, _ -> Effect.XMult(2.0) }),
    // cry_brokenhome: X11.4 Mult unconditionally (Cryptid spooky.lua)
    "j_cry_brokenhome"   to JokerSpec(jokerMain = { _, _ -> Effect.XMult(11.4) }),
    // cry_cube: +6 Chips unconditionally (Cryptid misc_joker.lua config.extra.chip=6)
    "j_cry_cube"         to JokerSpec(jokerMain = { _, _ -> Effect.Chips(6.0) }),
    // cry_big_cube: X6 Chips (Xchip) unconditionally (Cryptid exotic.lua)
    "j_cry_big_cube"     to JokerSpec(jokerMain = { _, _ -> Effect.XChips(6.0) }),
    // cry_apjoker: X4 Mult on boss blinds (Cryptid misc_joker.lua)
    "j_cry_apjoker"      to JokerSpec(jokerMain = { _, ctx -> if (ctx.bossBlind) Effect.XMult(4.0) else Effect.None }),

    // ── batch 4c: Cryptid hand-type flat jokers (contains-<type> → flat stat) ────────────────────────
    // The two-column layout: High Card, Full House, Four of a Kind, Straight Flush,
    // Five of a Kind, Flush House, Flush Five, Two Pair — each has Mult / Chips / XMult variants.
    "j_cry_giggly"    to JokerSpec(jokerMain = { _, ctx -> if (HandType.HIGH_CARD in ctx.pokerHands) Effect.Mult(4.0) else Effect.None }),
    "j_cry_dubious"   to JokerSpec(jokerMain = { _, ctx -> if (HandType.HIGH_CARD in ctx.pokerHands) Effect.Chips(20.0) else Effect.None }),
    "j_cry_silly"     to JokerSpec(jokerMain = { _, ctx -> if (HandType.FULL_HOUSE in ctx.pokerHands) Effect.Mult(16.0) else Effect.None }),
    "j_cry_foxy"      to JokerSpec(jokerMain = { _, ctx -> if (HandType.FULL_HOUSE in ctx.pokerHands) Effect.Chips(130.0) else Effect.None }),
    "j_cry_home"      to JokerSpec(jokerMain = { _, ctx -> if (HandType.FULL_HOUSE in ctx.pokerHands) Effect.XMult(3.5) else Effect.None }),
    "j_cry_nutty"     to JokerSpec(jokerMain = { _, ctx -> if (HandType.FOUR_OF_A_KIND in ctx.pokerHands) Effect.Mult(19.0) else Effect.None }),
    "j_cry_shrewd"    to JokerSpec(jokerMain = { _, ctx -> if (HandType.FOUR_OF_A_KIND in ctx.pokerHands) Effect.Chips(150.0) else Effect.None }),
    "j_cry_manic"     to JokerSpec(jokerMain = { _, ctx -> if (HandType.STRAIGHT_FLUSH in ctx.pokerHands) Effect.Mult(22.0) else Effect.None }),
    "j_cry_tricksy"   to JokerSpec(jokerMain = { _, ctx -> if (HandType.STRAIGHT_FLUSH in ctx.pokerHands) Effect.Chips(170.0) else Effect.None }),
    "j_cry_nuts"      to JokerSpec(jokerMain = { _, ctx -> if (HandType.STRAIGHT_FLUSH in ctx.pokerHands) Effect.XMult(5.0) else Effect.None }),
    "j_cry_delirious" to JokerSpec(jokerMain = { _, ctx -> if (HandType.FIVE_OF_A_KIND in ctx.pokerHands) Effect.Mult(22.0) else Effect.None }),
    "j_cry_savvy"     to JokerSpec(jokerMain = { _, ctx -> if (HandType.FIVE_OF_A_KIND in ctx.pokerHands) Effect.Chips(170.0) else Effect.None }),
    "j_cry_quintet"   to JokerSpec(jokerMain = { _, ctx -> if (HandType.FIVE_OF_A_KIND in ctx.pokerHands) Effect.XMult(5.0) else Effect.None }),
    "j_cry_wacky"     to JokerSpec(jokerMain = { _, ctx -> if (HandType.FLUSH_HOUSE in ctx.pokerHands) Effect.Mult(30.0) else Effect.None }),
    "j_cry_subtle"    to JokerSpec(jokerMain = { _, ctx -> if (HandType.FLUSH_HOUSE in ctx.pokerHands) Effect.Chips(240.0) else Effect.None }),
    "j_cry_unity"     to JokerSpec(jokerMain = { _, ctx -> if (HandType.FLUSH_HOUSE in ctx.pokerHands) Effect.XMult(9.0) else Effect.None }),
    "j_cry_kooky"     to JokerSpec(jokerMain = { _, ctx -> if (HandType.FLUSH_FIVE in ctx.pokerHands) Effect.Mult(30.0) else Effect.None }),
    "j_cry_discreet"  to JokerSpec(jokerMain = { _, ctx -> if (HandType.FLUSH_FIVE in ctx.pokerHands) Effect.Chips(240.0) else Effect.None }),
    "j_cry_swarm"     to JokerSpec(jokerMain = { _, ctx -> if (HandType.FLUSH_FIVE in ctx.pokerHands) Effect.XMult(9.0) else Effect.None }),
    "j_cry_duos"      to JokerSpec(jokerMain = { _, ctx -> if (HandType.TWO_PAIR in ctx.pokerHands || HandType.FULL_HOUSE in ctx.pokerHands) Effect.XMult(2.5) else Effect.None }),

    // ── batch 5a: individual-path retrigger jokers ───────────────────────────────────────────────────
    // cry_mask: +3 retriggers per scored face card (Cryptid spooky.lua config.extra.retriggers=3)
    "j_cry_mask"        to JokerSpec(individual = { _, ctx, c -> if (c.isFace || ctx.pareidolia) Effect.Retrigger(3) else Effect.None }),
    // cry_sock_and_sock: retrigger each played Abstract-enhanced card once (Cryptid misc_joker.lua)
    "j_cry_sock_and_sock" to JokerSpec(individual = { _, _, c -> if (c.enhancement == Enhancement.ABSTRACT) Effect.Retrigger(1) else Effect.None }),

    // ── batch 5b: held-in-hand jokers ────────────────────────────────────────────────────────────────
    // baron: X1.5 Mult per King held in hand (game.lua)
    "j_baron"           to JokerSpec(held = { _, _, c -> if (c.id == 13) Effect.XMult(1.5) else Effect.None }),
    // shoot_the_moon: +13 Mult per Queen held in hand (game.lua)
    "j_shoot_the_moon"  to JokerSpec(held = { _, _, c -> if (c.id == 12) Effect.Mult(13.0) else Effect.None }),
    // raised_fist: +2x the chip value of the LOWEST non-Stone held card (game.lua).
    // fired once, for the specific lowest card; the hook receives each held card, fires only on the minimum.
    "j_raised_fist"     to JokerSpec(held = { _, ctx, c ->
        val low = ctx.heldHand.filter { it.enhancement != Enhancement.STONE }.minByOrNull { it.nominal }
        if (low != null && c === low) Effect.Mult(2.0 * low.chips) else Effect.None
    }),

    // ── batch 5c: Cryptid edition reactors (multi-hook: individual + held + otherJoker) ─────────────
    // Each fires per playing card / held card / board joker carrying the named edition.
    // meteor: +75 Chips / Foil scored card; +75 Chips / Foil other joker (held-chips dead in Lua).
    "j_cry_meteor"      to JokerSpec(
        individual  = { _, _, c -> if (c.edition == "Foil") Effect.Chips(75.0) else Effect.None },
        otherJoker  = { _, _, oj -> if (oj.edition == "Foil") Effect.Chips(75.0) else Effect.None },
    ),
    // exoplanet: +15 Mult / Holo scored card; +15 hMult (Steel-card-style) / Holo held card; +15 Mult / Holo other joker.
    "j_cry_exoplanet"   to JokerSpec(
        individual  = { _, _, c -> if (c.edition == "Holo") Effect.Mult(15.0) else Effect.None },
        held        = { _, _, c -> if (c.edition == "Holo") Effect.HeldMult(15.0) else Effect.None },
        otherJoker  = { _, _, oj -> if (oj.edition == "Holo") Effect.Mult(15.0) else Effect.None },
    ),
    // stardust: X2 Mult / Poly scored card; X2 Mult / Poly held card; X2 Mult / Poly other joker.
    "j_cry_stardust"    to JokerSpec(
        individual  = { _, _, c -> if (c.edition == "Poly") Effect.XMult(2.0) else Effect.None },
        held        = { _, _, c -> if (c.edition == "Poly") Effect.XMult(2.0) else Effect.None },
        otherJoker  = { _, _, oj -> if (oj.edition == "Poly") Effect.XMult(2.0) else Effect.None },
    ),
    // universe: Emult^1.2 / Astral scored card; Emult^1.2 / Astral held card; Emult^1.2 / Astral other joker.
    "j_cry_universe"    to JokerSpec(
        individual  = { _, _, c -> if (c.edition == "Astral") Effect.EMult(1.2) else Effect.None },
        held        = { _, _, c -> if (c.edition == "Astral") Effect.EMult(1.2) else Effect.None },
        otherJoker  = { _, _, oj -> if (oj.edition == "Astral") Effect.EMult(1.2) else Effect.None },
    ),

    // ── batch 5d: retrigger-joker-check (retrigger hook) ─────────────────────────────────────────────
    // cry_flip_side: retrigger any board joker that has the double-sided edition (Cryptid misc_joker.lua).
    "j_cry_flip_side"   to JokerSpec(retrigger = { _, ctx -> if (ctx.retriggeredJoker?.edition == "cry_double_sided") Effect.Retrigger(1) else Effect.None }),

    // ── batch 5e: other-joker reactors ────────────────────────────────────────────────────────────────
    // baseball: X1.5 Mult per Uncommon (rarity=2) other board joker; baseball is Legendary so never fires on itself.
    "j_baseball"        to JokerSpec(otherJoker = { _, _, oj -> if (oj.rarity == 2) Effect.XMult(1.5) else Effect.None }),
    // cry_waluigi: X2.5 Mult per board joker including itself (Cryptid misc_joker.lua — no self-exclusion in vanilla).
    "j_cry_waluigi"     to JokerSpec(otherJoker = { _, _, _ -> Effect.XMult(2.5) }),

    // ── batch 6a: board-state counters refreshed by RunScreen before-pass (j.n = live count) ─────────
    // steel_joker: X(1 + 0.2×steelCount) Mult; j.n = count of Steel-enhanced cards in the deck (before-pass).
    "j_steel_joker"   to JokerSpec(jokerMain = { s, _ -> if (s.n > 0) Effect.XMult(1.0 + 0.2 * s.n) else Effect.None }),
    // stone: +25 Chips per Stone-enhanced card in the deck (before-pass sets j.n = stoneCount).
    "j_stone"         to JokerSpec(jokerMain = { s, _ -> if (s.n > 0) Effect.Chips(25.0 * s.n) else Effect.None }),
    // blue_joker: +2 Chips per card remaining in the deck (before-pass sets j.n = deck.remaining).
    "j_blue_joker"    to JokerSpec(jokerMain = { s, _ -> if (s.n > 0) Effect.Chips(2.0 * s.n) else Effect.None }),
    // banner: +30 Chips per remaining discard (before-pass sets j.n = discardsLeft).
    "j_banner"        to JokerSpec(jokerMain = { s, _ -> if (s.n > 0) Effect.Chips(30.0 * s.n) else Effect.None }),
    // abstract: +3 Mult per joker on the board (before-pass sets j.n = owned.size).
    "j_abstract"      to JokerSpec(jokerMain = { s, _ -> if (s.n > 0) Effect.Mult(3.0 * s.n) else Effect.None }),
    // drivers_license: X3 Mult when ≥16 enhanced cards in the deck (before-pass sets j.n = deck.enhancedCards).
    "j_drivers_license" to JokerSpec(jokerMain = { s, _ -> if (s.n >= 16) Effect.XMult(3.0) else Effect.None }),

    // ── batch 6b: ctx-reads — no j.n/j.x/j.chips state, reads scoring context directly ─────────────
    // supernova: +Mult = number of times this hand type has been played this run (incl. current hand).
    // ctx.scoringPlays is set before the joker pass to G.GAME.hands[scoringName].played + 1.
    "j_supernova"     to JokerSpec(jokerMain = { _, ctx -> Effect.Mult(ctx.scoringPlays.toDouble()) }),

    // ── batch 6c: individual retrigger — no state, fires on the first scored card ──────────────────
    // hanging_chad: retrigger the FIRST scored card 2 additional times (game.lua config.extra.repetitions=2).
    "j_hanging_chad"  to JokerSpec(individual = { _, ctx, c -> if (c === ctx.scoringHand.firstOrNull()) Effect.Retrigger(2) else Effect.None }),

    // ── batch 6d: chips-accumulator jokerMain (j.chips set by run-loop events, jokerMain reads the total) ──
    // castle: +j.chips (+=3 per flush-suit card discarded in a flush discard; run-loop sets it).
    "j_castle"        to JokerSpec(jokerMain = { s, _ -> Effect.chipsOrNone(s.chips) }),
    // cry_cursor: +j.chips (+=8 per card purchased; run-loop sets it via onCardBought()).
    "j_cry_cursor"    to JokerSpec(jokerMain = { s, _ -> Effect.chipsOrNone(s.chips) }),
    // cry_crustulum: +j.chips (+=4 per reroll in the shop; run-loop sets it via reroll()).
    "j_cry_crustulum" to JokerSpec(jokerMain = { s, _ -> Effect.chipsOrNone(s.chips) }),
)
