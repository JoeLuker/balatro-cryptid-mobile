package systems.balatro.game

/**
 * Joker MANIFEST — the structure that kills the "scatter".
 *
 * Today a single joker is smeared across up to ~8 sites in two files: Score.kt's
 * individual / joker_main / before / other_joker when-blocks, plus RunScreen's buy-init,
 * scoreBank accumulator, discard loop, and end-of-round reset. One JokerSpec co-locates ALL of
 * it — which actually mirrors Lua's single `calculate(self, card, context)` MORE faithfully than
 * that split does.
 *
 * Principles:
 *  - Unix: the engine is the MECHANISM (a thin interpreter that dispatches by context and threads
 *    state); each spec is POLICY (one joker, defined in one place).
 *  - FP: scoring hooks are PURE — (FJokerState, Sctx) -> Fx? — they read context and return an
 *    effect VALUE, they never touch score state. State evolution is a single PURE reducer —
 *    (FJokerState, GameEvent) -> FJokerState — so a joker's whole state machine is one place, with
 *    no scattered in-place mutation. The mutable FJoker is bridged to/from the immutable FJokerState
 *    only by the engine (snapshot/restore); a spec never sees the mutable object.
 *
 * Migration is incremental: calcJoker and the run loop consult the manifest first and fall back to
 * the legacy when-blocks for un-migrated keys, so jokers move over one at a time, oracle-guarded.
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

/** Game events a joker's state reducer reacts to — each carries just enough to be a pure input. */
sealed interface GameEvent {
    /** Before-pass, once per hand, with the scoring context (scoringName / pokerHands known). */
    data class BeforeHand(val ctx: Sctx) : GameEvent
    /** A hand was scored and banked (run loop). */
    data class HandScored(val handType: HandType) : GameEvent
    /** Cards were discarded (run loop). */
    data class Discarded(val cards: List<PlayingCard>) : GameEvent
}

typealias ScoreHook = (self: FJokerState, ctx: Sctx) -> Fx?
typealias OtherJokerHook = (self: FJokerState, ctx: Sctx, other: FJoker) -> Fx?
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
    return when {
        ctx.jokerRetriggerCheck                  -> spec.retrigger?.invoke(self, ctx)
        ctx.individual && ctx.cardarea == "play" -> spec.individual?.invoke(self, ctx)
        ctx.held                                 -> spec.held?.invoke(self, ctx)
        ctx.otherJoker != null                   -> spec.otherJoker?.invoke(self, ctx, ctx.otherJoker!!)
        ctx.jokerMain                            -> spec.jokerMain?.invoke(self, ctx)
        else                                     -> null
    }
}

/**
 * The registry. Proof-of-concept: three jokers chosen to exercise the whole scatter —
 *  - stronghold : a pure conditional joker_main (score-time only).
 *  - bonk       : initial state + a before-pass reducer + an other-joker hook (was 4 sites, 2 files).
 *  - green_joker: two run-loop reducers (hand played / discarded) + a joker_main read (was 3 sites, 2 files).
 */
val JOKER_MANIFEST: Map<String, JokerSpec> = mapOf(
    "j_cry_stronghold" to JokerSpec(
        // +X5 Mult when the played hand contains cry_Bulwark (m.lua:5637 next(poker_hands[type])).
        jokerMain = { _, ctx -> if (HandType.CRY_BULWARK in ctx.pokerHands) Fx().apply { xMultMod = 5.0 } else null },
    ),
    "j_cry_bonk" to JokerSpec(
        // +6 Chips per board joker (x3 for Jolly jokers); +1 to that bonus every Pair played (m.lua:640-718).
        initialState = FJokerState(chips = 6.0, xc = 3.0),
        reduce = { s, e -> if (e is GameEvent.BeforeHand && e.ctx.scoringName == HandType.PAIR) s.copy(chips = s.chips + 1.0) else s },
        otherJoker = { s, _, oj ->
            val isJolly = oj.key == "j_jolly" || oj.key == "j_cry_jollysus" || oj.edition == "cry_m"
            val add = if (isJolly) s.chips * s.xc else s.chips
            if (add != 0.0) Fx().apply { chipMod = add } else null
        },
    ),
    "j_green_joker" to JokerSpec(
        // +1 Mult per hand played, -1 per discard (never below 0); the accumulated Mult is read in joker_main.
        reduce = { s, e -> when (e) {
            is GameEvent.HandScored -> s.copy(mult = s.mult + 1.0)
            is GameEvent.Discarded  -> s.copy(mult = maxOf(0.0, s.mult - 1.0))
            else -> s
        } },
        jokerMain = { s, _ -> if (s.mult > 0.0) Fx().apply { multMod = s.mult } else null },
    ),
)
