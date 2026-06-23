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
    /** A hand was scored and banked (run loop). */
    data class HandScored(val handType: HandType) : GameEvent
    /** Cards were discarded (run loop). */
    data class Discarded(val cards: List<PlayingCard>) : GameEvent
}

typealias ScoreHook = (self: FJokerState, ctx: Sctx) -> Effect
typealias OtherJokerHook = (self: FJokerState, ctx: Sctx, other: FJoker) -> Effect
typealias Reducer = (state: FJokerState, event: GameEvent) -> FJokerState

/** One joker's complete behaviour, co-located. Every hook optional — a joker fills only what it uses. */
data class JokerSpec(
    val initialState: FJokerState = FJokerState(),
    val jokerMain: ScoreHook? = null,        // context.joker_main
    val individual: ScoreHook? = null,        // context.individual, per scored played card
    val held: ScoreHook? = null,              // context.cardarea == hand
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
        ctx.individual && ctx.cardarea == "play" -> spec.individual?.invoke(self, ctx) ?: Effect.None
        ctx.held                                 -> spec.held?.invoke(self, ctx) ?: Effect.None
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
)
