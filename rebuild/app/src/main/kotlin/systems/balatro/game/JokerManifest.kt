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
    /** A hand was scored and banked (run loop). [playedCount] = number of cards played (for j_square etc.).
     *  [handPlays] = play-counts per hand type AFTER this hand is recorded (matching Lua context.after timing
     *  where G.GAME.hands[...].played has already been incremented). Obelisk uses this to decide grow vs reset. */
    data class HandScored(val handType: HandType, val playedCount: Int = 0, val handPlays: Map<HandType, Int> = emptyMap(), val totalHandsPlayed: Int = 0, val contained: Set<HandType> = emptySet()) : GameEvent
    /** Cards were discarded (run loop). */
    data class Discarded(val cards: List<PlayingCard>) : GameEvent
    /** A joker was sold (run loop). [sellValue] = its sell value (sell_cost), for the eternalflame >= 2 gate. */
    data class Sold(val soldKey: String, val sellValue: Int) : GameEvent
    /** End of round (cashOut, context.end_of_round). [discardsUsed] = discards consumed this round (mondrian: guards +0.25 only when 0). */
    data class RoundEnd(val discardsUsed: Int) : GameEvent
    /** One or more playing cards were added to the persistent deck (Standard pack pick; future: Certificate,
     *  Familiar/Grim/Incantation, Marble Joker, DNA). [count] mirrors #context.cards in Lua's
     *  playing_card_joker_effects call — always >=1. Hologram accumulates +0.25 × count. */
    data class CardAdded(val count: Int = 1) : GameEvent
    /** The player skipped a Small or Big blind (button_callbacks.lua:2995 G.GAME.skips++,
     *  SMODS.calculate_context({skip_blind=true})). Throwback recomputes x_mult = 1 + skips*0.25
     *  in Lua's update() loop; here we mirror that with x += 0.25 per skip event. */
    object BlindSkipped : GameEvent
}

typealias ScoreHook = (self: FJokerState, ctx: Sctx) -> Effect
typealias CardHook = (self: FJokerState, ctx: Sctx, card: PlayingCard) -> Effect   // individual / held: the card is passed, never null
typealias OtherJokerHook = (self: FJokerState, ctx: Sctx, other: FJoker) -> Effect
typealias Reducer = (state: FJokerState, event: GameEvent) -> FJokerState
/** Per-card state mutation (two-phase jokers: accumulate during individual pass, read in jokerMain). */
typealias PerCardHook = (state: FJokerState, ctx: Sctx, card: PlayingCard) -> FJokerState

/** One joker's complete behaviour, co-located. Every hook optional — a joker fills only what it uses. */
data class JokerSpec(
    val initialState: FJokerState = FJokerState(),
    val jokerMain: ScoreHook? = null,        // context.joker_main
    val individual: CardHook? = null,         // context.individual, per scored played card (receives the card)
    val held: CardHook? = null,               // context.cardarea == hand (receives the held card)
    val otherJoker: OtherJokerHook? = null,   // context.other_joker
    val retrigger: ScoreHook? = null,         // context.retrigger_joker_check
    val reduce: Reducer? = null,              // pure state evolution on game events
    val perCard: PerCardHook? = null,         // in-scoring accumulation: called during individual pass, mutates j.state via restore(); receives ctx for rankOf etc.
    /** Chips permanently added to a scored played card each time this joker sees it (Hiker: +5).
     *  Score.kt fires the onPermaBonusGained callback so RunScreen can persist the bonus to Deck. */
    val grantsPermaBonusPerCard: Int = 0,
)

/** Dispatch a migrated joker through its spec for the CURRENT scoring context (mirrors calcJoker's flags). */
internal fun dispatchManifest(spec: JokerSpec, j: FJoker, ctx: Sctx): Fx? {
    val self = j.snapshot()
    ctx.selfJoker = j   // expose live self identity for hooks that need j !== rj / j !== oj guards
    ctx.handsPlayedAtCreate = j.n   // j.n stores hands_played_at_create for loyalty_card; safe default=0 for all others
    val individual = (ctx.individual && ctx.cardarea == "play") || ctx.held
    val effect: Effect = when {
        ctx.jokerRetriggerCheck                  -> spec.retrigger?.invoke(self, ctx) ?: Effect.None
        // Repetition-collection pass (ctx.repetition=true): same condition logic as individual —
        // route through the individual hook so retrigger jokers contribute fx.repetitions here.
        // Scoring side-effects (chips/mult) in the Fx are ignored by the collector (only .repetitions is read).
        ctx.repetition && ctx.otherCard != null  -> spec.individual?.invoke(self, ctx, ctx.otherCard!!) ?: Effect.None
        ctx.individual && ctx.cardarea == "play" && ctx.otherCard != null -> spec.individual?.invoke(self, ctx, ctx.otherCard!!) ?: Effect.None
        ctx.held && ctx.otherCard != null        -> spec.held?.invoke(self, ctx, ctx.otherCard!!) ?: Effect.None
        ctx.otherJoker != null                   -> spec.otherJoker?.invoke(self, ctx, ctx.otherJoker!!) ?: Effect.None
        ctx.jokerMain                            -> spec.jokerMain?.invoke(self, ctx) ?: Effect.None
        else                                     -> Effect.None
    }
    // Two-phase accumulation: perCard hook runs during the individual SCORING pass (which itself loops
    // 1+reps times, so retriggered cards accumulate once per scoring instance) and updates live FJoker
    // state so the jokerMain pass can read the accumulated value from j.snapshot(). It must NOT also fire
    // during the repetition-COLLECTION pass (ctx.repetition) — that pass only tallies retrigger counts and
    // visits each card once, so firing there double-counts every non-retriggered card.
    if (ctx.individual && ctx.cardarea == "play" && ctx.otherCard != null) {
        spec.perCard?.let { j.restore(it(self, ctx, ctx.otherCard!!)) }
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
        // card.lua:3563 — growth happens in context.BEFORE, so the +1 counts for THIS hand
        // (HandScored fires after scoring — one hand late; caught by the parity audit).
        reduce = { s, e -> when (e) {
            is GameEvent.BeforeHand -> s.copy(mult = s.mult + 1.0)
            is GameEvent.Discarded  -> s.copy(mult = maxOf(0.0, s.mult - 1.0))
            else -> s
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
    // Spare Trousers / Runner fire on CONTAINMENT (context.poker_hands), not the top hand type — so they
    // also fire when a higher hand contains the pattern (e.g. a Flush House contains a Two Pair). The top-
    // handType check missed those (only reachable with duplicate-card hands). e.contained = r.pokerHands.
    // These three grow in context.BEFORE (card.lua ~3411-3520): the growth counts for the hand
    // that triggers it. Containment still via ctx.pokerHands (a Flush House contains a Two Pair).
    "j_spare_trousers" to JokerSpec(
        reduce = { s, e -> if (e is GameEvent.BeforeHand && HandType.TWO_PAIR in e.ctx.pokerHands) s.copy(mult = s.mult + 2.0) else s },
        jokerMain = { s, _ -> Effect.multOrNone(s.mult) },
    ),
    "j_runner" to JokerSpec(
        reduce = { s, e -> if (e is GameEvent.BeforeHand && HandType.STRAIGHT in e.ctx.pokerHands) s.copy(chips = s.chips + 15.0) else s },
        jokerMain = { s, _ -> Effect.chipsOrNone(s.chips) },
    ),
    "j_square" to JokerSpec(
        // Square Joker: +4 Chips when EXACTLY 4 cards are played (vanilla j_square chip_mod=4; #full_hand==4), not 5.
        reduce = { s, e -> if (e is GameEvent.BeforeHand && e.ctx.fullHand.size == 4) s.copy(chips = s.chips + 4.0) else s },
        jokerMain = { s, _ -> Effect.chipsOrNone(s.chips) },
    ),
    // ride_the_bus — card.lua:3525-3541 context.before: any FACE in the scoring hand resets the
    // accumulated Mult to 0; otherwise +1/hand. is_face honors Pareidolia.
    "j_ride_the_bus" to JokerSpec(
        reduce = { s, e -> if (e is GameEvent.BeforeHand) {
            if (e.ctx.scoringHand.any { it.isFace || e.ctx.pareidolia }) s.copy(mult = 0.0)
            else s.copy(mult = s.mult + 1.0)
        } else s },
        jokerMain = { s, _ -> Effect.multOrNone(s.mult) },
    ),
    // constellation — X(1 + 0.1/planet used) Mult. RunScreen grows x on planet USE
    // (useConsumable hook); this reader was missing, so the joker never applied at scoring.
    "j_constellation" to JokerSpec(
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),

    // ── batch 4a: per-scored-card reactors — vanilla individual jokers ────────────────────────────────
    // arrowhead: +50 Chips per scored Spade (game.lua)
    "j_arrowhead"     to JokerSpec(individual = { _, ctx, c -> if (c.isSuit(Suit.S, ctx.smeared)) Effect.Chips(50.0) else Effect.None }),
    // onyx_agate: +7 Mult per scored Club (game.lua)
    "j_onyx_agate"    to JokerSpec(individual = { _, ctx, c -> if (c.isSuit(Suit.C, ctx.smeared)) Effect.Mult(7.0) else Effect.None }),
    // j_ancient: X1.5 Mult per scored card of THIS round's suit (ctx.ancientSuit, picked by the run loop;
    // changes each round, never the same as last round). card.lua:3849 individual x_mult.
    "j_ancient"       to JokerSpec(individual = { _, ctx, c ->
        val s = ctx.ancientSuit; if (s != null && c.isSuit(s, ctx.smeared)) Effect.XMult(1.5) else Effect.None
    }),
    // j_vampire: gains X0.1 Mult per scored ENHANCED card and strips the enhancement (card.lua:4065).
    // joker_main READS the persisted x plus THIS hand's contribution (pure — Blueprint-safe); RunScreen's
    // scoreBank persists the growth (fj.x += 0.1·n) and removes the enhancements after scoring.
    "j_vampire"       to JokerSpec(jokerMain = { fj, ctx ->
        val n = ctx.scoringHand.count { it.enhancement != Enhancement.NONE }
        val x = fj.x + 0.1 * n
        if (x > 1.0) Effect.XMult(x) else Effect.None
    }),
    // j_lucky_cat: gains X0.25 per Lucky-card TRIGGER (card.lua:3660 individual on lucky_trigger). Fires per
    // triggered scored card; reads the persisted x plus 0.25·(triggers so far this hand) — pure, Blueprint-safe.
    // RunScreen's scoreBank persists the growth (fj.x += 0.25·luckyTriggers) after the hand.
    "j_lucky_cat"     to JokerSpec(individual = { fj, ctx, _ ->
        if (ctx.luckyTriggerThisCard) {
            val x = fj.x + 0.25 * ctx.luckyTriggersThisHand
            if (x > 1.0) Effect.XMult(x) else Effect.None
        } else Effect.None
    }),
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

    // ── batch 7a: joker_main flat (no state, no accumulator) ─────────────────────────────────────────
    // j_joker: +4 Mult unconditionally (game.lua)
    "j_joker"         to JokerSpec(jokerMain = { _, _ -> Effect.Mult(4.0) }),
    // j_stuntman: +250 Chips unconditionally (game.lua config.extra.chip=250)
    "j_stuntman"      to JokerSpec(jokerMain = { _, _ -> Effect.Chips(250.0) }),

    // ── batch 7b: individual + repetition hybrid (fires per scored card: scoring effect + retrigger) ──
    // cry_iterum: X2 Mult AND +1 retrigger per scored played card (spooky.lua — the "iterum" (again) joker).
    // Both effects go through the individual hook; the repetition routing in dispatchManifest ensures the
    // Retrigger(1) contribution is collected during ctx.repetition=true.
    "j_cry_iterum"    to JokerSpec(individual = { _, _, _ -> Effect.All(listOf(Effect.XMult(2.0), Effect.Retrigger(1))) }),
    // cry_weegaming: +2 retriggers per scored 2 (get_id; Maximized maps pips→10)
    "j_cry_weegaming" to JokerSpec(individual = { _, ctx, c -> if (ctx.rankOf(c) == 2) Effect.Retrigger(2) else Effect.None }),
    // cry_nosound: +3 retriggers per scored 7 (get_id)
    "j_cry_nosound"   to JokerSpec(individual = { _, ctx, c -> if (ctx.rankOf(c) == 7) Effect.Retrigger(3) else Effect.None }),
    // cry_exposed: +2 retriggers per scored non-face card
    "j_cry_exposed"   to JokerSpec(individual = { _, ctx, c -> if (!(c.isFace || ctx.pareidolia)) Effect.Retrigger(2) else Effect.None }),
    // cry_lightupthenight: X1.5 per scored 2 or 7 (individual; get_id)
    "j_cry_lightupthenight" to JokerSpec(individual = { _, ctx, c ->
        val r = ctx.rankOf(c); if (r == 2 || r == 7) Effect.XMult(1.5) else Effect.None
    }),

    // ── batch 7c: joker_main ctx-reads — plain ctx fields, no j.field ────────────────────────────────
    // cry_triplet_rhythm: X3 when scoring hand has exactly 3 cards ranked 3 (get_id; Maximized→10)
    "j_cry_triplet_rhythm" to JokerSpec(jokerMain = { _, ctx ->
        if (ctx.scoringHand.count { ctx.rankOf(it) == 3 } == 3) Effect.XMult(3.0) else Effect.None
    }),
    // cry_jtron: Emult = 1 + count(j_joker on board); no-op when none present
    "j_cry_jtron"     to JokerSpec(jokerMain = { _, ctx ->
        val n = ctx.boardKeys.count { it == "j_joker" }; if (n > 0) Effect.EMult(1.0 + n) else Effect.None
    }),
    // cry_filler: X≈1 when High Card is in the played hand (meme joker; xMultMod = 1.00000000000003)
    "j_cry_filler"    to JokerSpec(jokerMain = { _, ctx -> if (HandType.HIGH_CARD in ctx.pokerHands) Effect.XMult(1.00000000000003) else Effect.None }),
    // cry_nice: +420 Chips when the played hand contains a 6 AND a 9 (any suits; get_id)
    "j_cry_nice"      to JokerSpec(jokerMain = { _, ctx ->
        if (ctx.fullHand.any { ctx.rankOf(it) == 6 } && ctx.fullHand.any { ctx.rankOf(it) == 9 }) Effect.Chips(420.0) else Effect.None
    }),
    // cry_circulus_pistoris: Echip^PI × Emult^PI when exactly 3 hands remain (exotic.lua:886)
    "j_cry_circulus_pistoris" to JokerSpec(jokerMain = { _, ctx ->
        if (ctx.handsLeft == 3) Effect.All(listOf(Effect.EChips(Math.PI), Effect.EMult(Math.PI))) else Effect.None
    }),

    // ── batch 7d: Cryptid custom hand-type jokers (exact scoringName, not containment) ───────────────
    "j_cry_wtf"             to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_CLUSTERFUCK) Effect.XMult(10.0)     else Effect.None }),
    "j_cry_clash"           to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_ULTPAIR)     Effect.XMult(12.0)     else Effect.None }),
    "j_cry_the"             to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_NONE)        Effect.XMult(2.0)      else Effect.None }),
    "j_cry_annihalation"    to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_WHOLEDECK)   Effect.EMult(5.2)      else Effect.None }),
    "j_cry_words_cant_even" to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_WHOLEDECK)   Effect.XMult(52000000.0) else Effect.None }),
    "j_cry_bonkers"         to JokerSpec(jokerMain = { _, ctx -> if (HandType.CRY_BULWARK in ctx.pokerHands)      Effect.Mult(20.0)      else Effect.None }),
    "j_cry_fuckedup"        to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_CLUSTERFUCK) Effect.Mult(37.0)      else Effect.None }),
    "j_cry_foolhardy"       to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_ULTPAIR)     Effect.Mult(42.0)      else Effect.None }),
    "j_cry_undefined"       to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_NONE)        Effect.Mult(5.0)       else Effect.None }),
    "j_cry_adroit"          to JokerSpec(jokerMain = { _, ctx -> if (HandType.CRY_BULWARK in ctx.pokerHands)      Effect.Chips(170.0)    else Effect.None }),
    "j_cry_penetrating"     to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_CLUSTERFUCK) Effect.Chips(270.0)    else Effect.None }),
    "j_cry_treacherous"     to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_ULTPAIR)     Effect.Chips(300.0)    else Effect.None }),
    "j_cry_nebulous"        to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_NONE)        Effect.Chips(30.0)     else Effect.None }),
    "j_cry_many_lost_minds" to JokerSpec(jokerMain = { _, ctx -> if (ctx.scoringName == HandType.CRY_WHOLEDECK)   Effect.Chips(8.0658175e67) else Effect.None }),

    // ── batch 8: jokerMain accumulator-readers (RunScreen/before-pass sets j.field; manifest reads snapshot) ─
    // ── 8a: j.x accumulator → XMult ─────────────────────────────────────────────────────────────────────────
    // cry_spy: Xmult = j.x (seeded 0.5 in initialFJoker, changes via run events); always fires (unconditional)
    "j_cry_spy"         to JokerSpec(initialState = FJokerState(x = 0.5), jokerMain = { s, _ -> Effect.XMult(s.x) }),
    // cry_m: Xmult = j.x (starts 1.0, +13 each Jolly sold — m.lua selling_card: soldKey=="j_jolly" → x+=13).
    // Sold reducer replaces RunScreen.sell() inline. jokerMain guard prevents identity effect at start.
    "j_cry_m"           to JokerSpec(
        reduce    = { s, e -> if (e is GameEvent.Sold && e.soldKey == "j_jolly") s.copy(x = s.x + 13.0) else s },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),
    // cry_longboi: Xmult = j.x (= G.GAME.monstermult at equip, grows end_of_round); same guard
    "j_cry_longboi"     to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    // cry_googol_play: Xmult = j.x (pre-resolved: 1e100 on success, 1.0 on fail); guard prevents identity
    "j_cry_googol_play" to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    // cry_unjust_dagger, jimball, pizza_slice, wheelhope, cut, python: all j.x > 1.0 → XMult
    "j_cry_unjust_dagger" to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    // j_cry_jimball: +0.15 Xmult while THIS hand type is the strict most-played (no other type
    // has been played as often); x resets to 1.0 if another type ties or beats it
    // (misc_joker.lua:1623-1656). HandScored.handPlays is the post-increment snapshot so
    // handPlays[handType] is the updated count. handType NONE / CRY_NONE are skipped (Lua
    // only fires in context.after with a real scoring_name). jokerMain: if x > 1 → XMult(x).
    "j_cry_jimball" to JokerSpec(
        reduce    = { s, e ->
            if (e is GameEvent.HandScored && e.handType != HandType.NONE && e.handType != HandType.CRY_NONE) {
                val thisCount = e.handPlays[e.handType] ?: 0
                val beaten = e.handPlays.any { (h, n) -> h != e.handType && n >= thisCount }
                if (beaten) s.copy(x = 1.0) else s.copy(x = s.x + 0.15)
            } else s
        },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),
    "j_cry_pizza_slice"   to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    "j_cry_wheelhope"     to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    "j_cry_cut"           to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    "j_cry_python"        to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),

    // ── 8b: j.x accumulator → EMult ──────────────────────────────────────────────────────────────────────────
    // cry_exponentia: Emult = j.x (base 1.0, +0.03 each time any xmult fires during scoring; grows per-hand)
    "j_cry_exponentia"     to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.EMult(s.x) else Effect.None }),
    // cry_primus: Emult = j.x (base 1.01, +0.17 in before-pass when any prime-rank card played)
    "j_cry_primus"         to JokerSpec(initialState = FJokerState(x = 1.01), jokerMain = { s, _ -> if (s.x > 1.0) Effect.EMult(s.x) else Effect.None }),
    // stella_mortis, formidiulosus, starfruit: Emult = j.x (all j.x > 1.0 guard, different accumulation paths)
    "j_cry_stella_mortis"  to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.EMult(s.x) else Effect.None }),
    "j_cry_formidiulosus"  to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.EMult(s.x) else Effect.None }),
    "j_cry_starfruit"      to JokerSpec(initialState = FJokerState(x = 2.0), jokerMain = { s, _ -> if (s.x > 1.0) Effect.EMult(s.x) else Effect.None }),

    // ── 8c: j.n accumulator → EMult ──────────────────────────────────────────────────────────────────────────
    // cry_happyhouse: Emult=4 after 114 hands played (misc_joker.lua:155-198).
    // check counter (j.chips) increments in context.before; once check > trigger(114), gate j.n=1
    // opens permanently and joker_main fires emult=4. Reducer migrated from RunScreen per-hand loop.
    "j_cry_happyhouse" to JokerSpec(
        reduce    = { s, e -> if (e is GameEvent.BeforeHand) {
            val newChips = s.chips + 1.0
            if (newChips > 114.0) s.copy(chips = newChips, n = 1) else s.copy(chips = newChips)
        } else s },
        jokerMain = { s, _ -> if (s.n > 0) Effect.EMult(4.0) else Effect.None },
    ),

    // ── 8d: j.mult accumulator → Mult ────────────────────────────────────────────────────────────────────────
    // Vanilla scaling jokers: j.mult set by run events; before-pass loops in Score.kt stay for zooble.
    // j_swashbuckler: NOT migrated — initialFJoker seed is dynamic (swashSellSum runtime param).
    // A static JokerSpec.initialState cannot capture it; stays legacy until a dynamic-seed mechanism is added.
    // j_red_card: j.mult += per pack-open skip (epic.lua) — RunScreen handles event
    "j_red_card"        to JokerSpec(jokerMain = { s, _ -> if (s.mult > 0.0) Effect.Mult(s.mult) else Effect.None }),
    // j_flash (Flash Card): +2 Mult each shop reroll (extra=2, context.reroll_shop — RunScreen.reroll
    // accrues j.mult); joker_main adds the accumulated mult when > 0 (card.lua:4559).
    "j_flash"           to JokerSpec(jokerMain = { s, _ -> if (s.mult > 0.0) Effect.Mult(s.mult) else Effect.None }),
    // j_popcorn: starts mult=20 (config), −4 per ROUND (extra=4, context.end_of_round); self-destructs
    // at 0 (card.lua k_eaten_ex — RunScreen's cashOut handles the removal). NOT per-hand (vanilla decays
    // only at end of round).
    "j_popcorn"         to JokerSpec(
        initialState = FJokerState(mult = 20.0),
        reduce = { s, e -> if (e is GameEvent.RoundEnd) s.copy(mult = maxOf(0.0, s.mult - 4.0)) else s },
        jokerMain = { s, _ -> if (s.mult > 0.0) Effect.Mult(s.mult) else Effect.None },
    ),
    // j_turtle_bean: +5 hand size (n = h_size, config), −1 per ROUND (h_mod=1, context.end_of_round);
    // self-destructs (k_eaten_ex) when h_size would hit 0. No scoring output — RunScreen's startRound
    // reads n for the hand-size bonus and cashOut prunes it at n<=0 (card.lua:3515).
    "j_turtle_bean"     to JokerSpec(
        initialState = FJokerState(n = 5),
        reduce = { s, e -> if (e is GameEvent.RoundEnd) s.copy(n = s.n - 1) else s },
    ),
    // j_selzer (Seltzer): retriggers EVERY scored card once, for the next 10 hands, then self-destructs
    // (card.lua:3963 repetitions=1, unconditional; n = hands remaining, RunScreen decrements + prunes).
    "j_selzer"          to JokerSpec(initialState = FJokerState(n = 10), individual = { _, _, _ -> Effect.Retrigger(1) }),
    // j_rocket: end-of-round $ payout (n = dollars, starts 1); increases by $2 when a Boss Blind is
    // defeated (config {dollars=1, increase=2}). No scoring output — RunScreen pays n in buildCashOut and
    // bumps n on a boss win (before the payout, so the boss round itself pays the increased amount).
    "j_rocket"          to JokerSpec(initialState = FJokerState(n = 1)),
    // cry-gold Joker: x = percent (starts 0), grows +2 per scored Gold-enhanced card (epic.lua individual
    // + m_gold). No scoring output — RunScreen grows x in scoreBank and pays floor(percent% × money) in
    // buildCashOut (calc_dollar_bonus = floor(0.01·percent·dollars)).
    "j_cry_goldjoker"        to JokerSpec(initialState = FJokerState(x = 0.0)),
    // cry-Compound Interest: x = percent (starts 12), pays floor(percent% × money) at round end then grows
    // +3 (percent_mod) when it pays (calc_dollar_bonus, only when dollars > 0). RunScreen does both inline.
    "j_cry_compound_interest" to JokerSpec(initialState = FJokerState(x = 12.0)),
    // cry-redbloon: n = rounds_remaining (starts 2). RoundEnd reducer decrements n; RunScreen pays $20 in
    // buildCashOut the round n reaches 0 (predicted at n==1) and self-destructs it (n<=0). No scoring output.
    "j_cry_redbloon"         to JokerSpec(initialState = FJokerState(n = 2),
        reduce = { s, e -> if (e is GameEvent.RoundEnd) s.copy(n = s.n - 1) else s }),
    // cry-Morse: x = money payout (starts 1), grows +2 (bonus) each time a Joker WITH an edition is sold
    // (RunScreen.sell). Pays x at round end (calc_dollar_bonus, when x > 0) via buildCashOut. No scoring.
    "j_cry_morse"            to JokerSpec(initialState = FJokerState(x = 1.0)),
    // cry_zooble: j.mult += distinct-rank-count in before-pass (Score.kt line 612); no individual hook
    "j_cry_zooble"      to JokerSpec(jokerMain = { s, _ -> if (s.mult > 0.0) Effect.Mult(s.mult) else Effect.None }),
    // cry_poor_joker: j.mult += mult_mod(4) each rental (non-scoring, RunScreen event)
    "j_cry_poor_joker"  to JokerSpec(jokerMain = { s, _ -> if (s.mult > 0.0) Effect.Mult(s.mult) else Effect.None }),
    // cry_foodm: j.mult=40 default, decrements per round, replenished by selling jolly jokers; self-destructs at 0
    "j_cry_foodm"       to JokerSpec(jokerMain = { s, _ -> if (s.mult > 0.0) Effect.Mult(s.mult) else Effect.None }),
    // cry_busdriver: j.mult pre-resolved each hand (+50 success, -50 fail); fires when non-zero
    "j_cry_busdriver"   to JokerSpec(initialState = FJokerState(n = 4), jokerMain = { s, _ -> if (s.mult != 0.0) Effect.Mult(s.mult) else Effect.None }),

    // ── 8e: j.chips accumulator → Chips ──────────────────────────────────────────────────────────────────────
    // cry_clicked_cookie: j.chips seeded 200, −1 per press; unconditional read
    "j_cry_clicked_cookie"  to JokerSpec(initialState = FJokerState(chips = 200.0), jokerMain = { s, _ -> Effect.Chips(s.chips) }),
    // cry_monkey_dagger: j.chips += 10*sell_cost of left joker (destroyed at setting_blind); fires when non-zero
    "j_cry_monkey_dagger"   to JokerSpec(jokerMain = { s, _ -> if (s.chips != 0.0) Effect.Chips(s.chips) else Effect.None }),
    // cry_fspinner: j.chips += 6 per context.before when another hand type has same play count; fires when non-zero
    "j_cry_fspinner"        to JokerSpec(jokerMain = { s, _ -> if (s.chips != 0.0) Effect.Chips(s.chips) else Effect.None }),
    // cry_membershipcardtwo: j.chips = pre-computed bonus (epic.lua); fires when non-zero
    "j_cry_membershipcardtwo" to JokerSpec(initialState = FJokerState(chips = 38598.0), jokerMain = { s, _ -> if (s.chips != 0.0) Effect.Chips(s.chips) else Effect.None }),

    // ── batch 9: remaining jokerMain accumulator-readers (j.x > 1.0 group) + ctx-read jokers ─────────────
    // ── 9a: j.x > 1.0 → XMult (the big Xmult group; RunScreen grows j.x per event) ───────────────────────
    // j_ramen: X(j.x) Mult; starts x=2.0, -0.01 per discarded card; self-destructs at x≤1.0
    // j_ramen: X(j.x) Mult; starts x=2.0, depletes -0.01 per discarded card (run loop self-destructs at x<=1).
    "j_ramen"              to JokerSpec(
        initialState = FJokerState(x = 2.0),
        reduce = { s, e -> if (e is GameEvent.Discarded) s.copy(x = maxOf(1.0, s.x - 0.01 * e.cards.size)) else s },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),
    // j_campfire: X(j.x) Mult; starts x=1.0, +0.25 per joker sold; resets to x=1.0 at end of round.
    // (Vanilla game.lua: campfire resets config.extra.Xmult=1 in end_of_round context.)
    "j_campfire"           to JokerSpec(
        reduce = { s, e -> when (e) {
            is GameEvent.Sold     -> s.copy(x = s.x + 0.25)
            is GameEvent.RoundEnd -> s.copy(x = 1.0)
            else                  -> s
        }},
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),
    // j_obelisk: X(j.x) Mult; x grows when the played hand type isn't the current top-played hand
    "j_obelisk"            to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    // cry_paved_joker: X(j.x) Mult; x grows when any perishable joker expires
    "j_cry_paved_joker"    to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    // cry_membershipcard: X(j.x) Mult; j.x = 0.1 * CRYPTID_MEMBER_COUNT (3859.8) — static at acquisition
    "j_cry_membershipcard" to JokerSpec(initialState = FJokerState(x = 3859.8), jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    // cry_dropshot: X(j.x) Mult; x grows per before-pass by non-scoring hand cards of a random suit
    "j_cry_dropshot"       to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    // cry_chili_pepper: X(j.x) Mult; x grows end-of-round; self-destructs when perishable countdown hits 0
    // (misc_joker.lua:1119-1192). j.x = Xmult accumulator (starts 1.0); j.n = rounds_remaining (init 8).
    // RoundEnd reducer: x += 0.5 (Xmult_mod), n -= 1 floored at 0. Self-destruct: RunScreen removes when n==0.
    "j_cry_chili_pepper"   to JokerSpec(
        initialState = FJokerState(n = 8),
        reduce = { s, e -> if (e is GameEvent.RoundEnd) s.copy(x = s.x + 0.5, n = maxOf(0, s.n - 1)) else s },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),
    // cry_mondrian: X(j.x) Mult; x grows end-of-round when no discard was used that round
    // (misc_joker.lua:3228-3246). RoundEnd reducer: +0.25 only when discardsUsed == 0.
    "j_cry_mondrian"       to JokerSpec(
        reduce = { s, e -> if (e is GameEvent.RoundEnd && e.discardsUsed == 0) s.copy(x = s.x + 0.25) else s },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),
    // cry_fading_joker: X(j.x) Mult; x grows when this perishable joker expires (before the debuff takes effect)
    "j_cry_fading_joker"   to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    // cry_keychange: X(j.x) Mult; x grows when a hand type is played for the first time this round; resets end-of-round
    "j_cry_keychange"      to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    // cry_verisimile: X(j.x) Mult; x grows per pseudorandom_result event
    "j_cry_verisimile"     to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    // cry_duplicare: X(j.x) Mult; x grows per played card (post_trigger / pendingSel.size per before-pass)
    "j_cry_duplicare"      to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),
    // cry_clockwork: 4 effects (see Score.kt L269); this entry covers effect 1: X(j.x) Mult at joker_main
    // (Effects 2/3: held Steel-card Xmult stays legacy for now; Effect 4 in held block stays legacy)
    "j_cry_clockwork"      to JokerSpec(jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None }),

    // ── 9b: ctx-read jokerMain (no j.field dependency) ──────────────────────────────────────────────────
    // j_flower_pot: X3 when ALL four suits appear in the scoring hand (bypass_debuff path counts debuffed cards' suits)
    "j_flower_pot"     to JokerSpec(jokerMain = { _, ctx ->
        if (Suit.values().all { s -> ctx.scoringHand.any { it.isSuit(s, ctx.smeared) } }) Effect.XMult(3.0) else Effect.None
    }),
    // j_seeing_double: X2 when at least one Club AND at least one non-Club non-Stone non-debuffed card score
    "j_seeing_double"  to JokerSpec(jokerMain = { _, ctx ->
        val nd = ctx.scoringHand.filter { it.suit != ctx.debuffSuit }
        val club  = nd.any { it.isSuit(Suit.C, ctx.smeared) }
        val other = nd.any { it.enhancement != Enhancement.STONE && !it.isSuit(Suit.C, ctx.smeared) }
        if (club && other) Effect.XMult(2.0) else Effect.None
    }),
    // cry_thalia: XMult = C(n,2) where n = distinct rarities among board jokers (n=1→X0 no-op; n=2→X1; n=3→X3; n=4→X6; n=5→X10)
    "j_cry_thalia"     to JokerSpec(jokerMain = { _, ctx ->
        val n = ctx.board.map { it.rarity }.filter { it > 0 }.toSet().size
        val bonus = n * (n - 1) / 2
        if (bonus >= 1) Effect.XMult(bonus.toDouble()) else Effect.None
    }),
    // cry_blacklist: NOT migrated — seed is randomly chosen at acquisition ((2..14).random() in initialFJoker).
    // The static JokerSpec.initialState can't represent this; stays legacy until dynamic-seed support is added.

    // ── 9c: individual-hook ctx-reads (no j.field mutation) ─────────────────────────────────────────────
    // j_photograph: X2 Mult per first face card scored (debuff-aware; excludes suit-debuffed + face-debuffed)
    "j_photograph"     to JokerSpec(individual = { _, ctx, c ->
        val faceOk = (c.isFace || ctx.pareidolia) && c.suit != ctx.debuffSuit && !(ctx.debuffFace && (c.isFace || ctx.pareidolia))
        val firstFace = ctx.scoringHand.firstOrNull {
            (it.isFace || ctx.pareidolia) && it.suit != ctx.debuffSuit && !(ctx.debuffFace && (it.isFace || ctx.pareidolia))
        }
        if (faceOk && firstFace === c) Effect.XMult(2.0) else Effect.None
    }),
    // cry_caramel: X(j.x) Mult per scored played card (j.x=1.75 default; self-destructs when x drops to 1 each end-of-round)
    "j_cry_caramel"    to JokerSpec(
        initialState = FJokerState(x = 1.75, n = 11),   // x=1.75, n=11 (end-of-round countdown)
        individual = { s, _, _ -> if (s.x >= 1.0) Effect.XMult(s.x) else Effect.None },
    ),

    // ── batch 10: remaining "other" jokers + two-phase (before-pass sets field, jokerMain reads) ────────
    // ── 10a: individual retrigger reads ────────────────────────────────────────────────────────────────────
    // cry_mstack: +j.n retriggers per scored played card; j.n=1 default, grows per jolly-type sold.
    // sell_req=3: every 3 Jolly sells → n+=1; j.chips repurposed as the sell-progress counter (0-2).
    // Sold reducer replaces RunScreen.sell() inline (m.lua selling_card: sell_count%sell_req==0 → n+=1).
    "j_cry_mstack"     to JokerSpec(
        initialState = FJokerState(n = 1),
        reduce = { s, e ->
            if (e is GameEvent.Sold && e.soldKey == "j_jolly") {
                if (s.chips + 1.0 >= 3.0) s.copy(n = s.n + 1, chips = 0.0) else s.copy(chips = s.chips + 1.0)
            } else s
        },
        // Lua caps the emitted repetitions at immutable.max_retriggers=40 (m.lua: math.min(retriggers, 40)).
        individual = { s, ctx, _ -> if (ctx.cardarea == "play") Effect.Retrigger(minOf(s.n, 40)) else Effect.None },
    ),

    // ── 10b: jokerMain XMult from before-pass-set j.n + static j.x ─────────────────────────────────────
    // cry_biggestm: X(j.x=7.0) when j.n>0 (Score.kt before-pass sets j.n=1 when hand is PAIR; stays until end-of-round reset).
    // RoundEnd reducer resets j.n=0 (m.lua:1437-1451, context.end_of_round → check=false). RunScreen.cashOut() inline removed.
    "j_cry_biggestm"   to JokerSpec(
        initialState = FJokerState(x = 7.0),
        reduce    = { s, e -> if (e is GameEvent.RoundEnd) s.copy(n = 0) else s },
        jokerMain = { s, _ -> if (s.n > 0) Effect.XMult(s.x) else Effect.None },
    ),

    // cry_jollysus: spawns a random Joker when any Joker is sold (selling_card context, m.lua:41-65).
    // Spawn is once-per-round: j.n=1 = armed, j.n=0 = spent. Re-armed at end_of_round (m.lua:31-39).
    // The spawn action (createRandomJoker()) stays in RunScreen.sell() — it mutates `owned`, can't be a reducer.
    // RoundEnd reducer re-arms the spawn flag (n=1). RunScreen.cashOut() inline removed.
    "j_cry_jollysus"   to JokerSpec(
        initialState = FJokerState(n = 1),
        reduce    = { s, e -> if (e is GameEvent.RoundEnd) s.copy(n = 1) else s },
    ),

    // ── 10c: jokerMain XChips from RunScreen-grown j.xc ─────────────────────────────────────────────────
    // cry_spaceglobe: XChip = j.xc (starts 1.0, +0.2 each time target hand type played; seeded n=HandType.HIGH_CARD ordinal)
    "j_cry_spaceglobe" to JokerSpec(
        initialState = FJokerState(n = HandType.HIGH_CARD.ordinal),
        jokerMain = { s, _ -> if (s.xc > 1.0) Effect.XChips(s.xc) else Effect.None },
    ),
    // cry_pirate_dagger: XChip = j.xc (+0.25 * sell_cost of joker to the right at setting_blind, that joker destroyed)
    "j_cry_pirate_dagger" to JokerSpec(jokerMain = { s, _ -> if (s.xc > 1.0) Effect.XChips(s.xc) else Effect.None }),

    // ── batch 11: identity-guarded hooks (use ctx.selfJoker for j !== rj / j !== oj checks) ─────────────
    // ── 11a: retrigger-joker-check (ctx.jokerRetriggerCheck=true) — vote to retrigger ctx.retriggeredJoker ─
    // cry_chad: retrigger the LEFTMOST board joker j.n(=2) times — but NOT chad itself (j !== rj).
    // ctx.board.firstOrNull() is the leftmost joker; cry_chad must not be the leftmost to fire (self-exclusion).
    "j_cry_chad"       to JokerSpec(
        initialState = FJokerState(n = 2),
        retrigger = { s, ctx ->
            ctx.retriggeredJoker?.let { rj ->
                if (ctx.selfJoker !== rj && rj === ctx.board.firstOrNull() && s.n > 0) Effect.Retrigger(s.n) else Effect.None
            } ?: Effect.None
        },
    ),
    // cry_loopy: retrigger ALL other board jokers min(j.n, 40) times (j.n = Jolly Jokers sold, starts 0).
    // Sold reducer replaces RunScreen.sell() inline (m.lua selling_card: soldKey=="j_jolly" → n+=1).
    "j_cry_loopy"      to JokerSpec(
        reduce    = { s, e -> if (e is GameEvent.Sold && e.soldKey == "j_jolly") s.copy(n = s.n + 1) else s },
        retrigger = { s, ctx ->
            if (ctx.selfJoker !== ctx.retriggeredJoker && s.n > 0) Effect.Retrigger(minOf(s.n, 40)) else Effect.None
        },
    ),
    // cry_spectrogram: retrigger the RIGHTMOST board joker j.n times (j.n = Echo-enhanced cards scored this hand).
    // j.n is set once per hand in Score.kt's before-pass (mirrors epic.lua:2047-2053: context.before iterates
    // context.scoring_hand once, counting Echo-enhanced cards before any scoring begins). It must NOT be
    // accumulated via perCard — the perCard hook runs inside repeat(reps), so a retriggered Echo card would
    // be counted once per retrigger instance, inflating j.n and causing extra joker retriggers.
    // Score.kt before-pass also resets j.n = 0 each hand, then sets it to the Echo count.
    "j_cry_spectrogram" to JokerSpec(
        retrigger = { s, ctx ->
            ctx.retriggeredJoker?.let { rj ->
                if (rj === ctx.board.lastOrNull() && ctx.selfJoker !== rj && s.n > 0) Effect.Retrigger(s.n) else Effect.None
            } ?: Effect.None
        },
    ),
    // cry_boredom: pseudorandom 1-retrigger of any other joker (1-in-2 odds; pre-resolved by run loop).
    // Run loop sets j.n=1 on success, j.n=0 on fail (reset each hand). Self-excluded.
    "j_cry_boredom"    to JokerSpec(retrigger = { s, ctx ->
        if (ctx.selfJoker !== ctx.retriggeredJoker && s.n > 0) Effect.Retrigger(1) else Effect.None
    }),

    // ── 11b: other-joker with self-exclusion (ctx.selfJoker !== other) ──────────────────────────────────
    // cry_circus: XMult based on other joker's rarity; excludes self (oj !== j guard via ctx.selfJoker).
    // Rarity: 1=Common, 2=Uncommon, 3=Rare, 4=Legendary, 5=cry_epic, 6=cry_exotic.
    "j_cry_circus"     to JokerSpec(otherJoker = { _, ctx, other ->
        if (ctx.selfJoker !== other) {
            val xm = when (other.rarity) { 3 -> 2.0; 5 -> 3.0; 4 -> 4.0; 6 -> 20.0; else -> 1.0 }
            if (xm > 1.0) Effect.XMult(xm) else Effect.None
        } else Effect.None
    }),
    // cry_mprime: Emult^j.x (base 1.05) per Jolly-type or M-pool joker (m.lua:1534; is_jolly()).
    // j.x seeded 1.05; grows via run events. No self-exclusion needed (mprime is not in isJolly or CRY_M_POOL).
    "j_cry_mprime"     to JokerSpec(
        initialState = FJokerState(x = 1.05),
        otherJoker = { s, _, other ->
            val isJolly = other.isJolly() || other.key in Score.CRY_M_POOL
            if (isJolly && s.x > 1.0) Effect.EMult(s.x) else Effect.None
        },
    ),

    // ── batch 12: two-phase jokers (perCard accumulates state; jokerMain reads it) ─────────────────────
    // perCard runs during the individual pass (ctx.individual=true), updates live FJoker state via restore().
    // jokerMain fires after all per-card scoring and reads the accumulated state from j.snapshot().

    // cry_krustytheclown: j.x += 0.02 per scored played card; jokerMain X(j.x) Mult (starts at 1.0, guard fires once grown).
    "j_cry_krustytheclown" to JokerSpec(
        perCard   = { s, _, _ -> s.copy(x = s.x + 0.02) },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),
    // cry_antennastoheaven: j.xc += 0.1 per scored 4 or 7 (get_id; Maximized-aware via ctx.rankOf).
    // jokerMain X(j.xc) Chips when xc > 1.0.
    "j_cry_antennastoheaven" to JokerSpec(
        perCard   = { s, ctx, c -> val r = ctx.rankOf(c); if (r == 4 || r == 7) s.copy(xc = s.xc + 0.1) else s },
        jokerMain = { s, _ -> if (s.xc > 1.0) Effect.XChips(s.xc) else Effect.None },
    ),
    // cry_wee_fib: j.mult += 3 per scored Fibonacci card (A,2,3,5,8; Maximized-aware via ctx.rankOf).
    // jokerMain +Mult = j.mult when > 0.
    "j_cry_wee_fib"        to JokerSpec(
        perCard   = { s, ctx, c ->
            val r = ctx.rankOf(c)
            if (r == 14 || r == 2 || r == 3 || r == 5 || r == 8) s.copy(mult = s.mult + 3.0) else s
        },
        jokerMain = { s, _ -> if (s.mult > 0.0) Effect.Mult(s.mult) else Effect.None },
    ),
    // cry_facile: count every scored-card pass (incl. retrigger reps) in j.n (= check2 in Lua).
    // jokerMain: EMult=3 only when j.n <= 10 (exotic.lua:1002-1013). HandScored reducer resets j.n to 0.
    "j_cry_facile"         to JokerSpec(
        perCard   = { s, _, _ -> s.copy(n = s.n + 1) },
        jokerMain = { s, _ -> if (s.n <= 10) Effect.EMult(3.0) else Effect.None },
        reduce    = { s, e -> if (e is GameEvent.HandScored) s.copy(n = 0) else s },
    ),

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

    // j_hiker: permanently adds +extra(5) Chips to each scored played card (card.lua:3606-3608,
    // context.individual cardarea==G.play). The bonus sticks to the card across hands via
    // PlayingCard.permaBonus (added to chipBonus() in Score.kt so next-hand plays include it).
    // Timing: Lua increments perma_bonus AFTER eval_card runs for that card (joker hooks fire
    // after card eval), so the current hand uses the OLD permaBonus value and the +5 appears
    // from the NEXT hand. The permaBonus is already in chipBonus(c) via the existing Deck entry;
    // individual returns None (no additional chips this hand). Score.kt fires onPermaBonusGained
    // callback to update Deck.all for future hands. blueprint_compat=true.
    "j_hiker" to JokerSpec(
        grantsPermaBonusPerCard = 5,
    ),
    // cry_cursor: +j.chips (+=8 per card purchased; run-loop sets it via onCardBought()).
    "j_cry_cursor"    to JokerSpec(jokerMain = { s, _ -> Effect.chipsOrNone(s.chips) }),
    // cry_crustulum: +j.chips (+=4 per reroll in the shop; run-loop sets it via reroll()).
    "j_cry_crustulum" to JokerSpec(jokerMain = { s, _ -> Effect.chipsOrNone(s.chips) }),

    // ── Sold-event accumulator: eternalflame scales Xmult per joker sold ──
    // Lua (misc_joker.lua:1357-1360): fires when `sell_cost >= 2 OR gameset ~= "modest"`. The rebuild targets the
    // non-modest gameset, where the OR is always true → eternalflame scales on EVERY sale regardless of sell value.
    "j_cry_eternalflame" to JokerSpec(
        reduce = { s, e -> if (e is GameEvent.Sold) s.copy(x = s.x + 0.1) else s },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),

    // ── CardAdded-event accumulator ────────────────────────────────────────────────────────────────
    // j_hologram: +0.25 Xmult per playing card added to the deck (card.lua:2979-2990).
    // Lua: context.playing_card_added, self.ability.x_mult += self.ability.extra * #context.cards.
    // config = {extra=0.25, Xmult=1}; x starts at 1.0 (FJokerState default). No self-destruct,
    // no reset. perishable_compat=false (game.lua:464). Blueprint excluded in Lua (not context.blueprint);
    // the Kotlin event fires only on real deck adds — no blueprint path exists here.
    // Dispatch site: RunScreen.kt pickPackItem():CardAdded (Standard pack pick). Future sites:
    // Certificate (first_hand_drawn), Familiar/Grim/Incantation (spectral use), Marble Joker
    // (setting_blind), DNA (first hand 1-card play) — all unimplemented in Kotlin today.
    "j_hologram" to JokerSpec(
        reduce    = { s, e -> if (e is GameEvent.CardAdded) s.copy(x = s.x + 0.25 * e.count) else s },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),

    // ── HandScored-event accumulator: obelisk ──────────────────────────────────────────────────────
    // j_obelisk: +0.2 Xmult per hand NOT of the most-played type; resets x→1.0 if the played hand
    // becomes the new sole top-played (card.lua:4105-4130, context.after).
    // Lua timing: G.GAME.hands[...].played++ happens BEFORE context.after fires, so HandScored.handPlays
    // already includes the current hand (matching Lua). Reset condition: no OTHER hand type has
    // played >= this hand's count → this hand IS the new most-played → reset to 1.0.
    // No self-destruct, no blueprint. Removed from Score.kt legacy xMult when-group (j.x reader).
    "j_obelisk" to JokerSpec(
        // card.lua:3543-3561 — context.BEFORE, not after (parity audit): hands[..].played is
        // already incremented when the pass runs, so the growth/reset counts for THIS hand.
        reduce = { s, e ->
            if (e is GameEvent.BeforeHand && e.ctx.handTypePlays.isNotEmpty()) {
                val thisCount = e.ctx.handTypePlays[e.ctx.scoringName] ?: 0
                val anotherLeads = e.ctx.handTypePlays.any { (h, n) -> h != e.ctx.scoringName && n >= thisCount }
                if (anotherLeads) s.copy(x = s.x + 0.2) else s.copy(x = 1.0)
            } else s
        },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),

    // ── jokerMain ctx-read: loyalty_card ─────────────────────────────────────────────────────────
    // j_loyalty_card: X4 every 6th hand in a repeating cycle (every+1=6). The cycle index is
    // (every-1-(handsPlayedTotal-handsPlayedAtCreate)) % (every+1) where every=5 (card.lua:4175-4194).
    // Fires when loyalty_remaining == every (== 5). NOT an accumulator — jokerMain recomputes each hand.
    // j.n stores handsPlayedAtCreate (set in RunScreen.initialFJoker/owned.add sites at equip time).
    // Requires ctx.totalHandsPlayed (added to Sctx / score() signature). blueprint_compat=true.
    // Removed from Score.kt legacy xMult when-group.
    "j_loyalty_card" to JokerSpec(
        initialState = FJokerState(x = 1.0),   // x is unused by the reducer; jokerMain reads ctx
        jokerMain = { _, ctx ->
            val every = 5
            val elapsed = ctx.totalHandsPlayed - ctx.handsPlayedAtCreate
            // Lua's % is floored (always non-negative); Kotlin's % is remainder (can be negative).
            // Use .mod() for the non-negative modulo equivalent to Lua's x % y.
            val loyaltyRemaining = (every - 1 - elapsed).mod(every + 1)
            if (loyaltyRemaining == every) Effect.XMult(4.0) else Effect.None
        },
    ),

    // ── BlindSkipped-event accumulator ────────────────────────────────────────────────────────────
    // j_throwback: +0.25 Xmult per blind skipped this run (card.lua:4708-4709, game.lua config.extra=0.25).
    // Lua recomputes x_mult = 1 + G.GAME.skips * extra in update() every frame, making it a live
    // read from a global counter. Kotlin mirrors this with x += 0.25 per BlindSkipped event
    // dispatched from RunScreen.skipBlind(). Equivalent because x starts at 1.0 and each skip
    // adds exactly 0.25, so the accumulated value equals 1 + skips*0.25 at all times.
    // No self-destruct, no reset. perishable_compat=true (game.lua). Blueprint excluded in Lua
    // (context.skip_blind path is display-only; scoring reads self.ability.x_mult directly).
    "j_throwback" to JokerSpec(
        reduce    = { s, e -> if (e is GameEvent.BlindSkipped) s.copy(x = s.x + 0.25) else s },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),

    // ── BeforeHand-event accumulator ─────────────────────────────────────────────────────────────
    // j_cry_whip: +0.5 Xmult when the played hand holds a 2 AND a 7 of different suits
    // (misc_joker.lua:585-661, context.before). Suit resolution honours WILD enhancement
    // (counts as all suits) and Smeared Joker (red H↔D, black S↔C collide). Uses get_id()
    // in Lua so Maximized remapping applies (rankOf). No self-destruct, no reset, no blueprint.
    // Previously accumulated in the Score.kt before-pass loop (j.x += 0.5 inline); now
    // migrated to BeforeHand so the MANIFEST early-return at calcJoker handles jokerMain.
    "j_cry_whip" to JokerSpec(
        reduce = { s, e ->
            if (e is GameEvent.BeforeHand) {
                val ctx = e.ctx
                fun suitsOf(id: Int) = ctx.fullHand.filter { ctx.rankOf(it) == id }
                    .flatMap { when {
                        it.enhancement == Enhancement.WILD -> Suit.values().toList()
                        ctx.smeared && (it.suit == Suit.H || it.suit == Suit.D) -> listOf(Suit.H, Suit.D)
                        ctx.smeared && (it.suit == Suit.S || it.suit == Suit.C) -> listOf(Suit.S, Suit.C)
                        else -> listOf(it.suit)
                    } }.toSet()
                val ts = suitsOf(2); val ss = suitsOf(7)
                val triggered = ts.isNotEmpty() && ss.isNotEmpty() &&
                    (ts.size > 1 || ss.size > 1 || ts.first() != ss.first())
                if (triggered) s.copy(x = s.x + 0.5) else s
            } else s
        },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),

    // ── Pure jokerMain readers (no run-loop event; accumulation managed by RunScreen when-block) ──

    // j_cry_membershipcard: Xmult_mod(0.1) × Cryptid.member_count (epic.lua:7893-7901, context.joker_main).
    // j.x = 0.1 * CRYPTID_MEMBER_COUNT is set at construction in initialFJoker; it never changes at
    // runtime. Fires when x > 1 (member count > 10). blueprint_compat=true; no accumulation event.
    "j_cry_membershipcard" to JokerSpec(
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),

    // j_cry_verisimile: Xmult += denominator each pseudorandom_result hit (exotic.lua:1214-1253).
    // Accumulation is in RunScreen (no Kotlin event for pseudorandom_result yet). jokerMain always
    // fires (Lua: return { xmult = ... } with no guard). Starting x=1.0; no guard needed because
    // XMult(1.0) is a mathematical no-op and the Kotlin engine suppresses the exponentia side-effect
    // when xMult==1.0 (apply() guard: if fx.xMult != 1.0). blueprint_compat=true.
    "j_cry_verisimile" to JokerSpec(
        jokerMain = { s, _ -> Effect.XMult(s.x) },
    ),

    // j_cry_duplicare: Xmult += Xmult_mod(1) per played card this hand (exotic.lua:1288-1318,
    // context.individual cardarea==G.play OR context.post_trigger). HandScored.playedCount is the
    // number of played cards (pendingSel.size); reducer adds that count to x each hand scored.
    // jokerMain: Xmult > 1 → XMult(j.x). blueprint_compat=true.
    // The post_trigger path (other joker fires) remains unimplemented in Kotlin.
    "j_cry_duplicare" to JokerSpec(
        reduce    = { s, e -> if (e is GameEvent.HandScored) s.copy(x = s.x + e.playedCount.toDouble()) else s },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),

    // ── Mixed: jokerMain + RoundEnd reset ────────────────────────────────────────────────────────

    // j_cry_keychange: +0.25 Xmult each time a new hand type is played for the first time this round
    // (misc_joker.lua:10546-10568, context.before, hands[scoring_name].played_this_round < 2).
    // "First time this round" ≡ handPlays[handType] == 1 post-increment (HandScored carries post-
    // increment snapshot). Resets to xm=1 at end_of_round (Lua line 10564-10566). Accumulation
    // and reset fully migrated from RunScreen per-hand when-block + startRound.
    // jokerMain fires Xmult always (no guard in Lua; XMult(1.0) is a safe no-op in Kotlin engine).
    "j_cry_keychange" to JokerSpec(
        reduce = { s, e -> when (e) {
            is GameEvent.HandScored -> if ((e.handPlays[e.handType] ?: 0) == 1) s.copy(x = s.x + 0.25) else s
            is GameEvent.RoundEnd   -> s.copy(x = 1.0)
            else                    -> s
        }},
        jokerMain = { s, _ -> Effect.XMult(s.x) },
    ),

    // j_cry_clockwork: +0.25 Xmult every 3rd hand played (epic.lua:2198-2216, context.before,
    // immutable counter c2 cycles 0→1→2→0; fires when c2==0). HandScored.totalHandsPlayed carries
    // the post-increment total so (totalHandsPlayed % 3 == 0) matches Lua's every-3rd semantics.
    // jokerMain: always fires (Lua: return { xmult = card.ability.extra.xmult } unconditionally).
    // No reset, no self-destruct. blueprint_compat=true. Steel-retrigger and steel-enhancement side
    // effects remain in Score.kt (context.repetition / context.before) — unimplemented for now.
    "j_cry_clockwork" to JokerSpec(
        reduce    = { s, e -> if (e is GameEvent.HandScored && e.totalHandsPlayed % 3 == 0) s.copy(x = s.x + 0.25) else s },
        jokerMain = { s, _ -> Effect.XMult(s.x) },
    ),

    // ── PerishableExpired accumulator (RunScreen manages the event; jokerMain reads j.x) ─────────

    // j_cry_fading_joker: +1 Xmult each time a perishable joker expires (misc_joker.lua:10298-10316,
    // context.perishable_debuffed → xmult += xmult_mod(1)). Accumulation fires in RunScreen.cashOut()
    // when chili_pepper or caramel expires (j.x += 1.0 per expired perishable). jokerMain always
    // fires (Lua: return { xmult = card.ability.extra.xmult } unconditionally — no guard). The
    // Kotlin legacy gate j.x > 1.0 was WRONG: a freshly acquired fading_joker with x=1.0 never
    // scored in Kotlin but fired XMult(1.0) in Lua (safe no-op). Fixed here: no guard. blueprint_compat=true.
    "j_cry_fading_joker" to JokerSpec(
        jokerMain = { s, _ -> Effect.XMult(s.x) },
    ),

    // j_cry_dropshot: +0.2 Xmult per non-scoring played-hand card of this round's target suit
    // (misc_joker.lua:57-89, context.before). The target suit is chosen at round start via
    // reset_castle_card (overrides.lua:303-316): pseudorandom_element of the deck seeded by ante,
    // stored in G.GAME.current_round.cry_dropshot_card.suit. In Kotlin: RunScreen.dropShotSuit is
    // set in startRound() (seeded by blindIndex) and the accumulation loop at RunScreen:984-986
    // applies j.x += 0.2 * nonScoring.size (held cards of that suit, isSuit honours WILD/Smeared).
    // jokerMain: x > 1 → XMult(x). blueprint_compat=true, perishable_compat=false.
    // Infrastructure was already complete; only the MANIFEST jokerMain spec was missing.
    "j_cry_dropshot" to JokerSpec(
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),

    // j_cry_blacklist: zeros chips AND mult when the blacklisted rank (stored in j.n; default 0 → Ace=14)
    // appears in the played hand (G.play.cards) OR the held hand (G.hand.cards) — spooky.lua:1021-1038.
    // Effect.Nullify sets Fx.nullify=true, which the cascade engine uses to atomically zero both accumulators
    // (not expressible as additive/multiplicative). blueprint_compat=false, eternal_compat=false.
    // RunScreen sets j.n to a pseudorandom rank at add_to_deck time (line 178); self-destruct check at RunScreen:1089.
    "j_cry_blacklist" to JokerSpec(
        jokerMain = { s, ctx ->
            val rank = if (s.n == 0) 14 else s.n
            if (ctx.fullHand.any { it.id == rank } || ctx.heldHand.any { it.id == rank })
                Effect.Nullify
            else
                Effect.None
        },
    ),

    // ── missing vanilla jokers — batch 1 (flat / per-hand scaling / held-state) ────────────────────
    // Cavendish: X3 Mult always. Gros Michel: +15 Mult always (1-in-6 end-of-round extinction = lifecycle TODO).
    "j_cavendish"    to JokerSpec(jokerMain = { _, _ -> Effect.XMult(3.0) }),
    "j_gros_michel"  to JokerSpec(jokerMain = { _, _ -> Effect.Mult(15.0) }),
    // Ice Cream: +Chips starting at 100, −5 each hand played (reducer); floors at 0 (self-destruct = lifecycle TODO).
    "j_ice_cream"    to JokerSpec(
        initialState = FJokerState(chips = 100.0),
        reduce = { s, e -> if (e is GameEvent.HandScored) s.copy(chips = maxOf(0.0, s.chips - 5.0)) else s },
        jokerMain = { s, _ -> Effect.chipsOrNone(s.chips) },
    ),
    // Blackboard: X3 Mult if every card held in hand is a Spade or Club (an empty hand counts — vacuously true).
    "j_blackboard"   to JokerSpec(jokerMain = { _, ctx ->
        if (ctx.heldHand.all { it.isSuit(Suit.S, ctx.smeared) || it.isSuit(Suit.C, ctx.smeared) }) Effect.XMult(3.0) else Effect.None
    }),

    // ── missing vanilla jokers — batch 2: the "X Mult if hand contains <type>" family ──────────────
    "j_duo"    to JokerSpec(jokerMain = { _, ctx -> if (HandType.PAIR in ctx.pokerHands) Effect.XMult(2.0) else Effect.None }),
    "j_trio"   to JokerSpec(jokerMain = { _, ctx -> if (HandType.THREE_OF_A_KIND in ctx.pokerHands) Effect.XMult(3.0) else Effect.None }),
    "j_family" to JokerSpec(jokerMain = { _, ctx -> if (HandType.FOUR_OF_A_KIND in ctx.pokerHands) Effect.XMult(4.0) else Effect.None }),
    "j_order"  to JokerSpec(jokerMain = { _, ctx -> if (HandType.STRAIGHT in ctx.pokerHands) Effect.XMult(3.0) else Effect.None }),
    "j_tribe"  to JokerSpec(jokerMain = { _, ctx -> if (HandType.FLUSH in ctx.pokerHands) Effect.XMult(2.0) else Effect.None }),

    // ── missing vanilla jokers — batch 3: money-scaling (read ctx.money = G.GAME.dollars) ─────────
    "j_bull"        to JokerSpec(jokerMain = { _, ctx -> Effect.chipsOrNone(2.0 * ctx.money) }),         // +2 Chips per $1
    "j_bootstraps"  to JokerSpec(jokerMain = { _, ctx -> Effect.multOrNone(2.0 * (ctx.money / 5)) }),    // +2 Mult per $5

    // ── missing vanilla jokers — batch 4: deck-size / joker-slot scaling ──────────────────────────
    "j_erosion"  to JokerSpec(jokerMain = { _, ctx -> Effect.multOrNone(4.0 * maxOf(0, 52 - ctx.deckSize)) }),  // +4 Mult per card below 52
    "j_stencil"  to JokerSpec(jokerMain = { _, ctx ->
        // X1 Mult per empty Joker slot; Joker Stencil itself counts as an empty slot.
        val empty = ctx.jokerSlots - ctx.boardKeys.size + ctx.boardKeys.count { it == "j_stencil" }
        if (empty > 1) Effect.XMult(empty.toDouble()) else Effect.None
    }),

    // ── missing vanilla jokers — batch 5: discard-scaling reducers ────────────────────────────────
    // Yorick: +X1 Mult for every 23 cards discarded (n carries the running remainder).
    "j_yorick"  to JokerSpec(
        reduce = { s, e -> if (e is GameEvent.Discarded) {
            var n = s.n + e.cards.size; var x = s.x
            while (n >= 23) { x += 1.0; n -= 23 }
            s.copy(n = n, x = x)
        } else s },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),
    // Hit the Road: +X0.5 Mult per Jack discarded this round; resets to X1 at end of round.
    "j_hit_the_road"  to JokerSpec(
        reduce = { s, e -> when (e) {
            is GameEvent.Discarded -> s.copy(x = s.x + 0.5 * e.cards.count { it.rank == 11 })
            is GameEvent.RoundEnd  -> s.copy(x = 1.0)
            else                   -> s
        } },
        jokerMain = { s, _ -> if (s.x > 1.0) Effect.XMult(s.x) else Effect.None },
    ),
)
