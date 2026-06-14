package systems.balatro.game

import systems.balatro.engine.Entity
import systems.balatro.engine.World

/**
 * Scoring as composition. A joker does NOT override `calculate`; it REGISTERS an
 * Effect — data (which contexts it reacts to) plus a pure handler. Scoring a hand
 * visits, per context, only the jokers SUBSCRIBED to that context, in board order,
 * over a single reused Context object. No per-event table allocation (the old
 * ~340-tables-per-hand GC churn), no monkeypatched dispatch.
 *
 * Correctness contract: for every ported joker, the score this produces on a fixed
 * seed must equal the original LÖVE build's score (verified by score-oracle). The
 * registration order = original SMODS joker order; context identity is the single
 * reused object; big numbers pass through the BigValue type uncoerced.
 */

/** The points being accumulated. BigValue wraps the eventual Talisman/OmegaNum path. */
class Tally {
    var chips: BigValue = BigValue.ZERO
    var mult: BigValue = BigValue.ONE
    fun reset() { chips = BigValue.ZERO; mult = BigValue.ONE }
    fun score(): BigValue = chips * mult
}

/** The contexts a joker can react to. Closed set => a subscription is just a bitmask. */
enum class Ctx { BEFORE, INDIVIDUAL_SCORED, INDIVIDUAL_HELD, JOKER_MAIN, OTHER_JOKER, RETRIGGER, AFTER, END_OF_ROUND }

/**
 * The single, reused dispatch context. One instance per scoring run; its fields are
 * rewritten per visit, never reallocated. Because it is one stable object, any
 * effect comparing context identity still works — and there is nothing to GC.
 */
class Context {
    lateinit var phase: Ctx
    var scoredCard: Entity = 0          // the playing card entity being scored (INDIVIDUAL_*)
    var scoredPlaying: PlayingCard? = null  // its data (suit/rank/chips)
    var scoringCards: List<PlayingCard> = emptyList()  // the scoring cards only (kickers excluded)
    var playedCards: List<PlayingCard> = emptyList()   // the whole played hand (incl. non-scoring)
    var self: Entity = 0                // the joker whose handler is running
    var otherJoker: Entity = 0          // the board joker being offered (OTHER_JOKER pass)
    val tally = Tally()
    var retriggers: Int = 0             // an effect may request repeats of the current card
}

/** A joker's contribution. Pure: reads the context, mutates the tally, returns nothing. */
fun interface Effect { fun apply(world: World, ctx: Context) }

/**
 * The subscription index: ctx -> ordered list of (joker entity, effect). A context
 * dispatch walks ONE list, not every joker. Insertion order = board order =
 * deterministic, matching the original left-to-right scoring cascade.
 */
class Effects {
    private val byCtx = HashMap<Ctx, ArrayList<Pair<Entity, Effect>>>()

    /** A ported joker calls this once when it enters play. */
    fun register(joker: Entity, contexts: Set<Ctx>, effect: Effect) {
        for (c in contexts) byCtx.getOrPut(c) { ArrayList() }.add(joker to effect)
    }

    fun unregister(joker: Entity) {
        for (list in byCtx.values) list.removeAll { it.first == joker }
    }

    /** Visit only the subscribers of `phase`, in board order, over the reused ctx. */
    fun dispatch(world: World, ctx: Context, phase: Ctx) {
        val list = byCtx[phase] ?: return
        ctx.phase = phase
        for ((joker, effect) in list) { ctx.self = joker; effect.apply(world, ctx) }
    }

    /**
     * Run ONLY `joker`'s effect(s) for `phase`, with self set to it — the copy primitive
     * a blueprint joker uses to re-apply the joker beside it. Self is saved/restored so the
     * caller (whose own handler is mid-dispatch) sees no change to the reused ctx.
     */
    fun dispatchJoker(world: World, ctx: Context, phase: Ctx, joker: Entity) {
        val list = byCtx[phase] ?: return
        val saved = ctx.self
        ctx.phase = phase
        for ((j, effect) in list) if (j == joker) { ctx.self = joker; effect.apply(world, ctx) }
        ctx.self = saved
    }
}

/**
 * Scoring a played hand: the cascade, composed. This is the whole scoring loop —
 * no overrides, no event queue of closures referencing torn-down state (the rewind
 * crash class is structurally gone), just ordered context dispatch over pooled state.
 */
class ScoreRun(private val effects: Effects) {
    private val ctx = Context()

    fun scoreHand(world: World, played: List<PlayingCard>): BigValue {
        // Compose any active rank remaps (maximized &c.) into the effective rank hand
        // detection sees. Empty store => identity, so the no-remap path is unchanged.
        val remaps = ArrayList<(Int) -> Int>()
        world.store<RankMod>().each { _, m -> remaps.add(m.map) }
        val rankOf: (PlayingCard) -> Int = { c -> remaps.fold(c.rank) { r, m -> m(r) } }
        val (handType, scoring) = Hands.evaluate(played, rankOf)   // base chips/mult + the scoring cards
        ctx.tally.reset()
        ctx.tally.chips = BigValue.of(handType.baseChips)
        ctx.tally.mult = BigValue.of(handType.baseMult)
        ctx.scoringCards = scoring                                      // shape-aware jokers inspect the scoring cards
        ctx.playedCards = played                                        // ...or the full played hand (primus prime-check)
        effects.dispatch(world, ctx, Ctx.BEFORE)
        for (card in scoring) {
            ctx.scoredPlaying = card
            ctx.retriggers = 0                                          // subscribers may add repeats
            effects.dispatch(world, ctx, Ctx.RETRIGGER)
            repeat(1 + ctx.retriggers) {                               // each trigger re-scores the card whole
                ctx.tally.chips = ctx.tally.chips + BigValue.of(card.chips)
                effects.dispatch(world, ctx, Ctx.INDIVIDUAL_SCORED)
            }
        }
        effects.dispatch(world, ctx, Ctx.JOKER_MAIN)
        // The for-each-other-joker pass: every board joker is offered to the OTHER_JOKER
        // subscribers once, in board order (no self-exclusion — a joker is offered itself too).
        for (other in Board.order(world)) { ctx.otherJoker = other; effects.dispatch(world, ctx, Ctx.OTHER_JOKER) }
        effects.dispatch(world, ctx, Ctx.AFTER)
        return BigValue.of(kotlin.math.floor(ctx.tally.score().v))      // Balatro floors the final
    }
}
